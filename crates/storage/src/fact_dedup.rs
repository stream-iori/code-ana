//! Task-local, disk-backed identity checks. This database never publishes facts.
use anyhow::Result;
use rusqlite::{Connection, OptionalExtension, params};
use std::path::Path;

pub enum Observation {
    New,
    Same,
    Conflict { first_offset: u64 },
}

pub struct FactDeduplicator {
    connection: Connection,
    _directory: tempfile::TempDir,
    pending: usize,
}

impl FactDeduplicator {
    pub fn new(root: &Path) -> Result<Self> {
        let directory = tempfile::Builder::new()
            .prefix("fact-identities-")
            .tempdir_in(root)?;
        let connection = Connection::open(directory.path().join("identities.db"))?;
        // This task-private index is discarded on every error or restart. It never
        // publishes data and needs no crash recovery or rollback journal.
        connection.execute_batch("PRAGMA journal_mode=OFF; PRAGMA cache_size=-65536; PRAGMA synchronous=OFF;
            CREATE TABLE identities(id TEXT PRIMARY KEY,digest TEXT NOT NULL,offset INTEGER NOT NULL) WITHOUT ROWID;
            BEGIN;")?;
        Ok(Self {
            connection,
            _directory: directory,
            pending: 0,
        })
    }

    pub fn observe(&mut self, id: &str, digest: &str, offset: u64) -> Result<Observation> {
        let existing: Option<(String, i64)> = self
            .connection
            .prepare_cached("SELECT digest,offset FROM identities WHERE id=?1")?
            .query_row([id], |row| Ok((row.get(0)?, row.get(1)?)))
            .optional()?;
        if let Some((previous, first_offset)) = existing {
            return Ok(if previous == digest {
                Observation::Same
            } else {
                Observation::Conflict {
                    first_offset: u64::try_from(first_offset)?,
                }
            });
        }
        self.connection
            .prepare_cached("INSERT INTO identities VALUES(?1,?2,?3)")?
            .execute(params![id, digest, i64::try_from(offset)?])?;
        self.pending += 1;
        if self.pending == 2048 {
            self.connection.execute_batch("COMMIT; BEGIN;")?;
            self.pending = 0;
        }
        Ok(Observation::New)
    }
}
