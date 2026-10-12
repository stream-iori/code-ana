use super::*;
use codeana_contracts::archive::{FactArchive, FactArchiveBuilder};

/// A Rust indexing policy supplies the meaning of derived relations. Storage
/// only provides private candidate reads/writes and atomic replacement.
pub trait CandidateDerivation {
    fn producer(&self) -> &str;
    fn aggregate_id(&self) -> &str;
    fn derive(&self, facts: &CandidateFacts<'_>) -> Result<()>;
}

pub struct CandidateFacts<'a> {
    db: &'a Connection,
    manifest: &'a Manifest,
    policy: &'a dyn CandidateDerivation,
    check: &'a dyn Fn() -> Result<()>,
    cancellation: &'a dyn Fn() -> Result<()>,
    writes: std::cell::Cell<usize>,
}

impl CandidateFacts<'_> {
    pub fn source_unit(&self, path: &str) -> Option<&SourceUnit> {
        self.manifest.files.iter().find(|s| s.path == path)
    }

    pub fn declarations(&self, source: &str, world: &str, signature: &str) -> Result<Vec<Fact>> {
        (self.cancellation)()?;
        let mut statement = self.db.prepare(
            "SELECT body FROM facts WHERE kind='declaration' AND source=? AND world=?
             AND json_extract(body,'$.signature')=? ORDER BY id",
        )?;
        let mut rows = statement.query(params![source, world, signature])?;
        let mut output = Vec::new();
        while let Some(row) = rows.next()? {
            (self.cancellation)()?;
            output.push(serde_json::from_str(&row.get::<_, String>(0)?)?);
        }
        Ok(output)
    }

    pub fn relations(&self, relation: &str) -> Result<Vec<Fact>> {
        let mut statement = self
            .db
            .prepare("SELECT body FROM facts WHERE kind='relation' AND relation=? ORDER BY id")?;
        let mut rows = statement.query([relation])?;
        let mut facts = Vec::new();
        while let Some(row) = rows.next()? {
            (self.cancellation)()?;
            facts.push(serde_json::from_str(&row.get::<_, String>(0)?)?);
        }
        Ok(facts)
    }

    /// Directed adapter references, distinct from relation records.
    pub fn references(&self, relation: &str, parent: Option<&str>) -> Result<Vec<Fact>> {
        // Keep directed reads indexable. An optional-parent OR makes SQLite
        // scan every reference even when the caller supplies one exact owner.
        let sql = if parent.is_some() {
            "SELECT body FROM facts WHERE kind='reference' AND relation=? AND parent=? ORDER BY id"
        } else {
            "SELECT body FROM facts WHERE kind='reference' AND relation=? ORDER BY id"
        };
        let mut statement = self.db.prepare(sql)?;
        let mut rows = match parent {
            Some(parent) => statement.query(params![relation, parent])?,
            None => statement.query([relation])?,
        };
        let mut facts = Vec::new();
        while let Some(row) = rows.next()? {
            (self.cancellation)()?;
            facts.push(serde_json::from_str(&row.get::<_, String>(0)?)?);
        }
        Ok(facts)
    }

    pub fn fact(&self, id: &str) -> Result<Option<Fact>> {
        (self.cancellation)()?;
        self.db
            .query_row("SELECT body FROM facts WHERE id=?", [id], |r| {
                r.get::<_, String>(0)
            })
            .optional()?
            .map(|body| serde_json::from_str(&body).map_err(Into::into))
            .transpose()
    }

    pub fn insert(&self, fact: &Fact) -> Result<()> {
        (self.cancellation)()?;
        let position = self.writes.get();
        if position.is_multiple_of(256) {
            (self.check)()?;
        }
        ensure!(
            fact.owner.producer == self.policy.producer()
                && fact.owner.kind == "aggregate"
                && fact.owner.id == self.policy.aggregate_id(),
            "DERIVED_OWNER_MISMATCH"
        );
        FactValidator::new(self.manifest)
            .validate(fact)
            .map_err(anyhow::Error::msg)?;
        let body = json(fact)?;
        ensure!(body.len() <= MAX_FRAME, "FACT_FRAME_BUDGET");
        self.db.execute(
            "INSERT INTO facts VALUES(?,?,?,?,?,?,?,?,?,?)",
            params![
                fact.id,
                fact.kind.name(),
                fact.owner.key(),
                fact.parent,
                fact.target,
                fact.relation,
                fact.name,
                fact.source,
                fact.owner.world,
                body
            ],
        )?;
        self.writes.set(position + 1);
        Ok(())
    }
}

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
#[derive(Clone, Default, serde::Serialize)]
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
impl ReadContext {
    pub fn export_archive(&self, max_bytes: u64, max_count: u64) -> Result<FactArchive> {
        self.export_archive_checked(max_bytes, max_count, || Ok(()))
    }
    pub fn export_archive_checked(
        &self,
        max_bytes: u64,
        max_count: u64,
        check: impl Fn() -> Result<()>,
    ) -> Result<FactArchive> {
        self.export_archive_checked_in(max_bytes, max_count, None, check)
    }
    pub fn export_archive_checked_in(
        &self,
        max_bytes: u64,
        max_count: u64,
        root: Option<&Path>,
        check: impl Fn() -> Result<()>,
    ) -> Result<FactArchive> {
        self.export_adapter_archive_checked_in(max_bytes, max_count, root, &[], check)
    }

