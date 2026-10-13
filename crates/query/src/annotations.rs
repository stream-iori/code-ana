//! Frozen AnnotationQueryService semantics: occurrence-local, cycle-safe paths.
use super::*;

pub(super) fn expand(
    reader: &dyn GraphReader,
    direct: Vec<Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Fact>> {
    let mut catalogue = BTreeMap::<String, Vec<AnnotationMetaEdge>>::new();
    let mut output = Vec::new();
    for direct in direct {
        let name = direct
            .attributes
            .get("annotation")
            .and_then(Value::as_str)
            .or_else(|| direct.attributes.get("raw_name").and_then(Value::as_str))
            .unwrap_or("")
            .to_owned();
        let mut queue = VecDeque::from([(direct.clone(), vec![name], 0_u32)]);
        while let Some((mut fact, path, depth)) = queue.pop_front() {
            if output.len() >= 10000 {
                evidence.enumeration = false;
                evidence.limits.push("work".into());
                return Ok(output);
            }
            fact.attributes.insert("direct".into(), json!(depth == 0));
            fact.attributes.insert("meta_depth".into(), json!(depth));
            fact.attributes.insert("via".into(), json!(path.join(">")));
            output.push(fact);
            let annotation = path.last().unwrap();
            if !catalogue.contains_key(annotation) {
                let edges = reader
                    .annotation_meta_edges(std::slice::from_ref(annotation))
                    .map_err(anyhow::Error::msg)?;
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
                next.attributes.extend(attributes.clone());
                next.attributes.insert("annotation".into(), json!(meta));
                next.attributes.insert("values".into(), Value::Null);
                next.attributes.remove("resolved_type");
                let resolved =
                    attributes.get("resolution").and_then(Value::as_str) == Some("exact");
                next.evidence_complete &= resolved;
                next.status = if resolved { "complete" } else { "possible" }.into();
                if resolved {
                    next.attributes.insert("resolved_type".into(), json!(meta));
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
