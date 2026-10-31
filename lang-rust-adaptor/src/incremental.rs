//! Persistent owner invalidation, including unsuccessful name lookups.
use crate::Analysis;
use anyhow::Result;
use codeana_model::AnalysisContext;
use codeana_model::{
    digest,
    rust::{AnalysisConfig, Dependency, DependencyKind, SourceState},
};
use std::collections::{BTreeMap, BTreeSet};
fn charge(budget: Option<&AnalysisContext>, work: u64, bytes: u64) -> Result<()> {
    if let Some(budget) = budget {
        budget.charge(work, 0, bytes)?;
    }
    Ok(())
}
fn nodes(budget: Option<&AnalysisContext>, count: u64) -> Result<()> {
    if let Some(budget) = budget {
        budget.charge(0, count, 0)?;
    }
    Ok(())
}
fn pair_bytes(a: &str, b: &str) -> u64 {
    (a.len() as u64)
        .saturating_add(b.len() as u64)
        .saturating_add(128)
}

pub fn states(config: &AnalysisConfig, analysis: &Analysis) -> Vec<SourceState> {
    states_with_context(config, analysis, None).expect("unmetered state projection is infallible")
}
pub fn states_with_context(
    config: &AnalysisConfig,
    analysis: &Analysis,
    budget: Option<&AnalysisContext>,
) -> Result<Vec<SourceState>> {
    charge(
        budget,
        (analysis.declarations.len() + analysis.relations.len() + config.inputs.crates.len())
            as u64,
        ((analysis.declarations.len()
            + analysis.relations.len()
            + config.inputs.crates.len()
            + config.baseline.as_ref().map_or(0, Vec::len)) as u64)
            .saturating_mul(256),
    )?;
    nodes(
        budget,
        ((analysis.declarations.len() + analysis.relations.len()) as u64)
            .saturating_mul(2)
            .saturating_add(config.inputs.crates.len() as u64)
            .saturating_add(config.baseline.as_ref().map_or(0, Vec::len) as u64),
    )?;
    let declarations = analysis
        .declarations
        .iter()
        .map(|d| (d.id.as_str(), d))
        .collect::<BTreeMap<_, _>>();
    let mut owned = BTreeMap::<(&str, &str), Vec<_>>::new();
    for d in &analysis.declarations {
        owned.entry((&d.world, &d.source)).or_default().push(d);
    }
    let mut relations = BTreeMap::<(&str, &str), Vec<_>>::new();
    for r in &analysis.relations {
        relations.entry((&r.world, &r.file)).or_default().push(r);
    }
    let old = config
        .baseline
        .as_deref()
        .unwrap_or_default()
        .iter()
        .map(|s| ((s.world.as_str(), s.file.as_str()), s))
        .collect::<BTreeMap<_, _>>();
    let worlds = config
        .inputs
        .crates
        .iter()
        .map(|c| (c.id.as_str(), c.world.as_str()))
        .collect::<BTreeMap<_, _>>();
    let mut result = Vec::new();
    for input in config.inputs.crates.iter().filter(|c| c.publish) {
        for file in &config.files {
            charge(budget, 1, pair_bytes(&file.path, &input.world))?;
            if !file.crates.contains(&input.id)
                || config
                    .excluded
                    .contains(&(file.path.clone(), input.world.clone()))
            {
                continue;
            }
            let owned = owned
                .get(&(input.world.as_str(), file.path.as_str()))
                .map(Vec::as_slice)
                .unwrap_or_default();
            let names_bytes = owned
                .iter()
                .fold(0u64, |n, d| n.saturating_add(d.name.len() as u64));
            let sorting_work =
                (owned.len() as u64).saturating_mul(u64::from(owned.len().max(1).ilog2()) + 2);
            charge(
                budget,
                sorting_work,
                names_bytes
                    .saturating_add((owned.len() as u64).saturating_mul(128))
                    .saturating_add(pair_bytes(&file.path, &input.world))
                    .saturating_add(512),
            )?;
            let mut names = owned.iter().map(|d| d.name.clone()).collect::<Vec<_>>();
            names.sort();
            names.dedup();
            let interface_hash = digest(&(
                owned
                    .iter()
                    .map(|d| (&d.id, &d.signature_hash))
                    .collect::<Vec<_>>(),
                analysis
                    .imports
                    .get(&(file.path.clone(), input.world.clone())),
            ));
            let mut dependencies = BTreeSet::new();
            for relation in relations
                .get(&(input.world.as_str(), file.path.as_str()))
                .into_iter()
                .flatten()
            {
                charge(budget, 1, 0)?;
                if let Some(target) = relation
                    .target
                    .as_deref()
                    .and_then(|id| declarations.get(id))
                {
                    charge(
                        budget,
                        0,
                        pair_bytes(&target.world, &target.source)
                            .saturating_add(pair_bytes(&target.world, &target.id)),
                    )?;
                    nodes(budget, 2)?;
                    dependencies.insert(Dependency {
                        kind: DependencyKind::File,
                        world: target.world.clone(),
                        key: target.source.clone(),
                    });
                    dependencies.insert(Dependency {
                        kind: DependencyKind::Symbol,
                        world: target.world.clone(),
                        key: target.id.clone(),
                    });
                }
            }
            for lookup in analysis
                .lookups
                .get(&(file.path.clone(), input.world.clone()))
                .into_iter()
                .flatten()
            {
                charge(
                    budget,
                    lookup.len() as u64,
                    pair_bytes(&file.path, &input.world),
                )?;
                for name in lookup
                    .split(|c: char| !(c.is_alphanumeric() || c == '_'))
                    .filter(|n| !n.is_empty())
                {
                    // Search the current crate and declared dependencies; this also records failures.
                    for world in std::iter::once(input.world.as_str()).chain(
                        input
                            .dependencies
                            .values()
                            .filter_map(|id| worlds.get(id.as_str()).copied()),
                    ) {
                        charge(budget, 1, pair_bytes(world, name))?;
                        nodes(budget, 1)?;
                        dependencies.insert(Dependency {
                            kind: DependencyKind::Lookup,
                            world: world.into(),
                            key: name.into(),
                        });
                    }
                }
            }
            // Trait and macro environment changes can affect binding without a direct resolved edge.
            for world in std::iter::once(input.world.as_str()).chain(
                input
                    .dependencies
                    .values()
                    .filter_map(|id| worlds.get(id.as_str()).copied()),
            ) {
                charge(budget, 1, pair_bytes(world, "binding-environment"))?;
                nodes(budget, 1)?;
                dependencies.insert(Dependency {
                    kind: DependencyKind::ImplSet,
                    world: world.into(),
                    key: "binding-environment".into(),
                });
            }
            if config.baseline.is_some()
                && analysis
                    .dirty
                    .as_ref()
                    .is_none_or(|dirty| !dirty.contains(&(file.path.clone(), input.world.clone())))
                && let Some(old) = old.get(&(input.world.as_str(), file.path.as_str()))
            {
                let bytes = old
                    .dependencies
                    .iter()
                    .fold(0u64, |n, d| n.saturating_add(pair_bytes(&d.world, &d.key)));
                charge(budget, old.dependencies.len() as u64, bytes)?;
                nodes(budget, old.dependencies.len() as u64)?;
                dependencies = old.dependencies.iter().cloned().collect();
            }
            charge(
                budget,
                1,
                pair_bytes(&file.path, &input.world)
                    .saturating_add(file.hash.len() as u64)
                    .saturating_add(
                        (dependencies.len() as u64)
                            .saturating_mul(2 * std::mem::size_of::<Dependency>() as u64),
                    )
                    .saturating_add(512),
            )?;
            nodes(budget, 1)?;
            result.push(SourceState {
                file: file.path.clone(),
                world: input.world.clone(),
                source_hash: file.hash.clone(),
                interface_hash,
                names,
                dependencies: dependencies.into_iter().collect(),
            });
        }
    }
    charge(
        budget,
        (result.len() as u64).saturating_mul(u64::from(result.len().max(1).ilog2()) + 1),
        0,
    )?;
    result.sort_by(|a, b| (&a.world, &a.file).cmp(&(&b.world, &b.file)));
    Ok(result)
}

