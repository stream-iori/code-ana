//! Coordinator-owned static reservations and cumulative remote-usage reconciliation.
use crate::{
    AnalysisAllocation, AnalysisContext, AnalysisFailure, AnalysisLanguage, AnalysisLimits,
    AnalysisUsage, allocate_analysis,
};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, time::Instant};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AnalysisGrant {
    pub id: String,
    pub language: AnalysisLanguage,
    pub limits: AnalysisLimits,
}
/// One worker task, including retries. A new attempt cannot replace its grant,
/// reset counters, remove its deadline, or extend the original deadline.
#[derive(Default)]
pub struct AnalysisTaskMeter {
    active: Option<(Option<AnalysisGrant>, AnalysisContext)>,
}
impl AnalysisTaskMeter {
    pub fn active(&self) -> Option<(Option<AnalysisGrant>, AnalysisContext)> {
        self.active.clone()
    }
    pub fn resume(
        &mut self,
        grant: &Option<AnalysisGrant>,
        remaining_ms: u64,
    ) -> Result<AnalysisContext, AnalysisFailure> {
        if remaining_ms > crate::MAX_TASK_DEADLINE_SECONDS * 1000 {
            return Err(AnalysisFailure::InvalidUsage);
        }
        let deadline = if remaining_ms == 0 {
            None
        } else {
            Some(
                Instant::now()
                    .checked_add(std::time::Duration::from_millis(remaining_ms))
                    .ok_or(AnalysisFailure::InvalidUsage)?,
            )
        };
        if let Some((previous, context)) = &self.active {
            if previous != grant {
                return Err(AnalysisFailure::InvalidUsage);
            }
            if let Some(deadline) = deadline {
                context.tighten_deadline(deadline)?;
            }
            context.checkpoint()?;
            return Ok(context.clone());
        }
        let limits = grant.as_ref().ok_or(AnalysisFailure::InvalidUsage)?.limits;
        let context = AnalysisContext::new(limits, deadline)?;
        self.active = Some((grant.clone(), context.clone()));
        Ok(context)
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum GrantState {
    Reserved,
    Running,
    Complete,
    Failed,
    Unknown,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisGrantSnapshot {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub phase: Option<String>,
    pub grant: AnalysisGrant,
    pub state: GrantState,
    pub usage: Option<AnalysisUsage>,
}
struct LanguageReservation {
    allocation: AnalysisAllocation,
    host: AnalysisContext,
    workers: BTreeMap<String, AnalysisGrantSnapshot>,
}
pub struct AnalysisCoordinator {
    limits: AnalysisLimits,
    admission: AnalysisUsage,
    languages: BTreeMap<AnalysisLanguage, LanguageReservation>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisCoordinatorSnapshot {
    pub limits: AnalysisLimits,
    pub admission: AnalysisUsage,
    pub known_usage: AnalysisUsage,
    pub languages: Vec<AnalysisLanguageSnapshot>,
    pub complete: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisLanguageSnapshot {
    pub allocation: AnalysisAllocation,
    pub host_usage: AnalysisUsage,
    /// Compatibility alias for the first instance; never sum this with workers.
    pub worker: AnalysisGrantSnapshot,
    #[serde(default)]
    pub workers: Vec<AnalysisGrantSnapshot>,
}
impl AnalysisCoordinator {
    pub fn new(
        task_id: &str,
        limits: AnalysisLimits,
        admission: AnalysisUsage,
        languages: &[AnalysisLanguage],
        deadline: Option<Instant>,
    ) -> Result<Self, AnalysisFailure> {
        if task_id.is_empty() {
            return Err(AnalysisFailure::InvalidUsage);
        }
        let plan = allocate_analysis(limits, &admission, languages)?;
        let mut slots = BTreeMap::new();
        for allocation in plan {
            let language = allocation.language;
            let host = AnalysisContext::new(allocation.host, deadline)?;
            let grant = AnalysisGrant {
                id: format!("{task_id}/{language:?}/worker"),
                language,
                limits: allocation.worker,
            };
            slots.insert(
                language,
                LanguageReservation {
                    allocation,
                    host,
                    workers: BTreeMap::from([(
                        String::new(),
                        AnalysisGrantSnapshot {
                            phase: None,
                            grant,
                            state: GrantState::Reserved,
                            usage: None,
                        },
                    )]),
                },
            );
        }
        Ok(Self {
            limits,
            admission,
            languages: slots,
        })
    }
    pub fn host(&self, language: AnalysisLanguage) -> Result<AnalysisContext, AnalysisFailure> {
        self.languages
            .get(&language)
            .map(|s| s.host.clone())
            .ok_or(AnalysisFailure::InvalidUsage)
    }
    /// Freeze all concurrent instances before any quota is issued.
    pub fn configure_instances(
        &mut self,
        language: AnalysisLanguage,
        ids: &[String],
    ) -> Result<(), AnalysisFailure> {
        let slot = self
            .languages
            .get_mut(&language)
            .ok_or(AnalysisFailure::InvalidUsage)?;
        if ids.is_empty()
            || slot.workers.len() != 1
            || !slot.workers.contains_key("")
            || slot
                .workers
                .values()
                .any(|w| w.state != GrantState::Reserved)
        {
            return Err(AnalysisFailure::InvalidUsage);
        }
        let count = ids.len() as u64;
        let total = slot.allocation.worker;
        for (resource, limit) in [
            ("work", total.max_work),
            ("nodes", total.max_nodes),
            ("payload_bytes", total.max_payload_bytes),
        ] {
            if limit < count {
                let failure = AnalysisFailure::Limit {
                    resource: resource.into(),
                    limit,
                    used: 0,
                    requested: count,
                };
                slot.host.fail(failure.clone());
                return Err(failure);
            }
        }
        let base = &slot.workers.values().next().unwrap().grant.id;
        let bytes = ids.iter().fold(0u64, |n, id| {
            n.saturating_add(id.len() as u64)
                .saturating_add(base.len() as u64)
                .saturating_add(512)
        });
        slot.host.charge(count, count, bytes)?;
        let ordered = ids.iter().collect::<std::collections::BTreeSet<_>>();
        if ordered.len() != ids.len() || ordered.iter().any(|id| id.is_empty()) {
            return Err(AnalysisFailure::InvalidUsage);
        }
        let part = |n: u64, i: u64| n / count + u64::from(i < n % count);
        let mut workers = BTreeMap::new();
        for (i, id) in ordered.into_iter().enumerate() {
            let limits = AnalysisLimits {
                max_work: part(total.max_work, i as u64),
                max_nodes: part(total.max_nodes, i as u64),
                max_payload_bytes: part(total.max_payload_bytes, i as u64),
                max_depth: total.max_depth,
            };
            let grant = AnalysisGrant {
                id: format!("{base}/instance/{id}"),
                language,
                limits,
            };
            workers.insert(
                id.clone(),
                AnalysisGrantSnapshot {
                    grant,
                    state: GrantState::Reserved,
                    usage: None,
                    phase: None,
                },
            );
        }
        slot.workers = workers;
        Ok(())
    }
    pub fn issue(&mut self, language: AnalysisLanguage) -> Result<AnalysisGrant, AnalysisFailure> {
        self.issue_instance(language, "")
    }
    pub fn issue_instance(
        &mut self,
        language: AnalysisLanguage,
        instance: &str,
    ) -> Result<AnalysisGrant, AnalysisFailure> {
        let slot = self
            .languages
            .get_mut(&language)
            .ok_or(AnalysisFailure::InvalidUsage)?;
        slot.host.checkpoint()?;
        let worker = slot
            .workers
            .get_mut(instance)
            .ok_or(AnalysisFailure::InvalidUsage)?;
        if worker.state != GrantState::Reserved {
            return Err(AnalysisFailure::InvalidUsage);
        }
        worker.state = GrantState::Running;
        Ok(worker.grant.clone())
    }
    fn remote_mut(
        &mut self,
        grant: &AnalysisGrant,
    ) -> Result<&mut AnalysisGrantSnapshot, AnalysisFailure> {
        self.languages
            .get_mut(&grant.language)
            .and_then(|s| s.workers.values_mut().find(|r| r.grant == *grant))
            .ok_or(AnalysisFailure::InvalidUsage)
    }
    /// A duplicate exact terminal is idempotent; different or stale updates are rejected.
    pub fn reconcile(
        &mut self,
        grant: &AnalysisGrant,
        usage: AnalysisUsage,
        terminal: bool,
    ) -> Result<(), AnalysisFailure> {
        let remote = self.remote_mut(grant)?;
        validate_usage(&usage, grant.limits)?;
        let next = if !terminal {
            GrantState::Running
        } else if usage.failure.is_some() {
            GrantState::Failed
        } else {
            GrantState::Complete
        };
        if matches!(remote.state, GrantState::Complete | GrantState::Failed) {
            return if terminal && remote.state == next && remote.usage.as_ref() == Some(&usage) {
                Ok(())
            } else {
                Err(AnalysisFailure::InvalidUsage)
            };
        }
        if remote.state != GrantState::Running {
            return Err(AnalysisFailure::InvalidUsage);
        }
        if let Some(old) = &remote.usage
            && (usage.work < old.work
                || usage.nodes < old.nodes
                || usage.payload_bytes < old.payload_bytes
                || usage.max_depth < old.max_depth
                || old.failure.is_some() && usage.failure != old.failure)
        {
            return Err(AnalysisFailure::InvalidUsage);
        }
        remote.usage = Some(usage);
        remote.state = next;
        Ok(())
    }
    /// A failed attempt can have valid usage without a resource failure (e.g. IO).
    pub fn reconcile_failure(
        &mut self,
        grant: &AnalysisGrant,
        usage: AnalysisUsage,
    ) -> Result<(), AnalysisFailure> {
        if self
            .languages
            .get(&grant.language)
            .and_then(|s| s.workers.values().find(|w| w.grant == *grant))
            .is_some_and(|w| w.state == GrantState::Failed && w.usage.as_ref() == Some(&usage))
        {
            return Ok(());
        }
        self.reconcile(grant, usage, false)?;
        self.remote_mut(grant)?.state = GrantState::Failed;
        Ok(())
    }
    pub fn record_phase(
        &mut self,
        grant: &AnalysisGrant,
        phase: &str,
    ) -> Result<(), AnalysisFailure> {
        if !valid_analysis_phase(phase) {
            return Err(AnalysisFailure::InvalidUsage);
        }
        let remote = self.remote_mut(grant)?;
        if matches!(remote.state, GrantState::Reserved | GrantState::Unknown) {
            return Err(AnalysisFailure::InvalidUsage);
        }
        remote.phase = Some(phase.into());
        Ok(())
    }
    pub fn lost(&mut self, language: AnalysisLanguage) -> Result<(), AnalysisFailure> {
        let grant = self
            .languages
            .get(&language)
            .and_then(|s| s.workers.get(""))
            .ok_or(AnalysisFailure::InvalidUsage)?
            .grant
            .clone();
        self.lost_grant(&grant)
    }
    pub fn lost_grant(&mut self, grant: &AnalysisGrant) -> Result<(), AnalysisFailure> {
        let remote = self.remote_mut(grant)?;
        if remote.state != GrantState::Running {
            return Err(AnalysisFailure::InvalidUsage);
        }
        remote.state = GrantState::Unknown;
        self.languages.get(&grant.language).unwrap().host.cancel();
        Ok(())
    }
    pub fn cancel(&self) {
        for slot in self.languages.values() {
            slot.host.cancel();
        }
    }
    /// Reservations are not added to consumption. Unknown final usage stays explicit.
    pub fn snapshot(&self) -> AnalysisCoordinatorSnapshot {
        let mut total = self.admission.clone();
        let mut complete = total.failure.is_none();
        let mut languages = Vec::new();
        for slot in self.languages.values() {
            let host = slot.host.usage();
            add_usage(&mut total, &host);
            let workers = slot.workers.values().cloned().collect::<Vec<_>>();
            for worker in &workers {
                if let Some(remote) = &worker.usage {
                    add_usage(&mut total, remote);
                }
                complete &= worker.state == GrantState::Complete;
            }
            complete &= host.failure.is_none();
            languages.push(AnalysisLanguageSnapshot {
                allocation: slot.allocation.clone(),
                host_usage: host,
                worker: workers[0].clone(),
                workers,
            });
        }
        AnalysisCoordinatorSnapshot {
            limits: self.limits,
            admission: self.admission.clone(),
            known_usage: total,
            languages,
            complete,
        }
    }
}
pub fn valid_analysis_phase(phase: &str) -> bool {
    matches!(
        phase,
        "discover"
            | "reused"
            | "input_decode"
            | "backend"
            | "extract"
            | "incremental"
            | "records"
            | "archive"
            | "receiver"
    )
}
fn add_usage(total: &mut AnalysisUsage, value: &AnalysisUsage) {
    // All components occupy disjoint validated reservations within i64::MAX.
    total.work += value.work;
    total.nodes += value.nodes;
    total.payload_bytes += value.payload_bytes;
    total.max_depth = total.max_depth.max(value.max_depth);
    if total.failure.is_none() {
        total.failure = value.failure.clone();
    }
}
pub(crate) fn validate_usage(
    usage: &AnalysisUsage,
    limits: AnalysisLimits,
) -> Result<(), AnalysisFailure> {
    if usage.work > limits.max_work
        || usage.nodes > limits.max_nodes
        || usage.payload_bytes > limits.max_payload_bytes
        || usage.max_depth > limits.max_depth
    {
        return Err(AnalysisFailure::InvalidUsage);
    }
    if let Some(AnalysisFailure::Limit {
        resource,
        limit,
        used,
        requested,
    }) = &usage.failure
    {
        let maximum = match resource.as_str() {
            "work" => limits.max_work,
            "nodes" => limits.max_nodes,
            "payload_bytes" => limits.max_payload_bytes,
            "depth" => limits.max_depth as u64,
            _ => return Err(AnalysisFailure::InvalidUsage),
        };
        if *limit > maximum || *used > *limit || *requested <= limit.saturating_sub(*used) {
            return Err(AnalysisFailure::InvalidUsage);
        }
    }
    if matches!(
        usage.failure,
        Some(AnalysisFailure::InvalidLimits | AnalysisFailure::InvalidUsage)
    ) {
        return Err(AnalysisFailure::InvalidUsage);
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn coordinator() -> AnalysisCoordinator {
        AnalysisCoordinator::new(
            "task",
            AnalysisLimits::default(),
            AnalysisUsage {
                work: 7,
                ..Default::default()
            },
            &[AnalysisLanguage::Rust],
            None,
        )
        .unwrap()
    }
    #[test]
    fn issue_once_and_do_not_count_reservations_as_consumption() {
        let mut c = coordinator();
        assert_eq!(c.snapshot().known_usage.work, 7);
        c.issue(AnalysisLanguage::Rust).unwrap();
        assert!(c.issue(AnalysisLanguage::Rust).is_err());
        assert!(!c.snapshot().complete);
    }
    #[test]
    fn cumulative_reports_and_exact_terminal_replay_do_not_double_charge() {
        let mut c = coordinator();
        let grant = c.issue(AnalysisLanguage::Rust).unwrap();
        c.reconcile(
            &grant,
            AnalysisUsage {
                work: 10,
                ..Default::default()
            },
            false,
        )
        .unwrap();
        let usage = AnalysisUsage {
            work: 20,
            ..Default::default()
        };
        c.reconcile(&grant, usage.clone(), true).unwrap();
        c.reconcile(&grant, usage, true).unwrap();
        assert_eq!(c.snapshot().known_usage.work, 27);
        assert!(c.snapshot().complete);
    }
    #[test]
    fn wrong_identity_overuse_and_regression_cannot_mutate_ledger() {
        let mut c = coordinator();
        let grant = c.issue(AnalysisLanguage::Rust).unwrap();
        let mut other = grant.clone();
        other.id.push('x');
        assert!(c.reconcile(&other, Default::default(), false).is_err());
        assert!(
            c.reconcile(
                &grant,
                AnalysisUsage {
                    work: u64::MAX,
                    ..Default::default()
                },
                false
            )
            .is_err()
        );
        c.reconcile(
            &grant,
            AnalysisUsage {
                work: 9,
                ..Default::default()
            },
            false,
        )
        .unwrap();
        assert!(
            c.reconcile(
                &grant,
                AnalysisUsage {
                    work: 8,
                    ..Default::default()
                },
                false
            )
            .is_err()
        );
        assert_eq!(c.snapshot().known_usage.work, 16);
    }
    #[test]
    fn crash_keeps_last_watermark_and_never_reissues_unknown_capacity() {
        let mut c = coordinator();
        let grant = c.issue(AnalysisLanguage::Rust).unwrap();
        c.reconcile(
            &grant,
            AnalysisUsage {
                nodes: 3,
                ..Default::default()
            },
            false,
        )
        .unwrap();
        c.lost(AnalysisLanguage::Rust).unwrap();
        assert!(c.issue(AnalysisLanguage::Rust).is_err());
        assert!(c.reconcile(&grant, Default::default(), true).is_err());
        let s = c.snapshot();
        assert_eq!(s.known_usage.nodes, 3);
        assert!(!s.complete);
        assert_eq!(s.languages[0].worker.state, GrantState::Unknown);
    }
    #[test]
    fn host_and_worker_reservations_are_independent() {
        let mut c = coordinator();
        let host = c.host(AnalysisLanguage::Rust).unwrap();
        let grant = c.issue(AnalysisLanguage::Rust).unwrap();
        host.charge(5, 0, 0).unwrap();
        c.reconcile(
            &grant,
            AnalysisUsage {
                work: 10,
                ..Default::default()
            },
            true,
        )
        .unwrap();
        assert_eq!(c.snapshot().known_usage.work, 22);
    }
    #[test]
    fn non_resource_failure_cannot_certify_completion() {
        let mut c = coordinator();
        let grant = c.issue(AnalysisLanguage::Rust).unwrap();
        let usage = AnalysisUsage {
            work: 3,
            ..Default::default()
        };
        c.reconcile_failure(&grant, usage.clone()).unwrap();
        c.reconcile_failure(&grant, usage).unwrap();
        let snapshot = c.snapshot();
        assert!(!snapshot.complete);
        assert_eq!(snapshot.languages[0].worker.state, GrantState::Failed);
        assert_eq!(snapshot.known_usage.work, 10);
    }
    #[test]
    fn instances_share_one_worker_pool_without_duplicate_consumption() {
        let mut c = coordinator();
        c.configure_instances(AnalysisLanguage::Rust, &["b".into(), "a".into()])
            .unwrap();
        let a = c.issue_instance(AnalysisLanguage::Rust, "a").unwrap();
        let b = c.issue_instance(AnalysisLanguage::Rust, "b").unwrap();
        assert_ne!(a.id, b.id);
        let snapshot = c.snapshot();
        assert_eq!(
            a.limits.max_work + b.limits.max_work,
            snapshot.languages[0].allocation.worker.max_work
        );
        assert!(c.issue(AnalysisLanguage::Rust).is_err());
        assert!(c.issue_instance(AnalysisLanguage::Rust, "a").is_err());
        assert!(
            c.configure_instances(AnalysisLanguage::Rust, &["renew".into()])
                .is_err()
        );
        c.reconcile(
            &a,
            AnalysisUsage {
                work: 3,
                ..Default::default()
            },
            true,
        )
        .unwrap();
        c.reconcile(
            &b,
            AnalysisUsage {
                work: 5,
                ..Default::default()
            },
            true,
        )
        .unwrap();
        let snapshot = c.snapshot();
        assert_eq!(snapshot.known_usage.work, 17); // admission 7 + setup 2 + instances 3,5
        assert!(snapshot.complete);
        assert_eq!(snapshot.languages[0].workers.len(), 2);
    }
    #[test]
    fn losing_one_instance_preserves_other_watermarks_and_blocks_reissue() {
        let mut c = coordinator();
        c.configure_instances(AnalysisLanguage::Rust, &["a".into(), "b".into()])
            .unwrap();
        let a = c.issue_instance(AnalysisLanguage::Rust, "a").unwrap();
        let b = c.issue_instance(AnalysisLanguage::Rust, "b").unwrap();
        c.reconcile(
            &a,
            AnalysisUsage {
                work: 3,
                ..Default::default()
            },
            true,
        )
        .unwrap();
        c.reconcile(
            &b,
            AnalysisUsage {
                work: 4,
                ..Default::default()
            },
            false,
        )
        .unwrap();
        c.lost_grant(&b).unwrap();
        assert!(c.issue_instance(AnalysisLanguage::Rust, "b").is_err());
        let snapshot = c.snapshot();
        assert!(!snapshot.complete);
        assert_eq!(snapshot.known_usage.work, 16);
        assert_eq!(snapshot.languages[0].workers[0].state, GrantState::Complete);
        assert_eq!(snapshot.languages[0].workers[1].state, GrantState::Unknown);
    }
    #[test]
    fn instance_metadata_denial_does_not_partially_charge_work_or_nodes() {
        let mut c = AnalysisCoordinator::new(
            "task",
            AnalysisLimits {
                max_payload_bytes: 1024,
                ..Default::default()
            },
            Default::default(),
            &[AnalysisLanguage::Rust],
            None,
        )
        .unwrap();
        let before = c.host(AnalysisLanguage::Rust).unwrap().usage();
        assert!(matches!(
            c.configure_instances(AnalysisLanguage::Rust, &["a".into(), "b".into()]),
            Err(AnalysisFailure::Limit { resource, .. }) if resource == "payload_bytes"
        ));
        let after = c.host(AnalysisLanguage::Rust).unwrap().usage();
        assert_eq!(
            (before.work, before.nodes, before.payload_bytes),
            (after.work, after.nodes, after.payload_bytes)
        );
        assert!(c.issue(AnalysisLanguage::Rust).is_err());
    }

    #[test]
    fn insufficient_instance_quota_is_sticky_and_cannot_issue_after_failure() {
        let mut c = AnalysisCoordinator::new(
            "task",
            AnalysisLimits {
                max_work: 2,
                ..Default::default()
            },
            Default::default(),
            &[AnalysisLanguage::Rust],
            None,
        )
        .unwrap();
        let failure = c
            .configure_instances(AnalysisLanguage::Rust, &["a".into(), "b".into()])
            .unwrap_err();
        assert!(
            matches!(failure,AnalysisFailure::Limit { ref resource,limit:1,requested:2,.. } if resource=="work")
        );
        assert_eq!(c.snapshot().known_usage.failure, Some(failure));
        assert!(c.issue(AnalysisLanguage::Rust).is_err());
    }
}
