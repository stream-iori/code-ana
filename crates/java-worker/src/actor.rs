//! Java Native actor: G1 isolate and ABI policy stay here; runtime owns its OS thread.
use anyhow::{Context, Result, ensure};
use codeana_contracts::performance::{Capture, Metric, measure};
use codeana_worker_runtime::ffi::{Notification, Owner};
use std::path::{Path, PathBuf};
pub struct NativeActor {
    owner: Owner<crate::native::Native>,
    notification: Option<Notification>,
    capture: Capture,
}
impl NativeActor {
    pub async fn load(path: &Path, heap: u64, capture: Capture) -> Result<Self> {
        let path = path.to_owned();
        let trace = capture.clone();
        let owner = Owner::spawn(move || {
            trace.sync(|| {
                measure(Metric::EngineLoad, 0, false, || {
                    crate::native::Native::load_with_heap(&path, heap)
                })
            })
        })
        .await?;
        Ok(Self {
            owner,
            notification: None,
            capture,
        })
    }
    pub fn set_capture(&mut self, capture: Capture) {
        self.capture = capture;
    }
    pub async fn heap_bytes(&self) -> Result<u64> {
        self.owner.call(|native| native.heap_bytes()).await
    }
    pub async fn start(&mut self, request: Vec<u8>) -> Result<()> {
        use std::os::fd::AsRawFd;
        ensure!(
            self.notification.is_none(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::NativeNotificationState, "")
        );
        let (notification, writer) = Notification::pair()?;
        self.notification = Some(notification);
        let trace = self.capture.clone();
        self.owner
            .call(move |native| {
                trace.sync(|| {
                    measure(Metric::EngineStart, request.len() as u64, false, || {
                        native.start_notified(&request, writer.as_raw_fd())
                    })
                })
            })
            .await
    }
    pub async fn ready(&mut self) -> Result<crate::state::RunState> {
        self.notification
            .take()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NativeNotificationNotRegistered,
                "",
            ))?
            .wait()
            .await?;
        let trace = self.capture.clone();
        let state = self
            .owner
            .call(move |native| {
                trace.sync(|| measure(Metric::EnginePoll, 0, false, || native.poll()))
            })
            .await?;
        ensure!(
            matches!(
                state,
                crate::state::RunState::Failed | crate::state::RunState::Complete
            ),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::NativeNotificationNotTerminal,
                ""
            )
        );
        Ok(state)
    }
    pub async fn result_file(
        &self,
        limit: u64,
        directory: PathBuf,
    ) -> Result<tempfile::NamedTempFile> {
        let trace = self.capture.clone();
        self.owner
            .call(move |native| {
                trace.sync(|| {
                    measure(Metric::DescriptorRead, 0, false, || {
                        native.result_file_with_limit(limit, Some(&directory))
                    })
                })
            })
            .await
    }
    pub async fn ack(&self) -> Result<()> {
        self.owner.call(|native| native.ack()).await
    }
    pub async fn finish(&self) -> Result<()> {
        self.owner.call(|native| native.finish()).await
    }
    pub fn cancel(&self) {
        self.owner.stop();
    }
    pub async fn shutdown(&mut self) -> Result<()> {
        self.owner.shutdown().await
    }
}
