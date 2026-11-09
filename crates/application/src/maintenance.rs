use super::*;
#[derive(Clone)]
pub(super) struct MaintenanceService {
    store: Store,
}
impl MaintenanceService {
    pub(super) fn new(store: Store) -> Self {
        Self { store }
    }
    pub(super) fn execute(
        &self,
        req: &Request,
        project: &str,
        active_tasks: usize,
    ) -> Result<Output> {
        let record = match req.operation {
            Operation::Gc => {
                let policy = codeana_config::resolve(Path::new(&req.project), req)?
                    .config
                    .versions
                    .gc;
                Record::display(&self.store.gc_policy(
                    req.execute,
                    Some(project),
                    policy.keep.unwrap_or(20) as usize,
                    policy.max_age_days.unwrap_or(30) as u64,
                    policy.max_bytes.unwrap_or(0),
                )?)?
            }
            Operation::Recover => {
                ensure!(
                    active_tasks <= 1,
                    DomainError::new(ErrorCode::RecoveryRequiresDrain, "")
                );
                Record::display(&self.store.recover(req.execute)?)?
            }
            _ => bail!(DomainError::new(
                ErrorCode::UnsupportedOperation,
                req.operation.to_string()
            )),
        };
        Ok(Output {
            records: vec![record],
            evidence: Evidence::complete(),
            ..Default::default()
        })
    }
    pub(super) async fn after_publish(
        &self,
        project: String,
        policy: codeana_config::GcPolicy,
    ) -> Result<Result<()>> {
        let store = self.store.clone();
        Ok(tokio::task::spawn_blocking(move || {
            store
                .gc_policy(
                    true,
                    Some(&project),
                    policy.keep.unwrap_or(20) as usize,
                    policy.max_age_days.unwrap_or(30) as u64,
                    policy.max_bytes.unwrap_or(0),
                )
                .map(|_| ())
        })
        .await?)
    }
}
