//! Query semantics operate on a fixed read port, independent of SQLite and RPC.
use anyhow::{Result, bail, ensure};
mod annotations;
mod calls;
mod dispatch;
mod hierarchy;
mod navigation;
mod overview;
mod pipeline;
mod source;
mod text;
use codeana_model::QueryRequest as Request;
use codeana_model::*;
#[cfg(test)]
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use text::text_matcher;
pub use text::{stream_text, stream_text_candidates};
fn select(r: &dyn GraphAccess, f: FactFilter) -> Result<Vec<Fact>> {
    r.select(&f).map_err(anyhow::Error::new)
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
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::OverviewRelationInvalid,
                item.to_string()
            )),
        };
        selected.extend(relations.iter().map(|relation| (*relation).to_owned()));
    }
    Ok(selected.into_iter().collect())
}
fn hierarchy_options(
    operation: Operation,
    instantiability: Instantiability,
    transitive: bool,
) -> Result<()> {
    ensure!(
        instantiability == Instantiability::Default
            || operation == Operation::RuntimeImplementations,
        DomainError::new(ErrorCode::InstantiabilityRequiresRuntimeImplementations, "")
    );
    ensure!(
        !transitive
            || matches!(
                operation,
                Operation::TypeRelations | Operation::CallableRelations
            ),
        DomainError::new(ErrorCode::TransitiveRequiresHierarchyRelations, "")
    );
    Ok(())
}
fn preflight_query(request: &Request) -> Result<()> {
    dispatch::options(request.operation, request.algorithm, request.analysis_world)?;
    hierarchy_options(
        request.operation,
        request.instantiability,
        request.transitive,
    )?;
    ensure!(
        !request.meta || request.operation == codeana_model::Operation::Annotations,
        codeana_model::DomainError::new(codeana_model::ErrorCode::MetaRequiresAnnotations, "")
    );
    if request.operation == codeana_model::Operation::References && !request.relation.is_empty() {
        let relations = overview_relations(&request.relation)?;
        ensure!(
            relations
                .iter()
                .all(|r| !matches!(r.as_str(), "calls" | "extends" | "implements")),
            codeana_model::DomainError::new(codeana_model::ErrorCode::ReferenceRelationInvalid, "")
        );
    }
    if request.operation == codeana_model::Operation::Overview
        && request.overview_section == "dependencies"
    {
        overview_relations(&request.relation)?;
    }
    if !request.overview_section.is_empty() || request.overview_depth > 0 {
        ensure!(
            request.operation == codeana_model::Operation::Overview,
            codeana_model::DomainError::new(codeana_model::ErrorCode::OverviewSectionOperation, "")
        );
        ensure!(
            matches!(
                request.overview_section.as_str(),
                "" | "project" | "modules" | "packages" | "dependencies"
            ),
            codeana_model::DomainError::new(codeana_model::ErrorCode::OverviewSectionInvalid, "")
        );
        ensure!(
            request.overview_depth <= 128,
            codeana_model::DomainError::new(codeana_model::ErrorCode::OverviewDepthBudget, "")
        );
    }
    ensure!(
        request.text_context <= 100,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::TextContextBudget,
            "maximum 100".to_string()
        )
    );
    if request.text_regex || request.text_ignore_case || request.text_context > 0 {
        ensure!(
            request.operation == codeana_model::Operation::SearchText
                || (request.operation == codeana_model::Operation::Pipeline
                    && request
                        .stages
                        .first()
                        .is_some_and(|s| s.operation == codeana_model::Operation::SearchText)),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::TextOptionsRequireSearchText,
                ""
            )
        );
    }
    if request.operation == codeana_model::Operation::SearchText {
        text_matcher(request)?;
    }
    ensure!(
        request.package.is_empty() || request.package_prefix.is_empty(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::PackageFilterConflict, "")
    );
    ensure!(
        request.paths.len() <= 64,
        codeana_model::DomainError::new(codeana_model::ErrorCode::PathFilterBudget, "")
    );
    for path in &request.paths {
        ensure!(
            !path.starts_with('/')
                && !path.contains('\\')
                && path.split('/').all(|part| !matches!(part, "" | "." | "..")),
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidPathFilter, "")
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
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidVisibility, "")
    );
    ensure!(
        request.depth <= 32,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::DepthBudget,
            "maximum 32".to_string()
        )
    );
    ensure!(
        request.max_records <= 10000,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::RecordBudget,
            "maximum 10000".to_string()
        )
    );
    ensure!(
        request.stages.len() <= 16,
        codeana_model::DomainError::new(codeana_model::ErrorCode::PipelineStageBudget, "")
    );
    ensure!(
        request.direction.is_empty()
            || matches!(request.direction.as_str(), "in" | "out")
            || (request.operation == codeana_model::Operation::Overview
                && request.direction == codeana_model::Direction::Both),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidDirection, "")
    );
    if request.operation == codeana_model::Operation::Pipeline {
        ensure!(
            !request.stages.is_empty(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::PipelineRequired, "")
        );
        let mut ty = StageRecordType::Start;
        for (i, s) in request.stages.iter().enumerate() {
            dispatch::options(s.operation, s.algorithm, s.analysis_world)?;
            hierarchy_options(s.operation, s.instantiability, s.transitive)?;
            ensure!(
                !s.meta || s.operation == codeana_model::Operation::Annotations,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::MetaRequiresAnnotations,
                    ""
                )
            );
            ensure!(
                s.direction.is_empty()
                    || matches!(s.direction.as_str(), "in" | "out")
                    || (s.operation == codeana_model::Operation::Overview
                        && s.direction == codeana_model::Direction::Both),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::InvalidStageDirection,
                    ""
                )
            );
            if s.operation == codeana_model::Operation::References && !s.relation.is_empty() {
                let selected = overview_relations(&s.relation)?;
                ensure!(
                    selected
                        .iter()
                        .all(|r| !matches!(r.as_str(), "calls" | "extends" | "implements")),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::ReferenceRelationInvalid,
                        ""
                    )
                );
            }
            ensure!(
                s.depth <= 32,
                codeana_model::DomainError::new(codeana_model::ErrorCode::DepthBudget, "")
            );
            ensure!(
                OPERATIONS.contains(&s.operation.as_str())
                    && s.operation != codeana_model::Operation::Pipeline,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::UnsupportedStage,
                    format!("{}", s.operation)
                )
            );
            if i > 0 {
                ensure!(
                    !matches!(
                        s.operation.as_str(),
                        "search-code" | "overview" | "search-text"
                    ),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::PipelineStageDoesNotConsumeInput,
                        ""
                    )
                );
                ensure!(
                    matches!(ty, StageRecordType::Entity | StageRecordType::Site)
                        || (ty == StageRecordType::Edge && !s.endpoint.is_empty()),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::PipelineTypeMismatch,
                        format!("{ty} -> {}", s.operation)
                    )
                );
                ensure!(
                    s.selector.is_empty(),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::PipelineSelectorOverridesInput,
                        ""
                    )
                );
            }
            ensure!(
                s.endpoint.is_empty()
                    || (i > 0
                        && matches!(ty, StageRecordType::Edge | StageRecordType::Site)
                        && matches!(
                            s.endpoint.as_str(),
                            "caller" | "callee" | "parent" | "target" | "site"
                        )),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::PipelineInvalidEndpoint,
                    ""
                )
            );
            ty = match s.operation.as_str() {
                "source" | "search-text" => StageRecordType::Text,
                "calls" | "type-relations" | "callable-relations" | "trace" => {
                    StageRecordType::Edge
                }
                "call-sites" | "references" => StageRecordType::Site,
                _ => StageRecordType::Entity,
            };
        }
    }
    Ok(())
}
pub fn resolve(r: &dyn GraphAccess, selector: &str, unique: bool) -> Result<Vec<Fact>> {
    ensure!(
        !selector.is_empty(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::SelectorRequired, "")
    );
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
    ensure!(
        !facts.is_empty(),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::SymbolNotFound,
            selector.to_string()
        )
    );
    ensure!(
        !unique || facts.len() == 1,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::AmbiguousSelector,
            selector.to_string()
        )
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
fn selector_filter(r: &dyn GraphAccess, req: &Request) -> Result<FactFilter> {
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
fn resolve_scoped(r: &dyn GraphAccess, req: &Request) -> Result<Vec<Fact>> {
    ensure!(
        !req.selector.is_empty() || !req.signature.is_empty(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::SelectorRequired, "")
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
    ensure!(
        !facts.is_empty(),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::SymbolNotFound,
            req.selector.to_string()
        )
    );
    let internal = facts
        .iter()
        .any(|fact| fact.is_public_entity() && fact.kind != FactKind::ExternalSymbol);
    if internal {
        facts.retain(|fact| fact.kind != FactKind::ExternalSymbol);
    }
    ensure!(
        !req.unique || facts.len() == 1,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::AmbiguousSelector,
            req.selector.to_string()
        )
    );
    Ok(facts)
}
fn selection_evidence(r: &dyn SourceContext, req: &Request) -> Evidence {
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

    e
}
fn coverage(r: &dyn GraphAccess, req: &Request) -> Result<Evidence> {
    let mut e = selection_evidence(r, req);
    let mut filter = scope_filter(req);
    filter.kinds = vec![FactKind::Coverage];
    // Incoming callers can originate in any captured module. Their coverage matters too.
    if req.operation == codeana_model::Operation::RuntimeImplementations
        || (req.direction == codeana_model::Direction::Incoming
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
fn allowed(r: &dyn GraphAccess, req: &Request, f: &Fact) -> bool {
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
fn fact_source(r: &dyn SourceQueryReader, f: &Fact) -> Result<(SourceUnit, Option<Span>)> {
    if f.attributes.source_available.as_ref() != Some(&false) {
        return r
            .manifest()
            .files
            .iter()
            .find(|s| s.path == f.source)
            .cloned()
            .map(|s| {
                r.source_span(f)
                    .map(|span| (s, span))
                    .map_err(anyhow::Error::new)
            })
            .transpose()?
            .ok_or_else(|| {
                anyhow::anyhow!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::SourceUnavailable,
                    format!("{} origin={}", f.id, f.origin)
                ))
            });
    }
    ensure!(
        f.kind == FactKind::ExternalSymbol && f.source.is_empty(),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::SourceUnavailable,
            f.id.to_string()
        )
    );
    let origin = f.attributes.source_origin.as_ref().ok_or_else(|| {
        anyhow::anyhow!(DomainError::new(
            ErrorCode::SourceUnavailable,
            f.id.to_string()
        ))
    })?;
    let source = r
        .manifest()
        .files
        .iter()
        .find(|s| {
            s.path == origin.path
                && s.hash == origin.hash
                && s.world == origin.world
                && s.module == origin.module
                && s.scope == origin.scope
        })
        .cloned()
        .ok_or_else(|| {
            anyhow::anyhow!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::SourceOriginMismatch,
                f.id.to_string()
            ))
        })?;
    ensure!(
        !f.signature.is_empty(),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::SourceUnavailable,
            f.id.to_string()
        )
    );
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
            && d.attributes.source_available.as_ref() != Some(&false)
    })
    .collect::<Vec<_>>();
    ensure!(
        candidates.len() == 1,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::SourceUnavailable,
            format!("{} candidates={}", f.id, candidates.len())
        )
    );
    Ok((source, candidates[0].span.clone()))
}

