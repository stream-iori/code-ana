//! Owned children retain their frozen inputs until a verified close or process-tree stop.
use anyhow::{Context, Result, ensure};
use codeana_config::{Capture, CapturedBlob};
use std::{
    collections::BTreeMap,
    sync::{Arc, Condvar, Mutex, OnceLock},
    time::Duration,
};
use tokio::{
    io::AsyncReadExt,
    process::{Child, Command},
    sync::Notify,
};

#[derive(Default)]
pub(super) struct InputLease {
    _directories: Vec<Arc<tempfile::TempDir>>,
    _blobs: Vec<CapturedBlob>,
}
impl InputLease {
    pub fn new(capture: &Capture, directories: Vec<Arc<tempfile::TempDir>>) -> Self {
        let mut spools = std::collections::BTreeSet::new();
        let mut blobs = Vec::new();
        for blob in capture.blobs.values() {
            if spools.insert(blob.as_ref().parent()) {
                blobs.push(blob.clone());
            }
        }
        Self {
            _directories: directories,
            _blobs: blobs,
        }
    }
    #[cfg(test)]
    pub fn directory(directory: Arc<tempfile::TempDir>) -> Self {
        Self {
            _directories: vec![directory],
            _blobs: vec![],
        }
    }
}

#[derive(Default)]
struct Completion {
    state: Mutex<Option<std::result::Result<std::process::ExitStatus, String>>>,
    changed: Notify,
}
impl Completion {
    fn set(&self, state: std::result::Result<std::process::ExitStatus, String>) {
        *self.state.lock().unwrap() = Some(state);
        self.changed.notify_waiters();
    }
    fn confirmed(&self) -> bool {
        matches!(*self.state.lock().unwrap(), Some(Ok(_)))
    }
    async fn wait(&self) -> Result<std::process::ExitStatus> {
        loop {
            let changed = self.changed.notified();
            let state = self.state.lock().unwrap().clone();
            if let Some(state) = state {
                return state.map_err(anyhow::Error::msg);
            }
            changed.await;
        }
    }
}
struct Pending {
    child: Child,
    group: u32,
    _inputs: Arc<InputLease>,
    completion: Arc<Completion>,
    #[cfg(test)]
    fault: Option<Arc<TestFault>>,
}
#[derive(Default)]
struct Registry {
    next: u64,
    pending: BTreeMap<u64, Pending>,
}
struct Reaper {
    registry: Mutex<Registry>,
    wake: Condvar,
}
static REAPER: OnceLock<Reaper> = OnceLock::new();

/// The static registry also pins inputs if the task runtime is shut down or the
/// reaper cannot prove a stop. It never drops unconfirmed records on an error.
fn reaper() -> &'static Reaper {
    let reaper = REAPER.get_or_init(|| Reaper {
        registry: Mutex::new(Registry::default()),
        wake: Condvar::new(),
    });
    static START: OnceLock<()> = OnceLock::new();
    START.get_or_init(|| {
        std::thread::Builder::new()
            .name("codeana-worker-reaper".into())
            .spawn(reap_loop)
            .expect("worker reaper thread");
    });
    reaper
}
pub(super) fn admit_launch() -> Result<()> {
    ensure!(
        reaper().registry.lock().unwrap().pending.len() < 1024,
        "WORKER_REAP_CAPACITY: unconfirmed processes retain their inputs"
    );
    Ok(())
}

