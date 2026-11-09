//! Composition for one operation. Services receive task-bound controls explicitly.
use super::*;
#[derive(Clone)]
pub(super) struct TaskExecution {
    pub index: IndexService<ports::storage::IndexAdapter>,
    pub workers: Arc<worker_service::WorkerService>,
    pub history: history::HistoryService,
    pub reports: reports::ReportService,
    pub maintenance: maintenance::MaintenanceService,
    pub operations: operation_router::OperationRouter,
}
impl TaskExecution {
    pub(super) async fn index(
        &self,
        task: task_runner::TaskContext,
        req: &IndexCommand,
        capture: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let id = task.id.to_string();
        IndexTask {
            index: self.index.clone(),
            task,
            workers: self.workers.clone(),
            reports: self.reports.clone(),
            maintenance: self.maintenance.clone(),
        }
        .index(&id, req, capture, cancel)
        .await
    }
    pub(super) async fn snapshot(
        &self,
        task: task_runner::TaskContext,
        req: &Request,
        capture: Option<Capture>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        self.history
            .snapshot(req, capture, cancel, |capture| async move {
                self.index(task, &IndexCommand::from(req), capture, cancel)
                    .await
            })
            .await
    }
}
