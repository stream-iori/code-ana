use super::*;
use codeana_contracts::archive::{FactArchive, FactArchiveBuilder};

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
    pub fact_count: u64,
    pub counts: BTreeMap<String, u64>,
    pub diagnostic_total: u64,
    pub diagnostic_groups: BTreeMap<String, u64>,
    pub diagnostic_groups_omitted: u64,
    pub diagnostic_samples: Vec<Fact>,
    pub evidence: Evidence,
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
        check()?;
        let mut archive = match root {
            Some(path) => FactArchiveBuilder::new_in(max_bytes, max_count, path)?,
            None => FactArchiveBuilder::new(max_bytes, max_count)?,
        };
        let mut statement = self
            .connection
            .prepare("SELECT body FROM facts ORDER BY id")?;
        let mut rows = statement.query([])?;
        while let Some(row) = rows.next()? {
            if archive.count().is_multiple_of(256) {
                check()?;
            }
            let body: String = row.get(0)?;
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
        mut revision: Revision,
        header: &AnalysisHeader,
        archive: &FactArchive,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
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
        db.execute_batch("PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA cache_size=-8192; PRAGMA temp_store=FILE;
            CREATE TABLE IF NOT EXISTS identity(key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS facts(id TEXT PRIMARY KEY,kind TEXT NOT NULL,owner TEXT NOT NULL,parent TEXT NOT NULL,target TEXT NOT NULL,relation TEXT NOT NULL,name TEXT NOT NULL,source TEXT NOT NULL,world TEXT NOT NULL,body TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS fact_kind ON facts(kind); CREATE INDEX IF NOT EXISTS fact_owner ON facts(owner);
            CREATE INDEX IF NOT EXISTS fact_parent ON facts(parent); CREATE INDEX IF NOT EXISTS fact_target ON facts(target);
            CREATE INDEX IF NOT EXISTS fact_name ON facts(name); CREATE INDEX IF NOT EXISTS fact_source ON facts(source,world);")?;
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
        let validator = FactValidator::new(&revision.manifest);
        let tx = db.transaction()?;
        for owner in &header.replacements {
            tx.execute("DELETE FROM facts WHERE owner=?", [owner.key()])?;
        }
        let mut insert = tx.prepare("INSERT INTO facts VALUES(?,?,?,?,?,?,?,?,?,?)")?;
        for (position, fact) in archive.iter()?.enumerate() {
            if position.is_multiple_of(256) {
                check()?;
            }
            let f = fact?;
            validator.validate(&f).map_err(anyhow::Error::msg)?;
            ensure!(
                header.mode != "delta" || owners.contains(&f.owner),
                "OWNER_OUTSIDE_REPLACEMENT"
            );
            insert
                .execute(params![
                    f.id,
                    f.kind.name(),
                    f.owner.key(),
                    f.parent,
                    f.target,
                    f.relation,
                    f.name,
                    f.source,
                    f.owner.world,
                    json(&f)?
                ])
                .with_context(|| format!("DUPLICATE_OR_INVALID_FACT: {}", f.id))?;
        }
        drop(insert);
        tx.execute(
            "INSERT OR REPLACE INTO identity VALUES('revision',?)",
            [&revision.id],
        )?;
        tx.execute(
            "INSERT OR REPLACE INTO identity VALUES('profile',?)",
            [&revision.profile],
        )?;
        tx.commit()?;
        for (column, code) in [("parent", "DANGLING_PARENT"), ("target", "DANGLING_TARGET")] {
            let missing: Option<String> = db.query_row(&format!("SELECT f.id FROM facts f LEFT JOIN facts t ON f.{column}=t.id WHERE f.{column}<>'' AND t.id IS NULL LIMIT 1"), [], |r| r.get(0)).optional()?;
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
                    summary.diagnostic_total += 1;
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
                        *summary.diagnostic_groups.entry(group).or_default() += 1;
                    } else {
                        summary.diagnostic_groups_omitted += 1;
                    }
                    if summary.diagnostic_samples.len() < 100 {
                        summary.diagnostic_samples.push(f);
                    }
                }
            }
        }
        check()?;
        let integrity: String = db.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
        ensure!(integrity == "ok", "CORRUPT_CANDIDATE");
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
        for source in &revision.manifest.files {
            self.blob(&source.hash)?;
        }
        std::fs::File::open(candidate.directory.path().join("facts.db"))?.sync_all()?;
        durable_write(
            &candidate.directory.path().join("revision.json"),
            json(revision)?.as_bytes(),
        )?;
        sync_dir(candidate.directory.path())?;
        test_fault(&self.home, "candidate_synced")?;
        std::fs::rename(
            candidate.directory.path(),
            self.home.join("revisions").join(&revision.id),
        )?;
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
        tx.commit()?;
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests::{result, revision};
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
