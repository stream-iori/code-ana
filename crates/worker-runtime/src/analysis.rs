//! Common worker budget and terminal policy, independent of an engine.
use codeana_contracts::{analysis_budget::*, build_context, worker as w};
use codeana_model::{AnalysisBackend, AnalysisFailure, AnalysisGrant};
pub fn expect_cancel(frame: w::Envelope, attempt: &str) -> anyhow::Result<()> {
    anyhow::ensure!(frame.attempt == attempt, "ATTEMPT_MISMATCH");
    anyhow::ensure!(
        matches!(frame.body, Some(w::envelope::Body::Cancel(_))),
        "UNEXPECTED_DURING_ANALYSIS"
    );
    Ok(())
}

pub fn begin_hello(
    meter: &mut codeana_model::AnalysisTaskMeter,
    hello: &w::Hello,
    language: AnalysisBackend,
) -> anyhow::Result<(Option<AnalysisGrant>, codeana_model::AnalysisContext)> {
    use prost::Message;
    let raw = hello
        .analysis_grant
        .as_ref()
        .ok_or(AnalysisFailure::InvalidUsage)?;
    anyhow::ensure!(raw.id.len() <= 256, "ANALYSIS_GRANT_ID_BUDGET");
    let grant = Some(required_grant(Some(raw), language)?);
    let budget = meter.resume(
        &grant,
        hello.deadline_ms.ok_or(AnalysisFailure::InvalidUsage)?,
    )?;
    budget.charge(1, 1, 512 + 3 * hello.encoded_len() as u64)?;
    Ok((grant, budget))
}

pub fn begin_discovery(
    meter: &mut codeana_model::AnalysisTaskMeter,
    discover: &w::Discover,
    language: AnalysisBackend,
) -> anyhow::Result<(Option<AnalysisGrant>, codeana_model::AnalysisContext)> {
    anyhow::ensure!(
        discovery_backend(discover)? == language,
        "DISCOVER_BACKEND_MISMATCH"
    );
    build_context::discovery(discover, language)?;
    let grant = Some(required_grant(
        discover.analysis_grant.as_deref(),
        language,
    )?);
    let expected_unit = grant
        .as_ref()
        .and_then(|g| g.id.rsplit_once("/instance/").map(|(_, id)| id))
        .unwrap_or(language.as_str());
    anyhow::ensure!(
        discover.unit == expected_unit,
        "DISCOVER_UNIT_GRANT_MISMATCH"
    );
    let budget = meter.resume(
        &grant,
        discover.deadline_ms.ok_or(AnalysisFailure::InvalidUsage)?,
    )?;
    let files = discover.manifest.as_ref().map_or(0, |m| m.files.len()) as u64;
    budget.charge(1 + files, 1 + files, 192 * files)?;
    Ok((grant, budget))
}

pub fn cancelled_receipt(
    grant: &Option<AnalysisGrant>,
    budget: &codeana_model::AnalysisContext,
    phase: &str,
    usage_known: bool,
) -> w::Failure {
    budget.cancel();
    let _ = budget.checkpoint();
    let mut value = failure_receipt(&anyhow::anyhow!("CANCELLED"), grant, budget, phase);
    if budget.usage().failure.is_none() {
        value.code = "CANCELLED".into();
    }
    if let Some(receipt) = &mut value.analysis_usage {
        receipt.usage_known = Some(usage_known);
    }
    value
}

pub fn failure_receipt(
    error: &anyhow::Error,
    grant: &Option<AnalysisGrant>,
    budget: &codeana_model::AnalysisContext,
    phase: &str,
) -> w::Failure {
    w::Failure {
        code: budget
            .usage()
            .failure
            .map(|f| codeana_model::DomainError::analysis(f).code.to_string())
            .unwrap_or_else(|| {
                error
                    .downcast_ref::<codeana_model::DomainError>()
                    .map(|e| e.code.to_string())
                    .unwrap_or_else(|| "DISCOVERY_FAILED".into())
            }),
        message: bounded_error(error),
        analysis_usage: grant.as_ref().map(|g| receipt(g, &budget.usage(), phase)),
    }
}

