//! Maintenance persistence over the shared catalog coordination.
use super::*;
impl Store {
    pub fn gc(&self, execute: bool) -> Result<GcResult> {
        self.gc_policy(execute, None, 0, 0, 0)
    }
    pub fn gc_policy(
        &self,
        execute: bool,
        project: Option<&str>,
        keep: usize,
        max_age_days: u64,
        max_bytes: u64,
    ) -> Result<GcResult> {
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
            #[derive(serde::Deserialize)]
            struct ObjectAge {
                created: Option<u64>,
            }
            let value: ObjectAge = serde_json::from_str(&body)?;
            let created = value.created.unwrap_or_else(now);
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
            #[derive(serde::Deserialize)]
            struct SnapshotRoots {
                manifest: Manifest,
            }
            let snapshot: SnapshotRoots = serde_json::from_str(&row?)?;
            blob_roots.extend(snapshot.manifest.files.into_iter().map(|s| s.hash));
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
        Ok(GcResult {
            execute,
            protected,
            revisions: removals,
            blobs,
            expired_objects: expired,
            retained_graph_bytes: retained_bytes,
            budget_unmet: max_bytes > 0 && retained_bytes > max_bytes,
        })
    }
    pub fn recover(&self, execute: bool) -> Result<RecoveryResult> {
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
                codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidCleanupId, "")
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
        Ok(RecoveryResult {
            execute,
            orphans: orphan,
            pending,
        })
    }
}
