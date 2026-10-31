//! Process-isolated Rust analysis over immutable captured inputs.
use anyhow::{Context, Result, bail, ensure};
use clap::Parser;
use codeana_contracts::{
    FrameReader, envelope, map, worker as w, worker::envelope::Body, write_frame,
};
use codeana_model::*;
use std::{
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
    fd: Option<i32>,
    /// Internal preparation helper: shares config validation and globset with capture.
    #[arg(long)]
    prepare_inputs: bool,
}
async fn run() -> Result<()> {
    let args = Args::parse();
    if args.prepare_inputs {
        use std::io::Read;
        ensure!(args.fd.is_none(), "DEDICATED_FD_CONFLICT");
        let mut bytes = Vec::new();
        std::io::stdin()
            .take((32 << 20) + 1)
            .read_to_end(&mut bytes)?;
        ensure!(bytes.len() <= 32 << 20, "RUST_INPUT_BUDGET");
        let result = codeana_config::rust_inputs::prepare(serde_json::from_slice(&bytes)?)?;
        println!("{}", serde_json::to_string(&result)?);
        return Ok(());
    }
    let fd = args.fd.context("DEDICATED_FD_REQUIRED")?;
    ensure!(fd >= 3, "DEDICATED_FD_REQUIRED");
    // SAFETY: descriptor 3 is exclusively transferred by the supervisor.
    let socket = unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) };
    socket.set_nonblocking(true)?;
    session(UnixStream::from_std(socket)?).await
}
async fn session(socket: UnixStream) -> Result<()> {
    let (mut input, mut ipc) = socket.into_split();
    let mut frames = FrameReader::default();
    let mut selected: Option<(rust::AnalysisConfig, Manifest)> = None;
    let mut task_meter = AnalysisTaskMeter::default();
    let mut hello = false;
    let mut pending_ack = false;
    let mut session_attempt: Option<String> = None;
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
                    let failure = codeana_contracts::analysis_budget::failure_receipt(
                        &error,
                        &grant,
                        &budget,
                        "input_decode",
                    );
                    let _ = write_frame(
                        &mut ipc,
                        &envelope(
                            frames
                                .pending_attempt()
                                .as_deref()
                                .or(session_attempt.as_deref())
                                .unwrap_or("invalid"),
                            Body::Failure(failure),
                        ),
                    )
                    .await;
                }
                return Err(error);
            }
        };
        let attempt = frame.attempt;
        match &frame.body {
            Some(Body::Hello(_)) => ensure!(!hello, "DUPLICATE_HELLO"),
            Some(Body::Discover(_)) => {
                ensure!(
                    hello && selected.is_none() && !pending_ack,
                    "DISCOVER_STATE"
                );
                session_attempt = Some(attempt.clone());
            }
            Some(Body::Analyze(_)) => {
                ensure!(hello && selected.is_some() && !pending_ack, "ANALYZE_STATE");
                session_attempt = Some(attempt.clone());
            }
            _ => ensure!(
                session_attempt.as_ref() == Some(&attempt),
                "ATTEMPT_MISMATCH"
            ),
        }
        match frame.body {
            Some(Body::Hello(h)) => {
                ensure!(
                    h.protocol == WORKER_PROTOCOL_VERSION && h.fact_ir == VERSION,
                    "UNSUPPORTED_PROTOCOL"
                );
                hello = true;
                session_attempt = Some(attempt.clone());
                let initialized = codeana_contracts::analysis_budget::begin_hello(
                    &mut task_meter,
                    &h,
                    AnalysisLanguage::Rust,
                );
                let (hello_grant, hello_budget) = match initialized {
                    Ok(v) => v,
                    Err(error) => {
                        if let Some((g, b)) = task_meter.active() {
                            let _ = write_frame(
                                &mut ipc,
                                &envelope(
                                    &attempt,
                                    Body::Failure(
                                        codeana_contracts::analysis_budget::failure_receipt(
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
                    &mut ipc,
                    &envelope(
                        &attempt,
                        Body::Hello(w::Hello {
                            protocol: WORKER_PROTOCOL_VERSION,
                            fact_ir: VERSION,
                            producer: "rust-core".into(),
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
                let initialized = codeana_contracts::analysis_budget::begin_discovery(
                    &mut task_meter,
                    &d,
                    AnalysisLanguage::Rust,
                );
                let (grant, budget) = match initialized {
                    Ok(active) => active,
                    Err(error) => {
                        if let Some((grant, budget)) = task_meter.active() {
                            write_frame(
                                &mut ipc,
                                &envelope(
                                    &attempt,
                                    Body::Failure(
                                        codeana_contracts::analysis_budget::failure_receipt(
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
                    eprintln!("codeana-rust-phase=inputs");
                    ensure!(
                        d.java.is_none() && d.python.is_none(),
                        "RUST_DISCOVERY_CONFIG"
                    );
                    let descriptor = d.rust.context("RUST_DISCOVERY_REQUIRED")?;
                    budget.charge(
                        1,
                        1,
                        descriptor.bytes.saturating_mul(8).saturating_add(1024),
                    )?;
                    let decoding_budget = budget.clone();
                    let config =
                        tokio::task::spawn_blocking(move || -> Result<rust::AnalysisConfig> {
                            ensure!(descriptor.bytes <= 32 << 20, "RUST_INPUT_BUDGET");
                            ensure!(
                                std::fs::metadata(&descriptor.input)?.len() == descriptor.bytes,
                                "RUST_INPUT_CHANGED"
                            );
                            let mut bytes = Vec::new();
                            use std::io::Read;
                            std::fs::File::open(&descriptor.input)?
                                .take(descriptor.bytes + 1)
                                .read_to_end(&mut bytes)?;
                            ensure!(
                                bytes.len() as u64 == descriptor.bytes
                                    && hash(&bytes) == descriptor.sha256,
                                "RUST_INPUT_CHANGED"
                            );
                            let config: rust::AnalysisConfig =
                                codeana_contracts::json_budget::decode_with_budget(
                                    &bytes,
                                    &decoding_budget,
                                )?;
                            config.inputs.validate().map_err(anyhow::Error::msg)?;
                            Ok(config)
                        })
                        .await??;
                    let manifest: Manifest = map(&d.manifest.context("MANIFEST_REQUIRED")?)?;
                    budget.charge(
                        config.inputs.crates.len() as u64,
                        config.inputs.crates.len() as u64,
                        128 * config.inputs.crates.len() as u64,
                    )?;
                    let context = BuildContext {
                        language: "rust".into(),
                        modules: config
                            .inputs
                            .crates
                            .iter()
                            .filter(|c| c.publish)
                            .map(|c| c.module.clone())
                            .collect(),
                        source_version: config.inputs.toolchain.clone(),
                        classpath: vec![],
                        producer: rust::PRODUCER.into(),
                        capabilities: vec![
                            "analysis-resources-v1".into(),
                            "declarations".into(),
                            "references".into(),
                            "calls".into(),
                            codeana_rust_adaptor::ADAPTER_VERSION.into(),
                        ],
                    };
                    codeana_contracts::write_frame_with_budget(
                        &mut ipc,
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

                    Ok(())
                }
                .await;
                if let Err(error) = discovered {
                    write_frame(
                        &mut ipc,
                        &envelope(
                            &attempt,
                            Body::Failure(codeana_contracts::analysis_budget::failure_receipt(
                                &error, &grant, &budget, "discover",
                            )),
                        ),
                    )
                    .await?;
                    return Ok(());
                }
            }
            Some(Body::Analyze(a)) => {
                let (config, captured) = selected.as_ref().context("DISCOVER_REQUIRED")?;
                let manifest: Manifest = map(a.manifest.as_ref().context("MANIFEST_REQUIRED")?)?;
                ensure!(manifest.id() == captured.id(), "INPUT_DRIFT");
                let grant = Some(codeana_contracts::analysis_budget::required_grant(
                    a.analysis_grant.as_ref(),
                    AnalysisLanguage::Rust,
                )?);
                let budget = task_meter
                    .resume(&grant, a.deadline_ms.ok_or(AnalysisFailure::InvalidUsage)?)?;
                let phase = Arc::new(std::sync::Mutex::new("input_decode"));
                let result = tokio::select! {
                    biased;
                    incoming=frames.next_cancel(&mut input)=>{
                        codeana_contracts::analysis_budget::expect_cancel(incoming?,&attempt)?;
                        let cancelled_phase=*phase.lock().unwrap();
                        let _=tokio::time::timeout(std::time::Duration::from_millis(50),write_frame(&mut ipc,&envelope(&attempt,Body::Failure(codeana_contracts::analysis_budget::cancelled_receipt(&grant,&budget,cancelled_phase,false))))).await;
                        return Ok(());
                    }
                    result=async {
                        let config=codeana_contracts::json_budget::clone_with_budget(config,&budget)?;
                        analyze_stream(config,a,manifest,attempt.clone(),&mut ipc,grant.clone(),budget.clone(),phase.clone()).await
                    }=>result,
                };
                if let Err(error) = result {
                    let failed_phase = *phase.lock().unwrap();
                    write_frame(
                        &mut ipc,
                        &envelope(
                            &attempt,
                            Body::Failure(w::Failure {
                                analysis_usage: grant.as_ref().map(|g| {
                                    codeana_contracts::analysis_budget::receipt(
                                        g,
                                        &budget.usage(),
                                        failed_phase,
                                    )
                                }),
                                code: budget
                                    .usage()
                                    .failure
                                    .map(|f| DomainError::analysis(f).code.to_string())
                                    .unwrap_or_else(|| "RUST_ANALYSIS_FAILED".into()),
                                message: format!("{error:#}"),
                            }),
                        ),
                    )
                    .await?;
                    return Ok(());
                }
                pending_ack = true;
            }

            Some(Body::Ack(a)) => {
                ensure!(pending_ack, "PUBLISH_ACK_REQUIRED");
                write_frame(
                    &mut ipc,
                    &envelope(
                        &attempt,
                        Body::Acked(w::Acked {
                            revision: a.revision,
                        }),
                    ),
                )
                .await?;
                return Ok(());
            }
            Some(Body::Reuse(_)) => {
                ensure!(selected.is_some() && !pending_ack, "ANALYSIS_REUSE_STATE");
                let (grant, budget) = task_meter.active().context("DISCOVER_REQUIRED")?;
                budget.checkpoint()?;
                write_frame(
                    &mut ipc,
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
                selected = None;
            }
            Some(Body::Cancel(_)) => return Ok(()),
            _ => bail!("UNEXPECTED_WORKER_MESSAGE"),
        }
    }
}
struct StopOnDrop(Arc<AtomicBool>);
impl Drop for StopOnDrop {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Relaxed);
    }
}
#[allow(clippy::too_many_arguments)]
async fn analyze_stream(
    mut config: rust::AnalysisConfig,
    a: w::Analyze,
    manifest: Manifest,
    attempt: String,
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    grant: Option<AnalysisGrant>,
    budget: AnalysisContext,
    phase: Arc<std::sync::Mutex<&'static str>>,
) -> Result<()> {
    use codeana_contracts::stream::{GraphStream, GraphStreamEvent};
    let stopped = Arc::new(AtomicBool::new(false));
    let _stop = StopOnDrop(stopped.clone());
    let (header_tx, header_rx) = tokio::sync::oneshot::channel();
    let (sender, mut records) = GraphStream::channel();
    let metrics = sender.metrics.clone();
    let meter = budget.clone();
    let work = tokio::task::spawn_blocking(move || -> Result<(u64, String)> {
        let check = || {
            ensure!(!stopped.load(Ordering::Relaxed), "CANCELLED");
            meter.checkpoint()?;
            Ok(())
        };
        meter.charge(
            1,
            0,
            (a.baseline_summary_json.len() as u64).saturating_mul(2),
        )?;
        let baseline: BaselineSummary = if a.full || a.baseline_summary_json.is_empty() {
            BaselineSummary::default()
        } else {
            match codeana_contracts::json_budget::decode_with_budget::<BaselineInput>(
                &a.baseline_summary_json,
                &meter,
            )? {
                BaselineInput::Inline(summary) => summary,
                BaselineInput::File(file) => {
                    ensure!(file.bytes <= 64 << 20, "BASELINE_BUDGET");
                    meter.charge(1, 0, file.bytes.saturating_mul(2))?;
                    let mut bytes = Vec::new();
                    use std::io::Read;
                    std::fs::File::open(file.path)?
                        .take(file.bytes + 1)
                        .read_to_end(&mut bytes)?;
                    ensure!(
                        bytes.len() as u64 == file.bytes && hash(&bytes) == file.sha256,
                        "BASELINE_CHANGED"
                    );
                    codeana_contracts::json_budget::decode_with_budget(&bytes, &meter)?
                }
            }
        };
        config.baseline = (!a.full).then_some(baseline.rust_states);
        *phase.lock().unwrap() = "backend";
        let analysis = codeana_rust_adaptor::analyze_with_context(&config, &check, &meter)?;
        let started = std::time::Instant::now();
        eprintln!("codeana-rust-phase=incremental");
        *phase.lock().unwrap() = "incremental";
        let prepared = codeana_rust_adaptor::records::prepare_with_context(
            &config,
            &analysis,
            config.baseline.as_deref(),
            Some(&meter),
        )?;
        eprintln!(
            "codeana-rust-timing incremental_ms={}",
            started.elapsed().as_millis()
        );
        let replacements = prepared
            .replacements
            .iter()
            .map(|(file, world)| {
                map(&Owner {
                    world: world.clone().into(),
                    producer: rust::PRODUCER.into(),
                    kind: "source".into(),
                    id: file.clone(),
                })
            })
            .collect::<Result<Vec<_>>>()?;
        header_tx
            .send(w::Begin {
                mode: if a.full { "full" } else { "delta" }.into(),
                baseline: a.baseline,
                manifest: manifest.id(),
                profile: a.profile,
                producer: rust::PRODUCER.into(),
                replacements,
            })
            .map_err(|_| anyhow::anyhow!("CANCELLED"))?;
        eprintln!("codeana-rust-phase=records");
        let started = std::time::Instant::now();
        *phase.lock().unwrap() = "records";
        let (count, checksum) = codeana_contracts::worker_rows::produce_with_budget(
            &sender,
            config.result_bytes,
            config.fact_limit,
            &check,
            Some(&meter),
            |push| {
                codeana_rust_adaptor::records::emit_with_context(
                    &config,
                    &analysis,
                    &prepared,
                    Some(&meter),
                    push,
                )
            },
        )?;
        eprintln!(
            "codeana-rust-timing records_and_backpressure_ms={}",
            started.elapsed().as_millis()
        );
        Ok((count, checksum))
    });
    let header = match header_rx.await {
        Ok(header) => header,
        Err(_) => {
            return match work.await? {
                Err(error) => Err(error),
                Ok(_) => bail!("RESULT_BEGIN_MISSING"),
            };
        }
    };
    codeana_contracts::write_frame_with_budget(
        writer,
        &envelope(&attempt, Body::Begin(header)),
        Some(&budget),
    )
    .await?;
    let mut seq = 0;
    while let Some(item) = records.recv().await {
        match item.event {
            GraphStreamEvent::Batch(rows) => {
                let frame = envelope(&attempt, Body::Chunk(w::Chunk { seq, rows }));
                // IPC serialization creates another buffer after archive hashing.

                codeana_contracts::write_frame_with_budget(writer, &frame, Some(&budget)).await?;
                seq += 1;
            }
            GraphStreamEvent::VerifiedEnd => break,
        }
    }
    let (count, checksum) = work.await??;
    codeana_contracts::write_frame_with_budget(
        writer,
        &envelope(
            &attempt,
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
    eprintln!(
        "codeana-rust-stream {}",
        serde_json::to_string(&metrics.summary())?
    );
    Ok(())
}
#[tokio::main]
async fn main() -> Result<()> {
    run().await
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;
    use tokio::io::AsyncWriteExt;
    #[tokio::test]
    async fn rejects_legacy_handshake_before_discovery() -> Result<()> {
        let (mut client, server) = UnixStream::pair()?;
        let task = tokio::spawn(session(server));
        write_frame(
            &mut client,
            &envelope(
                "old",
                Body::Hello(w::Hello {
                    protocol: 21,
                    fact_ir: VERSION,
                    producer: "daemon".into(),
                    ..Default::default()
                }),
            ),
        )
        .await?;
        let error = tokio::time::timeout(std::time::Duration::from_secs(1), task)
            .await??
            .unwrap_err();
        assert!(error.to_string().contains("UNSUPPORTED_PROTOCOL"));
        Ok(())
    }
    #[tokio::test]
    async fn partial_control_frame_cancels_with_unconsumed_output() -> Result<()> {
        worker_fixture(false).await
    }
    #[tokio::test]
    async fn wire_deadline_returns_typed_failure_and_cumulative_usage() -> Result<()> {
        worker_fixture(true).await
    }
    async fn worker_fixture(expire: bool) -> Result<()> {
        use std::collections::BTreeMap;
        let scratch = tempfile::tempdir()?;
        let source = (0..5000)
            .map(|i| format!("pub fn function_{i}() {{}}\n"))
            .collect::<String>();
        let path = scratch.path().join("lib.rs");
        std::fs::write(&path, &source)?;
        let config = rust::AnalysisConfig {
            baseline: None,
            excluded: Default::default(),
            inputs: rust::Inputs {
                schema_version: 2,
                toolchain: "fixture".into(),
                policy: hash(b"policy"),
                controls: BTreeMap::new(),
                generated: BTreeMap::new(),
                dependency_sources: BTreeMap::new(),
                omitted_dependencies: BTreeMap::new(),
                selection: Default::default(),
                crates: vec![rust::CrateInput {
                    id: "crate".into(),
                    name: "fixture".into(),
                    module: "fixture".into(),
                    world: "world".into(),
                    scope: "MAIN".into(),
                    root: "lib.rs".into(),
                    directory: ".".into(),
                    edition: "2024".into(),
                    target: "fixture".into(),
                    cfg: vec![],
                    dependencies: BTreeMap::new(),
                    environment: BTreeMap::new(),
                    publish: true,
                    proc_macro: false,
                    gaps: vec![],
                }],
            },
            files: vec![rust::FrozenFile {
                path: "lib.rs".into(),
                hash: hash(&source),
                input: path,
                crates: vec!["crate".into()],
            }],
            mode: codeana_model::rust::RustAnalysisMode::Syntax,
            scratch: scratch.path().into(),
            result_bytes: 64 << 20,
            fact_limit: 100000,
        };
        let manifest = Manifest {
            files: vec![SourceUnit {
                path: "lib.rs".into(),
                hash: hash(&source),
                size: source.len() as u64,
                module: "fixture".into(),
                world: "world".into(),
                scope: "MAIN".into(),
                language: "rust".into(),
                auxiliary: false,
            }],
        };
        let bytes = serde_json::to_vec(&config)?;
        let input = scratch.path().join("inputs.json");
        std::fs::write(&input, &bytes)?;
        let (mut client, server) = UnixStream::pair()?;
        let task = tokio::spawn(session(server));
        write_frame(
            &mut client,
            &envelope(
                "hello",
                Body::Hello(w::Hello {
                    protocol: WORKER_PROTOCOL_VERSION,
                    fact_ir: VERSION,
                    producer: "test".into(),
                    analysis_grant: Some(codeana_contracts::analysis_budget::encode_grant(
                        &AnalysisGrant {
                            id: "wire/rust/worker".into(),
                            language: AnalysisLanguage::Rust,
                            limits: Default::default(),
                        },
                    )),
                    deadline_ms: Some(0),
                    ..Default::default()
                }),
            ),
        )
        .await?;
        assert!(matches!(
            codeana_contracts::read_frame(&mut client).await?.body,
            Some(Body::Hello(_))
        ));
        write_frame(
            &mut client,
            &envelope(
                "discover",
                Body::Discover(w::Discover {
                    deadline_ms: Some(0),
                    analysis_grant: Some({
                        Box::new(codeana_contracts::analysis_budget::encode_grant(
                            &AnalysisGrant {
                                id: "wire/rust/worker".into(),
                                language: AnalysisLanguage::Rust,
                                limits: Default::default(),
                            },
                        ))
                    }),
                    python: None,
                    manifest: Some(map(&manifest)?),
                    rust: Some(Box::new(w::RustConfig {
                        input: input.to_string_lossy().into_owned(),
                        sha256: hash(&bytes),
                        bytes: bytes.len() as u64,
                        result_bytes: 64 << 20,
                        fact_limit: 100000,
                    })),
                    ..Default::default()
                }),
            ),
        )
        .await?;
        let mut frames = FrameReader::default();
        assert!(matches!(
            frames.next(&mut client).await?.body,
            Some(Body::Context(_))
        ));
        write_frame(
            &mut client,
            &envelope(
                "analyze",
                Body::Analyze(w::Analyze {
                    manifest: Some(map(&manifest)?),
                    full: true,
                    profile: "fixture".into(),
                    deadline_ms: Some(if expire { 1 } else { 0 }),
                    analysis_grant: Some({
                        codeana_contracts::analysis_budget::encode_grant(&AnalysisGrant {
                            id: "wire/rust/worker".into(),
                            language: AnalysisLanguage::Rust,
                            limits: Default::default(),
                        })
                    }),
                    ..Default::default()
                }),
            ),
        )
        .await?;
        if expire {
            let frame =
                tokio::time::timeout(std::time::Duration::from_secs(20), frames.next(&mut client))
                    .await??;
            let Some(Body::Failure(failure)) = frame.body else {
                bail!("deadline must fail before a publishable stream");
            };
            assert_eq!(failure.code, "TIMEOUT");
            let receipt = failure.analysis_usage.context("USAGE_REQUIRED")?;
            assert_eq!(receipt.grant_id, "wire/rust/worker");
            let grant = AnalysisGrant {
                id: "wire/rust/worker".into(),
                language: AnalysisLanguage::Rust,
                limits: Default::default(),
            };
            let usage = codeana_contracts::analysis_budget::decode_receipt(&receipt, &grant)?;
            assert_eq!(usage.failure, Some(AnalysisFailure::Deadline));
            tokio::time::timeout(std::time::Duration::from_secs(2), task).await???;
            return Ok(());
        }
        assert!(matches!(
            tokio::time::timeout(std::time::Duration::from_secs(20), frames.next(&mut client))
                .await??
                .body,
            Some(Body::Begin(_))
        ));
        let cancel = envelope("analyze", Body::Cancel(w::Cancel {})).encode_to_vec();
        let prefix = (cancel.len() as u32).to_be_bytes();
        client.write_all(&prefix[..2]).await?;
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        assert!(!task.is_finished());
        client.write_all(&prefix[2..]).await?;
        client.write_all(&cancel).await?;
        tokio::time::timeout(std::time::Duration::from_secs(2), task).await???;
        Ok(())
    }
}

#[cfg(test)]
mod meter_lifecycle_tests {
    use super::*;
    #[test]
    fn subsequent_attempts_keep_consumption_and_cannot_replace_grant() {
        let grant = Some(AnalysisGrant {
            id: "task/rust/worker".into(),
            language: AnalysisLanguage::Rust,
            limits: AnalysisLimits {
                max_work: 5,
                ..Default::default()
            },
        });
        let mut active = AnalysisTaskMeter::default();
        active.resume(&grant, 0).unwrap().charge(3, 0, 0).unwrap();
        let resumed = active.resume(&grant, 0).unwrap();
        assert_eq!(resumed.usage().work, 3);
        let mut replacement = grant.clone();
        replacement.as_mut().unwrap().id = "other".into();
        assert!(active.resume(&replacement, 0).is_err());
        assert!(resumed.charge(3, 0, 0).is_err());
        assert_eq!(resumed.usage().work, 3);
    }
    #[test]
    fn subsequent_attempts_cannot_extend_the_initial_deadline() {
        let grant = Some(AnalysisGrant {
            id: "task/rust/worker".into(),
            language: AnalysisLanguage::Rust,
            limits: Default::default(),
        });
        let mut active = AnalysisTaskMeter::default();
        let first = active.resume(&grant, 1).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
        assert!(active.resume(&grant, 10000).is_err());
        assert_eq!(first.checkpoint(), Err(AnalysisFailure::Deadline));
        assert_eq!(
            DomainError::analysis(AnalysisFailure::Deadline).code,
            ErrorCode::Timeout
        );
    }
}
