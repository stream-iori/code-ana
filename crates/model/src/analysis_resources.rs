//! Language-independent analysis admission. A scope shares its task ledger.
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    sync::{Arc, Mutex},
    time::Instant,
};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, deny_unknown_fields)]
pub struct AnalysisLimits {
    pub max_work: u64,
    pub max_nodes: u64,
    pub max_payload_bytes: u64,
    pub max_depth: u32,
}
impl Default for AnalysisLimits {
    fn default() -> Self {
        Self {
            max_work: 10_000_000,
            max_nodes: 1_000_000,
            max_payload_bytes: 512 << 20,
            max_depth: 256,
        }
    }
}
impl AnalysisLimits {
    pub fn validate(self) -> Result<(), AnalysisFailure> {
        if [self.max_work, self.max_nodes, self.max_payload_bytes]
            .iter()
            .any(|v| *v == 0 || *v > i64::MAX as u64)
            || !(1..=4096).contains(&self.max_depth)
        {
            return Err(AnalysisFailure::InvalidLimits);
        }
        Ok(())
    }
    /// Stable caller order is Java, Rust, Python. Unused shares are never borrowed.
    pub fn partition(self, languages: usize) -> Result<Vec<Self>, AnalysisFailure> {
        self.validate()?;
        if !(1..=3).contains(&languages) {
            return Err(AnalysisFailure::InvalidLimits);
        }
        let n = languages as u64;
        let split = |v: u64, i: usize| v / n + u64::from((i as u64) < v % n);
        let shares: Vec<_> = (0..languages)
            .map(|i| Self {
                max_work: split(self.max_work, i),
                max_nodes: split(self.max_nodes, i),
                max_payload_bytes: split(self.max_payload_bytes, i),
                max_depth: self.max_depth,
            })
            .collect();
        for share in &shares {
            share.validate()?;
        }
        Ok(shares)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, thiserror::Error)]
