//! Native values are constructed, called and destroyed on one dedicated OS thread.
use anyhow::{Context, Result, ensure};
use codeana_contracts::performance::{Capture, Metric, measure};
use std::{
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::sync::{mpsc, oneshot};

type Work<T> = Box<dyn FnOnce(&mut T) + Send>;
struct Owner<T> {
    sender: mpsc::Sender<Work<T>>,
    stopped: Arc<AtomicBool>,
    ended: Option<oneshot::Receiver<()>>,
}
impl<T: 'static> Owner<T> {
    async fn spawn(create: impl FnOnce() -> Result<T> + Send + 'static) -> Result<Self> {
        let (sender, mut receiver) = mpsc::channel::<Work<T>>(8);
        let stopped = Arc::new(AtomicBool::new(false));
        let stop = stopped.clone();
        let (ready, loaded) = oneshot::channel();
        let (finished, ended) = oneshot::channel();
        std::thread::Builder::new()
            .name("codeana-ffi".into())
            .spawn(move || {
                match create() {
                    Ok(mut value) => {
                        if ready.send(Ok(())).is_ok() {
                            while let Some(work) = receiver.blocking_recv() {
                                if stop.load(Ordering::Acquire) {
                                    break;
                                }
                                work(&mut value);
                            }
                        }
                        drop(value);
                    }
                    Err(error) => {
                        let _ = ready.send(Err(error));
                    }
                }
                let _ = finished.send(());
            })?;
        let owner = Self {
            sender,
            stopped,
            ended: Some(ended),
        };
        loaded.await.context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::FfiOwnerStart,
            "",
        ))??;
        Ok(owner)
    }
    async fn call<R: Send + 'static>(
        &self,
        work: impl FnOnce(&mut T) -> Result<R> + Send + 'static,
    ) -> Result<R> {
        ensure!(
            !self.stopped.load(Ordering::Acquire),
            codeana_model::DomainError::new(codeana_model::ErrorCode::FfiOwnerStopped, "")
        );
        let (reply, result) = oneshot::channel();
        self.sender
            .send(Box::new(move |value| {
                let _ = reply.send(work(value));
            }))
            .await
            .map_err(|_| {
                anyhow::anyhow!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::FfiOwnerStopped,
                    ""
                ))
            })?;
        result.await.context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::FfiOwnerStopped,
            "",
        ))?
    }
    fn stop(&self) {
        self.stopped.store(true, Ordering::Release);
        // A full queue already wakes the owner; cancellation never waits for capacity.
        let _ = self.sender.try_send(Box::new(|_| {}));
    }
    async fn shutdown(&mut self) -> Result<()> {
        self.stop();
        if let Some(ended) = self.ended.take() {
            tokio::time::timeout(std::time::Duration::from_secs(2), ended)
                .await
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::FfiOwnerCloseTimeout,
                    "",
                ))?
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::FfiOwnerClose,
                    "",
                ))?;
        }
        Ok(())
    }
}
impl<T> Drop for Owner<T> {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Release);
        let _ = self.sender.try_send(Box::new(|_| {}));
    }
}

pub struct NativeActor {
    owner: Owner<crate::native::Native>,
    notification: Option<tokio::net::UnixStream>,
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
        let (reader, writer) = std::os::unix::net::UnixStream::pair()?;
        reader.set_nonblocking(true)?;
        writer.set_nonblocking(true)?;
        self.notification = Some(tokio::net::UnixStream::from_std(reader)?);
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
        use tokio::io::AsyncReadExt;
        let mut reader = self
            .notification
            .take()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NativeNotificationNotRegistered,
                "",
            ))?;
        let mut token = [0];
        reader
            .read_exact(&mut token)
            .await
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NativeNotificationIncomplete,
                "",
            ))?;
        ensure!(
            token[0] == 1,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::NativeNotificationProtocol,
                ""
            )
        );
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

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test(flavor = "current_thread")]
    async fn non_send_state_stays_on_owner_and_blocking_call_does_not_block_control() -> Result<()>
    {
        struct State(
            std::rc::Rc<std::thread::ThreadId>,
            Arc<std::sync::Mutex<Option<std::thread::ThreadId>>>,
        );
        impl Drop for State {
            fn drop(&mut self) {
                *self.1.lock().unwrap() = Some(std::thread::current().id());
            }
        }
        let dropped = Arc::new(std::sync::Mutex::new(None));
        let drop_capture = dropped.clone();
        let mut owner = Owner::spawn(move || {
            Ok(State(
                std::rc::Rc::new(std::thread::current().id()),
                drop_capture,
            ))
        })
        .await?;
        let id = owner.call(|s| Ok(*s.0)).await?;
        assert_ne!(id, std::thread::current().id());
        let (entered, ready) = oneshot::channel();
        let (release, waiting) = std::sync::mpsc::channel();
        {
            let blocked = owner.call(move |s| {
                let _ = entered.send(());
                waiting.recv()?;
                assert_eq!(*s.0, std::thread::current().id());
                Ok(())
            });
            tokio::pin!(blocked);
            tokio::select! {
                result = &mut blocked => { result?; anyhow::bail!("unexpected early return"); },
                _ = async {
                    ready.await.unwrap();
                    tokio::task::yield_now().await;
                    // Fill all eight slots while FFI blocks. Stop must not wait
                    // for capacity or execute queued work after cancellation.
                    for _ in 0..8 { owner.sender.try_send(Box::new(|_| panic!("cancelled queued work ran"))).unwrap(); }
                    assert!(owner.sender.try_send(Box::new(|_| {})).is_err());
                    owner.stop();
                    assert!(owner.call(|_| Ok(())).await.is_err());
                    release.send(()).unwrap();
                } => {}
            }
            blocked.await?;
        }
        owner.shutdown().await?;
        assert_eq!(*dropped.lock().unwrap(), Some(id));
        Ok(())
    }
}