fn values(facts: impl IntoIterator<Item = Fact>) -> Vec<Record> {
    facts.into_iter().map(Record::from).collect()
}
fn call_edges_many(
    r: &dyn GraphAccess,
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
            let by_id: BTreeMap<_, _> = sites.into_iter().map(|s| (s.id.to_string(), s)).collect();
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
                        .push(s.id.to_string());
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
                    parents: chunk.iter().map(|s| s.id.to_string()).collect(),
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
                        .push(s.id.to_string());
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
fn stream_search_query(
    r: &dyn GraphReader,
    req: &Request,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<Output> {
    preflight_query(req)?;
    ensure!(
        req.operation == codeana_model::Operation::SearchCode,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::StreamSearchOperationRequired,
            ""
        )
    );
    let evidence = coverage(r, req)?;
    let filter = selector_filter(r, req)?;
    let mut after = String::new();
    loop {
        let batch = r
            .scan_symbols(&req.selector, &filter, &after, 512)
            .map_err(anyhow::Error::new)?;
        ensure!(
            batch.len() <= 512,
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidScanBatchResult, "")
        );
        if batch.is_empty() {
            break;
        }
        for fact in &batch {
            ensure!(
                fact.id > after,
                codeana_model::DomainError::new(codeana_model::ErrorCode::UnorderedScanResult, "")
            );
            after.clear();
            after.push_str(fact.id.as_str());
        }
        let complete = batch.len() < 512;
        sink(values(batch))?;
        if complete {
            break;
        }
    }
    Ok(Output {
        revision: r.revision().id.to_string(),
        producer: r.revision().producer.clone(),
        evidence,
        ..Default::default()
    })
}
fn run_query(
    r: &dyn GraphReader,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output> {
    if req.operation == codeana_model::Operation::SearchText {
        let mut records = Vec::new();
        let mut output = stream_text(r, req, read_source, &mut |batch| {
            ensure!(
                records.len() + batch.len() <= MAX_FACTS,
                codeana_model::DomainError::new(codeana_model::ErrorCode::TextWorkBudget, "")
            );
            records.extend(batch);
            Ok(())
        })?;
        output.records = records;
        return Ok(output);
    }
    if req.operation == codeana_model::Operation::SearchCode {
        let mut records = Vec::new();
        let mut output = stream_search_query(r, req, &mut |batch| {
            records.extend(batch);
            Ok(())
        })?;
        output.records = records;
        return Ok(output);
    }
    preflight_query(req)?;
    let mut evidence = coverage(r, req)?;
    let mut records;
    if req.operation == codeana_model::Operation::Pipeline {
        records = pipeline::run(r, req, read_source, &mut evidence)?;
    } else {
        match req.operation {
            Operation::Resolve => {
                records = navigation::resolve(r, req, &mut evidence)?;
            }
            Operation::SearchCode => {
                records = values(
                    r.search_symbols_filtered(&req.selector, &selector_filter(r, req)?)
                        .map_err(anyhow::Error::new)?,
                );
            }
            Operation::Overview => {
                records = overview::run(r, req, &mut evidence)?;
            }
            Operation::Source => {
                records = source::run(r, req, &mut evidence, read_source)?;
            }
            Operation::SearchText => unreachable!("text search returns from streaming entry"),
            Operation::Structure => {
                records = navigation::structure(r, req, &mut evidence)?;
            }
            Operation::References | Operation::CallSites | Operation::Annotations => {
                records = navigation::occurrences(r, req, &mut evidence)?;
            }
            Operation::Trace => {
                records = calls::trace(r, req, &mut evidence)?;
            }
            Operation::Calls => {
                records = calls::run(r, req, &mut evidence)?;
            }
            Operation::RuntimeImplementations => {
                records = hierarchy::runtime(r, req, resolve_scoped(r, req)?, &mut evidence)?;
            }
            Operation::TypeRelations | Operation::CallableRelations => {
                records = hierarchy::relations(r, req, resolve_scoped(r, req)?, &mut evidence)?;
            }
            Operation::Dispatch => {
                records = dispatch::run(r, req, &mut evidence)?;
            }
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnsupportedOperation,
                format!("{}", req.operation)
            )),
        }
    }
    records.sort_by(Record::query_cmp);
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
        revision: r.revision().id.to_string(),
        producer: r.revision().producer.clone(),
        records,
        evidence,
        cursor: String::new(),
    })
}

