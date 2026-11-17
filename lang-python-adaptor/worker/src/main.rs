//! Process-isolated Pyrefly worker. Only captured inputs enter the backend.
use anyhow::{Context, Result, bail, ensure};
use clap::Parser;
use codeana_contracts::{envelope, map, worker as w, worker::envelope::Body};
use codeana_graph_io::stream::{GraphStream, GraphStreamEvent};
use codeana_model::*;
use codeana_worker_runtime::{FrameReader, write_frame};
use std::{
    io::Read,
    os::fd::FromRawFd,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::net::UnixStream;

#[derive(Parser)]
struct Args {
    #[arg(long)]
    fd: i32,
}

struct Stop(Arc<AtomicBool>);
impl Drop for Stop {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Relaxed);
    }
}

#[allow(clippy::too_many_arguments)]
async fn analyze(
    config: python::AnalysisConfig,
    manifest: Manifest,
    request: w::Analyze,
    attempt: &str,
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    grant: Option<AnalysisGrant>,
    budget: AnalysisContext,
    phase: Arc<std::sync::Mutex<&'static str>>,
) -> Result<()> {
    ensure!(request.full, "PYTHON_FULL_ANALYSIS_REQUIRED");
    budget.charge(
        manifest.files.len() as u64,
        manifest.files.len() as u64,
        manifest
            .files
            .iter()
            .map(|f| (f.path.len() + f.world.len() + 256) as u64)
            .sum(),
    )?;
    let replacements = manifest
        .files
        .iter()
        .filter(|s| s.language == "python" && !s.auxiliary)
        .map(|s| {
            map(&Owner {
                world: s.world.clone().into(),
                producer: python::PRODUCER.into(),
                kind: "source".into(),
                id: s.path.clone(),
            })
        })
        .collect::<Result<Vec<_>>>()?;
    codeana_worker_runtime::write_frame_with_budget(
        writer,
        &envelope(
            attempt,
            Body::Begin(w::Begin {
                mode: "full".into(),
                baseline: request.baseline,
                manifest: manifest.id(),
                profile: request.profile.clone(),
                producer: python::PRODUCER.into(),
                replacements,
            }),
        ),
        Some(&budget),
    )
    .await?;
    let stopped = Arc::new(AtomicBool::new(false));
    let _stop = Stop(stopped.clone());
    let (sender, mut records) = GraphStream::channel();
    let meter = budget.clone();
    let work = tokio::task::spawn_blocking(move || -> Result<_> {
        let check = || {
            if stopped.load(Ordering::Relaxed) {
                meter.cancel();
            }
            meter.checkpoint()?;
            Ok(())
        };
        eprintln!("codeana-python-phase=analyze");
        *phase.lock().unwrap() = "backend";
        let analysis =
            codeana_python_adaptor::frozen::analyze_with_context(&config, &check, &meter)?;
        eprintln!("codeana-python-phase=records");
        *phase.lock().unwrap() = "records";
        codeana_graph_io::producer::produce_with_budget(
            &sender,
            config.result_bytes,
            config.fact_limit,
            &check,
            Some(&meter),
            |push| {
                codeana_python_adaptor::records::emit_with_context(
                    &analysis,
                    &manifest,
                    &request.profile,
                    &meter,
                    push,
                )
            },
        )
    });
    let mut seq = 0;
    while let Some(item) = records.recv().await {
        match item.event {
            GraphStreamEvent::Batch(rows) => {
                let frame = envelope(attempt, Body::Chunk(w::Chunk { seq, rows }));

                codeana_worker_runtime::write_frame_with_budget(writer, &frame, Some(&budget))
                    .await?;
                seq += 1;
            }
            GraphStreamEvent::VerifiedEnd(_) => break,
            GraphStreamEvent::Begin { .. } => anyhow::bail!("UNEXPECTED_GRAPH_BEGIN"),
        }
    }
    let (count, checksum) = work.await??;
    codeana_worker_runtime::write_frame_with_budget(
        writer,
        &envelope(
            attempt,
            Body::End(w::End {
                analysis_usage: grant.as_ref().map(|g| {
                    codeana_contracts::analysis_budget::receipt(g, &budget.usage(), "records")
                }),
                chunks: seq,
                count,
                checksum,
            }),
        ),
        Some(&budget),
    )
    .await?;
    Ok(())
}