/// Returns complete replacement owners, including deleted and now-empty files.
/// Physical-file closure keeps all instances correct when the common store replaces a file.
pub fn dirty(before: &[SourceState], after: &[SourceState]) -> BTreeSet<(String, String)> {
    dirty_with_context(before, after, None).expect("unmetered invalidation is infallible")
}
pub fn dirty_with_context(
    before: &[SourceState],
    after: &[SourceState],
    budget: Option<&AnalysisContext>,
) -> Result<BTreeSet<(String, String)>> {
    for state in before.iter().chain(after) {
        charge(
            budget,
            1,
            pair_bytes(&state.file, &state.world).saturating_mul(8),
        )?;
    }
    nodes(
        budget,
        ((before.len() + after.len()) as u64).saturating_mul(3),
    )?;
    let old = before
        .iter()
        .map(|s| ((s.file.clone(), s.world.clone()), s))
        .collect::<BTreeMap<_, _>>();
    let new = after
        .iter()
        .map(|s| ((s.file.clone(), s.world.clone()), s))
        .collect::<BTreeMap<_, _>>();
    let keys = old
        .keys()
        .chain(new.keys())
        .cloned()
        .collect::<BTreeSet<_>>();
    let mut changed = BTreeSet::new();
    let mut interfaces = BTreeSet::new();
    let mut names = BTreeSet::new();
    for key in &keys {
        charge(budget, 1, pair_bytes(&key.0, &key.1).saturating_mul(3))?;
        nodes(budget, 3)?;
        let a = old.get(key);
        let b = new.get(key);
        if a.map(|s| &s.source_hash) != b.map(|s| &s.source_hash)
            || a.map(|s| &s.dependencies) != b.map(|s| &s.dependencies)
        {
            changed.insert(key.clone());
        }
        if a.map(|s| &s.interface_hash) != b.map(|s| &s.interface_hash) {
            changed.insert(key.clone());
            interfaces.insert(key.clone());
            for s in a.into_iter().chain(b) {
                let bytes = s
                    .names
                    .iter()
                    .fold(0u64, |n, name| n.saturating_add(pair_bytes(&s.world, name)));
                charge(budget, s.names.len() as u64, bytes)?;
                nodes(budget, s.names.len() as u64)?;
                names.extend(s.names.iter().map(|name| (s.world.clone(), name.clone())));
            }
        }
    }
    charge(
        budget,
        interfaces.len() as u64,
        interfaces
            .iter()
            .fold(0u64, |n, (_, w)| n.saturating_add(pair_bytes(w, ""))),
    )?;
    nodes(budget, interfaces.len() as u64)?;
    let mut binding_worlds = interfaces
        .iter()
        .map(|(_, world)| world.clone())
        .collect::<BTreeSet<_>>();
    loop {
        let size = (changed.len(), binding_worlds.len());
        charge(
            budget,
            changed.len() as u64,
            changed
                .iter()
                .fold(0u64, |n, (p, _)| n.saturating_add(pair_bytes(p, ""))),
        )?;
        nodes(budget, changed.len() as u64)?;
        let changed_paths = changed
            .iter()
            .map(|(path, _)| path.clone())
            .collect::<BTreeSet<_>>();
        for state in before.iter().chain(after) {
            let deps = state
                .dependencies
                .iter()
                .fold(0u64, |n, d| n.saturating_add(pair_bytes(&d.key, &d.world)));
            charge(
                budget,
                (state.dependencies.len() as u64)
                    .saturating_mul(2)
                    .saturating_add(1),
                deps.saturating_mul(2)
                    .saturating_add(pair_bytes(&state.file, &state.world))
                    .saturating_add(pair_bytes(&state.world, "")),
            )?;
            if changed_paths.contains(&state.file)
                || state
                    .dependencies
                    .iter()
                    .any(|dependency| match dependency.kind {
                        DependencyKind::File | DependencyKind::Macro => {
                            interfaces.contains(&(dependency.key.clone(), dependency.world.clone()))
                        }
                        DependencyKind::Lookup => {
                            names.contains(&(dependency.world.clone(), dependency.key.clone()))
                        }
                        DependencyKind::ImplSet => binding_worlds.contains(&dependency.world),
                        DependencyKind::Symbol => false, // File/lookup keys cover source-level symbol invalidation.
                    })
            {
                nodes(budget, 2)?;
                changed.insert((state.file.clone(), state.world.clone()));
                if state
                    .dependencies
                    .iter()
                    .any(|d| binding_worlds.contains(&d.world))
                {
                    binding_worlds.insert(state.world.clone());
                }
            }
        }
        if (changed.len(), binding_worlds.len()) == size {
            break;
        }
    }
    Ok(changed)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn state(file: &str) -> SourceState {
        SourceState {
            file: file.into(),
            world: "main".into(),
            source_hash: "old".into(),
            interface_hash: "stable".into(),
            ..Default::default()
        }
    }
    #[test]
    fn body_edit_is_local_and_new_name_invalidates_failed_lookup() {
        let a = state("a.rs");
        let mut b = state("b.rs");
        b.dependencies.push(Dependency {
            kind: DependencyKind::Lookup,
            world: "main".into(),
            key: "added".into(),
        });
        let mut next = a.clone();
        next.source_hash = "new".into();
        assert_eq!(
            dirty(&[a.clone(), b.clone()], &[next.clone(), b.clone()]),
            BTreeSet::from([("a.rs".into(), "main".into())])
        );
        next.interface_hash = "changed".into();
        next.names.push("added".into());
        assert_eq!(dirty(&[a, b.clone()], &[next, b]).len(), 2);
    }
    #[test]
    fn removal_and_shared_source_instances_remain_in_replacement_set() {
        let a = state("a.rs");
        let mut other = a.clone();
        other.world = "test".into();
        let mut next = a.clone();
        next.source_hash = "changed".into();
        assert_eq!(dirty(&[a, other.clone()], &[next, other]).len(), 2);
        assert_eq!(dirty(&[state("gone.rs")], &[]).len(), 1);
    }
    #[test]
    fn bounded_invalidation_preserves_semantics_and_rejects_before_clone() {
        let a = state("a.rs");
        let mut changed = a.clone();
        changed.source_hash = "next".into();
        let budget = AnalysisContext::new(Default::default(), None).unwrap();
        assert_eq!(
            dirty_with_context(
                std::slice::from_ref(&a),
                std::slice::from_ref(&changed),
                Some(&budget)
            )
            .unwrap(),
            dirty(std::slice::from_ref(&a), std::slice::from_ref(&changed))
        );
        assert!(budget.usage().work > 0);
        assert!(budget.usage().nodes > 0);
        assert!(budget.usage().payload_bytes > 0);
        let tiny = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(dirty_with_context(&[a], &[changed], Some(&tiny)).is_err());
        assert_eq!(tiny.usage().payload_bytes, 0);
        assert!(tiny.usage().failure.is_some());
    }
    #[test]
    fn invalidation_convergence_cannot_reset_the_work_ledger() {
        let a = state("a.rs");
        let mut changed = a.clone();
        changed.interface_hash = "next".into();
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_work: 3,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(
            dirty_with_context(
                std::slice::from_ref(&a),
                std::slice::from_ref(&changed),
                Some(&budget)
            )
            .is_err()
        );
        assert!(dirty_with_context(&[a], &[changed], Some(&budget)).is_err());
        assert!(budget.usage().work <= 3);
    }
}
