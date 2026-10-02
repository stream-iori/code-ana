use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::archive::{FactArchive, FactArchiveBuilder, SpooledAnalysis};
use codeana_contracts::{
    convert, envelope, fact_from_wire, read_frame, worker as w, worker::envelope::Body, write_frame,
};
use codeana_model::*;
use std::{os::fd::AsRawFd, path::Path, time::Duration};
use tokio::{
    net::UnixStream,
    process::{Child, Command},
};
use tokio_util::sync::CancellationToken;
pub struct Worker {
    child: Child,
    process_group: u32,
    command: String,
    ipc: UnixStream,
    logs: std::sync::Arc<std::sync::Mutex<Vec<u8>>>,
    log_task: tokio::task::JoinHandle<()>,
    monitor: tokio::task::JoinHandle<()>,
    memory_exceeded: std::sync::Arc<std::sync::atomic::AtomicBool>,
    pub attempt: String,
    pub context: BuildContext,
    output_limits: codeana_contracts::java::OutputLimits,
    scratch: std::sync::Arc<std::sync::Mutex<Option<super::java::DiskBudget>>>,
    scratch_exceeded: std::sync::Arc<std::sync::atomic::AtomicBool>,
}
impl Drop for Worker {
    fn drop(&mut self) {
        self.log_task.abort();
        self.monitor.abort();
        // SAFETY: this group was created exclusively for the owned worker. Reap only after
        // terminating descendants, including when the leader exited unexpectedly.
        unsafe {
            libc::kill(-(self.process_group as i32), libc::SIGKILL);
        }
        let _ = self.child.try_wait();
    }
}
impl Worker {
    pub async fn start(
        fixtures: Option<&Path>,
        java: Option<&super::java::PreparedJava>,
        checkout: &Path,
        manifest: &Manifest,
        cancel: &CancellationToken,
        memory_limit: u64,
    ) -> Result<Self> {
        let (a, b) = std::os::unix::net::UnixStream::pair()?;
        a.set_nonblocking(true)?;
        let fd = b.as_raw_fd();
        let binary = std::env::current_exe()?.with_file_name(if fixtures.is_some() {
            "codeana-mock-worker"
        } else {
            "codeana-java-worker"
        });
        let mut cmd = Command::new(&binary);
        if let Some(fixtures) = fixtures {
            cmd.arg("--fixtures").arg(fixtures);
        }
        if let Some(java) = java {
            cmd.arg(&java.library);
        }
        cmd.arg("--fd")
            .arg("3")
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .process_group(0);
        // SAFETY: only async-signal-safe descriptor operations execute after fork. dup2 transfers the owned IPC endpoint, never project text.
        unsafe {
            cmd.pre_exec(move || {
                if fd != 3 && libc::dup2(fd, 3) < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                if libc::fcntl(3, libc::F_SETFD, 0) < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
        let command = serde_json::to_string(
            &std::iter::once(cmd.as_std().get_program())
                .chain(cmd.as_std().get_args())
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
        )?;
        let mut child = cmd.spawn().context("WORKER_NOT_INSTALLED")?;
        let mut stderr = child.stderr.take().context("WORKER_STDERR")?;
        let logs = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let captured = logs.clone();
        let log_task = tokio::spawn(async move {
            use tokio::io::AsyncReadExt;
            let mut buf = [0u8; 1024];
            while let Ok(n) = stderr.read(&mut buf).await {
                if n == 0 {
                    break;
                }
                let mut tail = captured.lock().unwrap();
                tail.extend_from_slice(&buf[..n]);
                let excess = tail.len().saturating_sub(4096);
                tail.drain(..excess);
            }
        });
        let pid = child.id().context("WORKER_PID_REQUIRED")?;
        let memory_exceeded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let exceeded = memory_exceeded.clone();
        let scratch =
            std::sync::Arc::new(std::sync::Mutex::new(java.map(|j| j.disk_budget.clone())));
        let monitored_scratch = scratch.clone();
        let scratch_exceeded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let disk_exceeded = scratch_exceeded.clone();
        let monitor = tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_millis(100));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                interval.tick().await;
                let budget = monitored_scratch.lock().unwrap().clone();
                if let Some(budget) = budget {
                    let check = tokio::task::spawn_blocking(move || budget.check()).await;
                    if !matches!(check, Ok(Ok(()))) {
                        disk_exceeded.store(true, std::sync::atomic::Ordering::SeqCst);
                        unsafe {
                            libc::kill(-(pid as i32), libc::SIGKILL);
                        }
                        break;
                    }
                }
                let sample = tokio::time::timeout(
                    Duration::from_secs(1),
                    Command::new("/bin/ps")
                        .args(["-axo", "pgid=,rss="])
                        .kill_on_drop(true)
                        .output(),
                )
                .await;
                if let Ok(Ok(output)) = sample {
                    let rss: u64 = String::from_utf8_lossy(&output.stdout)
                        .lines()
                        .filter_map(|line| {
                            let mut columns = line.split_whitespace();
                            let group = columns.next()?.parse::<u32>().ok()?;
                            let rss = columns.next()?.parse::<u64>().ok()?;
                            (group == pid).then_some(rss.saturating_mul(1024))
                        })
                        .sum();
                    if rss > memory_limit {
                        exceeded.store(true, std::sync::atomic::Ordering::SeqCst);
                        unsafe {
                            libc::kill(-(pid as i32), libc::SIGKILL);
                        }
                        break;
                    }
                }
            }
        });
        drop(b);
        let mut worker = Self {
            output_limits: java
                .map(|j| codeana_contracts::java::OutputLimits::from_config(&j.config))
                .transpose()?
                .unwrap_or_default(),
            scratch,
            scratch_exceeded,
            monitor,
            memory_exceeded,
            child,
            process_group: pid,
            command,
            logs,
            log_task,
            ipc: UnixStream::from_std(a)?,
            attempt: new_id("attempt"),
            context: BuildContext {
                language: String::new(),
                modules: vec![],
                source_version: String::new(),
                classpath: vec![],
                producer: String::new(),
                capabilities: vec![],
            },
        };
        worker
            .send(Body::Hello(w::Hello {
                protocol: WORKER_PROTOCOL_VERSION,
                fact_ir: VERSION,
                producer: "daemon".into(),
            }))
            .await?;
        match worker.receive(cancel).await? {
            Body::Hello(h) => ensure!(
                h.protocol == WORKER_PROTOCOL_VERSION
                    && h.fact_ir == VERSION
                    && h.producer
                        == if fixtures.is_some() {
                            "mock-fixture-v1"
                        } else {
                            "javac-java8-v1"
                        },
                "WORKER_VERSION_MISMATCH"
            ),
            _ => bail!("EXPECTED_HELLO"),
        };
        worker
            .send(Body::Discover(w::Discover {
                manifest: Some(convert(manifest)?),
                java: java.map(|j| j.config.clone()),
            }))
            .await?;
        worker.context = match worker.receive(cancel).await? {
            Body::Context(c) => convert(&c)?,
            Body::Advice(a) => {
                let mut advice: BuildAdvice = convert(&a)?;
                ensure!(!advice.missing.is_empty(), "EMPTY_BUILD_ADVICE");
                advice.capture = manifest.id();
                advice.cwd = if advice.cwd == "." {
                    checkout.to_string_lossy().into_owned()
                } else if Path::new(&advice.cwd).is_absolute() {
                    advice.cwd
                } else {
                    ensure!(safe_path(&advice.cwd), "INVALID_BUILD_ADVICE_CWD");
                    checkout.join(&advice.cwd).to_string_lossy().into_owned()
                };
                let relative = Path::new(&advice.cwd)
                    .strip_prefix(checkout)
                    .context("BUILD_ADVICE_OUTSIDE_CHECKOUT")?;
                ensure!(
                    relative.as_os_str().is_empty()
                        || relative
                            .components()
                            .all(|c| matches!(c, std::path::Component::Normal(_))),
                    "INVALID_BUILD_ADVICE_CWD"
                );
                bail!("BUILD_INPUT_REQUIRED: {}", serde_json::to_string(&advice)?);
            }
            _ => bail!("EXPECTED_BUILD_CONTEXT"),
        };
        ensure!(
            fixtures.is_none() || worker.context.producer.starts_with("mock-"),
            "MOCK_PROVENANCE_REQUIRED"
        );
        Ok(worker)
    }
    async fn send(&mut self, body: Body) -> Result<()> {
        tokio::time::timeout(
            Duration::from_secs(5),
            write_frame(&mut self.ipc, &envelope(&self.attempt, body)),
        )
        .await??;
        Ok(())
    }
    async fn receive(&mut self, cancel: &CancellationToken) -> Result<Body> {
        let frame = tokio::select! {
            result=read_frame(&mut self.ipc)=>match result {
                Ok(frame)=>frame,
                Err(error)=>{
                    let status=tokio::time::timeout(Duration::from_millis(100),self.child.wait()).await;
                    tokio::task::yield_now().await;
                    let logs=String::from_utf8_lossy(&self.logs.lock().unwrap()).into_owned();
                    if self.scratch_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!("JAVA_SCRATCH_BUDGET");}
                    if self.memory_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!("WORKER_MEMORY_BUDGET");}
                    let status=match status {Ok(Ok(s))=>s.to_string(),Ok(Err(e))=>e.to_string(),Err(_)=>"still running".into()};
                    bail!("WORKER_PROTOCOL: {error}; pid={}; command={}; exit={status}; stderr={logs}",self.process_group,self.command);
                }
            },
            _=cancel.cancelled()=>{let _=self.send(Body::Cancel(w::Cancel{})).await;let _=tokio::time::timeout(Duration::from_secs(2),self.child.wait()).await;bail!("CANCELLED")}
        };
        ensure!(frame.attempt == self.attempt, "ATTEMPT_MISMATCH");
        match frame.body.context("EMPTY_FRAME")? {
            Body::Failure(f) => bail!("{}: {}", f.code, f.message),
            b => Ok(b),
        }
    }
    pub async fn analyze(
        &mut self,
        manifest: &Manifest,
        profile: &str,
        baseline: &str,
        old: Option<&FactArchive>,
        full: bool,
        cancel: &CancellationToken,
    ) -> Result<SpooledAnalysis> {
        let mut full = full;
        for retry in 0..2 {
            self.attempt = new_id("attempt");
            let baseline_input = if full { None } else { old };
            let mut sequence = 0;
            if let Some(archive) = baseline_input {
                for chunk in archive.chunks()? {
                    self.send(Body::BaselineChunk(w::BaselineChunk {
                        seq: sequence,
                        facts: chunk?,
                    }))
                    .await?;
                    sequence += 1;
                }
            }
            self.send(Body::BaselineEnd(w::BaselineEnd {
                count: baseline_input.map_or(0, |a| a.count),
                checksum: baseline_input
                    .map_or_else(|| digest(&Vec::<Fact>::new()), |a| a.checksum.clone()),
            }))
            .await?;
            self.send(Body::Analyze(w::Analyze {
                manifest: Some(convert(manifest)?),
                profile: profile.into(),
                baseline: baseline.into(),
                full,
                baseline_facts: vec![],
            }))
            .await?;
            let begin = match self.receive(cancel).await? {
                Body::Begin(b) => b,
                Body::Replan(_) if retry == 0 && !full => {
                    full = true;
                    continue;
                }
                _ => bail!("EXPECTED_RESULT_BEGIN"),
            };
            ensure!(
                begin.manifest == manifest.id()
                    && begin.profile == profile
                    && begin.baseline == baseline
                    && begin.producer == self.context.producer,
                "RESULT_IDENTITY_MISMATCH"
            );
            ensure!(!full || begin.mode == "full", "EXPECTED_FULL_RESULT");
            let mut facts = match self.scratch.lock().unwrap().as_ref() {
                Some(budget) => FactArchiveBuilder::new_in(
                    self.output_limits.bytes,
                    self.output_limits.facts,
                    &budget.root,
                )?,
                None => {
                    FactArchiveBuilder::new(self.output_limits.bytes, self.output_limits.facts)?
                }
            };
            let mut seq = 0;
            let archive = loop {
                match self.receive(cancel).await? {
                    Body::Chunk(chunk) => {
                        ensure!(chunk.seq == seq, "CHUNK_SEQUENCE_MISMATCH");
                        seq += 1;
                        for f in chunk.facts {
                            let f = fact_from_wire(f)?;
                            facts.push(&f)?;
                        }
                    }
                    Body::End(end) => {
                        ensure!(
                            end.chunks == seq && end.count == facts.count(),
                            "RESULT_CHECKSUM_MISMATCH"
                        );
                        let sealed = facts.seal()?;
                        ensure!(end.checksum == sealed.checksum, "RESULT_CHECKSUM_MISMATCH");
                        break sealed;
                    }
                    _ => bail!("UNEXPECTED_RESULT_MESSAGE"),
                }
            };
            let r = AnalysisHeader {
                mode: begin.mode,
                baseline: begin.baseline,
                manifest: begin.manifest,
                profile: begin.profile,
                producer: begin.producer,
                replacements: begin
                    .replacements
                    .iter()
                    .map(convert)
                    .collect::<Result<_>>()?,
            };
            r.validate(manifest).map_err(anyhow::Error::msg)?;
            return Ok(SpooledAnalysis {
                header: r,
                records: archive,
            });
        }
        bail!("REPLAN_BUDGET")
    }
    pub async fn ack(&mut self, revision: &str) -> Result<()> {
        self.send(Body::Ack(w::Ack {
            revision: revision.into(),
        }))
        .await?;
        if self.context.producer.starts_with("mock-") {
            let status = tokio::time::timeout(Duration::from_secs(2), self.child.wait()).await??;
            ensure!(status.success(), "WORKER_EXIT_AFTER_ACK");
        } else {
            let frame =
                tokio::time::timeout(Duration::from_secs(2), read_frame(&mut self.ipc)).await??;
            ensure!(frame.attempt == self.attempt, "ACK_ATTEMPT_MISMATCH");
            match frame.body {
                Some(Body::Acked(acked)) => {
                    ensure!(acked.revision == revision, "ACK_REVISION_MISMATCH");
                }
                Some(Body::Failure(failure)) => bail!("{}: {}", failure.code, failure.message),
                _ => bail!("EXPECTED_ACKED"),
            }
        }
        Ok(())
    }

    pub fn alive(&mut self) -> Result<bool> {
        Ok(self.child.try_wait()?.is_none()
            && !self
                .memory_exceeded
                .load(std::sync::atomic::Ordering::SeqCst)
            && !self
                .scratch_exceeded
                .load(std::sync::atomic::Ordering::SeqCst))
    }

    pub async fn rediscover(
        &mut self,
        java: &super::java::PreparedJava,
        manifest: &Manifest,
        cancel: &CancellationToken,
    ) -> Result<()> {
        ensure!(self.alive()?, "WARM_WORKER_EXITED");
        *self.scratch.lock().unwrap() = Some(java.disk_budget.clone());
        self.output_limits = codeana_contracts::java::OutputLimits::from_config(&java.config)?;
        self.attempt = new_id("attempt");
        self.send(Body::Discover(w::Discover {
            manifest: Some(convert(manifest)?),
            java: Some(java.config.clone()),
        }))
        .await?;
        self.context = match self.receive(cancel).await? {
            Body::Context(context) => convert(&context)?,
            _ => bail!("EXPECTED_BUILD_CONTEXT"),
        };
        ensure!(self.context.language == "java", "WORKER_LANGUAGE_MISMATCH");
        Ok(())
    }
}
