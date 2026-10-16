//! Joins durable publication before the task can save its terminal or release permits.
use crate::ports::IndexRepository;
use anyhow::{Result, ensure};
use codeana_config::CapturedBlob;
use codeana_model::*;
use std::{collections::BTreeMap, sync::Arc};
use tokio_util::sync::CancellationToken;
pub(super) struct PublicationCoordinator;
impl PublicationCoordinator {
    pub(super) async fn publish<R: IndexRepository>(
        repository: R,
        candidate: R::Candidate,
        task: TaskId,
        blobs: BTreeMap<String, CapturedBlob>,
        cancel: CancellationToken,
        fence: Arc<PublicationFence>,
    ) -> Result<Revision> {
        tokio::task::spawn_blocking(move || {
            let diagnostics = codeana_contracts::performance::Report::new("publish-detail");
            diagnostics.capture.sync(|| {
                ensure!(
                    !cancel.is_cancelled(),
                    DomainError::new(ErrorCode::Cancelled, "")
                );
                repository.persist_and_publish(candidate, &task, blobs, &cancel, &fence)
            })
        })
        .await?
    }
}
