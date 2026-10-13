//! Query semantics operate on a fixed read port, independent of SQLite and RPC.
use anyhow::{Result, bail, ensure};
mod annotations;
mod dispatch;
mod hierarchy;
mod text;
use codeana_model::*;
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use text::text_matcher;
pub use text::{stream_text, stream_text_candidates};
fn select(r: &dyn GraphReader, f: FactFilter) -> Result<Vec<Fact>> {
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
fn overview_relations(value: &str) -> Result<Vec<String>> {
    if value.is_empty() {
        return Ok(Vec::new());
    }
    let mut selected = BTreeSet::new();
    for item in value.split(',').map(str::trim) {
        let relations: &[&str] = match item {
            "type" | "REFERENCES" => &["type_reference", "reflection_lookup"],
            "reads" | "READS" => &["reads"],
            "writes" | "WRITES" => &["writes"],
            "config" => &[
                "defined_by",
                "config",
                "unresolved_config",
                "parent_bean",
                "config_member",
                "binds_to",
                "injects",
                "wires_possible",
                "handles_possible",
            ],
            "all" => &[
                "type_reference",
                "reflection_lookup",
                "reads",
                "writes",
                "defined_by",
                "config",
                "unresolved_config",
                "parent_bean",
                "config_member",
                "binds_to",
                "injects",
                "wires_possible",
                "handles_possible",
            ],
            "calls" | "CALLS" => &["calls"],
            "INHERITS" => &["extends"],
            "IMPLEMENTS" => &["implements"],
            "DEFINED_BY" => &["defined_by"],
            "XML_REFERS_TO" => &["config", "unresolved_config"],
            "PARENT_BEAN" => &["parent_bean"],
            "BINDS_TO" => &["binds_to", "config_member"],
            "INJECTS" => &["injects"],
            "WIRES" => &["wires_possible"],
            "HANDLES" => &["handles_possible"],
            "extends" | "implements" | "type_reference" | "reflection_lookup" | "defined_by"
            | "unresolved_config" | "parent_bean" | "config_member" | "binds_to" | "injects"
            | "wires_possible" | "handles_possible" => {
                selected.insert(item.to_owned());
                continue;
            }
            _ => bail!("OVERVIEW_RELATION_INVALID: {item}"),
        };
        selected.extend(relations.iter().map(|relation| (*relation).to_owned()));
    }
    Ok(selected.into_iter().collect())
}
fn hierarchy_options(operation: &str, instantiability: &str, transitive: bool) -> Result<()> {
    ensure!(
        instantiability.is_empty() || operation == "runtime-implementations",
        "INSTANTIABILITY_REQUIRES_RUNTIME_IMPLEMENTATIONS"
    );
    ensure!(
        matches!(instantiability, "" | "yes" | "unknown" | "all"),
        "INVALID_INSTANTIABILITY"
    );
    ensure!(
        !transitive || matches!(operation, "type-relations" | "callable-relations"),
        "TRANSITIVE_REQUIRES_HIERARCHY_RELATIONS"
    );
    Ok(())
}
pub fn preflight(request: &Request) -> Result<()> {
    dispatch::options(
        &request.operation,
        &request.algorithm,
        &request.analysis_world,
    )?;
    hierarchy_options(
        &request.operation,
        &request.instantiability,
        request.transitive,
    )?;
    ensure!(
        !request.meta || request.operation == "annotations",
        "META_REQUIRES_ANNOTATIONS"
    );
    if request.operation == "references" && !request.relation.is_empty() {
        let relations = overview_relations(&request.relation)?;
        ensure!(
            relations
                .iter()
                .all(|r| !matches!(r.as_str(), "calls" | "extends" | "implements")),
            "REFERENCE_RELATION_INVALID"
        );
    }
    if request.operation == "overview" && request.overview_section == "dependencies" {
        overview_relations(&request.relation)?;
    }
    if !request.overview_section.is_empty() || request.overview_depth > 0 {
        ensure!(
            request.operation == "overview",
            "OVERVIEW_SECTION_OPERATION"
        );
        ensure!(
            matches!(
                request.overview_section.as_str(),
                "" | "project" | "modules" | "packages" | "dependencies"
            ),
            "OVERVIEW_SECTION_INVALID"
        );
        ensure!(request.overview_depth <= 128, "OVERVIEW_DEPTH_BUDGET");
    }
    ensure!(
        request.text_context <= 100,
        "TEXT_CONTEXT_BUDGET: maximum 100"
    );
    if request.text_regex || request.text_ignore_case || request.text_context > 0 {
        ensure!(
            request.operation == "search-text"
                || (request.operation == "pipeline"
                    && request
                        .stages
                        .first()
                        .is_some_and(|s| s.operation == "search-text")),
            "TEXT_OPTIONS_REQUIRE_SEARCH_TEXT"
        );
    }
    if request.operation == "search-text" {
        text_matcher(request)?;
    }
    ensure!(
        request.package.is_empty() || request.package_prefix.is_empty(),
        "PACKAGE_FILTER_CONFLICT"
    );
    ensure!(request.paths.len() <= 64, "PATH_FILTER_BUDGET");
    for path in &request.paths {
        ensure!(
            !path.starts_with('/')
                && !path.contains('\\')
                && path.split('/').all(|part| !matches!(part, "" | "." | "..")),
            "INVALID_PATH_FILTER"
        );
        globset::GlobBuilder::new(path)
            .literal_separator(true)
            .backslash_escape(false)
            .build()?;
    }
    ensure!(
        request.visibility.is_empty()
            || matches!(
                request.visibility.as_str(),
                "public" | "protected" | "private" | "package" | "package-private"
            ),
        "INVALID_VISIBILITY"
    );
    ensure!(request.depth <= 32, "DEPTH_BUDGET: maximum 32");
    ensure!(request.max_records <= 10000, "RECORD_BUDGET: maximum 10000");
    ensure!(request.stages.len() <= 16, "PIPELINE_STAGE_BUDGET");
    ensure!(
        request.direction.is_empty()
            || matches!(request.direction.as_str(), "in" | "out")
            || (request.operation == "overview" && request.direction == "both"),
        "INVALID_DIRECTION"
    );
    if request.operation == "pipeline" {
        ensure!(!request.stages.is_empty(), "PIPELINE_REQUIRED");
        let mut ty = "start";
        for (i, s) in request.stages.iter().enumerate() {
            dispatch::options(&s.operation, &s.algorithm, &s.analysis_world)?;
            hierarchy_options(&s.operation, &s.instantiability, s.transitive)?;
            ensure!(
                !s.meta || s.operation == "annotations",
                "META_REQUIRES_ANNOTATIONS"
            );
            ensure!(
                s.direction.is_empty()
                    || matches!(s.direction.as_str(), "in" | "out")
                    || (s.operation == "overview" && s.direction == "both"),
                "INVALID_STAGE_DIRECTION"
            );
            if s.operation == "references" && !s.relation.is_empty() {
                let selected = overview_relations(&s.relation)?;
                ensure!(
                    selected
                        .iter()
                        .all(|r| !matches!(r.as_str(), "calls" | "extends" | "implements")),
                    "REFERENCE_RELATION_INVALID"
                );
            }
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
                        "search-code" | "overview" | "search-text"
                    ),
                    "PIPELINE_STAGE_DOES_NOT_CONSUME_INPUT"
                );
                ensure!(
                    matches!(ty, "entity" | "site") || (ty == "edge" && !s.endpoint.is_empty()),
                    "PIPELINE_TYPE_MISMATCH: {ty} -> {}",
                    s.operation
                );
                ensure!(s.selector.is_empty(), "PIPELINE_SELECTOR_OVERRIDES_INPUT");
            }
            ensure!(
                s.endpoint.is_empty()
                    || (i > 0
                        && matches!(ty, "edge" | "site")
                        && matches!(
                            s.endpoint.as_str(),
                            "caller" | "callee" | "parent" | "target" | "site"
                        )),
                "PIPELINE_INVALID_ENDPOINT"
            );
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
pub fn resolve(r: &dyn GraphReader, selector: &str, unique: bool) -> Result<Vec<Fact>> {
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
                kinds: vec![
                    FactKind::Declaration,
                    FactKind::ExternalSymbol,
                    FactKind::Facet,
                ],
                name: Some(selector.into()),
                ..Default::default()
            },
        )?
        .into_iter()
        .filter(Fact::is_public_entity)
        .collect()
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
fn scope_filter(req: &Request) -> FactFilter {
    FactFilter {
        world: (!req.world.is_empty()).then(|| req.world.clone()),
        module: (!req.module.is_empty()).then(|| req.module.clone()),
        scope: (!req.scope.is_empty() && req.scope != "ALL").then(|| req.scope.clone()),
        ..Default::default()
    }
}
fn selector_filter(r: &dyn GraphReader, req: &Request) -> Result<FactFilter> {
    let mut filter = scope_filter(req);
    filter.name = (!req.name.is_empty()).then(|| req.name.clone());
    filter.signature = (!req.signature.is_empty()).then(|| req.signature.clone());
    filter.package = (!req.package.is_empty()).then(|| req.package.clone());
    filter.package_prefix = (!req.package_prefix.is_empty()).then(|| req.package_prefix.clone());
    filter.entity_kind = (!req.entity_kind.is_empty()).then(|| req.entity_kind.clone());
    filter.annotation = (!req.annotation.is_empty()).then(|| req.annotation.clone());
    filter.visibility = (!req.visibility.is_empty()).then(|| req.visibility.clone());
    filter.synthetic = req.synthetic;
    if !req.paths.is_empty() {
        let patterns = req
            .paths
            .iter()
            .map(|p| {
                globset::GlobBuilder::new(p)
                    .literal_separator(true)
                    .backslash_escape(false)
                    .build()
                    .map(|g| g.compile_matcher())
            })
            .collect::<std::result::Result<Vec<_>, _>>()?;
        filter.sources = r
            .manifest()
            .files
            .iter()
            .filter(|source| {
                patterns
                    .iter()
                    .any(|pattern| pattern.is_match(&source.path))
            })
            .map(|s| s.path.clone())
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        if filter.sources.is_empty() {
            filter.source = Some("\0no-captured-source".into());
        }
    }
    Ok(filter)
}
fn resolve_scoped(r: &dyn GraphReader, req: &Request) -> Result<Vec<Fact>> {
    ensure!(
        !req.selector.is_empty() || !req.signature.is_empty(),
        "SELECTOR_REQUIRED"
    );
    let selector = if req.selector.is_empty() {
        &req.signature
    } else {
        &req.selector
    };
    let mut filter = selector_filter(r, req)?;
    filter.ids = vec![selector.clone()];
    let mut facts = select(r, filter.clone())?;
    if facts.is_empty() {
        filter.ids.clear();
        filter.kinds = vec![
            FactKind::Declaration,
            FactKind::ExternalSymbol,
            FactKind::Facet,
        ];
        filter.signature = Some(selector.clone());
        facts = select(r, filter.clone())?
            .into_iter()
            .filter(Fact::is_public_entity)
            .collect();
        if facts.is_empty() {
            filter.signature = (!req.signature.is_empty()).then(|| req.signature.clone());
            filter.name = Some(selector.clone());
            facts = select(r, filter)?
                .into_iter()
                .filter(Fact::is_public_entity)
                .collect();
        }
    }
    ensure!(!facts.is_empty(), "SYMBOL_NOT_FOUND: {}", req.selector);
    let internal = facts
        .iter()
        .any(|fact| fact.is_public_entity() && fact.kind != FactKind::ExternalSymbol);
    if internal {
        facts.retain(|fact| fact.kind != FactKind::ExternalSymbol);
    }
    ensure!(
        !req.unique || facts.len() == 1,
        "AMBIGUOUS_SELECTOR: {}",
        req.selector
    );
    Ok(facts)
}
fn coverage(r: &dyn GraphReader, req: &Request) -> Result<Evidence> {
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

    let mut filter = scope_filter(req);
    filter.kinds = vec![FactKind::Coverage];
    // Incoming callers can originate in any captured module. Their coverage matters too.
    if req.operation == "runtime-implementations"
        || (req.direction == "in"
            && matches!(
                req.operation.as_str(),
                "calls"
                    | "call-sites"
                    | "references"
                    | "type-relations"
                    | "callable-relations"
                    | "trace"
            ))
    {
        filter.module = None;
        filter.scope = None;
        filter.world = None;
    }
    let cov = select(r, filter)?;
    for c in cov {
        if !c.evidence_complete || c.status != "complete" {
            e.semantic = false;
            e.reasons
                .push(format!("coverage:{}:{}", c.source, c.status));
        }
    }
    Ok(e)
}
fn allowed(r: &dyn GraphReader, req: &Request, f: &Fact) -> bool {
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
fn fact_source(r: &dyn GraphReader, f: &Fact) -> Result<(SourceUnit, Option<Span>)> {
    if f.attributes.get("source_available") != Some(&json!(false)) {
        return r
            .manifest()
            .files
            .iter()
            .find(|s| s.path == f.source)
            .cloned()
            .map(|s| {
                r.source_span(f)
                    .map(|span| (s, span))
                    .map_err(anyhow::Error::msg)
            })
            .transpose()?
            .ok_or_else(|| anyhow::anyhow!("SOURCE_UNAVAILABLE: {} origin={}", f.id, f.origin));
    }
    ensure!(
        f.kind == FactKind::ExternalSymbol && f.source.is_empty(),
        "SOURCE_UNAVAILABLE: {}",
        f.id
    );
    let origin = f
        .attributes
        .get("source_origin")
        .and_then(Value::as_object)
        .ok_or_else(|| anyhow::anyhow!("SOURCE_UNAVAILABLE: {}", f.id))?;
    let text = |key: &str| origin.get(key).and_then(Value::as_str).unwrap_or("");
    let source = r
        .manifest()
        .files
        .iter()
        .find(|s| {
            s.path == text("path")
                && s.hash == text("hash")
                && s.world == text("world")
                && s.module == text("module")
                && s.scope == text("scope")
        })
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("SOURCE_ORIGIN_MISMATCH: {}", f.id))?;
    ensure!(!f.signature.is_empty(), "SOURCE_UNAVAILABLE: {}", f.id);
    // Navigate a captured SOURCE declaration; never substitute its binding world or target ID.
    let candidates = select(
        r,
        FactFilter {
            kinds: vec![FactKind::Declaration],
            source: Some(source.path.clone()),
            world: Some(source.world.clone()),
            ..Default::default()
        },
    )?
    .into_iter()
    .filter(|d| {
        d.signature == f.signature
            && d.origin == "javac"
            && d.span.is_some()
            && d.attributes.get("source_available") != Some(&json!(false))
    })
    .collect::<Vec<_>>();
    ensure!(
        candidates.len() == 1,
        "SOURCE_UNAVAILABLE: {} candidates={}",
        f.id,
        candidates.len()
    );
    Ok((source, candidates[0].span.clone()))
}

