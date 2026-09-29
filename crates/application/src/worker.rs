use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::{
    convert, envelope, fact_from_wire, fact_to_wire, read_frame, worker as w,
    worker::envelope::Body, write_frame,
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
    ipc: UnixStream,
    logs: std::sync::Arc<std::sync::Mutex<Vec<u8>>>,
    log_task: tokio::task::JoinHandle<()>,
    monitor: tokio::task::JoinHandle<()>,
    memory_exceeded: std::sync::Arc<std::sync::atomic::AtomicBool>,
    pub attempt: String,
    pub context: BuildContext,
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
        fixtures: &Path,
        checkout: &Path,
        manifest: &Manifest,
        cancel: &CancellationToken,
        memory_limit: u64,
    ) -> Result<Self> {
        let (a, b) = std::os::unix::net::UnixStream::pair()?;
        a.set_nonblocking(true)?;
        let fd = b.as_raw_fd();
        let binary = std::env::current_exe()?.with_file_name("codeana-mock-worker");
        let mut cmd = Command::new(binary);
        cmd.arg("--fd")
            .arg("3")
            .arg("--fixtures")
            .arg(fixtures)
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
        let mut child = cmd.spawn().context("MOCK_WORKER_NOT_INSTALLED")?;
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
        let monitor = tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_millis(250)).await;
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
            monitor,
            memory_exceeded,
            child,
            process_group: pid,
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
                    && h.producer == "mock-fixture-v1",
                "WORKER_VERSION_MISMATCH"
            ),
            _ => bail!("EXPECTED_HELLO"),
        };
        worker
            .send(Body::Discover(w::Discover {
                manifest: Some(convert(manifest)?),
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
            worker.context.producer.starts_with("mock-"),
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
                    if self.memory_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!("WORKER_MEMORY_BUDGET");}
                    let status=match status {Ok(Ok(s))=>s.to_string(),Ok(Err(e))=>e.to_string(),Err(_)=>"still running".into()};
                    bail!("WORKER_PROTOCOL: {error}; exit={status}; stderr={logs}");
                }
            },
            _=cancel.cancelled()=>{let _=self.send(Body::Cancel(w::Cancel{})).await;bail!("CANCELLED")}
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
        old: &[Fact],
        full: bool,
        cancel: &CancellationToken,
    ) -> Result<AnalysisResult> {
        let mut full = full;
        for retry in 0..2 {
            self.attempt = new_id("attempt");
            self.send(Body::Analyze(w::Analyze {
                manifest: Some(convert(manifest)?),
                profile: profile.into(),
                baseline: baseline.into(),
                full,
                baseline_facts: if full {
                    vec![]
                } else {
                    old.iter().map(fact_to_wire).collect::<Result<_>>()?
                },
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
            let mut facts = Vec::new();
            let mut seq = 0;
            let mut bytes = 0;
            loop {
                match self.receive(cancel).await? {
                    Body::Chunk(chunk) => {
                        ensure!(chunk.seq == seq, "CHUNK_SEQUENCE_MISMATCH");
                        seq += 1;
                        for f in chunk.facts {
                            let f = fact_from_wire(f)?;
                            bytes += serde_json::to_vec(&f)?.len();
                            ensure!(
                                bytes <= MAX_RESULT && facts.len() < MAX_FACTS,
                                "RESULT_BUDGET"
                            );
                            facts.push(f);
                        }
                    }
                    Body::End(end) => {
                        ensure!(
                            end.chunks == seq
                                && end.count == facts.len() as u64
                                && end.checksum == digest(&facts),
                            "RESULT_CHECKSUM_MISMATCH"
                        );
                        break;
                    }
                    _ => bail!("UNEXPECTED_RESULT_MESSAGE"),
                }
            }
            let r = AnalysisResult {
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
                facts,
            };
            r.validate(manifest, old).map_err(anyhow::Error::msg)?;
            return Ok(r);
        }
        bail!("REPLAN_BUDGET")
    }
    pub async fn ack(&mut self, revision: &str) -> Result<()> {
        self.send(Body::Ack(w::Ack {
            revision: revision.into(),
        }))
        .await?;
        let status = tokio::time::timeout(Duration::from_secs(2), self.child.wait()).await??;
        ensure!(status.success(), "WORKER_EXIT_AFTER_ACK");
        Ok(())
    }
}
