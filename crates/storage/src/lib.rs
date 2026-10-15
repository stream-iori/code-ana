//! Immutable fact revisions, catalog compare-and-swap, durable task and history records.
mod annotations;
mod blobs;
pub mod derived_cache;
mod graph_calls;
mod graph_compact;
mod graph_query;
pub mod graph_store;
mod maintenance;
mod objects;
mod pages;
mod staged;
mod tasks;
mod text_search;
use anyhow::{Context, Result, ensure};
use codeana_model::*;
pub use pages::PageSpool;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Serialize, de::DeserializeOwned};
pub use staged::{CandidateStatistics, StagedCandidate, StagedSummary};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};
pub use text_search::TextCandidates;
#[derive(Default)]
struct Coordination {
    readers: BTreeMap<String, usize>,
    staged_blobs: BTreeMap<String, u64>,
    staged_candidates: BTreeSet<String>,
    active_blobs: BTreeMap<String, usize>,
}
#[derive(Clone)]
pub struct Store {
    pub home: PathBuf,
    coord: Arc<Mutex<Coordination>>,
    blob_io: Arc<blobs::IoLimit>,
}
pub struct AnnotationCommand<'a> {
    pub project: &'a str,
    pub action: AnnotationAction,
    pub id: &'a str,
    pub symbol: &'a str,
    pub text: &'a str,
    pub version: &'a str,
    pub anchor: Option<&'a AnnotationAnchor>,
}
pub struct ReadContext {
    connection: Connection,
    sources: BTreeMap<String, usize>,
    revision: Revision,
    coord: Arc<Mutex<Coordination>>,
    home: PathBuf,
}
impl Drop for ReadContext {
    fn drop(&mut self) {
        let mut c = self.coord.lock().unwrap();
        if let Some(n) = c.readers.get_mut(&self.revision.id) {
            *n -= 1;
            if *n == 0 {
                c.readers.remove(&self.revision.id);
            }
        }
    }
}
fn json<T: Serialize>(x: &T) -> Result<String> {
    Ok(serde_json::to_string(x)?)
}
pub fn durable_write(path: &Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write;
    let parent = path.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::PathParent,
        "",
    ))?;
    std::fs::create_dir_all(parent)?;
    let temp = parent.join(new_id("write"));
    let mut f = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temp)?;
    f.write_all(bytes)?;
    f.sync_all()?;
    std::fs::rename(temp, path)?;
    sync_dir(parent)?;
    Ok(())
}
fn sync_dir(p: &Path) -> Result<()> {
    codeana_contracts::performance::measure(
        codeana_contracts::performance::Metric::DirectorySync,
        0,
        false,
        || std::fs::File::open(p)?.sync_all(),
    )?;
    Ok(())
}
/// Debug-only fault points for the isolated mock daemon. Release builds and
/// ordinary homes cannot activate them.
pub fn test_fault(home: &Path, point: &str) -> Result<()> {
    #[cfg(debug_assertions)]
    if std::fs::read_to_string(home.join("mode")).ok().as_deref() == Some("mock-v1")
        && let Ok(spec) = std::env::var("CODEANA_TEST_FAULT")
    {
        if spec == format!("crash:{point}") {
            std::process::exit(75);
        }
        if spec == format!("error:{point}") {
            anyhow::bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::TestFault,
                point.to_string()
            ));
        }
        if spec == format!("pause:{point}") {
            durable_write(&home.join("test-fault-reached"), point.as_bytes())?;
            let until = std::time::Instant::now() + Duration::from_secs(10);
            while !home.join("test-fault-release").exists() {
                ensure!(
                    std::time::Instant::now() < until,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::TestFaultTimeout,
                        point.to_string()
                    )
                );
                std::thread::sleep(Duration::from_millis(10));
            }
        }
    }
    let _ = (home, point);
    Ok(())
}
impl Store {
    pub fn open(home: &Path) -> Result<Self> {
        std::fs::create_dir_all(home)?;
        for p in ["revisions", "candidates", "blobs", "reports", "pages"] {
            std::fs::create_dir_all(home.join(p))?;
        }
        let s = Self {
            home: home.to_owned(),
            coord: Arc::default(),
            blob_io: Arc::default(),
        };
        let c = s.catalog()?;
        let schema: i64 = c.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(
            schema <= 2,
            codeana_model::DomainError::new(codeana_model::ErrorCode::UnsupportedCatalogSchema, "")
        );
        c.execute_batch("PRAGMA journal_mode=WAL; BEGIN IMMEDIATE;
 CREATE TABLE IF NOT EXISTS project_paths(path TEXT PRIMARY KEY,project TEXT NOT NULL,checkout TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS revisions(id TEXT PRIMARY KEY,target TEXT NOT NULL,project TEXT NOT NULL,body TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS heads(target TEXT PRIMARY KEY,revision TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS objects(kind TEXT NOT NULL,id TEXT NOT NULL,project TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(kind,id));
 CREATE TABLE IF NOT EXISTS refs(kind TEXT NOT NULL,id TEXT NOT NULL,revision TEXT NOT NULL,PRIMARY KEY(kind,id,revision));
 CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,request_id TEXT UNIQUE NOT NULL,request_hash TEXT NOT NULL,request TEXT NOT NULL,state TEXT NOT NULL,result TEXT,created INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS events(task TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(task,seq));
 CREATE TABLE IF NOT EXISTS commits(task TEXT PRIMARY KEY,revision TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS cleanup_pending(kind TEXT NOT NULL,id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(kind,id));
 PRAGMA user_version=2; COMMIT;")?;
        let a = Connection::open(home.join("annotations.db"))?;
        let annotation_schema: i64 = a.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(
            annotation_schema <= 2,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnsupportedAnnotationSchema,
                ""
            )
        );
        a.execute_batch("BEGIN IMMEDIATE; CREATE TABLE IF NOT EXISTS annotations(id TEXT PRIMARY KEY,project TEXT NOT NULL,symbol TEXT NOT NULL,body TEXT NOT NULL,version INTEGER NOT NULL,anchor TEXT NOT NULL DEFAULT '');")?;
        if annotation_schema == 1 {
            a.execute_batch("ALTER TABLE annotations ADD COLUMN anchor TEXT NOT NULL DEFAULT '';")?;
            test_fault(home, "migration")?;
        }
        a.execute_batch("CREATE TABLE IF NOT EXISTS annotation_revisions(project TEXT PRIMARY KEY,revision INTEGER NOT NULL); PRAGMA user_version=2; COMMIT;")?;
        Ok(s)
    }
    fn catalog(&self) -> Result<Connection> {
        let c = Connection::open(self.home.join("catalog.db"))?;
        c.busy_timeout(Duration::from_secs(5))?;
        c.execute_batch("PRAGMA synchronous=FULL;")?;
        Ok(c)
    }
    pub fn register_project(&self, path: &str, project: &str, checkout: &str) -> Result<()> {
        self.catalog()?.execute(
            "INSERT OR REPLACE INTO project_paths VALUES(?,?,?)",
            params![path, project, checkout],
        )?;
        Ok(())
    }
    pub fn project_path(&self, path: &str) -> Result<(String, String)> {
        self.catalog()?
            .query_row(
                "SELECT project,checkout FROM project_paths WHERE path=?",
                [path],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::ProjectNotRegistered,
                "",
            ))
    }

    pub fn head(&self, target: &str) -> Result<String> {
        Ok(self
            .catalog()?
            .query_row("SELECT revision FROM heads WHERE target=?", [target], |r| {
                r.get(0)
            })
            .optional()?
            .unwrap_or_default())
    }
    pub fn latest(&self, project: &str, checkout: &str) -> Result<String> {
        let c = self.catalog()?;
        let mut q=c.prepare("SELECT r.body FROM heads h JOIN revisions r ON r.id=h.revision WHERE r.project=? ORDER BY r.rowid DESC")?;
        for row in q.query_map([project], |r| r.get::<_, String>(0))? {
            let r: Revision = serde_json::from_str(&row?)?;
            if r.checkout == checkout {
                return Ok(r.id);
            }
        }
        anyhow::bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::NoIndex,
            ""
        ))
    }
    fn revision_unlocked(&self, id: &str) -> Result<Revision> {
        let body: String = self
            .catalog()?
            .query_row("SELECT body FROM revisions WHERE id=?", [id], |r| r.get(0))
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::RevisionNotFound,
                "",
            ))?;
        Ok(serde_json::from_str(&body)?)
    }
    pub fn read(&self, id: &str) -> Result<ReadContext> {
        let mut lock = self.coord.lock().unwrap();
        let r = self.revision_unlocked(id)?;
        let c = Connection::open_with_flags(
            self.home.join("revisions").join(&r.id).join("facts.db"),
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )?;
        let schema_version: i64 = c.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(
            schema_version == graph_store::SCHEMA_VERSION,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::GraphSchemaRebuildRequired,
                format!(
                    "expected={} actual={schema_version}; run index --full",
                    graph_store::SCHEMA_VERSION
                )
            )
        );
        let identity: String = c.query_row(
            "SELECT value FROM project_meta WHERE key='codeana.revision'",
            [],
            |r| r.get(0),
        )?;
        ensure!(
            identity == r.id,
            codeana_model::DomainError::new(codeana_model::ErrorCode::GraphIdentityMismatch, "")
        );
        *lock.readers.entry(id.into()).or_default() += 1;
        let sources = r
            .manifest
            .files
            .iter()
            .enumerate()
            .map(|(index, s)| (s.path.clone(), index))
            .collect();
        Ok(ReadContext {
            sources,
            connection: c,
            revision: r,
            coord: self.coord.clone(),
            home: self.home.clone(),
        })
    }
    /// In-memory fixture compatibility. Production already receives a disk archive.
    pub fn publish(
        &self,
        revision: Revision,
        result: &AnalysisResult,
        expected: &str,
        task: &str,
    ) -> Result<Revision> {
        ensure!(
            revision.producer.starts_with("mock"),
            codeana_model::DomainError::new(codeana_model::ErrorCode::MockFixtureRequired, "")
        );
        let rows = codeana_contracts::fixture_graph::rows(&revision.manifest, &result.facts);
        let candidate = self.stage_graph_records(
            revision,
            &result.header(),
            rows.into_iter().map(Ok),
            expected,
            || Ok(()),
            |_| Ok(()),
        )?;
        self.publish_staged(candidate, task)
    }
}
#[cfg(test)]
mod tests {
    #[test]
    fn blob_file_batches_retry_partial_failure_and_verify_reused_files() {
        use super::*;
        let directory = tempfile::tempdir().unwrap();
        let home = directory.path().join("home");
        let store = Store::open(&home).unwrap();
        let mut sources = BTreeMap::new();
        for (name, bytes) in [("first", b"one".as_slice()), ("second", b"two".as_slice())] {
            let source = directory.path().join(name);
            std::fs::write(&source, bytes).unwrap();
            sources.insert(hash(bytes), source);
        }
        let (first_hash, _) = sources.first_key_value().unwrap();
        let (last_hash, last_source) = sources.last_key_value().unwrap();
        let last_bytes = std::fs::read(last_source).unwrap();
        std::fs::write(last_source, b"changed before copy").unwrap();
        assert!(store.put_blob_files(&sources).is_err());
        assert!(home.join("blobs").join(first_hash).exists());
        assert!(!home.join("blobs").join(last_hash).exists());
        assert_eq!(std::fs::read_dir(home.join("blobs")).unwrap().count(), 1);
        assert_eq!(store.head("WORKTREE").unwrap(), "");
        // Reopen after failure: a successful retry must make reused renames
        // durable too, then permit publication to reference the whole batch.
        drop(store);
        std::fs::write(last_source, &last_bytes).unwrap();
        let store = Store::open(&home).unwrap();
        store.put_blob_files(&sources).unwrap();
        for (digest, source) in &sources {
            assert_eq!(store.blob(digest).unwrap(), std::fs::read(source).unwrap());
        }
        store.put_blob_files(&sources).unwrap();
        std::fs::write(last_source, b"changed reused source").unwrap();
        assert!(store.put_blob_files(&sources).is_err());
        std::fs::write(last_source, &last_bytes).unwrap();
        std::fs::write(home.join("blobs").join(last_hash), b"corrupt stored blob").unwrap();
        assert!(store.put_blob_files(&sources).is_err());
    }

