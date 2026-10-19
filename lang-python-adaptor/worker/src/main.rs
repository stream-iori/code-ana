//! Process-isolated Pyrefly worker. Only captured inputs enter the backend.
use anyhow::{Context, Result, bail, ensure};
use clap::Parser;
use codeana_contracts::{
    FrameReader, envelope, map,
    stream::{GraphStream, GraphStreamEvent},
    worker as w,
    worker::envelope::Body,
    write_frame,
};
use codeana_model::*;
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

async fn analyze(
    config: python::AnalysisConfig,
    manifest: Manifest,
    request: w::Analyze,
    attempt: &str,
    writer: &mut tokio::net::unix::OwnedWriteHalf,
) -> Result<()> {
    ensure!(request.full, "PYTHON_FULL_ANALYSIS_REQUIRED");
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
    write_frame(
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
    )
    .await?;
    let stopped = Arc::new(AtomicBool::new(false));
    let _stop = Stop(stopped.clone());
    let (sender, mut records) = GraphStream::channel();
    let work = tokio::task::spawn_blocking(move || -> Result<_> {
        let check = || {
            ensure!(!stopped.load(Ordering::Relaxed), "CANCELLED");
            Ok(())
        };
        eprintln!("codeana-python-phase=analyze");
        let analysis = codeana_python_adaptor::frozen::analyze(&config, &check)?;
        eprintln!("codeana-python-phase=records");
        codeana_contracts::worker_rows::produce(
            &sender,
            config.result_bytes,
            config.fact_limit,
            &check,
            |push| {
                codeana_python_adaptor::records::emit(&analysis, &manifest, &request.profile, push)
            },
        )
    });
    let mut seq = 0;
    while let Some(item) = records.recv().await {
        match item.event {
            GraphStreamEvent::Batch(rows) => {
                write_frame(
                    writer,
                    &envelope(attempt, Body::Chunk(w::Chunk { seq, rows })),
                )
                .await?;
                seq += 1;
            }
            GraphStreamEvent::VerifiedEnd => break,
        }
    }
    let (count, checksum) = work.await??;
    write_frame(
        writer,
        &envelope(
            attempt,
            Body::End(w::End {
                chunks: seq,
                count,
                checksum,
            }),
        ),
    )
    .await?;
    Ok(())
}

async fn session(socket: UnixStream) -> Result<()> {
    let (mut input, mut writer) = socket.into_split();
    let mut frames = FrameReader::default();
    let mut selected: Option<(python::AnalysisConfig, Manifest)> = None;
    let mut pending_ack = false;
    let mut session_attempt: Option<String> = None;
    loop {
        let frame = frames.next(&mut input).await?;
        let attempt = frame.attempt;
        // Discover and Analyze are separate attempts in the supervisor contract.
        if matches!(frame.body, Some(Body::Analyze(_))) && selected.is_some() && !pending_ack {
            session_attempt = Some(attempt.clone());
        }
        if let Some(expected) = &session_attempt {
            ensure!(expected == &attempt, "ATTEMPT_MISMATCH");
            ensure!(
                !matches!(frame.body, Some(Body::Hello(_))),
                "DUPLICATE_HELLO"
            );
        } else {
            ensure!(matches!(frame.body, Some(Body::Hello(_))), "HELLO_REQUIRED");
            session_attempt = Some(attempt.clone());
        }
        match frame.body {
            Some(Body::Hello(h)) => {
                ensure!(
                    h.protocol == WORKER_PROTOCOL_VERSION && h.fact_ir == VERSION,
                    "UNSUPPORTED_PROTOCOL"
                );
                write_frame(
                    &mut writer,
                    &envelope(
                        &attempt,
                        Body::Hello(w::Hello {
                            protocol: WORKER_PROTOCOL_VERSION,
                            fact_ir: VERSION,
                            producer: python::PRODUCER.into(),
                        }),
                    ),
                )
                .await?;
            }
            Some(Body::Discover(d)) => {
                ensure!(
                    d.java.is_none() && d.rust.is_none() && !pending_ack && selected.is_none(),
                    "PYTHON_DISCOVERY_CONFIG"
                );
                let descriptor = d.python.context("PYTHON_DISCOVERY_REQUIRED")?;
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
                        let config: python::AnalysisConfig = serde_json::from_slice(&bytes)?;
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
                let context = BuildContext {
                    language: "python".into(),
                    modules: config
                        .inputs
                        .contexts
                        .iter()
                        .map(|c| c.module.clone())
                        .collect(),
                    source_version: "3.12".into(),
                    classpath: vec![],
                    producer: python::PRODUCER.into(),
                    capabilities: vec![
                        python::ADAPTER_VERSION.into(),
                        "declarations".into(),
                        "references".into(),
                        "calls".into(),
                        format!("pyrefly-{}", codeana_python_adaptor::BACKEND_COMMIT),
                    ],
                };
                write_frame(
                    &mut writer,
                    &envelope(&attempt, Body::Context(map(&context)?)),
                )
                .await?;
                selected = Some((config, manifest));
            }
            Some(Body::Analyze(request)) => {
                ensure!(!pending_ack, "PUBLISH_ACK_REQUIRED");
                let (config, captured) = selected.as_ref().context("DISCOVER_REQUIRED")?;
                let manifest: Manifest =
                    map(request.manifest.as_ref().context("MANIFEST_REQUIRED")?)?;
                ensure!(manifest.id() == captured.id(), "INPUT_DRIFT");
                let result = tokio::select! {
                    biased;
                    incoming = frames.next(&mut input) => {
                        let frame = incoming?;
                        ensure!(frame.attempt == attempt && matches!(frame.body, Some(Body::Cancel(_))), "UNEXPECTED_WHILE_ANALYZING");
                        return Ok(());
                    }
                    result = analyze(config.clone(), manifest, request, &attempt, &mut writer) => result,
                };
                if let Err(error) = result {
                    write_frame(
                        &mut writer,
                        &envelope(
                            &attempt,
                            Body::Failure(w::Failure {
                                code: "PYTHON_ANALYSIS_FAILED".into(),
                                message: format!("{error:#}"),
                            }),
                        ),
                    )
                    .await?;
                    return Ok(());
                }
                pending_ack = true;
            }
            Some(Body::Ack(ack)) => {
                ensure!(pending_ack, "UNEXPECTED_ACK");
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
                return Ok(());
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
