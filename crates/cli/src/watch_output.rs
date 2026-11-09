//! Bounded observation codec. Encode each event once; never duplicate it in records.
use anyhow::{Result, ensure};
use codeana_application::presentation;
use codeana_model::*;

pub struct WatchOutput {
    request: Request,
    task: String,
    after: u64,
    fragments: Vec<(Vec<u8>, bool, u64)>,
    bytes: usize,
    omitted: bool,
    phase: String,
}
impl WatchOutput {
    pub fn new(request: &Request, task: &str, after: u64) -> Self {
        Self {
            request: request.clone(),
            task: task.into(),
            after,
            fragments: vec![],
            bytes: 0,
            omitted: false,
            phase: String::new(),
        }
    }
    fn push(&mut self, bytes: Vec<u8>, record: bool, seq: u64) {
        let limit = self.request.max_output_bytes.max(2048) as usize;
        if self.omitted || self.bytes.saturating_add(bytes.len()) > limit {
            self.omitted = true;
            return;
        }
        self.bytes += bytes.len();
        self.fragments.push((bytes, record, seq));
    }
    pub fn progress(&mut self, seq: u64, phase: &str) -> Result<()> {
        if self.phase == phase {
            return Ok(());
        }
        self.phase = phase.into();
        let bytes = if self.request.delivery.format() == OutputFormat::Json {
            serde_json::to_vec(&serde_json::json!({"seq":seq,"type":"progress","phase":phase}))?
        } else {
            format!("event: {seq} progress {}\n", presentation::scalar(phase)).into_bytes()
        };
        self.push(bytes, false, seq);
        Ok(())
    }
    pub fn record(&mut self, seq: u64, record: Record) -> Result<()> {
        if self.omitted {
            return Ok(());
        }
        let bytes = if self.request.delivery.format() == OutputFormat::Json {
            serde_json::to_vec(
                &serde_json::json!({"seq":seq,"type":"record","record":presentation::public_operation_record(&self.request, &record)?}),
            )?
        } else {
            let mut bytes = format!("event: {seq} record\n").into_bytes();
            bytes.extend(presentation::record_text(&self.request, record)?);
            bytes
        };
        self.push(bytes, true, seq);
        Ok(())
    }
    pub fn finish(mut self, result: &mut TaskResult, last: u64) -> Result<Vec<u8>> {
        let limit = self.request.max_output_bytes.max(2048) as usize;
        if result.output.delivery.context.operation != Operation::Unspecified {
            self.request.operation = result.output.delivery.context.operation;
        }
        result.output.delivery.context.format = self.request.delivery.format();
        result.output.delivery.context.detail = self.request.delivery.detail();
        result.output.delivery.context.max_output_bytes = self.request.max_output_bytes;
        if self.after > 0 {
            result.output.delivery.range_known = false;
        }
        loop {
            result.output.records.clear();
            result.output.delivery.returned = self
                .fragments
                .iter()
                .filter(|(_, record, _)| *record)
                .count();
            result.output.delivery.projection_version = PUBLIC_PROJECTION_VERSION;
            let checkpoint = if self.omitted {
                self.fragments.last().map_or(self.after, |(_, _, seq)| *seq)
            } else {
                last
            };
            if result.output.delivery.state != DeliveryState::Failed {
                result.output.delivery.state = if self.omitted || self.after > 0 {
                    DeliveryState::Partial
                } else if !result.output.cursor.is_empty() {
                    DeliveryState::Page
                } else {
                    DeliveryState::Complete
                };
            }
            let mut footer_view = result.clone();
            if self.request.delivery.format() == OutputFormat::Text {
                footer_view.output.delivery.context.comparison = None;
            }
            let footer = presentation::encode(&self.request, &footer_view)?;
            let mut bytes = if self.request.delivery.format() == OutputFormat::Json {
                let mut metadata: serde_json::Value = serde_json::from_slice(&footer)?;
                let fields = metadata.as_object_mut().expect("receipt");
                fields.remove("records");
                fields.insert("task".into(), self.task.clone().into());
                fields.insert("after_seq".into(), self.after.into());
                fields.insert("last_seq".into(), checkpoint.into());
                fields.insert("observed_seq".into(), last.into());
                let mut bytes = b"{\"events\":[".to_vec();
                for (i, (fragment, _, _)) in self.fragments.iter().enumerate() {
                    if i > 0 {
                        bytes.push(b',');
                    }
                    bytes.extend(fragment);
                }
                bytes.extend(b"],");
                let tail = serde_json::to_vec(&metadata)?;
                bytes.extend(&tail[1..]);
                bytes.push(b'\n');
                bytes
            } else {
                let mut bytes = format!(
                    "task: {}\nafter-seq: {}\n",
                    presentation::scalar(&self.task),
                    self.after
                )
                .into_bytes();
                bytes.extend(
                    presentation::comparison_header(
                        result.output.delivery.context.comparison.as_ref(),
                    )
                    .as_bytes(),
                );
                for (fragment, _, _) in &self.fragments {
                    bytes.extend(fragment);
                }
                bytes.extend(format!("last-seq: {checkpoint}\nobserved-seq: {last}\n").as_bytes());
                bytes.extend(footer);
                bytes
            };
            if bytes.len() <= limit {
                return Ok(std::mem::take(&mut bytes));
            }
            ensure!(
                !self.fragments.is_empty(),
                DomainError::new(
                    ErrorCode::TerminalOutputBudget,
                    "watch metadata exceeds output budget"
                )
            );
            self.fragments.pop();
            self.omitted = true;
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn observation_budget_counts_header_events_and_footer_and_preserves_commit() {
        for format in [OutputFormat::Text, OutputFormat::Json] {
            let req = Request {
                max_output_bytes: 2048,
                delivery: DeliveryOptions {
                    format: Some(format),
                    ..Default::default()
                },
                ..Default::default()
            };
            let mut watch = WatchOutput::new(&req, "task", 2);
            for seq in 3..100 {
                watch
                    .record(
                        seq,
                        Record::display(&serde_json::json!({"body":"中😀".repeat(90)})).unwrap(),
                    )
                    .unwrap();
            }
            let mut result = TaskResult::success(Output {
                evidence: Evidence::complete(),
                ..Default::default()
            });
            result.publication = PublicationState::Published;
            let bytes = watch.finish(&mut result, 100).unwrap();
            assert!(bytes.len() <= 2048);
            assert_eq!(result.publication, PublicationState::Published);
            assert_eq!(result.output.delivery.state, DeliveryState::Partial);
            if format == OutputFormat::Json {
                let doc: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
                assert!(doc.get("records").is_none());
                assert!(doc["last_seq"].as_u64().unwrap() < 100);
                assert_eq!(doc["observed_seq"], 100);
            } else {
                assert!(bytes.ends_with(b"end\n"));
            }
        }
    }
}
