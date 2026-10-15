use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::graph_archive::{Builder as GraphArchiveBuilder, GraphArchive};
pub struct SpooledAnalysis {
    pub header: AnalysisHeader,
    pub records: GraphArchive,
}
use codeana_contracts::{
    envelope, map, read_frame, worker as w, worker::envelope::Body, write_frame,
};
use codeana_model::*;
use std::{os::fd::AsRawFd, path::Path, time::Duration};
use tokio::{
    net::UnixStream,
    process::{Child, Command},
};
use tokio_util::sync::CancellationToken;
// A Java Ack also releases the native task and atomically persists bounded derived
// artifacts. The cache lock alone may wait five seconds; the old two-second IPC
// deadline killed healthy workers after a successful publication.
const JAVA_ACK_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, serde::Serialize)]
pub(super) struct MemoryUsage {
    limit_bytes: u64,
    rss_bytes: u64,
    peak_rss_bytes: u64,
    stage: String,
    process_group: u32,
    phase_ms: std::collections::BTreeMap<String, u64>,
    stream: Option<codeana_contracts::stream::StreamSummary>,
}
impl MemoryUsage {
    fn sample(&mut self, rss: u64) -> bool {
        self.rss_bytes = rss;
        self.peak_rss_bytes = self.peak_rss_bytes.max(rss);
        rss > self.limit_bytes
    }
    fn observe_phase(&mut self, log: &[u8]) {
        for line in String::from_utf8_lossy(log).lines() {
            if let Some(timing) = line.strip_prefix("codeana-rust-timing ")
                && let Some((name, value)) = timing.split_once('=')
                && let Ok(value) = value.parse::<u64>()
            {
                self.phase_ms.insert(name.into(), value);
            }
            if let Some(summary) = line.strip_prefix("codeana-rust-stream ")
                && let Ok(value) = serde_json::from_str(summary)
            {
                self.stream = Some(value);
            }
            if let Some(phase) = line.strip_prefix("codeana-rust-phase=")
                && matches!(
                    phase,
                    "inputs"
                        | "database_load"
                        | "declarations"
                        | "bindings"
                        | "archive"
                        | "transport"
                        | "incremental"
                        | "records"
                )
            {
                self.stage = phase.to_owned();
            }
        }
    }
}

