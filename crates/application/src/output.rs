//! Budget the text projection without discarding persisted task evidence.
use anyhow::{Result, ensure};
use codeana_contracts::{terminal, v1::Terminal};
use codeana_model::{Request, TaskResult};

const HEADER: &[u8] = b"codeana-text-v1\n";
const DETAILS: &str = "terminal_evidence_details";

fn projected(original: &Terminal, reasons: usize, limits: usize) -> Result<Vec<u8>> {
    let mut value = original.clone();
    if let (Some(full), Some(evidence)) = (&original.evidence, &mut value.evidence) {
        evidence.reasons = full.reasons[..reasons].to_vec();
        evidence.limits = full.limits[..limits].to_vec();
        if reasons < full.reasons.len() || limits < full.limits.len() {
            if !evidence.limits.iter().any(|limit| limit == DETAILS) {
                evidence.limits.push(DETAILS.into());
            }
            if reasons < full.reasons.len() {
                evidence.reasons.push(format!(
                    "terminal_reasons_omitted:{}",
                    full.reasons.len() - reasons
                ));
            }
            if limits < full.limits.len() {
                evidence.reasons.push(format!(
                    "terminal_limits_omitted:{}",
                    full.limits.len() - limits
                ));
            }
        }
    }
    Ok(format!("terminal {}\n", serde_json::to_string(&value)?).into_bytes())
}

fn terminal_line(result: &TaskResult, budget: usize) -> Result<(Vec<u8>, bool)> {
    let value = terminal(result);
    let full = format!("terminal {}\n", serde_json::to_string(&value)?).into_bytes();
    if full.len() <= budget {
        return Ok((full, false));
    }
    let evidence = value.evidence.as_ref();
    let (reasons, limits) = evidence.map_or((0, 0), |e| (e.reasons.len(), e.limits.len()));
    ensure!(
        projected(&value, 0, 0)?.len() <= budget,
        codeana_model::DomainError::new(codeana_model::ErrorCode::TerminalOutputBudget, "")
    );
    // Keep complete strings. JSON escaping and UTF-8 bytes are measured, not estimated.
    let mut kept_limits = 0;
    let mut high = limits;
    while kept_limits < high {
        let middle = kept_limits + (high - kept_limits).div_ceil(2);
        if projected(&value, 0, middle)?.len() <= budget {
            kept_limits = middle;
        } else {
            high = middle - 1;
        }
    }
    let mut kept_reasons = 0;
    high = reasons;
    while kept_reasons < high {
        let middle = kept_reasons + (high - kept_reasons).div_ceil(2);
        if projected(&value, middle, kept_limits)?.len() <= budget {
            kept_reasons = middle;
        } else {
            high = middle - 1;
        }
    }
    Ok((projected(&value, kept_reasons, kept_limits)?, true))
}

fn limited_records(result: &mut TaskResult, request: &Request) {
    result.output.evidence.enumeration = false;
    if !result
        .output
        .evidence
        .limits
        .iter()
        .any(|limit| limit == "output_bytes")
    {
        result.output.evidence.limits.push("output_bytes".into());
    }
    if request.strict && result.exit_code == 0 {
        result.exit_code = 3;
        result.gate = "rejected".into();
    }
}

