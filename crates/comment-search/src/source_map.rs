//! Map normalized body hits back to captured source, including non-linear escape mappings.
use crate::BodyHit;
use codeana_model::{CommentBodySegment, CommentHitRange, CommentRange, DomainError, ErrorCode};
use std::collections::{BTreeMap, BTreeSet};

fn invalid() -> DomainError {
    DomainError::new(ErrorCode::CommentSourceMapInvalid, "")
}

struct Segment {
    body_start: usize,
    body_end: usize,
    source_start: usize,
    source_end: usize,
    linear: bool,
}

/// Validate the complete map, then project only real source spans. Removed comment markers
/// are never highlighted. A non-linear segment (e.g. a Unicode escape) maps as a whole;
/// equal byte lengths alone do not establish a linear mapping.
pub fn map_hits(
    body: &str,
    map: &[CommentBodySegment],
    source: &str,
    hits: &[BodyHit],
    admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
) -> Result<Vec<CommentHitRange>, DomainError> {
    admit(
        (body.len() as u64)
            .saturating_add(map.len() as u64)
            .saturating_add(1),
        (map.len() as u64).saturating_mul(128),
    )?;
    let mut validated = Vec::with_capacity(map.len());
    let mut body_end = 0;
    let mut source_end = 0;
    for segment in map {
        admit(1, 0)?;
        let (bs, be, ss, se) = bounds(segment)?;
        if bs != body_end
            || bs >= be
            || ss < source_end
            || ss >= se
            || body.get(bs..be).is_none()
            || source.get(ss..se).is_none()
        {
            return Err(invalid());
        }
        body_end = be;
        source_end = se;
        validated.push(Segment {
            body_start: bs,
            body_end: be,
            source_start: ss,
            source_end: se,
            linear: body[bs..be] == source[ss..se],
        });
    }
    if body_end != body.len() {
        return Err(invalid());
    }
    let mut spans = Vec::new();
    let mut boundaries = BTreeSet::new();
    for hit in hits {
        admit(map.len().checked_ilog2().unwrap_or(0) as u64 + 1, 0)?;
        if hit.start >= hit.end || body.get(hit.start..hit.end).is_none() {
            return Err(invalid());
        }
        let first = validated.partition_point(|segment| segment.body_end <= hit.start);
        for segment in &validated[first..] {
            let (bs, be, ss, se) = (
                segment.body_start,
                segment.body_end,
                segment.source_start,
                segment.source_end,
            );
            if bs >= hit.end {
                break;
            }
            admit(1, 512)?; // spans, boundary set, coordinates, result, vector growth.
            let start = bs.max(hit.start);
            let end = be.min(hit.end);
            let (source_start, source_end) = if segment.linear {
                (ss + start - bs, ss + end - bs)
            } else {
                (ss, se)
            };
            spans.push((start, end, source_start, source_end));
            boundaries.insert(source_start);
            boundaries.insert(source_end);
        }
    }
    // Compute requested coordinates in one forward walk, rather than rescanning a large
    // source line for every occurrence. CRLF uses LF as the line boundary, like source views.
    let mut positions = BTreeMap::new();
    let mut chars = source.char_indices().peekable();
    let (mut line, mut column) = (1u32, 1u32);
    for offset in boundaries {
        while chars.peek().is_some_and(|(index, _)| *index < offset) {
            admit(1, 0)?;
            let (at, ch) = chars.next().ok_or_else(invalid)?;
            crate::positions::advance(source, at, ch, &mut line, &mut column)?;
        }
        positions.insert(offset, (line, column));
    }
    let mut result = Vec::new();
    for (start, end, source_start, source_end) in spans {
        admit(1, 0)?;
        let (begin_line, begin_column) = positions[&source_start];
        let (end_line, end_column) = positions[&source_end];
        result.push(CommentHitRange {
            body_start: start as u64,
            body_end: end as u64,
            source: CommentRange {
                byte_start: source_start as u64,
                byte_end: source_end as u64,
                begin_line,
                begin_column,
                end_line,
                end_column,
            },
        });
    }
    admit(0, 0)?;
    Ok(result)
}

