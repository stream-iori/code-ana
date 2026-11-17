//! Host merge intent. This never substitutes for a worker's verified delivery.
use crate::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnitUpdateKind {
    Full,
    Delta,
    Reuse,
    Removed,
    FilesOnly,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnitUpdate {
    pub unit: AnalysisUnitId,
    pub kind: UnitUpdateKind,
    /// Delta supplies its invalidation closure. Other modes derive their range.
    pub replacements: Vec<Owner>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnitMergePlan {
    updates: Vec<UnitUpdate>,
}

impl UnitMergePlan {
    /// Production reuse prepays validation maps and the owned plan from the
    /// existing host accounts. A rejected reservation preserves its failure code.
    pub fn all_reused_with_budget(
        before: &Revision,
        after: &Revision,
        hosts: &BTreeMap<AnalysisBackend, AnalysisContext>,
    ) -> Result<Self, DomainError> {
        for revision in [before, after] {
            let ledger = revision.unit_ownership.as_ref().ok_or_else(|| {
                DomainError::new(
                    ErrorCode::ResultIdentityMismatch,
                    "UNIT_MERGE_LEDGER_REQUIRED",
                )
            })?;
            if ledger.version != UNIT_OWNERSHIP_VERSION || ledger.units.len() > 10_000 {
                return Err(DomainError::new(
                    ErrorCode::ResultIdentityMismatch,
                    "UNIT_MERGE_LEDGER_VERSION_OR_BUDGET",
                ));
            }
            // Shared manifest lookup tables use the first participating host in
            // the same fixed backend order as all other task reservations.
            let host = hosts
                .values()
                .next()
                .ok_or_else(|| DomainError::analysis(AnalysisFailure::InvalidUsage))?;
            host.charge(
                (revision.manifest.files.len() as u64).saturating_mul(32),
                0,
                (revision.manifest.files.len() as u64).saturating_mul(256),
            )
            .map_err(DomainError::analysis)?;
            for unit in &ledger.units {
                let host = hosts
                    .get(&unit.backend)
                    .ok_or_else(|| DomainError::analysis(AnalysisFailure::InvalidUsage))?;
                let bytes = retained_size(unit).map_err(|e| {
                    DomainError::new(ErrorCode::ResultIdentityMismatch, e.to_string())
                })?;
                host.charge(
                    (unit.inputs.len() as u64)
                        .saturating_add(unit.sources.len() as u64)
                        .saturating_add(1)
                        .saturating_mul(32),
                    0,
                    bytes.saturating_mul(4).saturating_add(1024),
                )
                .map_err(DomainError::analysis)?;
            }
        }
        Self::all_reused(before, after)
            .map_err(|e| DomainError::new(ErrorCode::ResultIdentityMismatch, e))
    }
    /// Validate every old/new unit, including units that emit no graph rows.
    pub fn new(
        before: &Revision,
        after: &Revision,
        mut updates: Vec<UnitUpdate>,
    ) -> Result<Self, String> {
        if before.project != after.project
            || before.checkout != after.checkout
            || before.target != after.target
        {
            return Err("UNIT_MERGE_REVISION_IDENTITY".into());
        }
        let before_ledger = before
            .unit_ownership
            .as_ref()
            .ok_or("UNIT_MERGE_LEDGER_REQUIRED")?;
        let after_ledger = after
            .unit_ownership
            .as_ref()
            .ok_or("UNIT_MERGE_LEDGER_REQUIRED")?;
        before_ledger.validate(&before.manifest)?;
        after_ledger.validate(&after.manifest)?;
        let before = before_ledger;
        let after = after_ledger;
        let old = unit_map(before)?;
        let new = unit_map(after)?;
        let old_publications = publications(before);
        let new_publications = publications(after);
        let expected: BTreeSet<_> = old.keys().chain(new.keys()).copied().collect();
        updates.sort_by(|a, b| a.unit.cmp(&b.unit));
        if updates.len() != expected.len()
            || updates.iter().map(|u| &u.unit).collect::<BTreeSet<_>>() != expected
        {
            return Err("UNIT_MERGE_MEMBERSHIP".into());
        }
        let mut claimed = BTreeMap::new();
        for update in &mut updates {
            let a = old.get(&update.unit).copied();
            let b = new.get(&update.unit).copied();
            if a.zip(b).is_some_and(|(a, b)| a.backend != b.backend) {
                return Err("UNIT_MERGE_BACKEND_CHANGED".into());
            }
            let complete = || {
                owners(a)
                    .union(&owners(b))
                    .cloned()
                    .collect::<BTreeSet<_>>()
            };
            let valid = match update.kind {
                UnitUpdateKind::Full => b.is_some_and(ready),
                UnitUpdateKind::Removed => a.is_some() && b.is_none(),
                UnitUpdateKind::FilesOnly => {
                    b.is_some_and(|u| u.availability == UnitAvailability::FilesOnly)
                }
                UnitUpdateKind::Reuse => a.zip(b).is_some_and(|(a, b)| {
                    compatible(a, b)
                        && a.input_digest == b.input_digest
                        && a.sources == b.sources
                        && same_semantic_sources(&a.inputs, &b.inputs)
                        && binding_publications_unchanged(a, &old_publications, &new_publications)
                }),
                UnitUpdateKind::Delta => a.zip(b).is_some_and(|(a, b)| {
                    compatible(a, b)
                        && same_semantic_sources(&controls(a), &controls(b))
                        && binding_publications_unchanged(a, &old_publications, &new_publications)
                }),
            };
            if !valid {
                return Err("UNIT_MERGE_MODE_OR_IDENTITY".into());
            }
            match update.kind {
                UnitUpdateKind::Reuse => {
                    if !update.replacements.is_empty() {
                        return Err("UNIT_MERGE_UNEXPECTED_REPLACEMENTS".into());
                    }
                }
                UnitUpdateKind::Delta => {
                    let supplied: BTreeSet<_> = update.replacements.iter().cloned().collect();
                    if supplied.len() != update.replacements.len()
                        || !supplied.is_subset(&complete())
                        || !changed_owners(a.unwrap(), b.unwrap()).is_subset(&supplied)
                    {
                        return Err("UNIT_MERGE_DELTA_SCOPE".into());
                    }
                    update.replacements = supplied.into_iter().collect();
                }
                _ => {
                    if !update.replacements.is_empty() {
                        return Err("UNIT_MERGE_UNEXPECTED_REPLACEMENTS".into());
                    }
                    update.replacements = complete().into_iter().collect();
                }
            }
            for owner in &update.replacements {
                if !safe_path(&owner.id)
                    || claimed
                        .insert((owner.world.clone(), owner.id.clone()), update.unit.clone())
                        .is_some_and(|unit| unit != update.unit)
                {
                    return Err("UNIT_MERGE_OWNER_CONFLICT".into());
                }
            }
        }
        Ok(Self { updates })
    }

    pub fn all_reused(before: &Revision, after: &Revision) -> Result<Self, String> {
        let old = before
            .unit_ownership
            .as_ref()
            .ok_or("UNIT_MERGE_LEDGER_REQUIRED")?;
        let new = after
            .unit_ownership
            .as_ref()
            .ok_or("UNIT_MERGE_LEDGER_REQUIRED")?;
        Self::new(
            before,
            after,
            new.units
                .iter()
                .map(|u| UnitUpdate {
                    unit: u.unit.clone(),
                    kind: if u.availability == UnitAvailability::FilesOnly {
                        UnitUpdateKind::FilesOnly
                    } else {
                        UnitUpdateKind::Reuse
                    },
                    replacements: Vec::new(),
                })
                .collect(),
        )
        .and_then(|plan| {
            // FilesOnly is a no-worker state, but whole-project reuse cannot add,
            // remove, or alter such a unit either.
            let old_units = unit_map(old)?;
            let new_units = unit_map(new)?;
            if old.units.len() != new.units.len()
                || old
                    .units
                    .iter()
                    .filter(|u| u.availability == UnitAvailability::FilesOnly)
                    .any(|u| new_units.get(&u.unit).copied() != Some(u))
                || new
                    .units
                    .iter()
                    .filter(|u| u.availability == UnitAvailability::FilesOnly)
                    .any(|u| old_units.get(&u.unit).copied() != Some(u))
            {
                Err("UNIT_MERGE_FILES_ONLY_CHANGED".into())
            } else {
                Ok(plan)
            }
        })
    }

    pub fn updates(&self) -> &[UnitUpdate] {
        &self.updates
    }
}

fn unit_map(
    ledger: &UnitOwnershipLedger,
) -> Result<BTreeMap<&AnalysisUnitId, &UnitOwnership>, String> {
    let map: BTreeMap<_, _> = ledger.units.iter().map(|u| (&u.unit, u)).collect();
    if map.len() != ledger.units.len() || map.keys().any(|id| id.is_empty()) {
        return Err("UNIT_MERGE_LEDGER_MEMBERSHIP".into());
    }
    Ok(map)
}
fn ready(u: &UnitOwnership) -> bool {
    u.availability == UnitAvailability::Ready
}
fn compatible(a: &UnitOwnership, b: &UnitOwnership) -> bool {
    ready(a)
        && ready(b)
        && a.backend == b.backend
        && a.producer == b.producer
        && a.semantic_profile == b.semantic_profile
        && a.source_languages == b.source_languages
        && a.modules == b.modules
        && a.worlds == b.worlds
        && a.scopes == b.scopes
}
fn owners(unit: Option<&UnitOwnership>) -> BTreeSet<Owner> {
    unit.into_iter()
        .flat_map(|u| {
            u.sources.iter().map(|s| Owner {
                world: s.world.clone().into(),
                producer: u.producer.clone(),
                kind: "source".into(),
                id: s.path.clone(),
            })
        })
        .collect()
}
fn changed_owners(a: &UnitOwnership, b: &UnitOwnership) -> BTreeSet<Owner> {
    let old = source_map(&a.sources);
    let new = source_map(&b.sources);
    [a, b]
        .into_iter()
        .flat_map(|u| {
            u.sources
                .iter()
                .filter(|s| {
                    let key = (s.world.as_str(), s.path.as_str());
                    old.get(&key) != new.get(&key)
                })
                .map(|s| Owner {
                    world: s.world.clone().into(),
                    producer: u.producer.clone(),
                    kind: "source".into(),
                    id: s.path.clone(),
                })
        })
        .collect()
}
fn controls(u: &UnitOwnership) -> Vec<SourceUnit> {
    let published = source_map(&u.sources);
    u.inputs
        .iter()
        .filter(|s| !published.contains_key(&(s.world.as_str(), s.path.as_str())))
        .cloned()
        .collect()
}
fn source_map(sources: &[SourceUnit]) -> BTreeMap<(&str, &str), &SourceUnit> {
    sources
        .iter()
        .map(|s| ((s.world.as_str(), s.path.as_str()), s))
        .collect()
}
type Publications<'a> =
    BTreeMap<(&'a str, &'a str), (&'a AnalysisUnitId, AnalysisBackend, &'a str, &'a str)>;
fn publications(ledger: &UnitOwnershipLedger) -> Publications<'_> {
    ledger
        .units
        .iter()
        .flat_map(|u| {
            u.sources.iter().map(|s| {
                (
                    (s.world.as_str(), s.path.as_str()),
                    (
                        &u.unit,
                        u.backend,
                        u.producer.as_str(),
                        u.semantic_profile.as_str(),
                    ),
                )
            })
        })
        .collect()
}
fn binding_publications_unchanged(
    unit: &UnitOwnership,
    before: &Publications<'_>,
    after: &Publications<'_>,
) -> bool {
    // The same auxiliary bytes can change from an internal declaration to an
    // external target when their publishing peer disappears. Require reanalysis.
    unit.inputs.iter().filter(|s| s.auxiliary).all(|input| {
        let key = (input.world.as_str(), input.path.as_str());
        before.get(&key) == after.get(&key)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unit(id: &str) -> UnitOwnership {
        let source = SourceUnit {
            path: format!("src/{id}.java"),
            hash: hash(id),
            size: 20,
            module: "app".into(),
            world: "main".into(),
            scope: "MAIN".into(),
            language: "java".into(),
            auxiliary: false,
        };
        UnitOwnership {
            unit: id.into(),
            backend: AnalysisBackend::Java,
            availability: UnitAvailability::Ready,
            source_languages: vec![SourceLanguage::Java],
            modules: vec!["app".into()],
            worlds: vec!["main".into()],
            scopes: vec!["MAIN".into()],
            producer: "javac".into(),
            semantic_profile: hash("profile"),
            input_digest: hash(id),
            inputs: vec![source.clone()],
            sources: vec![source],
        }
    }
    fn revision(mut units: Vec<UnitOwnership>) -> Revision {
        units.sort_by(|a, b| (a.backend, &a.unit).cmp(&(b.backend, &b.unit)));
        let mut files = BTreeMap::new();
        for u in &units {
            for source in &u.inputs {
                let key = (source.world.clone(), source.path.clone());
                if !source.auxiliary || !files.contains_key(&key) {
                    files.insert(key, source.clone());
                }
            }
        }
        Revision {
            id: "revision".into(),
            target: "target".into(),
            project: "project".into(),
            checkout: "checkout".into(),
            manifest: Manifest {
                files: files.into_values().collect(),
            },
            profile: hash("aggregate"),
            producer: "combined".into(),
            created: 0,
            unit_ownership: Some(UnitOwnershipLedger {
                version: UNIT_OWNERSHIP_VERSION,
                units,
            }),
        }
    }
    fn request(id: &str, kind: UnitUpdateKind) -> UnitUpdate {
        UnitUpdate {
            unit: id.into(),
            kind,
            replacements: vec![],
        }
    }
    fn files_only(mut u: UnitOwnership) -> UnitOwnership {
        u.availability = UnitAvailability::FilesOnly;
        u.sources.clear();
        u.inputs.clear();
        u.producer.clear();
        u.semantic_profile.clear();
        u.input_digest.clear();
        u
    }
    #[test]
    fn mixed_updates_include_deleted_and_zero_output_owners_and_normalize_order() {
        let before = revision(vec![
            unit("full"),
            unit("delta"),
            unit("reuse"),
            unit("removed"),
            unit("files"),
        ]);
        let mut delta = unit("delta");
        delta.sources.clear();
        delta.inputs.clear();
        delta.input_digest = hash("deleted");
        let mut full = unit("full");
        full.sources.clear();
        full.inputs.clear();
        full.input_digest = hash("empty");
        let after = revision(vec![full, delta, unit("reuse"), files_only(unit("files"))]);
        let mut update = request("delta", UnitUpdateKind::Delta);
        update.replacements = owners(Some(&unit("delta"))).into_iter().collect();
        let mut updates = vec![
            request("reuse", UnitUpdateKind::Reuse),
            request("removed", UnitUpdateKind::Removed),
            request("files", UnitUpdateKind::FilesOnly),
            request("full", UnitUpdateKind::Full),
            update,
        ];
        let plan = UnitMergePlan::new(&before, &after, updates.clone()).unwrap();
        updates.reverse();
        assert_eq!(plan, UnitMergePlan::new(&before, &after, updates).unwrap());
        assert_eq!(
            plan.updates()
                .iter()
                .map(|u| u.replacements.len())
                .collect::<Vec<_>>(),
            [1, 1, 1, 1, 0]
        );
        assert!(UnitMergePlan::all_reused(&before, &after).is_err());
    }
    #[test]
    fn delta_requires_changed_deleted_and_added_sources_and_rejects_foreign_worlds() {
        let before = revision(vec![unit("a")]);
        let mut changed = unit("a");
        changed.sources[0].hash = hash("changed");
        changed.inputs = changed.sources.clone();
        let after = revision(vec![changed]);
        let mut update = request("a", UnitUpdateKind::Delta);
        assert!(UnitMergePlan::new(&before, &after, vec![update.clone()]).is_err());
        update.replacements = owners(Some(&unit("a"))).into_iter().collect();
        assert!(UnitMergePlan::new(&before, &after, vec![update.clone()]).is_ok());
        update.replacements[0].world = "test".into();
        assert!(UnitMergePlan::new(&before, &after, vec![update]).is_err());
    }
    #[test]
    fn forged_input_digest_does_not_authorize_reuse_or_binding_input_delta() {
        let mut u = unit("a");
        let mut auxiliary = unit("dependency").sources.remove(0);
        auxiliary.auxiliary = true;
        u.inputs.push(auxiliary);
        let before = revision(vec![u.clone()]);
        u.inputs[1].hash = hash("different dependency");
        let after = revision(vec![u]);
        assert!(UnitMergePlan::all_reused(&before, &after).is_err());
        assert!(
            UnitMergePlan::new(&before, &after, vec![request("a", UnitUpdateKind::Delta)]).is_err()
        );
    }
    #[test]
    fn removing_a_binding_publisher_requires_consumer_reanalysis() {
        let publisher = unit("a");
        let mut consumer = unit("b");
        let mut auxiliary = publisher.sources[0].clone();
        auxiliary.auxiliary = true;
        consumer.inputs.push(auxiliary);
        let before = revision(vec![publisher, consumer.clone()]);
        let after = revision(vec![consumer]);
        let mut updates = vec![
            request("a", UnitUpdateKind::Removed),
            request("b", UnitUpdateKind::Reuse),
        ];
        assert!(UnitMergePlan::new(&before, &after, updates.clone()).is_err());
        updates[1].kind = UnitUpdateKind::Delta;
        assert!(UnitMergePlan::new(&before, &after, updates.clone()).is_err());
        updates[1].kind = UnitUpdateKind::Full;
        assert!(UnitMergePlan::new(&before, &after, updates).is_ok());
    }
    #[test]
    fn incomplete_duplicate_and_cross_revision_plans_are_rejected() {
        let before = revision(vec![unit("a"), unit("b")]);
        assert!(
            UnitMergePlan::new(&before, &before, vec![request("a", UnitUpdateKind::Reuse)])
                .is_err()
        );
        assert!(
            UnitMergePlan::new(
                &before,
                &before,
                vec![request("a", UnitUpdateKind::Reuse); 2]
            )
            .is_err()
        );
        let mut after = before.clone();
        after.checkout = "another checkout".into();
        assert!(UnitMergePlan::all_reused(&before, &after).is_err());
        let after = revision(vec![unit("a"), files_only(unit("b"))]);
        assert!(UnitMergePlan::all_reused(&before, &after).is_err());
    }
    #[test]
    fn whole_reuse_preserves_files_only_identity() {
        let before = revision(vec![unit("a"), files_only(unit("b"))]);
        assert!(UnitMergePlan::all_reused(&before, &before).is_ok());
        let mut after = before.clone();
        after.unit_ownership.as_mut().unwrap().units[1].scopes = vec!["TEST".into()];
        assert!(UnitMergePlan::all_reused(&before, &after).is_err());
    }
    #[test]
    fn full_replaces_both_producers_but_delta_and_reuse_reject_profile_changes() {
        let before = revision(vec![unit("a")]);
        let mut replacement = unit("a");
        replacement.producer = "new-javac".into();
        replacement.semantic_profile = hash("new-profile");
        let after = revision(vec![replacement]);
        let plan =
            UnitMergePlan::new(&before, &after, vec![request("a", UnitUpdateKind::Full)]).unwrap();
        assert_eq!(plan.updates()[0].replacements.len(), 2);
        assert!(
            UnitMergePlan::new(&before, &after, vec![request("a", UnitUpdateKind::Delta)]).is_err()
        );
        assert!(UnitMergePlan::all_reused(&before, &after).is_err());
    }
    #[test]
    fn delta_source_rename_clears_old_owner_and_reserves_new_zero_output_owner() {
        let old = unit("a");
        let before = revision(vec![old.clone()]);
        let mut new = old.clone();
        new.sources[0].path = "src/Renamed.java".into();
        new.inputs = new.sources.clone();
        let after = revision(vec![new.clone()]);
        let mut update = request("a", UnitUpdateKind::Delta);
        update.replacements = owners(Some(&old)).into_iter().collect();
        assert!(UnitMergePlan::new(&before, &after, vec![update.clone()]).is_err());
        update.replacements.extend(owners(Some(&new)));
        assert_eq!(
            UnitMergePlan::new(&before, &after, vec![update.clone()])
                .unwrap()
                .updates()[0]
                .replacements
                .len(),
            2
        );
        update.replacements.push(update.replacements[0].clone());
        assert!(UnitMergePlan::new(&before, &after, vec![update]).is_err());
    }
    #[test]
    fn backend_changes_and_foreign_replacement_owners_are_rejected() {
        let before = revision(vec![unit("a"), unit("b")]);
        let mut rust = unit("a");
        rust.backend = AnalysisBackend::Rust;
        rust.source_languages = vec![SourceLanguage::Rust];
        rust.sources[0].language = "rust".into();
        rust.inputs = rust.sources.clone();
        let after = revision(vec![rust, unit("b")]);
        assert!(
            UnitMergePlan::new(
                &before,
                &after,
                vec![
                    request("a", UnitUpdateKind::Full),
                    request("b", UnitUpdateKind::Reuse)
                ]
            )
            .is_err()
        );
        let mut foreign = request("a", UnitUpdateKind::Delta);
        foreign.replacements = owners(Some(&unit("b"))).into_iter().collect();
        assert!(
            UnitMergePlan::new(
                &before,
                &before,
                vec![foreign, request("b", UnitUpdateKind::Reuse)]
            )
            .is_err()
        );
        let mut full = request("a", UnitUpdateKind::Full);
        full.replacements = owners(Some(&unit("a"))).into_iter().collect();
        assert!(
            UnitMergePlan::new(
                &before,
                &before,
                vec![full, request("b", UnitUpdateKind::Reuse)]
            )
            .is_err()
        );
    }
    #[test]
    fn reuse_uses_existing_host_accounts_and_keeps_budget_failure_identity() {
        let revision = revision(vec![unit("a")]);
        let host = AnalysisContext::new(AnalysisLimits::default(), None).unwrap();
        let hosts = BTreeMap::from([(AnalysisBackend::Java, host.clone())]);
        UnitMergePlan::all_reused_with_budget(&revision, &revision, &hosts).unwrap();
        let first = host.usage();
        assert!(first.work > 0 && first.payload_bytes > 0);
        UnitMergePlan::all_reused_with_budget(&revision, &revision, &hosts).unwrap();
        assert_eq!(host.usage().work, first.work * 2);
        assert_eq!(host.usage().payload_bytes, first.payload_bytes * 2);
        let limited = AnalysisContext::new(
            AnalysisLimits {
                max_work: 1,
                ..AnalysisLimits::default()
            },
            None,
        )
        .unwrap();
        let hosts = BTreeMap::from([(AnalysisBackend::Java, limited.clone())]);
        let error =
            UnitMergePlan::all_reused_with_budget(&revision, &revision, &hosts).unwrap_err();
        assert_eq!(
            error.code,
            DomainError::analysis(limited.usage().failure.unwrap()).code
        );
        assert_ne!(error.code, ErrorCode::ResultIdentityMismatch);
        assert!(UnitMergePlan::all_reused_with_budget(&revision, &revision, &hosts).is_err());
    }
}
