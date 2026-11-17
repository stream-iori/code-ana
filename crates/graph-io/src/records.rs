//! Host-assigned provenance; never decoded from worker-controlled graph bytes.
use crate::archive::{GraphArchive, Records};
use crate::graph::Row;
use anyhow::{Result, ensure};
use codeana_contracts::graph_delivery::OutputLimits;
use codeana_model::AnalysisUnitId;
use codeana_model::{AnalysisContext, DomainError, ErrorCode};
use prost::Message;
use std::sync::Arc;

/// Include in semantic profiles when changing host publication/provenance policy.
pub const POLICY_VERSION: u32 = 1;

pub fn validate_host_row(row: &Row) -> Result<()> {
    codeana_contracts::validate_graph_row(row)?;
    ensure!(
        row.encoded_len() <= crate::archive::RECORD_LIMIT,
        DomainError::new(ErrorCode::GraphRecordBudget, "")
    );
    ensure!(
        matches!(
            crate::graph::Table::try_from(row.table),
            Ok(crate::graph::Table::ProjectMeta | crate::graph::Table::Coverage)
        ),
        DomainError::new(
            ErrorCode::AnalysisUnitScope,
            "host cannot deliver semantic records"
        )
    );
    Ok(())
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Origin {
    Unit(Arc<AnalysisUnitId>),
    /// Availability coverage and preparation notes emitted by the host.
    Host,
    /// Explicit fixture APIs, including the development mock worker.
    Fixture,
}
#[derive(Debug)]
pub struct Record {
    pub origin: Origin,
    pub row: Row,
}
impl Origin {
    pub fn record(&self, row: Row) -> Record {
        Record {
            origin: self.clone(),
            row,
        }
    }
}

pub struct ArchiveInput {
    pub origin: Origin,
    pub archive: GraphArchive,
    pub budget: Option<AnalysisContext>,
}

/// One open file at a time; each original archive must pass its own EOF/SHA.
/// This never creates a replacement archive or a replacement checksum.
pub struct Archives {
    pending: std::vec::IntoIter<ArchiveInput>,
    current: Option<(Origin, Records)>,
    additional: std::vec::IntoIter<Row>,
    failed: bool,
}
impl Archives {
    pub fn new(
        inputs: Vec<ArchiveInput>,
        additional: Vec<Row>,
        limits: OutputLimits,
    ) -> Result<Self> {
        let mut count = additional.len() as u64;
        // Preserve the old combined-output ceiling: one magic, then all frames.
        let mut bytes = 4u64;
        for input in &inputs {
            ensure!(
                input.archive.bytes >= 4,
                DomainError::new(ErrorCode::GraphArchiveSize, "")
            );
            count = count.saturating_add(input.archive.count);
            bytes = bytes.saturating_add(input.archive.bytes - 4);
        }
        for row in &additional {
            validate_host_row(row)?;
            bytes = bytes
                .saturating_add(row.encoded_len() as u64)
                .saturating_add(4);
        }
        ensure!(
            count <= limits.facts && bytes <= limits.bytes,
            DomainError::new(ErrorCode::GraphArchiveBudget, "")
        );
        Ok(Self {
            pending: inputs.into_iter(),
            current: None,
            additional: additional.into_iter(),
            failed: false,
        })
    }
}
impl Iterator for Archives {
    type Item = Result<Record>;
    fn next(&mut self) -> Option<Self::Item> {
        if self.failed {
            return None;
        }
        loop {
            if let Some((origin, records)) = &mut self.current {
                if let Some(row) = records.next() {
                    return Some(match row {
                        Ok(row) => Ok(origin.record(row)),
                        Err(error) => {
                            self.failed = true;
                            Err(error)
                        }
                    });
                }
                self.current = None;
            }
            match self.pending.next() {
                Some(input) => match input.archive.records_with_budget(input.budget) {
                    Ok(records) => self.current = Some((input.origin, records)),
                    Err(error) => {
                        self.failed = true;
                        return Some(Err(error));
                    }
                },
                None => {
                    return self
                        .additional
                        .next()
                        .map(|row| Ok(Origin::Host.record(row)));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        archive::Builder,
        graph::{Cell, Table, cell},
    };
    fn row(value: &str) -> Row {
        Row {
            table: Table::ProjectMeta as i32,
            world: String::new(),
            cells: vec![
                Cell {
                    value: Some(cell::Value::Text("test".into())),
                },
                Cell {
                    value: Some(cell::Value::Text(value.into())),
                },
            ],
        }
    }
    fn input(id: &str) -> Result<ArchiveInput> {
        let mut builder = Builder::new(None, 4096, 2)?;
        builder.push(&row(id))?;
        Ok(ArchiveInput {
            origin: Origin::Unit(Arc::new(id.into())),
            archive: builder.seal()?,
            budget: None,
        })
    }
    fn limits() -> OutputLimits {
        OutputLimits {
            bytes: 16384,
            facts: 8,
        }
    }
    #[test]
    fn original_archives_keep_origins_and_lifetimes_until_each_verified_eof() -> Result<()> {
        let first = input("first")?;
        let second = input("second")?;
        let paths = [
            first.archive.path().to_path_buf(),
            second.archive.path().to_path_buf(),
        ];
        let mut records = Archives::new(vec![first, second], vec![row("host")], limits())?;
        assert_eq!(
            records.next().unwrap()?.origin,
            Origin::Unit(Arc::new("first".into()))
        );
        assert!(paths.iter().all(|p| p.exists()));
        assert_eq!(
            records.next().unwrap()?.origin,
            Origin::Unit(Arc::new("second".into()))
        );
        assert!(!paths[0].exists() && paths[1].exists());
        assert_eq!(records.next().unwrap()?.origin, Origin::Host);
        assert!(!paths[1].exists());
        assert!(records.next().is_none());
        Ok(())
    }
    #[test]
    fn late_original_sha_failure_never_delivers_a_later_unit_or_host_tail() -> Result<()> {
        let mut first = input("first")?;
        first.archive.checksum = "0".repeat(64);
        let second = input("second")?;
        let path = second.archive.path().to_path_buf();
        let mut records = Archives::new(vec![first, second], vec![row("host")], limits())?;
        assert!(records.next().unwrap().is_ok());
        assert!(records.next().unwrap().is_err());
        assert!(records.next().is_none());
        assert!(path.exists());
        drop(records);
        assert!(!path.exists());
        Ok(())
    }
    #[test]
    fn combined_output_limits_include_the_host_tail_without_a_second_spool() -> Result<()> {
        let first = input("first")?;
        let bytes = first.archive.bytes;
        assert!(
            Archives::new(
                vec![first],
                vec![row("tail")],
                OutputLimits { bytes, facts: 2 }
            )
            .is_err()
        );
        assert!(
            Archives::new(
                vec![input("first")?, input("second")?],
                vec![],
                OutputLimits {
                    bytes: 16384,
                    facts: 1
                }
            )
            .is_err()
        );
        assert!(
            Archives::new(
                vec![input("first")?],
                vec![],
                OutputLimits { bytes, facts: 1 }
            )?
            .collect::<Result<Vec<_>>>()
            .is_ok()
        );
        Ok(())
    }
    #[test]
    fn readers_keep_the_owned_directory_and_admit_decode_to_the_original_account() -> Result<()> {
        let archive = input("first")?.archive;
        let path = archive.path().to_path_buf();
        let budget = AnalysisContext::new(codeana_model::AnalysisLimits::default(), None)?;
        let mut records = archive.records_with_budget(Some(budget.clone()))?;
        drop(archive);
        assert!(path.exists());
        let before = budget.usage();
        assert!(before.payload_bytes >= 8192);
        assert!(records.next().unwrap().is_ok());
        assert!(budget.usage().payload_bytes > before.payload_bytes);
        drop(records);
        assert!(!path.exists());
        let archive = input("limited")?.archive;
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )?;
        assert!(archive.records_with_budget(Some(budget.clone())).is_err());
        assert!(budget.usage().failure.is_some());
        assert!(archive.path().exists());
        Ok(())
    }
    #[test]
    fn forged_size_and_count_fail_instead_of_continuing_into_the_next_unit() -> Result<()> {
        let mut first = input("first")?;
        first.archive.bytes -= 1;
        let mut records = Archives::new(vec![first, input("second")?], vec![], limits())?;
        assert!(records.next().unwrap().is_err());
        assert!(records.next().is_none());
        let mut first = input("first")?;
        first.archive.count = 0;
        let mut records = Archives::new(vec![first, input("second")?], vec![], limits())?;
        assert!(records.next().unwrap().is_err());
        assert!(records.next().is_none());
        Ok(())
    }
    #[test]
    fn host_rows_keep_wire_admission_after_removing_the_combined_spool() {
        let mut invalid = row("host");
        invalid.cells.resize(35, Cell::default());
        assert!(Archives::new(vec![], vec![invalid], limits()).is_err());
        let mut invalid = row("host");
        invalid.world = "w".repeat(257);
        assert!(validate_host_row(&invalid).is_err());
        let oversized = row(&"x".repeat(crate::archive::RECORD_LIMIT));
        assert!(
            Archives::new(
                vec![],
                vec![oversized],
                OutputLimits {
                    bytes: 1 << 30,
                    facts: 1
                }
            )
            .is_err()
        );
        let mut semantic = row("host");
        semantic.table = Table::Node as i32;
        assert!(validate_host_row(&semantic).is_err());
    }
}
