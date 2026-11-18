use crate::{DomainError, ErrorCode};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SourceLineBreaks {
    Standard,
    EcmaScript,
}
impl SourceLineBreaks {
    pub fn for_language(language: &str) -> Self {
        match language {
            "javascript" | "typescript" => Self::EcmaScript,
            _ => Self::Standard,
        }
    }
}

/// Sparse original-source checkpoints; resolving a position scans at most 256 scalars.
pub struct SourcePositions {
    points: Vec<(usize, u32, u32)>,
    line_breaks: SourceLineBreaks,
}
impl SourcePositions {
    pub fn new(
        source: &str,
        line_breaks: SourceLineBreaks,
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
            advance(source, offset, ch, line_breaks, &mut line, &mut column)?;
        }
        Ok(Self {
            points,
            line_breaks,
        })
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
            advance(
                source,
                begin + at,
                ch,
                self.line_breaks,
                &mut line,
                &mut column,
            )?;
        }
        Ok((line, column))
    }

    pub fn offset(
        &self,
        source: &str,
        line: u32,
        column: u32,
        admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
    ) -> Result<usize, DomainError> {
        if line == 0 || column == 0 {
            return Err(DomainError::new(ErrorCode::SourceRangeInvalid, ""));
        }
        admit(self.points.len().checked_ilog2().unwrap_or(0) as u64 + 1, 0)?;
        let index = self
            .points
            .partition_point(|(_, l, c)| (*l, *c) <= (line, column))
            - 1;
        let (begin, mut current_line, mut current_column) = self.points[index];
        for (at, ch) in source[begin..].char_indices() {
            admit(1, 0)?;
            if (current_line, current_column) == (line, column) {
                return Ok(begin + at);
            }
            if (current_line, current_column) > (line, column) {
                return Err(DomainError::new(ErrorCode::SourceRangeColumn, ""));
            }
            advance(
                source,
                begin + at,
                ch,
                self.line_breaks,
                &mut current_line,
                &mut current_column,
            )?;
        }
        if (current_line, current_column) == (line, column) {
            return Ok(source.len());
        }
        Err(DomainError::new(
            if current_line < line {
                ErrorCode::SourceRangeLine
            } else {
                ErrorCode::SourceRangeColumn
            },
            "",
        ))
    }
}
fn invalid() -> DomainError {
    DomainError::new(ErrorCode::CommentSourceMapInvalid, "")
}
pub fn advance(
    source: &str,
    offset: usize,
    ch: char,
    line_breaks: SourceLineBreaks,
    line: &mut u32,
    column: &mut u32,
) -> Result<(), DomainError> {
    if ch == '\n'
        || ch == '\r' && source.as_bytes().get(offset + 1) != Some(&b'\n')
        || line_breaks == SourceLineBreaks::EcmaScript && matches!(ch, '\u{2028}' | '\u{2029}')
    {
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
        let positions = SourcePositions::new(&source, SourceLineBreaks::Standard, &admit).unwrap();
        let mut line = 1;
        let mut column = 1;
        for (offset, ch) in source.char_indices() {
            assert_eq!(
                positions.position(&source, offset, &admit).unwrap(),
                (line, column)
            );
            advance(
                &source,
                offset,
                ch,
                SourceLineBreaks::Standard,
                &mut line,
                &mut column,
            )
            .unwrap();
        }
        assert_eq!(
            positions.position(&source, source.len(), &admit).unwrap(),
            (4, 2)
        );
        assert!(positions.position(&source, 258, &admit).is_err());
        assert!(
            SourcePositions::new(&source, SourceLineBreaks::Standard, &|_, _| Err(invalid()))
                .is_err()
        );
    }

    #[test]
    fn ecma_unicode_separators_preserve_other_languages_and_sparse_boundaries() {
        let source = format!("{}\u{2028}needle\u{2029}😀\r\nend", "a".repeat(257));
        let offset = source.find("needle").unwrap();
        for language in ["javascript", "typescript", "java", "rust", "python", ""] {
            let policy = SourceLineBreaks::for_language(language);
            let positions = SourcePositions::new(&source, policy, &|_, _| Ok(())).unwrap();
            let expected = if policy == SourceLineBreaks::EcmaScript {
                (2, 1)
            } else {
                (1, 259)
            };
            assert_eq!(
                positions.position(&source, offset, &|_, _| Ok(())).unwrap(),
                expected,
                "{language}"
            );
            assert_eq!(
                positions
                    .offset(&source, expected.0, expected.1, &|_, _| Ok(()))
                    .unwrap(),
                offset,
                "{language}"
            );
            assert_eq!(
                positions
                    .position(&source, source.len(), &|_, _| Ok(()))
                    .unwrap(),
                if policy == SourceLineBreaks::EcmaScript {
                    (4, 4)
                } else {
                    (2, 4)
                }
            );
        }
    }
}