fn bounds(segment: &CommentBodySegment) -> Result<(usize, usize, usize, usize), DomainError> {
    Ok((
        usize::try_from(segment.body_start).map_err(|_| invalid())?,
        usize::try_from(segment.body_end).map_err(|_| invalid())?,
        usize::try_from(segment.source_start).map_err(|_| invalid())?,
        usize::try_from(segment.source_end).map_err(|_| invalid())?,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::{ResourceBudget, ResourceLimits};

    fn segment(bs: usize, be: usize, ss: usize, se: usize) -> CommentBodySegment {
        CommentBodySegment {
            body_start: bs as u64,
            body_end: be as u64,
            source_start: ss as u64,
            source_end: se as u64,
        }
    }

    #[test]
    fn stripped_markers_crlf_emoji_and_utf16_columns_map_to_capture() {
        let source = "/** 库存😀\r\n * count */";
        let body = "库存😀\r\ncount";
        let count = source.find("count").unwrap();
        let map = [
            segment(0, 12, 4, 16),
            segment(12, body.len(), count, count + 5),
        ];
        let budget = ResourceBudget::new(ResourceLimits::default());
        let hits = map_hits(
            body,
            &map,
            source,
            &[
                BodyHit { start: 0, end: 10 },
                BodyHit { start: 12, end: 17 },
            ],
            &|w, b| budget.charge(w, b),
        )
        .unwrap();
        assert_eq!(
            (hits[0].source.byte_start, hits[0].source.byte_end),
            (4, 14)
        );
        assert_eq!(
            (
                hits[0].source.begin_line,
                hits[0].source.begin_column,
                hits[0].source.end_column
            ),
            (1, 5, 9)
        );
        assert_eq!(
            (
                hits[1].source.begin_line,
                hits[1].source.begin_column,
                hits[1].source.end_column
            ),
            (2, 4, 9)
        );
        assert_eq!(
            &source[hits[1].source.byte_start as usize..hits[1].source.byte_end as usize],
            "count"
        );
    }

    #[test]
    fn escape_segments_are_not_linearly_interpolated_and_gaps_are_not_highlighted() {
        let source = "// \\u5e93*\\u5b58";
        let body = "库存";
        let map = [segment(0, 3, 3, 9), segment(3, 6, 10, 16)];
        let budget = ResourceBudget::new(ResourceLimits::default());
        let hits = map_hits(
            body,
            &map,
            source,
            &[BodyHit { start: 0, end: 6 }],
            &|w, b| budget.charge(w, b),
        )
        .unwrap();
        assert_eq!(hits.len(), 2);
        assert_eq!((hits[0].source.byte_start, hits[0].source.byte_end), (3, 9));
        assert_eq!(
            (hits[1].source.byte_start, hits[1].source.byte_end),
            (10, 16)
        );
        assert_eq!((hits[0].body_start, hits[0].body_end), (0, 3));
    }

    #[test]
    fn malformed_maps_and_non_character_boundaries_are_rejected() {
        let budget = ResourceBudget::new(ResourceLimits::default());
        let admit = |w, b| budget.charge(w, b);
        for map in [
            vec![],
            vec![segment(1, 3, 0, 3)],
            vec![segment(0, 1, 0, 1)],
            vec![segment(0, 3, 0, 4)],
        ] {
            assert_eq!(
                map_hits("库", &map, "库", &[], &admit).unwrap_err().code,
                ErrorCode::CommentSourceMapInvalid
            );
        }
        let map = [segment(0, 3, 0, 3)];
        assert!(map_hits("库", &map, "库", &[BodyHit { start: 0, end: 1 }], &admit).is_err());
        let cancelled = |_, _| Err(DomainError::new(ErrorCode::Cancelled, ""));
        assert_eq!(
            map_hits("库", &map, "库", &[], &cancelled)
                .unwrap_err()
                .code,
            ErrorCode::Cancelled
        );
    }
}