/// Transport DTOs and typed domain requests share the same checked query entry.
pub fn preflight<'a, R>(request: &'a R) -> Result<()>
where
    Request: From<&'a R>,
{
    preflight_query(&Request::from(request))
}
pub fn run<'a, R>(
    reader: &dyn GraphReader,
    request: &'a R,
    source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output>
where
    Request: From<&'a R>,
{
    run_query(reader, &Request::from(request), source)
}
pub fn stream_search<'a, R>(
    reader: &dyn GraphReader,
    request: &'a R,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<Output>
where
    Request: From<&'a R>,
{
    stream_search_query(reader, &Request::from(request), sink)
}

/// FILES snapshots expose captured bytes without implementing a semantic graph port.
pub fn run_files<'a, R>(
    reader: &dyn SourceContext,
    request: &'a R,
    source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output>
where
    Request: From<&'a R>,
{
    let request = Request::from(request);
    preflight_query(&request)?;
    ensure!(
        request.operation == Operation::Source,
        DomainError::new(ErrorCode::AnalysisSnapshotRequired, "")
    );
    let file = reader
        .manifest()
        .files
        .iter()
        .find(|s| s.path == request.selector)
        .ok_or_else(|| DomainError::new(ErrorCode::SourceUnavailable, request.selector.clone()))?;
    let records = source::snippets(&request, vec![(file.clone(), None)], source)?;
    Ok(Output {
        revision: reader.revision().id.to_string(),
        producer: reader.revision().producer.clone(),
        records,
        evidence: selection_evidence(reader, &request),
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
        impl SourceContext for Counted {
            fn manifest(&self) -> &Manifest {
                self.inner.manifest()
            }
            fn revision(&self) -> &Revision {
                self.inner.revision()
            }
        }
        impl GraphAccess for Counted {
            fn scan(
                &self,
                filter: &FactFilter,
                after: &str,
                limit: usize,
            ) -> std::result::Result<Vec<Fact>, DomainError> {
                self.inner.scan(filter, after, limit)
            }

            fn scan_symbols(
                &self,
                text: &str,
                filter: &FactFilter,
                after: &str,
                limit: usize,
            ) -> std::result::Result<Vec<Fact>, DomainError> {
                self.inner.scan_symbols(text, filter, after, limit)
            }

            fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, DomainError> {
                assert!(
                    filter.ids.len() <= 500
                        && filter.parents.len() <= 500
                        && filter.targets.len() <= 500
                );
                self.reads.set(self.reads.get() + 1);
                self.inner.select(filter)
            }
        }
        impl SourceLocator for Counted {}
        impl AnnotationReader for Counted {}
        impl OverviewReader for Counted {}
        impl DependencyReader for Counted {}
        let mut inner = hierarchy_fixture();
        inner.facts.retain(|fact| fact.id == "api");
        let template = inner.facts[0].clone();
        let mut root_method = template.clone();
        root_method.id = "api#run()".into();
        root_method.signature = root_method.id.to_string();
        root_method.parent = "api".into();
        root_method.attributes =
            serde_json::from_value(json!({"element_kind":"METHOD","modifiers":["abstract"]}))
                .unwrap();
        inner.facts.push(root_method.clone());
        for number in 0..1200 {
            let mut class = template.clone();
            class.id = format!("impl{number}").into();
            class.signature = class.id.to_string();
            class.attributes =
                serde_json::from_value(json!({"element_kind":"CLASS","modifiers":[]})).unwrap();
            let mut method = root_method.clone();
            method.id = format!("{}#run()", class.id).into();
            method.signature = method.id.to_string();
            method.parent = class.id.to_string();
            method
                .attributes
                .set_wire_field("modifiers".into(), json!([]));
            let mut type_edge = class.clone();
            type_edge.id = format!("type{number}").into();
            type_edge.kind = FactKind::Relation;
            type_edge.parent = class.id.to_string();
            type_edge.target = "api".into();
            type_edge.relation = "implements".into();
            let mut override_edge = type_edge.clone();
            override_edge.id = format!("override{number}").into();
            override_edge.parent = method.id.to_string();
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
                operation: codeana_model::Operation::Dispatch,
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
            method.id = format!("{owner}#run()").into();
            method.signature = method.id.to_string();
            method.parent = owner.into();
            method
                .attributes
                .set_wire_field("element_kind".into(), json!("METHOD"));
            method
                .attributes
                .set_wire_field("entity_kind".into(), json!("callable"));
            method.attributes.set_wire_field(
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
            edge.id = format!("override:{child}").into();
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
            .set_wire_field("receiver_static_type".into(), json!("final"));
        site.attributes
            .set_wire_field("mechanism".into(), json!("invoke"));
        reader.facts.push(site.clone());
        site.id = "runtime-binding".into();
        site.kind = FactKind::CallTarget;
        site.parent = "runtime-site".into();
        site.target = "api#run()".into();
        reader.facts.push(site);
        let result = run(
            &reader,
            &Request {
                operation: codeana_model::Operation::Dispatch,
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
                .map(|row| row.wire().unwrap()["target"].as_str().unwrap().to_owned())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["api#run()".to_owned(), "final#run()".to_owned()])
        );
        let candidate = result
            .records
            .iter()
            .find(|row| row.wire().unwrap()["target"] == "final#run()")
            .unwrap();
        assert_eq!(candidate.wire().unwrap()["possible"], true);
        assert_eq!(candidate.wire().unwrap()["algorithm"], "CHA");
        assert_eq!(
            candidate.wire().unwrap()["proof"],
            json!([
                "runtime-binding",
                "override:abstract",
                "override:concrete",
                "override:final"
            ])
        );
        assert_eq!(candidate.wire().unwrap()["type_proof"], json!([]));
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
            operation: codeana_model::Operation::RuntimeImplementations,
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
                .map(|r| r.wire().unwrap()["id"].as_str().unwrap().to_owned())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["concrete".to_owned(), "final".to_owned()])
        );
        let leaf = result
            .records
            .iter()
            .find(|r| r.wire().unwrap()["id"] == "final")
            .unwrap();
        assert_eq!(
            leaf.wire().unwrap()["runtime_implementation"]["proof"],
            json!(["e1", "e2", "e3"])
        );
        assert_eq!(
            leaf.wire().unwrap()["runtime_implementation"]["reason"],
            "java.final_class"
        );
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
                operation: codeana_model::Operation::Pipeline,
                stages: vec![
                    Stage {
                        operation: codeana_model::Operation::RuntimeImplementations,
                        selector: "api".into(),
                        depth: 4,
                        ..Default::default()
                    },
                    Stage {
                        operation: codeana_model::Operation::Resolve,
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
            operation: codeana_model::Operation::RuntimeImplementations,
            selector: "api".into(),
            depth: 4,
            instantiability: codeana_model::Instantiability::Unknown,
            module: "a".into(),
            ..Default::default()
        };
        let result = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(result.records.len(), 1);
        assert_eq!(result.records[0].wire().unwrap()["id"], "unknown");
        let all = run(
            &reader,
            &Request {
                instantiability: codeana_model::Instantiability::All,
                ..request.clone()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(all.records.len(), 4);
        assert!(
            all.records
                .iter()
                .any(|row| row.wire().unwrap()["id"] == "abstract"
                    && row.wire().unwrap()["runtime_implementation"]["instantiability"] == "no")
        );
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
        assert!("no".parse::<Instantiability>().is_err());
        assert!(
            preflight(&Request {
                operation: codeana_model::Operation::Resolve,
                ..request
            })
            .is_err()
        );
    }
    #[test]
    fn transitive_hierarchy_preserves_direct_default_cycles_and_depth_limits() {
        let reader = hierarchy_fixture();
        let request = Request {
            operation: codeana_model::Operation::TypeRelations,
            selector: "api".into(),
            direction: codeana_model::Direction::Incoming,
            module: "a".into(),
            depth: 4,
            ..Default::default()
        };
        let direct = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(direct.records.len(), 2);
        assert!(
            direct
                .records
                .iter()
                .all(|row| row.wire().unwrap().get("depth").is_none())
        );
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
                operation: codeana_model::Operation::Calls,
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
                operation: codeana_model::Operation::CallableRelations,
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
            operation: codeana_model::Operation::References,
            selector: "caller".into(),
            direction: codeana_model::Direction::Outgoing,
            relation: "reads".into(),
            ..Default::default()
        };
        let result = run(&reader, &request, &|_| bail!("no source")).unwrap();
        assert_eq!(result.records.len(), 2);
        assert!(
            result
                .records
                .iter()
                .all(|row| row.wire().unwrap()["relation"] == "reads")
        );
        let incoming = run(
            &reader,
            &Request {
                selector: "callee".into(),
                module: "b".into(),
                direction: codeana_model::Direction::Incoming,
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
                .all(|row| row.wire().unwrap()["source"] == "a/Api.java")
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
            operation: codeana_model::Operation::Overview,
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
                fact.id = format!("symbol-{number:05}").into();
                fact.name = "same".into();
                fact
            })
            .collect();
        let output = run(
            &reader,
            &Request {
                operation: codeana_model::Operation::SearchCode,
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
        direct
            .attributes
            .set_wire_field("annotation".into(), json!("A0"));
        reader.facts.push(direct);
        for index in 0..18 {
            let mut meta = reader.facts[0].clone();
            meta.id = format!("meta{index}").into();
            meta.kind = FactKind::Facet;
            meta.relation = "java.annotation.meta".into();
            meta.attributes
                .set_wire_field("annotation".into(), json!(format!("A{index}")));
            meta.attributes
                .set_wire_field("meta_annotation".into(), json!(format!("A{}", index + 1)));
            meta.attributes
                .set_wire_field("raw_name".into(), json!("z"));
            reader.facts.push(meta);
        }
        let mut duplicate = reader.facts[reader.facts.len() - 18].clone();
        duplicate.id = "duplicate-producer".into();
        duplicate
            .attributes
            .set_wire_field("raw_name".into(), json!("a"));
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
                .find(|row| row.wire().unwrap()["attributes"]["annotation"] == "A1")
                .unwrap()
                .wire()
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
                .set_wire_field("annotation".into(), json!(annotation));
            fact.attributes
                .set_wire_field("meta_annotation".into(), json!(meta));
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
            .map(|r| {
                r.wire().unwrap()["attributes"]["via"]
                    .as_str()
                    .unwrap()
                    .to_owned()
            })
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
                operation: codeana_model::Operation::Annotations,
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
                    operation: codeana_model::Operation::Structure,
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
                    .map(|row| row.wire().unwrap()["id"].as_str().unwrap().to_owned())
                    .collect::<Vec<_>>(),
                ["bean", "property"]
            );
        }
        assert!(
            run(
                &reader,
                &Request {
                    operation: codeana_model::Operation::Resolve,
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
                operation: codeana_model::Operation::CallSites,
                selector: "callee".into(),
                module: "b".into(),
                direction: codeana_model::Direction::Incoming,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(
            output
                .records
                .iter()
                .map(|r| r.wire().unwrap()["id"].as_str().unwrap().to_owned())
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
        assert_eq!(output.records[0].wire().unwrap()["path"], "b/Api.java");
        assert_eq!(output.records[0].wire().unwrap()["text"], "class Api {}");
    }
    #[test]
    fn trace_returns_a_bounded_start_to_target_path() {
        let reader = navigation_fixture();
        let output = run(
            &reader,
            &Request {
                operation: codeana_model::Operation::Trace,
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
            output.records[0].wire().unwrap()["nodes"],
            json!(["caller", "callee", "end"])
        );
        assert_eq!(
            output.records[0].wire().unwrap()["hops"][0]["sites"],
            json!(["site1"])
        );
        let unreachable = run(
            &reader,
            &Request {
                operation: codeana_model::Operation::Trace,
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
                operation: codeana_model::Operation::Trace,
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
    impl SourceContext for MemoryReader {
        fn manifest(&self) -> &Manifest {
            &self.revision.manifest
        }
        fn revision(&self) -> &Revision {
            &self.revision
        }
    }
    impl GraphAccess for MemoryReader {
        fn scan(
            &self,
            filter: &FactFilter,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            let mut facts = self.select(filter)?;
            facts.retain(|f| f.id.as_str() > after);
            facts.sort_by(|a, b| a.id.cmp(&b.id));
            facts.truncate(limit);
            Ok(facts)
        }

        fn scan_symbols(
            &self,
            text: &str,
            filter: &FactFilter,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            let mut facts = self.search_symbols_filtered(text, filter)?;
            facts.retain(|f| f.id.as_str() > after);
            facts.sort_by(|a, b| a.id.cmp(&b.id));
            facts.truncate(limit);
            Ok(facts)
        }

        fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, DomainError> {
            Ok(self
                .facts
                .iter()
                .filter(|f| {
                    (filter.ids.is_empty() || filter.ids.iter().any(|id| id == f.id.as_str()))
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
    }
    impl SourceLocator for MemoryReader {}
    impl AnnotationReader for MemoryReader {}
    impl OverviewReader for MemoryReader {}
    impl DependencyReader for MemoryReader {}
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
                operation: codeana_model::Operation::Resolve,
                selector: "Api".into(),
                module: "b".into(),
                unique: true,
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(result.records.len(), 1);
        assert_eq!(result.records[0].wire().unwrap()["id"], "b");
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
            operation: codeana_model::Operation::Source,
            selector: auxiliary.id.to_string(),
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
            output.records[0].wire().unwrap()["text"],
            &text[start as usize..end as usize]
        );
        assert_eq!(output.records[0].wire().unwrap()["path"], source.path);
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
                        .source_origin
                        .as_mut()
                        .unwrap()
                        .hash = "stale".into()
                }
                "wrong-module" => {
                    altered.facts[1]
                        .attributes
                        .source_origin
                        .as_mut()
                        .unwrap()
                        .module = "other".into()
                }
                "wrong-scope" => {
                    altered.facts[1]
                        .attributes
                        .source_origin
                        .as_mut()
                        .unwrap()
                        .scope = "TEST".into()
                }
                "wrong-world" => altered.facts[0].owner.world = "other-world".into(),
                "binary-only" => {
                    altered.facts[1]
                        .attributes
                        .remove_wire_field("source_origin");
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
                operation: codeana_model::Operation::Calls,
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
                operation: codeana_model::Operation::Dispatch,
                selector: "site".into(),
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(dispatch.records[0].wire().unwrap()["possible"], false);
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
                operation: codeana_model::Operation::Calls,
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
                operation: codeana_model::Operation::Dispatch,
                selector: "site".into(),
                ..Default::default()
            },
            &|_| bail!("no source"),
        )
        .unwrap();
        assert_eq!(dispatch.records.len(), 1);
        assert_eq!(
            dispatch.records[0].wire().unwrap()["binding_origin"],
            "reflection_model"
        );
        assert_eq!(dispatch.records[0].wire().unwrap()["basis"], "REFLECTION");
        assert_eq!(
            dispatch.records[0].wire().unwrap()["operation"],
            "METHOD_INVOKE"
        );
        assert_eq!(dispatch.records[0].wire().unwrap()["possible"], true);
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
                    operation: codeana_model::Operation::Calls,
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
                    operation: codeana_model::Operation::Dispatch,
                    selector: "site".into(),
                    ..Default::default()
                },
                &|_| bail!("no source"),
            )
            .unwrap();
            assert_eq!(
                dispatch.records[0].wire().unwrap()["binding_origin"],
                origin
            );
            assert_eq!(
                dispatch.records[0].wire().unwrap()["basis"],
                "modeled_expression"
            );
            assert_eq!(dispatch.records[0].wire().unwrap()["recovery_phase"], 1);
            assert_eq!(dispatch.records[0].wire().unwrap()["possible"], true);
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
            operation: codeana_model::Operation::Dispatch,
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
        assert_eq!(exact.records[0].wire().unwrap()["possible"], false);
        assert!(exact.evidence.semantic);
        facts[2]
            .attributes
            .set_wire_field("modifiers".into(), json!([]));
        let virtual_call = run(&MemoryReader { revision, facts }, &request, &|_| {
            bail!("no source")
        })
        .unwrap();
        assert_eq!(virtual_call.records.len(), 2);
        assert!(
            virtual_call
                .records
                .iter()
                .all(|record| record.wire().unwrap()["possible"] == true)
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
            operation: codeana_model::Operation::SearchCode,
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
            operation: codeana_model::Operation::SearchCode,
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
            operation: codeana_model::Operation::Pipeline,
            stages: vec![
                Stage {
                    operation: codeana_model::Operation::Source,
                    selector: "a".into(),
                    ..Default::default()
                },
                Stage {
                    operation: codeana_model::Operation::Calls,
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        assert!(preflight(&r).is_err());
    }
}
