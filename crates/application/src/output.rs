//! Final-format budgeting. Delivery refusal never rewrites analysis or publication gates.
use anyhow::{Result, ensure};
use codeana_model::*;

pub(crate) fn prepare(request: &Request, result: &mut TaskResult) {
    result.output.delivery.context = DeliveryContext {
        format: request.delivery.format(),
        detail: request.delivery.detail(),
        operation: request.operation,
        max_output_bytes: request.max_output_bytes,
        historical: !request.revision.is_empty() || request.operation == Operation::Diff,
        comparison: result
            .output
            .delivery
            .context
            .comparison
            .take()
            .or_else(|| {
                (request.operation == Operation::Diff).then(|| ComparisonContext {
                    section: if request.name.is_empty() {
                        "files".into()
                    } else {
                        request.name.clone()
                    },
                    base: request.base.clone(),
                    target: request.target.clone(),
                    ..Default::default()
                })
            }),
    };
    result.output.delivery.projection_version = PUBLIC_PROJECTION_VERSION;
    result.output.delivery.returned = result.output.records.len();
    if !result.output.cursor.is_empty()
        && matches!(
            result.output.delivery.state,
            DeliveryState::Complete | DeliveryState::Page
        )
    {
        result.output.delivery.state = DeliveryState::Page;
    }
}

pub fn render(request: &Request, result: &mut TaskResult) -> Result<Vec<u8>> {
    let limit = if request.max_output_bytes == 0 {
        32768
    } else {
        request.max_output_bytes as usize
    };
    ensure!(
        limit >= 2048,
        DomainError::new(ErrorCode::OutputBudgetTooSmall, "minimum 2048")
    );
    prepare(request, result);
    let encoded = crate::presentation::encode(request, result)?;
    let record_limit = if request.max_records == 0 {
        100
    } else {
        request.max_records as usize
    };
    if encoded.len() <= limit && result.output.records.len() <= record_limit {
        return Ok(encoded);
    }
    // Query pages must be sliced by the same codec before cursor advancement.
    // Never silently discard page records here, which would skip them on resume.
    if !result.output.cursor.is_empty() {
        return Err(DomainError::new(
            ErrorCode::RecordExceedsOutputBudget,
            "page projection exceeds --max-output-bytes",
        )
        .into());
    }
    if result.output.delivery.state != DeliveryState::Failed {
        result.output.delivery.state = DeliveryState::Partial;
    }
    result.output.delivery.strict_rejected = request.strict;
    if request.strict && result.exit_code == 0 {
        result.exit_code = 3;
        result.code = "OUTPUT_LIMIT_EXCEEDED".into();
        result.message = "Business output exceeds the delivery record/byte budget".into();
    }
    // Preserve the complete persisted result; only the public response is bounded.
    let mut view = result.clone();
    view.output.records.truncate(record_limit);
    loop {
        view.output.delivery.returned = view.output.records.len();
        let encoded = crate::presentation::encode(request, &view)?;
        if encoded.len() <= limit {
            result.output.delivery.returned = view.output.delivery.returned;
            return Ok(encoded);
        }
        if view.output.records.pop().is_none() {
            break;
        }
    }
    Err(DomainError::new(
        ErrorCode::TerminalOutputBudget,
        "necessary response exceeds --max-output-bytes",
    )
    .into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn ordinary_text_has_one_closure_and_no_protocol_banner() {
        let req = Request::default();
        let mut result = TaskResult::success(Output {
            evidence: Evidence::complete(),
            ..Default::default()
        });
        let text = String::from_utf8(render(&req, &mut result).unwrap()).unwrap();
        assert_eq!(text, "end\n");
        assert_eq!(
            result.output.delivery.projection_version,
            PUBLIC_PROJECTION_VERSION
        );
    }
    #[test]
    fn explicit_json_is_one_document_and_keeps_false_values() {
        let req = Request {
            delivery: DeliveryOptions {
                format: Some(OutputFormat::Json),
                ..Default::default()
            },
            ..Default::default()
        };
        let mut result = TaskResult::success(Output {
            records: vec![
                Record::display(
                    &json!({"enabled":false,"display_name":"duplicate","metadata":"{}"}),
                )
                .unwrap(),
            ],
            ..Default::default()
        });
        let value: serde_json::Value =
            serde_json::from_slice(&render(&req, &mut result).unwrap()).unwrap();
        assert_eq!(value["records"][0]["enabled"], false);
        assert!(value["records"][0].get("display_name").is_none());
        assert_eq!(value["records"][0]["metadata"], "{}");
        assert!(value.get("result").is_some());
    }
    #[test]
    fn nonquery_record_budget_limits_delivery_and_keeps_the_full_persisted_result() {
        let request = Request {
            operation: Operation::SnapshotList,
            max_records: 1,
            ..Default::default()
        };
        let mut result = TaskResult::success(Output {
            records: (0..3)
                .map(|id| Record::display(&json!({"id":id})).unwrap())
                .collect(),
            evidence: Evidence::complete(),
            ..Default::default()
        });
        let text = String::from_utf8(render(&request, &mut result).unwrap()).unwrap();
        assert!(text.contains("id: 0"));
        assert!(!text.contains("id: 1"));
        assert!(text.contains("delivery: partial"));
        assert_eq!(result.output.records.len(), 3);
        assert_eq!(result.exit_code, 0);
        assert!(result.output.evidence.enumeration);
    }
    #[test]
    fn delivery_strict_refusal_preserves_gate_commit_and_original_records() {
        let req = Request {
            strict: true,
            max_output_bytes: 2048,
            ..Default::default()
        };
        let mut result = TaskResult::success(Output {
            records: (0..40)
                .map(|_| Record::display(&json!({"body":"中😀".repeat(100)})).unwrap())
                .collect(),
            ..Default::default()
        });
        result.publication = PublicationState::Published;
        let gate = result.gate;
        let encoded = render(&req, &mut result).unwrap();
        assert!(encoded.len() <= 2048);
        assert_eq!(result.publication, PublicationState::Published);
        assert_eq!(result.gate, gate);
        assert_eq!(result.output.records.len(), 40);
        assert_eq!(result.exit_code, 3);
        assert!(result.output.delivery.strict_rejected);
        assert_eq!(result.output.delivery.state, DeliveryState::Partial);
    }
}