pub enum AnalysisFailure {
    #[error("ANALYSIS_USAGE_INVALID")]
    InvalidUsage,
    #[error("ANALYSIS_LIMITS_INVALID")]
    InvalidLimits,
    #[error("CANCELLED")]
    Cancelled,
    #[error("DEADLINE_EXCEEDED")]
    Deadline,
    #[error("ANALYSIS_RESOURCE_LIMIT: {resource} limit={limit} used={used} requested={requested}")]
    Limit {
        resource: String,
        limit: u64,
        used: u64,
        requested: u64,
    },
}
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct AnalysisUsage {
    pub work: u64,
    pub nodes: u64,
    pub payload_bytes: u64,
    pub max_depth: u32,
    pub failure: Option<AnalysisFailure>,
}
struct Scope {
    limits: AnalysisLimits,
    usage: AnalysisUsage,
}
struct State {
    scopes: Vec<Scope>,
    usage: AnalysisUsage,
    cancelled: bool,
    deadline: Option<Instant>,
}
#[derive(Clone)]
pub struct AnalysisContext {
    limits: AnalysisLimits,
    state: Arc<Mutex<State>>,
    ancestors: Vec<usize>,
}
impl AnalysisContext {
    pub fn new(limits: AnalysisLimits, deadline: Option<Instant>) -> Result<Self, AnalysisFailure> {
        limits.validate()?;
        Ok(Self {
            limits,
            state: Arc::new(Mutex::new(State {
                usage: AnalysisUsage::default(),
                cancelled: false,
                scopes: Vec::new(),
                deadline,
            })),
            ancestors: Vec::new(),
        })
    }
    /// Tightening applies to all clones/scopes and can never renew a deadline.
    pub fn tighten_deadline(&self, deadline: Instant) -> Result<(), AnalysisFailure> {
        let mut state = self.state.lock().unwrap();
        state.deadline = Some(state.deadline.map_or(deadline, |old| old.min(deadline)));
        self.check(&mut state)
    }
    pub fn remaining_millis(&self) -> Result<Option<u64>, AnalysisFailure> {
        let mut state = self.state.lock().unwrap();
        self.check(&mut state)?;
        Ok(state.deadline.map(|deadline| {
            u64::try_from(
                deadline
                    .saturating_duration_since(Instant::now())
                    .as_millis(),
            )
            .unwrap_or(u64::MAX)
            .max(1)
        }))
    }
    pub fn usage(&self) -> AnalysisUsage {
        self.state.lock().unwrap().usage.clone()
    }
    /// Preserve the first coordinator or backend failure on the root task.
    pub fn fail(&self, failure: AnalysisFailure) {
        let mut state = self.state.lock().unwrap();
        if state.usage.failure.is_none() {
            state.usage.failure = Some(failure);
        }
    }
    pub fn cancel(&self) {
        self.state.lock().unwrap().cancelled = true;
    }
    fn check(&self, state: &mut State) -> Result<(), AnalysisFailure> {
        if let Some(failure) = &state.usage.failure {
            return Err(failure.clone());
        }
        let failure = if state.cancelled {
            Some(AnalysisFailure::Cancelled)
        } else if state.deadline.is_some_and(|d| Instant::now() >= d) {
            Some(AnalysisFailure::Deadline)
        } else {
            None
        };
        if let Some(f) = failure {
            state.usage.failure = Some(f.clone());
            return Err(f);
        }
        Ok(())
    }
    pub fn checkpoint(&self) -> Result<(), AnalysisFailure> {
        self.check(&mut self.state.lock().unwrap())
    }
    pub fn charge(&self, work: u64, nodes: u64, payload_bytes: u64) -> Result<(), AnalysisFailure> {
        let mut state = self.state.lock().unwrap();
        self.check(&mut state)?;
        let candidates = std::iter::once((self.limits, &state.usage)).chain(
            self.ancestors.iter().map(|&index| {
                let scope = &state.scopes[index];
                (scope.limits, &scope.usage)
            }),
        );
        let failure = candidates.into_iter().find_map(|(limits, usage)| {
            [
                ("work", usage.work, work, limits.max_work),
                ("nodes", usage.nodes, nodes, limits.max_nodes),
                (
                    "payload_bytes",
                    usage.payload_bytes,
                    payload_bytes,
                    limits.max_payload_bytes,
                ),
            ]
            .into_iter()
            .find_map(|(name, used, requested, limit)| {
                crate::resources::capacity_failure(name, limit, used, requested).map(|failure| {
                    AnalysisFailure::Limit {
                        resource: failure.resource,
                        limit: failure.limit,
                        used: failure.used,
                        requested: failure.requested,
                    }
                })
            })
        });
        if let Some(failure) = failure {
            state.usage.failure = Some(failure.clone());
            return Err(failure);
        }
        state.usage.work += work;
        state.usage.nodes += nodes;
        state.usage.payload_bytes += payload_bytes;
        for &index in &self.ancestors {
            let usage = &mut state.scopes[index].usage;
            usage.work += work;
            usage.nodes += nodes;
            usage.payload_bytes += payload_bytes;
        }
        Ok(())
    }
    /// Resume one sequential FFI producer in this same root account. A receipt
    /// cannot reset counters; failed validation leaves the ledger unchanged.
    pub fn reconcile_cumulative(&self, next: AnalysisUsage) -> Result<(), AnalysisFailure> {
        if !self.ancestors.is_empty() {
            return Err(AnalysisFailure::InvalidUsage);
        }
        crate::analysis_grants::validate_usage(&next, self.limits)?;
        let mut state = self.state.lock().unwrap();
        if !state.scopes.is_empty() {
            return Err(AnalysisFailure::InvalidUsage);
        }
        let old = &state.usage;
        if next.work < old.work
            || next.nodes < old.nodes
            || next.payload_bytes < old.payload_bytes
            || next.max_depth < old.max_depth
            || old.failure.is_some() && old.failure != next.failure
        {
            return Err(AnalysisFailure::InvalidUsage);
        }
        state.usage = next;
        Ok(())
    }
    /// Local caps are additional restrictions, never replacement task limits.
    pub fn child_scope(&self, mut limits: AnalysisLimits) -> Result<Self, AnalysisFailure> {
        limits.validate()?;
        // Bound scope metadata and charge before cloning the ancestor path.
        self.charge(
            1,
            1,
            (4 * std::mem::size_of::<Scope>()
                + (self.ancestors.len() + 1) * std::mem::size_of::<usize>()) as u64,
        )?;
        let mut state = self.state.lock().unwrap();
        self.check(&mut state)?;
        let parent = self
            .ancestors
            .last()
            .map(|&i| state.scopes[i].limits)
            .unwrap_or(self.limits);
        limits.max_depth = limits.max_depth.min(parent.max_depth);
        let index = state.scopes.len();
        state.scopes.push(Scope {
            limits,
            usage: AnalysisUsage::default(),
        });
        let mut ancestors = self.ancestors.clone();
        ancestors.push(index);
        Ok(Self {
            limits: self.limits,
            state: self.state.clone(),
            ancestors,
        })
    }
    /// Root totals include this scope. Do not add these snapshots together.
    pub fn scope_usage(&self) -> AnalysisUsage {
        let state = self.state.lock().unwrap();
        let mut usage = self
            .ancestors
            .last()
            .map(|&i| state.scopes[i].usage.clone())
            .unwrap_or_else(|| state.usage.clone());
        usage.failure = state.usage.failure.clone();
        usage
    }
    /// Check an explicit-stack traversal's current depth without allocating a path key.
    pub fn observe_depth(&self, depth: u32) -> Result<(), AnalysisFailure> {
        let mut state = self.state.lock().unwrap();
        self.check(&mut state)?;
        let limit = self
            .ancestors
            .last()
            .map(|&i| state.scopes[i].limits.max_depth)
            .unwrap_or(self.limits.max_depth);
        if depth > limit {
            let failure = AnalysisFailure::Limit {
                resource: "depth".into(),
                limit: limit as u64,
                used: limit as u64,
                requested: (depth - limit) as u64,
            };
            state.usage.failure = Some(failure.clone());
            return Err(failure);
        }
        state.usage.max_depth = state.usage.max_depth.max(depth);
        for &i in &self.ancestors {
            state.scopes[i].usage.max_depth = state.scopes[i].usage.max_depth.max(depth);
        }
        Ok(())
    }
    pub fn traversal(&self) -> TraversalGuard {
        TraversalGuard {
            context: self.clone(),
            path: Arc::new(Mutex::new(BTreeSet::new())),
        }
    }
}
/// One guard per traversal, not per task: shared subtrees are legal after exit.
pub struct TraversalGuard {
    context: AnalysisContext,
    path: Arc<Mutex<BTreeSet<String>>>,
}
pub struct AnalysisPathEntry {
    path: Arc<Mutex<BTreeSet<String>>>,
    identity: String,
}
impl TraversalGuard {
    /// None means already active; the adaptor chooses reuse, skip, or domain error.
    pub fn enter(&self, identity: &str) -> Result<Option<AnalysisPathEntry>, AnalysisFailure> {
        self.context.checkpoint()?;
        let mut path = self.path.lock().unwrap();
        if path.contains(identity) {
            self.context.charge(1, 0, 0)?;
            return Ok(None);
        }
        let depth = path.len() as u64 + 1;
        let depth_limit = {
            let state = self.context.state.lock().unwrap();
            self.context
                .ancestors
                .last()
                .map(|&i| state.scopes[i].limits.max_depth)
                .unwrap_or(self.context.limits.max_depth)
        };
        if depth > depth_limit as u64 {
            let mut state = self.context.state.lock().unwrap();
            self.context.check(&mut state)?;
            let failure = AnalysisFailure::Limit {
                resource: "depth".into(),
                limit: depth_limit as u64,
                used: path.len() as u64,
                requested: 1,
            };
            state.usage.failure = Some(failure.clone());
            return Err(failure);
        }
        self.context.charge(
            1,
            0,
            (identity.len() as u64)
                .saturating_mul(2)
                .saturating_add(128),
        )?;
        {
            let mut state = self.context.state.lock().unwrap();
            state.usage.max_depth = state.usage.max_depth.max(depth as u32);
            for &index in &self.context.ancestors {
                state.scopes[index].usage.max_depth =
                    state.scopes[index].usage.max_depth.max(depth as u32);
            }
        }
        path.insert(identity.into());
        Ok(Some(AnalysisPathEntry {
            path: self.path.clone(),
            identity: identity.into(),
        }))
    }
}
impl Drop for AnalysisPathEntry {
    fn drop(&mut self) {
        self.path.lock().unwrap().remove(&self.identity);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn partition_conserves_all_dimensions() {
        let l = AnalysisLimits::default();
        let p = l.partition(3).unwrap();
        assert_eq!(p.iter().map(|x| x.max_work).sum::<u64>(), l.max_work);
        assert_eq!(p.iter().map(|x| x.max_nodes).sum::<u64>(), l.max_nodes);
        assert_eq!(
            p.iter().map(|x| x.max_payload_bytes).sum::<u64>(),
            l.max_payload_bytes
        );
        assert!(AnalysisLimits { max_work: 1, ..l }.partition(3).is_err());
    }
    #[test]
    fn admission_is_atomic_shared_and_sticky() {
        let c = AnalysisContext::new(
            AnalysisLimits {
                max_nodes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let child = c.clone();
        child.charge(2, 1, 3).unwrap();
        assert!(c.charge(1, 1, 1).is_err());
        assert_eq!(c.usage().work, 2);
        assert!(child.charge(0, 0, 0).is_err());
    }
    #[test]
    fn path_exit_allows_shared_nodes() {
        let c = AnalysisContext::new(AnalysisLimits::default(), None).unwrap();
        let t = c.traversal();
        let a = t.enter("a").unwrap().unwrap();
        assert!(t.enter("a").unwrap().is_none());
        drop(a);
        assert!(t.enter("a").unwrap().is_some());
    }
    #[test]
    fn depth_is_sticky_and_other_traversals_independent() {
        let c = AnalysisContext::new(
            AnalysisLimits {
                max_depth: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let t = c.traversal();
        let _a = t.enter("a").unwrap();
        assert!(c.traversal().enter("b").is_ok());
        assert!(t.enter("b").is_err());
        assert!(c.checkpoint().is_err());
    }
    #[test]
    fn cancellation_and_deadline_are_distinct() {
        let c = AnalysisContext::new(Default::default(), None).unwrap();
        c.cancel();
        assert_eq!(c.checkpoint(), Err(AnalysisFailure::Cancelled));
        let d = AnalysisContext::new(Default::default(), Some(Instant::now())).unwrap();
        assert_eq!(d.checkpoint(), Err(AnalysisFailure::Deadline));
    }
    #[test]
    fn parallel_admission_cannot_overspend() {
        let c = AnalysisContext::new(
            AnalysisLimits {
                max_work: 1000,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        std::thread::scope(|scope| {
            for _ in 0..8 {
                let c = c.clone();
                scope.spawn(move || while c.charge(1, 0, 0).is_ok() {});
            }
        });
        assert_eq!(c.usage().work, 1000);
        assert!(c.checkpoint().is_err());
    }
    #[test]
    fn overflow_and_invalid_limits_are_rejected() {
        assert!(
            AnalysisLimits {
                max_payload_bytes: u64::MAX,
                ..Default::default()
            }
            .validate()
            .is_err()
        );
        let c = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: i64::MAX as u64,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        c.charge(0, 0, u32::MAX as u64 + 1).unwrap();
        assert!(c.charge(0, 0, u64::MAX).is_err());
        assert_eq!(c.usage().payload_bytes, u32::MAX as u64 + 1);
    }
    #[test]
    fn local_rejection_never_charges_any_ancestor() {
        let root = AnalysisContext::new(Default::default(), None).unwrap();
        let child = root
            .child_scope(AnalysisLimits {
                max_nodes: 1,
                ..Default::default()
            })
            .unwrap();
        child.charge(1, 1, 5).unwrap();
        let before = root.usage();
        assert!(child.clone().charge(9, 1, 100).is_err());
        assert_eq!(root.usage().work, before.work);
        assert_eq!(root.usage().payload_bytes, before.payload_bytes);
        assert_eq!(child.scope_usage().nodes, 1);
        assert_eq!(child.scope_usage().failure, root.usage().failure);
        assert!(root.charge(0, 0, 0).is_err());
    }
    #[test]
    fn nested_scope_cannot_escape_parent_or_increase_depth() {
        let root = AnalysisContext::new(Default::default(), None).unwrap();
        let parent = root
            .child_scope(AnalysisLimits {
                max_work: 3,
                max_depth: 1,
                ..Default::default()
            })
            .unwrap();
        let child = parent.child_scope(Default::default()).unwrap();
        child.charge(2, 0, 0).unwrap();
        let before = root.usage();
        assert!(child.charge(1, 0, 0).is_err());
        assert_eq!(root.usage().work, before.work);
        let root = AnalysisContext::new(Default::default(), None).unwrap();
        let parent = root
            .child_scope(AnalysisLimits {
                max_depth: 1,
                ..Default::default()
            })
            .unwrap();
        let child = parent.child_scope(Default::default()).unwrap();
        let traversal = child.traversal();
        let _entry = traversal.enter("a").unwrap();
        assert!(traversal.enter("b").is_err());
    }
    #[test]
    fn tightening_deadline_is_shared_by_clones_and_child_scopes() {
        let root = AnalysisContext::new(Default::default(), None).unwrap();
        let child = root.child_scope(Default::default()).unwrap();
        let clone = root.clone();
        assert!(root.tighten_deadline(Instant::now()).is_err());
        assert_eq!(child.checkpoint(), Err(AnalysisFailure::Deadline));
        assert_eq!(clone.checkpoint(), Err(AnalysisFailure::Deadline));
        assert!(
            clone
                .tighten_deadline(Instant::now() + std::time::Duration::from_secs(60))
                .is_err()
        );
    }
    #[test]
    fn deadline_cancel_and_limit_have_distinct_domain_codes() {
        use crate::{DomainError, ErrorCode};
        assert_eq!(
            DomainError::analysis(AnalysisFailure::Deadline).code,
            ErrorCode::Timeout
        );
        assert_eq!(
            DomainError::analysis(AnalysisFailure::Cancelled).code,
            ErrorCode::Cancelled
        );
        assert_eq!(
            DomainError::analysis(AnalysisFailure::InvalidUsage).code,
            ErrorCode::AnalysisUsageInvalid
        );
        assert_eq!(
            DomainError::analysis(AnalysisFailure::Limit {
                resource: "work".into(),
                limit: 1,
                used: 1,
                requested: 1
            })
            .code,
            ErrorCode::AnalysisResourceLimit
        );
    }
}

#[cfg(test)]
mod cross_implementation_tests {
    use super::*;
    #[derive(Deserialize)]
    struct Vector {
        name: String,
        limits: AnalysisLimits,
        operations: Vec<Operation>,
        expected: AnalysisUsage,
    }
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Operation {
        Charge { charge: [u64; 3] },
        Depth { depth: u32 },
    }
    #[test]
    fn shared_rust_java_vectors() {
        let vectors: Vec<Vector> = serde_json::from_str(include_str!(
            "../../../tests/fixtures/analysis-resources/ledger-vectors.json"
        ))
        .unwrap();
        for vector in vectors {
            let context = AnalysisContext::new(vector.limits, None).unwrap();
            for operation in vector.operations {
                let _ = match operation {
                    Operation::Charge {
                        charge: [work, nodes, payload],
                    } => context.charge(work, nodes, payload),
                    Operation::Depth { depth } => context.observe_depth(depth),
                };
            }
            assert_eq!(context.usage(), vector.expected, "{}", vector.name);
        }
    }
    #[test]
    fn cumulative_ffi_receipt_is_monotonic_and_cannot_refund_or_reset_failure() {
        let context = AnalysisContext::new(Default::default(), None).unwrap();
        context.charge(3, 2, 1).unwrap();
        context
            .reconcile_cumulative(AnalysisUsage {
                work: 7,
                nodes: 3,
                payload_bytes: 4,
                max_depth: 2,
                failure: None,
            })
            .unwrap();
        assert!(
            context
                .reconcile_cumulative(AnalysisUsage {
                    work: 6,
                    ..context.usage()
                })
                .is_err()
        );
        assert_eq!(context.usage().work, 7);
        context.fail(AnalysisFailure::Deadline);
        assert!(
            context
                .reconcile_cumulative(AnalysisUsage {
                    failure: None,
                    ..context.usage()
                })
                .is_err()
        );
        assert_eq!(context.usage().failure, Some(AnalysisFailure::Deadline));
    }
}
