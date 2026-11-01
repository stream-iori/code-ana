use super::*;
#[derive(Clone)]
pub(super) struct MaintenanceService {
    store: Store,
}
impl MaintenanceService {
    pub(super) fn new(store: Store) -> Self {
        Self { store }
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