pub fn reconcile_receipt(
    coordinator: &mut codeana_model::AnalysisCoordinator,
    grant: &AnalysisGrant,
    value: Option<&w::AnalysisBudgetReceipt>,
    terminal: bool,
    failed: bool,
) -> anyhow::Result<()> {
    let value = value.ok_or_else(|| anyhow::anyhow!("ANALYSIS_USAGE_REQUIRED"))?;
    let usage = decode_receipt(value, grant)?;
    if !value
        .usage_known
        .ok_or_else(|| anyhow::anyhow!("ANALYSIS_USAGE_KNOWN_REQUIRED"))?
    {
        anyhow::ensure!(failed && terminal, "ANALYSIS_USAGE_INCOMPLETE");
        coordinator.reconcile(grant, usage, false)?;
        coordinator.record_phase(grant, &value.phase)?;
        coordinator.lost_grant(grant)?;
    } else {
        if failed {
            coordinator.reconcile_failure(grant, usage)?;
        } else {
            coordinator.reconcile(grant, usage, terminal)?;
        }
        coordinator.record_phase(grant, &value.phase)?;
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::{AnalysisLimits, AnalysisUsage};
    fn coordinator() -> (codeana_model::AnalysisCoordinator, AnalysisGrant) {
        let mut coordinator = codeana_model::AnalysisCoordinator::new(
            "task",
            Default::default(),
            Default::default(),
            &[AnalysisBackend::Rust],
            None,
        )
        .unwrap();
        let grant = coordinator.issue(AnalysisBackend::Rust).unwrap();
        (coordinator, grant)
    }
    #[test]
    fn deadlines_distinguish_explicit_unbounded_zero_from_missing() {
        use prost::Message;
        let (_, g) = coordinator();
        let mut raw = w::Discover {
            unit: "rust".into(),
            source_languages: vec!["rust".into()],
            config: Some(w::discover::Config::Rust(Box::default())),
            analysis_grant: Some(Box::new(encode_grant(&g))),
            deadline_ms: None,
            ..Default::default()
        };
        let mut meter = codeana_model::AnalysisTaskMeter::default();
        assert!(begin_discovery(&mut meter, &raw, AnalysisBackend::Rust).is_err());
        assert!(meter.active().is_none());
        raw.deadline_ms = Some(0);
        let decoded = w::Discover::decode(raw.encode_to_vec().as_slice()).unwrap();
        assert_eq!(decoded.deadline_ms, Some(0));
        let (_, budget) = begin_discovery(&mut meter, &decoded, AnalysisBackend::Rust).unwrap();
        assert_eq!(budget.remaining_millis().unwrap(), None);
    }
    #[test]
    fn unknown_usage_presence_survives_wire_encoding_and_absence_is_rejected() {
        use prost::Message;
        let (mut c, g) = coordinator();
        let mut raw = receipt(&g, &Default::default(), "backend");
        raw.usage_known = Some(false);
        let decoded = w::AnalysisBudgetReceipt::decode(raw.encode_to_vec().as_slice()).unwrap();
        assert_eq!(decoded.usage_known, Some(false));
        raw.usage_known = None;
        let absent = w::AnalysisBudgetReceipt::decode(raw.encode_to_vec().as_slice()).unwrap();
        assert_eq!(absent.usage_known, None);
        assert!(reconcile_receipt(&mut c, &g, Some(&absent), true, true).is_err());
        assert!(c.snapshot().languages[0].workers[0].usage.is_none());
    }
    #[test]
    fn cancellation_control_requires_the_active_attempt_and_correct_message() {
        assert!(
            expect_cancel(
                codeana_contracts::envelope("active", w::envelope::Body::Cancel(w::Cancel {})),
                "active"
            )
            .is_ok()
        );
        assert!(
            expect_cancel(
                codeana_contracts::envelope("stale", w::envelope::Body::Cancel(w::Cancel {})),
                "active"
            )
            .is_err()
        );
        assert!(
            expect_cancel(
                codeana_contracts::envelope("active", w::envelope::Body::Reuse(w::Reuse {})),
                "active"
            )
            .is_err()
        );
    }
    #[test]
    fn strict_terminal_receipts_cannot_default_or_certify_unknown_success() {
        let (mut c, g) = coordinator();
        assert!(reconcile_receipt(&mut c, &g, None, true, true).is_err());
        let mut raw = receipt(
            &g,
            &AnalysisUsage {
                work: 7,
                ..Default::default()
            },
            "archive",
        );
        raw.usage_known = None;
        assert!(reconcile_receipt(&mut c, &g, Some(&raw), true, true).is_err());
        raw.usage_known = Some(false);
        assert!(reconcile_receipt(&mut c, &g, Some(&raw), true, false).is_err());
        assert!(c.snapshot().languages[0].workers[0].usage.is_none());
        reconcile_receipt(&mut c, &g, Some(&raw), true, true).unwrap();
        let snapshot = c.snapshot();
        assert!(!snapshot.complete);
        assert_eq!(
            snapshot.languages[0].workers[0].state,
            codeana_model::GrantState::Unknown
        );
        assert_eq!(
            snapshot.languages[0].workers[0]
                .usage
                .as_ref()
                .unwrap()
                .work,
            7
        );
    }
    #[test]
    fn strict_replan_receipt_is_cumulative_and_final_end_is_idempotent() {
        let (mut c, g) = coordinator();
        let partial = receipt(
            &g,
            &AnalysisUsage {
                work: 3,
                ..Default::default()
            },
            "incremental",
        );
        reconcile_receipt(&mut c, &g, Some(&partial), false, false).unwrap();
        let end = receipt(
            &g,
            &AnalysisUsage {
                work: 5,
                ..Default::default()
            },
            "archive",
        );
        reconcile_receipt(&mut c, &g, Some(&end), true, false).unwrap();
        reconcile_receipt(&mut c, &g, Some(&end), true, false).unwrap();
        assert!(c.snapshot().complete);
        assert_eq!(c.snapshot().known_usage.work, 5);
        assert!(reconcile_receipt(&mut c, &g, Some(&partial), false, false).is_err());
    }
    #[test]
    fn replan_matrix_keeps_one_grant_for_each_static_language_instance() {
        use AnalysisBackend::{Java, Python, Rust};
        use codeana_model::{AnalysisCoordinator, AnalysisTaskMeter};
        for languages in [
            vec![Java, Rust],
            vec![Java, Python],
            vec![Rust, Python],
            vec![Java, Rust, Python],
        ] {
            let mut coordinator = AnalysisCoordinator::new(
                "task",
                Default::default(),
                Default::default(),
                &languages,
                None,
            )
            .unwrap();
            for language in &languages {
                coordinator
                    .configure_instances(*language, &["a".into(), "b".into()])
                    .unwrap();
            }
            let admission = coordinator.snapshot().known_usage;
            for language in &languages {
                for instance in ["a", "b"] {
                    let grant = coordinator.issue_instance(*language, instance).unwrap();
                    let mut meter = AnalysisTaskMeter::default();
                    let first = meter.resume(&Some(grant.clone()), 60000).unwrap();
                    first.charge(3, 2, 128).unwrap();
                    let replan = receipt(&grant, &first.usage(), "incremental");
                    reconcile_receipt(&mut coordinator, &grant, Some(&replan), false, false)
                        .unwrap();
                    assert!(!coordinator.snapshot().complete);
                    assert!(coordinator.issue_instance(*language, instance).is_err());
                    let retried = meter.resume(&Some(grant.clone()), 60000).unwrap();
                    assert_eq!(retried.usage(), first.usage());
                    retried.charge(5, 1, 64).unwrap();
                    assert_eq!(first.usage().work, 8);
                    let end = receipt(&grant, &retried.usage(), "archive");
                    reconcile_receipt(&mut coordinator, &grant, Some(&end), true, false).unwrap();
                    reconcile_receipt(&mut coordinator, &grant, Some(&end), true, false).unwrap();
                    assert!(
                        reconcile_receipt(&mut coordinator, &grant, Some(&replan), false, false)
                            .is_err()
                    );
                }
            }
            let final_state = coordinator.snapshot();
            assert!(final_state.complete);
            assert_eq!(
                final_state.known_usage.work,
                admission.work + 16 * languages.len() as u64
            );
            assert_eq!(
                final_state.known_usage.nodes,
                admission.nodes + 6 * languages.len() as u64
            );
        }
    }
    #[test]
    fn known_io_failure_retains_usage_without_fabricating_a_resource_limit() {
        let (mut c, g) = coordinator();
        let raw = receipt(
            &g,
            &AnalysisUsage {
                nodes: 2,
                ..Default::default()
            },
            "backend",
        );
        reconcile_receipt(&mut c, &g, Some(&raw), true, true).unwrap();
        let snapshot = c.snapshot();
        assert_eq!(
            snapshot.languages[0].workers[0].state,
            codeana_model::GrantState::Failed
        );
        assert_eq!(
            snapshot.languages[0].workers[0]
                .usage
                .as_ref()
                .unwrap()
                .failure,
            None
        );
    }
    #[test]
    fn discovery_receipt_is_not_a_semantic_context_field() {
        let mut model = codeana_model::BuildContext::new(
            "rust",
            codeana_model::BackendContext::Rust {
                toolchain: "test".into(),
                adapter: "test".into(),
            },
        );
        model.producer = "rust-core".into();
        model.modules = vec!["app".into()];
        model.seal();
        let mut context = w::BuildContext::from(&model);
        let before = codeana_model::BuildContext::try_from(&context).unwrap();
        let grant = AnalysisGrant {
            id: "task/rust/worker".into(),
            language: AnalysisBackend::Rust,
            limits: Default::default(),
        };
        context.analysis_usage = Some(receipt(
            &grant,
            &AnalysisUsage {
                work: 7,
                payload_bytes: 500,
                ..Default::default()
            },
            "discover",
        ));
        let after = codeana_model::BuildContext::try_from(&context).unwrap();
        assert_eq!(
            codeana_model::digest(&before),
            codeana_model::digest(&after)
        );
        assert!(w::BuildContext::from(&after).analysis_usage.is_none());
    }
    #[test]
    fn discovery_never_defaults_missing_or_wrong_language_grants() {
        let mut meter = codeana_model::AnalysisTaskMeter::default();
        assert!(
            begin_discovery(&mut meter, &w::Discover::default(), AnalysisBackend::Java).is_err()
        );
        assert!(meter.active().is_none());
        let wrong = AnalysisGrant {
            id: "task/python/worker".into(),
            language: AnalysisBackend::Python,
            limits: Default::default(),
        };
        let d = w::Discover {
            unit: "rust".into(),
            source_languages: vec!["rust".into()],
            config: Some(w::discover::Config::Rust(Box::default())),
            deadline_ms: Some(0),
            analysis_grant: Some(Box::new(encode_grant(&wrong))),
            ..Default::default()
        };
        assert!(begin_discovery(&mut meter, &d, AnalysisBackend::Java).is_err());
        assert!(meter.active().is_none());
    }
    #[test]
    fn discovery_and_analysis_share_grant_counters_and_cannot_renew() {
        let grant = AnalysisGrant {
            id: "task/rust/worker".into(),
            language: AnalysisBackend::Rust,
            limits: Default::default(),
        };
        let d = w::Discover {
            unit: "rust".into(),
            source_languages: vec!["rust".into()],
            config: Some(w::discover::Config::Rust(Box::default())),
            analysis_grant: Some(Box::new(encode_grant(&grant))),
            deadline_ms: Some(1000),
            ..Default::default()
        };
        let mut meter = codeana_model::AnalysisTaskMeter::default();
        begin_hello(
            &mut meter,
            &w::Hello {
                analysis_grant: Some(encode_grant(&grant)),
                deadline_ms: Some(1000),
                ..Default::default()
            },
            AnalysisBackend::Rust,
        )
        .unwrap();
        let (selected, discovered) =
            begin_discovery(&mut meter, &d, AnalysisBackend::Rust).unwrap();
        assert!(discovered.usage().payload_bytes > 0);
        let next = meter.resume(&selected, 0).unwrap();
        assert_eq!(next.usage(), discovered.usage());
        assert!(meter.resume(&None, 0).is_err());
        let mut changed = grant;
        changed.id = "another".into();
        assert!(meter.resume(&Some(changed), 0).is_err());
    }
    #[test]
    fn discovery_cannot_switch_to_another_instance_of_the_same_backend() {
        let mut coordinator = codeana_model::AnalysisCoordinator::new(
            "task",
            Default::default(),
            Default::default(),
            &[AnalysisBackend::Rust],
            None,
        )
        .unwrap();
        coordinator
            .configure_instances(AnalysisBackend::Rust, &["a".into(), "b".into()])
            .unwrap();
        let a = coordinator
            .issue_instance(AnalysisBackend::Rust, "a")
            .unwrap();
        let b = coordinator
            .issue_instance(AnalysisBackend::Rust, "b")
            .unwrap();
        let mut meter = codeana_model::AnalysisTaskMeter::default();
        let (_, budget) = begin_hello(
            &mut meter,
            &w::Hello {
                protocol: codeana_model::WORKER_PROTOCOL_VERSION,
                fact_ir: codeana_model::VERSION,
                analysis_grant: Some(encode_grant(&a)),
                deadline_ms: Some(1000),
                ..Default::default()
            },
            AnalysisBackend::Rust,
        )
        .unwrap();
        let before = budget.usage();
        let discovery = w::Discover {
            unit: "b".into(),
            source_languages: vec!["rust".into()],
            config: Some(w::discover::Config::Rust(Box::default())),
            analysis_grant: Some(Box::new(encode_grant(&b))),
            deadline_ms: Some(1000),
            ..Default::default()
        };
        assert!(begin_discovery(&mut meter, &discovery, AnalysisBackend::Rust).is_err());
        let (selected, active) = meter.active().unwrap();
        assert_eq!(selected, Some(a));
        assert_eq!(active.usage(), before);
    }
    #[test]
    fn discovery_rejection_is_atomic_and_has_a_typed_receipt() {
        let grant = AnalysisGrant {
            id: "task/rust/worker".into(),
            language: AnalysisBackend::Rust,
            limits: AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
        };
        let d = w::Discover {
            unit: "rust".into(),
            source_languages: vec!["rust".into()],
            config: Some(w::discover::Config::Rust(Box::default())),
            deadline_ms: Some(0),
            analysis_grant: Some(Box::new(encode_grant(&grant))),
            manifest: Some(w::Manifest {
                files: vec![w::SourceUnit::default()],
            }),
        };
        let mut meter = codeana_model::AnalysisTaskMeter::default();
        let error = begin_discovery(&mut meter, &d, AnalysisBackend::Rust)
            .err()
            .unwrap();
        let (selected, budget) = meter.active().unwrap();
        assert_eq!(
            (
                budget.usage().work,
                budget.usage().nodes,
                budget.usage().payload_bytes
            ),
            (0, 0, 0)
        );
        let failed = failure_receipt(&error, &selected, &budget, "discover");
        assert_eq!(failed.code, "ANALYSIS_RESOURCE_LIMIT");
        assert_eq!(
            decode_receipt(failed.analysis_usage.as_ref().unwrap(), &grant).unwrap(),
            budget.usage()
        );
    }
    #[test]
    fn grant_and_usage_round_trip_without_json() {
        let grant = AnalysisGrant {
            id: "task/rust/worker".into(),
            language: AnalysisBackend::Rust,
            limits: Default::default(),
        };
        assert_eq!(decode_grant(&encode_grant(&grant)).unwrap(), grant);
        for failure in [
            None,
            Some(AnalysisFailure::Cancelled),
            Some(AnalysisFailure::Deadline),
            Some(AnalysisFailure::Limit {
                resource: "nodes".into(),
                limit: 8,
                used: 8,
                requested: 1,
            }),
        ] {
            let used = AnalysisUsage {
                work: 5,
                nodes: 8,
                payload_bytes: 16,
                max_depth: 2,
                failure,
            };
            assert_eq!(
                decode_receipt(&receipt(&grant, &used, "extract"), &grant).unwrap(),
                used
            );
        }
    }
    #[test]
    fn missing_limits_unknown_language_and_malformed_failures_are_rejected() {
        assert!(decode_grant(&w::AnalysisBudgetGrant::default()).is_err());
        let grant = AnalysisGrant {
            id: "id".into(),
            language: AnalysisBackend::Java,
            limits: Default::default(),
        };
        let mut wire = encode_grant(&grant);
        wire.language = "other".into();
        assert!(decode_grant(&wire).is_err());
        let mut wire = encode_grant(&grant);
        wire.limits = None;
        assert!(decode_grant(&wire).is_err());
        let mut value = receipt(&grant, &Default::default(), "records");
        value.grant_id = "wrong".into();
        assert!(decode_receipt(&value, &grant).is_err());
        let value = w::AnalysisBudgetUsage {
            failure: Some(w::AnalysisBudgetFailure {
                code: "ANALYSIS_RESOURCE_LIMIT".into(),
                resource: "work".into(),
                limit: 10,
                used: 1,
                requested: 1,
            }),
            ..Default::default()
        };
        assert!(decode_usage(&value).is_err());
    }
}
