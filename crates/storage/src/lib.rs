//! Immutable fact revisions, catalog compare-and-swap, durable task and history records.
pub mod derived_cache;
pub mod fact_dedup;
mod pages;
mod schema;
mod staged;
mod symbol_search;
mod text_search;
use anyhow::{Context, Result, bail, ensure};
use codeana_model::*;
pub use pages::PageSpool;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Serialize, de::DeserializeOwned};
pub use staged::{CandidateDerivation, CandidateFacts, StagedCandidate, StagedSummary};
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
}
#[derive(Clone)]
pub struct Store {
    pub home: PathBuf,
    coord: Arc<Mutex<Coordination>>,
}
pub struct AnnotationCommand<'a> {
    pub project: &'a str,
    pub action: &'a str,
    pub id: &'a str,
    pub symbol: &'a str,
    pub text: &'a str,
    pub version: &'a str,
    pub anchor: Option<&'a serde_json::Value>,
}
pub struct ReadContext {
    connection: Connection,
    revision: Revision,
    coord: Arc<Mutex<Coordination>>,
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
    let parent = path.parent().context("PATH_PARENT")?;
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
            anyhow::bail!("TEST_FAULT:{point}");
        }
        if spec == format!("pause:{point}") {
            durable_write(&home.join("test-fault-reached"), point.as_bytes())?;
            let until = std::time::Instant::now() + Duration::from_secs(10);
            while !home.join("test-fault-release").exists() {
                ensure!(
                    std::time::Instant::now() < until,
                    "TEST_FAULT_TIMEOUT:{point}"
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
        };
        let c = s.catalog()?;
        let schema: i64 = c.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(schema <= 2, "UNSUPPORTED_CATALOG_SCHEMA");
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
        ensure!(annotation_schema <= 2, "UNSUPPORTED_ANNOTATION_SCHEMA");
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
            .context("PROJECT_NOT_REGISTERED")
    }
    pub fn put_blobs(&self, blobs: &BTreeMap<String, Vec<u8>>) -> Result<()> {
        let mut guard = self.coord.lock().unwrap();
        guard
            .staged_blobs
            .extend(blobs.keys().cloned().map(|h| (h, now())));
        for (h, b) in blobs {
            ensure!(hash(b) == *h, "BLOB_HASH_MISMATCH");
            let p = self.home.join("blobs").join(h);
            if p.exists() {
                ensure!(hash(std::fs::read(p)?) == *h, "CORRUPT_BLOB");
            } else {
                durable_write(&p, b)?;
            }
        }
        // Reused files may have been renamed by a failed file-backed batch.
        if !blobs.is_empty() {
            sync_dir(&self.home.join("blobs"))?;
        }
        Ok(())
    }
    /// Persist captured input files without materializing dependency archives in memory.
    pub fn put_blob_files<T: AsRef<Path>>(&self, blobs: &BTreeMap<String, T>) -> Result<()> {
        let mut guard = self.coord.lock().unwrap();
        guard
            .staged_blobs
            .extend(blobs.keys().cloned().map(|h| (h, now())));
        for (hash, source) in blobs {
            ensure!(
                hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()),
                "INVALID_BLOB_ID"
            );
            let destination = self.home.join("blobs").join(hash);
            if destination.exists() {
                ensure!(
                    codeana_contracts::performance::measure(
                        codeana_contracts::performance::Metric::BlobHash,
                        std::fs::metadata(&destination)?.len(),
                        false,
                        || hash_file(&destination)
                    )? == *hash,
                    "CORRUPT_BLOB"
                );
                ensure!(
                    codeana_contracts::performance::measure(
                        codeana_contracts::performance::Metric::BlobHash,
                        std::fs::metadata(source.as_ref())?.len(),
                        false,
                        || hash_file(source.as_ref())
                    )? == *hash,
                    "BLOB_HASH_MISMATCH"
                );
                continue;
            }
            let temporary = destination.with_file_name(new_id("write"));
            let result = (|| -> Result<()> {
                let mut input = std::fs::File::open(source.as_ref())?;
                let mut output = std::fs::OpenOptions::new()
                    .create_new(true)
                    .write(true)
                    .open(&temporary)?;
                use codeana_contracts::performance::{Metric, measure};
                measure(Metric::BlobCopy, input.metadata()?.len(), false, || {
                    std::io::copy(&mut input, &mut output)
                })?;
                measure(
                    Metric::BlobFileSync,
                    output.metadata()?.len(),
                    false,
                    || output.sync_all(),
                )?;
                ensure!(
                    measure(Metric::BlobHash, output.metadata()?.len(), false, || {
                        hash_file(&temporary)
                    })? == *hash,
                    "BLOB_HASH_MISMATCH"
                );
                std::fs::rename(&temporary, &destination)?;
                Ok(())
            })();
            if result.is_err() {
                let _ = std::fs::remove_file(&temporary);
            }
            result?;
        }
        // Every file is durable before rename; persist the batch's directory
        // entries before a caller can publish references. Also sync on reuse:
        // an earlier failed batch may have left renamed, unreferenced files.
        if !blobs.is_empty() {
            sync_dir(&self.home.join("blobs"))?;
        }
        Ok(())
    }
    pub fn blob(&self, h: &str) -> Result<Vec<u8>> {
        ensure!(
            h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit()),
            "INVALID_BLOB_ID"
        );
        let b = std::fs::read(self.home.join("blobs").join(h))?;
        ensure!(hash(&b) == h, "CORRUPT_BLOB");
        Ok(b)
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
        anyhow::bail!("NO_INDEX")
    }
    fn revision_unlocked(&self, id: &str) -> Result<Revision> {
        let body: String = self
            .catalog()?
            .query_row("SELECT body FROM revisions WHERE id=?", [id], |r| r.get(0))
            .context("REVISION_NOT_FOUND")?;
        Ok(serde_json::from_str(&body)?)
    }
    pub fn read(&self, id: &str) -> Result<ReadContext> {
        let mut lock = self.coord.lock().unwrap();
        let r = self.revision_unlocked(id)?;
        let c = Connection::open_with_flags(
            self.home.join("revisions").join(&r.id).join("facts.db"),
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )?;
        let identity: String =
            c.query_row("SELECT value FROM identity WHERE key='revision'", [], |r| {
                r.get(0)
            })?;
        ensure!(identity == r.id, "GRAPH_IDENTITY_MISMATCH");
        *lock.readers.entry(id.into()).or_default() += 1;
        Ok(ReadContext {
            connection: c,
            revision: r,
            coord: self.coord.clone(),
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
        let mut archive = codeana_contracts::archive::FactArchiveBuilder::new(
            MAX_RESULT as u64,
            MAX_FACTS as u64,
        )?;
        for fact in &result.facts {
            archive.push(fact)?;
        }
        let archive = archive.seal()?;
        let candidate = self.stage_archive(revision, &result.header(), &archive, expected)?;
        self.publish_staged(candidate, task)
    }
    pub fn put_object<T: Serialize>(
        &self,
        kind: &str,
        id: &str,
        project: &str,
        value: &T,
        revisions: &[String],
    ) -> Result<()> {
        let mut guard = self.coord.lock().unwrap();
        let mut c = self.catalog()?;
        let tx = c.transaction()?;
        for r in revisions {
            self.revision_unlocked(r)?;
        }
        tx.execute(
            "INSERT OR REPLACE INTO objects VALUES(?,?,?,?)",
            params![kind, id, project, json(value)?],
        )?;
        tx.execute("DELETE FROM refs WHERE kind=? AND id=?", params![kind, id])?;
        for r in revisions {
            tx.execute("INSERT INTO refs VALUES(?,?,?)", params![kind, id, r])?;
        }
        tx.commit()?;
        if let Some(files) = serde_json::to_value(value)?
            .get("manifest")
            .and_then(|m| m.get("files"))
            .and_then(|f| f.as_array())
        {
            for source in files {
                if let Some(h) = source["hash"].as_str() {
                    guard.staged_blobs.remove(h);
                }
            }
        }
        Ok(())
    }
    pub fn object<T: DeserializeOwned>(&self, kind: &str, id: &str, project: &str) -> Result<T> {
        let s: String = self
            .catalog()?
            .query_row(
                "SELECT body FROM objects WHERE kind=? AND id=? AND project=?",
                params![kind, id, project],
                |r| r.get(0),
            )
            .context("OBJECT_NOT_FOUND_OR_EXPIRED")?;
        Ok(serde_json::from_str(&s)?)
    }
    pub fn objects(&self, kind: &str, project: &str) -> Result<Vec<serde_json::Value>> {
        let c = self.catalog()?;
        let mut s = c.prepare("SELECT body FROM objects WHERE kind=? AND project=? ORDER BY id")?;
        let mut out = Vec::new();
        for r in s.query_map(params![kind, project], |r| r.get::<_, String>(0))? {
            out.push(serde_json::from_str(&r?)?);
        }
        Ok(out)
    }
    pub fn delete_object(&self, kind: &str, id: &str, project: &str) -> Result<()> {
        let _guard = self.coord.lock().unwrap();
        let mut c = self.catalog()?;
        let tx = c.transaction()?;
        let n = tx.execute(
            "DELETE FROM objects WHERE kind=? AND id=? AND project=?",
            params![kind, id, project],
        )?;
        ensure!(n == 1, "OBJECT_NOT_FOUND");
        tx.execute("DELETE FROM refs WHERE kind=? AND id=?", params![kind, id])?;
        tx.commit()?;
        if kind == "page" {
            self.remove_page(id)?;
        }
        Ok(())
    }
    pub fn register_task(&self, id: &str, r: &Request) -> Result<(String, bool)> {
        let mut normalized = r.clone();
        normalized.request_id.clear();
        let h = digest(&normalized);
        let c = self.catalog()?;
        c.execute(
            "INSERT OR IGNORE INTO tasks VALUES(?,?,?,?,'queued',NULL,?)",
            params![id, r.request_id, h, json(r)?, now() as i64],
        )?;
        let (existing, old): (String, String) = c.query_row(
            "SELECT id,request_hash FROM tasks WHERE request_id=?",
            [&r.request_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        ensure!(h == old, "REQUEST_ID_CONFLICT");
        Ok((existing.clone(), existing == id))
    }
    pub fn task(&self, id: &str) -> Result<(String, Option<TaskResult>, u64)> {
        let c = self.catalog()?;
        let (state, result): (String, Option<String>) = c
            .query_row("SELECT state,result FROM tasks WHERE id=?", [id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .context("TASK_NOT_FOUND")?;
        let seq: i64 = c.query_row(
            "SELECT COALESCE(MAX(seq),0) FROM events WHERE task=?",
            [id],
            |r| r.get(0),
        )?;
        Ok((
            state,
            result.map(|r| serde_json::from_str(&r)).transpose()?,
            seq as u64,
        ))
    }
    pub fn running(&self, id: &str) -> Result<()> {
        self.catalog()?.execute(
            "UPDATE tasks SET state='running' WHERE id=? AND state='queued'",
            [id],
        )?;
        Ok(())
    }
    pub fn event<T: Serialize>(&self, id: &str, event: &T) -> Result<u64> {
        self.event_batch(id, std::iter::once(event))?
            .context("EMPTY_EVENT_BATCH")
    }
    /// Persist one already-rendered output in a single durable transaction.
    /// Serialization or insertion failure rolls back the complete batch.
    pub fn event_batch<T: Serialize>(
        &self,
        id: &str,
        events: impl IntoIterator<Item = T>,
    ) -> Result<Option<u64>> {
        let mut events = events.into_iter().peekable();
        if events.peek().is_none() {
            return Ok(None);
        }
        let mut c = self.catalog()?;
        let tx = c.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let mut seq: i64 = tx.query_row(
            "SELECT COALESCE(MAX(seq),0)+1 FROM events WHERE task=?",
            [id],
            |r| r.get(0),
        )?;
        let mut last = None;
        {
            let mut insert = tx.prepare("INSERT INTO events VALUES(?,?,?)")?;
            for event in events {
                insert.execute(params![id, seq, json(&event)?])?;
                last = Some(u64::try_from(seq)?);
                seq = seq.checked_add(1).context("EVENT_SEQUENCE_OVERFLOW")?;
            }
        }
        tx.commit()?;
        Ok(last)
    }
    pub fn events<T: DeserializeOwned>(&self, id: &str, after: u64) -> Result<Vec<(u64, T)>> {
        let c = self.catalog()?;
        let mut q =
            c.prepare("SELECT seq,body FROM events WHERE task=? AND seq>? ORDER BY seq LIMIT 64")?;
        let rows = q.query_map(params![id, i64::try_from(after)?], |r| {
            Ok((r.get::<_, i64>(0)? as u64, r.get::<_, String>(1)?))
        })?;
        let mut out = Vec::new();
        for r in rows {
            let (s, b) = r?;
            out.push((s, serde_json::from_str(&b)?));
        }
        Ok(out)
    }
    pub fn finish<T: Serialize>(&self, id: &str, result: &TaskResult, event: &T) -> Result<()> {
        let mut c = self.catalog()?;
        let tx = c.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let seq: i64 = tx.query_row(
            "SELECT COALESCE(MAX(seq),0)+1 FROM events WHERE task=?",
            [id],
            |r| r.get(0),
        )?;
        tx.execute(
            "UPDATE tasks SET state='terminal',result=? WHERE id=?",
            params![json(result)?, id],
        )?;
        tx.execute(
            "INSERT INTO events VALUES(?,?,?)",
            params![id, seq, json(event)?],
        )?;
        tx.commit()?;
        Ok(())
    }
    pub fn committed(&self, task: &str) -> Result<Option<String>> {
        Ok(self
            .catalog()?
            .query_row("SELECT revision FROM commits WHERE task=?", [task], |r| {
                r.get(0)
            })
            .optional()?)
    }
    pub fn unfinished(&self) -> Result<Vec<(String, Option<String>)>> {
        let c = self.catalog()?;
        let mut q=c.prepare("SELECT t.id,c.revision FROM tasks t LEFT JOIN commits c ON c.task=t.id WHERE state NOT IN ('terminal','expired')")?;
        Ok(q.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<std::result::Result<_, _>>()?)
    }
    pub fn annotation(&self, command: AnnotationCommand<'_>) -> Result<serde_json::Value> {
        let AnnotationCommand {
            project,
            action,
            id,
            symbol,
            text,
            version,
            anchor,
        } = command;
        let mut c = Connection::open(self.home.join("annotations.db"))?;
        let tx = c.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        match action {
            "add" => {
                ensure!(
                    !text.is_empty() && !symbol.is_empty() && text.len() <= 64 * 1024,
                    "ANNOTATION_CONTENT_REQUIRED"
                );
                let id = new_id("note");
                let anchor = anchor.context("ANNOTATION_ANCHOR_REQUIRED")?;
                tx.execute(
                    "INSERT INTO annotations(id,project,symbol,body,version,anchor) VALUES(?,?,?,?,1,?)",
                    params![id, project, symbol, text, json(anchor)?],
                )?;
                tx.execute("INSERT INTO annotation_revisions(project,revision) VALUES(?,1) ON CONFLICT(project) DO UPDATE SET revision=revision+1", [project])?;
                let annotation_revision: i64 = tx.query_row(
                    "SELECT revision FROM annotation_revisions WHERE project=?",
                    [project],
                    |r| r.get(0),
                )?;
                tx.commit()?;
                Ok(
                    serde_json::json!({"id":id,"symbol":symbol,"text":text,"version":1,"annotation_revision":annotation_revision,"anchor":anchor,"binding":"bound"}),
                )
            }
            "update" | "remove" => {
                if action == "update" {
                    ensure!(text.len() <= 64 * 1024, "ANNOTATION_CONTENT_BUDGET");
                }
                let expected = version
                    .parse::<i64>()
                    .context("ANNOTATION_VERSION_REQUIRED")?;
                let n = if action == "update" {
                    tx.execute("UPDATE annotations SET body=?,version=version+1 WHERE id=? AND project=? AND version=?",params![text,id,project,expected])?
                } else {
                    tx.execute(
                        "DELETE FROM annotations WHERE id=? AND project=? AND version=?",
                        params![id, project, expected],
                    )?
                };
                ensure!(n == 1, "ANNOTATION_CONFLICT");
                tx.execute("INSERT INTO annotation_revisions(project,revision) VALUES(?,1) ON CONFLICT(project) DO UPDATE SET revision=revision+1", [project])?;
                let annotation_revision: i64 = tx.query_row(
                    "SELECT revision FROM annotation_revisions WHERE project=?",
                    [project],
                    |r| r.get(0),
                )?;
                tx.commit()?;
                Ok(
                    serde_json::json!({"id":id,"version":expected+1,"annotation_revision":annotation_revision,"action":action}),
                )
            }
            "list" => {
                let revision: i64 = tx
                    .query_row(
                        "SELECT revision FROM annotation_revisions WHERE project=?",
                        [project],
                        |r| r.get(0),
                    )
                    .optional()?
                    .unwrap_or(0);
                let mut q = tx.prepare(
                    "SELECT id,symbol,body,version,anchor FROM annotations WHERE project=? ORDER BY id LIMIT 10001",
                )?;
                let rows = q
                    .query_map([project], |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, i64>(3)?,
                            r.get::<_, String>(4)?,
                        ))
                    })?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                drop(q);
                ensure!(rows.len() <= 10000, "ANNOTATION_LIST_BUDGET");
                let rows = rows.into_iter().map(|(id,symbol,text,version,anchor)|{
                    let anchor = if anchor.is_empty(){serde_json::Value::Null}else{serde_json::from_str(&anchor)?};
                    Ok(serde_json::json!({"id":id,"symbol":symbol,"text":text,"version":version,"annotation_revision":revision,"anchor":anchor}))
                }).collect::<Result<Vec<_>>>()?;
                tx.commit()?;
                Ok(serde_json::Value::Array(rows))
            }
            _ => anyhow::bail!("INVALID_ANNOTATION_ACTION"),
        }
    }
    pub fn gc(&self, execute: bool) -> Result<serde_json::Value> {
        self.gc_policy(execute, None, 0, 0, 0)
    }
    pub fn gc_policy(
        &self,
        execute: bool,
        project: Option<&str>,
        keep: usize,
        max_age_days: u64,
        max_bytes: u64,
    ) -> Result<serde_json::Value> {
        let mut guard = self.coord.lock().unwrap();
        // A task may take up to one hour. Keep its staged bytes for another hour,
        // then let ordinary reachability reclaim abandoned captures.
        guard
            .staged_blobs
            .retain(|_, at| now().saturating_sub(*at) < 7200);
        let mut c = self.catalog()?;
        let mut protected: BTreeSet<String> = guard.readers.keys().cloned().collect();
        let mut q = c.prepare("SELECT id,body FROM revisions ORDER BY rowid DESC")?;
        let all = q
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        drop(q);
        let mut policies = BTreeMap::<String, usize>::new();
        let mut size = 0u64;
        let mut sizes = BTreeMap::new();
        let mut recent = Vec::new();
        for (id, body) in &all {
            let r: Revision = serde_json::from_str(body)?;
            let bytes =
                std::fs::metadata(self.home.join("revisions").join(id).join("facts.db"))?.len();
            sizes.insert(id.clone(), bytes);
            if project.is_some_and(|p| p != r.project) {
                protected.insert(id.clone());
                continue;
            }
            size += bytes;
            let rank = policies.entry(r.target.clone()).or_default();
            if *rank < keep {
                protected.insert(id.clone());
            } else if max_age_days > 0
                && now().saturating_sub(r.created) < max_age_days.saturating_mul(86400)
            {
                recent.push(id.clone());
            }
            *rank += 1;
        }
        let mut expired = BTreeSet::new();
        let mut q =
            c.prepare("SELECT kind,id,project,body FROM objects WHERE kind IN ('page','report')")?;
        for row in q.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })? {
            let (kind, id, p, body) = row?;
            if project.is_some_and(|selected| selected != p) {
                continue;
            }
            let value: serde_json::Value = serde_json::from_str(&body)?;
            let created = value["created"].as_u64().unwrap_or(now());
            let ttl = if kind == "page" {
                86400
            } else {
                max_age_days.max(30).saturating_mul(86400)
            };
            if now().saturating_sub(created) >= ttl {
                expired.insert((kind, id));
            }
        }
        drop(q);
        for sql in [
            "SELECT revision FROM heads",
            "SELECT revision FROM commits JOIN tasks ON commits.task=tasks.id WHERE tasks.state!='terminal' AND tasks.state!='expired'",
        ] {
            let mut q = c.prepare(sql)?;
            for r in q.query_map([], |r| r.get::<_, String>(0))? {
                protected.insert(r?);
            }
        }
        let mut q = c.prepare("SELECT kind,id,revision FROM refs")?;
        for row in q.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })? {
            let (k, id, r) = row?;
            if !expired.contains(&(k, id)) {
                protected.insert(r);
            }
        }
        drop(q);
        let mut soft: BTreeSet<_> = recent.into_iter().collect();
        if max_bytes > 0 && size > max_bytes {
            for (id, _) in all.iter().rev() {
                if size <= max_bytes {
                    break;
                }
                if !protected.contains(id) {
                    soft.remove(id);
                    size = size.saturating_sub(*sizes.get(id).unwrap_or(&0));
                }
            }
        }
        protected.extend(soft);
        let removals: Vec<_> = all
            .iter()
            .map(|(id, _)| id.clone())
            .filter(|id| !protected.contains(id))
            .collect();
        let retained_bytes: u64 = all
            .iter()
            .filter(|(id, _)| protected.contains(id))
            .filter_map(|(id, body)| {
                let r: Revision = serde_json::from_str(body).ok()?;
                if project.is_none_or(|p| p == r.project) {
                    sizes.get(id).copied()
                } else {
                    None
                }
            })
            .sum();
        if execute {
            let tx = c.transaction()?;
            for (k, id) in &expired {
                tx.execute("DELETE FROM refs WHERE kind=? AND id=?", params![k, id])?;
                tx.execute("DELETE FROM objects WHERE kind=? AND id=?", params![k, id])?;
            }
            // Task identities and terminal decisions remain; only replay payloads expire.
            let cutoff = now().saturating_sub(86400) as i64;
            tx.execute("DELETE FROM events WHERE task IN (SELECT id FROM tasks WHERE state='terminal' AND created<?)",[cutoff])?;
            tx.execute(
                "UPDATE tasks SET state='expired' WHERE state='terminal' AND created<?",
                [cutoff],
            )?;
            tx.commit()?;
            for (kind, id) in &expired {
                if kind == "page" {
                    self.remove_page(id)?;
                }
            }
            self.remove_orphan_pages(&c)?;
        }
        let mut blob_roots: BTreeSet<String> = guard.staged_blobs.keys().cloned().collect();
        for id in &protected {
            if let Ok(r) = self.revision_unlocked(id) {
                blob_roots.extend(r.manifest.files.into_iter().map(|s| s.hash));
            }
        }
        let mut q = c.prepare("SELECT body FROM objects WHERE kind='snapshot'")?;
        for row in q.query_map([], |r| r.get::<_, String>(0))? {
            let v: serde_json::Value = serde_json::from_str(&row?)?;
            if let Some(files) = v["manifest"]["files"].as_array() {
                for s in files {
                    if let Some(h) = s["hash"].as_str() {
                        blob_roots.insert(h.into());
                    }
                }
            }
        }
        drop(q);
        let mut blobs = Vec::new();
        for entry in std::fs::read_dir(self.home.join("blobs"))? {
            let e = entry?;
            let h = e.file_name().to_string_lossy().into_owned();
            if h.len() == 64 && !blob_roots.contains(&h) {
                blobs.push(h);
            }
        }
        blobs.sort();
        if execute {
            for id in &removals {
                let tx = c.transaction()?;
                tx.execute(
                    "INSERT OR IGNORE INTO cleanup_pending(kind,id,created) VALUES('revision',?,?)",
                    params![id, now() as i64],
                )?;
                tx.execute("DELETE FROM revisions WHERE id=?", [id])?;
                tx.commit()?;
                let p = self.home.join("revisions").join(id);
                if p.exists() {
                    std::fs::remove_dir_all(p)?;
                }
                c.execute(
                    "DELETE FROM cleanup_pending WHERE kind='revision' AND id=?",
                    [id],
                )?;
            }
            for h in &blobs {
                std::fs::remove_file(self.home.join("blobs").join(h))?;
            }
        }
        Ok(
            serde_json::json!({"execute":execute,"protected":protected,"revisions":removals,"blobs":blobs,"expired_objects":expired,"retained_graph_bytes":retained_bytes,"budget_unmet":max_bytes>0&&retained_bytes>max_bytes}),
        )
    }

    pub fn recover(&self, execute: bool) -> Result<serde_json::Value> {
        let _guard = self.coord.lock().unwrap();
        let c = self.catalog()?;
        let mut orphan = Vec::new();
        let mut pending = Vec::new();
        let mut q =
            c.prepare("SELECT id FROM cleanup_pending WHERE kind='revision' ORDER BY id")?;
        for row in q.query_map([], |r| r.get::<_, String>(0))? {
            let id = row?;
            ensure!(
                id.starts_with("rev_") && safe_path(&id),
                "INVALID_CLEANUP_ID"
            );
            pending.push(id.clone());
            if execute {
                let path = self.home.join("revisions").join(&id);
                if path.exists() {
                    std::fs::remove_dir_all(path)?;
                }
                c.execute(
                    "DELETE FROM cleanup_pending WHERE kind='revision' AND id=?",
                    [id],
                )?;
            }
        }
        drop(q);
        for dir in ["candidates", "revisions"] {
            for e in std::fs::read_dir(self.home.join(dir))? {
                let e = e?;
                let id = e.file_name().to_string_lossy().into_owned();
                if !(id.starts_with("rev_") || dir == "candidates" && id.starts_with("page-spool-"))
                    || dir == "candidates" && _guard.staged_candidates.contains(&id)
                {
                    continue;
                }
                let known = dir == "revisions"
                    && c.query_row("SELECT 1 FROM revisions WHERE id=?", [&id], |_| Ok(()))
                        .optional()?
                        .is_some();
                if !known {
                    orphan.push(format!("{dir}/{id}"));
                    if execute {
                        if e.file_type()?.is_dir() {
                            std::fs::remove_dir_all(e.path())?;
                        } else {
                            std::fs::remove_file(e.path())?;
                        }
                    }
                }
            }
        }
        Ok(serde_json::json!({"execute":execute,"orphans":orphan,"pending":pending}))
    }
}
impl ReadContext {
    fn fact_predicate(&self, f: &FactFilter) -> Result<(String, Vec<String>)> {
        let mut sql = "1=1".to_owned();
        let mut values = Vec::new();
        let selectors = f.signature.is_some()
            || f.package.is_some()
            || f.package_prefix.is_some()
            || f.visibility.is_some()
            || f.synthetic.is_some()
            || f.entity_kind.is_some();
        let projected = selectors && f.kinds != [FactKind::Facet] && self.connection.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='declaration_projection' AND type='table')", [], |r| r.get::<_,bool>(0))?;
        if projected {
            sql.push_str(" AND f.id IN(SELECT p.id FROM declaration_projection p WHERE 1=1");
            for (column, value) in [
                ("p.signature", &f.signature),
                ("p.package", &f.package),
                ("p.visibility", &f.visibility),
            ] {
                if let Some(value) = value {
                    sql.push_str(&format!(" AND {column}=?"));
                    values.push(value.clone());
                }
            }
            if let Some(prefix) = &f.package_prefix {
                sql.push_str(" AND (p.package=? OR instr(p.package,?)=1)");
                values.extend([prefix.clone(), format!("{prefix}.")]);
            }
            if let Some(synthetic) = f.synthetic {
                sql.push_str(" AND p.synthetic=CAST(? AS INTEGER)");
                values.push(if synthetic { "1" } else { "0" }.into());
            }
            if let Some(kind) = &f.entity_kind {
                sql.push_str(
                    if matches!(
                        kind.as_str(),
                        "type"
                            | "callable"
                            | "value"
                            | "artifact"
                            | "component"
                            | "config_entity"
                            | "entity"
                    ) {
                        " AND p.entity_kind=?"
                    } else {
                        " AND p.language_kind=?"
                    },
                );
                values.push(kind.clone());
            }
            sql.push(')');
        }
        for (column, value) in [
            ("f.parent", &f.parent),
            ("f.target", &f.target),
            ("f.relation", &f.relation),
            ("f.name", &f.name),
            ("f.source", &f.source),
            ("f.world", &f.world),
            ("json_extract(f.body,'$.signature')", &f.signature),
            (
                "COALESCE(json_extract(f.body,'$.attributes.package'),json_extract(f.body,'$.attributes.namespace'),'')",
                &f.package,
            ),
            (
                "COALESCE(json_extract(f.body,'$.attributes.visibility'),'')",
                &f.visibility,
            ),
        ] {
            if projected && (column.starts_with("json_extract") || column.starts_with("COALESCE")) {
                continue;
            }
            if let Some(value) = value {
                sql.push_str(&format!(" AND {column}=?"));
                values.push(value.clone());
            }
        }
        if let Some(prefix) = &f.package_prefix
            && !projected
        {
            sql.push_str(" AND (json_extract(f.body,'$.attributes.package')=? OR instr(json_extract(f.body,'$.attributes.package'),?)=1)");
            values.extend([prefix.clone(), format!("{prefix}.")]);
        }
        if let Some(synthetic) = f.synthetic
            && !projected
        {
            sql.push_str(
                " AND COALESCE(json_extract(f.body,'$.attributes.synthetic'),0)=CAST(? AS INTEGER)",
            );
            values.push(if synthetic { "1" } else { "0" }.into());
        }
        if let Some(kind) = &f.entity_kind
            && !projected
        {
            if matches!(
                kind.as_str(),
                "type"
                    | "callable"
                    | "value"
                    | "artifact"
                    | "component"
                    | "config_entity"
                    | "entity"
            ) {
                sql.push_str(" AND COALESCE(json_extract(f.body,'$.attributes.entity_kind'),CASE json_extract(f.body,'$.attributes.element_kind') WHEN 'CLASS' THEN 'type' WHEN 'INTERFACE' THEN 'type' WHEN 'ENUM' THEN 'type' WHEN 'ANNOTATION' THEN 'type' WHEN 'RECORD' THEN 'type' WHEN 'METHOD' THEN 'callable' WHEN 'CONSTRUCTOR' THEN 'callable' WHEN 'LAMBDA' THEN 'callable' WHEN 'METHOD_REF' THEN 'callable' WHEN 'FIELD' THEN 'value' WHEN 'ENUM_CONSTANT' THEN 'value' WHEN 'BEAN' THEN 'component' WHEN 'ARTIFACT' THEN 'artifact' ELSE 'entity' END)=?");
            } else {
                sql.push_str(" AND (json_extract(f.body,'$.attributes.element_kind')=? OR json_extract(f.body,'$.attributes.entity_kind')=?)");
                values.push(kind.clone());
            }
            values.push(kind.clone());
        }
        if let Some(annotation) = &f.annotation {
            sql.push_str(" AND EXISTS(SELECT 1 FROM facts a WHERE a.kind='facet' AND a.relation='java.annotation' AND a.parent=f.id AND (a.name=? OR json_extract(a.body,'$.attributes.annotation')=?))");
            values.extend([annotation.clone(), annotation.clone()]);
        }
        for (column, ids) in [
            ("f.id", f.ids.clone()),
            ("f.parent", f.parents.clone()),
            ("f.target", f.targets.clone()),
            (
                "f.kind",
                f.kinds.iter().map(|kind| kind.name().to_owned()).collect(),
            ),
        ] {
            if !ids.is_empty() {
                ensure!(ids.len() <= 1000, "SELECTOR_BUDGET");
                sql.push_str(&format!(
                    " AND {column} IN ({})",
                    vec!["?"; ids.len()].join(",")
                ));
                values.extend(ids);
            }
        }
        if !f.sources.is_empty() {
            // One JSON bind supports a large captured manifest without exceeding SQLite limits.
            sql.push_str(" AND f.source IN (SELECT value FROM json_each(?))");
            values.push(serde_json::to_string(&f.sources)?);
        }
        if f.module.is_some() || f.scope.is_some() {
            let projected: bool = self.connection.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='source_units' AND type='table')", [], |r| r.get(0))?;
            let sources = if projected {
                "source_units".to_owned()
            } else {
                values.push(serde_json::to_string(&self.revision.manifest.files)?);
                "(SELECT json_extract(value,'$.path') AS path,json_extract(value,'$.world') AS world,json_extract(value,'$.module') AS module,json_extract(value,'$.scope') AS scope,json_extract(value,'$.auxiliary') AS auxiliary FROM json_each(?))".to_owned()
            };
            sql.push_str(&format!(" AND EXISTS(SELECT 1 FROM {sources} s WHERE s.auxiliary=0 AND ((s.path=f.source AND s.world=f.world) OR (f.kind='external_symbol' AND EXISTS(SELECT 1 FROM facts observation WHERE observation.target=f.id AND observation.source=s.path AND observation.world=s.world AND observation.kind IN ('call_target','reference','relation'))))"));
            for (column, value) in [("s.module", &f.module), ("s.scope", &f.scope)] {
                if let Some(value) = value {
                    sql.push_str(&format!(" AND {column}=?"));
                    values.push(value.clone());
                }
            }
            sql.push(')');
        }
        if projected && (f.kinds.is_empty() || f.kinds.contains(&FactKind::Facet)) {
            // Existing immutable revisions project only declarations. Apply the
            // same selectors to public configuration facets without rebuilding
            // their authoritative graph or widening declaration counts.
            let mut configuration = f.clone();
            configuration.kinds = vec![FactKind::Facet];
            let (fallback, extra) = self.fact_predicate(&configuration)?;
            sql = format!(
                "({sql} OR ({fallback} AND COALESCE(json_extract(f.body,'$.signature'),'')<>'' AND f.relation IN ('java.bean','java.sofa','java.spring.xml','java.spring.mvc','java.spring.handler')))"
            );
            values.extend(extra);
        }
        Ok((sql, values))
    }
}
impl FactReader for ReadContext {
    fn annotation_meta_edges(
        &self,
        names: &[String],
    ) -> std::result::Result<Vec<AnnotationMetaEdge>, String> {
        (|| -> Result<_> {
            ensure!(!names.is_empty() && names.len()<=500,"ANNOTATION_META_KEY_BUDGET");
            let columns=["raw_name","language","mechanism","resolution","upstream_producer"];
            let sql=format!("SELECT json_extract(body,'$.attributes.annotation'),json_extract(body,'$.attributes.meta_annotation'),{} FROM facts WHERE kind='facet' AND relation='java.annotation.meta' AND json_extract(body,'$.attributes.annotation') IN ({}) AND json_extract(body,'$.attributes.meta_annotation') IS NOT NULL GROUP BY json_extract(body,'$.attributes.annotation'),json_extract(body,'$.attributes.meta_annotation') LIMIT 10001",
                columns.iter().map(|key|format!("min(json_extract(body,'$.attributes.{key}'))")).collect::<Vec<_>>().join(","),vec!["?";names.len()].join(","));
            let mut statement=self.connection.prepare(&sql)?;
            let mut rows=statement.query(rusqlite::params_from_iter(names))?;
            let mut output=Vec::new();
            while let Some(row)=rows.next()? {
                ensure!(output.len()<10000,"QUERY_WORK_BUDGET");
                let mut attributes=BTreeMap::new();
                for (index,key) in columns.iter().enumerate() {
                    if let Some(value)=row.get::<_,Option<String>>(index+2)? { attributes.insert((*key).into(),serde_json::json!(value)); }
                }
                output.push(AnnotationMetaEdge { annotation:row.get(0)?,meta_annotation:row.get(1)?,attributes });
            }
            Ok(output)
        })().map_err(|error|error.to_string())
    }
    fn overview_groups(
        &self,
        filter: &FactFilter,
        section: &str,
        direction: &str,
        relations: &[String],
    ) -> std::result::Result<Vec<serde_json::Value>, String> {
        (|| -> Result<_> {
            let mut owned = filter.clone();
            if section == "dependencies" {
                owned.package = None;
                owned.package_prefix = None;
            }
            let (mut predicate, mut values) = self.fact_predicate(&owned)?;
            let (joins, columns, grouping) = match section {
                "modules" => ("JOIN source_units s ON s.path=f.source AND s.world=f.world AND s.auxiliary=0", "s.module", "s.module"),
                "packages" => ("JOIN declaration_projection p ON p.id=f.id", "p.package", "p.package"),
                "dependencies" => (
                    "LEFT JOIN facts site ON f.kind='call_target' AND site.id=f.parent JOIN declaration_projection p ON p.id=CASE WHEN f.kind='call_target' THEN site.parent ELSE f.parent END JOIN declaration_projection t ON t.id=f.target",
                    "p.package,t.package,f.relation", "p.package,t.package,f.relation"),
                _ => bail!("OVERVIEW_SECTION_INVALID"),
            };
            if section == "packages" {
                predicate.push_str(" AND f.kind='declaration'");
            } else if section == "dependencies" {
                predicate.push_str(" AND f.kind IN ('call_target','reference','relation') AND f.relation<>'contains' AND p.package<>t.package");
                if !relations.is_empty() {
                    ensure!(relations.len() <= 100, "OVERVIEW_RELATION_BUDGET");
                    predicate.push_str(&format!(" AND f.relation IN ({})", vec!["?"; relations.len()].join(",")));
                    values.extend_from_slice(relations);
                }
                if filter.package.is_some() || filter.package_prefix.is_some() {
                    let mut endpoints = Vec::new();
                    let columns: &[&str] = match direction {
                        "in" => &["t.package"],
                        "out" => &["p.package"],
                        "both" => &["p.package", "t.package"],
                        _ => bail!("OVERVIEW_DIRECTION_INVALID"),
                    };
                    for column in columns {
                        if let Some(package) = &filter.package {
                            endpoints.push(format!("{column}=?"));
                            values.push(package.clone());
                        } else if let Some(prefix) = &filter.package_prefix {
                            endpoints.push(format!("({column}=? OR instr({column},?)=1)"));
                            values.extend([prefix.clone(), format!("{prefix}.")]);
                        }
                    }
                    predicate.push_str(&format!(" AND ({})", endpoints.join(" OR ")));
                }
            }
            let mut statement = self.connection.prepare(&format!(
                "SELECT {columns},COUNT(*) FROM facts f {joins} WHERE {predicate} GROUP BY {grouping} ORDER BY {grouping} LIMIT 10001"
            ))?;
            let rows = statement.query_map(rusqlite::params_from_iter(values), |row| {
                Ok(if section == "dependencies" {
                    serde_json::json!({"package":row.get::<_,String>(0)?,"target_package":row.get::<_,String>(1)?,"relation":row.get::<_,String>(2)?,"count":row.get::<_,i64>(3)?})
                } else {
                    serde_json::json!({if section == "modules" { "module" } else { "package" }:row.get::<_,String>(0)?,"count":row.get::<_,i64>(1)?})
                })
            })?.collect::<rusqlite::Result<Vec<_>>>()?;
            ensure!(rows.len() <= 10000, "OVERVIEW_GROUP_BUDGET");
            Ok(rows)
        })().map_err(|error| error.to_string())
    }
    fn reverse_file_dependencies(
        &self,
        targets: &[FileKey],
        source_world: Option<&str>,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<FileDependency>, String> {
        (|| -> Result<_> {
            ensure!(targets.len() <= 500 && (1..=1024).contains(&limit), "INVALID_DEPENDENCY_BATCH");
            if targets.is_empty() { return Ok(Vec::new()); }
            let available: bool = self.connection.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='file_dependencies' AND type='table')", [], |r| r.get(0))?;
            ensure!(available, "FILE_DEPENDENCIES_UNAVAILABLE");
            let keys = serde_json::to_string(targets)?;
            let mut statement = self.connection.prepare("SELECT id,source_world,source_file,target_world,target_file FROM file_dependencies WHERE (target_world,target_file) IN (SELECT json_extract(value,'$.world'),json_extract(value,'$.path') FROM json_each(?)) AND (? IS NULL OR source_world=?) AND id>? ORDER BY id LIMIT ?")?;
            Ok(statement.query_map(params![keys, source_world, source_world, after, limit as i64], |r| Ok(FileDependency {
                id:r.get(0)?, source_world:r.get(1)?, source_file:r.get(2)?, target_world:r.get(3)?, target_file:r.get(4)?
            }))?.collect::<rusqlite::Result<Vec<_>>>()?)
        })().map_err(|error| error.to_string())
    }

