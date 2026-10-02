//! Immutable fact revisions, catalog compare-and-swap, durable task and history records.
mod staged;
use anyhow::{Context, Result, ensure};
use codeana_model::*;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Serialize, de::DeserializeOwned};
pub use staged::{StagedCandidate, StagedSummary};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};
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
    std::fs::File::open(p)?.sync_all()?;
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
        for p in ["revisions", "candidates", "blobs", "reports"] {
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
                ensure!(hash_file(&destination)? == *hash, "CORRUPT_BLOB");
                ensure!(hash_file(source.as_ref())? == *hash, "BLOB_HASH_MISMATCH");
                continue;
            }
            let temporary = destination.with_file_name(new_id("write"));
            let result = (|| -> Result<()> {
                let mut input = std::fs::File::open(source.as_ref())?;
                let mut output = std::fs::OpenOptions::new()
                    .create_new(true)
                    .write(true)
                    .open(&temporary)?;
                std::io::copy(&mut input, &mut output)?;
                output.sync_all()?;
                ensure!(hash_file(&temporary)? == *hash, "BLOB_HASH_MISMATCH");
                std::fs::rename(&temporary, &destination)?;
                sync_dir(destination.parent().context("BLOB_PARENT")?)?;
                Ok(())
            })();
            if result.is_err() {
                let _ = std::fs::remove_file(&temporary);
            }
            result?;
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
        let mut c = self.catalog()?;
        let tx = c.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let seq: i64 = tx.query_row(
            "SELECT COALESCE(MAX(seq),0)+1 FROM events WHERE task=?",
            [id],
            |r| r.get(0),
        )?;
        tx.execute(
            "INSERT INTO events VALUES(?,?,?)",
            params![id, seq, json(event)?],
        )?;
        tx.commit()?;
        Ok(seq as u64)
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
                if !id.starts_with("rev_")
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
                        std::fs::remove_dir_all(e.path())?;
                    }
                }
            }
        }
        Ok(serde_json::json!({"execute":execute,"orphans":orphan,"pending":pending}))
    }
}
impl FactReader for ReadContext {
    fn manifest(&self) -> &Manifest {
        &self.revision.manifest
    }
    fn revision(&self) -> &Revision {
        &self.revision
    }
    fn select(&self, f: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
        (|| -> Result<Vec<Fact>> {
            let mut sql = "SELECT body FROM facts WHERE 1=1".to_owned();
            let mut values: Vec<String> = Vec::new();
            for (column, value) in [
                ("parent", &f.parent),
                ("target", &f.target),
                ("relation", &f.relation),
                ("name", &f.name),
                ("source", &f.source),
                ("world", &f.world),
            ] {
                if let Some(v) = value {
                    sql.push_str(&format!(" AND {column}=?"));
                    values.push(v.clone());
                }
            }
            for (column, vs) in [
                ("id", f.ids.clone()),
                ("parent", f.parents.clone()),
                ("kind", f.kinds.iter().map(|k| k.name().into()).collect()),
            ] {
                if !vs.is_empty() {
                    ensure!(vs.len() <= 1000, "SELECTOR_BUDGET");
                    sql.push_str(&format!(
                        " AND {column} IN ({})",
                        vec!["?"; vs.len()].join(",")
                    ));
                    values.extend(vs);
                }
            }
            sql.push_str(" ORDER BY id LIMIT 100001");
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
