//! Deterministic allocation policy. Construct after frozen-input admission.
//! An allocation is not consumption; the task coordinator must own and settle grants.
use crate::{AnalysisFailure, AnalysisLimits, AnalysisUsage};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AnalysisLanguage {
    Java,
    Rust,
    Python,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AnalysisAllocation {
    pub language: AnalysisLanguage,
    pub total: AnalysisLimits,
    pub host: AnalysisLimits,
    pub worker: AnalysisLimits,
}
/// Plan remaining capacity once. The coordinator must enforce these grants.
pub fn allocate_analysis(
    limits: AnalysisLimits,
    admission: &AnalysisUsage,
    languages: &[AnalysisLanguage],
) -> Result<Vec<AnalysisAllocation>, AnalysisFailure> {
    limits.validate()?;
    if let Some(failure) = &admission.failure {
        return Err(failure.clone());
    }
    let ordered: BTreeSet<_> = languages.iter().copied().collect();
    if ordered.is_empty()
        || ordered.len() != languages.len()
        || admission.max_depth > limits.max_depth
    {
        return Err(AnalysisFailure::InvalidUsage);
    }
    let minimum = 2 * ordered.len() as u64; // one positive host and worker share per language
    let mut remaining = limits;
    for (name, used, total, target) in [
        (
            "work",
            admission.work,
            limits.max_work,
            &mut remaining.max_work,
        ),
        (
            "nodes",
            admission.nodes,
            limits.max_nodes,
            &mut remaining.max_nodes,
        ),
        (
            "payload_bytes",
            admission.payload_bytes,
            limits.max_payload_bytes,
            &mut remaining.max_payload_bytes,
        ),
    ] {
        *target = total
            .checked_sub(used)
            .ok_or(AnalysisFailure::InvalidUsage)?;
        if *target < minimum {
            return Err(AnalysisFailure::Limit {
                resource: name.into(),
                limit: total,
                used,
                requested: minimum,
            });
        }
    }
    let shares = remaining.partition(ordered.len())?;
    ordered
        .into_iter()
        .zip(shares)
        .map(|(language, total)| {
            let split = |value: u64| (value.div_ceil(4), value - (value.div_ceil(4)));
            let (hw, ww) = split(total.max_work);
            let (hn, wn) = split(total.max_nodes);
            let (hb, wb) = split(total.max_payload_bytes);
            let host = AnalysisLimits {
                max_work: hw,
                max_nodes: hn,
                max_payload_bytes: hb,
                max_depth: total.max_depth,
            };
            let worker = AnalysisLimits {
                max_work: ww,
                max_nodes: wn,
                max_payload_bytes: wb,
                max_depth: total.max_depth,
            };
            host.validate()?;
            worker.validate()?;
            Ok(AnalysisAllocation {
                language,
                total,
                host,
                worker,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn admission_is_subtracted_and_all_splits_conserve_capacity() {
        let limits = AnalysisLimits {
            max_work: 103,
            max_nodes: 103,
            max_payload_bytes: 103,
            max_depth: 7,
        };
        let used = AnalysisUsage {
            work: 2,
            nodes: 2,
            payload_bytes: 2,
            ..Default::default()
        };
        let plan = allocate_analysis(
            limits,
            &used,
            &[
                AnalysisLanguage::Python,
                AnalysisLanguage::Java,
                AnalysisLanguage::Rust,
            ],
        )
        .unwrap();
        assert_eq!(plan.iter().map(|p| p.total.max_work).sum::<u64>(), 101);
        assert_eq!(plan.iter().map(|p| p.total.max_nodes).sum::<u64>(), 101);
        assert_eq!(
            plan.iter().map(|p| p.total.max_payload_bytes).sum::<u64>(),
            101
        );
        assert_eq!(
            plan.iter().map(|p| p.language).collect::<Vec<_>>(),
            vec![
                AnalysisLanguage::Java,
                AnalysisLanguage::Rust,
                AnalysisLanguage::Python
            ]
        );
        assert_eq!(
            plan.iter().map(|p| p.total.max_work).collect::<Vec<_>>(),
            vec![34, 34, 33]
        );
        for p in plan {
            assert_eq!(p.host.max_work + p.worker.max_work, p.total.max_work);
            assert_eq!(p.host.max_nodes + p.worker.max_nodes, p.total.max_nodes);
            assert_eq!(
                p.host.max_payload_bytes + p.worker.max_payload_bytes,
                p.total.max_payload_bytes
            );
            assert_eq!(p.host.max_work, p.total.max_work.div_ceil(4));
            assert_eq!(p.worker.max_depth, 7);
        }
    }
    #[test]
    fn invalid_usage_duplicates_and_zero_shares_are_rejected() {
        let limits = AnalysisLimits::default();
        assert!(allocate_analysis(limits, &Default::default(), &[]).is_err());
        assert!(
            allocate_analysis(
                limits,
                &Default::default(),
                &[AnalysisLanguage::Rust, AnalysisLanguage::Rust]
            )
            .is_err()
        );
        assert_eq!(
            allocate_analysis(
                limits,
                &AnalysisUsage {
                    work: u64::MAX,
                    ..Default::default()
                },
                &[AnalysisLanguage::Rust]
            ),
            Err(AnalysisFailure::InvalidUsage)
        );
        assert!(
            allocate_analysis(
                AnalysisLimits {
                    max_work: 1,
                    ..limits
                },
                &Default::default(),
                &[AnalysisLanguage::Rust]
            )
            .is_err()
        );
    }
    #[test]
    fn failed_admission_cannot_be_restarted_as_a_new_grant() {
        let used = AnalysisUsage {
            failure: Some(AnalysisFailure::Cancelled),
            ..Default::default()
        };
        assert_eq!(
            allocate_analysis(Default::default(), &used, &[AnalysisLanguage::Rust]),
            Err(AnalysisFailure::Cancelled)
        );
    }
}
