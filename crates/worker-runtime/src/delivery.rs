//! Host-side graph response state: envelope identity and phase complement graph-io's byte proof.
use anyhow::{Result, bail, ensure};
use codeana_contracts::{graph_delivery::OutputLimits, worker as w};
use codeana_graph_io::delivery::{Projection, VerifiedEnd, Verifier};
use codeana_graph_io::scope::Scope;
use codeana_model::{AnalysisContext, AnalysisHeader, DomainError, ErrorCode, Manifest};
pub struct RequestBinding<'a> {
    pub manifest: &'a Manifest,
    pub profile: &'a str,
    pub baseline: &'a str,
    pub producer: String,
    pub full: bool,
    pub scope: Scope<'a>,
}
#[derive(PartialEq, Eq)]
enum Phase {
    Begin,
    Rows,
    Ended,
    Failed,
}
pub struct Receiver<'a> {
    manifest: &'a Manifest,
    profile: &'a str,
    baseline: &'a str,
    producer: String,
    full: bool,
    phase: Phase,
    verifier: Option<Verifier>,
    projection: Option<Projection>,
    budget: Option<AnalysisContext>,
    scope: Scope<'a>,
}
impl<'a> Receiver<'a> {
    pub fn new(
        binding: RequestBinding<'a>,
        limits: OutputLimits,
        budget: Option<AnalysisContext>,
    ) -> Self {
        Self {
            manifest: binding.manifest,
            profile: binding.profile,
            baseline: binding.baseline,
            producer: binding.producer,
            full: binding.full,
            scope: binding.scope,
            phase: Phase::Begin,
            verifier: Some(Verifier::new(limits, budget.clone())),
            projection: Some(Projection::new(limits, budget.clone())),
            budget,
        }
    }
    pub fn begin(&mut self, begin: w::Begin) -> Result<AnalysisHeader> {
        let result = (|| {
            ensure!(self.phase == Phase::Begin, "DUPLICATE_RESULT_BEGIN");
            ensure!(
                begin.manifest == self.manifest.id()
                    && begin.profile == self.profile
                    && begin.baseline == self.baseline
                    && begin.producer == self.producer,
                DomainError::new(ErrorCode::ResultIdentityMismatch, "")
            );
            ensure!(
                !self.full || begin.mode == "full",
                DomainError::new(ErrorCode::ExpectedFullResult, "")
            );
            let header = AnalysisHeader {
                mode: begin.mode.parse()?,
                baseline: begin.baseline,
                manifest: begin.manifest,
                profile: begin.profile,
                producer: begin.producer,
                replacements: begin
                    .replacements
                    .iter()
                    .map(codeana_contracts::map)
                    .collect::<Result<_>>()?,
            };
            header.validate(self.manifest).map_err(anyhow::Error::msg)?;
            for owner in &header.replacements {
                self.scope.owner(owner, &self.producer)?;
            }
            Ok(header)
        })();
        self.phase = if result.is_ok() {
            Phase::Rows
        } else {
            Phase::Failed
        };
        result
    }
    pub fn chunk(&mut self, mut chunk: w::Chunk) -> Result<Vec<codeana_contracts::graph::Row>> {
        let result = (|| {
            ensure!(self.phase == Phase::Rows, "GRAPH_DELIVERY_STATE");
            ensure!(
                !chunk.rows.is_empty() && chunk.rows.len() <= 64,
                DomainError::new(ErrorCode::FactChunkBudget, "")
            );
            let verifier = self
                .verifier
                .as_mut()
                .ok_or_else(|| anyhow::anyhow!("GRAPH_DELIVERY_CLOSED"))?;
            ensure!(
                chunk.seq == verifier.chunks(),
                DomainError::new(ErrorCode::ChunkSequenceMismatch, "")
            );
            let projection = self
                .projection
                .as_mut()
                .ok_or_else(|| anyhow::anyhow!("GRAPH_DELIVERY_CLOSED"))?;
            for row in &mut chunk.rows {
                let world = self.scope.source_world(row)?;
                verifier.push(row)?;
                if row.world.is_empty()
                    && let Some(world) = world
                {
                    if let Some(budget) = &self.budget {
                        budget
                            .charge(1, 0, 64 + 3 * world.len() as u64)
                            .map_err(DomainError::analysis)?;
                    }
                    row.world = world.into();
                }
                projection.push(row)?;
            }
            verifier.record_chunk();
            projection.record_chunk();
            Ok(chunk.rows)
        })();
        if result.is_err() {
            self.phase = Phase::Failed;
        }
        result
    }
    pub fn end(&mut self, end: &w::End) -> Result<VerifiedEnd> {
        if self.phase != Phase::Rows {
            self.phase = Phase::Failed;
            bail!("GRAPH_DELIVERY_STATE");
        }
        let result = (|| {
            let source = self
                .verifier
                .take()
                .ok_or_else(|| anyhow::anyhow!("GRAPH_DELIVERY_CLOSED"))?
                .verify_end(end.chunks, end.count, &end.checksum)?;
            self.projection
                .take()
                .ok_or_else(|| anyhow::anyhow!("GRAPH_DELIVERY_CLOSED"))?
                .finish(&source)
        })();
        self.phase = if result.is_ok() {
            Phase::Ended
        } else {
            Phase::Failed
        };
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn begin(manifest: &Manifest) -> w::Begin {
        w::Begin {
            mode: "full".into(),
            baseline: String::new(),
            manifest: manifest.id(),
            profile: "profile".into(),
            producer: "test".into(),
            replacements: vec![],
        }
    }
    fn binding(manifest: &Manifest) -> RequestBinding<'_> {
        RequestBinding {
            manifest,
            profile: "profile",
            baseline: "",
            producer: "test".into(),
            full: true,
            scope: Scope::new(
                "unit",
                manifest,
                &[],
                &[],
                &[codeana_model::SourceLanguage::Java],
                codeana_graph_io::scope::Mode::Compiler,
                None,
            )
            .unwrap(),
        }
    }
    fn chunk(seq: u64) -> w::Chunk {
        w::Chunk {
            seq,
            rows: vec![codeana_contracts::graph::Row {
                table: 14,
                world: String::new(),
                cells: ["k", "v"]
                    .iter()
                    .map(|v| codeana_contracts::graph::Cell {
                        value: Some(codeana_contracts::graph::cell::Value::Text((*v).into())),
                    })
                    .collect(),
            }],
        }
    }
    fn end() -> w::End {
        w::End {
            chunks: 1,
            count: 1,
            checksum: codeana_model::hash([
                71, 82, 65, 49, 0, 0, 0, 12, 8, 14, 18, 3, 10, 1, b'k', 18, 3, 10, 1, b'v',
            ]),
            analysis_usage: None,
        }
    }
    #[test]
    fn implicit_world_projection_keeps_the_original_sha_check_and_certifies_its_own_bytes() {
        use codeana_contracts::graph_rows::{GraphRow, NodeRow};
        use codeana_graph_io::archive::Builder;
        for world in ["context-a", "context-b"] {
            let manifest = Manifest {
                files: vec![codeana_model::SourceUnit {
                    path: "A.java".into(),
                    hash: codeana_model::hash("source"),
                    size: 6,
                    module: "app".into(),
                    scope: "MAIN".into(),
                    language: "java".into(),
                    world: world.into(),
                    auxiliary: false,
                }],
            };
            let row = NodeRow {
                id: Some("node".into()),
                source_file: Some("A.java".into()),
                module: Some("app".into()),
                scope: Some("MAIN".into()),
                language: Some("java".into()),
                ..Default::default()
            }
            .into_row("");
            let mut original = Builder::new(None, 1 << 20, 10).unwrap();
            original.push(&row).unwrap();
            let original = original.seal().unwrap();
            let end = w::End {
                chunks: 1,
                count: 1,
                checksum: original.checksum.clone(),
                analysis_usage: None,
            };
            let mut receiver = Receiver::new(binding(&manifest), OutputLimits::default(), None);
            receiver.begin(begin(&manifest)).unwrap();
            let projected = receiver
                .chunk(w::Chunk {
                    seq: 0,
                    rows: vec![row.clone()],
                })
                .unwrap();
            assert_eq!(projected[0].world, world);
            let mut archive = Builder::new(None, 1 << 20, 10).unwrap();
            archive.push(&projected[0]).unwrap();
            let archive = archive.seal().unwrap();
            assert_ne!(archive.checksum, original.checksum);
            let proof = receiver.end(&end).unwrap();
            assert_eq!(proof.checksum(), archive.checksum);
            assert_eq!(
                original.records().unwrap().next().unwrap().unwrap().world,
                ""
            );
            let mut receiver = Receiver::new(binding(&manifest), OutputLimits::default(), None);
            receiver.begin(begin(&manifest)).unwrap();
            receiver
                .chunk(w::Chunk {
                    seq: 0,
                    rows: vec![row.clone()],
                })
                .unwrap();
            let mut wrong = end.clone();
            wrong.checksum = archive.checksum;
            assert!(receiver.end(&wrong).is_err());
            assert!(receiver.end(&end).is_err());
            let mut limited = Receiver::new(
                binding(&manifest),
                OutputLimits {
                    bytes: original.bytes,
                    facts: 10,
                },
                None,
            );
            limited.begin(begin(&manifest)).unwrap();
            assert!(
                limited
                    .chunk(w::Chunk {
                        seq: 0,
                        rows: vec![row]
                    })
                    .is_err()
            );
            assert!(limited.end(&end).is_err());
        }
    }
    #[test]
    fn ignored_scope_failures_cannot_produce_a_verified_end() {
        let manifest = Manifest::default();
        for owner_fault in [true, false] {
            let mut receiver = Receiver::new(binding(&manifest), OutputLimits::default(), None);
            if owner_fault {
                let mut header = begin(&manifest);
                header.replacements.push(w::Owner {
                    world: "foreign".into(),
                    producer: "test".into(),
                    kind: "source".into(),
                    id: "foreign.java".into(),
                });
                assert!(receiver.begin(header).is_err());
            } else {
                receiver.begin(begin(&manifest)).unwrap();
                let row = codeana_contracts::graph_rows::NodeRow {
                    source_file: Some("foreign.java".into()),
                    language: Some("java".into()),
                    ..Default::default()
                };
                use codeana_contracts::graph_rows::GraphRow;
                let error = receiver
                    .chunk(w::Chunk {
                        seq: 0,
                        rows: vec![row.into_row("foreign")],
                    })
                    .unwrap_err();
                assert_eq!(
                    error.downcast_ref::<DomainError>().unwrap().code,
                    ErrorCode::AnalysisUnitScope
                );
            }
            assert!(receiver.chunk(chunk(0)).is_err());
            assert!(receiver.end(&end()).is_err());
        }
    }
    #[test]
    fn complete_graph_has_one_proof_and_rejects_repeated_begin_chunk_or_end() {
        let manifest = Manifest::default();
        let mut receiver = Receiver::new(binding(&manifest), OutputLimits::default(), None);
        receiver.begin(begin(&manifest)).unwrap();
        assert_eq!(receiver.chunk(chunk(0)).unwrap().len(), 1);
        let proof = receiver.end(&end()).unwrap();
        assert_eq!(proof.count(), 1);
        assert!(receiver.end(&end()).is_err());
        assert!(receiver.chunk(chunk(1)).is_err());
    }
    #[test]
    fn ignored_header_sequence_or_checksum_errors_never_recover_into_a_successful_end() {
        let manifest = Manifest::default();
        for fault in 0..4 {
            let mut receiver = Receiver::new(binding(&manifest), OutputLimits::default(), None);
            if fault == 0 {
                let mut wrong = begin(&manifest);
                wrong.producer = "wrong".into();
                assert!(receiver.begin(wrong).is_err());
                assert!(receiver.begin(begin(&manifest)).is_err());
            } else {
                receiver.begin(begin(&manifest)).unwrap();
                match fault {
                    1 => assert!(receiver.begin(begin(&manifest)).is_err()),
                    2 => assert!(receiver.chunk(chunk(1)).is_err()),
                    _ => {
                        receiver.chunk(chunk(0)).unwrap();
                        let mut wrong = end();
                        wrong.checksum = "wrong".into();
                        assert!(receiver.end(&wrong).is_err());
                    }
                }
            }
            assert!(receiver.end(&end()).is_err());
        }
    }
}
