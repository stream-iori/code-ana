//! JavaDispatchService's receiver/override/default-method rules, using directed
//! reads rather than loading every declaration and hierarchy edge in a project.
use super::*;

pub(super) fn options(operation: &str, algorithm: &str, world: &str) -> Result<()> {
    ensure!(
        algorithm.is_empty() || operation == "dispatch",
        "ALGORITHM_REQUIRES_DISPATCH"
    );
    ensure!(
        matches!(algorithm, "" | "auto" | "exact" | "cha"),
        "INVALID_DISPATCH_ALGORITHM"
    );
    ensure!(
        world.is_empty() || matches!(operation, "dispatch" | "runtime-implementations"),
        "ANALYSIS_WORLD_REQUIRES_JAVA_CANDIDATES"
    );
    ensure!(
        matches!(
            world,
            "" | "workspace-open" | "workspace-closed" | "classpath-open"
        ),
        "INVALID_ANALYSIS_WORLD"
    );
    Ok(())
}

fn kind(fact: &Fact) -> &str {
    fact.attributes
        .get("element_kind")
        .and_then(Value::as_str)
        .unwrap_or("")
}
fn modifier(fact: &Fact, name: &str) -> bool {
    fact.attributes
        .get("modifiers")
        .and_then(Value::as_array)
        .is_some_and(|items| items.iter().any(|item| item.as_str() == Some(name)))
}
fn load(
    reader: &dyn GraphReader,
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
            cache.insert(fact.id.clone(), fact);
        }
        ensure!(cache.len() <= 10000, "DISPATCH_DECLARATION_WORK_BUDGET");
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
    reader: &dyn GraphReader,
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
    reader: &dyn GraphReader,
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
            path.push(edge.id);
            paths.insert(next.clone(), path);
            next_frontier.insert(next.clone());
        }
        frontier = next_frontier.into_iter().collect();
    }
    Ok(paths)
}

pub(super) fn candidates(
    reader: &dyn GraphReader,
    req: &Request,
    site: &Fact,
    binding: &Fact,
    target: Option<&Fact>,
    evidence: &mut Evidence,
) -> Result<Vec<Value>> {
    let Some(target) = target.filter(|fact| fact.kind == FactKind::Declaration) else {
        return Ok(vec![]);
    };
    if target.parent.is_empty() {
        return Ok(vec![]);
    }
    let mut cache = BTreeMap::from([(target.id.clone(), target.clone())]);
    load(reader, std::slice::from_ref(&target.parent), &mut cache)?;
    let receiver_name = site
        .attributes
        .get("receiver_static_type")
        .or_else(|| binding.attributes.get("receiver_static_type"))
        .and_then(Value::as_str);
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
                receiver = matches[0].id.clone();
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
        ensure!(work <= 100000, "DISPATCH_WORK_BUDGET");
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
        ensure!(work <= 100000, "DISPATCH_WORK_BUDGET");
        for method in methods.into_iter().filter(|fact| kind(fact) == "METHOD") {
            cache.insert(method.id.clone(), method);
        }
        method_owners.extend(batch.iter().cloned());
        ensure!(cache.len() <= 10000, "DISPATCH_DECLARATION_WORK_BUDGET");
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
            ensure!(work <= 100000, "DISPATCH_WORK_BUDGET");
            for method in methods.into_iter().filter(|fact| kind(fact) == "METHOD") {
                cache.insert(method.id.clone(), method);
            }
            method_owners.extend(batch.iter().cloned());
            ensure!(cache.len() <= 10000, "DISPATCH_DECLARATION_WORK_BUDGET");
        }
        let slot = target.signature.rsplit_once('#').map(|(_, slot)| slot);
        let matching = cache
            .values()
            .filter(|method| {
                kind(method) == "METHOD"
                    && ancestry.contains_key(&method.parent)
                    && !modifier(method, "static")
                    && !modifier(method, "private")
                    && (callable.contains_key(&method.id)
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
            let mut proof = vec![binding.id.clone()];
            proof.extend(
                callable
                    .get(&implementation.id)
                    .into_iter()
                    .flatten()
                    .cloned(),
            );
            let mut type_proof = runtime_proof.clone();
            type_proof.extend(ancestry[&implementation.parent].clone());
            let mut unique = BTreeSet::new();
            type_proof.retain(|edge| unique.insert(edge.clone()));
            output.entry(implementation.id.clone()).or_insert(json!({
                "call_site":site.id,"target":implementation.id,"proof":proof,"type_proof":type_proof,
                "possible":true,"candidate":"possible","executable":true,"algorithm":"CHA",
                "analysis_world":if req.analysis_world.is_empty() {"workspace-open"} else {&req.analysis_world},
                "world":implementation.owner.world
            }));
        }
    }
    Ok(output.into_values().collect())
}
