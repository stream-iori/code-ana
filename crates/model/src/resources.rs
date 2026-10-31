//! A root operation's monotonic resource ledger. Clones share the same ledger.
use crate::{DomainError, Evidence};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub struct ResourceLimits {
    pub max_work: u64,
    pub max_payload_bytes: u64,
}
impl Default for ResourceLimits {
    fn default() -> Self {
        Self {
            max_work: 100_000,
            max_payload_bytes: 64 << 20,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ResourceLimit {
    pub resource: String,
    pub limit: u64,
    pub used: u64,
    pub requested: u64,
}
/// Shared overflow-safe admission primitive. Used by query and analysis ledgers.
pub(crate) fn capacity_failure(
    resource: &str,
    limit: u64,
    used: u64,
    requested: u64,
) -> Option<ResourceLimit> {
    (requested > limit.saturating_sub(used)).then(|| ResourceLimit {
        resource: resource.into(),
        limit,
        used,
        requested,
    })
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ResourceUsage {
    pub limits: ResourceLimits,
    pub work: u64,
    pub payload_bytes: u64,
    pub exhausted: Option<ResourceLimit>,
}
#[derive(Debug, Clone)]
pub struct ResourceBudget(Arc<Mutex<ResourceUsage>>);
impl ResourceBudget {
    pub fn new(limits: ResourceLimits) -> Self {
        Self(Arc::new(Mutex::new(ResourceUsage {
            limits,
            work: 0,
            payload_bytes: 0,
            exhausted: None,
        })))
    }
    pub fn usage(&self) -> ResourceUsage {
        self.0.lock().unwrap().clone()
    }
    pub fn exhausted(&self) -> bool {
        self.0.lock().unwrap().exhausted.is_some()
    }
    pub fn remaining_payload(&self) -> u64 {
        let s = self.0.lock().unwrap();
        if s.exhausted.is_some() {
            0
        } else {
            s.limits.max_payload_bytes - s.payload_bytes
        }
    }
    pub fn charge(&self, work: u64, bytes: u64) -> Result<(), DomainError> {
        let mut s = self.0.lock().unwrap();
        if s.exhausted.is_some() {
            return Err(Self::error());
        }
        for (resource, used, requested, limit) in [
            ("work", s.work, work, s.limits.max_work),
            (
                "payload_bytes",
                s.payload_bytes,
                bytes,
                s.limits.max_payload_bytes,
            ),
        ] {
            if let Some(failure) = capacity_failure(resource, limit, used, requested) {
                s.exhausted = Some(failure);
                return Err(Self::error());
            }
        }
        s.work += work;
        s.payload_bytes += bytes;
        Ok(())
    }
    pub fn error() -> DomainError {
        crate::ErrorCode::ComputationLimit.as_str().into()
    }
    pub fn apply(&self, evidence: &mut Evidence) {
        let usage = self.usage();
        if usage.exhausted.is_some() {
            evidence.enumeration = false;
            if !evidence.reasons.iter().any(|s| s == "COMPUTATION_LIMIT") {
                evidence.reasons.push("COMPUTATION_LIMIT".into());
            }
            if !evidence.limits.iter().any(|s| s == "resource") {
                evidence.limits.push("resource".into());
            }
            evidence.resource_usage = Some(usage);
        }
    }
}

/// Allocation-free upper bound for retaining a serialized value (includes JSON framing).
pub fn retained_size(value: &impl Serialize) -> Result<u64, serde_json::Error> {
    struct Counter(u64);
    impl std::io::Write for Counter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0 = self.0.saturating_add(bytes.len() as u64);
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut counter = Counter(0);
    serde_json::to_writer(&mut counter, value)?;
    Ok(counter.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn clones_share_sticky_ledger_and_rejected_allocations_do_not_charge() {
        let b = ResourceBudget::new(ResourceLimits {
            max_work: 2,
            max_payload_bytes: 10,
        });
        b.charge(1, 6).unwrap();
        let child = b.clone();
        child.charge(1, 4).unwrap();
        assert!(child.charge(1, 0).is_err());
        assert!(b.charge(0, 0).is_err());
        assert_eq!(b.usage().work, 2);
        assert_eq!(b.usage().payload_bytes, 10);
    }
    #[test]
    fn large_limits_and_overflow_are_checked() {
        let b = ResourceBudget::new(ResourceLimits {
            max_work: i64::MAX as u64,
            max_payload_bytes: i64::MAX as u64,
        });
        b.charge(u32::MAX as u64 + 1, 0).unwrap();
        assert!(b.charge(u64::MAX, 0).is_err());
    }
}
