//! Frozen AnnotationQueryService semantics: occurrence-local, cycle-safe paths.
use super::*;

pub(super) fn expand(
    reader: &dyn AnnotationQueryReader,
    direct: Vec<Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Fact>> {
    let mut catalogue = BTreeMap::<String, Vec<AnnotationMetaEdge>>::new();
    let mut output = Vec::new();
    for direct in direct {
        let name = direct
            .attributes
            .annotation
            .as_ref()
            .map(String::as_str)
            .or_else(|| direct.attributes.raw_name.as_ref().map(String::as_str))
            .unwrap_or("")
            .to_owned();
        let mut queue = VecDeque::from([(direct.clone(), vec![name], 0_u32)]);
        while let Some((mut fact, path, depth)) = queue.pop_front() {
            if output.len() >= 10000 {
                evidence.enumeration = false;
                evidence.limits.push("work".into());
                return Ok(output);
            }
            fact.attributes.direct = (depth == 0).into();
            fact.attributes.meta_depth = depth.into();
            fact.attributes.via = path.join(">").into();
            output.push(fact);
            let annotation = path.last().unwrap();
            if !catalogue.contains_key(annotation) {
                let edges = reader
                    .annotation_meta_edges(std::slice::from_ref(annotation))
                    .map_err(anyhow::Error::new)?;
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
                let mut next = direct.clone();
                let mut next_path = path.clone();
                next_path.push(meta.clone());
                next.id = format!(
                    "annotation-meta-{}",
                    hash(format!("{}\n{}", direct.id, next_path.join(">")).as_bytes())
                );
                next.attributes.merge_annotation_meta(attributes);
                next.attributes.annotation = meta.clone().into();
                next.attributes.values = Field::Null;
                next.attributes.resolved_type = Field::Missing;
                let resolved = attributes.resolution.as_ref().map(String::as_str) == Some("exact");
                next.evidence_complete &= resolved;
                next.status = if resolved { "complete" } else { "possible" }.into();
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
