//! Fresh-process worker IPC over authenticated frozen inputs and the native owner.
use crate::{frozen, native::Native};
use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::{
    analysis_budget, envelope, map, protobuf::typescript::v1 as p, worker as w,
    worker::envelope::Body,
};
use codeana_graph_io::stream::{GraphStream, GraphStreamEvent};
use codeana_model::*;
use codeana_worker_runtime::{
    FrameReader, analysis,
    ffi::{BudgetHandoff, Notification, Owner},
    session::{ReusePolicy, Session},
    write_frame, write_frame_with_budget,
};
use prost::Message;
use std::{
    io::Read,
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::OpenOptionsExt,
    },
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::net::{
    UnixStream,
    unix::{OwnedReadHalf, OwnedWriteHalf},
};

struct Selected {
    config: typescript::AnalysisConfig,
    manifest: Manifest,
    unit: AnalysisUnitId,
}

pub async fn run(fd: i32, owner: &Owner<Native>) -> Result<()> {
    ensure!(fd >= 3, "DEDICATED_FD_REQUIRED");
    // SAFETY: the supervisor transfers this descriptor exclusively to the worker.
    let socket = unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) };
    socket.set_nonblocking(true)?;
    session(UnixStream::from_std(socket)?, owner).await
}

async fn descriptor(
    value: w::TypeScriptConfig,
    budget: &AnalysisContext,
) -> Result<typescript::AnalysisConfig> {
    ensure!(
        value.bytes > 0 && value.bytes <= 32 << 20,
        "TYPESCRIPT_INPUT_BUDGET"
    );
    budget.charge(1, 1, value.bytes.saturating_mul(8) + 4096)?;
    let meter = budget.clone();
    tokio::task::spawn_blocking(move || -> Result<_> {
        let mut file = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(&value.input)?;
        ensure!(
            file.metadata()?.is_file() && file.metadata()?.len() == value.bytes,
            "TYPESCRIPT_INPUT_CHANGED"
        );
        let mut bytes = Vec::with_capacity(value.bytes as usize);
        (&mut file).take(value.bytes + 1).read_to_end(&mut bytes)?;
        ensure!(
            bytes.len() as u64 == value.bytes && hash(&bytes) == value.sha256,
            "TYPESCRIPT_INPUT_CHANGED"
        );
        let config: typescript::AnalysisConfig =
            codeana_contracts::json_budget::decode_with_budget(&bytes, &meter)?;
        config.snapshot.validate().map_err(anyhow::Error::msg)?;
        ensure!(
            config.result_bytes == value.result_bytes
                && config.fact_limit == value.fact_limit
                && config.scratch_bytes == value.scratch_bytes,
            "TYPESCRIPT_OUTPUT_IDENTITY"
        );
        ensure!(
            (65536..=MAX_RESULT as u64).contains(&config.result_bytes)
                && (1..=MAX_FACTS as u64).contains(&config.fact_limit)
                && config.scratch_bytes >= config.result_bytes
                && config.scratch_bytes <= 32u64 << 30,
            "TYPESCRIPT_OUTPUT_LIMIT"
        );
        ensure!(
            config.scratch.is_absolute()
                && config.scratch.parent() == Path::new(&value.input).parent(),
            "TYPESCRIPT_SCRATCH_OWNER"
        );
        Ok(config)
    })
    .await?
}

