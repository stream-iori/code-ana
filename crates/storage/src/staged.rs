use super::*;
#[path = "graph_candidate.rs"]
mod graph_candidate;

pub struct StagedCandidate {
    directory: CandidateDirectory,
    revision: Revision,
    expected: String,
    _baseline: Option<ReadContext>,
    pub summary: StagedSummary,
}
struct CandidateDirectory {
    directory: tempfile::TempDir,
    _pin: CandidatePin,
}
impl CandidateDirectory {
    fn path(&self) -> &Path {
        self.directory.path()
    }
}
struct CandidatePin {
    id: String,
    coord: Arc<Mutex<Coordination>>,
}
impl Drop for CandidatePin {
    fn drop(&mut self) {
        self.coord
            .lock()
            .unwrap()
            .staged_candidates
            .remove(&self.id);
    }
}
#[derive(Clone, Default)]
pub struct StagedSummary {
    pub statistics: CandidateStatistics,
    /// Task-local diagnostics; never part of Fact IR or the worker protocol.
    pub phase_ms: BTreeMap<String, u64>,
    pub fact_count: u64,
    pub counts: BTreeMap<String, u64>,
    pub diagnostic_total: u64,
    pub diagnostic_rows: u64,
    pub diagnostic_sampled_occurrences: u64,
    pub diagnostic_groups: BTreeMap<String, u64>,
    pub diagnostic_groups_omitted: u64,
    pub diagnostic_samples: Vec<Fact>,
    pub evidence: Evidence,
}
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub struct CandidateStatistics {
    pub analyzed_indexes: u64,
    pub elapsed_ms: u64,
    pub warnings: Vec<String>,
}
fn prepare_statistics(
    db: &Connection,
    check: &dyn Fn() -> Result<()>,
) -> Result<CandidateStatistics> {
    let started = std::time::Instant::now();
    let mut statement = db.prepare("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name NOT LIKE 'sqlite_%' ORDER BY name")?;
    let indexes = statement
        .query_map([], |row| row.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(statement);
    let mut summary = CandidateStatistics::default();
    for index in indexes {
        check()?;
        let result = db.execute_batch(&format!("ANALYZE \"{}\"", index.replace('"', "\"\"")));
        check()?;
        match result {
            Ok(()) => summary.analyzed_indexes += 1,
            Err(rusqlite::Error::SqliteFailure(error, detail))
                if matches!(
                    error.code,
                    rusqlite::ErrorCode::SystemIoFailure
                        | rusqlite::ErrorCode::DiskFull
                        | rusqlite::ErrorCode::DatabaseCorrupt
                        | rusqlite::ErrorCode::NotADatabase
                        | rusqlite::ErrorCode::ReadOnly
                        | rusqlite::ErrorCode::OperationInterrupted
                ) =>
            {
                return Err(rusqlite::Error::SqliteFailure(error, detail).into());
            }
            Err(error) => summary
                .warnings
                .push(format!("STATISTICS_FAILED:{index}:{error}")),
        }
    }
    summary.elapsed_ms = started.elapsed().as_millis() as u64;
    Ok(summary)
}
impl Store {
    pub fn publish_staged(&self, candidate: StagedCandidate, task: &str) -> Result<Revision> {
        let mut lock = self.coord.lock().unwrap();
        let revision = &candidate.revision;
        ensure!(
            self.head(&revision.target)? == candidate.expected,
            codeana_model::DomainError::new(codeana_model::ErrorCode::HeadConflict, "")
        );
        use codeana_contracts::performance::{Metric, measure};
        measure(Metric::PublishBlobCheck, 0, false, || -> Result<()> {
            for source in &revision.manifest.files {
                self.blob(&source.hash)?;
            }
            Ok(())
        })?;
        measure(Metric::PublishFileSync, 0, false, || {
            std::fs::File::open(candidate.directory.path().join("facts.db"))?.sync_all()
        })?;
        durable_write(
            &candidate.directory.path().join("revision.json"),
            json(revision)?.as_bytes(),
        )?;
        sync_dir(candidate.directory.path())?;
        test_fault(&self.home, "candidate_synced")?;
        measure(Metric::PublishMove, 0, false, || {
            std::fs::rename(
                candidate.directory.path(),
                self.home.join("revisions").join(&revision.id),
            )
        })?;
        sync_dir(&self.home.join("revisions"))?;
        test_fault(&self.home, "revision_renamed")?;
        let mut catalog = self.catalog()?;
        let tx = catalog.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let actual: String = tx
            .query_row(
                "SELECT revision FROM heads WHERE target=?",
                [&revision.target],
                |r| r.get(0),
            )
            .optional()?
            .unwrap_or_default();
        ensure!(
            actual == candidate.expected,
            codeana_model::DomainError::new(codeana_model::ErrorCode::HeadConflict, "")
        );
        tx.execute(
            "INSERT INTO revisions VALUES(?,?,?,?)",
            params![
                revision.id,
                revision.target,
                revision.project,
                json(revision)?
            ],
        )?;
        tx.execute(
            "INSERT OR REPLACE INTO heads VALUES(?,?)",
            params![revision.target, revision.id],
        )?;
        tx.execute(
            "INSERT INTO commits VALUES(?,?)",
            params![task, revision.id],
        )?;
        measure(Metric::CatalogCommit, 0, false, || tx.commit())?;
        test_fault(&self.home, "catalog_committed")?;
        for source in &revision.manifest.files {
            lock.staged_blobs.remove(&source.hash);
        }
        let published = revision.clone();
        // Release the baseline reader outside the coordination mutex.
        drop(lock);
        drop(candidate);
        Ok(published)
    }
}

// The DISTINCT subquery is deliberately not flattened: validate each endpoint
// once using covering indexes, rather than probing the same ID for every facet.
