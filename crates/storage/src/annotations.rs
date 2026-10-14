//! Annotations persistence over the shared catalog coordination.
use super::*;
impl Store {
    pub fn annotation(&self, command: AnnotationCommand<'_>) -> Result<AnnotationResult> {
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
            AnnotationAction::Add => {
                ensure!(
                    !text.is_empty() && !symbol.is_empty() && text.len() <= 64 * 1024,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::AnnotationContentRequired,
                        ""
                    )
                );
                let id = new_id("note");
                let anchor = anchor.context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::AnnotationAnchorRequired,
                    "",
                ))?;
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
                Ok(AnnotationResult::Entry(Box::new(AnnotationEntry {
                    id,
                    symbol: symbol.into(),
                    text: text.into(),
                    version: 1,
                    annotation_revision,
                    anchor: Some(anchor.clone()),
                    binding: Some(AnnotationBinding::Bound),
                    binding_revision: None,
                })))
            }
            AnnotationAction::Update | AnnotationAction::Remove => {
                if action == "update" {
                    ensure!(
                        text.len() <= 64 * 1024,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::AnnotationContentBudget,
                            ""
                        )
                    );
                }
                let expected = version
                    .parse::<i64>()
                    .context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::AnnotationVersionRequired,
                        "",
                    ))?;
                let n = if action == "update" {
                    tx.execute("UPDATE annotations SET body=?,version=version+1 WHERE id=? AND project=? AND version=?",params![text,id,project,expected])?
                } else {
                    tx.execute(
                        "DELETE FROM annotations WHERE id=? AND project=? AND version=?",
                        params![id, project, expected],
                    )?
                };
                ensure!(
                    n == 1,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::AnnotationConflict,
                        ""
                    )
                );
                tx.execute("INSERT INTO annotation_revisions(project,revision) VALUES(?,1) ON CONFLICT(project) DO UPDATE SET revision=revision+1", [project])?;
                let annotation_revision: i64 = tx.query_row(
                    "SELECT revision FROM annotation_revisions WHERE project=?",
                    [project],
                    |r| r.get(0),
                )?;
                tx.commit()?;
                Ok(AnnotationResult::Mutation(AnnotationMutation {
                    id: id.into(),
                    version: expected + 1,
                    annotation_revision,
                    action,
                }))
            }
            AnnotationAction::List => {
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
                ensure!(
                    rows.len() <= 10000,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::AnnotationListBudget,
                        ""
                    )
                );
                let rows = rows
                    .into_iter()
                    .map(|(id, symbol, text, version, anchor)| {
                        let anchor = if anchor.is_empty() {
                            None
                        } else {
                            serde_json::from_str(&anchor)?
                        };
                        Ok(AnnotationEntry {
                            id,
                            symbol,
                            text,
                            version,
                            annotation_revision: revision,
                            anchor,
                            binding: None,
                            binding_revision: None,
                        })
                    })
                    .collect::<Result<Vec<_>>>()?;
                tx.commit()?;
                Ok(AnnotationResult::List(rows))
            }
        }
    }
}