fn verify_manifest(config: &typescript::AnalysisConfig, manifest: &Manifest) -> Result<()> {
    let context = &config.snapshot.inputs.contexts[0];
    for reference in &config.snapshot.files {
        if !reference.publish {
            continue;
        }
        let original = context
            .files
            .iter()
            .find(|f| f.path == reference.source)
            .context("TYPESCRIPT_PUBLISH_FILE")?;
        let relative = Path::new(&reference.source)
            .strip_prefix(&config.snapshot.publication_root)?
            .to_str()
            .context("TYPESCRIPT_PATH_UTF8")?;
        ensure!(
            manifest.files.iter().any(|s| s.path == relative
                && !s.auxiliary
                && s.hash == original.sha256
                && s.size == original.bytes
                && Some(s.module.as_str()) == original.module.as_deref()
                && Some(s.scope.as_str()) == original.scope.as_deref()
                && original
                    .world
                    .as_ref()
                    .is_some_and(|w| w.as_str() == s.world)
                && original.language.is_some_and(|l| l.as_str() == s.language)),
            "TYPESCRIPT_PUBLISH_MANIFEST_MISMATCH"
        );
    }
    ensure!(
        manifest
            .files
            .iter()
            .filter(|s| !s.auxiliary
                && SourceLanguage::lookup(&s.language)
                    .is_some_and(|l| AnalysisBackend::TypeScript.supports(l)))
            .count()
            == config.snapshot.files.iter().filter(|f| f.publish).count(),
        "TYPESCRIPT_PUBLISH_MANIFEST_COVERAGE"
    );
    Ok(())
}

