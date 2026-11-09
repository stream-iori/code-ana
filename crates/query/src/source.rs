//! Query operation implementation over narrow read capabilities.
use super::*;

pub(super) fn run(
    r: &dyn SourceQueryReader,
    req: &Request,
    _evidence: &mut Evidence,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Vec<Record>> {
    let sources = if let Some(s) = r.manifest().files.iter().find(|s| s.path == req.selector) {
        vec![(s.clone(), None)]
    } else {
        resolve_scoped(r, req)?
            .into_iter()
            .map(|f| fact_source(r, &f))
            .collect::<Result<Vec<_>>>()?
    };
    snippets(r, req, sources, read_source)
}
pub(super) fn snippets(
    r: &dyn SourceContext,
    req: &Request,
    sources: Vec<(SourceUnit, Option<Span>)>,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    for (s, span) in sources {
        if r.charge(1, s.size).is_err() {
            break;
        }
        let bytes = read_source(&s)?;
        ensure!(
            hash(&bytes) == s.hash,
            codeana_model::DomainError::new(codeana_model::ErrorCode::SourceHashMismatch, "")
        );
        let positions = SourcePositions::new(std::str::from_utf8(&bytes)?, &|w, p| r.charge(w, p))?;
        let full_text = std::str::from_utf8(&bytes)?;
        let location = |start: u64, end: u64| -> Result<SourceRange> {
            let (bl, bc) = positions.position(full_text, start as usize, &|w, p| r.charge(w, p))?;
            let (el, ec) = positions.position(full_text, end as usize, &|w, p| r.charge(w, p))?;
            Ok(SourceRange {
                begin_line: Some(bl.into()),
                begin_column: Some(bc.into()),
                end_line: Some(el.into()),
                end_column: Some(ec.into()),
            })
        };
        let range = span.unwrap_or(Span {
            start: 0,
            end: bytes.len() as u64,
        });
        let bytes = bytes
            .get(range.start as usize..range.end as usize)
            .ok_or_else(|| {
                anyhow::anyhow!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::InvalidSourceSpan,
                    ""
                ))
            })?;
        let text = std::str::from_utf8(bytes)?;
        let chunk_limit = if req.max_output_bytes == 0 {
            4096
        } else {
            (req.max_output_bytes as usize)
                .saturating_sub(1800)
                .saturating_div(6)
                .clamp(16, 4096)
        };
        if text.is_empty() {
            records.push(Record::Source(SourceSnippet {
                range: Some(location(range.start, range.end)?),
                language: s.language.clone(),
                path: s.path.clone(),
                hash: s.hash.clone(),
                span: range.clone(),
                text: String::new(),
            }));
        }
        let mut offset = 0;
        while offset < text.len() {
            let mut end = (offset + chunk_limit).min(text.len());
            while !text.is_char_boundary(end) {
                end -= 1;
            }
            if r.charge(1, (end - offset + s.path.len() + s.hash.len()) as u64)
                .is_err()
            {
                return Ok(records);
            }
            records.push(Record::Source(SourceSnippet {
                range: Some(location(
                    range.start + offset as u64,
                    range.start + end as u64,
                )?),
                language: s.language.clone(),
                path: s.path.clone(),
                hash: s.hash.clone(),
                span: Span {
                    start: range.start + offset as u64,
                    end: range.start + end as u64,
                },
                text: text[offset..end].to_owned(),
            }));
            offset = end;
        }
    }
    Ok(records)
}
