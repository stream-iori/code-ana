//! Owns worker policy and the healthy Java process pool. No task registry or graph store.
use super::*;
pub(super) struct IdleJava {
    pub key: String,
    pub revision: String,
    id: String,
    pub worker: worker::Worker,
}
pub(super) struct WorkerService {
    home: PathBuf,
    mock: Option<PathBuf>,
    memory: u64,
    user: codeana_config::UserConfig,
    idle_java: Arc<Mutex<Option<IdleJava>>>,
}
impl WorkerService {
    pub(super) fn new(
        home: PathBuf,
        mock: Option<PathBuf>,
        user: codeana_config::UserConfig,
        memory: u64,
    ) -> Self {
        Self {
            home,
            mock,
            user,
            memory,
            idle_java: Arc::new(Mutex::new(None)),
        }
    }
    pub(super) fn is_mock(&self) -> bool {
        self.mock.is_some()
    }
    pub(super) fn mock_fixtures(&self) -> Option<&Path> {
        self.mock.as_deref()
    }
    pub(super) fn memory_cap(&self) -> u64 {
        self.memory
    }
    pub(super) fn java_toolchain(
        &self,
        effective: &codeana_config::Effective,
    ) -> Result<java::ToolchainEvidence> {
        java::toolchain_evidence(&self.user, effective)
    }
    pub(super) fn java_available(&self) -> bool {
        java::installed_library().is_ok()
    }
    pub(super) fn can_keep_java(&self) -> bool {
        !self.is_mock() && self.user.daemon.java_warm_workers.unwrap_or(true)
    }
    pub(super) fn take_java(&self) -> Option<IdleJava> {
        self.idle_java.lock().unwrap().take()
    }
    pub(super) fn keep_java(&self, key: String, revision: String, worker: worker::Worker) {
        let id = new_id("idle-java");
        *self.idle_java.lock().unwrap() = Some(IdleJava {
            key,
            revision,
            id: id.clone(),
            worker,
        });
        let pool = self.idle_java.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(60)).await;
            let mut pool = pool.lock().unwrap();
            if pool.as_ref().is_some_and(|item| item.id == id) {
                pool.take();
            }
        });
    }
    pub(super) async fn prepare_java(&self, captured: Capture) -> Result<java::PreparedJava> {
        let user = self.user.clone();
        let home = self.home.clone();
        tokio::task::spawn_blocking(move || java::prepare(&captured, &user, &home)).await?
    }
}