async fn receive_ack(
    ipc: &mut UnixStream,
    attempt: &str,
    revision: &str,
    timeout: Duration,
) -> Result<()> {
    let frame = tokio::time::timeout(timeout, read_frame(ipc)).await??;
    ensure!(
        frame.attempt == attempt,
        codeana_model::DomainError::new(codeana_model::ErrorCode::AckAttemptMismatch, "")
    );
    match frame.body {
        Some(Body::Acked(acked)) => {
            ensure!(
                acked.revision == revision,
                codeana_model::DomainError::new(codeana_model::ErrorCode::AckRevisionMismatch, "")
            );
        }
        Some(Body::Failure(failure)) => bail!(DomainError::adapter(failure.code, failure.message)),
        _ => bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::ExpectedAcked,
            ""
        )),
    }
    Ok(())
}
pub struct Worker {
    child: Child,
    process_group: u32,
    command: String,
    ipc: UnixStream,
    logs: std::sync::Arc<std::sync::Mutex<Vec<u8>>>,
    log_task: tokio::task::JoinHandle<()>,
    monitor: tokio::task::JoinHandle<()>,
    memory_exceeded: std::sync::Arc<std::sync::atomic::AtomicBool>,
    memory: std::sync::Arc<std::sync::Mutex<MemoryUsage>>,
    pub attempt: String,
    pub context: BuildContext,
    output_limits: codeana_contracts::graph_archive::OutputLimits,
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
        Self::start_language(
            fixtures,
            java,
            None,
            checkout,
            manifest,
            cancel,
            memory_limit,
        )
        .await
    }
    pub async fn start_language(
        fixtures: Option<&Path>,
        java: Option<&super::java::PreparedJava>,
        rust: Option<&w::RustConfig>,
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
        } else if rust.is_some() {
            "codeana-rust-worker"
        } else {
            "codeana-java-worker"
        });
        let mut cmd = Command::new(&binary);
        if let Some(fixtures) = fixtures {
            cmd.arg("--fixtures").arg(fixtures);
        }
        if let Some(java) = java {
            cmd.arg(&java.library).arg("--backend").arg(java.backend);
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
        let mut child = cmd.spawn().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::WorkerNotInstalled,
            "",
        ))?;
        let memory = std::sync::Arc::new(std::sync::Mutex::new(MemoryUsage {
            limit_bytes: memory_limit,
            rss_bytes: 0,
            peak_rss_bytes: 0,
            stage: "starting".into(),
            process_group: child.id().context("WORKER_PID_REQUIRED")?,
            phase_ms: Default::default(),
            stream: None,
        }));
        let mut stderr = child
            .stderr
            .take()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::WorkerStderr,
                "",
            ))?;
        let logs = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let captured = logs.clone();
        let phase_memory = memory.clone();
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
                phase_memory.lock().unwrap().observe_phase(&tail);
            }
        });
        let pid = child.id().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::WorkerPidRequired,
            "",
        ))?;
        let memory_exceeded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let exceeded = memory_exceeded.clone();
        let scratch =
            std::sync::Arc::new(std::sync::Mutex::new(java.map(|j| j.disk_budget.clone())));
        let monitored_scratch = scratch.clone();
        let scratch_exceeded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let disk_exceeded = scratch_exceeded.clone();
        let sampled_memory = memory.clone();
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
                    if sampled_memory.lock().unwrap().sample(rss) {
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
                .map(|j| codeana_contracts::graph_archive::OutputLimits::from_config(&j.config))
                .transpose()?
                .unwrap_or_default(),
            scratch,
            scratch_exceeded,
            monitor,
            memory_exceeded,
            memory,
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
        if let Some(config) = rust {
            ensure!(
                (65536..=1073741824).contains(&config.result_bytes)
                    && (1..=2000000).contains(&config.fact_limit),
                "RUST_OUTPUT_LIMIT_RANGE"
            );
            worker.output_limits = codeana_contracts::graph_archive::OutputLimits {
                bytes: config.result_bytes,
                facts: config.fact_limit,
            };
        }
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
                        } else if rust.is_some() {
                            "rust-core"
                        } else {
                            "javac-java8-v1"
                        },
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::WorkerVersionMismatch,
                    ""
                )
            ),
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::ExpectedHello,
                ""
            )),
        };
        worker
            .send(Body::Discover(w::Discover {
                rust: rust.cloned().map(Box::new),
                manifest: Some(map(manifest)?),
                java: java.map(|j| j.config.clone()),
            }))
            .await?;
        worker.context = match worker.receive(cancel).await? {
            Body::Context(c) => map(&c)?,
            Body::Advice(a) => {
                let mut advice: BuildAdvice = map(&a)?;
                ensure!(
                    !advice.missing.is_empty(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::EmptyBuildAdvice, "")
                );
                advice.capture = manifest.id();
                advice.cwd = if advice.cwd == "." {
                    checkout.to_string_lossy().into_owned()
                } else if Path::new(&advice.cwd).is_absolute() {
                    advice.cwd
                } else {
                    ensure!(
                        safe_path(&advice.cwd),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::InvalidBuildAdviceCwd,
                            ""
                        )
                    );
                    checkout.join(&advice.cwd).to_string_lossy().into_owned()
                };
                let relative = Path::new(&advice.cwd).strip_prefix(checkout).context(
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::BuildAdviceOutsideCheckout,
                        "",
                    ),
                )?;
                ensure!(
                    relative.as_os_str().is_empty()
                        || relative
                            .components()
                            .all(|c| matches!(c, std::path::Component::Normal(_))),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::InvalidBuildAdviceCwd,
                        ""
                    )
                );
                bail!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::BuildInputRequired,
                    serde_json::to_string(&advice)?.to_string()
                ));
            }
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::ExpectedBuildContext,
                ""
            )),
        };
        ensure!(
            fixtures.is_none() || worker.context.producer.starts_with("mock-"),
            codeana_model::DomainError::new(codeana_model::ErrorCode::MockProvenanceRequired, "")
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
                    if self.scratch_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaScratchBudget, ""));}
                    if self.memory_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerMemoryBudget, serde_json::to_string(&self.memory_usage())?));}
                    let status=match status {Ok(Ok(s))=>s.to_string(),Ok(Err(e))=>e.to_string(),Err(_)=>"still running".into()};
                    bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerProtocol, format!("{error}; pid={}; command={}; exit={status}; stderr={logs}", self.process_group,self.command)));
                }
            },
            _=cancel.cancelled()=>{
                let deadline=tokio::time::Instant::now()+Duration::from_secs(2);
                let _=tokio::time::timeout_at(deadline,self.send(Body::Cancel(w::Cancel{}))).await;
                let _=tokio::time::timeout_at(deadline,self.child.wait()).await;
                bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, ""))
            }
        };
        ensure!(
            frame.attempt == self.attempt,
            codeana_model::DomainError::new(codeana_model::ErrorCode::AttemptMismatch, "")
        );
        match frame.body.context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::EmptyFrame,
            "",
        ))? {
            Body::Failure(f) => bail!(DomainError::adapter(f.code, f.message)),
            b => Ok(b),
        }
    }

    pub(super) fn memory_usage(&self) -> MemoryUsage {
        self.memory.lock().unwrap().clone()
    }
    pub async fn analyze(
        &mut self,
        manifest: &Manifest,
        profile: &str,
        baseline: &str,
        old: Option<&BaselineInput>,
        full: bool,
        cancel: &CancellationToken,
    ) -> Result<SpooledAnalysis> {
        let root = self
            .scratch
            .lock()
            .unwrap()
            .as_ref()
            .map(|b| b.root.clone());
        let limits = self.output_limits;
        let mut archive_task = None;
        let header = self
            .analyze_into(manifest, profile, baseline, old, full, cancel, |_| {
                let (sender, records) = codeana_contracts::stream::GraphStream::channel();
                let root = root.clone();
                archive_task = Some(tokio::task::spawn_blocking(move || -> Result<_> {
                    let mut archive =
                        GraphArchiveBuilder::new(root.as_deref(), limits.bytes, limits.facts)?;
                    for row in records {
                        archive.push(&row?)?;
                    }
                    archive.seal()
                }));
                Ok(sender)
            })
            .await;
        let archive = match archive_task {
            Some(task) => Some(task.await?),
            None => None,
        };
        let header = header?;
        Ok(SpooledAnalysis {
            header,
            records: archive.context("RESULT_BEGIN_MISSING")??,
        })
    }
    #[allow(clippy::too_many_arguments)]
    pub async fn analyze_into(
        &mut self,
        manifest: &Manifest,
        profile: &str,
        baseline: &str,
        old: Option<&BaselineInput>,
        full: bool,
        cancel: &CancellationToken,
        mut begin_sink: impl FnMut(&AnalysisHeader) -> Result<codeana_contracts::stream::SinkSender>,
    ) -> Result<AnalysisHeader> {
        let diagnostics = codeana_contracts::performance::Report::new("transport");
        diagnostics
            .capture
            .scope(async {
                let mut full = full;
                for retry in 0..2 {
                    self.attempt = new_id("attempt");
                    let baseline_summary_json=if full { Vec::new() } else { old.map(serde_json::to_vec).transpose()?.unwrap_or_default() };
                    self.send(Body::Analyze(w::Analyze {
                        baseline_summary_json,
                        manifest: Some(map(manifest)?),
                        profile: profile.into(),
                        baseline: baseline.into(),
                        full,
                    }))
                    .await?;
                    let begin = match self.receive(cancel).await? {
                        Body::Begin(b) => b,
                        Body::Replan(_) if retry == 0 && !full => {
                            full = true;
                            continue;
                        }
                        _ => bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::ExpectedResultBegin, "")),
                    };
                    ensure!(
                        begin.manifest == manifest.id()
                            && begin.profile == profile
                            && begin.baseline == baseline
                            && begin.producer == self.context.producer, codeana_model::DomainError::new(codeana_model::ErrorCode::ResultIdentityMismatch, ""));
                    ensure!(!full || begin.mode == "full", codeana_model::DomainError::new(codeana_model::ErrorCode::ExpectedFullResult, ""));
                    let r = AnalysisHeader {
                        mode: begin.mode.parse()?, baseline: begin.baseline, manifest: begin.manifest,
                        profile: begin.profile, producer: begin.producer,
                        replacements: begin.replacements.iter().map(map).collect::<Result<_>>()?,
                    };
                    r.validate(manifest).map_err(anyhow::Error::msg)?;
                    let sink = begin_sink(&r)?;
                    let mut checksum = sha2::Sha256::new();
                    use sha2::Digest;
                    checksum.update(codeana_contracts::graph_archive::MAGIC);
                    let mut count = 0u64;
                    let mut bytes = 4u64;
                    let mut seq = 0;
                    loop {
                        match self.receive(cancel).await? {
                            Body::Chunk(chunk) => {
                                ensure!(!chunk.rows.is_empty() && chunk.rows.len() <= 64, codeana_model::DomainError::new(codeana_model::ErrorCode::FactChunkBudget, ""));
                                ensure!(chunk.seq == seq, codeana_model::DomainError::new(codeana_model::ErrorCode::ChunkSequenceMismatch, ""));
                                seq += 1;
                                let mut batch = Vec::new();
                                for record in chunk.rows {
                                    use prost::Message;
                                    let encoded=record.encode_to_vec();let size=encoded.len();
                                    ensure!(count<self.output_limits.facts && bytes+size as u64+4<=self.output_limits.bytes, codeana_model::DomainError::new(codeana_model::ErrorCode::GraphArchiveBudget, ""));
                                    codeana_contracts::graph_archive::update_digest(&mut checksum,&encoded);count+=1;bytes+=size as u64+4;
                                    batch.push(record);
                                }
                                if !batch.is_empty() {
                                    tokio::select! {
                                        _ = cancel.cancelled() => bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")),
                                        sent = sink.send(codeana_contracts::stream::GraphStreamEvent::Batch(batch)) => sent.map_err(|_| anyhow::anyhow!(codeana_model::DomainError::new(codeana_model::ErrorCode::CandidateStreamClosed, "")))?,
                                    }
                                }
                            }
                            Body::End(end) => {
                                ensure!(end.chunks == seq && end.count == count, codeana_model::DomainError::new(codeana_model::ErrorCode::ResultChecksumMismatch, ""));
                                ensure!(end.checksum == format!("{:x}", checksum.finalize()), codeana_model::DomainError::new(codeana_model::ErrorCode::ResultChecksumMismatch, ""));
                                tokio::select! {
                                    biased;
                                    _=cancel.cancelled()=>bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled,"")),
                                    sent=sink.send(codeana_contracts::stream::GraphStreamEvent::VerifiedEnd)=>sent.map_err(|_| anyhow::anyhow!(codeana_model::DomainError::new(codeana_model::ErrorCode::CandidateStreamClosed,"")))?,
                                }
                                eprintln!("codeana-stream {}",serde_json::to_string(&sink.metrics.summary())?);
                                return Ok(r);
                            }
                            _ => bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedResultMessage, "")),
                        }
                    };
                }
                bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::ReplanBudget, ""))
            })
            .await
    }
    /// Retire a configured Java worker without starting an analysis or publication Ack.
    pub async fn retire_unused(&mut self) -> Result<()> {
        self.send(Body::Cancel(w::Cancel {})).await?;
        let status = tokio::time::timeout(Duration::from_secs(2), self.child.wait()).await??;
        ensure!(
            status.success(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::WorkerExitAfterUnusedCancel,
                ""
            )
        );
        Ok(())
    }

    pub async fn ack(&mut self, revision: &str) -> Result<()> {
        self.send(Body::Ack(w::Ack {
            revision: revision.into(),
        }))
        .await?;
        if self.context.producer.starts_with("mock-") {
            let status = tokio::time::timeout(Duration::from_secs(2), self.child.wait()).await??;
            ensure!(
                status.success(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerExitAfterAck, "")
            );
        } else {
            receive_ack(&mut self.ipc, &self.attempt, revision, JAVA_ACK_TIMEOUT).await?;
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
        ensure!(
            self.alive()?,
            codeana_model::DomainError::new(codeana_model::ErrorCode::WarmWorkerExited, "")
        );
        *self.scratch.lock().unwrap() = Some(java.disk_budget.clone());
        self.output_limits =
            codeana_contracts::graph_archive::OutputLimits::from_config(&java.config)?;
        self.attempt = new_id("attempt");
        self.send(Body::Discover(w::Discover {
            rust: None,
            manifest: Some(map(manifest)?),
            java: Some(java.config.clone()),
        }))
        .await?;
        self.context = match self.receive(cancel).await? {
            Body::Context(context) => map(&context)?,
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::ExpectedBuildContext,
                ""
            )),
        };
        ensure!(
            self.context.language == "java",
            codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerLanguageMismatch, "")
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn memory_samples_keep_peak_and_last_complete_phase() {
        let mut usage = MemoryUsage {
            limit_bytes: 256,
            rss_bytes: 0,
            peak_rss_bytes: 0,
            stage: "starting".into(),
            process_group: 7,
            phase_ms: Default::default(),
            stream: None,
        };
        assert!(!usage.sample(100));
        assert!(!usage.sample(256));
        usage.observe_phase(b"codeana-rust-phase=database_load\ncodeana-rust-phase=decla");
        assert_eq!(usage.stage, "database_load");
        assert!(usage.sample(300));
        assert!(!usage.sample(200));
        assert_eq!((usage.rss_bytes, usage.peak_rss_bytes), (200, 300));
        usage.observe_phase(b"codeana-rust-phase=bindings\n");
        assert_eq!(usage.stage, "bindings");
        assert_eq!(serde_json::to_value(&usage).unwrap()["limit_bytes"], 256);
    }

    #[tokio::test]
    async fn delayed_java_ack_preserves_identity_and_exceeds_old_deadline() {
        let (mut receiver, mut sender) = UnixStream::pair().unwrap();
        let reply = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(2100)).await;
            write_frame(
                &mut sender,
                &envelope(
                    "attempt",
                    Body::Acked(w::Acked {
                        revision: "revision".into(),
                    }),
                ),
            )
            .await
            .unwrap();
        });
        receive_ack(&mut receiver, "attempt", "revision", JAVA_ACK_TIMEOUT)
            .await
            .unwrap();
        reply.await.unwrap();
    }

    #[tokio::test]
    async fn java_ack_rejects_wrong_attempt_revision_and_unexpected_message() {
        for (attempt, body, expected) in [
            (
                "wrong",
                Body::Acked(w::Acked {
                    revision: "revision".into(),
                }),
                "ACK_ATTEMPT_MISMATCH",
            ),
            (
                "attempt",
                Body::Acked(w::Acked {
                    revision: "wrong".into(),
                }),
                "ACK_REVISION_MISMATCH",
            ),
            ("attempt", Body::Cancel(w::Cancel {}), "EXPECTED_ACKED"),
        ] {
            let (mut receiver, mut sender) = UnixStream::pair().unwrap();
            write_frame(&mut sender, &envelope(attempt, body))
                .await
                .unwrap();
            let error = receive_ack(&mut receiver, "attempt", "revision", JAVA_ACK_TIMEOUT)
                .await
                .unwrap_err();
            assert!(error.to_string().contains(expected), "{error}");
        }
    }

    #[tokio::test]
    async fn stalled_java_ack_still_has_a_deadline() {
        let (mut receiver, _sender) = UnixStream::pair().unwrap();
        let error = receive_ack(
            &mut receiver,
            "attempt",
            "revision",
            Duration::from_millis(20),
        )
        .await
        .unwrap_err();
        assert!(error.to_string().contains("deadline has elapsed"));
    }
}