/// One bounded OS snapshot serves every pending process. Keep zombie leaders
/// waitable until all non-zombie members of their exclusive group have stopped.
async fn live_groups() -> Result<std::collections::BTreeSet<u32>> {
    tokio::time::timeout(Duration::from_secs(1), async {
        let mut child = Command::new("/bin/ps")
            .args(["-axo", "pgid=,stat="])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true)
            .spawn()?;
        let mut output = Vec::new();
        child
            .stdout
            .take()
            .context("WORKER_PROCESS_SNAPSHOT_PIPE")?
            .take((1 << 20) + 1)
            .read_to_end(&mut output)
            .await?;
        ensure!(output.len() <= 1 << 20, "WORKER_PROCESS_SNAPSHOT_BUDGET");
        ensure!(
            child.wait().await?.success(),
            "WORKER_PROCESS_SNAPSHOT_FAILED"
        );
        let mut groups = std::collections::BTreeSet::new();
        for row in std::str::from_utf8(&output)?.lines() {
            let mut columns = row.split_whitespace();
            let group = columns
                .next()
                .context("WORKER_PROCESS_SNAPSHOT_ROW")?
                .parse::<u32>()?;
            let state = columns.next().context("WORKER_PROCESS_SNAPSHOT_ROW")?;
            if !state.starts_with('Z') {
                groups.insert(group);
            }
        }
        Ok(groups)
    })
    .await?
}
fn reap_loop() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("worker reaper runtime");
    let reaper = REAPER.get().unwrap();
    loop {
        let mut registry = reaper.registry.lock().unwrap();
        while registry.pending.is_empty() {
            registry = reaper.wake.wait(registry).unwrap();
        }
        for pending in registry.pending.values_mut() {
            #[cfg(test)]
            if let Some(fault) = &pending.fault {
                if !fault.allow.load(std::sync::atomic::Ordering::SeqCst) {
                    continue;
                }
                if fault.fail.swap(false, std::sync::atomic::Ordering::SeqCst) {
                    fault
                        .allow
                        .store(false, std::sync::atomic::Ordering::SeqCst);
                    pending.completion.set(Err(
                        "WORKER_STOP_UNCONFIRMED: injected signal refusal".into()
                    ));
                    continue;
                }
            }
            // The leader has not been reaped, so this numeric group cannot be reused.
            let signal = unsafe { libc::kill(-(pending.group as i32), libc::SIGKILL) };
            if signal != 0 {
                let error = std::io::Error::last_os_error();
                // Darwin can report EPERM while a killed group is becoming zombies.
                // The independent live-member snapshot still gates every reap/release;
                // a genuinely live denied group reaches the bounded stop timeout.
                if !matches!(error.raw_os_error(), Some(libc::ESRCH) | Some(libc::EPERM)) {
                    pending
                        .completion
                        .set(Err(format!("WORKER_STOP_UNCONFIRMED: {error}")));
                }
            }
        }
        drop(registry);
        let groups = runtime.block_on(live_groups());
        let mut registry = reaper.registry.lock().unwrap();
        let mut done = Vec::new();
        for (id, pending) in &mut registry.pending {
            #[cfg(test)]
            if pending
                .fault
                .as_ref()
                .is_some_and(|f| !f.allow.load(std::sync::atomic::Ordering::SeqCst))
            {
                continue;
            }
            match &groups {
                Ok(groups) if !groups.contains(&pending.group) => match pending.child.try_wait() {
                    Ok(Some(status)) => {
                        done.push((*id, status));
                    }
                    Ok(None) => (),
                    Err(error) => pending
                        .completion
                        .set(Err(format!("WORKER_STOP_UNCONFIRMED: {error}"))),
                },
                Err(error) => pending
                    .completion
                    .set(Err(format!("WORKER_STOP_UNCONFIRMED: {error:#}"))),
                _ => (),
            }
        }
        for (id, status) in done {
            if let Some(pending) = registry.pending.remove(&id) {
                let completion = pending.completion.clone();
                drop(pending);
                completion.set(Ok(status));
            }
        }
        let _ = reaper
            .wake
            .wait_timeout(registry, Duration::from_millis(20))
            .unwrap();
    }
}

