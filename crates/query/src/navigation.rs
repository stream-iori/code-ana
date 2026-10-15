//! Query operation implementation over narrow read capabilities.
use super::*;

pub(super) fn resolve(
    r: &dyn GraphAccess,
    req: &Request,
    _evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    Ok(values(resolve_scoped(r, req)?))
}

pub(super) fn structure(
    r: &dyn GraphAccess,
    req: &Request,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    let roots = resolve_scoped(r, req)?;
    let mut queue: VecDeque<_> = roots.into_iter().map(|f| (f, 0)).collect();
    let mut seen = BTreeSet::new();
    while !queue.is_empty() {
        let mut frontier = Vec::new();
        for _ in 0..500 {
            let Some((fact, depth)) = queue.pop_front() else {
                break;
            };
            if !seen.insert(fact.id.to_string()) {
                continue;
            }
            frontier.push((fact.id.to_string(), depth));
            if allowed(r, req, &fact) {
                records.push(fact.into());
            }
        }
        if seen.len() > 10000 || records.len() > 10000 {
            evidence.enumeration = false;
            evidence.limits.push("work".into());
            break;
        }
        if frontier.is_empty() {
            continue;
        }
        let depth_by_id: BTreeMap<_, _> = frontier.into_iter().collect();
        let children = select(
            r,
            FactFilter {
                kinds: vec![FactKind::Declaration, FactKind::Facet],
                parents: depth_by_id.keys().cloned().collect(),
                ..Default::default()
            },
        )?;
        for child in children.into_iter().filter(Fact::is_public_entity) {
            let depth = *depth_by_id.get(&child.parent).unwrap_or(&0);
            if depth >= req.depth.max(1) {
                evidence.enumeration = false;
                evidence.limits.push("depth".into());
            } else {
                queue.push_back((child, depth + 1));
            }
        }
    }
    Ok(records)
}

pub(super) fn occurrences(
    r: &dyn AnnotationQueryReader,
    req: &Request,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    let roots = resolve_scoped(r, req)?;
    let mut seen = BTreeSet::new();
    for f in roots {
        let hits = match req.operation {
            Operation::References => {
                let outgoing = req.direction == codeana_model::Direction::Outgoing;
                let base = FactFilter {
                    kinds: if req.relation.is_empty() {
                        vec![FactKind::Reference]
                    } else {
                        vec![FactKind::Reference, FactKind::Relation]
                    },
                    parent: outgoing.then(|| f.id.to_string()),
                    target: (!outgoing).then(|| f.id.to_string()),
                    ..Default::default()
                };
                if req.relation.is_empty() {
                    select(r, base)?
                } else {
                    let mut hits = Vec::new();
                    for relation in overview_relations(&req.relation)? {
                        hits.extend(select(
                            r,
                            FactFilter {
                                relation: Some(relation),
                                ..base.clone()
                            },
                        )?);
                    }
                    hits
                }
            }
            Operation::Annotations => {
                let direct = select(
                    r,
                    FactFilter {
                        kinds: vec![FactKind::Facet],
                        parent: Some(f.id.to_string()),
                        relation: Some("java.annotation".into()),
                        ..Default::default()
                    },
                )?;
                if req.meta {
                    annotations::expand(r, direct, evidence)?
                } else {
                    direct
                }
            }
            _ => {
                if req.direction == codeana_model::Direction::Incoming {
                    let targets = select(
                        r,
                        FactFilter {
                            kinds: vec![FactKind::CallTarget],
                            target: Some(f.id.to_string()),
                            ..Default::default()
                        },
                    )?;
                    let mut sites = Vec::new();
                    for chunk in targets.chunks(500) {
                        sites.extend(select(
                            r,
                            FactFilter {
                                kinds: vec![FactKind::CallSite],
                                ids: chunk.iter().map(|t| t.parent.clone()).collect(),
                                ..Default::default()
                            },
                        )?);
                    }
                    sites
                } else {
                    select(
                        r,
                        FactFilter {
                            kinds: vec![FactKind::CallSite],
                            parent: Some(f.id.to_string()),
                            ..Default::default()
                        },
                    )?
                }
            }
        };
        for f in hits {
            if seen.insert(f.id.to_string())
                && ((req.direction == codeana_model::Direction::Incoming
                    || (req.operation == codeana_model::Operation::References
                        && req.direction.is_empty()))
                    || allowed(r, req, &f))
            {
                records.push(f.into());
            }
        }
    }
    Ok(records)
}
