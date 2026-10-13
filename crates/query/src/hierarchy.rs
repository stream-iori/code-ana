//! Directed, bounded hierarchy traversal. Scope selects the seed; descendants
//! can originate in any captured module, matching incoming-call semantics.
use super::*;

pub(super) fn adjacent(
    reader: &dyn GraphReader,
    ids: &[String],
    incoming: bool,
    kinds: &[&str],
) -> Result<Vec<Fact>> {
    let mut output = Vec::new();
    for batch in ids.chunks(500) {
        for kind in kinds {
            output.extend(select(
                reader,
                FactFilter {
                    kinds: vec![FactKind::Relation],
                    parents: if incoming { vec![] } else { batch.to_vec() },
                    targets: if incoming { batch.to_vec() } else { vec![] },
                    relation: Some((*kind).into()),
                    ..Default::default()
                },
            )?);
            ensure!(output.len() <= 100000, "HIERARCHY_WORK_BUDGET");
        }
    }
    output.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(output)
}

fn limit(evidence: &mut Evidence, name: &str) {
    evidence.enumeration = false;
    evidence.limits.push(name.into());
}

pub(super) fn instantiability(fact: &Fact) -> (&'static str, &'static str) {
    let modifier = |name| {
        fact.attributes
            .get("modifiers")
            .and_then(Value::as_array)
            .is_some_and(|items| items.iter().any(|item| item.as_str() == Some(name)))
    };
    match fact
        .attributes
        .get("element_kind")
        .and_then(Value::as_str)
        .unwrap_or("")
    {
        "INTERFACE" => ("no", "java.interface"),
        "ANNOTATION" | "ANNOTATION_TYPE" => ("no", "java.annotation"),
        "ENUM" => ("yes", "java.enum"),
        "RECORD" => ("yes", "java.record"),
        "ANONYMOUS_CLASS" => ("yes", "java.anonymous_class"),
        "CLASS" if modifier("abstract") => ("no", "java.abstract_class"),
        "CLASS" if modifier("final") => ("yes", "java.final_class"),
        "CLASS" => ("yes", "java.concrete_class"),
        _ => ("unknown", "java.kind_unknown"),
    }
}

