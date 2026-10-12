//! Task-local, disk-backed identity checks. This database never publishes facts.
use anyhow::Result;
use rusqlite::{Connection, params};
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
        let inserted = self
            .connection
            .prepare_cached("INSERT OR IGNORE INTO identities VALUES(?1,?2,?3)")?
            .execute(params![id, digest, i64::try_from(offset)?])?;
        if inserted == 0 {
            let (previous, first_offset): (String, i64) = self
                .connection
                .prepare_cached("SELECT digest,offset FROM identities WHERE id=?1")?
                .query_row([id], |row| Ok((row.get(0)?, row.get(1)?)))?;
            return Ok(if previous == digest {
                Observation::Same
            } else {
                Observation::Conflict {
                    first_offset: u64::try_from(first_offset)?,
                }
            });
        }
        self.pending += 1;
        if self.pending == 2048 {
            codeana_contracts::performance::measure(
                codeana_contracts::performance::Metric::DedupCommit,
                0,
                false,
                || self.connection.execute_batch("COMMIT; BEGIN;"),
            )?;
            self.pending = 0;
        }
        Ok(Observation::New)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn first_digest_and_offset_survive_duplicates_and_batch_commit() {
        let root = tempfile::tempdir().unwrap();
        let mut ids = FactDeduplicator::new(root.path()).unwrap();
        assert!(matches!(
            ids.observe("same", "one", 4).unwrap(),
            Observation::New
        ));
        assert!(matches!(
            ids.observe("same", "one", 8).unwrap(),
            Observation::Same
        ));
        for n in 0..2048 {
            assert!(matches!(
                ids.observe(&format!("id-{n}"), "digest", n).unwrap(),
                Observation::New
            ));
        }
        assert!(matches!(
            ids.observe("same", "two", 12).unwrap(),
            Observation::Conflict { first_offset: 4 }
        ));
        assert!(matches!(
            ids.observe("same", "one", 16).unwrap(),
            Observation::Same
        ));
        assert!(ids.observe("oversized-offset", "one", u64::MAX).is_err());
    }
}
