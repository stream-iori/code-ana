//! Query semantics operate on a fixed read port, independent of SQLite and RPC.
use anyhow::{Result, bail, ensure};
use codeana_model::*;
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
fn select(r: &dyn FactReader, f: FactFilter) -> Result<Vec<Fact>> {
    r.select(&f).map_err(anyhow::Error::msg)
}
pub const OPERATIONS: &[&str] = &[
    "resolve",
    "search-code",
    "structure",
    "calls",
    "call-sites",
    "references",
    "source",
    "annotations",
    "type-relations",
    "callable-relations",
    "runtime-implementations",
    "dispatch",
    "trace",
    "overview",
    "search-text",
    "pipeline",
];
pub fn preflight(request: &Request) -> Result<()> {
    ensure!(request.depth <= 32, "DEPTH_BUDGET: maximum 32");
    ensure!(request.max_records <= 10000, "RECORD_BUDGET: maximum 10000");
    ensure!(request.stages.len() <= 16, "PIPELINE_STAGE_BUDGET");
    ensure!(
        request.direction.is_empty() || matches!(request.direction.as_str(), "in" | "out"),
        "INVALID_DIRECTION"
    );
    if request.operation == "pipeline" {
        ensure!(!request.stages.is_empty(), "PIPELINE_REQUIRED");
        let mut ty = "start";
        for (i, s) in request.stages.iter().enumerate() {
            ensure!(s.depth <= 32, "DEPTH_BUDGET");
            ensure!(
                OPERATIONS.contains(&s.operation.as_str()) && s.operation != "pipeline",
                "UNSUPPORTED_STAGE: {}",
                s.operation
            );
            if i > 0 {
                ensure!(
                    !matches!(
                        s.operation.as_str(),
                        "resolve" | "search-code" | "overview" | "search-text"
                    ),
                    "PIPELINE_STAGE_DOES_NOT_CONSUME_INPUT"
                );
                ensure!(
                    matches!(ty, "entity" | "site"),
                    "PIPELINE_TYPE_MISMATCH: {ty} -> {}",
                    s.operation
                );
            }
            ty = match s.operation.as_str() {
                "source" | "search-text" => "text",
                "calls" | "type-relations" | "callable-relations" | "trace" => "edge",
                "call-sites" | "references" => "site",
                _ => "entity",
            };
        }
    }
    Ok(())
}
pub fn resolve(r: &dyn FactReader, selector: &str, unique: bool) -> Result<Vec<Fact>> {
    ensure!(!selector.is_empty(), "SELECTOR_REQUIRED");
    let exact = select(
        r,
        FactFilter {
            ids: vec![selector.into()],
            ..Default::default()
        },
    )?;
    let facts = if exact.is_empty() {
        select(
            r,
            FactFilter {
                kinds: vec![FactKind::Declaration, FactKind::ExternalSymbol],
                name: Some(selector.into()),
                ..Default::default()
            },
        )?
    } else {
        exact
    };
    ensure!(!facts.is_empty(), "SYMBOL_NOT_FOUND: {selector}");
    ensure!(
        !unique || facts.len() == 1,
        "AMBIGUOUS_SELECTOR: {selector}"
    );
    Ok(facts)
}
fn coverage(r: &dyn FactReader, req: &Request) -> Result<Evidence> {
    let mut e = Evidence::complete();
    if (!req.scope.is_empty()
        && req.scope != "ALL"
        && !r.manifest().files.iter().any(|s| s.scope == req.scope))
        || (!req.world.is_empty() && !r.manifest().files.iter().any(|s| s.world == req.world))
        || (!req.module.is_empty() && !r.manifest().files.iter().any(|s| s.module == req.module))
    {
        e.semantic = false;
        e.enumeration = false;
        e.reasons.push("selection_not_captured".into());
    }

    let cov = select(
        r,
        FactFilter {
            kinds: vec![FactKind::Coverage],
            ..Default::default()
        },
    )?;
    for c in cov {
        if !req.world.is_empty() && c.owner.world != req.world {
            continue;
        }
        if !c.evidence_complete || c.status != "complete" {
            e.semantic = false;
            e.reasons
                .push(format!("coverage:{}:{}", c.source, c.status));
        }
    }
    Ok(e)
}
fn allowed(r: &dyn FactReader, req: &Request, f: &Fact) -> bool {
    if !req.world.is_empty() && f.owner.world != req.world {
        return false;
    }
    if req.module.is_empty() && (req.scope.is_empty() || req.scope == "ALL") {
        return true;
    }
    r.manifest().files.iter().any(|s| {
        s.path == f.source
            && (req.module.is_empty() || s.module == req.module)
            && (req.scope.is_empty() || req.scope == "ALL" || s.scope == req.scope)
    })
}
fn values(facts: impl IntoIterator<Item = Fact>) -> Vec<Value> {
    facts
        .into_iter()
        .map(|f| serde_json::to_value(f).unwrap())
        .collect()
}
fn call_edges(
    r: &dyn FactReader,
    id: &str,
    incoming: bool,
    evidence: &mut Evidence,
) -> Result<BTreeMap<(String, String), Vec<String>>> {
    let mut edges = BTreeMap::<(String, String), Vec<String>>::new();
    if incoming {
        let targets = select(
            r,
            FactFilter {
                kinds: vec![FactKind::CallTarget],
                target: Some(id.into()),
                ..Default::default()
            },
        )?;
        for chunk in targets.chunks(500) {
            let sites = select(
                r,
                FactFilter {
                    kinds: vec![FactKind::CallSite],
                    ids: chunk.iter().map(|t| t.parent.clone()).collect(),
                    ..Default::default()
                },
            )?;
            let by_id: BTreeMap<_, _> = sites.into_iter().map(|s| (s.id.clone(), s)).collect();
            for t in chunk {
                if t.status != "resolved" || !t.declaration_resolved {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push(format!("unresolved_binding:{}", t.id));
                    continue;
                }
                if !t.evidence_complete {
                    evidence.semantic = false;
                    evidence.reasons.push(format!("binding:{}", t.id));
                }
                if let Some(s) = by_id.get(&t.parent) {
                    edges
                        .entry((s.parent.clone(), t.target.clone()))
                        .or_default()
                        .push(s.id.clone());
                } else {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push(format!("missing_call_site:{}", t.parent));
                }
            }
        }
    } else {
        let sites = select(
            r,
            FactFilter {
                kinds: vec![FactKind::CallSite],
                parent: Some(id.into()),
                ..Default::default()
            },
        )?;
        for chunk in sites.chunks(500) {
            let by_id: BTreeMap<_, _> = chunk.iter().map(|s| (s.id.as_str(), s)).collect();
            let mut resolved_sites = BTreeSet::new();
            let targets = select(
                r,
                FactFilter {
                    kinds: vec![FactKind::CallTarget],
                    parents: chunk.iter().map(|s| s.id.clone()).collect(),
                    ..Default::default()
                },
            )?;
            for t in targets {
                if t.status != "resolved" || !t.declaration_resolved {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push(format!("unresolved_binding:{}", t.id));
                    continue;
                }
                if !t.evidence_complete {
                    evidence.semantic = false;
                    evidence.reasons.push(format!("binding:{}", t.id));
                }
                if let Some(s) = by_id.get(t.parent.as_str()) {
                    resolved_sites.insert(s.id.as_str());
                    edges
                        .entry((s.parent.clone(), t.target.clone()))
                        .or_default()
                        .push(s.id.clone());
                }
            }
            for site in chunk {
                if !resolved_sites.contains(site.id.as_str()) {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push(format!("missing_resolved_target:{}", site.id));
                }
            }
        }
    }
    Ok(edges)
}
pub fn run(
    r: &dyn FactReader,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output> {
    preflight(req)?;
    let mut evidence = coverage(r, req)?;
    let mut records = Vec::new();
    if req.operation == "pipeline" {
        let mut input: Vec<Fact> = Vec::new();
        for (i, stage) in req.stages.iter().enumerate() {
            let mut s = req.clone();
            s.operation = stage.operation.clone();
            s.direction = stage.direction.clone();
            s.relation = stage.relation.clone();
            s.depth = stage.depth;
            s.unique = stage.unique;
            s.stages.clear();
            s.max_records = 10000;
            let selectors = if i == 0 {
                vec![stage.selector.clone()]
            } else {
                input.iter().map(|f| f.id.clone()).collect()
            };
            records.clear();
            for selector in selectors {
                s.selector = selector;
                let out = run(r, &s, read_source)?;
                evidence.merge(&out.evidence);
                records.extend(out.records);
                ensure!(records.len() <= 10000, "PIPELINE_WORK_BUDGET");
            }
            input = records
                .iter()
                .filter_map(|v| serde_json::from_value(v.clone()).ok())
                .collect();
        }
    } else {
        match req.operation.as_str() {
            "resolve" => {
                records = values(
                    resolve(r, &req.selector, req.unique)?
                        .into_iter()
                        .filter(|f| allowed(r, req, f)),
                );
            }
            "search-code" => {
                records = values(
                    select(
                        r,
                        FactFilter {
                            kinds: vec![FactKind::Declaration, FactKind::ExternalSymbol],
                            ..Default::default()
                        },
                    )?
                    .into_iter()
                    .filter(|f| {
                        allowed(r, req, f)
                            && (f.name.contains(&req.selector)
                                || f.signature.contains(&req.selector))
                    }),
                );
            }
            "overview" => {
                let mut counts = BTreeMap::<String, usize>::new();
                for f in select(r, FactFilter::default())?
                    .into_iter()
                    .filter(|f| allowed(r, req, f))
                {
                    *counts.entry(f.kind.name().into()).or_default() += 1;
                }
                records.push(json!({"counts":counts,"sources":r.manifest().files.len(),"profile":r.revision().profile}));
            }
            "source" => {
                let sources = if let Some(s) =
                    r.manifest().files.iter().find(|s| s.path == req.selector)
                {
                    vec![(s.clone(), None)]
                } else {
                    resolve(r, &req.selector, req.unique)?
                        .into_iter()
                        .map(|f| {
                            ensure!(
                                f.attributes.get("source_available") != Some(&json!(false)),
                                "SOURCE_UNAVAILABLE: {}",
                                f.id
                            );
                            let source = r
                                .manifest()
                                .files
                                .iter()
                                .find(|s| s.path == f.source)
                                .cloned();
                            source.map(|s| (s, f.span)).ok_or_else(|| {
                                anyhow::anyhow!("SOURCE_UNAVAILABLE: {} origin={}", f.id, f.origin)
                            })
                        })
                        .collect::<Result<Vec<_>>>()?
                };
                for (s, span) in sources {
                    let bytes = read_source(&s)?;
                    ensure!(hash(&bytes) == s.hash, "SOURCE_HASH_MISMATCH");
                    let range = span.unwrap_or(Span {
                        start: 0,
                        end: bytes.len() as u64,
                    });
                    let bytes = bytes
                        .get(range.start as usize..range.end as usize)
                        .ok_or_else(|| anyhow::anyhow!("INVALID_SOURCE_SPAN"))?;
                    let text = std::str::from_utf8(bytes)?;
                    let chunk_limit = if req.max_output_bytes == 0 {
                        4096
                    } else {
                        (req.max_output_bytes as usize)
                            .saturating_sub(1800)
                            .clamp(64, 4096)
                    };
                    if text.is_empty() {
                        records.push(json!({"path":s.path,"hash":s.hash,"span":range,"text":""}));
                    }
                    let mut offset = 0;
                    while offset < text.len() {
                        let mut end = (offset + chunk_limit).min(text.len());
                        while !text.is_char_boundary(end) {
                            end -= 1;
                        }
                        records.push(json!({"path":s.path,"hash":s.hash,"span":{"start":range.start+offset as u64,"end":range.start+end as u64},"text":&text[offset..end]}));
                        offset = end;
                    }
                }
            }
            "search-text" => {
                ensure!(!req.selector.is_empty(), "TEXT_PATTERN_REQUIRED");
                for s in &r.manifest().files {
                    if !req.scope.is_empty() && req.scope != "ALL" && s.scope != req.scope {
                        continue;
                    }
                    let bytes = read_source(s)?;
                    ensure!(hash(&bytes) == s.hash, "SOURCE_HASH_MISMATCH");
                    if let Ok(text) = std::str::from_utf8(&bytes) {
                        for (i, line) in text.lines().enumerate() {
                            if line.contains(&req.selector) {
                                records.push(
                                    json!({"path":s.path,"line":i+1,"text":line,"hash":s.hash}),
                                );
                                ensure!(records.len() <= MAX_FACTS, "TEXT_WORK_BUDGET");
                            }
                        }
                    }
                }
            }
            "structure" => {
                let roots = resolve(r, &req.selector, req.unique)?;
                let mut queue: VecDeque<_> = roots.into_iter().map(|f| (f, 0)).collect();
                let mut seen = BTreeSet::new();
                while !queue.is_empty() {
                    let mut frontier = Vec::new();
                    for _ in 0..500 {
                        let Some((fact, depth)) = queue.pop_front() else {
                            break;
                        };
                        if !seen.insert(fact.id.clone()) {
                            continue;
                        }
                        if allowed(r, req, &fact) {
                            records.push(serde_json::to_value(&fact)?);
                        }
                        frontier.push((fact.id, depth));
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
                            kinds: vec![FactKind::Declaration],
                            parents: depth_by_id.keys().cloned().collect(),
                            ..Default::default()
                        },
                    )?;
                    for child in children {
                        let depth = *depth_by_id.get(&child.parent).unwrap_or(&0);
                        if depth >= req.depth.max(1) {
                            evidence.enumeration = false;
                            evidence.limits.push("depth".into());
                        } else {
                            queue.push_back((child, depth + 1));
                        }
                    }
                }
            }
            "references" | "call-sites" | "annotations" => {
                let roots = resolve(r, &req.selector, req.unique)?;
                let mut seen = BTreeSet::new();
                for f in roots {
                    let hits = match req.operation.as_str() {
                        "references" => select(
                            r,
                            FactFilter {
                                kinds: vec![FactKind::Reference],
                                target: Some(f.id),
                                ..Default::default()
                            },
                        )?,
                        "annotations" => select(
                            r,
                            FactFilter {
                                kinds: vec![FactKind::Facet],
                                parent: Some(f.id),
                                relation: Some("java.annotation".into()),
                                ..Default::default()
                            },
                        )?,
                        _ => {
                            if req.direction == "in" {
                                let targets = select(
                                    r,
                                    FactFilter {
                                        kinds: vec![FactKind::CallTarget],
                                        target: Some(f.id),
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
                                        parent: Some(f.id),
                                        ..Default::default()
                                    },
                                )?
                            }
                        }
                    };
                    for f in hits {
                        if seen.insert(f.id.clone()) && allowed(r, req, &f) {
                            records.push(serde_json::to_value(f)?);
                        }
                    }
                }
            }
            "calls" | "trace" => {
                let roots = resolve(r, &req.selector, req.unique)?;
                let mut visited = BTreeSet::new();
                let mut emitted = BTreeSet::new();
                let mut q: VecDeque<_> = roots.into_iter().map(|f| (f.id, 0)).collect();
                let depth = if req.operation == "calls" {
                    1
                } else {
                    req.depth.max(1)
                };
                while let Some((id, d)) = q.pop_front() {
                    if !visited.insert(id.clone()) {
                        continue;
                    }
                    if visited.len() > 10000 || records.len() > 10000 {
                        evidence.enumeration = false;
                        evidence.limits.push("work".into());
                        break;
                    }
                    let edges = call_edges(r, &id, req.direction == "in", &mut evidence)?;
                    for ((from, to), sites) in edges {
                        if d >= depth {
                            evidence.enumeration = false;
                            evidence.limits.push("depth".into());
                            continue;
                        }
                        if emitted.insert((from.clone(), to.clone())) {
                            records.push(json!({"from":from,"to":to,"sites":sites,"kind":"resolved_call","depth":d+1}));
                        }
                        if req.operation == "trace" {
                            q.push_back((if req.direction == "in" { from } else { to }, d + 1));
                        }
                    }
                }
            }
            "type-relations" | "callable-relations" | "runtime-implementations" | "dispatch" => {
                let roots = resolve(r, &req.selector, req.unique)?;
                let mut seen = BTreeSet::new();
                for root in roots {
                    if req.operation == "dispatch" {
                        ensure!(
                            root.kind == FactKind::CallSite,
                            "DISPATCH_REQUIRES_CALL_SITE"
                        );
                        let targets = select(
                            r,
                            FactFilter {
                                kinds: vec![FactKind::CallTarget],
                                parent: Some(root.id.clone()),
                                ..Default::default()
                            },
                        )?;
                        if targets.is_empty() {
                            evidence.semantic = false;
                            evidence
                                .reasons
                                .push(format!("missing_dispatch_targets:{}", root.id));
                        }
                        for t in targets {
                            if t.status != "resolved"
                                || !t.declaration_resolved
                                || t.target.is_empty()
                            {
                                evidence.semantic = false;
                                evidence
                                    .reasons
                                    .push(format!("unresolved_binding:{}", t.id));
                                records.push(json!({"call_site":root.id,"binding":t.status,"proof":[t.id],"possible":false,"world":t.owner.world}));
                                continue;
                            }
                            if !t.evidence_complete {
                                evidence.semantic = false;
                                evidence.reasons.push(format!("binding:{}", t.id));
                            }
                            records.push(json!({"call_site":root.id,"target":t.target,"binding":t.status,"proof":[t.id],"possible":true,"world":t.owner.world}));
                            let mut expanded = BTreeSet::new();
                            let mut queue = VecDeque::from([(t.target, vec![t.id], 0u32)]);
                            while let Some((target, proof, depth)) = queue.pop_front() {
                                if !expanded.insert(target.clone()) {
                                    continue;
                                }
                                if expanded.len() > 10000 || records.len() > 10000 {
                                    evidence.enumeration = false;
                                    evidence.limits.push("work".into());
                                    break;
                                }
                                let relations = select(
                                    r,
                                    FactFilter {
                                        kinds: vec![FactKind::Relation],
                                        target: Some(target),
                                        ..Default::default()
                                    },
                                )?;
                                for rel in relations.into_iter().filter(|rel| {
                                    rel.relation == "overrides" && allowed(r, req, rel)
                                }) {
                                    if depth >= req.depth.max(1) {
                                        evidence.enumeration = false;
                                        evidence.limits.push("depth".into());
                                        continue;
                                    }
                                    let mut path = proof.clone();
                                    path.push(rel.id.clone());
                                    records.push(json!({"call_site":root.id,"target":rel.parent,"proof":path,"possible":true,"world":rel.owner.world}));
                                    queue.push_back((rel.parent, path, depth + 1));
                                }
                            }
                        }
                        evidence.semantic = false;
                        evidence.reasons.push("open_world_dispatch".into());
                    } else {
                        let incoming =
                            req.direction == "in" || req.operation == "runtime-implementations";
                        let relations = select(
                            r,
                            FactFilter {
                                kinds: vec![FactKind::Relation],
                                parent: (!incoming).then(|| root.id.clone()),
                                target: incoming.then(|| root.id.clone()),
                                ..Default::default()
                            },
                        )?;
                        for rel in relations {
                            let kinds = match req.operation.as_str() {
                                "type-relations" => ["extends", "implements"].as_slice(),
                                "callable-relations" => {
                                    ["overrides", "implements_method"].as_slice()
                                }
                                _ => ["implements", "extends", "overrides"].as_slice(),
                            };
                            if kinds.contains(&rel.relation.as_str())
                                && (req.relation.is_empty() || req.relation == rel.relation)
                                && allowed(r, req, &rel)
                                && seen.insert(rel.id.clone())
                            {
                                records.push(serde_json::to_value(rel)?);
                            }
                        }
                        if req.operation == "runtime-implementations" {
                            evidence.semantic = false;
                            evidence.reasons.push("static_candidates_open_world".into());
                        }
                    }
                }
            }
            _ => bail!("UNSUPPORTED_OPERATION: {}", req.operation),
        }
    }
    if req.operation == "source" || req.stages.last().is_some_and(|s| s.operation == "source") {
        records.sort_by_cached_key(|v| {
            (
                v["path"].as_str().unwrap_or("").to_owned(),
                v["span"]["start"].as_u64().unwrap_or(0),
            )
        });
    } else {
        records.sort_by_cached_key(|v| v.to_string());
    }
    if records.len() > 10000 {
        records.truncate(10000);
        evidence.enumeration = false;
        evidence.limits.push("work".into());
    }
    evidence.limits.sort();
    evidence.limits.dedup();
    evidence.reasons.sort();
    evidence.reasons.dedup();
    Ok(Output {
        revision: r.revision().id.clone(),
        producer: r.revision().producer.clone(),
        records,
        evidence,
        cursor: String::new(),
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    struct MemoryReader {
        revision: Revision,
        facts: Vec<Fact>,
    }
    impl FactReader for MemoryReader {
        fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
            Ok(self
                .facts
                .iter()
                .filter(|f| {
                    (filter.ids.is_empty() || filter.ids.contains(&f.id))
                        && (filter.parents.is_empty() || filter.parents.contains(&f.parent))
                        && (filter.kinds.is_empty() || filter.kinds.contains(&f.kind))
                        && filter.parent.as_ref().is_none_or(|v| v == &f.parent)
                        && filter.target.as_ref().is_none_or(|v| v == &f.target)
                })
                .cloned()
                .collect())
        }
        fn manifest(&self) -> &Manifest {
            &self.revision.manifest
        }
        fn revision(&self) -> &Revision {
            &self.revision
        }
    }
    #[test]
    fn unresolved_call_is_not_a_confirmed_candidate() {
        let owner = json!({"world":"world","producer":"mock","kind":"source","id":"x"});
        let fact = |id: &str, kind: &str, parent: &str, status: &str| {
            serde_json::from_value::<Fact>(json!({"id":id,"kind":kind,"owner":owner,"origin":"mock","parent":parent,"status":status})).unwrap()
        };
        let reader = MemoryReader {
            revision: Revision {
                id: "rev".into(),
                target: "t".into(),
                project: "p".into(),
                checkout: "c".into(),
                manifest: Manifest::default(),
                profile: "profile".into(),
                producer: "mock".into(),
                created: 0,
            },
            facts: vec![
                fact("main", "declaration", "", ""),
                fact("site", "call_site", "main", "unresolved"),
                fact("binding", "call_target", "site", "unresolved"),
            ],
        };
        let calls = run(
            &reader,
            &Request {
                operation: "calls".into(),
                selector: "main".into(),
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert!(calls.records.is_empty());
        assert!(!calls.evidence.semantic);
        let dispatch = run(
            &reader,
            &Request {
                operation: "dispatch".into(),
                selector: "site".into(),
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(dispatch.records[0]["possible"], false);
        assert!(!dispatch.evidence.semantic);
    }
    #[test]
    fn invalid_pipeline_fails_before_read() {
        let r = Request {
            operation: "pipeline".into(),
            stages: vec![
                Stage {
                    operation: "source".into(),
                    selector: "a".into(),
                    ..Default::default()
                },
                Stage {
                    operation: "calls".into(),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        assert!(preflight(&r).is_err());
    }
}