async fn session(socket: UnixStream) -> Result<()> {
    let (mut input, mut writer) = socket.into_split();
    let mut frames = FrameReader::default();
    let mut selected: Option<(python::AnalysisConfig, Manifest)> = None;
    let mut task_meter = AnalysisTaskMeter::default();
    let mut control = codeana_worker_runtime::session::Session::new(
        codeana_worker_runtime::session::ReusePolicy::FreshProcess,
    );
    loop {
        let read = if let Some((_, budget)) = task_meter.active() {
            frames.next_with_budget(&mut input, &budget).await
        } else {
            frames.next_bootstrap(&mut input).await
        };
        let frame = match read {
            Ok(frame) => frame,
            Err(error) => {
                if let Some((grant, budget)) = task_meter.active() {
                    let failure = codeana_worker_runtime::analysis::failure_receipt(
                        &error,
                        &grant,
                        &budget,
                        "input_decode",
                    );
                    let _ = write_frame(
                        &mut writer,
                        &envelope(
                            frames
                                .pending_attempt()
                                .as_deref()
                                .or(control.attempt())
                                .unwrap_or("invalid"),
                            Body::Failure(failure),
                        ),
                    )
                    .await;
                }
                return Err(error);
            }
        };
        control.admit(&frame)?;
        let attempt = frame.attempt;
        match frame.body {
            Some(Body::Hello(h)) => {
                ensure!(
                    h.protocol == WORKER_PROTOCOL_VERSION && h.fact_ir == VERSION,
                    "UNSUPPORTED_PROTOCOL"
                );
                let initialized = codeana_worker_runtime::analysis::begin_hello(
                    &mut task_meter,
                    &h,
                    AnalysisBackend::Python,
                );
                let (hello_grant, hello_budget) = match initialized {
                    Ok(v) => v,
                    Err(error) => {
                        if let Some((g, b)) = task_meter.active() {
                            let _ = write_frame(
                                &mut writer,
                                &envelope(
                                    &attempt,
                                    Body::Failure(
                                        codeana_worker_runtime::analysis::failure_receipt(
                                            &error, &g, &b, "discover",
                                        ),
                                    ),
                                ),
                            )
                            .await;
                        }
                        return Err(error);
                    }
                };
                write_frame(
                    &mut writer,
                    &envelope(
                        &attempt,
                        Body::Hello(w::Hello {
                            protocol: WORKER_PROTOCOL_VERSION,
                            fact_ir: VERSION,
                            producer: python::PRODUCER.into(),
                            analysis_usage: hello_grant.as_ref().map(|g| {
                                codeana_contracts::analysis_budget::receipt(
                                    g,
                                    &hello_budget.usage(),
                                    "discover",
                                )
                            }),
                            ..Default::default()
                        }),
                    ),
                )
                .await?;
            }
            Some(Body::Discover(d)) => {
                let initialized = codeana_worker_runtime::analysis::begin_discovery(
                    &mut task_meter,
                    &d,
                    AnalysisBackend::Python,
                );
                let (grant, budget) = match initialized {
                    Ok(active) => active,
                    Err(error) => {
                        if let Some((grant, budget)) = task_meter.active() {
                            write_frame(
                                &mut writer,
                                &envelope(
                                    &attempt,
                                    Body::Failure(
                                        codeana_worker_runtime::analysis::failure_receipt(
                                            &error, &grant, &budget, "discover",
                                        ),
                                    ),
                                ),
                            )
                            .await?;
                            return Ok(());
                        }
                        return Err(error);
                    }
                };
                let discovered: Result<()> = async {
                    let context_languages =
                        codeana_contracts::build_context::discovery(&d, AnalysisBackend::Python)?;
                    let Some(w::discover::Config::Python(descriptor)) = d.config else {
                        bail!("PYTHON_DISCOVERY_CONFIG");
                    };
                    budget.charge(
                        1,
                        1,
                        descriptor.bytes.saturating_mul(8).saturating_add(1024),
                    )?;
                    let decoding_budget = budget.clone();
                    let config =
                        tokio::task::spawn_blocking(move || -> Result<python::AnalysisConfig> {
                            ensure!(descriptor.bytes <= 32 << 20, "PYTHON_INPUT_BUDGET");
                            let mut bytes = Vec::new();
                            std::fs::File::open(&descriptor.input)?
                                .take(descriptor.bytes + 1)
                                .read_to_end(&mut bytes)?;
                            ensure!(
                                bytes.len() as u64 == descriptor.bytes
                                    && hash(&bytes) == descriptor.sha256,
                                "PYTHON_INPUT_CHANGED"
                            );
                            let config: python::AnalysisConfig =
                                codeana_contracts::json_budget::decode_with_budget(
                                    &bytes,
                                    &decoding_budget,
                                )?;
                            config.inputs.validate().map_err(anyhow::Error::msg)?;
                            ensure!(
                                config.result_bytes == descriptor.result_bytes
                                    && config.fact_limit == descriptor.fact_limit
                                    && config.scratch_bytes == descriptor.scratch_bytes,
                                "PYTHON_BUDGET_MISMATCH"
                            );
                            Ok(config)
                        })
                        .await??;
                    let manifest: Manifest = map(&d.manifest.context("MANIFEST_REQUIRED")?)?;
                    budget.charge(
                        config.inputs.contexts.len() as u64,
                        config.inputs.contexts.len() as u64,
                        128 * config.inputs.contexts.len() as u64,
                    )?;
                    let mut context = BuildContext {
                        unit: d.unit.clone().into(),
                        source_languages: context_languages,
                        worlds: BuildContext::worlds_for(&manifest, AnalysisBackend::Python),
                        semantic_digest: String::new(),
                        modules: config
                            .inputs
                            .contexts
                            .iter()
                            .map(|c| c.module.clone())
                            .collect(),
                        details: BackendContext::Python {
                            python_version: "3.12".into(),
                            platform: "darwin".into(),
                            adapter: python::ADAPTER_VERSION.into(),
                            backend_commit: codeana_python_adaptor::BACKEND_COMMIT.into(),
                        },
                        producer: python::PRODUCER.into(),
                        capabilities: vec![
                            "analysis-resources-v1".into(),
                            "declarations".into(),
                            "references".into(),
                            "calls".into(),
                        ],
                    };
                    context.seal();
                    codeana_worker_runtime::write_frame_with_budget(
                        &mut writer,
                        &envelope(
                            &attempt,
                            Body::Context({
                                let mut wire: w::BuildContext = map(&context)?;
                                wire.analysis_usage = grant.as_ref().map(|g| {
                                    codeana_contracts::analysis_budget::receipt(
                                        g,
                                        &budget.usage(),
                                        "discover",
                                    )
                                });
                                wire
                            }),
                        ),
                        Some(&budget),
                    )
                    .await?;
                    selected = Some((config, manifest));
                    control.discovered()?;

                    Ok(())
                }
                .await;
                if let Err(error) = discovered {
                    write_frame(
                        &mut writer,
                        &envelope(
                            &attempt,
                            Body::Failure(codeana_worker_runtime::analysis::failure_receipt(
                                &error, &grant, &budget, "discover",
                            )),
                        ),
                    )
                    .await?;
                    return Ok(());
                }
            }
            Some(Body::Analyze(request)) => {
                let (config, captured) = selected.as_ref().context("DISCOVER_REQUIRED")?;
                let manifest: Manifest =
                    map(request.manifest.as_ref().context("MANIFEST_REQUIRED")?)?;
                ensure!(manifest.id() == captured.id(), "INPUT_DRIFT");
                let grant = Some(codeana_contracts::analysis_budget::required_grant(
                    request.analysis_grant.as_ref(),
                    AnalysisBackend::Python,
                )?);
                let budget = task_meter.resume(
                    &grant,
                    request.deadline_ms.ok_or(AnalysisFailure::InvalidUsage)?,
                )?;
                let phase = Arc::new(std::sync::Mutex::new("input_decode"));
                let result = tokio::select! {
                    biased;
                    incoming = frames.next_cancel(&mut input) => {
                        let frame = incoming?;
                        codeana_worker_runtime::analysis::expect_cancel(frame,&attempt)?;
                        let cancelled_phase=*phase.lock().unwrap();
                        let _=tokio::time::timeout(std::time::Duration::from_millis(50),write_frame(&mut writer,&envelope(&attempt,Body::Failure(codeana_worker_runtime::analysis::cancelled_receipt(&grant,&budget,cancelled_phase,false))))).await;
                        return Ok(());
                    }
                    result = async {
                        let config=codeana_contracts::json_budget::clone_with_budget(config,&budget)?;
                        analyze(config, manifest, request, &attempt, &mut writer, grant.clone(), budget.clone(), phase.clone()).await
                    } => result,
                };
                if let Err(error) = result {
                    write_frame(
                        &mut writer,
                        &envelope(
                            &attempt,
                            Body::Failure(w::Failure {
                                analysis_usage: grant.as_ref().map(|g| {
                                    codeana_contracts::analysis_budget::receipt(
                                        g,
                                        &budget.usage(),
                                        *phase.lock().unwrap(),
                                    )
                                }),
                                code: budget
                                    .usage()
                                    .failure
                                    .map(|f| DomainError::analysis(f).code.to_string())
                                    .unwrap_or_else(|| "PYTHON_ANALYSIS_FAILED".into()),
                                message: format!("{error:#}"),
                            }),
                        ),
                    )
                    .await?;
                    return Ok(());
                }
                control.completed()?;
            }
            Some(Body::Ack(ack)) => {
                write_frame(
                    &mut writer,
                    &envelope(
                        &attempt,
                        Body::Acked(w::Acked {
                            revision: ack.revision,
                        }),
                    ),
                )
                .await?;
                control.acked()?;
                return Ok(());
            }
            Some(Body::Reuse(_)) => {
                let (grant, budget) = task_meter.active().context("DISCOVER_REQUIRED")?;
                budget.checkpoint()?;
                write_frame(
                    &mut writer,
                    &envelope(
                        &attempt,
                        Body::Reused(w::Reused {
                            analysis_usage: grant.as_ref().map(|g| {
                                codeana_contracts::analysis_budget::receipt(
                                    g,
                                    &budget.usage(),
                                    "reused",
                                )
                            }),
                        }),
                    ),
                )
                .await?;
                control.reused()?;
                selected = None;
            }
            Some(Body::Cancel(_)) => return Ok(()),
            _ => bail!("UNEXPECTED_WORKER_MESSAGE"),
        }
    }
}

// Match the pinned backend's default thread-pool stack. Its recursive solver
// runs inline on our blocking thread; Tokio's 2 MiB default is insufficient.
const ANALYSIS_STACK_BYTES: usize = 10 * 1024 * 1024;

fn main() -> Result<()> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .max_blocking_threads(1)
        .thread_stack_size(ANALYSIS_STACK_BYTES)
        .enable_all()
        .build()?
        .block_on(run())
}

async fn run() -> Result<()> {
    let args = Args::parse();
    ensure!(args.fd >= 3, "DEDICATED_FD_REQUIRED");
    // SAFETY: the supervisor exclusively transfers this owned IPC descriptor.
    let socket = unsafe { std::os::unix::net::UnixStream::from_raw_fd(args.fd) };
    socket.set_nonblocking(true)?;
    session(UnixStream::from_std(socket)?).await
}
