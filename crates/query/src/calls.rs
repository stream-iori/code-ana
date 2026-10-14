//! Query operation implementation over narrow read capabilities.
use super::*;

pub(super) fn trace(
    r: &dyn GraphAccess,
    req: &Request,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    ensure!(
        !req.target.is_empty(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::TraceTargetRequired, "")
    );
    let roots = resolve_scoped(r, req)?;
    ensure!(
        roots.len() == 1,
        codeana_model::DomainError::new(codeana_model::ErrorCode::TraceUniqueStartRequired, "")
    );
    let mut target_request = req.clone();
    target_request.selector = req.target.clone();
    target_request.module.clear();
    target_request.scope.clear();
    target_request.world.clear();
    let targets = resolve_scoped(r, &target_request)?;
    ensure!(
        targets.len() == 1,
        codeana_model::DomainError::new(codeana_model::ErrorCode::TraceUniqueTargetRequired, "")
    );
    let start = roots[0].id.clone();
    let target = targets[0].id.clone();
    let mut visited = BTreeSet::from([start.clone()]);
    let mut predecessor: BTreeMap<String, (String, CallRelation)> = BTreeMap::new();
    let mut frontier = vec![start.clone()];
    let mut reached = start == target;
    let mut work = 0usize;
    for depth in 0..=req.depth.max(1) {
        if reached || frontier.is_empty() {
            break;
        }
        let mut next = BTreeSet::new();
        for batch in frontier.chunks(500) {
            let edges = call_edges_many(r, batch, req.direction == "in", evidence)?;
            work += batch.len() + edges.len();
            if work > 100000 {
                evidence.enumeration = false;
                evidence.limits.push("work".into());
                break;
            }
            for ((from, to), sites) in edges {
                let (previous, node) = if req.direction == "in" {
                    (&to, &from)
                } else {
                    (&from, &to)
                };
                if visited.contains(node) {
                    continue;
                }
                if depth == req.depth.max(1) {
                    evidence.enumeration = false;
                    evidence.limits.push("depth".into());
                    continue;
                }
                if visited.len() >= 10000 {
                    evidence.enumeration = false;
                    evidence.limits.push("nodes".into());
                    continue;
                }
                visited.insert(node.clone());
                if node == &target {
                    reached = true;
                }
                predecessor.insert(
                    node.clone(),
                    (
                        previous.clone(),
                        CallRelation {
                            from: from.clone(),
                            to: to.clone(),
                            sites,
                            kind: "resolved_call".into(),
                            depth: None,
                        },
                    ),
                );
                next.insert(node.clone());
            }
        }
        frontier = next.into_iter().collect();
        if work > 100000 {
            break;
        }
    }
    if reached {
        let mut nodes = vec![target.clone()];
        let mut hops = Vec::new();
        let mut cursor = target.clone();
        while cursor != start {
            let (previous, hop) = predecessor.get(&cursor).ok_or_else(|| {
                anyhow::anyhow!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::TracePathState,
                    ""
                ))
            })?;
            hops.push(hop.clone());
            nodes.push(previous.clone());
            cursor = previous.clone();
        }
        nodes.reverse();
        hops.reverse();
        records.push(Record::Trace(TracePath {
            from: start,
            to: target,
            nodes,
            hops,
        }));
    }
    Ok(records)
}

pub(super) fn run(
    r: &dyn GraphAccess,
    req: &Request,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    let roots = resolve_scoped(r, req)?;
    let mut visited = BTreeSet::new();
    let mut emitted = BTreeSet::new();
    let mut frontier: Vec<_> = roots.into_iter().map(|f| f.id).collect();
    let mut work = 0;
    'layers: for depth in 0..req.depth.max(1) {
        frontier.retain(|id| visited.insert(id.clone()));
        if frontier.is_empty() {
            break;
        }
        let mut next = BTreeSet::new();
        for batch in frontier.chunks(500) {
            let edges = call_edges_many(r, batch, req.direction == "in", evidence)?;
            work += batch.len() + edges.len();
            if work > 100000 || visited.len() > 10000 || records.len() > 10000 {
                evidence.enumeration = false;
                evidence.limits.push("work".into());
                break 'layers;
            }
            for ((from, to), sites) in edges {
                if depth + 1 < req.depth.max(1) {
                    next.insert(if req.direction == "in" {
                        from.clone()
                    } else {
                        to.clone()
                    });
                }
                if emitted.insert((from.clone(), to.clone())) {
                    records.push(Record::Call(CallRelation {
                        from,
                        to,
                        sites,
                        kind: "resolved_call".into(),
                        depth: Some(depth + 1),
                    }));
                }
            }
        }
        frontier = next.into_iter().collect();
    }
    Ok(records)
}
