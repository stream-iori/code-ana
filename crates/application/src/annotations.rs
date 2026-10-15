//! User-content binding assessment over a fixed revision.
use super::*;
fn binding(
    anchor: Option<&AnnotationAnchor>,
    reader: Option<&dyn GraphAccess>,
) -> Result<AnnotationBinding> {
    let Some(anchor) = anchor else {
        return Ok(AnnotationBinding::Unbound);
    };
    let Some(reader) = reader else {
        return Ok(AnnotationBinding::Unavailable);
    };
    let facts = reader.select(&FactFilter {
        ids: vec![anchor.fact_id.to_string()],
        ..Default::default()
    })?;
    if facts.is_empty() {
        return Ok(AnnotationBinding::Orphaned);
    }
    if facts.len() != 1 || facts[0].owner.world != anchor.world {
        return Ok(AnnotationBinding::Ambiguous);
    }
    let actual = reader
        .manifest()
        .files
        .iter()
        .find(|s| s.path == facts[0].source)
        .map(|s| s.hash.as_str())
        .unwrap_or("");
    Ok(if actual == anchor.source_hash {
        AnnotationBinding::Bound
    } else {
        AnnotationBinding::Ambiguous
    })
}
impl Engine {
    pub(super) fn annotate(&self, req: &Request, project: &str, checkout: &str) -> Result<Output> {
        let selected = if let Some(id) = req.revision.strip_prefix("snapshot:") {
            let snapshot: history::Snapshot = self.store.object("snapshot", id, project)?;
            ensure!(
                !snapshot.revision.is_empty(),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::AnalysisSnapshotRequired,
                    ""
                )
            );
            Some(snapshot.revision)
        } else if !req.revision.is_empty() {
            Some(req.revision.clone())
        } else {
            self.store.latest(project, checkout).ok()
        };
        let anchor = if req.name == "add" {
            let rid = selected
                .as_deref()
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::AnnotationRevisionRequired,
                    "",
                ))?;
            let reader = self.store.read(rid)?;
            ensure!(
                reader.revision().project == project,
                codeana_model::DomainError::new(codeana_model::ErrorCode::ProjectMismatch, "")
            );
            let fact = codeana_query::resolve(&reader, &req.selector, true)?
                .pop()
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::AnnotationSymbolRequired,
                    "",
                ))?;
            let source_hash = reader
                .manifest()
                .files
                .iter()
                .find(|s| s.path == fact.source)
                .map(|s| s.hash.clone())
                .unwrap_or_default();
            Some(AnnotationAnchor {
                revision: rid.into(),
                fact_id: fact.id,
                world: fact.owner.world,
                source: fact.source,
                source_hash,
                span: fact.span,
            })
        } else {
            None
        };
        let mut record = self.store.annotation(AnnotationCommand {
            project,
            action: req.name.parse()?,
            id: &req.base,
            symbol: &req.selector,
            text: &req.text,
            version: &req.expected_revision,
            anchor: anchor.as_ref(),
        })?;
        if req.name == "list" {
            let reader = if let Some(id) = selected.as_deref() {
                match self.store.read(id) {
                    Ok(r) => Some(r),
                    Err(e) if req.revision.is_empty() => {
                        let _ = e;
                        None
                    }
                    Err(e) => return Err(e),
                }
            } else {
                None
            };
            if let AnnotationResult::List(rows) = &mut record {
                for row in rows {
                    row.binding = Some(binding(
                        row.anchor.as_ref(),
                        reader.as_ref().map(|r| r as &dyn GraphAccess),
                    )?);
                    row.binding_revision = selected.clone();
                }
            }
        }
        Ok(Output {
            records: vec![Record::display(&record)?],
            evidence: Evidence::complete(),
            ..Default::default()
        })
    }
}
