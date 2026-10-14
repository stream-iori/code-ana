//! Tasks persistence over the shared catalog coordination.
use super::*;
impl Store {
    pub fn register_task(&self, id: &str, r: &Request) -> Result<(String, bool)> {
        let mut normalized = r.clone();
        normalized.request_id.clear();
        let h = digest(&normalized);
        let c = self.catalog()?;
        c.execute(
            "INSERT OR IGNORE INTO tasks VALUES(?,?,?,?,'queued',NULL,?)",
            params![id, r.request_id.as_str(), h, json(r)?, now() as i64],
        )?;
        let (existing, old): (String, String) = c.query_row(
            "SELECT id,request_hash FROM tasks WHERE request_id=?",
            [r.request_id.as_str()],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        ensure!(
            h == old,
            codeana_model::DomainError::new(codeana_model::ErrorCode::RequestIdConflict, "")
        );
        Ok((existing.clone(), existing == id))
    }
    pub fn task(&self, id: &str) -> Result<(String, Option<TaskResult>, u64)> {
        let c = self.catalog()?;
        let (state, result): (String, Option<String>) = c
            .query_row("SELECT state,result FROM tasks WHERE id=?", [id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::TaskNotFound,
                "",
            ))?;
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
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::EmptyEventBatch,
                "",
            ))
    }
    /// Persist a rendered output atomically; serialization failures roll back the batch.
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
                seq = seq.checked_add(1).context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::EventSequenceOverflow,
                    "",
                ))?;
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
}
