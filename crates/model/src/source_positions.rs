use crate::{DomainError, ErrorCode};

/// Sparse original-source checkpoints; resolving a position scans at most 256 scalars.
pub struct SourcePositions {
    points: Vec<(usize, u32, u32)>,
}
impl SourcePositions {
    pub fn new(
        source: &str,
        admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
    ) -> Result<Self, DomainError> {
        admit(1, ((source.len() / 256 + 2) as u64).saturating_mul(64))?;
        let mut points = vec![(0, 1, 1)];
        let (mut line, mut column) = (1u32, 1u32);
        for (n, (offset, ch)) in source.char_indices().enumerate() {
            admit(1, 0)?;
            if n > 0 && n % 256 == 0 {
                points.push((offset, line, column));
            }
            advance(source, offset, ch, &mut line, &mut column)?;
        }
        Ok(Self { points })
    }
    pub fn position(
        &self,
        source: &str,
        offset: usize,
        admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
    ) -> Result<(u32, u32), DomainError> {
        if offset > source.len() || !source.is_char_boundary(offset) {
            return Err(invalid());
        }
        admit(self.points.len().checked_ilog2().unwrap_or(0) as u64 + 1, 0)?;
        let index = self.points.partition_point(|(byte, _, _)| *byte <= offset) - 1;
        let (begin, mut line, mut column) = self.points[index];
        for (at, ch) in source[begin..offset].char_indices() {
            admit(1, 0)?;
            advance(source, begin + at, ch, &mut line, &mut column)?;
        }
        Ok((line, column))
    }
}
fn invalid() -> DomainError {
    DomainError::new(ErrorCode::CommentSourceMapInvalid, "")
}
pub fn advance(
    source: &str,
    offset: usize,
    ch: char,
    line: &mut u32,
    column: &mut u32,
) -> Result<(), DomainError> {
    if ch == '\n' || ch == '\r' && source.as_bytes().get(offset + 1) != Some(&b'\n') {
        *line = line.checked_add(1).ok_or_else(invalid)?;
        *column = 1;
    } else {
        *column = column
            .checked_add(ch.len_utf16() as u32)
            .ok_or_else(invalid)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sparse_positions_preserve_utf16_and_all_line_endings() {
        let source = format!("{}😀中\r\nX\rY\nZ", "a".repeat(257));
        let admit = |_, _| Ok(());
        let positions = SourcePositions::new(&source, &admit).unwrap();
        let mut line = 1;
        let mut column = 1;
        for (offset, ch) in source.char_indices() {
            assert_eq!(
                positions.position(&source, offset, &admit).unwrap(),
                (line, column)
            );
            advance(&source, offset, ch, &mut line, &mut column).unwrap();
        }
        assert_eq!(
            positions.position(&source, source.len(), &admit).unwrap(),
            (4, 2)
        );
        assert!(positions.position(&source, 258, &admit).is_err());
        assert!(SourcePositions::new(&source, &|_, _| Err(invalid())).is_err());
    }
}
