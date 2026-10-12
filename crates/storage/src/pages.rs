//! Private, bounded result spooling; catalog publication owns revision references.
use super::*;
use serde_json::Value;
use tempfile::NamedTempFile;

pub struct PageSpool {
    file: Option<NamedTempFile>,
    connection: Option<Connection>,
    count: usize,
    coord: Arc<Mutex<Coordination>>,
    token: String,
}
impl Drop for PageSpool {
    fn drop(&mut self) {
        let mut guard = self.coord.lock().unwrap();
        self.connection.take();
        self.file.take();
        guard.staged_candidates.remove(&self.token);
    }
}
impl PageSpool {
    pub fn count(&self) -> usize {
        self.count
    }
    pub fn append(&mut self, records: &[Value]) -> Result<()> {
        ensure!(records.len() <= 1024, "PAGE_BATCH_BUDGET");
        let _guard = self.coord.lock().unwrap();
        let transaction = self
            .connection
            .as_mut()
            .context("PAGE_SPOOL_CLOSED")?
            .transaction()?;
        {
            let mut statement = transaction.prepare("INSERT INTO records VALUES(?,?)")?;
            for (offset, record) in records.iter().enumerate() {
                statement.execute(params![(self.count + offset) as i64, json(record)?])?;
            }
        }
        transaction.commit()?;
        self.count += records.len();
        Ok(())
    }
}
impl Store {
    pub fn page_spool(&self) -> Result<PageSpool> {
        let mut guard = self.coord.lock().unwrap();
        let file = tempfile::Builder::new()
            .prefix("page-spool-")
            .tempfile_in(self.home.join("candidates"))?;
        let token = file
            .path()
            .file_name()
            .context("PAGE_SPOOL_NAME")?
            .to_string_lossy()
            .into_owned();
        let connection = Connection::open(file.path())?;
        connection.execute_batch("PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; CREATE TABLE records(sequence INTEGER PRIMARY KEY,body TEXT NOT NULL);")?;
        guard.staged_candidates.insert(token.clone());
        Ok(PageSpool {
            file: Some(file),
            connection: Some(connection),
            count: 0,
            coord: self.coord.clone(),
            token,
        })
    }
    fn page_path(&self, id: &str) -> Result<PathBuf> {
        ensure!(
            !id.is_empty()
                && id
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-'),
            "INVALID_PAGE_ID"
        );
        Ok(self.home.join("pages").join(format!("{id}.db")))
    }
    pub fn freeze_page<T: Serialize>(
        &self,
        id: &str,
        project: &str,
        metadata: &T,
        revisions: &[String],
        mut spool: PageSpool,
    ) -> Result<()> {
        let body = json(metadata)?;
        let connection = spool.connection.take().context("PAGE_SPOOL_CLOSED")?;
        connection.close().map_err(|(_, error)| error)?;
        let file = spool.file.take().context("PAGE_SPOOL_CLOSED")?;
        file.as_file().sync_all()?;
        let _guard = self.coord.lock().unwrap();
        let path = self.page_path(id)?;
        let mut catalog = self.catalog()?;
        let transaction = catalog.transaction()?;
        for revision in revisions {
            self.revision_unlocked(revision)?;
        }
        transaction.execute(
            "INSERT INTO objects VALUES('page',?,?,?)",
            params![id, project, body],
        )?;
        for revision in revisions {
            transaction.execute("INSERT INTO refs VALUES('page',?,?)", params![id, revision])?;
        }
        file.persist_noclobber(&path).map_err(|error| error.error)?;
        let result = (|| -> Result<()> {
            std::fs::File::open(self.home.join("pages"))?.sync_all()?;
            transaction.commit()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(path);
        }
        result
    }
    /// An indexed sequence seek, independent of the original result size.
    pub fn page_records(
        &self,
        id: &str,
        project: &str,
        offset: usize,
        limit: usize,
    ) -> Result<Vec<Value>> {
        ensure!((1..=10000).contains(&limit), "INVALID_PAGE_LIMIT");
        let _guard = self.coord.lock().unwrap();
        let _: Value = self.object("page", id, project)?;
        let connection = Connection::open_with_flags(
            self.page_path(id)?,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        let mut statement = connection
            .prepare("SELECT body FROM records WHERE sequence>=? ORDER BY sequence LIMIT ?")?;
        let mut output = Vec::new();
        for row in statement.query_map(params![offset as i64, limit as i64], |row| {
            row.get::<_, String>(0)
        })? {
            output.push(serde_json::from_str(&row?)?);
        }
        Ok(output)
    }
    pub(crate) fn remove_page(&self, id: &str) -> Result<()> {
        let path = self.page_path(id)?;
        if path.exists() {
            std::fs::remove_file(path)?;
        }
        Ok(())
    }
    pub(crate) fn remove_orphan_pages(&self, catalog: &Connection) -> Result<()> {
        let mut statement = catalog.prepare("SELECT id FROM objects WHERE kind='page'")?;
        let live: BTreeSet<String> = statement
            .query_map([], |row| row.get(0))?
            .collect::<rusqlite::Result<_>>()?;
        for entry in std::fs::read_dir(self.home.join("pages"))? {
            let entry = entry?;
            let path = entry.path();
            if path.extension().is_some_and(|value| value == "db")
                && let Some(id) = path.file_stem().and_then(|value| value.to_str())
                && !live.contains(id)
            {
                self.remove_page(id)?;
            }
        }
        Ok(())
    }
}