    /// Product-derived aggregate facts are kept in revisions but are not
    /// returned to a language worker as adapter-owned baseline facts.
    pub fn export_adapter_archive_checked_in(
        &self,
        max_bytes: u64,
        max_count: u64,
        root: Option<&Path>,
        excluded_owners: &[Owner],
        check: impl Fn() -> Result<()>,
    ) -> Result<FactArchive> {
        check()?;
        let mut archive = match root {
            Some(path) => FactArchiveBuilder::new_in(max_bytes, max_count, path)?,
            None => FactArchiveBuilder::new(max_bytes, max_count)?,
        };
        let mut statement = self
            .connection
            .prepare("SELECT owner,body FROM facts ORDER BY id")?;
        let excluded: BTreeSet<_> = excluded_owners.iter().map(Owner::key).collect();
        let mut rows = statement.query([])?;
        let mut visited = 0u64;
        while let Some(row) = rows.next()? {
            if visited.is_multiple_of(256) {
                check()?;
            }
            visited += 1;
            if excluded.contains(&row.get::<_, String>(0)?) {
                continue;
            }
            let body: String = row.get(1)?;
            archive.push(&serde_json::from_str(&body)?)?;
        }
        archive.seal()
    }
}
impl Store {
    /// Prepare and validate the complete candidate before any publication decision.
    pub fn stage_archive(
        &self,
        revision: Revision,
        header: &AnalysisHeader,
        archive: &FactArchive,
        expected: &str,
    ) -> Result<StagedCandidate> {
        self.stage_archive_checked(revision, header, archive, expected, || Ok(()))
    }
    pub fn stage_archive_checked(
        &self,
        revision: Revision,
        header: &AnalysisHeader,
        archive: &FactArchive,
        expected: &str,
        check: impl Fn() -> Result<()>,
    ) -> Result<StagedCandidate> {
        self.stage_archive_with_scratch_check(
            revision,
            header,
            archive,
            expected,
            check,
            |_| Ok(()),
        )
    }
    pub fn stage_archive_with_scratch_check(
        &self,
        revision: Revision,
        header: &AnalysisHeader,
        archive: &FactArchive,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
    ) -> Result<StagedCandidate> {
        self.stage_archive_with_derivation(
            revision,
            header,
            archive,
            expected,
            cancellation,
            check_directory,
            None,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn stage_archive_with_derivation(
        &self,
        mut revision: Revision,
        header: &AnalysisHeader,
        archive: &FactArchive,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
        derivation: Option<&dyn CandidateDerivation>,
    ) -> Result<StagedCandidate> {
        cancellation()?;
        header
            .validate(&revision.manifest)
            .map_err(anyhow::Error::msg)?;
        ensure!(
            header.profile == revision.profile && header.producer == revision.producer,
            "RESULT_IDENTITY_MISMATCH"
        );
        ensure!(self.head(&revision.target)? == expected, "HEAD_CONFLICT");
        let baseline = if header.mode == "delta" {
            ensure!(header.baseline == expected, "BASELINE_MISMATCH");
            let reader = self.read(expected)?;
            ensure!(
                reader.revision.profile == revision.profile,
                "PROFILE_MISMATCH"
            );
            Some(reader)
        } else {
            None
        };
        revision.id = new_id("rev");
        revision.created = now();
        let directory = {
            let mut lock = self.coord.lock().unwrap();
            let directory = tempfile::Builder::new()
                .prefix(&format!("{}-", revision.id))
                .tempdir_in(self.home.join("candidates"))?;
            let id = directory
                .path()
                .file_name()
                .context("CANDIDATE_NAME")?
                .to_string_lossy()
                .into_owned();
            lock.staged_candidates.insert(id.clone());
            CandidateDirectory {
                directory,
                _pin: CandidatePin {
                    id,
                    coord: self.coord.clone(),
                },
            }
        };
        let check = || {
            cancellation()?;
            check_directory(directory.path())
        };
        check()?;
        let mut phase_ms = BTreeMap::new();
        let mut phase_started = std::time::Instant::now();
        let mut db = Connection::open(directory.path().join("facts.db"))?;
        if let Some(source) = &baseline {
            let backup = rusqlite::backup::Backup::new(&source.connection, &mut db)?;
            loop {
                check()?;
                match backup.step(128)? {
                    rusqlite::backup::StepResult::Done => break,
                    rusqlite::backup::StepResult::More => {}
                    rusqlite::backup::StepResult::Busy | rusqlite::backup::StepResult::Locked => {
                        std::thread::sleep(Duration::from_millis(1))
                    }
                    _ => anyhow::bail!("SQLITE_BACKUP_STEP"),
                }
            }
        }
        check()?;
        db.execute_batch("PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA cache_size=-65536; PRAGMA temp_store=FILE;")?;
        schema::initialize(&db)?;
        if baseline.is_none() {
            schema::begin_full_load(&db)?;
        }
        if let Some(policy) = derivation {
            let worlds: BTreeSet<_> = revision
                .manifest
                .files
                .iter()
                .chain(baseline.iter().flat_map(|r| &r.revision.manifest.files))
                .map(|s| s.world.as_str())
                .collect();
            for world in worlds {
                check()?;
                let owner = Owner {
                    world: world.into(),
                    producer: policy.producer().into(),
                    kind: "aggregate".into(),
                    id: policy.aggregate_id().into(),
                };
                db.execute("DELETE FROM facts WHERE owner=?", [owner.key()])?;
            }
        }
        let owners: BTreeSet<_> = header.replacements.iter().collect();
        if let Some(before) = &baseline {
            let after: BTreeMap<_, _> = revision
                .manifest
                .files
                .iter()
                .map(|s| (s.path.as_str(), s))
                .collect();
            let mut statement = db.prepare("SELECT DISTINCT owner FROM facts WHERE source=?")?;
            let replaced: BTreeSet<_> = owners.iter().map(|o| o.key()).collect();
            for source in &before.revision.manifest.files {
                check()?;
                if after.get(source.path.as_str()).copied() == Some(source) {
                    continue;
                }
                for owner in statement.query_map([&source.path], |r| r.get::<_, String>(0))? {
                    ensure!(
                        replaced.contains(&owner?),
                        "CHANGED_SOURCE_OWNER_NOT_REPLACED: {}",
                        source.path
                    );
                }
            }
        }
        phase_ms.insert(
            "candidate_setup_backup".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        let validator = FactValidator::new(&revision.manifest);
        let tx = db.transaction()?;
        tx.execute("DELETE FROM source_units", [])?;
        {
            let mut insert_source = tx.prepare("INSERT INTO source_units VALUES(?,?,?,?,?,?)")?;
            for source in &revision.manifest.files {
                check()?;
                insert_source.execute(params![
                    source.path,
                    source.world,
                    source.module,
                    source.scope,
                    source.hash,
                    source.auxiliary
                ])?;
            }
        }
        tx.execute_batch("PRAGMA user_version=4")?;
        for owner in &header.replacements {
            tx.execute("DELETE FROM facts WHERE owner=?", [owner.key()])?;
        }
        let mut owner_keys = BTreeMap::<Owner, String>::new();
        let mut owner_key_bytes = 0usize;
        let mut insert = tx.prepare("INSERT INTO facts VALUES(?,?,?,?,?,?,?,?,?,?)")?;
        for (position, fact) in archive.records()?.enumerate() {
            if position.is_multiple_of(256) {
                check()?;
            }
            let (f, body) = fact?.into_parts();
            ensure!(
                derivation.is_none_or(|p| f.owner.producer != p.producer()),
                "DERIVED_PRODUCER_RESERVED"
            );
            validator.validate(&f).map_err(anyhow::Error::msg)?;
            ensure!(
                header.mode != "delta" || owners.contains(&f.owner),
                "OWNER_OUTSIDE_REPLACEMENT"
            );
            // Repeated facts from one source share an owner. Bound this task-local cache.
            let owner_size = f.owner.world.len()
                + f.owner.producer.len()
                + f.owner.kind.len()
                + f.owner.id.len()
                + 256;
            if !owner_keys.contains_key(&f.owner) && owner_size <= (1 << 20) {
                if owner_keys.len() >= 1024 || owner_key_bytes + owner_size > (1 << 20) {
                    owner_keys.clear();
                    owner_key_bytes = 0;
                }
                owner_keys.insert(f.owner.clone(), f.owner.key());
                owner_key_bytes += owner_size;
            }
            let owner_key = owner_keys
                .get(&f.owner)
                .cloned()
                .unwrap_or_else(|| f.owner.key());
            insert
                .execute(params![
                    f.id,
                    f.kind.name(),
                    owner_key,
                    f.parent,
                    f.target,
                    f.relation,
                    f.name,
                    f.source,
                    f.owner.world,
                    body
                ])
                .with_context(|| format!("DUPLICATE_OR_INVALID_FACT: {}", f.id))?;
        }
        drop(insert);
        phase_ms.insert(
            "archive_validate_ingest".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        // Anatomist also separates ingestion from query-index preparation.
        // Build directed graph indexes after the archive load, before derivation.
        check()?;
        if baseline.is_none() {
            schema::finish_full_load(&tx)?;
        }
        schema::prepare_queries(&tx)?;
        check()?;
        phase_ms.insert(
            "projection_query_indexes_fts".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        if let Some(policy) = derivation {
            check()?;
            policy.derive(&CandidateFacts {
                db: &tx,
                manifest: &revision.manifest,
                policy,
                check: &check,
                cancellation: &cancellation,
                writes: std::cell::Cell::new(0),
            })?;
            check()?;
        }
        check()?;
        phase_ms.insert(
            "wiring_derivation".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        schema::refresh_file_dependencies(&tx)?;
        check()?;
        tx.execute(
            "INSERT OR REPLACE INTO identity VALUES('revision',?)",
            [&revision.id],
        )?;
        tx.execute(
            "INSERT OR REPLACE INTO identity VALUES('profile',?)",
            [&revision.profile],
        )?;
        tx.commit()?;
        phase_ms.insert(
            "dependencies_commit".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        for (column, code) in [("parent", "DANGLING_PARENT"), ("target", "DANGLING_TARGET")] {
            cancellation()?;
            let missing = dangling_fact(&db, column)?;
            ensure!(missing.is_none(), "{code}: {}", missing.unwrap_or_default());
        }
        let invalid_binding: Option<String> = db.query_row(
            "SELECT f.id FROM facts f LEFT JOIN facts p ON f.parent=p.id
             WHERE ((f.kind='facet' AND f.relation='java.binding'
                     AND json_extract(f.body,'$.attributes.schema_version')=3)
                    OR (f.kind='call_target' AND json_type(f.body,'$.attributes.binding_evidence') IS NOT NULL))
             AND (p.id IS NULL OR p.kind<>'call_site' OR p.owner<>f.owner OR p.source<>f.source) LIMIT 1",
            [], |r| r.get(0),
        ).optional()?;
        ensure!(
            invalid_binding.is_none(),
            "INVALID_BINDING_EVIDENCE: {}",
            invalid_binding.unwrap_or_default()
        );
        for source in revision
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "java" && !s.auxiliary)
        {
            let covered: bool = db.query_row(
                "SELECT EXISTS(SELECT 1 FROM facts WHERE kind='coverage' AND source=? AND world=?)",
                params![source.path, source.world],
                |r| r.get(0),
            )?;
            ensure!(covered, "MISSING_COVERAGE: {}", source.path);
        }
        let mut summary = StagedSummary {
            evidence: Evidence::complete(),
            ..Default::default()
        };
        {
            let mut statement = db.prepare("SELECT body FROM facts ORDER BY id")?;
            let mut rows = statement.query([])?;
            while let Some(row) = rows.next()? {
                let f: Fact = serde_json::from_str(&row.get::<_, String>(0)?)?;
                validator.validate(&f).map_err(anyhow::Error::msg)?;
                if summary.fact_count.is_multiple_of(256) {
                    check()?;
                }
                summary.fact_count += 1;
                *summary.counts.entry(f.kind.name().into()).or_default() += 1;
                if f.kind == FactKind::Coverage && (!f.evidence_complete || f.status != "complete")
                {
                    summary.evidence.semantic = false;
                    if summary.evidence.reasons.len() < 1000 {
                        summary
                            .evidence
                            .reasons
                            .push(format!("coverage:{}:{}", f.source, f.status));
                    }
                }
                if f.kind == FactKind::Diagnostic {
                    let occurrences = match f.attributes.get("count") {
                        Some(value) => value.as_u64().context("DIAGNOSTIC_COUNT_INVALID")?,
                        None => 1,
                    };
                    ensure!(occurrences > 0, "DIAGNOSTIC_COUNT_INVALID");
                    summary.diagnostic_rows += 1;
                    summary.diagnostic_total = summary
                        .diagnostic_total
                        .checked_add(occurrences)
                        .context("DIAGNOSTIC_COUNT_OVERFLOW")?;
                    let severity = f
                        .attributes
                        .get("severity")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or("unknown");
                    let code = f
                        .attributes
                        .get("code")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or("unknown");
                    let group = format!("{severity}:{code}");
                    if summary.diagnostic_groups.len() < 1000
                        || summary.diagnostic_groups.contains_key(&group)
                    {
                        let total = summary.diagnostic_groups.entry(group).or_default();
                        *total = total
                            .checked_add(occurrences)
                            .context("DIAGNOSTIC_COUNT_OVERFLOW")?;
                    } else {
                        summary.diagnostic_groups_omitted = summary
                            .diagnostic_groups_omitted
                            .checked_add(occurrences)
                            .context("DIAGNOSTIC_COUNT_OVERFLOW")?;
                    }
                    if summary.diagnostic_samples.len() < 100 {
                        summary.diagnostic_sampled_occurrences = summary
                            .diagnostic_sampled_occurrences
                            .checked_add(occurrences)
                            .context("DIAGNOSTIC_COUNT_OVERFLOW")?;
                        summary.diagnostic_samples.push(f);
                    }
                }
            }
        }
        check()?;
        phase_ms.insert(
            "graph_validate_summary".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        let integrity: String = db.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
        ensure!(integrity == "ok", "CORRUPT_CANDIDATE");
        check()?;
        phase_ms.insert(
            "integrity_check".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        phase_started = std::time::Instant::now();
        summary.statistics = prepare_statistics(&db, &cancellation)?;
        check()?;
        phase_ms.insert(
            "statistics".into(),
            phase_started.elapsed().as_millis() as u64,
        );
        summary.phase_ms = phase_ms;
        drop(db);
        Ok(StagedCandidate {
            directory,
            revision,
            expected: expected.into(),
            _baseline: baseline,
            summary,
        })
    }

    pub fn publish_staged(&self, candidate: StagedCandidate, task: &str) -> Result<Revision> {
        let mut lock = self.coord.lock().unwrap();
        let revision = &candidate.revision;
        ensure!(
            self.head(&revision.target)? == candidate.expected,
            "HEAD_CONFLICT"
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
        ensure!(actual == candidate.expected, "HEAD_CONFLICT");
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
fn dangling_fact(db: &Connection, column: &str) -> Result<Option<String>> {
    ensure!(
        matches!(column, "parent" | "target"),
        "INVALID_ENDPOINT_COLUMN"
    );
    let endpoint: Option<String> = db.query_row(
        &format!("SELECT endpoint FROM (SELECT DISTINCT {column} AS endpoint FROM facts WHERE {column}<>'' ) refs
                  WHERE NOT EXISTS(SELECT 1 FROM facts t WHERE t.id=refs.endpoint) LIMIT 1"),
        [], |row| row.get(0)).optional()?;
    match endpoint {
        None => Ok(None),
        Some(value) => Ok(Some(db.query_row(
            &format!("SELECT id FROM facts WHERE {column}=? ORDER BY id LIMIT 1"),
            [value],
            |row| row.get(0),
        )?)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests::{result, revision};
    // Rust replacement for frozen NavigationDiagnosticsTest:
    // reasonTotalsSurviveRetentionAndKeepFileIdentity.
    #[test]
    fn diagnostic_retention_keeps_50004_occurrences_and_file_identity_after_restart()
    -> anyhow::Result<()> {
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let bytes = b"class Fixture {}".to_vec();
        let checksum = hash(&bytes);
        store.put_blobs(&BTreeMap::from([(checksum.clone(), bytes.clone())]))?;
        let manifest = Manifest {
            files: ["A.java", "B.java"]
                .into_iter()
                .map(|path| SourceUnit {
                    path: path.into(),
                    hash: checksum.clone(),
                    size: bytes.len() as u64,
                    module: "module".into(),
                    world: "main".into(),
                    scope: "MAIN".into(),
                    language: "java".into(),
                    auxiliary: false,
                })
                .collect(),
        };
        let revision = Revision {
            id: String::new(),
            target: "diagnostics".into(),
            project: "fixture".into(),
            checkout: "fixture".into(),
            manifest: manifest.clone(),
            profile: "fixture".into(),
            producer: "fixture".into(),
            created: 0,
        };
        let header = AnalysisHeader {
            mode: "full".into(),
            baseline: String::new(),
            manifest: manifest.id(),
            profile: revision.profile.clone(),
            producer: revision.producer.clone(),
            replacements: vec![],
        };
        let mut archive = FactArchiveBuilder::new(64 << 20, 100_000)?;
        for source in &manifest.files {
            let coverage: Fact = serde_json::from_value(serde_json::json!({
                "id":format!("coverage_{}",source.path),"kind":"coverage","source":source.path,
                "origin":"fixture","status":"partial","evidence_complete":false,
                "owner":{"world":"main","producer":"fixture","kind":"source","id":source.path},
                "attributes":{"input_hash":source.hash,"capabilities":["navigation"]}
            }))?;
            archive.push(&coverage)?;
        }
        for number in 0..50_004 {
            let source = if number == 50_003 { "B.java" } else { "A.java" };
            let diagnostic: Fact = serde_json::from_value(serde_json::json!({
                "id":format!("diagnostic_{number:08}"),"kind":"diagnostic","source":source,
                "name":"NAVIGATION_TARGET_UNRESOLVED","origin":"fixture","status":"partial",
                "owner":{"world":"main","producer":"fixture","kind":"source","id":source},
                "attributes":{"code":"NAVIGATION_TARGET_UNRESOLVED","severity":"warning","count":1}
            }))?;
            archive.push(&diagnostic)?;
        }
        let archive = archive.seal()?;
        let staged = store.stage_archive(revision.clone(), &header, &archive, "")?;
        assert_eq!(staged.summary.diagnostic_total, 50_004);
        assert_eq!(staged.summary.diagnostic_rows, 50_004);
        assert_eq!(staged.summary.diagnostic_samples.len(), 100);
        assert_eq!(staged.summary.diagnostic_sampled_occurrences, 100);
        assert_eq!(
            staged.summary.diagnostic_groups["warning:NAVIGATION_TARGET_UNRESOLVED"],
            50_004
        );
        assert_eq!(
            staged.summary.diagnostic_total - staged.summary.diagnostic_sampled_occurrences,
            49_904
        );
        // Repeating report preparation must not count retained samples as the
        // complete input, or accumulate the same diagnostic events twice.
        let repeated = store.stage_archive(revision, &header, &archive, "")?;
        assert_eq!(
            repeated.summary.diagnostic_total,
            staged.summary.diagnostic_total
        );
        assert_eq!(
            repeated.summary.diagnostic_rows,
            staged.summary.diagnostic_rows
        );
        assert_eq!(
            repeated.summary.diagnostic_groups,
            staged.summary.diagnostic_groups
        );
        assert_eq!(
            repeated.summary.diagnostic_samples,
            staged.summary.diagnostic_samples
        );
        drop(repeated);
        let published = store.publish_staged(staged, "retention-proof")?;
        drop(store);
        let store = Store::open(directory.path())?;
        let reader = store.read(&published.id)?;
        for (source, expected) in [("A.java", 50_003), ("B.java", 1)] {
            let counts = reader
                .count_by_kind(&FactFilter {
                    kinds: vec![FactKind::Diagnostic],
                    source: Some(source.into()),
                    ..Default::default()
                })
                .map_err(anyhow::Error::msg)?;
            assert_eq!(counts["diagnostic"], expected);
        }
        Ok(())
    }

    #[test]
    fn statistics_cancellation_aborts_instead_of_becoming_a_warning() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch("CREATE TABLE items(id INTEGER, tag TEXT); CREATE INDEX by_id ON items(id); CREATE INDEX by_tag ON items(tag); INSERT INTO items VALUES(1,'x');").unwrap();
        let checks = std::cell::Cell::new(0);
        let failure = prepare_statistics(&db, &|| {
            checks.set(checks.get() + 1);
            ensure!(checks.get() < 3, "CANCELLED");
            Ok(())
        })
        .err()
        .unwrap();
        assert_eq!(failure.to_string(), "CANCELLED");
        let analyzed: Vec<String> = db
            .prepare("SELECT idx FROM sqlite_stat1")
            .unwrap()
            .query_map([], |row| row.get(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(analyzed, ["by_id"]);
    }

    #[test]
    fn query_statistics_are_prepared_in_the_candidate_without_rewriting_the_baseline() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let mut initial = result();
        initial.facts = vec![fact("a", "")];
        let first = store.publish(revision(), &initial, "", "first").unwrap();
        let baseline_path = directory
            .path()
            .join("revisions")
            .join(&first.id)
            .join("facts.db");
        let baseline_hash = hash(std::fs::read(&baseline_path).unwrap());
        let mut changed = initial;
        changed.mode = "delta".into();
        changed.baseline = first.id.clone();
        changed.replacements = changed
            .facts
            .iter()
            .map(|fact| fact.owner.clone())
            .collect();
        let mut writer = FactArchiveBuilder::new(1 << 20, 1000).unwrap();
        for fact in &changed.facts {
            writer.push(fact).unwrap();
        }
        let archive = writer.seal().unwrap();
        let candidate = store
            .stage_archive(revision(), &changed.header(), &archive, &first.id)
            .unwrap();
        let db = Connection::open_with_flags(
            candidate.directory.path().join("facts.db"),
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )
        .unwrap();
        let rows: i64 = db
            .query_row(
                "SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl='facts' AND stat IS NOT NULL",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert!(rows > 0);
        assert_eq!(hash(std::fs::read(&baseline_path).unwrap()), baseline_hash);
        assert_eq!(store.head("t").unwrap(), first.id);
    }
    #[test]
    fn diagnostic_occurrence_count_is_separate_from_rows_and_samples() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let mut input = result();
        let mut diagnostic = fact("diagnostic", "");
        diagnostic.kind = FactKind::Diagnostic;
        diagnostic.attributes = serde_json::from_value(
            serde_json::json!({"severity":"warning","code":"missing","count":7}),
        )
        .unwrap();
        input.facts = vec![diagnostic];
        let staged = store
            .stage_archive_with_derivation(
                revision(),
                &input.header(),
                &spool(&input.facts),
                "",
                || Ok(()),
                |_| Ok(()),
                None,
            )
            .unwrap();
        assert_eq!(staged.summary.fact_count, 1);
        assert_eq!(staged.summary.diagnostic_samples.len(), 1);
        assert_eq!(staged.summary.diagnostic_total, 7);
        assert_eq!(staged.summary.diagnostic_groups["warning:missing"], 7);
    }
    #[test]
    fn directed_references_preserve_global_empty_and_missing_parent_reads() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE facts(id TEXT PRIMARY KEY,kind TEXT,relation TEXT,parent TEXT,body TEXT);
            CREATE INDEX fact_kind ON facts(kind); CREATE INDEX fact_parent ON facts(parent);",
        )
        .unwrap();
        for (id, parent, relation, kind) in [
            ("z", "owner", "wanted", FactKind::Reference),
            ("a", "owner", "wanted", FactKind::Reference),
            ("m", "other", "wanted", FactKind::Reference),
            ("b", "", "wanted", FactKind::Reference),
            ("wrong-relation", "owner", "other", FactKind::Reference),
            ("wrong-kind", "owner", "wanted", FactKind::Relation),
        ] {
            let mut value = fact(id, "target");
            value.kind = kind;
            value.parent = parent.into();
            value.relation = relation.into();
            db.execute(
                "INSERT INTO facts VALUES(?1,?2,?3,?4,?5)",
                params![
                    id,
                    value.kind.name(),
                    relation,
                    parent,
                    serde_json::to_string(&value).unwrap()
                ],
            )
            .unwrap();
        }
        let manifest = Manifest::default();
        let visits = std::cell::Cell::new(0usize);
        let cancel = || {
            visits.set(visits.get() + 1);
            Ok(())
        };
        let check = || Ok(());
        let facts = CandidateFacts {
            db: &db,
            manifest: &manifest,
            policy: &ReadWriteDerivation,
            check: &check,
            cancellation: &cancel,
            writes: std::cell::Cell::new(0),
        };
        let ids = |parent| {
            facts
                .references("wanted", parent)
                .unwrap()
                .into_iter()
                .map(|f| f.id)
                .collect::<Vec<_>>()
        };
        assert_eq!(ids(None), vec!["a", "b", "m", "z"]);
        assert_eq!(ids(Some("owner")), vec!["a", "z"]);
        assert_eq!(ids(Some("")), vec!["b"]);
        assert!(ids(Some("missing")).is_empty());
        assert_eq!(
            visits.get(),
            7,
            "cancellation remains checked for every returned row"
        );
    }

    #[test]
    fn endpoint_checks_preserve_dangling_errors_with_repeated_references() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch("CREATE TABLE facts(id TEXT PRIMARY KEY,parent TEXT,target TEXT);
            CREATE INDEX fact_parent ON facts(parent); CREATE INDEX fact_target ON facts(target);
            INSERT INTO facts VALUES('owner','',''),('one','owner','owner'),('two','owner','owner');").unwrap();
        assert_eq!(dangling_fact(&db, "parent").unwrap(), None);
        assert_eq!(dangling_fact(&db, "target").unwrap(), None);
        db.execute(
            "INSERT INTO facts VALUES('bad-parent','missing','owner')",
            [],
        )
        .unwrap();
        assert_eq!(
            dangling_fact(&db, "parent").unwrap().as_deref(),
            Some("bad-parent")
        );
        assert_eq!(dangling_fact(&db, "target").unwrap(), None);
        db.execute(
            "INSERT INTO facts VALUES('bad-target','owner','missing')",
            [],
        )
        .unwrap();
        assert_eq!(
            dangling_fact(&db, "target").unwrap().as_deref(),
            Some("bad-target")
        );
        db.execute("INSERT INTO facts VALUES('missing','','')", [])
            .unwrap();
        assert_eq!(dangling_fact(&db, "parent").unwrap(), None);
        assert_eq!(dangling_fact(&db, "target").unwrap(), None);
        assert!(dangling_fact(&db, "body").is_err());
    }

    fn fact(id: &str, target: &str) -> Fact {
        serde_json::from_value(serde_json::json!({"id":id,"kind":"declaration",
            "owner":{"world":"w","producer":"mock","kind":"aggregate","id":id},
            "origin":"mock","target":target}))
        .unwrap()
    }
    fn spool(facts: &[Fact]) -> FactArchive {
        let mut writer = FactArchiveBuilder::new(1024 * 1024, 100).unwrap();
        for fact in facts {
            writer.push(fact).unwrap();
        }
        writer.seal().unwrap()
    }
    struct ReadWriteDerivation;
    impl CandidateDerivation for ReadWriteDerivation {
        fn producer(&self) -> &str {
            "derived-test"
        }
        fn aggregate_id(&self) -> &str {
            "links"
        }
        fn derive(&self, facts: &CandidateFacts<'_>) -> Result<()> {
            for index in 0..1000 {
                ensure!(facts.fact("a")?.is_some(), "MISSING_INPUT");
                let mut output = fact(&format!("derived-{index}"), "a");
                output.owner.producer = self.producer().into();
                output.owner.id = self.aggregate_id().into();
                facts.insert(&output)?;
            }
            Ok(())
        }
    }
    #[test]
    fn derived_reads_cancel_per_row_and_budget_checks_stay_batched() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let mut input = result();
        input.facts = vec![fact("a", "")];
        let disk = std::cell::Cell::new(0usize);
        let cancelled = std::cell::Cell::new(0usize);
        let staged = store
            .stage_archive_with_derivation(
                revision(),
                &input.header(),
                &spool(&input.facts),
                "",
                || {
                    cancelled.set(cancelled.get() + 1);
                    Ok(())
                },
                |_| {
                    disk.set(disk.get() + 1);
                    Ok(())
                },
                Some(&ReadWriteDerivation),
            )
            .unwrap();
        assert_eq!(staged.summary.fact_count, 1001);
        assert!(cancelled.get() >= 2000);
        assert!(disk.get() < 100, "per-read disk scans: {}", disk.get());
        drop(staged);
        let cancelled = std::cell::Cell::new(0usize);
        let failure = store
            .stage_archive_with_derivation(
                revision(),
                &input.header(),
                &spool(&input.facts),
                "",
                || {
                    cancelled.set(cancelled.get() + 1);
                    ensure!(cancelled.get() < 100, "CANCELLED");
                    Ok(())
                },
                |_| Ok(()),
                Some(&ReadWriteDerivation),
            )
            .err()
            .unwrap();
        assert!(failure.to_string().contains("CANCELLED"));
        assert_eq!(store.head("t").unwrap(), "");
    }

    #[test]
    fn partial_final_derived_batch_is_budget_checked_before_commit() {
        struct OneWrite<'a>(&'a std::cell::Cell<bool>);
        impl CandidateDerivation for OneWrite<'_> {
            fn producer(&self) -> &str {
                "derived-test"
            }
            fn aggregate_id(&self) -> &str {
                "links"
            }
            fn derive(&self, facts: &CandidateFacts<'_>) -> Result<()> {
                let mut output = fact("derived", "a");
                output.owner.producer = self.producer().into();
                output.owner.id = self.aggregate_id().into();
                facts.insert(&output)?;
                self.0.set(true);
                Ok(())
            }
        }
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let mut input = result();
        input.facts = vec![fact("a", "")];
        let written = std::cell::Cell::new(false);
        let failure = store
            .stage_archive_with_derivation(
                revision(),
                &input.header(),
                &spool(&input.facts),
                "",
                || Ok(()),
                |_| {
                    ensure!(!written.get(), "JAVA_SCRATCH_BUDGET");
                    Ok(())
                },
                Some(&OneWrite(&written)),
            )
            .err()
            .unwrap();
        assert!(failure.to_string().contains("JAVA_SCRATCH_BUDGET"));
        assert_eq!(store.head("t").unwrap(), "");
    }

    #[test]
    fn staged_delta_matches_merge_and_pins_baseline_until_publication() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let mut first = result();
        first.facts = vec![fact("a", ""), fact("b", "a")];
        let candidate = store
            .stage_archive(revision(), &first.header(), &spool(&first.facts), "")
            .unwrap();
        assert_eq!(store.head("t").unwrap(), "");
        assert_eq!(candidate.summary.fact_count, 2);
        let published = store.publish_staged(candidate, "first").unwrap();
        let reader = store.read(&published.id).unwrap();
        let baseline = reader.export_archive(1024 * 1024, 100).unwrap();
        assert_eq!(
            baseline
                .iter()
                .unwrap()
                .collect::<Result<Vec<_>>>()
                .unwrap(),
            first.facts
        );
        drop(reader);
        let mut delta = result();
        delta.mode = "delta".into();
        delta.baseline = published.id.clone();
        delta.replacements = vec![first.facts[1].owner.clone()];
        delta.facts = vec![fact("b", "")];
        let expected = delta.validate(&Manifest::default(), &first.facts).unwrap();
        let candidate = store
            .stage_archive(
                revision(),
                &delta.header(),
                &spool(&delta.facts),
                &published.id,
            )
            .unwrap();
        let next = store.publish_staged(candidate, "delta").unwrap();
        assert_eq!(
            store
                .read(&next.id)
                .unwrap()
                .select(&FactFilter::default())
                .unwrap(),
            expected
        );
        store.gc(true).unwrap();
        assert!(store.read(&published.id).is_err());
        assert_eq!(
            std::fs::read_dir(dir.path().join("candidates"))
                .unwrap()
                .count(),
            0
        );
    }
    #[test]
    fn invalid_or_conflicting_candidates_never_change_head() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let mut input = result();
        input.facts = vec![fact("a", "missing")];
        assert!(
            store
                .stage_archive(revision(), &input.header(), &spool(&input.facts), "")
                .is_err()
        );
        input.facts = vec![fact("a", ""), fact("a", "")];
        assert!(
            store
                .stage_archive(revision(), &input.header(), &spool(&input.facts), "")
                .is_err()
        );
        assert_eq!(store.head("t").unwrap(), "");
        let candidate = store
            .stage_archive(revision(), &result().header(), &spool(&[]), "")
            .unwrap();
        let other = store.publish(revision(), &result(), "", "other").unwrap();
        assert!(store.publish_staged(candidate, "conflict").is_err());
        assert_eq!(store.head("t").unwrap(), other.id);
        assert_eq!(
            std::fs::read_dir(dir.path().join("candidates"))
                .unwrap()
                .count(),
            0
        );
    }
    #[test]
    fn scratch_checks_abort_sqlite_staging_before_publication_and_remove_candidate() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let input = result();
        let archive = spool(&input.facts);
        let error = store
            .stage_archive_with_scratch_check(
                revision(),
                &input.header(),
                &archive,
                "",
                || Ok(()),
                |directory| {
                    let bytes = std::fs::read_dir(directory)?
                        .try_fold(0u64, |total, item| -> Result<u64> {
                            Ok(total + item?.metadata()?.len())
                        })?;
                    ensure!(bytes <= 1, "JAVA_SCRATCH_BUDGET");
                    Ok(())
                },
            )
            .err()
            .unwrap();
        assert!(error.to_string().contains("JAVA_SCRATCH_BUDGET"));
        assert_eq!(store.head("t").unwrap(), "");
        assert_eq!(
            std::fs::read_dir(root.path().join("candidates"))
                .unwrap()
                .count(),
            0
        );
    }
    #[test]
    fn recovery_skips_in_flight_candidates_and_cancellation_discards_private_state() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let candidate = store
            .stage_archive(revision(), &result().header(), &spool(&[]), "")
            .unwrap();
        let live = candidate.directory.path().to_owned();
        let stale = dir.path().join("candidates/rev_dead");
        std::fs::create_dir(&stale).unwrap();
        store.recover(true).unwrap();
        assert!(live.exists());
        assert!(!stale.exists());
        drop(candidate);
        assert!(!live.exists());
        let records = (0..600)
            .map(|i| fact(&format!("f{i}"), ""))
            .collect::<Vec<_>>();
        let mut writer = FactArchiveBuilder::new(1024 * 1024, 600).unwrap();
        for record in &records {
            writer.push(record).unwrap();
        }
        let records = writer.seal().unwrap();
        let checkpoints = AtomicUsize::new(0);
        let cancelled =
            store.stage_archive_checked(revision(), &result().header(), &records, "", || {
                ensure!(checkpoints.fetch_add(1, Ordering::SeqCst) < 3, "CANCELLED");
                Ok(())
            });
        assert!(cancelled.is_err());
        assert_eq!(store.head("t").unwrap(), "");
        assert_eq!(
            std::fs::read_dir(dir.path().join("candidates"))
                .unwrap()
                .count(),
            0
        );
    }
    #[test]
    fn failed_catalog_transaction_retains_old_revision_and_recovers_orphan() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let first = store.publish(revision(), &result(), "", "first").unwrap();
        let candidate = store
            .stage_archive(revision(), &result().header(), &spool(&[]), &first.id)
            .unwrap();
        store.catalog().unwrap().execute_batch("CREATE TRIGGER fail_publish BEFORE INSERT ON heads BEGIN SELECT RAISE(ABORT,'injected failure'); END;").unwrap();
        assert!(store.publish_staged(candidate, "failure").is_err());
        assert_eq!(store.head("t").unwrap(), first.id);
        assert_eq!(
            store.recover(false).unwrap()["orphans"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        store.recover(true).unwrap();
        assert!(store.read(&first.id).is_ok());
    }
}
