//! Fixed-context graph/text queries and frozen-page delivery; no maintenance commands.
use super::*;
#[derive(Clone)]
pub(super) struct QueryService {
    store: Store,
    history: history::HistoryService,
}
impl QueryService {
    pub(super) fn new(store: Store, history: history::HistoryService) -> Self {
        Self { store, history }
    }
    pub(super) fn execute(&self, req: &Request, cancel: &CancellationToken) -> Result<TaskResult> {
        ensure!(
            !cancel.is_cancelled(),
            DomainError::new(ErrorCode::Cancelled, "")
        );
        ensure!(
            codeana_query::OPERATIONS.contains(&req.operation.as_str()),
            DomainError::new(ErrorCode::UnsupportedOperation, req.operation.to_string())
        );
        let root = Path::new(&req.project);
        let (project, checkout) = self.store.project_path(&req.project)?;

        if !req.cursor.is_empty() {
            return self.history.continue_page(req, &project);
        }
        let budget = ResourceBudget::new(codeana_config::query_limits(root, &self.store.home)?);
        if let Some(sid) = req.revision.strip_prefix("snapshot:") {
            let snapshot: history::Snapshot = match self
                .store
                .object_budgeted("snapshot", sid, &project, &budget)
            {
                Ok(snapshot) => snapshot,
                Err(e) if resource_error(&e) => {
                    return self.history.resource_page(
                        req,
                        &project,
                        &req.revision,
                        &budget,
                        vec![],
                    );
                }
                Err(e) => return Err(e),
            };
            if snapshot.revision.is_empty() {
                ensure!(
                    matches!(req.operation, Operation::Source | Operation::SearchText),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::AnalysisSnapshotRequired,
                        ""
                    )
                );
                let raw_reader = FilesReader(Revision {
                    id: format!("snapshot:{sid}"),
                    target: String::new(),
                    project: project.clone(),
                    checkout: checkout.clone(),
                    manifest: snapshot.manifest,
                    profile: snapshot.profile,
                    producer: "files-capture-v1".into(),
                    created: snapshot.created,
                });
                let reader = BudgetedSource {
                    inner: &raw_reader,
                    budget: &budget,
                };
                if req.operation == codeana_model::Operation::SearchText {
                    return self.history.text_page(
                        req,
                        &reader,
                        &|s| {
                            ensure!(
                                !cancel.is_cancelled(),
                                codeana_model::DomainError::new(
                                    codeana_model::ErrorCode::Cancelled,
                                    ""
                                )
                            );
                            self.store.blob_bounded(&s.hash, s.size)
                        },
                        vec![],
                        cancel,
                    );
                }
                let out = codeana_query::run_files(&reader, req, &|s| {
                    ensure!(
                        !cancel.is_cancelled(),
                        codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                    );
                    self.store.blob_bounded(&s.hash, s.size)
                })?;
                ensure!(
                    !cancel.is_cancelled(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                );
                return self.history.page(req, &project, out, vec![]);
            }
        }
        let id = if req.revision.is_empty() {
            self.store.latest(&project, &checkout)?
        } else if let Some(id) = req.revision.strip_prefix("snapshot:") {
            let s: history::Snapshot = match self
                .store
                .object_budgeted("snapshot", id, &project, &budget)
            {
                Ok(snapshot) => snapshot,
                Err(e) if resource_error(&e) => {
                    return self.history.resource_page(
                        req,
                        &project,
                        &req.revision,
                        &budget,
                        vec![],
                    );
                }
                Err(e) => return Err(e),
            };
            ensure!(
                !s.revision.is_empty(),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::AnalysisSnapshotRequired,
                    ""
                )
            );
            s.revision
        } else {
            req.revision.clone()
        };
        let reader = match self.store.read_budgeted(&id, budget.clone()) {
            Ok(reader) => reader,
            Err(e) if resource_error(&e) => {
                return self
                    .history
                    .resource_page(req, &project, &id, &budget, vec![id.clone()]);
            }
            Err(e) => return Err(e),
        };
        let token = cancel.clone();
        let reader = reader.with_cancellation(Arc::new(move || token.is_cancelled()));
        ensure!(
            reader.revision().project == project,
            codeana_model::DomainError::new(codeana_model::ErrorCode::ProjectMismatch, "")
        );
        let guarded = CancelableReader {
            inner: &reader,
            cancel,
        };
        if req.operation == codeana_model::Operation::SearchCode && req.search_in == SearchIn::Names
        {
            return self
                .history
                .search_page(req, &project, &guarded, vec![id], cancel);
        }
        let source = |s: &SourceUnit| -> Result<Vec<u8>> {
            ensure!(
                !cancel.is_cancelled(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
            );
            if req.revision.is_empty() {
                let p = root.join(&s.path);
                let real = p.canonicalize()?;
                ensure!(
                    real.starts_with(root),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::SourceEscape, "")
                );
                let b = codeana_config::bounded_read(
                    &real,
                    usize::try_from(s.size).unwrap_or(usize::MAX).min(MAX_FRAME),
                )?;
                ensure!(
                    hash(&b) == s.hash,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::SourceHashMismatch,
                        "re-index or select --revision".to_string()
                    )
                );
                Ok(b)
            } else {
                self.store.blob_bounded(&s.hash, s.size)
            }
        };
        if req.operation == codeana_model::Operation::SearchText {
            return self
                .history
                .text_page(req, &guarded, &source, vec![id], cancel);
        }
        let out = codeana_query::run(&guarded, req, &source)?;
        ensure!(
            !cancel.is_cancelled(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
        );
        self.history.page(req, &project, out, vec![id])
    }
}
