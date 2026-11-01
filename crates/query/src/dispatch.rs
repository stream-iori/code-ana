//! Language dispatch selection and bounded reads.
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

pub(super) fn run(
    r: &dyn GraphAccess,
    req: &Request,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    for root in resolve_scoped(r, req)? {
        ensure!(
            root.kind == FactKind::CallSite,
            DomainError::new(ErrorCode::DispatchRequiresCallSite, "")
        );
        let language = language_policy::require(&root, "dispatch")?;
        if let Some(policy) = language.static_dispatch() {
            ensure!(req.algorithm != DispatchAlgorithm::Cha, policy.cha_error);
            ensure!(
                req.analysis_world == AnalysisWorld::Default,
                DomainError::new(ErrorCode::AnalysisWorldRequiresJavaCandidates, "")
            );
        }
        let targets = select(
            r,
            FactFilter {
                kinds: vec![FactKind::CallTarget],
                parent: Some(root.id.to_string()),
                ..Default::default()
            },
        )?;
        if let Some(policy) = language.static_dispatch() {
            if targets.is_empty() {
                evidence.semantic = false;
                evidence.reasons.push(format!(
                    "{}_unresolved_call:{}",
                    policy.reason_prefix, root.id
                ));
            }
            for target in targets {
                let exact = target.status == "resolved" && target.declaration_resolved;
                if !exact {
                    evidence.semantic = false;
                    evidence.reasons.push(format!(
                        "{}_partial_binding:{}",
                        policy.reason_prefix, target.id
                    ));
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
                    algorithm: Some(policy.algorithm.into()),
                    world: target.owner.world.to_string(),
                    ..Default::default()
                }));
            }
        } else {
            records.extend(java_dispatch::dispatch(r, req, &root, targets, evidence)?);
        }
    }
    Ok(records)
}
