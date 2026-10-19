//! JavaDispatchService's receiver/override/default-method rules, using directed
//! reads rather than loading every declaration and hierarchy edge in a project.
use super::*;

pub(super) fn options(
    operation: Operation,
    algorithm: DispatchAlgorithm,
    world: AnalysisWorld,
) -> Result<()> {
    ensure!(
        algorithm == DispatchAlgorithm::Default || operation == Operation::Dispatch,
        DomainError::new(ErrorCode::AlgorithmRequiresDispatch, "")
    );
    ensure!(
        world == AnalysisWorld::Default
            || matches!(
                operation,
                Operation::Dispatch | Operation::RuntimeImplementations
            ),
        DomainError::new(ErrorCode::AnalysisWorldRequiresJavaCandidates, "")
    );
    Ok(())
}

fn kind(fact: &Fact) -> &str {
    fact.attributes
        .element_kind
        .as_ref()
        .map(String::as_str)
        .unwrap_or("")
}
fn modifier(fact: &Fact, name: &str) -> bool {
    fact.attributes.has_modifier(name)
}
fn load(
    reader: &dyn GraphAccess,
    ids: &[String],
    cache: &mut BTreeMap<String, Fact>,
) -> Result<()> {
    let missing = ids
        .iter()
        .filter(|id| !cache.contains_key(*id))
        .cloned()
        .collect::<Vec<_>>();
    for batch in missing.chunks(500) {
        for fact in select(
            reader,
            FactFilter {
                ids: batch.to_vec(),
                ..Default::default()
            },
        )? {
            cache.insert(fact.id.to_string(), fact);
        }
        ensure!(
            cache.len() <= 10000,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::DispatchDeclarationWorkBudget,
                ""
            )
        );
    }
    Ok(())
}

#[derive(Clone, Copy)]
struct Traversal {
    incoming: bool,
    callable: bool,
    depth: u32,
}

type Graph = BTreeMap<(bool, bool, String), Vec<Fact>>;

fn adjacent_cached(
    reader: &dyn GraphAccess,
    ids: &[String],
    traversal: Traversal,
    graph: &mut Graph,
) -> Result<Vec<Fact>> {
    let missing = ids
        .iter()
        .filter(|id| !graph.contains_key(&(traversal.incoming, traversal.callable, (*id).clone())))
        .cloned()
        .collect::<Vec<_>>();
    let kinds = if traversal.callable {
        &["overrides", "implements_method"][..]
    } else {
        &["extends", "implements"][..]
    };
    let edges = hierarchy::adjacent(reader, &missing, traversal.incoming, kinds)?;
    for id in missing {
        graph.insert((traversal.incoming, traversal.callable, id), vec![]);
    }
    for edge in edges {
        let anchor = if traversal.incoming {
            &edge.target
        } else {
            &edge.parent
        };
        graph
            .get_mut(&(traversal.incoming, traversal.callable, anchor.clone()))
            .unwrap()
            .push(edge);
    }
    Ok(ids
        .iter()
        .flat_map(|id| graph[&(traversal.incoming, traversal.callable, id.clone())].clone())
        .collect())
}

fn walk(
    reader: &dyn GraphAccess,
    seed: &str,
    traversal: Traversal,
    evidence: &mut Evidence,
    work: &mut usize,
    cache: &mut BTreeMap<String, Fact>,
    graph: &mut Graph,
) -> Result<BTreeMap<String, Vec<String>>> {
    let Traversal {
        incoming,
        callable,
        depth,
    } = traversal;
    let mut paths = BTreeMap::from([(seed.to_owned(), Vec::new())]);
    let mut frontier = vec![seed.to_owned()];
    for level in 0..=depth.max(1) {
        if frontier.is_empty() {
            break;
        }
        let edges = adjacent_cached(reader, &frontier, traversal, graph)?;
        *work += frontier.len() + edges.len();
        if *work > 100000 || paths.len() > 10000 {
            evidence.enumeration = false;
            evidence.limits.push("work".into());
            break;
        }
        let endpoints = edges
            .iter()
            .map(|edge| {
                if incoming {
                    edge.parent.clone()
                } else {
                    edge.target.clone()
                }
            })
            .collect::<Vec<_>>();
        load(reader, &endpoints, cache)?;
        let mut next_frontier = BTreeSet::new();
        for edge in edges {
            let (current, next) = if incoming {
                (&edge.target, &edge.parent)
            } else {
                (&edge.parent, &edge.target)
            };
            if paths.contains_key(next) {
                continue;
            }
            if !callable
                && cache
                    .get(next)
                    .is_none_or(|fact| fact.kind != FactKind::Declaration)
            {
                if cache
                    .get(next)
                    .is_none_or(|fact| fact.signature != "java.lang.Object")
                {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push("dispatch_hierarchy_incomplete".into());
                }
                continue;
            }
            if level == depth.max(1) {
                evidence.enumeration = false;
                evidence.limits.push("depth".into());
                continue;
            }
            if !edge.evidence_complete || !edge.declaration_resolved {
                evidence.semantic = false;
                evidence
                    .reasons
                    .push("dispatch_hierarchy_incomplete".into());
            }
            let mut path = paths[current].clone();
            path.push(edge.id.to_string());
            paths.insert(next.clone(), path);
            next_frontier.insert(next.clone());
        }
        frontier = next_frontier.into_iter().collect();
    }
    Ok(paths)
}