async fn session(socket: UnixStream, owner: &Owner<Native>) -> Result<()> {
    let (mut input, mut output) = socket.into_split();
    let mut frames = FrameReader::default();
    let mut state = Session::new(ReusePolicy::FreshProcess);
    let mut meter = AnalysisTaskMeter::default();
    let mut selected = None;
    loop {
        let frame = if let Some((_, budget)) = meter.active() {
            frames.next_with_budget(&mut input, &budget).await
        } else {
            frames.next_bootstrap(&mut input).await
        };
        let frame = match frame {
            Ok(frame) => frame,
            Err(error) => {
                if let Some((grant, budget)) = meter.active() {
                    let _ = write_frame(
                        &mut output,
                        &envelope(
                            frames
                                .pending_attempt()
                                .as_deref()
                                .or(state.attempt())
                                .unwrap_or("invalid"),
                            Body::Failure(analysis::failure_receipt(
                                &error,
                                &grant,
                                &budget,
                                "input_decode",
                            )),
                        ),
                    )
                    .await;
                }
                return Err(error);
            }
        };
        state.admit(&frame)?;
        let attempt = frame.attempt;
        let result: Result<bool> = async {
            match frame.body {
                Some(Body::Hello(h)) => {
                    ensure!(
                        h.protocol == WORKER_PROTOCOL_VERSION && h.fact_ir == VERSION,
                        "UNSUPPORTED_PROTOCOL"
                    );
                    let (grant, budget) =
                        analysis::begin_hello(&mut meter, &h, AnalysisBackend::TypeScript)?;
                    write_frame(
                        &mut output,
                        &envelope(
                            &attempt,
                            Body::Hello(w::Hello {
                                protocol: WORKER_PROTOCOL_VERSION,
                                fact_ir: VERSION,
                                producer: typescript::PRODUCER.into(),
                                analysis_usage: grant.as_ref().map(|g| {
                                    analysis_budget::receipt(g, &budget.usage(), "discover")
                                }),
                                ..Default::default()
                            }),
                        ),
                    )
                    .await?;
                }
                Some(Body::Discover(d)) => {
                    let (grant, budget) =
                        analysis::begin_discovery(&mut meter, &d, AnalysisBackend::TypeScript)?;
                    let languages = codeana_contracts::build_context::discovery(
                        &d,
                        AnalysisBackend::TypeScript,
                    )?;
                    let Some(w::discover::Config::Typescript(value)) = d.config else {
                        bail!("TYPESCRIPT_DISCOVERY_CONFIG")
                    };
                    eprintln!("codeana-typescript-phase=inputs");
                    let config = descriptor(*value, &budget).await?;
                    let unit: AnalysisUnitId = d.unit.into();
                    let context = &config.snapshot.inputs.contexts[0];
                    ensure!(
                        context.unit == unit && context.publish_languages == languages,
                        "TYPESCRIPT_DISCOVER_CONTEXT"
                    );
                    let compiler = owner.call(|n| Ok(n.compiler.clone())).await?;
                    ensure!(
                        config.snapshot.inputs.compiler == compiler,
                        "TYPESCRIPT_COMPILER_IDENTITY_MISMATCH"
                    );
                    let manifest: Manifest =
                        map(d.manifest.as_ref().context("MANIFEST_REQUIRED")?)?;
                    verify_manifest(&config, &manifest)?;
                    let mut context = BuildContext {
                        unit: unit.clone(),
                        source_languages: languages,
                        modules: manifest
                            .files
                            .iter()
                            .filter(|s| !s.auxiliary)
                            .map(|s| s.module.clone())
                            .collect::<std::collections::BTreeSet<_>>()
                            .into_iter()
                            .collect(),
                        worlds: BuildContext::worlds_for(&manifest, AnalysisBackend::TypeScript),
                        semantic_digest: String::new(),
                        producer: typescript::PRODUCER.into(),
                        capabilities: vec![
                            "analysis-resources-v1".into(),
                            "declarations".into(),
                            "references".into(),
                            "calls".into(),
                            "comments".into(),
                        ],
                        details: BackendContext::TypeScript {
                            compiler_version: compiler.compiler_version.clone(),
                            upstream_commit: compiler.upstream_commit.clone(),
                            adapter: compiler.semantic_adapter(),
                        },
                    };
                    context.seal();
                    let mut wire: w::BuildContext = map(&context)?;
                    wire.analysis_usage = grant
                        .as_ref()
                        .map(|g| analysis_budget::receipt(g, &budget.usage(), "discover"));
                    write_frame_with_budget(
                        &mut output,
                        &envelope(&attempt, Body::Context(wire)),
                        Some(&budget),
                    )
                    .await?;
                    selected = Some(Selected {
                        config,
                        manifest,
                        unit,
                    });
                    state.discovered()?;
                }
                Some(Body::Analyze(a)) => {
                    let selected = selected.as_ref().context("DISCOVER_REQUIRED")?;
                    let manifest: Manifest =
                        map(a.manifest.as_ref().context("MANIFEST_REQUIRED")?)?;
                    ensure!(
                        manifest.id() == selected.manifest.id() && !a.profile.is_empty(),
                        "INPUT_DRIFT"
                    );
                    let grant = analysis_budget::required_grant(
                        a.analysis_grant.as_ref(),
                        AnalysisBackend::TypeScript,
                    )?;
                    let budget = meter.resume(
                        &Some(grant.clone()),
                        a.deadline_ms.ok_or(AnalysisFailure::InvalidUsage)?,
                    )?;
                    analyze(
                        selected,
                        a,
                        &attempt,
                        owner,
                        &grant,
                        &budget,
                        &mut input,
                        &mut output,
                        &mut frames,
                    )
                    .await?;
                    state.completed()?;
                }
                Some(Body::Ack(a)) => {
                    owner.call(Native::published).await?;
                    owner.call(Native::close).await?;
                    write_frame(
                        &mut output,
                        &envelope(
                            &attempt,
                            Body::Acked(w::Acked {
                                revision: a.revision,
                            }),
                        ),
                    )
                    .await?;
                    state.acked()?;
                    return Ok(true);
                }
                Some(Body::Reuse(_)) => {
                    let (grant, budget) = meter.active().context("DISCOVER_REQUIRED")?;
                    budget.checkpoint()?;
                    write_frame(
                        &mut output,
                        &envelope(
                            &attempt,
                            Body::Reused(w::Reused {
                                analysis_usage: grant.as_ref().map(|g| {
                                    analysis_budget::receipt(g, &budget.usage(), "reused")
                                }),
                            }),
                        ),
                    )
                    .await?;
                    state.reused()?;
                    selected = None;
                }
                Some(Body::Cancel(_)) => return Ok(true),
                _ => bail!("UNEXPECTED_WORKER_MESSAGE"),
            }
            Ok(false)
        }
        .await;
        match result {
            Ok(true) => return Ok(()),
            Ok(false) => {}
            Err(error) => {
                if let Some((grant, budget)) = meter.active() {
                    let mut failure = analysis::failure_receipt(&error, &grant, &budget, "backend");
                    if budget.accounting_owner() != AccountingOwner::HostOwned
                        && let Some(receipt) = &mut failure.analysis_usage
                    {
                        receipt.usage_known = Some(false)
                    }
                    if failure.code == "DISCOVERY_FAILED" {
                        failure.code = "TYPESCRIPT_ANALYSIS_FAILED".into()
                    }
                    let _ =
                        write_frame(&mut output, &envelope(&attempt, Body::Failure(failure))).await;
                }
                let _ = owner.call(Native::cancel).await;
                let _ = owner.call(Native::close).await;
                return Err(error);
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn analyze(
    selected: &Selected,
    a: w::Analyze,
    attempt: &str,
    owner: &Owner<Native>,
    grant: &AnalysisGrant,
    budget: &AnalysisContext,
    input: &mut OwnedReadHalf,
    output: &mut OwnedWriteHalf,
    frames: &mut FrameReader,
) -> Result<()> {
    let compiler = owner.call(|n| Ok(n.compiler.clone())).await?;
    let project = frozen::load(&selected.config.snapshot, &selected.unit, &compiler, budget)?;
    let runtime = owner.call(|n| Ok(n.runtime.clone())).await?;
    let mut request = p::Request {
        version: typescript::ANALYSIS_VERSION,
        request_id: attempt.into(),
        expected_runtime: Some(runtime.clone()),
        grant: Some(analysis_budget::encode_grant(grant)),
        seed: Some(Default::default()),
        deadline_ms: Some(budget.remaining_millis()?.unwrap_or(0)),
        operation: Some(p::request::Operation::Analyze(p::Analyze {
            project: Some(project),
            publication_root: selected
                .config
                .snapshot
                .publication_root
                .to_str()
                .context("PUBLICATION_ROOT_UTF8")?
                .into(),
            semantic_profile: a.profile.clone(),
            scratch: selected
                .config
                .scratch
                .to_str()
                .context("SCRATCH_UTF8")?
                .into(),
            output: Some(p::OutputLimits {
                archive_bytes: selected.config.result_bytes,
                rows: selected.config.fact_limit,
                scratch_bytes: selected.config.scratch_bytes,
            }),
        })),
    };
    budget.charge(1, 32, (request.encoded_len() as u64 + 128) * 16 + (4 << 20))?;
    let seed = budget.usage();
    request.seed = Some(analysis_budget::encode_usage(&seed));
    let bytes = request.encode_to_vec();
    let (notification, fd) = Notification::pair()?;
    let mut handoff = BudgetHandoff::begin(budget, &seed)?;
    eprintln!("codeana-typescript-phase=backend_run");
    owner
        .call(move |n| {
            n.prepare(bytes, fd.as_raw_fd())?;
            n.run()
        })
        .await?;
    let completion = notification.wait_eof();
    tokio::pin!(completion);
    let cancelled = tokio::select! {
        result=&mut completion=>{result?;false},
        incoming=frames.next_cancel(input)=>{
            analysis::expect_cancel(incoming?,attempt)?;
            owner.call(Native::cancel).await?;
            tokio::time::timeout(Duration::from_secs(2),&mut completion).await??;
            true
        }
    };
    eprintln!("codeana-typescript-phase=archive");
    let bytes = owner.call(Native::result).await?;
    let result = codeana_contracts::typescript::decode_result(&bytes)?;
    ensure!(
        result.request_id == attempt && result.runtime.as_ref() == Some(&runtime),
        "TYPESCRIPT_RESULT_IDENTITY"
    );
    let receipt = result
        .receipt
        .as_ref()
        .context("TYPESCRIPT_RESULT_RECEIPT")?;
    ensure!(
        receipt.usage_known == Some(true),
        "TYPESCRIPT_USAGE_UNKNOWN"
    );
    handoff.complete(analysis_budget::decode_receipt(receipt, grant)?)?;
    drop(handoff);
    if cancelled {
        budget.cancel();
        budget.checkpoint()?;
        bail!("CANCELLED")
    }
    budget.checkpoint()?;
    let value = match result.outcome {
        Some(p::result::Outcome::Analysis(value)) => value,
        Some(p::result::Outcome::Failure(failure)) => {
            let code = p::FailureCode::try_from(failure.code)?;
            bail!(DomainError::adapter(
                format!("TYPESCRIPT_{}", code.as_str_name()),
                failure.message
            ));
        }
        _ => bail!("TYPESCRIPT_ANALYSIS_REQUIRED"),
    };
    let archive = value.graph.context("TYPESCRIPT_GRAPH_REQUIRED")?;
    ensure!(safe_path(&archive.path), "TYPESCRIPT_GRAPH_PATH");
    let descriptor = codeana_contracts::graph_delivery::Descriptor {
        format: "GRA1".into(),
        path: selected.config.scratch.join(archive.path),
        count: archive.count,
        bytes: archive.bytes,
        sha256: archive.sha256,
    };
    let root = selected.config.scratch.clone();
    let limits = codeana_contracts::graph_delivery::OutputLimits {
        bytes: selected.config.result_bytes,
        facts: selected.config.fact_limit,
    };
    let (sender, mut records) = GraphStream::channel();
    let meter = budget.clone();
    let stopped = Arc::new(AtomicBool::new(false));
    let check_stopped = stopped.clone();
    let work = tokio::task::spawn_blocking(move || {
        codeana_graph_io::producer::produce_with_budget(
            &sender,
            limits.bytes,
            limits.facts,
            &|| {
                ensure!(!check_stopped.load(Ordering::Relaxed), "CANCELLED");
                meter.checkpoint()?;
                Ok(())
            },
            Some(&meter),
            |push| {
                codeana_graph_io::reader::read_descriptor(
                    &descriptor,
                    limits,
                    &root,
                    false,
                    Some(&meter),
                    || Ok(()),
                    push,
                    || {
                        ensure!(!check_stopped.load(Ordering::Relaxed), "CANCELLED");
                        meter.checkpoint()?;
                        Ok(())
                    },
                )?;
                Ok(())
            },
        )
    });
    eprintln!("codeana-typescript-phase=transport");
    let delivered = async {
        write_frame_with_budget(
            output,
            &envelope(
                attempt,
                Body::Begin(w::Begin {
                    mode: "full".into(),
                    baseline: a.baseline,
                    manifest: selected.manifest.id(),
                    profile: a.profile,
                    producer: typescript::PRODUCER.into(),
                    replacements: vec![],
                }),
            ),
            Some(budget),
        )
        .await?;
        let mut seq = 0;
        while let Some(item) = records.recv().await {
            match item.event {
                GraphStreamEvent::Batch(rows) => {
                    write_frame_with_budget(
                        output,
                        &envelope(attempt, Body::Chunk(w::Chunk { seq, rows })),
                        Some(budget),
                    )
                    .await?;
                    seq += 1
                }
                GraphStreamEvent::VerifiedEnd(_) => break,
                _ => bail!("UNEXPECTED_GRAPH_BEGIN"),
            }
        }
        let (count, checksum) = work.await??;
        write_frame_with_budget(
            output,
            &envelope(
                attempt,
                Body::End(w::End {
                    chunks: seq,
                    count,
                    checksum,
                    analysis_usage: Some(analysis_budget::receipt(
                        grant,
                        &budget.usage(),
                        "records",
                    )),
                }),
            ),
            Some(budget),
        )
        .await
    };
    let result = tokio::select! {result=delivered=>result,incoming=frames.next_cancel(input)=>{analysis::expect_cancel(incoming?,attempt)?;budget.cancel();budget.checkpoint()?;Err(anyhow::anyhow!("CANCELLED"))}};
    stopped.store(true, Ordering::Relaxed);
    result
}
