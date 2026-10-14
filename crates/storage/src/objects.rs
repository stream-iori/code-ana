//! Objects persistence over the shared catalog coordination.
use super::*;
impl Store {
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
        #[derive(serde::Deserialize)]
        struct ObjectRoots {
            manifest: Option<Manifest>,
        }
        let roots: ObjectRoots = serde_json::from_value(serde_json::to_value(value)?)?;
        if let Some(manifest) = roots.manifest {
            for source in manifest.files {
                guard.staged_blobs.remove(&source.hash);
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
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::ObjectNotFoundOrExpired,
                "",
            ))?;
        Ok(serde_json::from_str(&s)?)
    }
    pub fn objects<T: DeserializeOwned>(&self, kind: &str, project: &str) -> Result<Vec<T>> {
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
        ensure!(
            n == 1,
            codeana_model::DomainError::new(codeana_model::ErrorCode::ObjectNotFound, "")
        );
        tx.execute("DELETE FROM refs WHERE kind=? AND id=?", params![kind, id])?;
        tx.commit()?;
        if kind == "page" {
            self.remove_page(id)?;
        }
        Ok(())
    }
}
