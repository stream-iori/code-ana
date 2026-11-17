use anyhow::{Context, Result, bail, ensure};
use codeana_graph_io::archive::{Builder as GraphArchiveBuilder, GraphArchive};
pub struct SpooledAnalysis {
    pub header: AnalysisHeader,
    pub records: GraphArchive,
}
pub enum Language<'a> {
    Java(Option<&'a super::java::PreparedJava>),
    Rust(&'a w::RustConfig),
    Python(&'a w::PythonConfig),
}
pub(super) struct LaunchSpec<'a> {
    pub fixtures: Option<&'a Path>,
    pub language: Language<'a>,
    pub checkout: &'a Path,
    pub manifest: &'a Manifest,
    pub inputs: crate::supervisor::InputLease,
    pub publication: Publication<'a>,
}
pub(super) struct Publication<'a> {
    pub previous: &'a [SourceUnit],
    pub modules: Vec<String>,
}
impl Publication<'_> {
    fn admit(&self, budget: Option<&AnalysisContext>) -> Result<()> {
        if let Some(budget) = budget {
            budget
                .charge(
                    1,
                    0,
                    2 * retained_size(&self.previous)?
                        + self
                            .modules
                            .iter()
                            .map(|m| m.len() as u64 + 128)
                            .sum::<u64>(),
                )
                .map_err(DomainError::analysis)?;
        }
        Ok(())
    }
}
#[derive(Clone)]
struct ScratchLease {
    budget: super::resources::DiskBudget,
    _inputs: std::sync::Arc<crate::supervisor::InputLease>,
}
use codeana_contracts::{envelope, map, worker as w, worker::envelope::Body};
use codeana_model::*;
use codeana_worker_runtime::write_frame_with_budget;
use std::{os::fd::AsRawFd, path::Path, time::Duration};
use tokio::{net::UnixStream, process::Command};
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
    stream: Option<codeana_graph_io::stream::StreamSummary>,
}
impl MemoryUsage {
    fn sample(&mut self, rss: u64) -> bool {
        self.rss_bytes = rss;
        self.peak_rss_bytes = self.peak_rss_bytes.max(rss);
        rss > self.limit_bytes
    }
    fn observe_phase(&mut self, log: &[u8]) {
        for line in String::from_utf8_lossy(log).lines() {
            if let Some(timing) = line
                .strip_prefix("codeana-rust-timing ")
                .or_else(|| line.strip_prefix("codeana-python-timing "))
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
            if let Some(phase) = line
                .strip_prefix("codeana-rust-phase=")
                .or_else(|| line.strip_prefix("codeana-python-phase="))
                && matches!(
                    phase,
                    "backend_run"
                        | "backend_facts"
                        | "analyze"
                        | "inputs"
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
    let mut reader = codeana_worker_runtime::FrameReader::default();
    let frame = tokio::time::timeout(timeout, reader.next_control(ipc)).await??;
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
pub struct AnalysisBinding {
    pub coordinator: std::sync::Arc<std::sync::Mutex<AnalysisCoordinator>>,
    pub language: AnalysisBackend,
    pub instance: String,
}

pub struct Worker {
    previous_sources: Vec<SourceUnit>,
    publication_modules: Vec<String>,
    fixture: bool,
    analysis: Option<(
        AnalysisGrant,
        std::sync::Arc<std::sync::Mutex<AnalysisCoordinator>>,
    )>,
    child: crate::supervisor::Process,
    stopped: bool,
    process_group: u32,
    command: String,
    ipc: UnixStream,
    frame_reader: codeana_worker_runtime::FrameReader,
    logs: std::sync::Arc<std::sync::Mutex<Vec<u8>>>,
    log_task: tokio::task::JoinHandle<()>,
    monitor: Option<tokio::task::JoinHandle<()>>,
    group_signals: std::sync::Arc<std::sync::Mutex<bool>>,
    memory_exceeded: std::sync::Arc<std::sync::atomic::AtomicBool>,
    memory: std::sync::Arc<std::sync::Mutex<MemoryUsage>>,
    pub attempt: String,
    pub context: BuildContext,
    output_limits: codeana_contracts::graph_delivery::OutputLimits,
    scratch: std::sync::Arc<std::sync::Mutex<Option<ScratchLease>>>,
    scratch_exceeded: std::sync::Arc<std::sync::atomic::AtomicBool>,
}
#[cfg(test)]
async fn terminate_group(
    child: &mut tokio::process::Child,
    process_group: u32,
) -> std::io::Result<std::process::ExitStatus> {
    // SAFETY: this group is the group assigned to the supervised child at launch.
    let result = unsafe { libc::kill(-(process_group as i32), libc::SIGKILL) };
    if result != 0 {
        let error = std::io::Error::last_os_error();
        // Darwin may report EPERM when the group consists only of its zombie leader.
        // Ignore that case only after a non-reaping observation of our owned child's exit;
        // permission errors for a live leader remain cleanup failures.
        let exited = error.raw_os_error() == Some(libc::EPERM)
            && child
                .id()
                .is_some_and(|pid| exited_without_reaping(pid).unwrap_or(false));
        if error.raw_os_error() != Some(libc::ESRCH) && !exited {
            return Err(error);
        }
    }
    child.wait().await
}

/// Observe an owned child's exit without releasing the PID before descendant cleanup.
pub(super) fn exited_without_reaping(pid: u32) -> std::io::Result<bool> {
    // SAFETY: waitid initializes siginfo; WNOHANG makes this a nonblocking observation,
    // WNOWAIT leaves the owned child waitable by Tokio after its group is terminated.
    let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
    loop {
        let result = unsafe {
            libc::waitid(
                libc::P_PID,
                pid as libc::id_t,
                &mut info,
                libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
            )
        };
        if result == 0 {
            return Ok(unsafe { info.si_pid() } != 0);
        }
        let error = std::io::Error::last_os_error();
        if error.kind() != std::io::ErrorKind::Interrupted {
            return Err(error);
        }
    }
}

fn signal_monitored_group(pid: u32, enabled: &std::sync::Mutex<bool>) {
    // Serialize the final signal with cleanup's decision to retire this numeric group ID.
    // This also covers synchronous Drop, where joining an async monitor is unavailable.
    let enabled = enabled.lock().unwrap();
    if *enabled {
        unsafe {
            libc::kill(-(pid as i32), libc::SIGKILL);
        }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        *self.group_signals.lock().unwrap() = false;
        if let Some((grant, coordinator)) = &self.analysis {
            let _ = coordinator.lock().unwrap().lost_grant(grant);
        }
        self.log_task.abort();
        if let Some(monitor) = &self.monitor {
            monitor.abort();
        }
    }
}
impl Worker {
    fn observed_exit(&mut self) {
        // Once reaped, the old PID/PGID can be reused. Never signal or monitor it again.
        self.stopped = true;
        self.scratch.lock().unwrap().take();
        self.child.release_inputs();
        *self.group_signals.lock().unwrap() = false;
        self.log_task.abort();
        if let Some(monitor) = &self.monitor {
            monitor.abort();
        }
    }

    async fn stop_monitor(&mut self) {
        *self.group_signals.lock().unwrap() = false;
        if let Some(monitor) = self.monitor.take() {
            monitor.abort();
            // Join the monitor before reaping: an already running sample can still signal
            // the group until its async task has actually finished on another runtime thread.
            let _ = monitor.await;
        }
    }

    /// Hold task input guards until the owned process has actually been reaped.
    pub(super) async fn shutdown(&mut self) -> Result<()> {
        self.stop_monitor().await;
        if self.stopped {
            return Ok(());
        }
        self.log_task.abort();
        self.child.shutdown().await?;
        self.observed_exit();
        Ok(())
    }

    fn attach_analysis(&mut self, binding: AnalysisBinding) -> Result<()> {
        ensure!(self.analysis.is_none(), "ANALYSIS_GRANT_ALREADY_ATTACHED");
        let grant = binding
            .coordinator
            .lock()
            .unwrap()
            .issue_instance(binding.language, &binding.instance)?;
        self.analysis = Some((grant, binding.coordinator));
        Ok(())
    }
    fn analysis_deadline_ms(&self) -> Result<u64> {
        self.analysis
            .as_ref()
            .map(|(grant, coordinator)| {
                coordinator
                    .lock()
                    .unwrap()
                    .host(grant.language)?
                    .remaining_millis()
                    .map(|n| n.unwrap_or(0))
                    .map_err(DomainError::analysis)
                    .map_err(Into::into)
            })
            .unwrap_or(Ok(0))
    }
    fn accept_analysis_receipt(
        &self,
        receipt: Option<&w::AnalysisBudgetReceipt>,
        terminal: bool,
        failed: bool,
    ) -> Result<()> {
        if let Some((grant, coordinator)) = &self.analysis {
            codeana_worker_runtime::analysis::reconcile_receipt(
                &mut coordinator.lock().unwrap(),
                grant,
                receipt,
                terminal,
                failed,
            )?;
        }
        Ok(())
    }

    pub(super) async fn start_language(
        spec: LaunchSpec<'_>,
        cancel: &CancellationToken,
        memory_limit: u64,
        analysis: Option<AnalysisBinding>,
    ) -> Result<Self> {
        let LaunchSpec {
            fixtures,
            language,
            checkout,
            manifest,
            inputs,
            publication,
        } = spec;
        crate::supervisor::admit_launch()?;
        let host = analysis
            .as_ref()
            .map(|b| b.coordinator.lock().unwrap().host(b.language))
            .transpose()
            .map_err(DomainError::analysis)?;
        publication.admit(host.as_ref())?;
        ensure!(
            fixtures.is_some() || analysis.is_some(),
            "ANALYSIS_GRANT_REQUIRED"
        );
        let (java, rust, python) = match language {
            Language::Java(java) => (java, None, None),
            Language::Rust(rust) => (None, Some(rust), None),
            Language::Python(python) => (None, None, Some(python)),
        };
        let backend = if rust.is_some() {
            AnalysisBackend::Rust
        } else if python.is_some() {
            AnalysisBackend::Python
        } else {
            AnalysisBackend::Java
        };
        let expected_producer = if fixtures.is_some() {
            "mock-fixture-v1"
        } else {
            match backend {
                AnalysisBackend::Java => "javac-v3",
                AnalysisBackend::Rust => codeana_model::rust::PRODUCER,
                AnalysisBackend::Python => codeana_model::python::PRODUCER,
                AnalysisBackend::TypeScript => unreachable!(),
            }
        };
        let unit = analysis
            .as_ref()
            .filter(|b| !b.instance.is_empty())
            .map(|b| b.instance.clone())
            .unwrap_or_else(|| backend.as_str().into());
        let source_languages =
            vec![SourceLanguage::lookup(backend.as_str()).expect("registered source")];
        let (a, b) = std::os::unix::net::UnixStream::pair()?;
        a.set_nonblocking(true)?;
        let fd = b.as_raw_fd();
        let binary = std::env::current_exe()?.with_file_name(if fixtures.is_some() {
            "codeana-mock-worker"
        } else if rust.is_some() {
            "codeana-rust-worker"
        } else if python.is_some() {
            "codeana-python-worker"
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
        let output_limits = java
            .map(|j| codeana_contracts::java::output_limits(&j.config))
            .transpose()?
            .unwrap_or_default();
        let ipc = UnixStream::from_std(a)?;
        let mut child = cmd.spawn().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::WorkerNotInstalled,
            "",
        ))?;
        let pid = child.id().context("WORKER_PID_REQUIRED")?;
        let inputs = std::sync::Arc::new(inputs);
        let stderr = child.stderr.take();
        let child = crate::supervisor::Process::new(child, pid, inputs.clone());
        let memory = std::sync::Arc::new(std::sync::Mutex::new(MemoryUsage {
            limit_bytes: memory_limit,
            rss_bytes: 0,
            peak_rss_bytes: 0,
            stage: "starting".into(),
            process_group: pid,
            phase_ms: Default::default(),
            stream: None,
        }));
        let mut stderr = stderr.context(codeana_model::DomainError::new(
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
        let memory_exceeded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let exceeded = memory_exceeded.clone();
        let scratch = std::sync::Arc::new(std::sync::Mutex::new(java.map(|j| ScratchLease {
            budget: j.disk_budget.clone(),
            _inputs: inputs.clone(),
        })));
        let monitored_scratch = scratch.clone();
        let scratch_exceeded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let disk_exceeded = scratch_exceeded.clone();
        let sampled_memory = memory.clone();
        let group_signals = std::sync::Arc::new(std::sync::Mutex::new(true));
        let monitor_signals = group_signals.clone();
        let monitor = tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_millis(100));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                interval.tick().await;
                let budget = monitored_scratch.lock().unwrap().clone();
                if let Some(budget) = budget {
                    let checked_root = budget.budget.root.clone();
                    let check = tokio::task::spawn_blocking(move || {
                        let result = budget.budget.check();
                        drop(budget);
                        result
                    })
                    .await;
                    if !matches!(check, Ok(Ok(()))) {
                        let active = monitored_scratch.lock().unwrap();
                        if active
                            .as_ref()
                            .is_some_and(|b| b.budget.root == checked_root)
                        {
                            disk_exceeded.store(true, std::sync::atomic::Ordering::SeqCst);
                            signal_monitored_group(pid, &monitor_signals);
                            break;
                        }
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
                        signal_monitored_group(pid, &monitor_signals);
                        break;
                    }
                }
            }
        });
        drop(b);
        let mut worker = Self {
            previous_sources: publication.previous.to_vec(),
            publication_modules: publication.modules,
            fixture: fixtures.is_some(),
            analysis: None,
            output_limits,
            scratch,
            scratch_exceeded,
            monitor: Some(monitor),
            group_signals,
            memory_exceeded,
            memory,
            child,
            stopped: false,
            process_group: pid,
            command,
            logs,
            log_task,
            ipc,
            frame_reader: codeana_worker_runtime::FrameReader::default(),
            attempt: new_id("attempt"),
            context: BuildContext::new(
                &unit,
                BackendContext::Java {
                    source_version: String::new(),
                    classpath: vec![],
                    toolchain: Default::default(),
                },
            ),
        };
        let initialized: Result<()> = async {
            if let Some(config) = rust {
                ensure!(
                    (65536..=1073741824).contains(&config.result_bytes)
                        && (1..=2000000).contains(&config.fact_limit),
                    "RUST_OUTPUT_LIMIT_RANGE"
                );
                worker.output_limits = codeana_contracts::graph_delivery::OutputLimits {
                    bytes: config.result_bytes,
                    facts: config.fact_limit,
                };
            }
            if let Some(config) = python {
                ensure!(
                    (65536..=1073741824).contains(&config.result_bytes)
                        && (1..=2000000).contains(&config.fact_limit),
                    "PYTHON_OUTPUT_LIMIT_RANGE"
                );
                worker.output_limits = codeana_contracts::graph_delivery::OutputLimits {
                    bytes: config.result_bytes,
                    facts: config.fact_limit,
                };
            }
            if let Some(binding) = analysis {
                worker.attach_analysis(binding)?;
            }
            let hello_deadline = worker.analysis_deadline_ms()?;
            worker
                .send(Body::Hello(w::Hello {
                    protocol: WORKER_PROTOCOL_VERSION,
                    fact_ir: VERSION,
                    producer: "daemon".into(),
                    analysis_grant: worker
                        .analysis
                        .as_ref()
                        .map(|(g, _)| codeana_contracts::analysis_budget::encode_grant(g)),
                    deadline_ms: Some(hello_deadline),
                    analysis_usage: None,
                }))
                .await?;
            match worker.receive(cancel).await? {
                Body::Hello(h) => {
                    worker.accept_analysis_receipt(h.analysis_usage.as_ref(), false, false)?;
                    ensure!(
                        h.protocol == WORKER_PROTOCOL_VERSION
                            && h.fact_ir == VERSION
                            && h.producer
                                == if fixtures.is_some() {
                                    "mock-fixture-v1"
                                } else if rust.is_some() {
                                    "rust-core"
                                } else if python.is_some() {
                                    "python-core"
                                } else {
                                    "javac-v3"
                                },
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::WorkerVersionMismatch,
                            ""
                        )
                    );
                }
                _ => bail!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::ExpectedHello,
                    ""
                )),
            };
            let deadline_ms = worker.analysis_deadline_ms()?;
            worker
                .send(Body::Discover(w::Discover {
                    unit: unit.clone(),
                    source_languages: source_languages
                        .iter()
                        .map(|l| l.as_str().to_owned())
                        .collect(),
                    deadline_ms: Some(deadline_ms),
                    analysis_grant: worker.analysis.as_ref().map(|(grant, _)| {
                        Box::new(codeana_contracts::analysis_budget::encode_grant(grant))
                    }),

                    config: match (java, rust, python) {
                        (Some(j), None, None) => {
                            Some(w::discover::Config::Java(Box::new(j.config.clone())))
                        }
                        (None, Some(r), None) => {
                            Some(w::discover::Config::Rust(Box::new(r.clone())))
                        }
                        (None, None, Some(p)) => {
                            Some(w::discover::Config::Python(Box::new(p.clone())))
                        }
                        (None, None, None) if fixtures.is_some() => {
                            Some(w::discover::Config::Java(Box::default()))
                        }
                        _ => bail!("DISCOVER_CONFIG_REQUIRED"),
                    },
                    manifest: Some(map(manifest)?),
                }))
                .await?;
            worker.context = match worker.receive(cancel).await? {
                Body::Context(c) => {
                    worker.accept_analysis_receipt(c.analysis_usage.as_ref(), false, false)?;
                    map(&c)?
                }
                Body::Advice(a) => {
                    let mut advice: BuildAdvice = map(&a)?;
                    ensure!(
                        !advice.missing.is_empty(),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::EmptyBuildAdvice,
                            ""
                        )
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
            codeana_contracts::build_context::accept(
                &worker.context,
                &unit,
                backend,
                &source_languages,
                expected_producer,
                manifest,
            )?;
            ensure!(
                fixtures.is_none() || worker.context.producer.starts_with("mock-"),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::MockProvenanceRequired,
                    ""
                )
            );
            ensure!(
                worker.analysis.is_none()
                    || worker
                        .context
                        .capabilities
                        .iter()
                        .any(|c| c == "analysis-resources-v1"),
                "ANALYSIS_CAPABILITY_REQUIRED"
            );
            if python.is_some() {
                validate_python_context(&worker.context)?;
            }
            Ok(())
        }
        .await;
        if let Err(error) = initialized {
            if let Err(cleanup) = worker.shutdown().await {
                eprintln!("worker_start_cleanup_warning: {cleanup:#}");
            }
            return Err(error);
        }
        Ok(worker)
    }
    async fn send(&mut self, body: Body) -> Result<()> {
        let budget = self
            .analysis
            .as_ref()
            .map(|(g, c)| c.lock().unwrap().host(g.language))
            .transpose()?;
        tokio::time::timeout(
            Duration::from_secs(5),
            write_frame_with_budget(
                &mut self.ipc,
                &envelope(&self.attempt, body),
                budget.as_ref(),
            ),
        )
        .await?
        .map_err(|error| {
            if let Some(failure) = error.downcast_ref::<AnalysisFailure>() {
                anyhow::Error::from(DomainError::analysis(failure.clone()))
            } else {
                error
            }
        })?;
        Ok(())
    }
    async fn receive(&mut self, cancel: &CancellationToken) -> Result<Body> {
        let budget = self
            .analysis
            .as_ref()
            .map(|(g, c)| c.lock().unwrap().host(g.language))
            .transpose()?;
        let frame = tokio::select! {
            result=async {if let Some(budget)=budget.as_ref(){self.frame_reader.next_with_budget(&mut self.ipc,budget).await}else{self.frame_reader.next(&mut self.ipc).await}}=>match result {
                Ok(frame)=>frame,
                Err(error)=>{
                    if let Some(failure)=error.downcast_ref::<AnalysisFailure>() {return Err(DomainError::analysis(failure.clone()).into());}
                    // Give the crashed process the existing diagnostic grace period, without
                    // reaping its leader before the descendant group can be terminated.
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    tokio::task::yield_now().await;
                    let logs=String::from_utf8_lossy(&self.logs.lock().unwrap()).into_owned();
                    self.stop_monitor().await;
                    let status=tokio::time::timeout(Duration::from_millis(100),self.child.shutdown()).await;
                    if matches!(&status,Ok(Ok(_))) { self.observed_exit(); }
                    if self.scratch_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaScratchBudget, ""));}
                    if self.memory_exceeded.load(std::sync::atomic::Ordering::SeqCst){bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerMemoryBudget, serde_json::to_string(&self.memory_usage())?));}
                    let status=match status {Ok(Ok(s))=>s.to_string(),Ok(Err(e))=>e.to_string(),Err(_)=>"still running".into()};
                    bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerProtocol, format!("{error}; pid={}; command={}; exit={status}; stderr={logs}", self.process_group,self.command)));
                }
            },
            _=cancel.cancelled()=>{
                let deadline=tokio::time::Instant::now()+Duration::from_secs(2);
                let _=tokio::time::timeout_at(deadline,self.send(Body::Cancel(w::Cancel{}))).await;
                while let Ok(Ok(frame))=tokio::time::timeout_at(deadline,self.frame_reader.next_terminal(&mut self.ipc)).await {
                    if frame.body.is_none() {continue;}
                    if frame.attempt!=self.attempt {break;}
                    match frame.body {
                        Some(Body::Failure(failure))=> {let _=self.accept_analysis_receipt(failure.analysis_usage.as_ref(),true,true);break;},
                        Some(Body::End(end))=> {let _=self.accept_analysis_receipt(end.analysis_usage.as_ref(),true,false);break;},
                        Some(Body::Replan(replan))=> {let _=self.accept_analysis_receipt(replan.analysis_usage.as_ref(),false,false);},
                        Some(Body::Begin(_) | Body::Chunk(_))=>{},
                        _=>break,
                    }
                }
                let _=tokio::time::timeout_at(deadline,self.shutdown()).await;
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
            Body::Failure(f) => {
                self.accept_analysis_receipt(f.analysis_usage.as_ref(), true, true)?;
                bail!(DomainError::adapter(f.code, f.message))
            }
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
            .map(|b| b.budget.root.clone());
        let limits = self.output_limits;
        let mut archive_task = None;
        let header = self
            .analyze_into(manifest, profile, baseline, old, full, cancel, |_| {
                let (sender, records) = codeana_graph_io::stream::GraphStream::channel();
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
        mut begin_sink: impl FnMut(&AnalysisHeader) -> Result<codeana_graph_io::stream::SinkSender>,
    ) -> Result<AnalysisHeader> {
        let diagnostics = codeana_contracts::performance::Report::new("transport");
        diagnostics
            .capture
            .scope(async {
                let mut full = full;
                for retry in 0..2 {
                    self.attempt = new_id("attempt");
                    let baseline_summary_json=if full { Vec::new() } else { old.map(serde_json::to_vec).transpose()?.unwrap_or_default() };
                    let deadline_ms=if let Some((grant,coordinator))=&self.analysis { coordinator.lock().unwrap().host(grant.language)?.remaining_millis().map_err(DomainError::analysis)?.unwrap_or(0) } else { 0 };
                    self.send(Body::Analyze(w::Analyze {
analysis_grant: self.analysis.as_ref().map(|(grant,_)| codeana_contracts::analysis_budget::encode_grant(grant)), deadline_ms:Some(deadline_ms),

                        baseline_summary_json,
                        manifest: Some(map(manifest)?),
                        profile: profile.into(),
                        baseline: baseline.into(),
                        full,
                    }))
                    .await?;
                    let begin = match self.receive(cancel).await? {
                        Body::Begin(b) => b,
                        Body::Replan(replan) if retry == 0 && !full => {
                            self.accept_analysis_receipt(replan.analysis_usage.as_ref(),false,false)?;
                            full = true;
                            continue;
                        }
                        _ => bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::ExpectedResultBegin, "")),
                    };
                    let host_budget=self.analysis.as_ref().map(|(grant,coordinator)|coordinator.lock().unwrap().host(grant.language)).transpose().map_err(DomainError::analysis)?;
                    let scope=codeana_graph_io::scope::Scope::new(&self.context.unit,manifest,&self.previous_sources,&self.publication_modules,&self.context.source_languages,if self.fixture {codeana_graph_io::scope::Mode::Fixture} else {codeana_graph_io::scope::Mode::Compiler},host_budget.as_ref())?;
                    let mut delivery=codeana_worker_runtime::delivery::Receiver::new(codeana_worker_runtime::delivery::RequestBinding {manifest,profile,baseline,producer:self.context.producer.clone(),full,scope},self.output_limits,host_budget);
                    let r=delivery.begin(begin)?;
                    let sink=begin_sink(&r)?;
                    loop {
                        match self.receive(cancel).await? {
                            Body::Chunk(chunk) => {
                                let batch=delivery.chunk(chunk)?;
                                if !batch.is_empty() {
                                    tokio::select! {
                                        _ = cancel.cancelled() => bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")),
                                        sent = sink.send(codeana_graph_io::stream::GraphStreamEvent::Batch(batch)) => sent.map_err(|_| anyhow::anyhow!(codeana_model::DomainError::new(codeana_model::ErrorCode::CandidateStreamClosed, "")))?,
                                    }
                                }
                            }
                            Body::End(end) => {
                                let verified=delivery.end(&end)?;
                                self.accept_analysis_receipt(end.analysis_usage.as_ref(),true,false)?;
                                tokio::select! {
                                    biased;
                                    _=cancel.cancelled()=>bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled,"")),
                                    sent=sink.send(codeana_graph_io::stream::GraphStreamEvent::VerifiedEnd(verified))=>sent.map_err(|_| anyhow::anyhow!(codeana_model::DomainError::new(codeana_model::ErrorCode::CandidateStreamClosed,"")))?,
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
    /// Seal the unused analysis account; the certified worker is then idle.
    async fn seal_unused(&mut self) -> Result<()> {
        if self.analysis.is_some() {
            self.send(Body::Reuse(w::Reuse {})).await?;
            let frame = tokio::time::timeout(
                Duration::from_secs(2),
                self.frame_reader.next_terminal(&mut self.ipc),
            )
            .await??;
            ensure!(frame.attempt == self.attempt, "ANALYSIS_REUSE_ATTEMPT");
            match frame.body {
                Some(Body::Reused(reused)) => {
                    self.accept_analysis_receipt(reused.analysis_usage.as_ref(), true, false)?
                }
                Some(Body::Failure(failure)) => {
                    self.accept_analysis_receipt(failure.analysis_usage.as_ref(), true, true)?;
                    bail!(DomainError::adapter(failure.code, failure.message));
                }
                _ => bail!("ANALYSIS_REUSE_RECEIPT_REQUIRED"),
            }
            self.analysis = None;
        }
        Ok(())
    }

    /// Java can return to its healthy process pool after an explicit Reused receipt.
    pub async fn reuse_java(&mut self) -> Result<()> {
        ensure!(
            self.context.backend() == AnalysisBackend::Java && self.analysis.is_some(),
            "JAVA_REUSE_ACCOUNT_REQUIRED"
        );
        self.seal_unused().await?;
        self.scratch.lock().unwrap().take();
        self.child.release_inputs();
        Ok(())
    }

    /// Other backends retire their process after sealing the unused account.
    pub async fn retire_unused(&mut self) -> Result<()> {
        self.seal_unused().await?;
        self.send(Body::Cancel(w::Cancel {})).await?;
        self.stop_monitor().await;
        let status =
            tokio::time::timeout(Duration::from_secs(2), self.child.wait_closed()).await??;
        self.observed_exit();
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
            self.stop_monitor().await;
            let status =
                tokio::time::timeout(Duration::from_secs(2), self.child.wait_closed()).await??;
            self.observed_exit();
            ensure!(
                status.success(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerExitAfterAck, "")
            );
        } else {
            receive_ack(&mut self.ipc, &self.attempt, revision, JAVA_ACK_TIMEOUT).await?;
            self.analysis = None;
            self.scratch.lock().unwrap().take();
            self.child.release_inputs();
        }
        Ok(())
    }

    /// An idle receipt must have ended the analysis account and released task inputs.
    pub(super) fn reusable(&self) -> bool {
        self.analysis.is_none() && self.scratch.lock().unwrap().is_none() && !self.stopped
    }

    pub fn alive(&mut self) -> Result<bool> {
        if self.stopped {
            return Ok(false);
        }
        let Some(pid) = self.child.id() else {
            self.observed_exit();
            return Ok(false);
        };
        Ok(!exited_without_reaping(pid)?
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
        analysis: Option<AnalysisBinding>,
        inputs: crate::supervisor::InputLease,
        publication: Publication<'_>,
    ) -> Result<()> {
        ensure!(
            self.alive()?,
            codeana_model::DomainError::new(codeana_model::ErrorCode::WarmWorkerExited, "")
        );
        ensure!(analysis.is_some(), "ANALYSIS_GRANT_REQUIRED");
        let unit = analysis
            .as_ref()
            .filter(|b| !b.instance.is_empty())
            .map(|b| b.instance.clone())
            .unwrap_or_else(|| "java".into());
        let source_languages = vec![SourceLanguage::Java];
        let inputs = std::sync::Arc::new(inputs);
        self.child.replace_inputs(inputs.clone())?;
        *self.scratch.lock().unwrap() = Some(ScratchLease {
            budget: java.disk_budget.clone(),
            _inputs: inputs,
        });
        self.output_limits = codeana_contracts::java::output_limits(&java.config)?;
        self.attempt = new_id("attempt");
        if let Some(binding) = analysis {
            self.attach_analysis(binding)?;
        }
        let host = self
            .analysis
            .as_ref()
            .map(|(g, c)| c.lock().unwrap().host(g.language))
            .transpose()
            .map_err(DomainError::analysis)?;
        publication.admit(host.as_ref())?;
        self.previous_sources = publication.previous.to_vec();
        self.publication_modules = publication.modules;
        let deadline_ms = self.analysis_deadline_ms()?;
        self.send(Body::Hello(w::Hello {
            protocol: WORKER_PROTOCOL_VERSION,
            fact_ir: VERSION,
            producer: "daemon".into(),
            analysis_grant: self
                .analysis
                .as_ref()
                .map(|(g, _)| codeana_contracts::analysis_budget::encode_grant(g)),
            deadline_ms: Some(deadline_ms),
            analysis_usage: None,
        }))
        .await?;
        match self.receive(cancel).await? {
            Body::Hello(h) => {
                ensure!(
                    h.protocol == WORKER_PROTOCOL_VERSION
                        && h.fact_ir == VERSION
                        && h.producer == "javac-v3",
                    "WORKER_VERSION_MISMATCH"
                );
                self.accept_analysis_receipt(h.analysis_usage.as_ref(), false, false)?;
            }
            _ => bail!("EXPECTED_HELLO"),
        }
        self.send(Body::Discover(w::Discover {
            unit: unit.clone(),
            source_languages: vec!["java".into()],
            deadline_ms: Some(deadline_ms),
            analysis_grant: self.analysis.as_ref().map(|(grant, _)| {
                Box::new(codeana_contracts::analysis_budget::encode_grant(grant))
            }),

            manifest: Some(map(manifest)?),
            config: Some(w::discover::Config::Java(Box::new(java.config.clone()))),
        }))
        .await?;
        self.context = match self.receive(cancel).await? {
            Body::Context(context) => {
                self.accept_analysis_receipt(context.analysis_usage.as_ref(), false, false)?;
                map(&context)?
            }
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::ExpectedBuildContext,
                ""
            )),
        };
        codeana_contracts::build_context::accept(
            &self.context,
            &unit,
            AnalysisBackend::Java,
            &source_languages,
            "javac-v3",
            manifest,
        )?;
        ensure!(
            self.context.backend() == AnalysisBackend::Java,
            codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerLanguageMismatch, "")
        );
        Ok(())
    }
}

fn validate_python_context(context: &BuildContext) -> Result<()> {
    ensure!(
        context.producer == codeana_model::python::PRODUCER
            && matches!(&context.details,
        BackendContext::Python {python_version,platform,adapter,backend_commit}
        if python_version == "3.12" && platform == "darwin" && adapter == codeana_model::python::ADAPTER_VERSION && backend_commit == codeana_model::python::BACKEND_COMMIT),
        "PYTHON_WORKER_VERSION_MISMATCH"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_worker_runtime::write_frame;
    #[tokio::test]
    async fn only_a_valid_ack_releases_live_worker_inputs_and_scratch_watch() -> Result<()> {
        for valid in [false, true] {
            let directory = std::sync::Arc::new(tempfile::tempdir()?);
            let path = directory.path().to_owned();
            let inputs =
                std::sync::Arc::new(crate::supervisor::InputLease::directory(directory.clone()));
            let child = Command::new("/bin/sleep")
                .arg("60")
                .process_group(0)
                .kill_on_drop(true)
                .spawn()?;
            let group = child.id().unwrap();
            let (mut worker, mut peer) = test_worker(child, group, inputs.clone());
            *worker.scratch.lock().unwrap() = Some(ScratchLease {
                budget: crate::resources::DiskBudget::new(path.clone(), 1 << 20, 0, 0, "test"),
                _inputs: inputs.clone(),
            });
            drop(inputs);
            drop(directory);
            let server = tokio::spawn(async move {
                let mut reader = codeana_worker_runtime::FrameReader::default();
                let frame = reader.next_control(&mut peer).await?;
                ensure!(matches!(frame.body, Some(Body::Ack(_))), "expected ack");
                write_frame(
                    &mut peer,
                    &envelope(
                        "test",
                        Body::Acked(w::Acked {
                            revision: if valid { "revision" } else { "wrong" }.into(),
                        }),
                    ),
                )
                .await
            });
            let result = worker.ack("revision").await;
            server.await??;
            assert_eq!(result.is_ok(), valid);
            assert_eq!(path.exists(), !valid);
            assert_eq!(worker.scratch.lock().unwrap().is_none(), valid);
            assert_eq!(worker.reusable(), valid);
            assert!(worker.alive()?);
            worker.shutdown().await?;
            assert!(!path.exists());
        }
        Ok(())
    }
    #[tokio::test]
    async fn only_a_verified_reuse_releases_inputs_and_keeps_java_alive() -> Result<()> {
        for fault in ["none", "attempt", "grant", "unknown"] {
            let directory = std::sync::Arc::new(tempfile::tempdir()?);
            let path = directory.path().to_owned();
            let inputs =
                std::sync::Arc::new(crate::supervisor::InputLease::directory(directory.clone()));
            let child = Command::new("/bin/sleep")
                .arg("60")
                .process_group(0)
                .kill_on_drop(true)
                .spawn()?;
            let group = child.id().unwrap();
            let (mut worker, mut peer) = test_worker(child, group, inputs.clone());
            *worker.scratch.lock().unwrap() = Some(ScratchLease {
                budget: crate::resources::DiskBudget::new(path.clone(), 1 << 20, 0, 0, "test"),
                _inputs: inputs.clone(),
            });
            drop(inputs);
            drop(directory);
            let mut coordinator = AnalysisCoordinator::new(
                "task",
                AnalysisLimits::default(),
                AnalysisUsage::default(),
                &[AnalysisBackend::Java],
                None,
            )?;
            coordinator.configure_instances(AnalysisBackend::Java, &["java".into()])?;
            worker.attach_analysis(AnalysisBinding {
                coordinator: std::sync::Arc::new(std::sync::Mutex::new(coordinator)),
                language: AnalysisBackend::Java,
                instance: "java".into(),
            })?;
            let grant = worker.analysis.as_ref().unwrap().0.clone();
            let server = tokio::spawn(async move {
                let mut reader = codeana_worker_runtime::FrameReader::default();
                let frame = reader.next_control(&mut peer).await?;
                ensure!(matches!(frame.body, Some(Body::Reuse(_))), "expected reuse");
                let mut receipt = codeana_contracts::analysis_budget::receipt(
                    &grant,
                    &AnalysisUsage::default(),
                    "reused",
                );
                if fault == "grant" {
                    receipt.grant_id = "wrong".into();
                }
                if fault == "unknown" {
                    receipt.usage_known = Some(false);
                }
                write_frame(
                    &mut peer,
                    &envelope(
                        if fault == "attempt" { "wrong" } else { "test" },
                        Body::Reused(w::Reused {
                            analysis_usage: Some(receipt),
                        }),
                    ),
                )
                .await
            });
            let result = worker.reuse_java().await;
            server.await??;
            let valid = fault == "none";
            assert_eq!(result.is_ok(), valid, "{fault}: {result:?}");
            assert_eq!(path.exists(), !valid, "{fault}");
            assert_eq!(worker.scratch.lock().unwrap().is_none(), valid);
            assert_eq!(worker.analysis.is_none(), valid);
            assert_eq!(worker.reusable(), valid);
            assert!(worker.alive()?);
            worker.shutdown().await?;
            assert!(!path.exists());
        }
        Ok(())
    }

    fn test_worker(
        child: tokio::process::Child,
        group: u32,
        inputs: std::sync::Arc<crate::supervisor::InputLease>,
    ) -> (Worker, UnixStream) {
        let (ipc, peer) = UnixStream::pair().unwrap();
        let worker = Worker {
            previous_sources: vec![],
            publication_modules: vec![],
            fixture: true,
            analysis: None,
            child: crate::supervisor::Process::new(child, group, inputs),
            stopped: false,
            process_group: group,
            command: "test".into(),
            ipc,
            frame_reader: Default::default(),
            logs: Default::default(),
            log_task: tokio::spawn(std::future::pending::<()>()),
            monitor: Some(tokio::spawn(std::future::pending::<()>())),
            group_signals: std::sync::Arc::new(std::sync::Mutex::new(true)),
            memory_exceeded: Default::default(),
            scratch_exceeded: Default::default(),
            memory: std::sync::Arc::new(std::sync::Mutex::new(MemoryUsage {
                limit_bytes: 1,
                rss_bytes: 0,
                peak_rss_bytes: 0,
                stage: "test".into(),
                process_group: group,
                phase_ms: Default::default(),
                stream: None,
            })),
            attempt: "test".into(),
            context: BuildContext {
                unit: "java".into(),
                source_languages: vec![SourceLanguage::Java],
                worlds: vec![],
                semantic_digest: String::new(),
                producer: "javac-v3".into(),
                modules: vec![],
                details: BackendContext::Java {
                    source_version: "8".into(),
                    classpath: vec![],
                    toolchain: Default::default(),
                },
                capabilities: vec![],
            },
            output_limits: Default::default(),
            scratch: Default::default(),
        };
        (worker, peer)
    }
    #[tokio::test]
    async fn liveness_observation_leaves_exit_status_for_group_cleanup() {
        let mut child = Command::new("/usr/bin/true")
            .process_group(0)
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let pid = child.id().unwrap();
        tokio::time::timeout(Duration::from_secs(3), async {
            while !exited_without_reaping(pid).unwrap() {
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .unwrap();
        // A second observation still succeeds: neither probe released the PID or status.
        assert!(exited_without_reaping(pid).unwrap());
        assert_eq!(child.id(), Some(pid));
        assert!(terminate_group(&mut child, pid).await.unwrap().success());
        assert!(child.id().is_none());
    }
    #[tokio::test]
    async fn group_termination_reaps_the_owned_process_before_returning() {
        let mut child = Command::new("/bin/sleep")
            .arg("60")
            .process_group(0)
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let group = child.id().unwrap();
        tokio::time::timeout(Duration::from_secs(3), terminate_group(&mut child, group))
            .await
            .unwrap()
            .unwrap();
        assert!(child.id().is_none(), "termination must await reaping");
        assert!(child.try_wait().unwrap().is_some());
    }

    #[tokio::test]
    async fn reaped_worker_never_signals_a_reused_group_on_shutdown_or_drop() {
        let mut child = Command::new("/usr/bin/true")
            .process_group(0)
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        child.wait().await.unwrap();
        // A second owned process stands in for a group that has reused the old numeric ID.
        let mut sentinel = Command::new("/bin/sleep")
            .arg("60")
            .process_group(0)
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let group = sentinel.id().unwrap();
        let (mut worker, _peer) = test_worker(child, group, std::sync::Arc::default());
        let monitor_signals = worker.group_signals.clone();
        assert!(!worker.alive().unwrap());
        worker.shutdown().await.unwrap();
        worker.shutdown().await.unwrap();
        drop(worker);
        signal_monitored_group(group, &monitor_signals);
        assert!(
            sentinel.try_wait().unwrap().is_none(),
            "a reaped worker must never signal a reused ID"
        );
        terminate_group(&mut sentinel, group).await.unwrap();
    }

    #[test]
    fn python_worker_requires_current_adapter_and_pinned_backend() {
        let mut context = BuildContext {
            unit: "python".into(),
            source_languages: vec![SourceLanguage::Python],
            worlds: vec![],
            semantic_digest: String::new(),
            producer: codeana_model::python::PRODUCER.into(),
            modules: vec![],
            details: BackendContext::Python {
                python_version: "3.12".into(),
                platform: "darwin".into(),
                adapter: "old-adapter".into(),
                backend_commit: codeana_model::python::BACKEND_COMMIT.into(),
            },
            capabilities: vec!["pyrefly-1.4.0-codeana-v1".into()],
        };
        assert!(validate_python_context(&context).is_err());
        if let BackendContext::Python { adapter, .. } = &mut context.details {
            *adapter = codeana_model::python::ADAPTER_VERSION.into();
        }
        validate_python_context(&context).unwrap();
        if let BackendContext::Python { backend_commit, .. } = &mut context.details {
            *backend_commit = "wrong-commit".into();
        }
        assert!(validate_python_context(&context).is_err());
    }

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