    #[test]
    fn event_batches_preserve_resume_sequence_and_rollback_failed_serialization() {
        struct Row(bool);
        impl serde::Serialize for Row {
            fn serialize<S: serde::Serializer>(
                &self,
                serializer: S,
            ) -> std::result::Result<S::Ok, S::Error> {
                if self.0 {
                    Err(serde::ser::Error::custom(
                        "injected event serialization failure",
                    ))
                } else {
                    serializer.serialize_str("chunk")
                }
            }
        }
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        assert_eq!(store.event("task", &"queued").unwrap(), 1);
        assert_eq!(store.event_batch("task", ["a", "b", "c"]).unwrap(), Some(4));
        assert!(store.event_batch("task", [Row(false), Row(true)]).is_err());
        assert_eq!(
            store.events::<String>("task", 1).unwrap(),
            vec![(2, "a".into()), (3, "b".into()), (4, "c".into())]
        );
        assert_eq!(store.event("task", &"terminal").unwrap(), 5);
        assert_eq!(
            store
                .event_batch("task", std::iter::empty::<String>())
                .unwrap(),
            None
        );
        assert_eq!(
            store.events::<String>("task", 4).unwrap(),
            vec![(5, "terminal".into())]
        );
    }

    #[test]
    fn concurrent_event_batches_are_contiguous_and_never_interleave() {
        let directory = tempfile::tempdir().unwrap();
        let store = std::sync::Arc::new(Store::open(directory.path()).unwrap());
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(4));
        let jobs: Vec<_> = (0..4)
            .map(|batch| {
                let store = store.clone();
                let barrier = barrier.clone();
                std::thread::spawn(move || {
                    barrier.wait();
                    store
                        .event_batch("task", (0..4).map(|row| format!("{batch}:{row}")))
                        .unwrap();
                })
            })
            .collect();
        for job in jobs {
            job.join().unwrap();
        }
        let events = store.events::<String>("task", 0).unwrap();
        assert_eq!(events.len(), 16);
        for (index, (seq, _)) in events.iter().enumerate() {
            assert_eq!(*seq, index as u64 + 1);
        }
        for rows in events.chunks(4) {
            let batch = rows[0].1.split(':').next().unwrap();
            for (index, (_, value)) in rows.iter().enumerate() {
                assert_eq!(value, &format!("{batch}:{index}"));
            }
        }
    }

    #[test]
    fn published_file_dependencies_page_by_world_and_keep_the_old_revision() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let mut rev = revision();
        let content = b"class Api {}".to_vec();
        let content_hash = hash(&content);
        store
            .put_blobs(&BTreeMap::from([(content_hash.clone(), content)]))
            .unwrap();
        for (path, world, scope) in [
            ("A.java", "main", "MAIN"),
            ("B.java", "main", "MAIN"),
            ("Test.java", "test", "TEST"),
        ] {
            rev.manifest.files.push(SourceUnit {
                path: path.into(),
                hash: content_hash.clone(),
                size: 12,
                module: "app".into(),
                world: world.into(),
                scope: scope.into(),
                language: "java".into(),
                auxiliary: false,
            });
        }
        let make = |id: &str, kind: &str, path: &str, world: &str, target: &str| {
            serde_json::from_value::<Fact>(serde_json::json!({
            "id":id,"kind":kind,"source":path,"target":target,"origin":"mock","status":"complete","evidence_complete":true,
            "owner":{"world":world,"producer":"mock","kind":"source","id":path}
        })).unwrap()
        };
        let mut initial = result();
        initial.manifest = rev.manifest.id();
        initial.facts = vec![
            make("a", "declaration", "A.java", "main", ""),
            make("b", "declaration", "B.java", "main", ""),
            make("ab", "reference", "A.java", "main", "b"),
            make("ab-2", "reference", "A.java", "main", "b"),
            make("tb", "reference", "Test.java", "test", "b"),
        ];
        for source in &rev.manifest.files {
            initial.facts.push(make(
                &format!("coverage-{}", source.path),
                "coverage",
                &source.path,
                &source.world,
                "",
            ));
        }
        let first = store
            .publish(rev.clone(), &initial, "", "file-dependency-initial")
            .unwrap();
        let baseline = store.read(&first.id).unwrap();
        let keys = [FileKey {
            world: "main".into(),
            path: "B.java".into(),
        }];
        let main = baseline
            .reverse_file_dependencies(&keys, Some("main"), "", 1)
            .unwrap();
        assert_eq!(main.len(), 1);
        assert_eq!(main[0].source_file, "A.java");
        let all = baseline
            .reverse_file_dependencies(&keys, None, "", 1024)
            .unwrap();
        assert_eq!(all.len(), 2);
        let next = baseline
            .reverse_file_dependencies(&keys, None, &all[0].id, 1)
            .unwrap();
        assert_eq!(next.len(), 1);
        assert_eq!(next[0], all[1]);
        let test_rows = baseline
            .reverse_file_dependencies(&keys, Some("test"), "", 1024)
            .unwrap();
        assert!(
            baseline
                .reverse_file_dependencies(&keys, None, &next[0].id, 1)
                .unwrap()
                .is_empty()
        );
        assert!(
            baseline
                .reverse_file_dependencies(
                    &[FileKey {
                        world: "test".into(),
                        path: "B.java".into()
                    }],
                    None,
                    "",
                    1024
                )
                .unwrap()
                .is_empty()
        );
        let mut delta = result();
        delta.mode = "delta".into();
        delta.baseline = first.id.clone();
        delta.manifest = rev.manifest.id();
        delta.replacements = vec![initial.facts[0].owner.clone()];
        delta.facts = initial
            .facts
            .iter()
            .filter(|f| f.source == "A.java" && f.kind != FactKind::Reference)
            .cloned()
            .collect();
        let second = store
            .publish(rev, &delta, &first.id, "file-dependency-delete")
            .unwrap();
        let current = store.read(&second.id).unwrap();
        assert!(
            current
                .reverse_file_dependencies(&keys, Some("main"), "", 1024)
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            current
                .reverse_file_dependencies(&keys, None, "", 1024)
                .unwrap(),
            test_rows
        );
        assert_eq!(
            baseline
                .reverse_file_dependencies(&keys, None, "", 1024)
                .unwrap()
                .len(),
            2
        );
    }

    #[test]
    fn reverse_dependencies_keep_cycles_scopes_occurrences_and_atomic_source_replacement() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let mut rev = revision();
        let content = b"class A {}".to_vec();
        let digest = hash(&content);
        store
            .put_blobs(&BTreeMap::from([(digest.clone(), content)]))
            .unwrap();
        for (path, world, scope) in [
            ("A.java", "main", "MAIN"),
            ("B.java", "main", "MAIN"),
            ("Test.java", "test", "TEST"),
        ] {
            rev.manifest.files.push(SourceUnit {
                path: path.into(),
                hash: digest.clone(),
                size: 10,
                module: "app".into(),
                world: world.into(),
                scope: scope.into(),
                language: "java".into(),
                auxiliary: false,
            });
        }
        let make =
            |id: &str, kind: &str, path: &str, world: &str, parent: &str, target: &str| -> Fact {
                serde_json::from_value(
                    serde_json::json!({"id":id,"signature":id,"kind":kind,"source":path,
                "owner":{"world":world,"producer":"mock","kind":"source","id":path},
                "parent":parent,"target":target,"relation":"type_reference","origin":"mock"}),
                )
                .unwrap()
            };
        let a = make("a", "declaration", "A.java", "main", "", "");
        let b = make("b", "declaration", "B.java", "main", "", "");
        let test = make("test", "declaration", "Test.java", "test", "", "");
        let mut initial = result();
        initial.manifest = rev.manifest.id();
        initial.facts = vec![
            a.clone(),
            b.clone(),
            test,
            make("ab1", "reference", "A.java", "main", "a", "b"),
            make("ab2", "reference", "A.java", "main", "a", "b"),
            make("ba", "relation", "B.java", "main", "b", "a"),
            make("test-b", "reference", "Test.java", "test", "test", "b"),
        ];
        let first = store
            .publish(rev.clone(), &initial, "", "dependency-initial")
            .unwrap();
        let baseline = store.read(&first.id).unwrap();
        let scoped = FactFilter {
            world: Some("main".into()),
            ..Default::default()
        };
        let incoming = baseline
            .reverse_dependencies(&["b".into()], &scoped, "", 1024)
            .unwrap();
        assert_eq!(incoming.len(), 2);
        assert_ne!(incoming[0].id, incoming[1].id);
        assert!(incoming.iter().all(|f| f.parent == "a" && f.target == "b"));
        assert_eq!(
            baseline
                .reverse_dependencies(&["a".into()], &scoped, "", 1024)
                .unwrap()[0]
                .parent,
            "b"
        );
        let mut missing = make("missing", "call_site", "B.java", "main", "b", "");
        missing.name = "A.read".into();
        missing.status = "unresolved".into();
        let mut delta = result();
        delta.mode = "delta".into();
        delta.baseline = first.id.clone();
        delta.manifest = rev.manifest.id();
        delta.replacements = vec![a.owner, b.owner.clone()];
        delta.facts = vec![b, missing];
        let second = store
            .publish(rev, &delta, &first.id, "dependency-deletion")
            .unwrap();
        let current = store.read(&second.id).unwrap();
        assert!(
            current
                .reverse_dependencies(&["a".into(), "b".into()], &scoped, "", 1024)
                .unwrap()
                .is_empty()
        );
        let unresolved = current
            .unresolved_consumers(&["A.read".into()], &scoped, "", 1024)
            .unwrap();
        assert_eq!(unresolved.len(), 1);
        assert_eq!(unresolved[0].parent, "b");
        assert!(
            current
                .unresolved_consumers(
                    &["A.read".into()],
                    &FactFilter {
                        world: Some("test".into()),
                        ..Default::default()
                    },
                    "",
                    1024
                )
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            current
                .reverse_dependencies(&["b".into()], &FactFilter::default(), "", 1024)
                .unwrap()[0]
                .source,
            "Test.java"
        );
        assert_eq!(
            baseline
                .reverse_dependencies(&["b".into()], &scoped, "", 1024)
                .unwrap()
                .len(),
            2
        );
        assert!(
            baseline
                .unresolved_consumers(&["A.read".into()], &scoped, "", 1024)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn page_spool_survives_recovery_pins_revision_and_cleans_up_on_expiry() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let first = store.publish(revision(), &result(), "", "first").unwrap();
        let mut spool = store.page_spool().unwrap();
        spool
            .append(&[Record::display(&serde_json::json!({"id":"saved"})).unwrap()])
            .unwrap();
        store.recover(true).unwrap();
        spool
            .append(&[Record::display(&serde_json::json!({"id":"second"})).unwrap()])
            .unwrap();
        let id = "page_saved";
        store
            .freeze_page(
                id,
                "p",
                &serde_json::json!({"created":now(),"total":2}),
                std::slice::from_ref(&first.id),
                spool,
            )
            .unwrap();
        store
            .publish(revision(), &result(), &first.id, "second")
            .unwrap();
        store.gc(true).unwrap();
        assert!(store.read(&first.id).is_ok());
        assert_eq!(
            store.page_records(id, "p", 1, 1).unwrap()[0]
                .wire()
                .unwrap()["id"],
            "second"
        );
        assert!(store.page_records(id, "different-project", 0, 1).is_err());
        store
            .put_object(
                "page",
                id,
                "p",
                &serde_json::json!({"created":now()-86401}),
                std::slice::from_ref(&first.id),
            )
            .unwrap();
        store.gc(true).unwrap();
        assert!(!directory.path().join("pages/page_saved.db").exists());
        assert!(store.read(&first.id).is_err());
        let count = std::fs::read_dir(directory.path().join("candidates"))
            .unwrap()
            .count();
        {
            let mut abandoned = store.page_spool().unwrap();
            abandoned
                .append(&[Record::display(&serde_json::json!({"id":"cancelled"})).unwrap()])
                .unwrap();
        }
        assert_eq!(
            std::fs::read_dir(directory.path().join("candidates"))
                .unwrap()
                .count(),
            count
        );
        let crashed = directory.path().join("candidates/page-spool-crashed");
        std::fs::write(&crashed, b"unfinished").unwrap();
        store.recover(true).unwrap();
        assert!(!crashed.exists());
        std::fs::write(directory.path().join("pages/page_orphan.db"), b"orphan").unwrap();
        store.gc(true).unwrap();
        assert!(!directory.path().join("pages/page_orphan.db").exists());
    }

    use super::*;

    pub(super) fn revision() -> Revision {
        Revision {
            id: String::new(),
            target: "t".into(),
            project: "p".into(),
            checkout: "c".into(),
            manifest: Manifest::default(),
            profile: "profile".into(),
            producer: "mock".into(),
            created: 0,
        }
    }
    #[test]
    fn migrates_legacy_annotations_without_guessing_an_anchor() {
        let dir = tempfile::tempdir().unwrap();
        let legacy = Connection::open(dir.path().join("annotations.db")).unwrap();
        legacy.execute_batch("CREATE TABLE annotations(id TEXT PRIMARY KEY,project TEXT NOT NULL,symbol TEXT NOT NULL,body TEXT NOT NULL,version INTEGER NOT NULL); INSERT INTO annotations VALUES('note_1','p','symbol','saved',3); PRAGMA user_version=1;").unwrap();
        drop(legacy);
        let store = Store::open(dir.path()).unwrap();
        let rows = store
            .annotation(AnnotationCommand {
                project: "p",
                action: AnnotationAction::List,
                id: "",
                symbol: "",
                text: "",
                version: "",
                anchor: None,
            })
            .unwrap();
        assert_eq!(serde_json::to_value(&rows).unwrap()[0]["text"], "saved");
        assert!(serde_json::to_value(&rows).unwrap()[0]["anchor"].is_null());
        assert_eq!(serde_json::to_value(&rows).unwrap()[0]["version"], 3);
        Store::open(dir.path()).unwrap();
        let future = Connection::open(dir.path().join("annotations.db")).unwrap();
        future.execute_batch("PRAGMA user_version=99;").unwrap();
        assert!(Store::open(dir.path()).is_err());
    }
    #[test]
    fn recovery_retries_recorded_cleanup() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let id = "rev_pending";
        let folder = dir.path().join("revisions").join(id);
        std::fs::create_dir(&folder).unwrap();
        store
            .catalog()
            .unwrap()
            .execute("INSERT INTO cleanup_pending VALUES('revision',?,0)", [id])
            .unwrap();
        assert_eq!(
            serde_json::to_value(store.recover(false).unwrap()).unwrap()["pending"][0],
            id
        );
        assert!(folder.exists());
        store.recover(true).unwrap();
        assert!(!folder.exists());
        assert!(
            serde_json::to_value(store.recover(false).unwrap()).unwrap()["pending"]
                .as_array()
                .unwrap()
                .is_empty()
        );
    }
    pub(super) fn result() -> AnalysisResult {
        AnalysisResult {
            mode: "full".into(),
            baseline: String::new(),
            manifest: Manifest::default().id(),
            profile: "profile".into(),
            producer: "mock".into(),
            replacements: vec![],
            facts: vec![],
        }
    }
    #[test]
    fn removed_binding_capability_is_explicitly_rejected_by_graph_reader() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let graph = store.publish(revision(), &result(), "", "initial").unwrap();
        let reader = store.read(&graph.id).unwrap();
        let error = reader
            .select(&FactFilter {
                kinds: vec![FactKind::Facet],
                relation: Some("java.binding".into()),
                ..Default::default()
            })
            .unwrap_err();
        assert!(error.to_string().contains("UNSUPPORTED_CAPABILITY"));
        assert_eq!(store.head("t").unwrap(), graph.id);
    }
    #[test]
    fn publish_cas_reader_and_gc() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let r = s.publish(revision(), &result(), "", "task1").unwrap();
        let reader = s.read(&r.id).unwrap();
        assert!(s.publish(revision(), &result(), "", "task2").is_err());
        let next = s.publish(revision(), &result(), &r.id, "task3").unwrap();
        s.gc(true).unwrap();
        assert!(s.read(&r.id).is_ok());
        drop(reader);
        s.gc(true).unwrap();
        assert!(s.read(&r.id).is_err());
        assert_eq!(s.head("t").unwrap(), next.id);
    }
    #[test]
    fn concurrent_gc_cannot_delete_an_active_reader() {
        use std::sync::{Arc, Barrier};
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let first = s.publish(revision(), &result(), "", "first").unwrap();
        let reader = s.read(&first.id).unwrap();
        let next = s.publish(revision(), &result(), &first.id, "next").unwrap();
        let gate = Arc::new(Barrier::new(2));
        let worker_store = s.clone();
        let worker_gate = gate.clone();
        let gc = std::thread::spawn(move || {
            worker_gate.wait();
            worker_store.gc(true).unwrap();
        });
        gate.wait();
        assert!(reader.select(&FactFilter::default()).is_ok());
        gc.join().unwrap();
        assert!(reader.select(&FactFilter::default()).is_ok());
        assert!(s.read(&first.id).is_ok());
        drop(reader);
        s.gc(true).unwrap();
        assert!(s.read(&first.id).is_err());
        assert_eq!(s.head("t").unwrap(), next.id);
    }
    #[test]
    fn aggregate_replacement_is_rejected_without_changing_head() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let first = store.publish(revision(), &result(), "", "initial").unwrap();
        let mut delta = result();
        delta.mode = "delta".into();
        delta.baseline = first.id.clone();
        delta.replacements.push(Owner {
            world: "w".into(),
            producer: "mock".into(),
            kind: "aggregate".into(),
            id: "a".into(),
        });
        let error = store
            .publish(revision(), &delta, &first.id, "invalid")
            .unwrap_err();
        assert!(
            error
                .to_string()
                .contains("GRAPH_REPLACEMENT_SOURCE_REQUIRED")
        );
        assert_eq!(store.head("t").unwrap(), first.id);
    }
    #[test]
    fn catalog_failure_leaves_head_and_recovers_orphan() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let first = s.publish(revision(), &result(), "", "a").unwrap();
        s.catalog().unwrap().execute_batch("CREATE TRIGGER fail_publish BEFORE INSERT ON heads BEGIN SELECT RAISE(ABORT,'injected catalog failure'); END;").unwrap();
        assert!(s.publish(revision(), &result(), &first.id, "b").is_err());
        assert_eq!(s.head("t").unwrap(), first.id);
        let preview = serde_json::to_value(s.recover(false).unwrap()).unwrap();
        assert_eq!(preview["orphans"].as_array().unwrap().len(), 1);
        s.recover(true).unwrap();
        assert!(
            serde_json::to_value(s.recover(false).unwrap()).unwrap()["orphans"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert!(s.read(&first.id).is_ok());
    }
    #[test]
    fn hostile_selector_is_bound_as_data() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let r = s.publish(revision(), &result(), "", "a").unwrap();
        let read = s.read(&r.id).unwrap();
        assert!(
            read.select(&FactFilter {
                name: Some("' OR 1=1; DROP TABLE facts; --".into()),
                ..Default::default()
            })
            .unwrap()
            .is_empty()
        );
        assert!(read.select(&FactFilter::default()).is_ok());
    }
    #[test]
    fn future_schema_is_not_downgraded() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        s.catalog()
            .unwrap()
            .execute_batch("PRAGMA user_version=99;")
            .unwrap();
        assert!(Store::open(d.path()).is_err());
    }
    #[test]
    fn retention_policy_never_breaks_pins_or_project_isolation() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let first = s.publish(revision(), &result(), "", "a").unwrap();
        let second = s.publish(revision(), &result(), &first.id, "b").unwrap();
        let mut other = revision();
        other.project = "other".into();
        other.target = "other".into();
        let other = s.publish(other, &result(), "", "c").unwrap();
        let read = s.read(&first.id).unwrap();
        let plan = serde_json::to_value(s.gc_policy(true, Some("p"), 0, 0, 1).unwrap()).unwrap();
        assert_eq!(plan["budget_unmet"], true);
        assert!(s.read(&first.id).is_ok());
        drop(read);
        s.gc_policy(true, Some("p"), 0, 0, 1).unwrap();
        assert!(s.read(&first.id).is_err());
        assert!(s.read(&second.id).is_ok());
        assert!(s.read(&other.id).is_ok());
    }
    #[test]
    fn expired_pages_release_revision_refs() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let first = s.publish(revision(), &result(), "", "a").unwrap();
        s.put_object(
            "page",
            "expired",
            "p",
            &serde_json::json!({"created":0}),
            std::slice::from_ref(&first.id),
        )
        .unwrap();
        s.publish(revision(), &result(), &first.id, "b").unwrap();
        let preview =
            serde_json::to_value(s.gc_policy(false, Some("p"), 0, 0, 0).unwrap()).unwrap();
        assert_eq!(preview["expired_objects"].as_array().unwrap().len(), 1);
        assert!(s.read(&first.id).is_ok());
        s.gc_policy(true, Some("p"), 0, 0, 0).unwrap();
        assert!(s.read(&first.id).is_err());
    }
}
