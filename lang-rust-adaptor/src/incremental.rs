//! Persistent owner invalidation, including unsuccessful name lookups.
use crate::Analysis;
use codeana_model::{
    digest,
    rust::{AnalysisConfig, Dependency, DependencyKind, SourceState},
};
use std::collections::{BTreeMap, BTreeSet};

pub fn states(config: &AnalysisConfig, analysis: &Analysis) -> Vec<SourceState> {
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
        for file in config.files.iter().filter(|f| {
            f.crates.contains(&input.id)
                && !config
                    .excluded
                    .contains(&(f.path.clone(), input.world.clone()))
        }) {
            let owned = owned
                .get(&(input.world.as_str(), file.path.as_str()))
                .map(Vec::as_slice)
                .unwrap_or_default();
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
                if let Some(target) = relation
                    .target
                    .as_deref()
                    .and_then(|id| declarations.get(id))
                {
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
                dependencies = old.dependencies.iter().cloned().collect();
            }
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
    result.sort_by(|a, b| (&a.world, &a.file).cmp(&(&b.world, &b.file)));
    result
}

/// Returns complete replacement owners, including deleted and now-empty files.
/// Physical-file closure keeps all instances correct when the common store replaces a file.
pub fn dirty(before: &[SourceState], after: &[SourceState]) -> BTreeSet<(String, String)> {
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
                names.extend(s.names.iter().map(|name| (s.world.clone(), name.clone())));
            }
        }
    }
    let mut binding_worlds = interfaces
        .iter()
        .map(|(_, world)| world.clone())
        .collect::<BTreeSet<_>>();
    loop {
        let size = (changed.len(), binding_worlds.len());
        let changed_paths = changed
            .iter()
            .map(|(path, _)| path.clone())
            .collect::<BTreeSet<_>>();
        for state in before.iter().chain(after) {
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
    changed
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
}