pub(crate) fn render(request: &Request, result: &mut TaskResult) -> Result<Vec<u8>> {
    let limit = if request.max_output_bytes == 0 {
        32768
    } else {
        request.max_output_bytes as usize
    };
    ensure!(
        limit >= 2048,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::OutputBudgetTooSmall,
            "minimum 2048".to_string()
        )
    );
    let available = limit - HEADER.len();
    let (initial, _) =
        terminal_line(result, available.min(8192)).or_else(|_| terminal_line(result, available))?;
    let mut bytes = HEADER.to_vec();
    let mut ends = Vec::new();
    for record in &result.output.records {
        let line = format!("record {}\n", serde_json::to_string(&record.wire()?)?);
        if bytes.len() + line.len() + initial.len() > limit {
            break;
        }
        bytes.extend_from_slice(line.as_bytes());
        ends.push(bytes.len());
    }
    if ends.len() < result.output.records.len() {
        limited_records(result, request);
    }
    loop {
        match terminal_line(result, limit - bytes.len()) {
            Ok((line, compact)) => {
                if compact && request.strict && result.exit_code == 0 {
                    result.exit_code = 3;
                    result.gate = "rejected".into();
                    if !result
                        .output
                        .evidence
                        .limits
                        .iter()
                        .any(|limit| limit == DETAILS)
                    {
                        result.output.evidence.limits.push(DETAILS.into());
                    }
                    continue;
                }
                bytes.extend(line);
                return Ok(bytes);
            }
            Err(error) => {
                if ends.pop().is_none() {
                    return Err(error);
                }
                bytes.truncate(ends.last().copied().unwrap_or(HEADER.len()));
                limited_records(result, request);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::Record;
    use codeana_model::{Evidence, Output};
    use serde_json::json;

    fn decode(bytes: &[u8]) -> (Vec<serde_json::Value>, Terminal) {
        let text = std::str::from_utf8(bytes).unwrap();
        let mut lines = text.lines();
        assert_eq!(lines.next(), Some("codeana-text-v1"));
        let mut records = Vec::new();
        let mut terminal = None;
        for line in lines {
            if let Some(value) = line.strip_prefix("record ") {
                records.push(serde_json::from_str(value).unwrap());
            } else {
                assert!(terminal.is_none());
                terminal =
                    Some(serde_json::from_str(line.strip_prefix("terminal ").unwrap()).unwrap());
            }
        }
        (records, terminal.unwrap())
    }

    fn published() -> TaskResult {
        let mut result = TaskResult::success(Output {
            revision: "rev-real".into(),
            producer: "javac-java8-v1".into(),
            records: vec![
                Record::display(&json!({"files": 2857, "revision": "rev-real"})).unwrap(),
            ],
            evidence: Evidence {
                enumeration: true,
                semantic: false,
                limits: vec![],
                reasons: (0..1000)
                    .map(|n| {
                        format!(
                            "coverage:src/中文😀/\"file\\{n}\"/{}.java:partial",
                            "long-path/".repeat(15)
                        )
                    })
                    .collect(),
            },
            ..Default::default()
        });
        result.publication = "published".into();
        result
    }

    #[test]
    fn large_published_evidence_fits_text_and_retains_complete_task_details() {
        for limit in [2048, 32768, 8 * 1024 * 1024] {
            let mut result = published();
            let original = result.output.evidence.clone();
            let bytes = render(
                &Request {
                    max_output_bytes: limit,
                    ..Default::default()
                },
                &mut result,
            )
            .unwrap();
            assert!(bytes.len() <= limit as usize);
            let (records, terminal) = decode(&bytes);
            assert_eq!(
                records,
                result
                    .output
                    .records
                    .iter()
                    .map(|r| r.wire().unwrap())
                    .collect::<Vec<_>>()
            );
            assert_eq!(terminal.publication, "published");
            assert_eq!(terminal.revision, "rev-real");
            assert_eq!(terminal.exit_code, 0);
            let evidence = terminal.evidence.unwrap();
            assert!(evidence.enumeration);
            assert!(!evidence.semantic);
            assert_eq!(result.output.evidence, original);
            if limit < 8 * 1024 * 1024 {
                assert!(evidence.limits.contains(&DETAILS.into()));
                let count = evidence
                    .reasons
                    .iter()
                    .find_map(|reason| reason.strip_prefix("terminal_reasons_omitted:"))
                    .unwrap()
                    .parse::<usize>()
                    .unwrap();
                assert_eq!(evidence.reasons.len() - 1 + count, original.reasons.len());
                assert!(
                    evidence.reasons[..evidence.reasons.len() - 1]
                        .iter()
                        .all(|reason| original.reasons.contains(reason))
                );
            } else {
                assert_eq!(evidence.reasons, original.reasons);
            }
        }
    }

    #[test]
    fn strict_detail_omission_rejects_without_revoking_publication_or_losing_reasons() {
        let mut result = published();
        let bytes = render(
            &Request {
                strict: true,
                max_output_bytes: 2048,
                ..Default::default()
            },
            &mut result,
        )
        .unwrap();
        let (_, terminal) = decode(&bytes);
        assert_eq!(terminal.exit_code, 3);
        assert_eq!(terminal.gate, "rejected");
        assert_eq!(terminal.publication, "published");
        assert_eq!(result.output.evidence.reasons.len(), 1000);
    }

    #[test]
    fn record_truncation_is_explicit_and_does_not_replace_existing_failure_codes() {
        for strict in [false, true] {
            let mut result = TaskResult::success(Output {
                records: (0..100)
                    .map(|n| Record::display(&json!({"n": n, "body": "字😀".repeat(100)})).unwrap())
                    .collect(),
                evidence: Evidence::complete(),
                ..Default::default()
            });
            result.code = "CANCELLED".into();
            result.exit_code = 130;
            let bytes = render(
                &Request {
                    strict,
                    max_output_bytes: 2048,
                    ..Default::default()
                },
                &mut result,
            )
            .unwrap();
            assert!(bytes.len() <= 2048);
            let (records, terminal) = decode(&bytes);
            assert!(!records.is_empty() && records.len() < 100);
            assert_eq!(terminal.exit_code, 130);
            assert_eq!(terminal.code, "CANCELLED");
            let evidence = terminal.evidence.unwrap();
            assert!(!evidence.enumeration);
            assert!(evidence.semantic);
            assert!(evidence.limits.contains(&"output_bytes".into()));
        }
    }

    #[test]
    fn small_complete_output_keeps_exact_protocol_and_strict_success() {
        let request = Request {
            strict: true,
            ..Default::default()
        };
        let mut result = TaskResult::success(Output {
            records: vec![Record::display(&json!({"n":1})).unwrap()],
            evidence: Evidence::complete(),
            ..Default::default()
        });
        let expected = format!(
            "codeana-text-v1\nrecord {{\"n\":1}}\nterminal {}\n",
            serde_json::to_string(&terminal(&result)).unwrap()
        );
        assert_eq!(render(&request, &mut result).unwrap(), expected.as_bytes());
        assert_eq!(result.exit_code, 0);
    }

    #[test]
    fn strict_does_not_reject_a_provisional_sample_when_complete_terminal_fits() {
        let mut result = published();
        result.output.evidence.reasons.truncate(70);
        let expected = result.output.evidence.clone();
        assert!(serde_json::to_vec(&terminal(&result)).unwrap().len() > 8192);
        let bytes = render(
            &Request {
                strict: true,
                ..Default::default()
            },
            &mut result,
        )
        .unwrap();
        let (_, terminal) = decode(&bytes);
        assert_eq!(terminal.exit_code, 0);
        assert_eq!(terminal.evidence.unwrap().reasons, expected.reasons);
        assert_eq!(result.output.evidence, expected);
    }
}