pub(super) struct Process {
    child: Option<Child>,
    group: u32,
    inputs: Option<Arc<InputLease>>,
    completion: Option<Arc<Completion>>,
    #[cfg(test)]
    fault: Option<Arc<TestFault>>,
}
impl Process {
    pub fn new(child: Child, group: u32, inputs: Arc<InputLease>) -> Self {
        Self {
            child: Some(child),
            group,
            inputs: Some(inputs),
            completion: None,
            #[cfg(test)]
            fault: None,
        }
    }
    pub fn id(&self) -> Option<u32> {
        self.child.as_ref().and_then(Child::id).or_else(|| {
            self.completion
                .as_ref()
                .filter(|c| !c.confirmed())
                .map(|_| self.group)
        })
    }
    pub fn release_inputs(&mut self) {
        self.inputs.take();
    }
    pub fn replace_inputs(&mut self, inputs: Arc<InputLease>) -> Result<()> {
        ensure!(self.inputs.is_none(), "WORKER_INPUTS_STILL_OWNED");
        self.inputs = Some(inputs);
        Ok(())
    }
    /// Used only after a protocol close that guarantees descendant/input cleanup.
    pub async fn wait_closed(&mut self) -> Result<std::process::ExitStatus> {
        let status = self
            .child
            .as_mut()
            .context("WORKER_PROCESS_RETIRING")?
            .wait()
            .await?;
        self.release_inputs();
        Ok(status)
    }
    fn retire(&mut self) {
        let Some(child) = self.child.take() else {
            return;
        };
        if child.id().is_none() {
            self.release_inputs();
            return;
        }
        let completion = Arc::new(Completion::default());
        // Signal before handing off, including during runtime teardown.
        #[cfg(test)]
        let signal_now = self.fault.is_none();
        #[cfg(not(test))]
        let signal_now = true;
        if signal_now {
            unsafe {
                libc::kill(-(self.group as i32), libc::SIGKILL);
            }
        }
        let pending = Pending {
            child,
            group: self.group,
            _inputs: self.inputs.take().unwrap_or_default(),
            completion: completion.clone(),
            #[cfg(test)]
            fault: self.fault.clone(),
        };
        let reaper = reaper();
        let mut registry = reaper.registry.lock().unwrap();
        let id = registry.next;
        registry.next = registry
            .next
            .checked_add(1)
            .expect("worker reaper identity");
        registry.pending.insert(id, pending);
        self.completion = Some(completion);
        reaper.wake.notify_one();
    }
    pub async fn shutdown(&mut self) -> Result<std::process::ExitStatus> {
        self.shutdown_bounded(Duration::from_secs(2)).await
    }
    async fn shutdown_bounded(&mut self, timeout: Duration) -> Result<std::process::ExitStatus> {
        self.retire();
        let completion = self.completion.as_ref().context("WORKER_ALREADY_REAPED")?;
        tokio::time::timeout(timeout, completion.wait())
            .await
            .context("WORKER_STOP_UNCONFIRMED: inputs retained")?
    }
}
impl Drop for Process {
    fn drop(&mut self) {
        self.retire();
    }
}