pub(super) fn candidates(
    reader: &dyn GraphAccess,
    req: &Request,
    site: &Fact,
    binding: &Fact,
    target: Option<&Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let Some(target) = target.filter(|fact| fact.kind == FactKind::Declaration) else {
        return Ok(vec![]);
    };
    if target.parent.is_empty() {
        return Ok(vec![]);
    }
    let mut cache = BTreeMap::from([(target.id.to_string(), target.clone())]);
    load(reader, std::slice::from_ref(&target.parent), &mut cache)?;
    let receiver_name = site
        .attributes
        .receiver_static_type
        .as_ref()
        .or(binding.attributes.receiver_static_type.as_ref())
        .map(String::as_str);
    let mut receiver = target.parent.clone();
    if let Some(name) = receiver_name.filter(|name| !name.is_empty()) {
        let name = name.split('<').next().unwrap();
        load(reader, &[name.to_owned()], &mut cache)?;
        if cache
            .get(name)
            .is_some_and(|fact| fact.kind == FactKind::Declaration)
        {
            receiver = name.into();
        } else {
            let matches = select(
                reader,
                FactFilter {
                    kinds: vec![FactKind::Declaration],
                    signature: Some(name.into()),
                    ..Default::default()
                },
            )?;
            if matches.len() == 1 {
                receiver = matches[0].id.to_string();
                cache.insert(receiver.clone(), matches[0].clone());
            } else {
                evidence.semantic = false;
                evidence.reasons.push("dispatch_receiver_unresolved".into());
            }
        }
    }
    let mut work = 0;
    let mut graph = Graph::new();
    let callable = walk(
        reader,
        &target.id,
        Traversal {
            incoming: true,
            callable: true,
            depth: req.depth,
        },
        evidence,
        &mut work,
        &mut cache,
        &mut graph,
    )?;
    let runtime = walk(
        reader,
        &receiver,
        Traversal {
            incoming: true,
            callable: false,
            depth: req.depth,
        },
        evidence,
        &mut work,
        &mut cache,
        &mut graph,
    )?;
    // Prime upward adjacency and methods for the entire relevant type closure
    // in 500-endpoint batches. Per-runtime precedence then uses only the cache.
    let upward = Traversal {
        incoming: false,
        callable: false,
        depth: req.depth,
    };
    let mut all_types = runtime.keys().cloned().collect::<BTreeSet<_>>();
    let mut frontier = all_types.iter().cloned().collect::<Vec<_>>();
    for _ in 0..=req.depth.max(1) {
        if frontier.is_empty() {
            break;
        }
        let edges = adjacent_cached(reader, &frontier, upward, &mut graph)?;
        work += frontier.len() + edges.len();
        ensure!(
            work <= 100000,
            codeana_model::DomainError::new(codeana_model::ErrorCode::DispatchWorkBudget, "")
        );
        let endpoints = edges
            .iter()
            .map(|edge| edge.target.clone())
            .collect::<Vec<_>>();
        load(reader, &endpoints, &mut cache)?;
        frontier = endpoints
            .into_iter()
            .filter(|id| {
                cache
                    .get(id)
                    .is_some_and(|fact| fact.kind == FactKind::Declaration)
                    && all_types.insert(id.clone())
            })
            .collect();
    }
    let mut method_owners = BTreeSet::new();
    for batch in all_types.into_iter().collect::<Vec<_>>().chunks(500) {
        let methods = select(
            reader,
            FactFilter {
                kinds: vec![FactKind::Declaration],
                parents: batch.to_vec(),
                ..Default::default()
            },
        )?;
        work += methods.len();
        ensure!(
            work <= 100000,
            codeana_model::DomainError::new(codeana_model::ErrorCode::DispatchWorkBudget, "")
        );
        for method in methods.into_iter().filter(|fact| kind(fact) == "METHOD") {
            cache.insert(method.id.to_string(), method);
        }
        method_owners.extend(batch.iter().cloned());
        ensure!(
            cache.len() <= 10000,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::DispatchDeclarationWorkBudget,
                ""
            )
        );
    }
    let mut output = BTreeMap::new();
    for (runtime_id, runtime_proof) in runtime {
        if cache
            .get(&runtime_id)
            .is_none_or(|fact| hierarchy::instantiability(fact).0 != "yes")
        {
            continue;
        }
        let ancestry = walk(
            reader,
            &runtime_id,
            Traversal {
                incoming: false,
                callable: false,
                depth: req.depth,
            },
            evidence,
            &mut work,
            &mut cache,
            &mut graph,
        )?;
        let missing = ancestry
            .keys()
            .filter(|id| !method_owners.contains(*id))
            .cloned()
            .collect::<Vec<_>>();
        for batch in missing.chunks(500) {
            let methods = select(
                reader,
                FactFilter {
                    kinds: vec![FactKind::Declaration],
                    parents: batch.to_vec(),
                    ..Default::default()
                },
            )?;
            work += methods.len();
            ensure!(
                work <= 100000,
                codeana_model::DomainError::new(codeana_model::ErrorCode::DispatchWorkBudget, "")
            );
            for method in methods.into_iter().filter(|fact| kind(fact) == "METHOD") {
                cache.insert(method.id.to_string(), method);
            }
            method_owners.extend(batch.iter().cloned());
            ensure!(
                cache.len() <= 10000,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::DispatchDeclarationWorkBudget,
                    ""
                )
            );
        }
        let slot = target.signature.rsplit_once('#').map(|(_, slot)| slot);
        let matching = cache
            .values()
            .filter(|method| {
                kind(method) == "METHOD"
                    && ancestry.contains_key(&method.parent)
                    && !modifier(method, "static")
                    && !modifier(method, "private")
                    && (callable.contains_key(method.id.as_str())
                        || (slot.is_some()
                            && method.signature.rsplit_once('#').map(|(_, slot)| slot) == slot))
            })
            .cloned()
            .collect::<Vec<_>>();
        let mut classes = matching
            .iter()
            .filter(|method| {
                cache
                    .get(&method.parent)
                    .is_some_and(|parent| kind(parent) != "INTERFACE")
            })
            .collect::<Vec<_>>();
        classes.sort_by_key(|method| (ancestry[&method.parent].len(), &method.id));
        let implementations = if let Some(nearest) = classes.first() {
            if modifier(nearest, "abstract") {
                vec![]
            } else {
                vec![(*nearest).clone()]
            }
        } else {
            let mut defaults = Vec::new();
            for candidate in &matching {
                let mut shadowed = false;
                for other in &matching {
                    if candidate.id == other.id {
                        continue;
                    }
                    if walk(
                        reader,
                        &other.parent,
                        Traversal {
                            incoming: false,
                            callable: false,
                            depth: req.depth,
                        },
                        evidence,
                        &mut work,
                        &mut cache,
                        &mut graph,
                    )?
                    .contains_key(&candidate.parent)
                    {
                        shadowed = true;
                        break;
                    }
                }
                if !shadowed && !modifier(candidate, "abstract") {
                    defaults.push(candidate.clone());
                }
            }
            if defaults.len() > 1 {
                evidence.semantic = false;
                evidence.reasons.push("dispatch_default_ambiguous".into());
            }
            defaults
        };
        for implementation in implementations {
            if implementation.id == target.id || !allowed(reader, req, &implementation) {
                continue;
            }
            let mut proof = vec![binding.id.to_string()];
            proof.extend(
                callable
                    .get(implementation.id.as_str())
                    .into_iter()
                    .flatten()
                    .cloned(),
            );
            let mut type_proof = runtime_proof.clone();
            type_proof.extend(ancestry[&implementation.parent].clone());
            let mut unique = BTreeSet::new();
            type_proof.retain(|edge| unique.insert(edge.clone()));
            output
                .entry(implementation.id.to_string())
                .or_insert(Record::Dispatch(DispatchCandidate {
                    call_site: site.id.to_string(),
                    target: Some(implementation.id.to_string()),
                    proof,
                    type_proof: Some(type_proof),
                    possible: true,
                    candidate: Some("possible".into()),
                    executable: Some(true),
                    algorithm: Some("CHA".into()),
                    analysis_world: Some(if req.analysis_world.is_empty() {
                        "workspace-open".into()
                    } else {
                        req.analysis_world.to_string()
                    }),
                    world: implementation.owner.world.to_string(),
                    ..Default::default()
                }));
        }
    }
    Ok(output.into_values().collect())
}

