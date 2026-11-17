//! Resolve query context from an actual direct occurrence, never a type name.
use super::*;

impl ReadContext {
    pub(crate) fn annotation_unit(&self, direct: &Fact) -> Result<i64> {
        self.charge(1, 64)?;
        let invalid = || {
            DomainError::new(
                ErrorCode::GraphColumnType,
                "invalid direct annotation identity",
            )
        };
        let (encoded, ordinal) = direct
            .id
            .as_str()
            .strip_prefix("annotation:")
            .and_then(|s| s.rsplit_once(':'))
            .with_context(invalid)?;
        ensure!(
            encoded.len() == 32 && encoded.bytes().all(|b| b.is_ascii_hexdigit()),
            invalid()
        );
        ensure!(
            !ordinal.is_empty()
                && ordinal.len() <= 19
                && ordinal.bytes().all(|b| b.is_ascii_digit())
                && (ordinal.len() == 1 || !ordinal.starts_with('0')),
            invalid()
        );
        let mut hash = [0u8; 16];
        for (index, byte) in hash.iter_mut().enumerate() {
            *byte = u8::from_str_radix(&encoded[index * 2..index * 2 + 2], 16)
                .map_err(|_| invalid())?;
        }
        let ordinal = ordinal.parse::<i64>().map_err(|_| invalid())?;
        ensure!(
            ordinal >= 0 && direct.kind == FactKind::Facet && direct.relation == "java.annotation",
            invalid()
        );
        let mut query = self.connection.prepare("SELECT DISTINCT a.unit_pk FROM graph_annotations a
          JOIN graph_nodes n ON n.node_pk=a.node_id LEFT JOIN source_files f ON f.file_pk=a.source_file
          WHERE a.fact_hash=? AND a.fact_ordinal=? AND n.id=? AND coalesce(f.path,'')=? AND a.producer_id=?
          AND (a.world=? OR (a.world='' AND coalesce(f.path,'')='')) LIMIT 2")?;
        let mut rows = query.query(params![
            hash.as_slice(),
            ordinal,
            direct.parent,
            direct.source,
            direct.owner.producer,
            direct.owner.world.as_str()
        ])?;
        let unit = rows
            .next()?
            .with_context(|| {
                DomainError::new(ErrorCode::GraphColumnType, "direct annotation not found")
            })?
            .get(0)?;
        ensure!(
            rows.next()?.is_none(),
            DomainError::new(
                ErrorCode::GraphWorldOwnershipConflict,
                "direct annotation has ambiguous unit ownership"
            )
        );
        Ok(unit)
    }
}