pub(super) fn runtime(
    reader: &dyn GraphReader,
    req: &Request,
    roots: Vec<Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Value>> {
    let mut output = BTreeMap::new();
    let mut work = 0usize;
    let filter = if req.instantiability.is_empty() {
        "yes"
    } else {
        &req.instantiability
    };
    ensure!(req.relation.is_empty(), "RUNTIME_RELATION_NOT_SUPPORTED");
    let world = if req.analysis_world.is_empty() {
        "workspace-open"
    } else {
        &req.analysis_world
    };
    if world != "workspace-closed" {
        evidence.semantic = false;
        evidence.reasons.push("static_candidates_open_world".into());
    }
    for root in roots {
        ensure!(
            root.is_public_entity()
                && (root.attributes.get("entity_kind").and_then(Value::as_str) == Some("type")
                    || matches!(
                        root.attributes.get("element_kind").and_then(Value::as_str),
                        Some(
                            "CLASS"
                                | "INTERFACE"
                                | "ANNOTATION"
                                | "ANNOTATION_TYPE"
                                | "ENUM"
                                | "RECORD"
                                | "ANONYMOUS_CLASS"
                        )
                    )),
            "RUNTIME_IMPLEMENTATIONS_REQUIRES_TYPE"
        );
        let mut visited = BTreeSet::from([root.id.clone()]);
        let mut frontier = BTreeMap::from([(root.id, Vec::<String>::new())]);
        for depth in 1..=req.depth.max(1) {
            let ids = frontier.keys().cloned().collect::<Vec<_>>();
            if ids.is_empty() {
                break;
            }
            let edges = adjacent(reader, &ids, true, &["extends", "implements"])?;
            work += ids.len() + edges.len();
            if work > 100000 {
                limit(evidence, "work");
                return Ok(output.into_values().collect());
            }
            let mut next = BTreeMap::new();
            for edge in edges {
                if visited.contains(&edge.parent) {
                    continue;
                }
                if visited.len() > 10000 {
                    limit(evidence, "work");
                    return Ok(output.into_values().collect());
                }
                visited.insert(edge.parent.clone());
                let mut proof = frontier[&edge.target].clone();
                proof.push(edge.id);
                next.insert(edge.parent, proof);
            }
            let children = next.keys().cloned().collect::<Vec<_>>();
            for batch in children.chunks(500) {
                for fact in select(
                    reader,
                    FactFilter {
                        ids: batch.to_vec(),
                        ..Default::default()
                    },
                )? {
                    if !fact.is_public_entity() {
                        continue;
                    }
                    let (state, reason) = instantiability(&fact);
                    if filter != "all" && filter != state {
                        continue;
                    }
                    let mut record = serde_json::to_value(&fact)?;
                    record["runtime_implementation"] = json!({"instantiability":state,
                        "reason":reason,"world":world,"proof":next[&fact.id],"depth":depth});
                    output.entry(fact.id).or_insert(record);
                }
            }
            frontier = next;
        }
        if !frontier.is_empty() {
            let ids = frontier.keys().cloned().collect::<Vec<_>>();
            if adjacent(reader, &ids, true, &["extends", "implements"])?
                .iter()
                .any(|edge| !visited.contains(&edge.parent))
            {
                limit(evidence, "depth");
            }
        }
    }
    Ok(output.into_values().collect())
}

pub(super) fn relations(
    reader: &dyn GraphReader,
    req: &Request,
    roots: Vec<Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Value>> {
    let kinds: &[&str] = match req.operation.as_str() {
        "type-relations" if req.relation == "wires_possible" => &["wires_possible"],
        "type-relations" => &["extends", "implements"],
        _ => &["overrides", "implements_method"],
    };
    ensure!(
        req.relation.is_empty() || kinds.contains(&req.relation.as_str()),
        "HIERARCHY_RELATION_INVALID"
    );
    let selected = if req.relation.is_empty() {
        kinds.to_vec()
    } else {
        vec![req.relation.as_str()]
    };
    let incoming = req.direction == "in";
    let mut frontier = roots
        .into_iter()
        .map(|root| root.id)
        .collect::<BTreeSet<_>>();
    let mut visited = frontier.clone();
    let mut output = BTreeMap::new();
    let mut work = 0;
    for depth in 1..=if req.transitive { req.depth.max(1) } else { 1 } {
        if frontier.is_empty() {
            break;
        }
        let ids = frontier.iter().cloned().collect::<Vec<_>>();
        let edges = adjacent(reader, &ids, incoming, &selected)?;
        work += ids.len() + edges.len();
        if work > 100000 {
            limit(evidence, "work");
            break;
        }
        let mut next = BTreeSet::new();
        for edge in edges {
            if !incoming && !allowed(reader, req, &edge) {
                continue;
            }
            if output.len() >= 10000 && !output.contains_key(&edge.id) {
                limit(evidence, "work");
                return Ok(output.into_values().collect());
            }
            let endpoint = if incoming { &edge.parent } else { &edge.target };
            if visited.insert(endpoint.clone()) {
                next.insert(endpoint.clone());
            }
            if edge.relation == "wires_possible" {
                evidence.semantic = false;
                evidence.reasons.push(
                    if edge.attributes.get("via").and_then(Value::as_str)
                        == Some("xml_configuration")
                    {
                        "static_xml_configuration_open_world"
                    } else {
                        "static_injection_candidates_open_world"
                    }
                    .into(),
                );
            }
            if !edge.evidence_complete || !edge.declaration_resolved {
                evidence.semantic = false;
                evidence.reasons.push(format!("relation:{}", edge.id));
            }
            let mut record = serde_json::to_value(&edge)?;
            if req.transitive {
                record["depth"] = json!(depth);
            }
            output.entry(edge.id).or_insert(record);
        }
        frontier = next;
    }
    if req.transitive && !frontier.is_empty() {
        let ids = frontier.into_iter().collect::<Vec<_>>();
        if adjacent(reader, &ids, incoming, &selected)?
            .iter()
            .any(|edge| (incoming || allowed(reader, req, edge)) && !output.contains_key(&edge.id))
        {
            limit(evidence, "depth");
        }
    }
    Ok(output.into_values().collect())
}