fn values(facts: impl IntoIterator<Item = Fact>) -> Vec<Value> {
    facts
        .into_iter()
        .map(|f| serde_json::to_value(f).unwrap())
        .collect()
}
fn call_edges_many(
    r: &dyn GraphReader,
    ids: &[String],
    incoming: bool,
    evidence: &mut Evidence,
) -> Result<BTreeMap<(String, String), Vec<String>>> {
    let mut edges = BTreeMap::<(String, String), Vec<String>>::new();
    if incoming {
        let targets = select(
            r,
            FactFilter {
                kinds: vec![FactKind::CallTarget],
                targets: ids.to_vec(),
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
                parents: ids.to_vec(),
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
/// Freeze a search in bounded batches; IDs are the stable, unique sort key.
pub fn stream_search(
    r: &dyn GraphReader,
    req: &Request,
    sink: &mut dyn FnMut(Vec<Value>) -> Result<()>,
) -> Result<Output> {
    preflight(req)?;
    ensure!(
        req.operation == "search-code",
        "STREAM_SEARCH_OPERATION_REQUIRED"
    );
    let evidence = coverage(r, req)?;
    let filter = selector_filter(r, req)?;
    let mut after = String::new();
    loop {
        let batch = r
            .scan_symbols(&req.selector, &filter, &after, 512)
            .map_err(anyhow::Error::msg)?;
        ensure!(batch.len() <= 512, "INVALID_SCAN_BATCH_RESULT");
        if batch.is_empty() {
            break;
        }
        for fact in &batch {
            ensure!(fact.id > after, "UNORDERED_SCAN_RESULT");
            after.clone_from(&fact.id);
        }
        let complete = batch.len() < 512;
        sink(values(batch))?;
        if complete {
            break;
        }
    }
    Ok(Output {
        revision: r.revision().id.clone(),
        producer: r.revision().producer.clone(),
        evidence,
        ..Default::default()
    })
}
pub fn run(
    r: &dyn GraphReader,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output> {
    if req.operation == "search-text" {
        let mut records = Vec::new();
        let mut output = stream_text(r, req, read_source, &mut |batch| {
            ensure!(records.len() + batch.len() <= MAX_FACTS, "TEXT_WORK_BUDGET");
            records.extend(batch);
            Ok(())
        })?;
        output.records = records;
        return Ok(output);
    }
    if req.operation == "search-code" {
        let mut records = Vec::new();
        let mut output = stream_search(r, req, &mut |batch| {
            records.extend(batch);
            Ok(())
        })?;
        output.records = records;
        return Ok(output);
    }
    preflight(req)?;
    let mut evidence = coverage(r, req)?;
    let mut records = Vec::new();
    if req.operation == "pipeline" {
        let mut input: Vec<Value> = Vec::new();
        for (i, stage) in req.stages.iter().enumerate() {
            let mut s = req.clone();
            s.operation = stage.operation.clone();
            s.direction = stage.direction.clone();
            s.relation = stage.relation.clone();
            s.depth = stage.depth;
            s.meta = stage.meta;
            s.instantiability = stage.instantiability.clone();
            s.transitive = stage.transitive;
            s.algorithm = stage.algorithm.clone();
            s.analysis_world = stage.analysis_world.clone();
            s.unique = stage.unique;
            s.stages.clear();
            s.max_records = 10000;
            let selectors = if i == 0 {
                vec![stage.selector.clone()]
            } else {
                let mut selectors = BTreeSet::new();
                for record in &input {
                    if stage.endpoint == "site" {
                        let sites = record["sites"].as_array().ok_or_else(|| {
                            anyhow::anyhow!("PIPELINE_ENDPOINT_UNAVAILABLE: site")
                        })?;
                        for site in sites {
                            selectors.insert(
                                site.as_str()
                                    .ok_or_else(|| anyhow::anyhow!("PIPELINE_INVALID_SITE"))?
                                    .to_owned(),
                            );
                        }
                    } else {
                        let keys: &[&str] = match stage.endpoint.as_str() {
                            "callee" | "target" => &["to", "target"],
                            "caller" | "parent" => &["from", "parent"],
                            "" => &["id"],
                            _ => bail!("PIPELINE_INVALID_ENDPOINT"),
                        };
                        let value = keys
                            .iter()
                            .find_map(|key| record[*key].as_str().filter(|s| !s.is_empty()))
                            .ok_or_else(|| {
                                anyhow::anyhow!("PIPELINE_ENDPOINT_UNAVAILABLE: {}", stage.endpoint)
                            })?;
                        selectors.insert(value.to_owned());
                    }
                }
                selectors.into_iter().collect()
            };
            records.clear();
            for selector in selectors {
                s.selector = selector;
                let out = run(r, &s, read_source)?;
                evidence.merge(&out.evidence);
                records.extend(out.records);
                ensure!(records.len() <= 10000, "PIPELINE_WORK_BUDGET");
            }
            input = records.clone();
        }
    } else {
        match req.operation.as_str() {
            "resolve" => {
                records = values(resolve_scoped(r, req)?);
            }
            "search-code" => {
                records = values(
                    r.search_symbols_filtered(&req.selector, &selector_filter(r, req)?)
                        .map_err(anyhow::Error::msg)?,
                );
            }
            "overview" => {
                if matches!(
                    req.overview_section.as_str(),
                    "modules" | "packages" | "dependencies"
                ) {
                    let filter = selector_filter(r, req)?;
                    let relations = overview_relations(&req.relation)?;
                    records = r
                        .overview_groups(&filter, &req.overview_section, &req.direction, &relations)
                        .map_err(anyhow::Error::msg)?;
                    if req.overview_depth > 0 && req.overview_section != "modules" {
                        let mut groups = BTreeMap::<(String, String, String), u64>::new();
                        for row in records {
                            let package = |key| {
                                row[key]
                                    .as_str()
                                    .unwrap_or("")
                                    .split('.')
                                    .take(req.overview_depth as usize)
                                    .collect::<Vec<_>>()
                                    .join(".")
                            };
                            let key = (
                                package("package"),
                                package("target_package"),
                                row["relation"].as_str().unwrap_or("").to_owned(),
                            );
                            *groups.entry(key).or_default() += row["count"].as_u64().unwrap_or(0);
                        }
                        records = groups.into_iter().map(|((package,target,relation),count)| {
                            if req.overview_section == "dependencies" { json!({"package":package,"target_package":target,"relation":relation,"count":count}) }
                            else { json!({"package":package,"count":count}) }
                        }).collect();
                    }
                } else {
                    let counts = r
                        .count_by_kind(&scope_filter(req))
                        .map_err(anyhow::Error::msg)?;
                    let sources = r
                        .manifest()
                        .files
                        .iter()
                        .filter(|source| {
                            !source.auxiliary
                                && (req.module.is_empty() || source.module == req.module)
                                && (req.scope.is_empty()
                                    || req.scope == "ALL"
                                    || source.scope == req.scope)
                                && (req.world.is_empty() || source.world == req.world)
                        })
                        .count();
                    records.push(
                        json!({"counts":counts,"sources":sources,"profile":r.revision().profile}),
                    );
                }
            }
            "source" => {
                let sources =
                    if let Some(s) = r.manifest().files.iter().find(|s| s.path == req.selector) {
                        vec![(s.clone(), None)]
                    } else {
                        resolve_scoped(r, req)?
                            .into_iter()
                            .map(|f| fact_source(r, &f))
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
            "search-text" => unreachable!("text search returns from streaming entry"),
            "structure" => {
                let roots = resolve_scoped(r, req)?;
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
            }
            "references" | "call-sites" | "annotations" => {
                let roots = resolve_scoped(r, req)?;
                let mut seen = BTreeSet::new();
                for f in roots {
                    let hits = match req.operation.as_str() {
                        "references" => {
                            let outgoing = req.direction == "out";
                            let base = FactFilter {
                                kinds: if req.relation.is_empty() {
                                    vec![FactKind::Reference]
                                } else {
                                    vec![FactKind::Reference, FactKind::Relation]
                                },
                                parent: outgoing.then(|| f.id.clone()),
                                target: (!outgoing).then(|| f.id.clone()),
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
                        "annotations" => {
                            let direct = select(
                                r,
                                FactFilter {
                                    kinds: vec![FactKind::Facet],
                                    parent: Some(f.id),
                                    relation: Some("java.annotation".into()),
                                    ..Default::default()
                                },
                            )?;
                            if req.meta {
                                annotations::expand(r, direct, &mut evidence)?
                            } else {
                                direct
                            }
                        }
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
                        if seen.insert(f.id.clone())
                            && ((req.direction == "in"
                                || (req.operation == "references" && req.direction.is_empty()))
                                || allowed(r, req, &f))
                        {
                            records.push(serde_json::to_value(f)?);
                        }
                    }
                }
            }
            "trace" => {
                ensure!(!req.target.is_empty(), "TRACE_TARGET_REQUIRED");
                let roots = resolve_scoped(r, req)?;
                ensure!(roots.len() == 1, "TRACE_UNIQUE_START_REQUIRED");
                let mut target_request = req.clone();
                target_request.selector = req.target.clone();
                target_request.module.clear();
                target_request.scope.clear();
                target_request.world.clear();
                let targets = resolve_scoped(r, &target_request)?;
                ensure!(targets.len() == 1, "TRACE_UNIQUE_TARGET_REQUIRED");
                let start = roots[0].id.clone();
                let target = targets[0].id.clone();
                let mut visited = BTreeSet::from([start.clone()]);
                let mut predecessor: BTreeMap<String, (String, Value)> = BTreeMap::new();
                let mut frontier = vec![start.clone()];
                let mut reached = start == target;
                let mut work = 0usize;
                for depth in 0..=req.depth.max(1) {
                    if reached || frontier.is_empty() {
                        break;
                    }
                    let mut next = BTreeSet::new();
                    for batch in frontier.chunks(500) {
                        let edges =
                            call_edges_many(r, batch, req.direction == "in", &mut evidence)?;
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
                            predecessor.insert(node.clone(),(previous.clone(),json!({"from":from,"to":to,"sites":sites,"kind":"resolved_call"})));
                            if node == &target {
                                reached = true;
                            }
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
                        let (previous, hop) = predecessor
                            .get(&cursor)
                            .ok_or_else(|| anyhow::anyhow!("TRACE_PATH_STATE"))?;
                        hops.push(hop.clone());
                        nodes.push(previous.clone());
                        cursor = previous.clone();
                    }
                    nodes.reverse();
                    hops.reverse();
                    records.push(json!({"from":start,"to":target,"nodes":nodes,"hops":hops}));
                }
            }
            "calls" => {
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
                        let edges =
                            call_edges_many(r, batch, req.direction == "in", &mut evidence)?;
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
                                records.push(json!({"from":from,"to":to,"sites":sites,"kind":"resolved_call","depth":depth+1}));
                            }
                        }
                    }
                    frontier = next.into_iter().collect();
                }
            }
            "runtime-implementations" => {
                records = hierarchy::runtime(r, req, resolve_scoped(r, req)?, &mut evidence)?;
            }
            "type-relations" | "callable-relations" => {
                records = hierarchy::relations(r, req, resolve_scoped(r, req)?, &mut evidence)?;
            }
            "dispatch" => {
                let roots = resolve_scoped(r, req)?;
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
                            if t.status == "possible" && !t.target.is_empty() {
                                evidence.semantic = false;
                                let reason = if t.origin == "reflection_model" {
                                    "reflection_lookup_candidate"
                                } else {
                                    "modeled_binding"
                                };
                                evidence.reasons.push(format!("{reason}:{}", t.id));
                                let basis = if t.origin == "reflection_model" {
                                    t.attributes
                                        .get("call_kind")
                                        .or_else(|| t.attributes.get("via"))
                                } else {
                                    t.attributes
                                        .get("via")
                                        .or_else(|| t.attributes.get("recovery_input"))
                                };
                                records.push(json!({"call_site":root.id,"target":t.target,"binding":t.status,"binding_origin":t.origin,
                                    "operation":t.attributes.get("operation"),"basis":basis,
                                    "recovery_phase":t.attributes.get("recovery_phase"),
                                    "proof":[t.id],"possible":true,"world":t.owner.world}));
                                continue;
                            }
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
                            let declaration = select(
                                r,
                                FactFilter {
                                    ids: vec![t.target.clone()],
                                    ..Default::default()
                                },
                            )?
                            .into_iter()
                            .next();
                            let modifiers = declaration
                                .as_ref()
                                .and_then(|fact| fact.attributes.get("modifiers"))
                                .and_then(Value::as_array);
                            let has_modifier = |name: &str| {
                                modifiers.is_some_and(|items| {
                                    items.iter().any(|item| item.as_str() == Some(name))
                                })
                            };
                            let class_final = if let Some(parent) =
                                declaration.as_ref().filter(|fact| !fact.parent.is_empty())
                            {
                                select(
                                    r,
                                    FactFilter {
                                        ids: vec![parent.parent.clone()],
                                        ..Default::default()
                                    },
                                )?
                                .into_iter()
                                .next()
                                .and_then(|owner| owner.attributes.get("modifiers").cloned())
                                .and_then(|value| value.as_array().cloned())
                                .is_some_and(|items| {
                                    items.iter().any(|item| item.as_str() == Some("final"))
                                })
                            } else {
                                declaration
                                    .as_ref()
                                    .and_then(|fact| fact.attributes.get("binary_class"))
                                    .and_then(|class| class.get("access"))
                                    .and_then(Value::as_i64)
                                    .is_some_and(|access| access & 0x0010 != 0)
                            };
                            let mechanism = root
                                .attributes
                                .get("mechanism")
                                .and_then(Value::as_str)
                                .unwrap_or("");
                            let exact = req.algorithm == "exact"
                                || mechanism == "constructor"
                                || root
                                    .attributes
                                    .get("reference_mode")
                                    .and_then(Value::as_str)
                                    == Some("NEW")
                                || root.name.starts_with("super.")
                                || has_modifier("static")
                                || has_modifier("private")
                                || has_modifier("final")
                                || class_final;
                            records.push(json!({"call_site":root.id,"target":t.target,"binding":t.status,"proof":[t.id],"possible":!exact,"world":t.owner.world,
                                "candidate":"resolved","algorithm":if exact {"exact"} else {"CHA"},
                                "executable":declaration.as_ref().filter(|d| d.kind == FactKind::Declaration).map(|_| !has_modifier("abstract")),
                                "analysis_world":if req.analysis_world.is_empty() {"workspace-open"} else {&req.analysis_world}}));
                            if exact {
                                continue;
                            }
                            records.extend(dispatch::candidates(
                                r,
                                req,
                                &root,
                                &t,
                                declaration.as_ref(),
                                &mut evidence,
                            )?);
                            if req.analysis_world != "workspace-closed" {
                                evidence.semantic = false;
                                evidence.reasons.push("open_world_dispatch".into());
                            }
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
    #[test]
    fn wide_dispatch_uses_batched_adjacency_and_method_reads() {
        use std::cell::Cell;
        struct Counted {
            inner: MemoryReader,
            reads: Cell<usize>,
        }
        impl GraphReader for Counted {
            fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
                assert!(
                    filter.ids.len() <= 500
                        && filter.parents.len() <= 500
                        && filter.targets.len() <= 500
                );
                self.reads.set(self.reads.get() + 1);
                self.inner.select(filter)
            }
            fn manifest(&self) -> &Manifest {
                self.inner.manifest()
            }
            fn revision(&self) -> &Revision {
                self.inner.revision()
            }
        }
        let mut inner = hierarchy_fixture();
        inner.facts.retain(|fact| fact.id == "api");
        let template = inner.facts[0].clone();
        let mut root_method = template.clone();
        root_method.id = "api#run()".into();
        root_method.signature = root_method.id.clone();
        root_method.parent = "api".into();
        root_method.attributes =
            serde_json::from_value(json!({"element_kind":"METHOD","modifiers":["abstract"]}))
                .unwrap();
        inner.facts.push(root_method.clone());
        for number in 0..1200 {
            let mut class = template.clone();
            class.id = format!("impl{number}");
            class.signature = class.id.clone();
            class.attributes =
                serde_json::from_value(json!({"element_kind":"CLASS","modifiers":[]})).unwrap();
            let mut method = root_method.clone();
            method.id = format!("{}#run()", class.id);
            method.signature = method.id.clone();
            method.parent = class.id.clone();
            method.attributes.insert("modifiers".into(), json!([]));
            let mut type_edge = class.clone();
            type_edge.id = format!("type{number}");
            type_edge.kind = FactKind::Relation;
            type_edge.parent = class.id.clone();
            type_edge.target = "api".into();
            type_edge.relation = "implements".into();
            let mut override_edge = type_edge.clone();
            override_edge.id = format!("override{number}");
            override_edge.parent = method.id.clone();
            override_edge.target = "api#run()".into();
            override_edge.relation = "overrides".into();
            inner
                .facts
                .extend([class, method, type_edge, override_edge]);
        }
        let mut site = template.clone();
        site.id = "site".into();
        site.kind = FactKind::CallSite;
        site.attributes = serde_json::from_value(json!({"mechanism":"invoke"})).unwrap();
        let mut binding = site.clone();
        binding.id = "binding".into();
        binding.kind = FactKind::CallTarget;
        binding.parent = "site".into();
        binding.target = "api#run()".into();
        inner.facts.extend([site, binding]);
        let reader = Counted {
            inner,
            reads: Cell::new(0),
        };
        let output = run(
            &reader,
            &Request {
                operation: "dispatch".into(),
                selector: "site".into(),
                depth: 4,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(output.records.len(), 1201);
        assert!(output.evidence.enumeration);
        assert!(
            reader.reads.get() < 60,
            "{} reads for 1200 types",
            reader.reads.get()
        );
    }
    #[test]
    fn dispatch_respects_receiver_types_and_skips_abstract_override_candidates() {
        let mut reader = hierarchy_fixture();
        reader.facts.retain(|fact| fact.id != "e4");
        for owner in ["api", "abstract", "concrete", "final"] {
            let mut method = reader
                .facts
                .iter()
                .find(|fact| fact.id == owner)
                .unwrap()
                .clone();
            method.id = format!("{owner}#run()");
            method.signature = method.id.clone();
            method.parent = owner.into();
            method
                .attributes
                .insert("element_kind".into(), json!("METHOD"));
            method
                .attributes
                .insert("entity_kind".into(), json!("callable"));
            method.attributes.insert(
                "modifiers".into(),
                if matches!(owner, "api" | "abstract") {
                    json!(["abstract"])
                } else {
                    json!([])
                },
            );
            reader.facts.push(method);
        }
        for (child, parent) in [
            ("abstract", "api"),
            ("concrete", "abstract"),
            ("final", "concrete"),
        ] {
            let mut edge = reader.facts[0].clone();
            edge.id = format!("override:{child}");
            edge.kind = FactKind::Relation;
            edge.parent = format!("{child}#run()");
            edge.target = format!("{parent}#run()");
            edge.relation = "overrides".into();
            reader.facts.push(edge);
        }
        let mut site = reader.facts[0].clone();
        site.id = "runtime-site".into();
        site.kind = FactKind::CallSite;
        site.attributes
            .insert("receiver_static_type".into(), json!("final"));
        site.attributes.insert("mechanism".into(), json!("invoke"));
        reader.facts.push(site.clone());
        site.id = "runtime-binding".into();
        site.kind = FactKind::CallTarget;
        site.parent = "runtime-site".into();
        site.target = "api#run()".into();
        reader.facts.push(site);
        let result = run(
            &reader,
            &Request {
                operation: "dispatch".into(),
                selector: "runtime-site".into(),
                depth: 8,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(
            result
                .records
                .iter()
                .map(|row| row["target"].as_str().unwrap())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["api#run()", "final#run()"])
        );
        let candidate = result
            .records
            .iter()
            .find(|row| row["target"] == "final#run()")
            .unwrap();
        assert_eq!(candidate["possible"], true);
        assert_eq!(candidate["algorithm"], "CHA");
        assert_eq!(
            candidate["proof"],
            json!([
                "runtime-binding",
                "override:abstract",
                "override:concrete",
                "override:final"
            ])
        );
        assert_eq!(candidate["type_proof"], json!([]));
    }
    fn hierarchy_fixture() -> MemoryReader {
        let mut reader = navigation_fixture();
        let template = reader.facts[0].clone();
        reader.facts.clear();
        for (id, kind, modifiers) in [
            ("api", "INTERFACE", vec![]),
            ("abstract", "CLASS", vec!["abstract"]),
            ("concrete", "CLASS", vec![]),
            ("final", "CLASS", vec!["final"]),
            ("unknown", "UNKNOWN", vec![]),
        ] {
            let mut fact = template.clone();
            fact.id = id.into();
            fact.name = id.into();
            fact.signature = id.into();
            fact.attributes = serde_json::from_value(json!({
                "entity_kind":"type", "element_kind":kind, "modifiers":modifiers
            }))
            .unwrap();
            if id != "api" {
                fact.source = "b/Api.java".into();
                fact.owner.world = "b-main".into();
            }
            reader.facts.push(fact);
        }
        for (id, child, parent, kind) in [
            ("e1", "abstract", "api", "implements"),
            ("e2", "concrete", "abstract", "extends"),
            ("e3", "final", "concrete", "extends"),
            ("e4", "api", "final", "extends"),
            ("e5", "unknown", "api", "implements"),
        ] {
            let mut fact = reader.facts.iter().find(|f| f.id == child).unwrap().clone();
            fact.id = id.into();
            fact.kind = FactKind::Relation;
            fact.parent = child.into();
            fact.target = parent.into();
            fact.relation = kind.into();
            reader.facts.push(fact);
        }
        reader
    }
    #[test]
    fn runtime_candidates_traverse_abstract_types_preserve_proofs_and_exclude_seed() {
        let reader = hierarchy_fixture();
        let request = Request {
            operation: "runtime-implementations".into(),
            selector: "api".into(),
            module: "a".into(),
            depth: 4,
            ..Default::default()
        };
        let result = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(
            result
                .records
                .iter()
                .map(|r| r["id"].as_str().unwrap())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["concrete", "final"])
        );
        let leaf = result.records.iter().find(|r| r["id"] == "final").unwrap();
        assert_eq!(
            leaf["runtime_implementation"]["proof"],
            json!(["e1", "e2", "e3"])
        );
        assert_eq!(leaf["runtime_implementation"]["reason"], "java.final_class");
        assert!(result.evidence.enumeration);
        assert!(!result.evidence.semantic);
        let limited = run(
            &reader,
            &Request {
                depth: 1,
                ..request.clone()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert!(limited.records.is_empty());
        assert!(!limited.evidence.enumeration);
        assert!(limited.evidence.limits.contains(&"depth".into()));
        let pipeline = run(
            &reader,
            &Request {
                operation: "pipeline".into(),
                stages: vec![
                    Stage {
                        operation: "runtime-implementations".into(),
                        selector: "api".into(),
                        depth: 4,
                        ..Default::default()
                    },
                    Stage {
                        operation: "resolve".into(),
                        ..Default::default()
                    },
                ],
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(pipeline.records.len(), 2);
    }
    #[test]
    fn runtime_instantiability_filters_and_incoming_coverage_are_explicit() {
        let mut reader = hierarchy_fixture();
        let request = Request {
            operation: "runtime-implementations".into(),
            selector: "api".into(),
            depth: 4,
            instantiability: "unknown".into(),
            module: "a".into(),
            ..Default::default()
        };
        let result = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(result.records.len(), 1);
        assert_eq!(result.records[0]["id"], "unknown");
        let all = run(
            &reader,
            &Request {
                instantiability: "all".into(),
                ..request.clone()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(all.records.len(), 4);
        assert!(all.records.iter().any(|row| row["id"] == "abstract"
            && row["runtime_implementation"]["instantiability"] == "no"));
        let mut incomplete = reader.facts[1].clone();
        incomplete.id = "coverage-b".into();
        incomplete.kind = FactKind::Coverage;
        incomplete.status = "partial".into();
        incomplete.evidence_complete = false;
        reader.facts.push(incomplete);
        let result = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert!(
            result
                .evidence
                .reasons
                .iter()
                .any(|reason| reason.starts_with("coverage:b/"))
        );
        assert!(
            preflight(&Request {
                instantiability: "no".into(),
                ..request.clone()
            })
            .is_err()
        );
        assert!(
            preflight(&Request {
                operation: "resolve".into(),
                ..request
            })
            .is_err()
        );
    }
    #[test]
    fn transitive_hierarchy_preserves_direct_default_cycles_and_depth_limits() {
        let reader = hierarchy_fixture();
        let request = Request {
            operation: "type-relations".into(),
            selector: "api".into(),
            direction: "in".into(),
            module: "a".into(),
            depth: 4,
            ..Default::default()
        };
        let direct = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(direct.records.len(), 2);
        assert!(direct.records.iter().all(|row| row.get("depth").is_none()));
        let all = run(
            &reader,
            &Request {
                transitive: true,
                ..request.clone()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(all.records.len(), 5);
        assert!(all.evidence.enumeration);
        let back_edge = run(
            &reader,
            &Request {
                transitive: true,
                depth: 3,
                ..request.clone()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert!(back_edge.evidence.limits.contains(&"depth".into()));
        let limited = run(
            &reader,
            &Request {
                transitive: true,
                depth: 1,
                ..request
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(limited.records.len(), 2);
        assert!(limited.evidence.limits.contains(&"depth".into()));
        assert!(
            preflight(&Request {
                operation: "calls".into(),
                transitive: true,
                ..Default::default()
            })
            .is_err()
        );
        let mut callable = hierarchy_fixture();
        for fact in &mut callable.facts {
            if fact.kind == FactKind::Relation {
                fact.relation = "overrides".into();
            }
        }
        let result = run(
            &callable,
            &Request {
                operation: "callable-relations".into(),
                selector: "final".into(),
                transitive: true,
                depth: 2,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(result.records.len(), 2);
        assert!(result.evidence.limits.contains(&"depth".into()));
    }
    #[test]
    fn outgoing_references_filter_relations_and_preserve_occurrences() {
        let mut reader = navigation_fixture();
        let mut reference = reader.facts[3].clone();
        reference.kind = FactKind::Reference;
        reference.parent = "caller".into();
        reference.target = "callee".into();
        for (id, relation) in [
            ("read-one", "reads"),
            ("read-two", "reads"),
            ("write", "writes"),
        ] {
            reference.id = id.into();
            reference.relation = relation.into();
            reader.facts.push(reference.clone());
        }
        let request = Request {
            operation: "references".into(),
            selector: "caller".into(),
            direction: "out".into(),
            relation: "reads".into(),
            ..Default::default()
        };
        let result = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(result.records.len(), 2);
        assert!(result.records.iter().all(|row| row["relation"] == "reads"));
        let incoming = run(
            &reader,
            &Request {
                selector: "callee".into(),
                module: "b".into(),
                direction: "in".into(),
                ..request
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(incoming.records.len(), 2);
        assert!(
            incoming
                .records
                .iter()
                .all(|row| row["source"] == "a/Api.java")
        );
    }
    #[test]
    fn overview_relation_groups_follow_the_copied_graph_mapping() {
        let selected = overview_relations(" type, config, reads, CALLS,INHERITS,type ").unwrap();
        assert!(selected.contains(&"reflection_lookup".into()));
        assert!(selected.contains(&"type_reference".into()));
        assert!(selected.contains(&"config_member".into()));
        assert!(selected.contains(&"unresolved_config".into()));
        assert!(selected.contains(&"wires_possible".into()));
        assert!(selected.contains(&"calls".into()));
        assert!(selected.contains(&"extends".into()));
        assert_eq!(
            selected.iter().filter(|r| *r == "type_reference").count(),
            1
        );
        assert!(!selected.contains(&"contains".into()));
        let all = overview_relations("all").unwrap();
        assert!(all.contains(&"writes".into()));
        assert!(!all.contains(&"calls".into()));
        assert!(!all.contains(&"extends".into()));
        assert!(overview_relations("calls,").is_err());
        assert!(overview_relations("").unwrap().is_empty());
    }
    #[test]
    fn overview_rejects_unknown_relation_before_reading() {
        let request = Request {
            operation: "overview".into(),
            overview_section: "dependencies".into(),
            relation: "unknown-group".into(),
            ..Default::default()
        };
        assert!(preflight(&request).is_err());
    }
    #[test]
    fn symbol_search_does_not_treat_the_first_ten_thousand_as_complete() {
        let mut reader = navigation_fixture();
        let template = reader
            .facts
            .iter()
            .find(|f| f.kind == FactKind::Declaration)
            .unwrap()
            .clone();
        reader.facts = (0..10001)
            .map(|number| {
                let mut fact = template.clone();
                fact.id = format!("symbol-{number:05}");
                fact.name = "same".into();
                fact
            })
            .collect();
        let output = run(
            &reader,
            &Request {
                operation: "search-code".into(),
                selector: "same".into(),
                ..Default::default()
            },
            &|_| Ok(vec![]),
        )
        .unwrap();
        assert_eq!(output.records.len(), 10001);
        assert!(output.evidence.enumeration);
    }
    fn navigation_fixture() -> MemoryReader {
        let units = ["a", "b"].map(|module| SourceUnit {
            path: format!("{module}/Api.java"),
            hash: hash("class Api {}"),
            size: 12,
            module: module.into(),
            world: format!("{module}-main"),
            scope: "MAIN".into(),
            language: "java".into(),
            auxiliary: false,
        });
        let fact = |id: &str, kind: &str, module: usize, parent: &str, target: &str| {
            serde_json::from_value(json!({"id":id,"kind":kind,"name":id,"signature":id,"parent":parent,"target":target,"source":units[module].path,"owner":{"world":units[module].world,"producer":"mock","kind":"source","id":units[module].path},"status":"resolved","evidence_complete":true,"declaration_resolved":true,"span":{"start":0,"end":12}})).unwrap()
        };
        MemoryReader {
            revision: Revision {
                id: "r".into(),
                target: "t".into(),
                project: "p".into(),
                checkout: "c".into(),
                manifest: Manifest {
                    files: units.to_vec(),
                },
                profile: "p".into(),
                producer: "mock".into(),
                created: 0,
            },
            facts: vec![
                fact("caller", "declaration", 0, "", ""),
                fact("callee", "declaration", 1, "", ""),
                fact("end", "declaration", 1, "", ""),
                fact("site1", "call_site", 0, "caller", ""),
                fact("binding1", "call_target", 0, "site1", "callee"),
                fact("site2", "call_site", 1, "callee", ""),
                fact("binding2", "call_target", 1, "site2", "end"),
            ],
        }
    }
    #[test]
    fn meta_annotation_depth_and_duplicate_producer_pairs_are_explicit() {
        let mut reader = navigation_fixture();
        let mut direct = reader.facts[0].clone();
        direct.id = "annotation".into();
        direct.kind = FactKind::Facet;
        direct.parent = "caller".into();
        direct.relation = "java.annotation".into();
        direct.attributes.insert("annotation".into(), json!("A0"));
        reader.facts.push(direct);
        for index in 0..18 {
            let mut meta = reader.facts[0].clone();
            meta.id = format!("meta{index}");
            meta.kind = FactKind::Facet;
            meta.relation = "java.annotation.meta".into();
            meta.attributes
                .insert("annotation".into(), json!(format!("A{index}")));
            meta.attributes
                .insert("meta_annotation".into(), json!(format!("A{}", index + 1)));
            meta.attributes.insert("raw_name".into(), json!("z"));
            reader.facts.push(meta);
        }
        let mut duplicate = reader.facts[reader.facts.len() - 18].clone();
        duplicate.id = "duplicate-producer".into();
        duplicate.attributes.insert("raw_name".into(), json!("a"));
        reader.facts.push(duplicate);
        let request: Request = serde_json::from_value(
            json!({"operation":"annotations","selector":"caller","meta":true}),
        )
        .unwrap();
        let output = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(output.records.len(), 17);
        assert!(!output.evidence.enumeration);
        assert!(output.evidence.limits.contains(&"depth".into()));
        assert_eq!(
            output
                .records
                .iter()
                .find(|row| row["attributes"]["annotation"] == "A1")
                .unwrap()["attributes"]["raw_name"],
            "a"
        );
    }
    #[test]
    fn meta_annotations_preserve_paths_occurrences_and_stop_cycles() {
        let mut reader = navigation_fixture();
        for (id, parent, relation, annotation, meta) in [
            ("direct1", "caller", "java.annotation", "A", ""),
            ("direct2", "caller", "java.annotation", "A", ""),
            ("ab", "", "java.annotation.meta", "A", "B"),
            ("ac", "", "java.annotation.meta", "A", "C"),
            ("bd", "", "java.annotation.meta", "B", "D"),
            ("cd", "", "java.annotation.meta", "C", "D"),
            ("da", "", "java.annotation.meta", "D", "A"),
        ] {
            let mut fact = reader.facts[0].clone();
            fact.id = id.into();
            fact.kind = FactKind::Facet;
            fact.parent = parent.into();
            fact.relation = relation.into();
            fact.attributes
                .insert("annotation".into(), json!(annotation));
            fact.attributes
                .insert("meta_annotation".into(), json!(meta));
            reader.facts.push(fact);
        }
        let req: Request = serde_json::from_value(
            json!({"operation":"annotations","selector":"caller","meta":true}),
        )
        .unwrap();
        let out = run(&reader, &req, &|_| bail!("no source")).unwrap();
        let mut paths = out
            .records
            .iter()
            .map(|r| r["attributes"]["via"].as_str().unwrap())
            .collect::<Vec<_>>();
        paths.sort();
        assert_eq!(
            paths,
            [
                "A", "A", "A>B", "A>B", "A>B>D", "A>B>D", "A>C", "A>C", "A>C>D", "A>C>D"
            ]
        );
        assert!(out.evidence.enumeration);
        let plain = run(
            &reader,
            &Request {
                operation: "annotations".into(),
                selector: "caller".into(),
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(plain.records.len(), 2);
        let invalid: Request =
            serde_json::from_value(json!({"operation":"resolve","selector":"caller","meta":true}))
                .unwrap();
        assert!(preflight(&invalid).is_err());
    }
    #[test]
    fn configuration_names_and_structure_exclude_helper_facets() {
        let mut reader = navigation_fixture();
        for (id, parent, relation) in [
            ("bean", "", "java.bean"),
            ("property", "bean", "java.spring.xml"),
            ("binding", "bean", "java.binding"),
            ("metadata", "bean", "java.sofa"),
        ] {
            let mut node = reader.facts[0].clone();
            node.id = id.into();
            node.name = id.into();
            node.signature = if id == "metadata" {
                String::new()
            } else {
                format!("config:{id}")
            };
            node.kind = FactKind::Facet;
            node.parent = parent.into();
            node.relation = relation.into();
            reader.facts.push(node);
        }
        for selector in ["bean", "config:bean"] {
            let output = run(
                &reader,
                &Request {
                    operation: "structure".into(),
                    selector: selector.into(),
                    depth: 4,
                    ..Default::default()
                },
                &|_| bail!("no source"),
            )
            .unwrap();
            assert_eq!(
                output
                    .records
                    .iter()
                    .map(|row| row["id"].as_str().unwrap())
                    .collect::<Vec<_>>(),
                ["bean", "property"]
            );
        }
        assert!(
            run(
                &reader,
                &Request {
                    operation: "resolve".into(),
                    selector: "config:binding".into(),
                    ..Default::default()
                },
                &|_| bail!("no source")
            )
            .is_err()
        );
    }
    #[test]
    fn incoming_sites_preserve_callers_outside_anchor_module() {
        let reader = navigation_fixture();
        let output = run(
            &reader,
            &Request {
                operation: "call-sites".into(),
                selector: "callee".into(),
                module: "b".into(),
                direction: "in".into(),
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(
            output
                .records
                .iter()
                .map(|r| r["id"].as_str().unwrap())
                .collect::<Vec<_>>(),
            ["site1"]
        );
    }
    #[test]
    fn typed_pipeline_can_resolve_call_endpoint_then_read_source() {
        let reader = navigation_fixture();
        let request:Request=serde_json::from_value(json!({"operation":"pipeline","stages":[{"operation":"calls","selector":"caller"},{"operation":"resolve","endpoint":"callee"},{"operation":"source"}]})).unwrap();
        let output = run(&reader, &request, &|_| Ok(b"class Api {}".to_vec())).unwrap();
        assert_eq!(output.records.len(), 1);
        assert_eq!(output.records[0]["path"], "b/Api.java");
        assert_eq!(output.records[0]["text"], "class Api {}");
    }
    #[test]
    fn trace_returns_a_bounded_start_to_target_path() {
        let reader = navigation_fixture();
        let output = run(
            &reader,
            &Request {
                operation: "trace".into(),
                selector: "caller".into(),
                target: "end".into(),
                depth: 3,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(output.records.len(), 1);
        assert_eq!(
            output.records[0]["nodes"],
            json!(["caller", "callee", "end"])
        );
        assert_eq!(output.records[0]["hops"][0]["sites"], json!(["site1"]));
        let unreachable = run(
            &reader,
            &Request {
                operation: "trace".into(),
                selector: "end".into(),
                target: "caller".into(),
                depth: 3,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert!(unreachable.records.is_empty());
        assert!(unreachable.evidence.enumeration);
        let limited = run(
            &reader,
            &Request {
                operation: "trace".into(),
                selector: "caller".into(),
                target: "end".into(),
                depth: 1,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert!(limited.records.is_empty());
        assert!(!limited.evidence.enumeration);
    }
    use super::*;
    struct MemoryReader {
        revision: Revision,
        facts: Vec<Fact>,
    }
    impl GraphReader for MemoryReader {
        fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
            Ok(self
                .facts
                .iter()
                .filter(|f| {
                    (filter.ids.is_empty() || filter.ids.contains(&f.id))
                        && (filter.targets.is_empty() || filter.targets.contains(&f.target))
                        && (filter.parents.is_empty() || filter.parents.contains(&f.parent))
                        && (filter.kinds.is_empty() || filter.kinds.contains(&f.kind))
                        && filter.parent.as_ref().is_none_or(|v| v == &f.parent)
                        && filter.target.as_ref().is_none_or(|v| v == &f.target)
                        && filter.source.as_ref().is_none_or(|v| v == &f.source)
                        && filter.world.as_ref().is_none_or(|v| v == &f.owner.world)
                        && filter.name.as_ref().is_none_or(|v| v == &f.name)
                        && filter.signature.as_ref().is_none_or(|v| v == &f.signature)
                        && filter.relation.as_ref().is_none_or(|v| v == &f.relation)
                        && ((filter.module.is_none() && filter.scope.is_none())
                            || self.manifest().files.iter().any(|unit| {
                                unit.path == f.source
                                    && unit.world == f.owner.world
                                    && filter.module.as_ref().is_none_or(|v| v == &unit.module)
                                    && filter.scope.as_ref().is_none_or(|v| v == &unit.scope)
                            }))
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
    fn resolve_unique_is_checked_inside_selected_module() {
        let units = ["a", "b"].map(|module| SourceUnit {
            path: format!("{module}/Api.java"),
            hash: hash("source"),
            size: 6,
            module: module.into(),
            scope: "MAIN".into(),
            world: format!("{module}-main"),
            language: "java".into(),
            auxiliary: false,
        });
        let facts = units.iter().map(|unit| serde_json::from_value(json!({
            "id":unit.module, "kind":"declaration", "name":"Api", "signature":"p.Api", "source":unit.path,
            "owner":{"world":unit.world,"producer":"mock","kind":"source","id":unit.path}
        })).unwrap()).collect();
        let reader = MemoryReader {
            revision: Revision {
                id: "r".into(),
                target: "t".into(),
                project: "p".into(),
                checkout: "c".into(),
                manifest: Manifest {
                    files: units.to_vec(),
                },
                profile: "p".into(),
                producer: "mock".into(),
                created: 0,
            },
            facts,
        };
        let result = run(
            &reader,
            &Request {
                operation: "resolve".into(),
                selector: "Api".into(),
                module: "b".into(),
                unique: true,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(result.records.len(), 1);
        assert_eq!(result.records[0]["id"], "b");
    }
    #[test]
    fn auxiliary_source_navigation_uses_captured_declaration_without_rebinding() {
        let text = "class Api { void read() { /* 汉 */ } }";
        let start = text.find("void read()").unwrap() as u64;
        let end = (text.rfind('}').unwrap() - 1) as u64;
        let source = SourceUnit {
            path: "lib/Api.java".into(),
            hash: hash(text),
            size: text.len() as u64,
            module: "lib".into(),
            world: "lib-main".into(),
            scope: "MAIN".into(),
            language: "java".into(),
            auxiliary: false,
        };
        let declaration: Fact = serde_json::from_value(json!({
            "id":"producer-method", "kind":"declaration", "source":source.path,
            "owner":{"world":source.world,"producer":"javac-java8-v1","kind":"source","id":source.path},
            "signature":"Api#read()", "origin":"javac", "span":{"start":start,"end":end}
        })).unwrap();
        let auxiliary: Fact = serde_json::from_value(json!({
            "id":"consumer-method", "kind":"external_symbol", "signature":"Api#read()", "origin":"javac",
            "owner":{"world":"app-main","producer":"javac-java8-v1","kind":"context","id":"app@MAIN"},
            "attributes":{"source_available":false,"source_origin":{
                "path":source.path,"hash":source.hash,"world":source.world,"module":source.module,"scope":source.scope
            }}
        })).unwrap();
        let reader = MemoryReader {
            revision: Revision {
                id: "rev".into(),
                target: "t".into(),
                project: "p".into(),
                checkout: "c".into(),
                manifest: Manifest {
                    files: vec![source.clone()],
                },
                profile: "profile".into(),
                producer: "javac".into(),
                created: 0,
            },
            facts: vec![declaration, auxiliary.clone()],
        };
        let request = Request {
            operation: "source".into(),
            selector: auxiliary.id.clone(),
            unique: true,
            ..Default::default()
        };
        let output = run(&reader, &request, &|captured| {
            assert_eq!(captured, &source);
            Ok(text.as_bytes().to_vec())
        })
        .unwrap();
        assert_eq!(output.records.len(), 1);
        assert_eq!(
            output.records[0]["text"],
            &text[start as usize..end as usize]
        );
        assert_eq!(output.records[0]["path"], source.path);
        assert_eq!(resolve(&reader, &auxiliary.id, true).unwrap()[0], auxiliary);
        assert!(
            run(&reader, &request, &|_| Ok(b"changed".to_vec()))
                .unwrap_err()
                .to_string()
                .contains("SOURCE_HASH_MISMATCH")
        );

        for change in [
            "stale-hash",
            "wrong-module",
            "wrong-scope",
            "wrong-world",
            "binary-only",
            "missing-producer",
            "modeled-only",
            "ambiguous",
        ] {
            let mut altered = MemoryReader {
                revision: reader.revision.clone(),
                facts: reader.facts.clone(),
            };
            match change {
                "stale-hash" => {
                    altered.facts[1]
                        .attributes
                        .get_mut("source_origin")
                        .unwrap()["hash"] = json!("stale")
                }
                "wrong-module" => {
                    altered.facts[1]
                        .attributes
                        .get_mut("source_origin")
                        .unwrap()["module"] = json!("other")
                }
                "wrong-scope" => {
                    altered.facts[1]
                        .attributes
                        .get_mut("source_origin")
                        .unwrap()["scope"] = json!("TEST")
                }
                "wrong-world" => altered.facts[0].owner.world = "other-world".into(),
                "binary-only" => {
                    altered.facts[1].attributes.remove("source_origin");
                }
                "missing-producer" => {
                    altered.facts.remove(0);
                }
                "modeled-only" => altered.facts[0].origin = "lombok_model".into(),
                "ambiguous" => {
                    let mut duplicate = altered.facts[0].clone();
                    duplicate.id = "duplicate".into();
                    altered.facts.push(duplicate);
                }
                _ => unreachable!(),
            }
            assert!(
                run(&altered, &request, &|_| bail!(
                    "must not read unproven source"
                ))
                .is_err(),
                "{change}"
            );
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
    fn possible_candidates_are_explained_and_excluded_from_confirmed_calls() {
        let owner = json!({"world":"world","producer":"javac","kind":"context","id":"ctx"});
        let make = |value| serde_json::from_value::<Fact>(value).unwrap();
        let mut reader = MemoryReader {
            revision: Revision {
                id: "rev".into(),
                target: "t".into(),
                project: "p".into(),
                checkout: "c".into(),
                manifest: Manifest::default(),
                profile: "p".into(),
                producer: "javac".into(),
                created: 0,
            },
            facts: vec![
                make(
                    json!({"id":"caller","kind":"declaration","owner":owner,"signature":"Use#run()"}),
                ),
                make(
                    json!({"id":"reflected","kind":"declaration","owner":owner,"signature":"Target#work()"}),
                ),
                make(json!({"id":"site","kind":"call_site","owner":owner,"parent":"caller"})),
                make(
                    json!({"id":"possible","kind":"call_target","owner":owner,"parent":"site","target":"reflected",
                    "status":"possible","origin":"reflection_model","declaration_resolved":false,"evidence_complete":false,
                    "attributes":{"via":"reflection","call_kind":"REFLECTION","operation":"METHOD_INVOKE"}}),
                ),
            ],
        };
        let calls = run(
            &reader,
            &Request {
                operation: "calls".into(),
                selector: "caller".into(),
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
        assert_eq!(dispatch.records.len(), 1);
        assert_eq!(dispatch.records[0]["binding_origin"], "reflection_model");
        assert_eq!(dispatch.records[0]["basis"], "REFLECTION");
        assert_eq!(dispatch.records[0]["operation"], "METHOD_INVOKE");
        assert_eq!(dispatch.records[0]["possible"], true);
        assert!(
            dispatch
                .evidence
                .reasons
                .contains(&"reflection_lookup_candidate:possible".into())
        );
        for origin in ["lombok_model", "source_recovery", "binary_recovery"] {
            let candidate = reader.facts.last_mut().unwrap();
            candidate.origin = origin.into();
            candidate.declaration_resolved = origin != "lombok_model";
            candidate.attributes = serde_json::from_value(
                json!({"recovery_input":"modeled_expression","recovery_phase":1}),
            )
            .unwrap();
            let calls = run(
                &reader,
                &Request {
                    operation: "calls".into(),
                    selector: "caller".into(),
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
            assert_eq!(dispatch.records[0]["binding_origin"], origin);
            assert_eq!(dispatch.records[0]["basis"], "modeled_expression");
            assert_eq!(dispatch.records[0]["recovery_phase"], 1);
            assert_eq!(dispatch.records[0]["possible"], true);
            assert!(!dispatch.evidence.semantic);
        }
    }
    #[test]
    fn dispatch_distinguishes_exact_and_open_world_targets() {
        let owner = json!({"world":"world","producer":"javac","kind":"source","id":"A.java"});
        let make = |value| serde_json::from_value::<Fact>(value).unwrap();
        let revision = Revision {
            id: "rev".into(),
            target: "t".into(),
            project: "p".into(),
            checkout: "c".into(),
            manifest: Manifest::default(),
            profile: "p".into(),
            producer: "javac".into(),
            created: 0,
        };
        let mut facts = vec![
            make(
                json!({"id":"site","kind":"call_site","owner":owner,"origin":"javac","status":"resolved","attributes":{"mechanism":"invoke"}}),
            ),
            make(
                json!({"id":"binding","kind":"call_target","owner":owner,"origin":"javac","parent":"site","target":"method","status":"resolved","declaration_resolved":true,"evidence_complete":true}),
            ),
            make(
                json!({"id":"method","kind":"declaration","owner":owner,"origin":"javac","parent":"class","signature":"Class#run()","attributes":{"element_kind":"METHOD","modifiers":["static"]}}),
            ),
            make(
                json!({"id":"class","kind":"declaration","owner":owner,"origin":"javac","attributes":{"element_kind":"CLASS","modifiers":[]}}),
            ),
            make(
                json!({"id":"child","kind":"declaration","owner":owner,"origin":"javac","parent":"child-class","signature":"Child#run()","attributes":{"element_kind":"METHOD","modifiers":[]}}),
            ),
            make(
                json!({"id":"edge","kind":"relation","owner":owner,"origin":"javac","parent":"child","target":"method","relation":"overrides"}),
            ),
            make(
                json!({"id":"child-class","kind":"declaration","owner":owner,"attributes":{"element_kind":"CLASS","modifiers":[]}}),
            ),
            make(
                json!({"id":"type-edge","kind":"relation","owner":owner,"parent":"child-class","target":"class","relation":"extends","evidence_complete":true,"declaration_resolved":true}),
            ),
        ];
        let request = Request {
            operation: "dispatch".into(),
            selector: "site".into(),
            ..Default::default()
        };
        let exact = run(
            &MemoryReader {
                revision: revision.clone(),
                facts: facts.clone(),
            },
            &request,
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(exact.records.len(), 1);
        assert_eq!(exact.records[0]["possible"], false);
        assert!(exact.evidence.semantic);
        facts[2].attributes.insert("modifiers".into(), json!([]));
        let virtual_call = run(&MemoryReader { revision, facts }, &request, &|_| {
            bail!("no source")
        })
        .unwrap();
        assert_eq!(virtual_call.records.len(), 2);
        assert!(
            virtual_call
                .records
                .iter()
                .all(|record| record["possible"] == true)
        );
        assert!(!virtual_call.evidence.semantic);
    }
    #[test]
    fn upstream_search_selection_matches_multiple_globs_with_literal_separators() {
        let mut reader = navigation_fixture();
        let template = reader.revision.manifest.files[0].clone();
        reader.revision.manifest.files = [
            "src/A1.java",
            "src/main/java/A2.java",
            "docs/README.md",
            "docs/sub/README.md",
            "src/main/A.java",
            "",
        ]
        .into_iter()
        .map(|path| SourceUnit {
            path: path.into(),
            ..template.clone()
        })
        .collect();
        let request = Request {
            operation: "search-code".into(),
            paths: vec!["src/**/A?.java".into(), "docs/*.md".into()],
            ..Default::default()
        };
        preflight(&request).unwrap();
        let selected = selector_filter(&reader, &request).unwrap();
        assert_eq!(
            selected.sources,
            vec!["docs/README.md", "src/A1.java", "src/main/java/A2.java"]
        );
    }
    #[test]
    fn upstream_search_selection_owns_paths_and_rejects_invalid_patterns() {
        let mut paths = vec!["src/**/A.java".to_owned()];
        let request = Request {
            operation: "search-code".into(),
            paths: paths.clone(),
            ..Default::default()
        };
        paths.clear();
        assert_eq!(request.paths, vec!["src/**/A.java"]);
        preflight(&request).unwrap();
        let mut reader = navigation_fixture();
        reader.revision.manifest.files[0].path = "src/main/A.java".into();
        assert_eq!(
            selector_filter(&reader, &request).unwrap().sources,
            vec!["src/main/A.java"]
        );
        assert_eq!(
            serde_json::to_value(request.clone()).unwrap(),
            serde_json::to_value(&request).unwrap()
        );
        for invalid in [
            "",
            "/absolute.java",
            "src/../A.java",
            "src//A.java",
            "src\\A.java",
        ] {
            assert!(
                preflight(&Request {
                    paths: vec![invalid.into()],
                    ..request.clone()
                })
                .is_err(),
                "{invalid}"
            );
        }
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