pub(super) fn run(
    r: &dyn GraphAccess,
    req: &Request,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    let roots = resolve_scoped(r, req)?;
    for root in roots {
        if req.operation == codeana_model::Operation::Dispatch {
            ensure!(
                root.kind == FactKind::CallSite,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::DispatchRequiresCallSite,
                    ""
                )
            );
            let targets = select(
                r,
                FactFilter {
                    kinds: vec![FactKind::CallTarget],
                    parent: Some(root.id.to_string()),
                    ..Default::default()
                },
            )?;
            if root.attributes.language.as_ref().map(String::as_str) == Some("python") {
                ensure!(
                    req.algorithm != codeana_model::DispatchAlgorithm::Cha,
                    "PYTHON_CHA_UNSUPPORTED"
                );
                if targets.is_empty() {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push(format!("python_unresolved_call:{}", root.id));
                }
                for target in targets {
                    let exact = target.status == "resolved" && target.declaration_resolved;
                    if !exact {
                        evidence.semantic = false;
                        evidence
                            .reasons
                            .push(format!("python_partial_binding:{}", target.id));
                    }
                    if req.algorithm == codeana_model::DispatchAlgorithm::Exact && !exact {
                        continue;
                    }
                    records.push(Record::Dispatch(DispatchCandidate {
                        call_site: root.id.to_string(),
                        target: (!target.target.is_empty()).then_some(target.target),
                        binding: Some(target.status),
                        proof: vec![target.id.to_string()],
                        possible: !exact,
                        candidate: Some(if exact { "resolved" } else { "possible" }.into()),
                        algorithm: Some("pyrefly-static".into()),
                        world: target.owner.world.to_string(),
                        ..Default::default()
                    }));
                }
                continue;
            }
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
                            .call_kind
                            .as_ref()
                            .or(t.attributes.via.as_ref())
                    } else {
                        t.attributes
                            .via
                            .as_ref()
                            .or(t.attributes.recovery_input.as_ref())
                    };
                    records.push(Record::Dispatch(DispatchCandidate {
                        call_site: root.id.to_string(),
                        target: Some(t.target.clone()),
                        binding: Some(t.status.clone()),
                        binding_origin: Some(t.origin.clone()),
                        modeled: Some(ModeledBinding {
                            operation: t
                                .attributes
                                .operation
                                .as_ref()
                                .map(String::as_str)
                                .map(str::to_owned),
                            basis: basis.cloned(),
                            recovery_phase: t.attributes.recovery_phase.as_ref().copied(),
                        }),
                        proof: vec![t.id.to_string()],
                        possible: true,
                        world: t.owner.world.to_string(),
                        ..Default::default()
                    }));
                    continue;
                }
                if t.status != "resolved" || !t.declaration_resolved || t.target.is_empty() {
                    evidence.semantic = false;
                    evidence
                        .reasons
                        .push(format!("unresolved_binding:{}", t.id));
                    records.push(Record::Dispatch(DispatchCandidate {
                        call_site: root.id.to_string(),
                        binding: Some(t.status),
                        proof: vec![t.id.to_string()],
                        possible: false,
                        world: t.owner.world.to_string(),
                        ..Default::default()
                    }));
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
                    .and_then(|fact| fact.attributes.modifiers.as_ref());
                let has_modifier = |name: &str| {
                    modifiers.is_some_and(|items| items.iter().any(|item| item == name))
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
                    .is_some_and(|owner| owner.attributes.has_modifier("final"))
                } else {
                    declaration
                        .as_ref()
                        .and_then(|fact| fact.attributes.binary_class.as_ref())
                        .and_then(|class| class.access)
                        .is_some_and(|access| access & 0x0010 != 0)
                };
                let mechanism = root
                    .attributes
                    .mechanism
                    .as_ref()
                    .map(String::as_str)
                    .unwrap_or("");
                let exact = req.algorithm == codeana_model::DispatchAlgorithm::Exact
                    || mechanism == "constructor"
                    || root.attributes.reference_mode.as_ref().map(String::as_str) == Some("NEW")
                    || root.name.starts_with("super.")
                    || has_modifier("static")
                    || has_modifier("private")
                    || has_modifier("final")
                    || class_final;
                records.push(Record::Dispatch(DispatchCandidate {
                    call_site: root.id.to_string(),
                    target: Some(t.target.clone()),
                    binding: Some(t.status.clone()),
                    proof: vec![t.id.to_string()],
                    possible: !exact,
                    world: t.owner.world.to_string(),
                    candidate: Some("resolved".into()),
                    algorithm: Some(if exact { "exact" } else { "CHA" }.into()),
                    executable: declaration
                        .as_ref()
                        .filter(|d| d.kind == FactKind::Declaration)
                        .map(|_| !has_modifier("abstract")),
                    analysis_world: Some(if req.analysis_world.is_empty() {
                        "workspace-open".into()
                    } else {
                        req.analysis_world.to_string()
                    }),
                    ..Default::default()
                }));
                if exact {
                    continue;
                }
                records.extend(dispatch::candidates(
                    r,
                    req,
                    &root,
                    &t,
                    declaration.as_ref(),
                    evidence,
                )?);
                if req.analysis_world != codeana_model::AnalysisWorld::WorkspaceClosed {
                    evidence.semantic = false;
                    evidence.reasons.push("open_world_dispatch".into());
                }
            }
        }
    }
    Ok(records)
}