    fn unresolved_consumers(
        &self,
        syntax_targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, String> {
        (|| -> Result<_> {
            ensure!(syntax_targets.len() <= 500 && (1..=1024).contains(&limit), "INVALID_DEPENDENCY_BATCH");
            if syntax_targets.is_empty() {return Ok(Vec::new());}
            let mut filter = filter.clone();
            filter.kinds = vec![FactKind::CallSite];
            let (predicate, mut values) = self.fact_predicate(&filter)?;
            values.extend_from_slice(syntax_targets);
            values.push(after.into());
            values.push(limit.to_string());
            let placeholders = vec!["?"; syntax_targets.len()].join(",");
            let mut statement = self.connection.prepare(&format!(
                "SELECT f.body FROM facts f WHERE {predicate} AND f.kind='call_site' AND json_extract(f.body,'$.status')='unresolved' AND f.name IN ({placeholders}) AND f.id>? ORDER BY f.id LIMIT ?"
            ))?;
            let mut result = Vec::new();
            for row in statement.query_map(rusqlite::params_from_iter(values), |row| row.get::<_, String>(0))? {
                result.push(serde_json::from_str(&row?)?);
            }
            Ok(result)
        })().map_err(|error| error.to_string())
    }

    fn scan(
        &self,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, String> {
        (|| -> Result<_> {
            ensure!((1..=1024).contains(&limit), "INVALID_SCAN_BATCH");
            let (predicate, mut values) = self.fact_predicate(filter)?;
            values.push(after.into());
            values.push(limit.to_string());
            let mut statement = self.connection.prepare(&format!(
                "SELECT f.body FROM facts f WHERE {predicate} AND f.id>? ORDER BY f.id LIMIT ?"
            ))?;
            let mut result = Vec::new();
            for row in statement.query_map(rusqlite::params_from_iter(values), |row| {
                row.get::<_, String>(0)
            })? {
                result.push(serde_json::from_str(&row?)?);
            }
            Ok(result)
        })()
        .map_err(|error| error.to_string())
    }
    fn scan_symbols(
        &self,
        text: &str,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, String> {
        (|| -> Result<_> {
            ensure!((1..=1024).contains(&limit), "INVALID_SCAN_BATCH");
            let (predicate, values) = self.fact_predicate(filter)?;
            symbol_search::scan_scoped(&self.connection, text, &predicate, values, after, limit)
        })()
        .map_err(|error| error.to_string())
    }
    fn search_symbols_filtered(
        &self,
        text: &str,
        filter: &FactFilter,
    ) -> std::result::Result<Vec<Fact>, String> {
        (|| -> Result<_> {
            let (predicate, values) = self.fact_predicate(filter)?;
            symbol_search::select_scoped(&self.connection, text, &predicate, values)
        })()
        .map_err(|e| e.to_string())
    }
    fn count_by_kind(
        &self,
        filter: &FactFilter,
    ) -> std::result::Result<BTreeMap<String, u64>, String> {
        (|| -> Result<_> {
            let (predicate, values) = self.fact_predicate(filter)?;
            let mut statement = self.connection.prepare(&format!(
                "SELECT f.kind,COUNT(*) FROM facts f WHERE {predicate} GROUP BY f.kind"
            ))?;
            Ok(statement
                .query_map(rusqlite::params_from_iter(values), |row| {
                    Ok((row.get(0)?, row.get::<_, i64>(1)? as u64))
                })?
                .collect::<rusqlite::Result<BTreeMap<_, _>>>()?)
        })()
        .map_err(|error| error.to_string())
    }
    fn search_symbols(&self, text: &str) -> std::result::Result<Vec<Fact>, String> {
        symbol_search::select(&self.connection, text).map_err(|e| e.to_string())
    }
    fn manifest(&self) -> &Manifest {
        &self.revision.manifest
    }
    fn revision(&self) -> &Revision {
        &self.revision
    }
    fn select(&self, f: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
        (|| -> Result<Vec<Fact>> {
            let (predicate, values) = self.fact_predicate(f)?;
            let sql =
                format!("SELECT f.body FROM facts f WHERE {predicate} ORDER BY f.id LIMIT 100001");
            let mut q = self.connection.prepare(&sql)?;
            let mut out = Vec::new();
            for row in q.query_map(rusqlite::params_from_iter(values), |r| {
                r.get::<_, String>(0)
            })? {
                out.push(serde_json::from_str(&row?)?);
            }
            ensure!(out.len() <= MAX_FACTS, "QUERY_WORK_BUDGET");
            Ok(out)
        })()
        .map_err(|e| e.to_string())
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
        let next = baseline
            .reverse_file_dependencies(&keys, None, &main[0].id, 1)
            .unwrap();
        assert_eq!(next.len(), 1);
        assert_eq!(next[0].source_world, "test");
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
            next
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
    fn file_dependency_batches_use_the_reverse_index_above_the_global_budget() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        db.execute("WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<100003) INSERT INTO file_dependencies SELECT printf('%08d',n),'main',printf('Source%d.java',n),'main','Target.java' FROM sequence",[]).unwrap();
        let plan:Vec<String>=db.prepare("EXPLAIN QUERY PLAN SELECT id FROM file_dependencies WHERE (target_world,target_file) IN (SELECT json_extract(value,'$.world'),json_extract(value,'$.path') FROM json_each('[{\"world\":\"main\",\"path\":\"Target.java\"}]')) AND id>'00000083' ORDER BY id LIMIT 83").unwrap().query_map([],|r|r.get(3)).unwrap().collect::<rusqlite::Result<_>>().unwrap();
        assert!(
            plan.iter()
                .any(|line| line.contains("file_dependencies_reverse")),
            "{plan:?}"
        );
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        let rows = reader
            .reverse_file_dependencies(
                &[FileKey {
                    world: "main".into(),
                    path: "Target.java".into(),
                }],
                Some("main"),
                "00000083",
                83,
            )
            .unwrap();
        assert_eq!(rows.len(), 83);
        assert_eq!(rows[0].id, "00000084");
        assert_eq!(rows[82].id, "00000166");
        assert!(
            reader
                .reverse_file_dependencies(&[], None, "", 1025)
                .is_err()
        );
    }

    #[test]
    fn reverse_dependencies_keep_cycles_scopes_occurrences_and_atomic_owner_replacement() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let make = |id: &str, kind: &str, owner: &str, world: &str, parent: &str, target: &str| {
            serde_json::from_value::<Fact>(serde_json::json!({"id":id,"kind":kind,
                "owner":{"world":world,"producer":"mock","kind":"context","id":owner},
                "parent":parent,"target":target,"origin":"mock"}))
            .unwrap()
        };
        let a = make("a", "declaration", "a-owner", "main", "", "");
        let b = make("b", "declaration", "b-owner", "main", "", "");
        let test = make("test", "declaration", "test-owner", "test", "", "");
        let mut initial = result();
        initial.facts = vec![
            a.clone(),
            b.clone(),
            test,
            make("ab-1", "dependency", "a-owner", "main", "a", "b"),
            make("ab-2", "reference", "a-owner", "main", "a", "b"),
            make("ba", "relation", "b-owner", "main", "b", "a"),
            make("test-b", "call_target", "test-owner", "test", "test", "b"),
        ];
        let first = store
            .publish(revision(), &initial, "", "dependency-initial")
            .unwrap();
        let baseline = store.read(&first.id).unwrap();
        let scoped = FactFilter {
            world: Some("main".into()),
            ..Default::default()
        };
        let incoming = baseline
            .reverse_dependencies(&["b".into()], &scoped, "", 1024)
            .unwrap();
        assert_eq!(
            incoming
                .iter()
                .map(|fact| fact.id.as_str())
                .collect::<Vec<_>>(),
            ["ab-1", "ab-2"]
        );
        assert_eq!(
            baseline
                .reverse_dependencies(&["a".into()], &scoped, "", 1024)
                .unwrap()[0]
                .id,
            "ba"
        );
        let mut missing = make("missing", "call_site", "b-owner", "main", "b", "");
        missing.name = "A.read".into();
        missing.status = "unresolved".into();
        let mut delta = result();
        delta.mode = "delta".into();
        delta.baseline = first.id.clone();
        delta.replacements = vec![a.owner, b.owner.clone()];
        delta.facts = vec![b, missing];
        let second = store
            .publish(revision(), &delta, &first.id, "dependency-deletion")
            .unwrap();
        let current = store.read(&second.id).unwrap();
        assert!(
            current
                .reverse_dependencies(&["a".into(), "b".into()], &scoped, "", 1024)
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            current
                .unresolved_consumers(&["A.read".into()], &scoped, "", 1024)
                .unwrap()[0]
                .id,
            "missing"
        );
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
                .id,
            "test-b"
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
    fn unresolved_consumers_read_a_bounded_batch_above_the_global_select_budget() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        let body = serde_json::json!({"id":"template","kind":"call_site","owner":{"world":"w","producer":"mock","kind":"context","id":"ctx"},"name":"missing()","status":"unresolved","origin":"mock"}).to_string();
        db.execute("WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<100003) INSERT INTO facts SELECT printf('%08d',n),'call_site','owner','','','','missing()','','w',json_set(?,'$.id',printf('%08d',n)) FROM sequence", [body]).unwrap();
        schema::prepare_queries(&db).unwrap();
        let plan: Vec<String> = db.prepare("EXPLAIN QUERY PLAN SELECT id FROM facts WHERE kind='call_site' AND json_extract(body,'$.status')='unresolved' AND name='missing()' AND id>'00000083' ORDER BY id LIMIT 83").unwrap()
            .query_map([], |row| row.get(3)).unwrap().collect::<rusqlite::Result<_>>().unwrap();
        assert!(
            plan.iter()
                .any(|line| line.contains("fact_unresolved_consumers")),
            "{plan:?}"
        );
        assert!(
            !plan.iter().any(|line| line.contains("TEMP B-TREE")),
            "{plan:?}"
        );
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        let rows = reader
            .unresolved_consumers(
                &["missing()".into()],
                &FactFilter::default(),
                "00000083",
                83,
            )
            .unwrap();
        assert_eq!(rows.len(), 83);
        assert_eq!(rows[0].id, "00000084");
        assert_eq!(rows[82].id, "00000166");
        assert!(
            reader
                .unresolved_consumers(&["absent()".into()], &FactFilter::default(), "", 83)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn keyset_search_enumerates_more_than_one_hundred_thousand_without_duplicate_ids() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        let body = serde_json::json!({"id":"template","kind":"declaration","owner":{"world":"w","producer":"mock","kind":"source","id":"A.java"},"source":"A.java","name":"Same","signature":"Same","origin":"mock"}).to_string();
        db.execute("WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<100003) INSERT INTO facts SELECT printf('%08d',n),'declaration','owner','','','','Same','A.java','w',json_set(?,'$.id',printf('%08d',n)) FROM sequence", [body]).unwrap();
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        let mut after = String::new();
        let mut total = 0;
        loop {
            let batch = reader
                .scan_symbols("Same", &FactFilter::default(), &after, 1024)
                .unwrap();
            assert!(batch.len() <= 1024);
            if batch.is_empty() {
                break;
            }
            for fact in batch {
                total += 1;
                assert_eq!(fact.id, format!("{total:08}"));
                after = fact.id;
            }
        }
        assert_eq!(total, 100003);
        assert!(
            reader
                .scan(&FactFilter::default(), &after, 512)
                .unwrap()
                .is_empty()
        );
        assert!(
            reader
                .scan_symbols("Same", &FactFilter::default(), "", 1025)
                .is_err()
        );
    }
    #[test]
    fn page_spool_survives_recovery_pins_revision_and_cleans_up_on_expiry() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let first = store.publish(revision(), &result(), "", "first").unwrap();
        let mut spool = store.page_spool().unwrap();
        spool.append(&[serde_json::json!({"id":"saved"})]).unwrap();
        store.recover(true).unwrap();
        spool.append(&[serde_json::json!({"id":"second"})]).unwrap();
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
            store.page_records(id, "p", 1, 1).unwrap()[0]["id"],
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
                .append(&[serde_json::json!({"id":"cancelled"})])
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
    #[test]
    fn meta_catalogue_groups_more_than_the_fact_budget_before_returning_edges() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        let body=serde_json::json!({"id":"template","kind":"facet","owner":{"world":"w","producer":"java","kind":"source","id":"A.java"},"source":"A.java","relation":"java.annotation.meta","attributes":{"annotation":"A","meta_annotation":"B","raw_name":"z","resolution":"exact"}}).to_string();
        db.execute("WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<100001) INSERT INTO facts SELECT printf('%08d',n),'facet','o','','','java.annotation.meta','','A.java','w',json_set(?,'$.id',printf('%08d',n)) FROM sequence",[body]).unwrap();
        db.execute(
            "UPDATE facts SET body=json_set(body,'$.attributes.raw_name','a') WHERE id='00000001'",
            [],
        )
        .unwrap();
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        assert_eq!(
            reader.annotation_meta_edges(&["A".into()]).unwrap().len(),
            1,
            "legacy revisions group before the fact budget without new indexes"
        );
        schema::prepare_queries(&reader.connection).unwrap();
        // The dense legacy case above legitimately scans every matching row.
        // Exercise the directed plan separately with many other annotation keys.
        reader.connection.execute("UPDATE facts SET body=json_set(body,'$.attributes.annotation',printf('Other%04d',CAST(id AS INTEGER)%1000)) WHERE id<>'00000001'",[]).unwrap();
        reader.connection.execute_batch("ANALYZE").unwrap();
        let plan=reader.connection.prepare("EXPLAIN QUERY PLAN SELECT json_extract(body,'$.attributes.annotation'),json_extract(body,'$.attributes.meta_annotation'),min(json_extract(body,'$.attributes.raw_name')) FROM facts WHERE kind='facet' AND relation='java.annotation.meta' AND json_extract(body,'$.attributes.annotation')='A' GROUP BY json_extract(body,'$.attributes.annotation'),json_extract(body,'$.attributes.meta_annotation')").unwrap().query_map([],|row|row.get::<_,String>(3)).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap().join(" ");
        assert!(plan.contains("fact_annotation_meta_pair"), "{plan}");
        // SQLite may group the already restricted rows in a temporary B-tree.
        // That does not imply a full catalogue scan.
        let edges = reader.annotation_meta_edges(&["A".into()]).unwrap();
        assert_eq!(edges.len(), 1);
        assert_eq!(edges[0].meta_annotation, "B");
        assert_eq!(edges[0].attributes["raw_name"], "a");
        assert!(
            reader
                .annotation_meta_edges(&["Other".into()])
                .unwrap()
                .is_empty()
        );
        assert!(reader.annotation_meta_edges(&[]).is_err());
    }
    #[test]
    fn configuration_selectors_use_captured_scope_without_declaration_projection_rows() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        for (id, relation, world, source) in [
            ("main-bean", "java.bean", "main", "main.xml"),
            ("test-bean", "java.bean", "test", "test.xml"),
            ("helper", "java.binding", "main", "main.xml"),
        ] {
            let body = serde_json::json!({"id":id,"kind":"facet","owner":{"world":world,"producer":"framework","kind":"source","id":source},"name":"api","signature":"bean:api","source":source,"relation":relation,"attributes":{"entity_kind":"component","element_kind":"BEAN"}}).to_string();
            db.execute(
                "INSERT INTO facts VALUES(?,'facet','o','','',?,'api',?,?,?)",
                params![id, relation, source, world, body],
            )
            .unwrap();
        }
        db.execute_batch("INSERT INTO source_units VALUES('main.xml','main','app','RESOURCE','hash',0); INSERT INTO source_units VALUES('test.xml','test','app','RESOURCE','hash',0);").unwrap();
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        let filter = FactFilter {
            signature: Some("bean:api".into()),
            entity_kind: Some("component".into()),
            world: Some("main".into()),
            module: Some("app".into()),
            scope: Some("RESOURCE".into()),
            ..Default::default()
        };
        assert_eq!(
            reader
                .select(&filter)
                .unwrap()
                .iter()
                .map(|f| f.id.as_str())
                .collect::<Vec<_>>(),
            ["main-bean"]
        );
        assert_eq!(
            reader.search_symbols_filtered("api", &filter).unwrap()[0].id,
            "main-bean"
        );
        assert!(
            reader
                .select(&FactFilter {
                    module: Some("other".into()),
                    ..filter.clone()
                })
                .unwrap()
                .is_empty()
        );
        assert!(
            reader
                .select(&FactFilter {
                    entity_kind: Some("type".into()),
                    ..filter.clone()
                })
                .unwrap()
                .is_empty()
        );
        assert!(
            reader
                .select(&FactFilter {
                    scope: Some("MAIN".into()),
                    ..filter
                })
                .unwrap()
                .is_empty()
        );
    }
    #[test]
    fn filtered_symbol_search_applies_scope_before_work_budget() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        let body=serde_json::to_string(&serde_json::from_value::<Fact>(serde_json::json!({"id":"needle","kind":"declaration","name":"Api","signature":"p.Api","source":"B.java","owner":{"world":"w","producer":"mock","kind":"source","id":"B.java"},"attributes":{"package":"p","element_kind":"CLASS","visibility":"public","synthetic":false}})).unwrap()).unwrap();
        db.execute(
            "INSERT INTO facts VALUES('needle','declaration','o','','','','Api','B.java','w',?)",
            [body],
        )
        .unwrap();
        db.execute_batch("WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<100001) INSERT INTO facts SELECT printf('%08d',n),'declaration','o','','','','Api','A.java','other','{}' FROM sequence; INSERT INTO source_units VALUES('B.java','w','selected','MAIN','hash',0); INSERT INTO source_units VALUES('A.java','other','other','MAIN','hash',0);").unwrap();
        db.execute("INSERT INTO facts VALUES('ann','facet','o','needle','','java.annotation','','B.java','w',?)", [r#"{"id":"ann","kind":"facet","owner":{"world":"w","producer":"javac","kind":"source","id":"B.java"},"parent":"needle","relation":"java.annotation","attributes":{"annotation":"p.Mark"}}"#]).unwrap();
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        let filter = FactFilter {
            module: Some("selected".into()),
            package: Some("p".into()),
            entity_kind: Some("type".into()),
            visibility: Some("public".into()),
            synthetic: Some(false),
            ..Default::default()
        };
        let hits = reader.search_symbols_filtered("Api", &filter).unwrap();
        assert_eq!(
            hits.iter().map(|f| f.id.as_str()).collect::<Vec<_>>(),
            ["needle"]
        );
        assert_eq!(
            reader
                .search_symbols_filtered(
                    "Api",
                    &FactFilter {
                        annotation: Some("p.Mark".into()),
                        ..filter.clone()
                    }
                )
                .unwrap()
                .len(),
            1
        );
        assert!(
            reader
                .search_symbols_filtered(
                    "Api",
                    &FactFilter {
                        package: Some("wrong".into()),
                        ..filter
                    }
                )
                .unwrap()
                .is_empty()
        );
    }
    use super::*;
    #[test]
    fn large_aggregate_counts_do_not_decode_bodies_or_inherit_select_limit() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        db.execute_batch("WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<100001) INSERT INTO facts SELECT printf('%08d',n),'declaration','owner','','','','Api','A.java','w','{}' FROM sequence;
            INSERT INTO source_units VALUES('A.java','w','selected','MAIN','hash',0);").unwrap();
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        assert_eq!(
            reader.count_by_kind(&FactFilter::default()).unwrap()["declaration"],
            100001
        );
        assert_eq!(
            reader
                .count_by_kind(&FactFilter {
                    module: Some("selected".into()),
                    ..Default::default()
                })
                .unwrap()["declaration"],
            100001
        );
        assert!(
            reader
                .count_by_kind(&FactFilter {
                    module: Some("other".into()),
                    ..Default::default()
                })
                .unwrap()
                .is_empty()
        );
        // Bodies deliberately cannot decode as Fact; aggregation must use projections.
        let packages = reader
            .overview_groups(&FactFilter::default(), "packages", "out", &[])
            .unwrap();
        assert_eq!(
            packages,
            vec![serde_json::json!({"package":"","count":100001})]
        );
        let modules = reader
            .overview_groups(&FactFilter::default(), "modules", "out", &[])
            .unwrap();
        assert_eq!(
            modules,
            vec![serde_json::json!({"module":"selected","count":100001})]
        );
    }
    #[test]
    fn overview_dependency_counts_keep_occurrences_direction_and_relation() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        db.execute_batch("INSERT INTO facts VALUES('a','declaration','owner','','','','A','A.java','w','{\"attributes\":{\"package\":\"p.a\"}}'),('b','declaration','owner','','','','B','B.java','w','{\"attributes\":{\"package\":\"p.b\"}}'),('site','call_site','owner','a','','','','A.java','w','{}'),('c1','call_target','owner','site','b','calls','','A.java','w','{}'),('c2','call_target','owner','site','b','calls','','A.java','w','{}'),('r','reference','owner','b','a','reads','','B.java','w','{}');").unwrap();
        let reader = ReadContext {
            connection: db,
            revision: revision(),
            coord: Arc::default(),
        };
        let filter = FactFilter {
            package: Some("p.a".into()),
            ..Default::default()
        };
        let outgoing = reader
            .overview_groups(&filter, "dependencies", "out", &[])
            .unwrap();
        assert_eq!(
            outgoing,
            vec![
                serde_json::json!({"package":"p.a","target_package":"p.b","relation":"calls","count":2})
            ]
        );
        let incoming = reader
            .overview_groups(&filter, "dependencies", "in", &[])
            .unwrap();
        assert_eq!(
            incoming,
            vec![
                serde_json::json!({"package":"p.b","target_package":"p.a","relation":"reads","count":1})
            ]
        );
        assert_eq!(
            reader
                .overview_groups(&filter, "dependencies", "both", &[])
                .unwrap()
                .len(),
            2
        );
        assert!(
            reader
                .overview_groups(&filter, "dependencies", "in", &["calls".into()])
                .unwrap()
                .is_empty()
        );
    }
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
                action: "list",
                id: "",
                symbol: "",
                text: "",
                version: "",
                anchor: None,
            })
            .unwrap();
        assert_eq!(rows[0]["text"], "saved");
        assert!(rows[0]["anchor"].is_null());
        assert_eq!(rows[0]["version"], 3);
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
        assert_eq!(store.recover(false).unwrap()["pending"][0], id);
        assert!(folder.exists());
        store.recover(true).unwrap();
        assert!(!folder.exists());
        assert!(
            store.recover(false).unwrap()["pending"]
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
    fn staged_binding_evidence_checks_parent_kind_and_owner() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let owner = serde_json::json!({"world":"w","producer":"javac","kind":"context","id":"c"});
        let site: Fact = serde_json::from_value(serde_json::json!({
            "id":"site", "kind":"call_site", "owner":owner, "origin":"javac"
        }))
        .unwrap();
        let binding: Fact = serde_json::from_value(serde_json::json!({
            "id":"binding", "kind":"facet", "parent":"site", "owner":owner,
            "origin":"javac", "relation":"java.binding",
            "attributes":{"schema_version":3,"evidence_owner":"site","resolved":true,"complete":true}
        })).unwrap();
        let mut evidence = result();
        evidence.facts = vec![site, binding];
        let valid = store.publish(revision(), &evidence, "", "valid").unwrap();
        evidence.facts[0].kind = FactKind::Declaration;
        let failed = store
            .publish(revision(), &evidence, &valid.id, "bad-kind")
            .unwrap_err();
        assert!(failed.to_string().contains("INVALID_BINDING_EVIDENCE"));
        evidence.facts[0].kind = FactKind::CallSite;
        evidence.facts[0].owner.world = "other".into();
        let failed = store
            .publish(revision(), &evidence, &valid.id, "bad-owner")
            .unwrap_err();
        assert!(failed.to_string().contains("INVALID_BINDING_EVIDENCE"));
        assert_eq!(store.head("t").unwrap(), valid.id);
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
    fn owner_replacement_removes_deleted_facts() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let mut first = result();
        let f:Fact=serde_json::from_value(serde_json::json!({"id":"a","kind":"declaration","owner":{"world":"w","producer":"mock","kind":"aggregate","id":"a"},"origin":"mock"})).unwrap();
        first.facts.push(f.clone());
        let r = s.publish(revision(), &first, "", "task1").unwrap();
        let mut delta = result();
        delta.mode = "delta".into();
        delta.baseline = r.id.clone();
        delta.replacements.push(f.owner);
        let r2 = s.publish(revision(), &delta, &r.id, "task2").unwrap();
        assert!(
            s.read(&r2.id)
                .unwrap()
                .select(&FactFilter::default())
                .unwrap()
                .is_empty()
        );
    }
    #[test]
    fn catalog_failure_leaves_head_and_recovers_orphan() {
        let d = tempfile::tempdir().unwrap();
        let s = Store::open(d.path()).unwrap();
        let first = s.publish(revision(), &result(), "", "a").unwrap();
        s.catalog().unwrap().execute_batch("CREATE TRIGGER fail_publish BEFORE INSERT ON heads BEGIN SELECT RAISE(ABORT,'injected catalog failure'); END;").unwrap();
        assert!(s.publish(revision(), &result(), &first.id, "b").is_err());
        assert_eq!(s.head("t").unwrap(), first.id);
        let preview = s.recover(false).unwrap();
        assert_eq!(preview["orphans"].as_array().unwrap().len(), 1);
        s.recover(true).unwrap();
        assert!(
            s.recover(false).unwrap()["orphans"]
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
        let plan = s.gc_policy(true, Some("p"), 0, 0, 1).unwrap();
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
        let preview = s.gc_policy(false, Some("p"), 0, 0, 0).unwrap();
        assert_eq!(preview["expired_objects"].as_array().unwrap().len(), 1);
        assert!(s.read(&first.id).is_ok());
        s.gc_policy(true, Some("p"), 0, 0, 0).unwrap();
        assert!(s.read(&first.id).is_err());
    }
}
