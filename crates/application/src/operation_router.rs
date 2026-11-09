//! Application use-case composition. Domain services keep their own policies.
use super::*;
#[derive(Clone)]
pub(super) struct OperationRouter {
    query: query_service::QueryService,
    history: history::HistoryService,
    reports: reports::ReportService,
    doctor: doctor::DoctorService,
    maintenance: maintenance::MaintenanceService,
}
impl OperationRouter {
    pub(super) fn new(
        query: query_service::QueryService,
        history: history::HistoryService,
        reports: reports::ReportService,
        doctor: doctor::DoctorService,
        maintenance: maintenance::MaintenanceService,
    ) -> Self {
        Self {
            query,
            history,
            reports,
            doctor,
            maintenance,
        }
    }
    pub(super) fn execute(
        &self,
        req: &Request,
        cancel: &CancellationToken,
        active_tasks: usize,
    ) -> Result<TaskResult> {
        ensure!(
            !cancel.is_cancelled(),
            DomainError::new(ErrorCode::Cancelled, "")
        );
        if codeana_query::OPERATIONS.contains(&req.operation.as_str()) {
            return self.query.execute(req, cancel);
        }
        let (project, _) = self.history.project_identity(&req.project)?;
        let output = match req.operation {
            Operation::Doctor => self.doctor.doctor(Path::new(&req.project), req)?,
            Operation::Gc | Operation::Recover => {
                self.maintenance.execute(req, &project, active_tasks)?
            }
            Operation::SnapshotList => self.history.list_snapshots(&project)?,
            Operation::SnapshotRemove => self.history.remove_snapshot(&project, &req.selector)?,
            Operation::Diff => self.history.diff(req, &project)?,
            Operation::Report => self.reports.report(req, &project)?,
            _ => bail!(DomainError::new(
                ErrorCode::UnsupportedOperation,
                req.operation.to_string()
            )),
        };
        Ok(TaskResult::success(output))
    }
}
