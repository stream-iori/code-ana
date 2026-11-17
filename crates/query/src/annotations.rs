//! Frozen AnnotationQueryService semantics: occurrence-local, cycle-safe paths.
use super::*;
use anyhow::Context;

pub(super) fn expand(
    reader: &dyn AnnotationQueryReader,
    direct: Vec<Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Fact>> {
    let mut catalogues =
        BTreeMap::<AnnotationContext, BTreeMap<String, Vec<AnnotationMetaEdge>>>::new();
    let mut output = Vec::new();
    for direct in direct {
        let name = direct
            .attributes
            .annotation
            .as_ref()
            .map(String::as_str)
            .filter(|name| !name.is_empty())
            .or_else(|| direct.attributes.raw_name.as_ref().map(String::as_str))
            .unwrap_or("")
            .to_owned();
        if reader.charge(1, retained_size(&direct)?).is_err() {
            break;
        }
        let mut catalogue = if direct.declaration_resolved {
            let context = reader
                .annotation_context(&direct)
                .map_err(anyhow::Error::new)?;
            Some(catalogues.entry(context).or_default())
        } else {
            None
        };
        let mut queue = VecDeque::from([(direct.clone(), vec![name], 0_u32)]);
        while let Some((mut fact, path, depth)) = queue.pop_front() {
            if output.len() >= 10000 {
                evidence.enumeration = false;
                evidence.limits.push("work".into());
                return Ok(output);
            }
            let path_bytes = path.iter().map(String::len).sum::<usize>() + path.len();
            if reader.charge(1, path_bytes as u64).is_err() {
                return Ok(output);
            }
            fact.attributes.direct = (depth == 0).into();
            fact.attributes.meta_depth = depth.into();
            fact.attributes.via = path.join(">").into();
            let unresolved_direct = depth == 0 && !direct.declaration_resolved;
            if unresolved_direct {
                fact.evidence_complete = false;
                fact.status = "possible".into();
                evidence.semantic = false;
                evidence
                    .reasons
                    .push("annotation_resolution_incomplete".into());
            }
            let path_complete = fact.evidence_complete;
            output.push(fact);
            if unresolved_direct {
                continue;
            }
            let catalogue = catalogue.as_deref_mut().with_context(|| {
                DomainError::new(
                    ErrorCode::GraphColumnType,
                    "direct annotation context missing",
                )
            })?;
            let annotation = path.last().unwrap();
            if !catalogue.contains_key(annotation) {
                let edges = reader
                    .annotation_meta_edges(std::slice::from_ref(annotation), &direct)
                    .map_err(anyhow::Error::new)?;
                if reader.resource_exhausted() {
                    return Ok(output);
                }
                catalogue.insert(annotation.clone(), edges);
            }
            for edge in &catalogue[annotation] {
                let meta = &edge.meta_annotation;
                let attributes = &edge.attributes;
                if path.contains(meta) {
                    continue;
                }
                if depth >= 16 {
                    evidence.enumeration = false;
                    evidence.limits.push("depth".into());
                    continue;
                }
                if queue.len() + output.len() >= 10000 {
                    evidence.enumeration = false;
                    evidence.limits.push("work".into());
                    continue;
                }
                if reader
                    .charge(
                        1,
                        retained_size(&direct)?
                            + path.iter().map(|s| s.len() as u64).sum::<u64>()
                            + meta.len() as u64,
                    )
                    .is_err()
                {
                    return Ok(output);
                }
                let mut next = direct.clone();
                let mut next_path = path.clone();
                next_path.push(meta.clone());
                next.id = format!(
                    "annotation-meta-{}",
                    hash(format!("{}\n{}", direct.id, next_path.join(">")).as_bytes())
                )
                .into();
                next.attributes.merge_annotation_meta(attributes);
                next.attributes.annotation = meta.clone().into();
                next.attributes.values = Field::Null;
                next.attributes.resolved_type = Field::Missing;
                let resolved = attributes.resolution.as_ref().map(String::as_str) == Some("exact");
                next.evidence_complete = path_complete && resolved;
                next.status = if next.evidence_complete {
                    "complete"
                } else {
                    "possible"
                }
                .into();
                if resolved {
                    next.attributes.resolved_type = meta.clone().into();
                } else {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push("meta_annotation_resolution_incomplete".into());
                }
                queue.push_back((next, next_path, depth + 1));
            }
        }
    }
    Ok(output)
}