#[cfg(test)]
struct TestFault {
    allow: std::sync::atomic::AtomicBool,
    fail: std::sync::atomic::AtomicBool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};

    struct Release(Arc<TestFault>);
    impl Drop for Release {
        fn drop(&mut self) {
            self.0.fail.store(false, Ordering::SeqCst);
            self.0.allow.store(true, Ordering::SeqCst);
            reaper().wake.notify_one();
        }
    }
    fn fault(allow: bool, fail: bool) -> Arc<TestFault> {
        Arc::new(TestFault {
            allow: AtomicBool::new(allow),
            fail: AtomicBool::new(fail),
        })
    }
    async fn stopped(completion: &Completion) -> Result<()> {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let changed = completion.changed.notified();
                if completion.confirmed() {
                    break;
                }
                changed.await;
            }
        })
        .await?;
        Ok(())
    }
    fn sleeping(inputs: InputLease, fault: Arc<TestFault>) -> Result<Process> {
        let child = Command::new("/bin/sleep")
            .arg("60")
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .process_group(0)
            .kill_on_drop(true)
            .spawn()?;
        let group = child.id().unwrap();
        let mut process = Process::new(child, group, Arc::new(inputs));
        process.fault = Some(fault);
        Ok(process)
    }
    fn pending(group: u32) -> Arc<Completion> {
        reaper()
            .registry
            .lock()
            .unwrap()
            .pending
            .values()
            .find(|p| p.group == group)
            .unwrap()
            .completion
            .clone()
    }
    #[tokio::test]
    async fn failed_stop_retains_inputs_until_the_owned_process_is_confirmed_dead() -> Result<()> {
        let directory = Arc::new(tempfile::tempdir()?);
        let path = directory.path().to_owned();
        std::fs::write(path.join("input"), "owned")?;
        let fault = fault(true, true);
        let _release = Release(fault.clone());
        let mut process = sleeping(InputLease::directory(directory.clone()), fault.clone())?;
        let pid = process.id().unwrap();
        drop(directory);
        assert!(
            process
                .shutdown()
                .await
                .unwrap_err()
                .to_string()
                .contains("UNCONFIRMED")
        );
        assert!(path.join("input").is_file());
        assert_eq!(unsafe { libc::kill(pid as i32, 0) }, 0);
        let completion = process.completion.as_ref().unwrap().clone();
        drop(process);
        assert!(path.exists());
        fault.allow.store(true, Ordering::SeqCst);
        reaper().wake.notify_one();
        stopped(&completion).await?;
        assert!(!path.exists());
        Ok(())
    }
    #[tokio::test]
    async fn dropping_the_task_keeps_frozen_bytes_until_background_reaping() -> Result<()> {
        let (project, capture) = crate::python_inputs::tests::capture();
        let source = capture
            .manifest
            .files
            .iter()
            .find(|s| s.language == "python")
            .unwrap();
        let path = capture.blobs[&source.hash].as_ref().to_owned();
        let fault = fault(false, false);
        let _release = Release(fault.clone());
        let process = sleeping(InputLease::new(&capture, vec![]), fault.clone())?;
        let group = process.group;
        drop(process);
        let completion = pending(group);
        drop(capture);
        drop(project);
        assert_eq!(std::fs::read(&path)?, b"def stock():\n    return 1\n");
        fault.allow.store(true, Ordering::SeqCst);
        reaper().wake.notify_one();
        stopped(&completion).await?;
        assert!(!path.exists());
        Ok(())
    }
    #[tokio::test]
    async fn task_runtime_shutdown_cannot_drop_unconfirmed_input_guards() -> Result<()> {
        let fault = fault(false, false);
        let _release = Release(fault.clone());
        let child_fault = fault.clone();
        let (path, group) = std::thread::spawn(move || -> Result<_> {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()?;
            runtime.block_on(async {
                let (sender, receiver) = tokio::sync::oneshot::channel();
                tokio::spawn(async move {
                    let directory = Arc::new(tempfile::tempdir().unwrap());
                    let path = directory.path().to_owned();
                    let process =
                        sleeping(InputLease::directory(directory.clone()), child_fault).unwrap();
                    let group = process.group;
                    drop(directory);
                    let _process = process;
                    sender.send((path, group)).unwrap();
                    std::future::pending::<()>().await;
                });
                Ok(receiver.await?)
            })
        })
        .join()
        .unwrap()?;
        let completion = pending(group);
        assert!(path.exists());
        fault.allow.store(true, Ordering::SeqCst);
        reaper().wake.notify_one();
        stopped(&completion).await?;
        assert!(!path.exists());
        Ok(())
    }
    #[tokio::test]
    async fn stop_timeout_does_not_release_inputs_or_reap_a_still_live_leader() -> Result<()> {
        let directory = Arc::new(tempfile::tempdir()?);
        let path = directory.path().to_owned();
        let fault = fault(false, false);
        let _release = Release(fault.clone());
        let mut process = sleeping(InputLease::directory(directory.clone()), fault.clone())?;
        let group = process.group;
        drop(directory);
        assert!(
            process
                .shutdown_bounded(Duration::from_millis(1))
                .await
                .is_err()
        );
        assert!(path.exists());
        assert_eq!(unsafe { libc::kill(group as i32, 0) }, 0);
        let completion = pending(group);
        drop(process);
        fault.allow.store(true, Ordering::SeqCst);
        reaper().wake.notify_one();
        stopped(&completion).await?;
        assert!(!path.exists());
        Ok(())
    }
    #[tokio::test]
    async fn shutdown_confirms_the_descendant_group_before_releasing_inputs() -> Result<()> {
        use tokio::io::AsyncBufReadExt;
        let mut child = Command::new("/bin/sh")
            .args(["-c", "sleep 60 & echo $!; wait"])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .process_group(0)
            .kill_on_drop(true)
            .spawn()?;
        let group = child.id().unwrap();
        let stdout = child.stdout.take().unwrap();
        let mut line = String::new();
        let mut reader = tokio::io::BufReader::new(stdout.take(32));
        tokio::time::timeout(Duration::from_secs(2), reader.read_line(&mut line)).await??;
        drop(reader);
        let descendant = line.trim().parse::<u32>()?;
        let directory = Arc::new(tempfile::tempdir()?);
        let path = directory.path().to_owned();
        let mut process = Process::new(
            child,
            group,
            Arc::new(InputLease::directory(directory.clone())),
        );
        drop(directory);
        process.shutdown().await?;
        assert!(!path.exists());
        let status = Command::new("/bin/ps")
            .args(["-p", &descendant.to_string(), "-o", "pgid=,stat="])
            .output()
            .await?;
        if status.status.success() {
            let text = std::str::from_utf8(&status.stdout)?;
            let mut fields = text.split_whitespace();
            let observed = fields.next().unwrap().parse::<u32>()?;
            let state = fields.next().unwrap();
            assert!(
                observed != group || state.starts_with('Z'),
                "owned descendant still live: {text}"
            );
        }
        Ok(())
    }
}
