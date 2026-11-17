//! Bounded owner mechanics. A non-Send engine never leaves its creating OS thread.
use anyhow::{Context, Result, ensure};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use tokio::sync::{mpsc, oneshot};
type Work<T> = Box<dyn FnOnce(&mut T) + Send>;
pub struct Owner<T> {
    sender: mpsc::Sender<Work<T>>,
    stopped: Arc<AtomicBool>,
    ended: Option<oneshot::Receiver<()>>,
}
impl<T: 'static> Owner<T> {
    pub async fn spawn(create: impl FnOnce() -> Result<T> + Send + 'static) -> Result<Self> {
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
    pub async fn call<R: Send + 'static>(
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
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::Release);
        // A full queue already wakes the owner; cancellation never waits for capacity.
        let _ = self.sender.try_send(Box::new(|_| {}));
    }
    pub async fn shutdown(&mut self) -> Result<()> {
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

/// One bounded completion token. The backend still validates its own terminal state.
pub struct Notification(tokio::net::UnixStream);
impl Notification {
    pub fn pair() -> Result<(Self, std::os::unix::net::UnixStream)> {
        let (reader, writer) = std::os::unix::net::UnixStream::pair()?;
        reader.set_nonblocking(true)?;
        writer.set_nonblocking(true)?;
        Ok((Self(tokio::net::UnixStream::from_std(reader)?), writer))
    }
    pub async fn wait(mut self) -> Result<()> {
        use tokio::io::AsyncReadExt;
        let mut token = [0];
        self.0
            .read_exact(&mut token)
            .await
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::FfiNotificationIncomplete,
                "",
            ))?;
        ensure!(
            token[0] == 1,
            codeana_model::DomainError::new(codeana_model::ErrorCode::FfiNotificationProtocol, "")
        );
        Ok(())
    }
    /// Backends whose protocol closes its duplicate after the terminal token
    /// additionally reject duplicate bytes and require EOF. Java keeps wait().
    pub async fn wait_eof(mut self) -> Result<()> {
        use tokio::io::AsyncReadExt;
        let mut token = [0];
        self.0
            .read_exact(&mut token)
            .await
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::FfiNotificationIncomplete,
                "",
            ))?;
        ensure!(
            token[0] == 1,
            codeana_model::DomainError::new(codeana_model::ErrorCode::FfiNotificationProtocol, "")
        );
        let count = self.0.read(&mut token).await?;
        ensure!(
            count == 0,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::FfiNotificationProtocol,
                "duplicate notification"
            )
        );
        Ok(())
    }
}

/// RAII transfer: dropping an active handoff records Unknown instead of pretending zero engine work.
pub struct BudgetHandoff {
    lease: codeana_model::EngineAccountingLease,
}
impl BudgetHandoff {
    pub fn begin(
        context: &codeana_model::AnalysisContext,
        seed: &codeana_model::AnalysisUsage,
    ) -> Result<Self> {
        let lease = context
            .transfer_to_engine(seed)
            .map_err(codeana_model::DomainError::analysis)?;
        Ok(Self { lease })
    }
    pub fn complete(&mut self, usage: codeana_model::AnalysisUsage) -> Result<()> {
        self.lease
            .complete_usage(usage)
            .map_err(codeana_model::DomainError::analysis)?;
        Ok(())
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

#[cfg(test)]
mod handoff_tests {
    use super::*;
    use codeana_model::{AccountingOwner, AnalysisContext, AnalysisUsage};
    #[test]
    fn engine_owned_clones_cannot_debit_or_change_the_seed_and_return_is_monotonic() {
        let context = AnalysisContext::new(Default::default(), None).unwrap();
        context.charge(3, 2, 1).unwrap();
        let seed = context.usage();
        let mut handoff = BudgetHandoff::begin(&context, &seed).unwrap();
        let clone = context.clone();
        assert_eq!(context.accounting_owner(), AccountingOwner::EngineOwned);
        assert!(clone.charge(1, 1, 1).is_err());
        assert!(clone.observe_depth(2).is_err());
        assert!(clone.child_scope(Default::default()).is_err());
        clone.fail(codeana_model::AnalysisFailure::Deadline);
        assert_eq!(clone.usage(), seed);
        assert!(
            handoff
                .complete(AnalysisUsage {
                    work: 2,
                    ..seed.clone()
                })
                .is_err()
        );
        assert_eq!(context.usage(), seed);
        handoff.complete(AnalysisUsage { work: 7, ..seed }).unwrap();
        assert_eq!(context.accounting_owner(), AccountingOwner::HostOwned);
        assert!(handoff.complete(context.usage()).is_err());
        clone.charge(1, 0, 0).unwrap();
        assert_eq!(context.usage().work, 8);
    }
    #[test]
    fn missing_receipt_or_cancellation_keeps_the_last_verified_watermark_unknown() {
        let context = AnalysisContext::new(Default::default(), None).unwrap();
        context.charge(3, 2, 1).unwrap();
        let seed = context.usage();
        let handoff = BudgetHandoff::begin(&context, &seed).unwrap();
        context.cancel();
        assert!(context.checkpoint().is_err());
        assert_eq!(context.usage(), seed);
        drop(handoff);
        assert_eq!(context.accounting_owner(), AccountingOwner::Unknown);
        assert!(context.charge(1, 0, 0).is_err());
        assert_eq!(context.usage(), seed);
    }
    #[test]
    fn ownership_is_per_account_and_a_stale_seed_cannot_steal_it() {
        let first = AnalysisContext::new(Default::default(), None).unwrap();
        let second = AnalysisContext::new(Default::default(), None).unwrap();
        let stale = first.usage();
        first.charge(1, 0, 0).unwrap();
        assert!(BudgetHandoff::begin(&first, &stale).is_err());
        let handoff = BudgetHandoff::begin(&first, &first.usage()).unwrap();
        second.charge(1, 1, 1).unwrap();
        assert_eq!(second.usage().work, 1);
        let clone = first.clone();
        let (tx, rx) = std::sync::mpsc::channel();
        let thread = std::thread::spawn(move || tx.send(clone.charge(1, 0, 0).is_err()).unwrap());
        assert!(rx.recv_timeout(std::time::Duration::from_secs(1)).unwrap());
        thread.join().unwrap();
        drop(handoff);
    }
    #[tokio::test]
    async fn notification_requires_exactly_the_bounded_completion_token() {
        use std::io::Write;
        for token in [0u8, 1, 2] {
            let (notification, mut writer) = Notification::pair().unwrap();
            writer.write_all(&[token]).unwrap();
            assert_eq!(notification.wait().await.is_ok(), token == 1);
        }
        let (notification, writer) = Notification::pair().unwrap();
        drop(writer);
        assert!(notification.wait().await.is_err());
    }
    #[tokio::test]
    async fn closing_notification_protocol_requires_one_token_then_eof() {
        use std::io::Write;
        for bytes in [&[][..], &[1][..], &[0][..], &[1, 1][..], &[1, 0][..]] {
            let (notification, mut writer) = Notification::pair().unwrap();
            writer.write_all(bytes).unwrap();
            drop(writer);
            let result =
                tokio::time::timeout(std::time::Duration::from_secs(1), notification.wait_eof())
                    .await
                    .unwrap();
            assert_eq!(result.is_ok(), bytes == [1]);
        }
    }
}
