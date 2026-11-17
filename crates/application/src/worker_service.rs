//! Owns worker policy and the healthy Java process pool. No task registry or graph store.
use super::*;
pub(super) struct UnitLaunch<'a> {
    pub capture: &'a Capture,
    pub manifest: &'a Manifest,
    pub input: &'a crate::analysis_unit::PreparedInput,
    pub cancel: &'a CancellationToken,
    pub memory: u64,
    pub analysis: Option<worker::AnalysisBinding>,
    pub previous: &'a [SourceUnit],
    pub baseline: &'a str,
}
pub(super) struct StartedUnit {
    pub worker: worker::Worker,
    pub pool_key: Option<String>,
}
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
    /// The only production launch factory. Pool rediscovery never retries a failed grant.
    pub(super) async fn launch(&self, request: UnitLaunch<'_>) -> Result<StartedUnit> {
        let UnitLaunch {
            capture,
            manifest,
            input,
            cancel,
            memory,
            analysis,
            previous,
            baseline,
        } = request;
        ensure!(
            input.fixtures() == self.mock_fixtures(),
            "WORKER_FIXTURE_POLICY_MISMATCH"
        );
        if let Some(binding) = &analysis {
            let host = binding
                .coordinator
                .lock()
                .unwrap()
                .host(input.backend())
                .map_err(DomainError::analysis)?;
            host.charge(1, 0, std::mem::size_of::<StartedUnit>() as u64 + 128)
                .map_err(DomainError::analysis)?;
            if let Some(java) = input.java() {
                codeana_contracts::json_budget::admit_owned_copy(
                    &(
                        &capture.target,
                        &capture.effective.semantic_digest,
                        &java.library,
                        &java.config.toolchain_digest,
                        &java.config.semantic_identity,
                        java.config.native_heap_bytes,
                        memory,
                    ),
                    &host,
                )?;
            }
        }
        let pool_key = input.java().map(|p| {
            digest(&(
                &capture.target,
                &capture.effective.semantic_digest,
                &p.library,
                &p.config.toolchain_digest,
                &p.config.semantic_identity,
                p.config.native_heap_bytes,
                memory,
            ))
        });
        if pool_key.is_some()
            && self.can_keep_java()
            && let Some(mut idle) = self.take_java()
        {
            if let (Some(prepared), Some(key)) = (input.java(), pool_key.as_ref())
                && idle.key == *key
                && idle.revision == baseline
                && idle.worker.alive().unwrap_or(false)
            {
                let discovered = idle
                    .worker
                    .rediscover(
                        prepared,
                        manifest,
                        cancel,
                        analysis,
                        crate::supervisor::InputLease::new(capture, input.directories()),
                        crate::analysis_unit::publication(capture, input.backend(), previous),
                    )
                    .await;
                if let Err(error) = discovered {
                    if let Err(cleanup) = idle.worker.shutdown().await {
                        eprintln!("java_rediscover_cleanup_warning: {cleanup:#}");
                    }
                    return Err(error);
                }
                return Ok(StartedUnit {
                    worker: idle.worker,
                    pool_key,
                });
            }
            if let Err(cleanup) = idle.worker.shutdown().await {
                eprintln!("java_idle_cleanup_warning: {cleanup:#}");
            }
        }
        let worker = worker::Worker::start_language(
            worker::LaunchSpec {
                fixtures: input.fixtures(),
                language: input.language(),
                checkout: &capture.root,
                manifest,
                inputs: crate::supervisor::InputLease::new(capture, input.directories()),
                publication: crate::analysis_unit::publication(capture, input.backend(), previous),
            },
            cancel,
            memory,
            analysis,
        )
        .await?;
        Ok(StartedUnit { worker, pool_key })
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
    fn take_java(&self) -> Option<IdleJava> {
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
