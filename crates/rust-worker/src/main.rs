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
    loop {
        let frame = frames.next(&mut input).await?;
        let attempt = frame.attempt;
        match frame.body {
            Some(Body::Hello(h)) => {
                ensure!(
                    h.protocol == WORKER_PROTOCOL_VERSION && h.fact_ir == VERSION,
                    "UNSUPPORTED_PROTOCOL"
                );
                write_frame(
                    &mut ipc,
                    &envelope(
                        &attempt,
                        Body::Hello(w::Hello {
                            protocol: WORKER_PROTOCOL_VERSION,
                            fact_ir: VERSION,
                            producer: "rust-core".into(),
                        }),
                    ),
                )
                .await?;
            }
            Some(Body::Discover(d)) => {
                eprintln!("codeana-rust-phase=inputs");
                ensure!(d.java.is_none(), "RUST_DISCOVERY_CONFIG");
                let descriptor = d.rust.context("RUST_DISCOVERY_REQUIRED")?;
                let config =
                    tokio::task::spawn_blocking(move || -> Result<rust::AnalysisConfig> {
                        ensure!(descriptor.bytes <= 32 << 20, "RUST_INPUT_BUDGET");
                        ensure!(
                            std::fs::metadata(&descriptor.input)?.len() == descriptor.bytes,
                            "RUST_INPUT_CHANGED"
                        );
                        let bytes = std::fs::read(&descriptor.input)?;
                        ensure!(
                            bytes.len() as u64 == descriptor.bytes
                                && hash(&bytes) == descriptor.sha256,
                            "RUST_INPUT_CHANGED"
                        );
                        let config: rust::AnalysisConfig = serde_json::from_slice(&bytes)?;
                        config.inputs.validate().map_err(anyhow::Error::msg)?;
                        Ok(config)
                    })
                    .await??;
                let manifest: Manifest = map(&d.manifest.context("MANIFEST_REQUIRED")?)?;
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
                        "declarations".into(),
                        "references".into(),
                        "calls".into(),
                        codeana_rust_adaptor::ADAPTER_VERSION.into(),
                    ],
                };
                write_frame(&mut ipc, &envelope(&attempt, Body::Context(map(&context)?))).await?;
                selected = Some((config, manifest));
            }
            Some(Body::Analyze(a)) => {
                let (config, captured) = selected.as_ref().context("DISCOVER_REQUIRED")?;
                let manifest: Manifest = map(a.manifest.as_ref().context("MANIFEST_REQUIRED")?)?;
                ensure!(manifest.id() == captured.id(), "INPUT_DRIFT");
                let result = tokio::select! {
                    biased;
                    incoming=frames.next(&mut input)=>{
                        ensure!(matches!(incoming?.body,Some(Body::Cancel(_))),"UNEXPECTED_WHILE_ANALYZING");
                        return Ok(());
                    }
                    result=analyze_stream(config.clone(),a,manifest,attempt.clone(),&mut ipc)=>result,
                };
                if let Err(error) = result {
                    write_frame(
                        &mut ipc,
                        &envelope(
                            &attempt,
                            Body::Failure(w::Failure {
                                code: "RUST_ANALYSIS_FAILED".into(),
                                message: format!("{error:#}"),
                            }),
                        ),
                    )
                    .await?;
                    return Ok(());
                }
            }

            Some(Body::Ack(a)) => {
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
async fn analyze_stream(
    mut config: rust::AnalysisConfig,
    a: w::Analyze,
    manifest: Manifest,
    attempt: String,
    writer: &mut tokio::net::unix::OwnedWriteHalf,
) -> Result<()> {
    use codeana_contracts::{
        graph_archive::{MAGIC, update_digest},
        stream::{BATCH_BYTES, BATCH_ROWS, GraphStream, GraphStreamEvent},
    };
    use prost::Message;
    use sha2::{Digest, Sha256};
    let stopped = Arc::new(AtomicBool::new(false));
    let _stop = StopOnDrop(stopped.clone());
    let (header_tx, header_rx) = tokio::sync::oneshot::channel();
    let (sender, mut records) = GraphStream::channel();
    let metrics = sender.metrics.clone();
    let work = tokio::task::spawn_blocking(move || -> Result<(u64, String)> {
        let check = || {
            ensure!(!stopped.load(Ordering::Relaxed), "CANCELLED");
            Ok(())
        };
        let baseline: BaselineSummary = if a.full || a.baseline_summary_json.is_empty() {
            BaselineSummary::default()
        } else {
            match serde_json::from_slice::<BaselineInput>(&a.baseline_summary_json)? {
                BaselineInput::Inline(summary) => summary,
                BaselineInput::File(file) => {
                    ensure!(file.bytes <= 64 << 20, "BASELINE_BUDGET");
                    let bytes = std::fs::read(file.path)?;
                    ensure!(
                        bytes.len() as u64 == file.bytes && hash(&bytes) == file.sha256,
                        "BASELINE_CHANGED"
                    );
                    serde_json::from_slice(&bytes)?
                }
            }
        };
        config.baseline = (!a.full).then_some(baseline.rust_states);
        let analysis = codeana_rust_adaptor::analyze(&config, &check)?;
        let started = std::time::Instant::now();
        eprintln!("codeana-rust-phase=incremental");
        let prepared =
            codeana_rust_adaptor::records::prepare(&config, &analysis, config.baseline.as_deref());
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
        let mut digest = Sha256::new();
        digest.update(MAGIC);
        let mut count = 0;
        let mut bytes = 4u64;
        let mut batch = Vec::new();
        let mut batch_size = 0;
        codeana_rust_adaptor::records::emit(&config, &analysis, &prepared, |row| {
            check()?;
            let encoded = row.encode_to_vec();
            ensure!(
                !encoded.is_empty() && encoded.len() + 128 <= MAX_FRAME,
                "GRAPH_RECORD_BUDGET"
            );
            ensure!(
                count < config.fact_limit
                    && bytes + encoded.len() as u64 + 4 <= config.result_bytes,
                "GRAPH_ARCHIVE_BUDGET"
            );
            if !batch.is_empty()
                && (batch.len() == BATCH_ROWS || batch_size + encoded.len() > BATCH_BYTES)
            {
                sender
                    .blocking_send(GraphStreamEvent::Batch(std::mem::take(&mut batch)))
                    .map_err(|_| anyhow::anyhow!("CANDIDATE_STREAM_CLOSED"))?;
                batch_size = 0;
            }
            update_digest(&mut digest, &encoded);
            count += 1;
            bytes += encoded.len() as u64 + 4;
            batch_size += encoded.len();
            batch.push(row);
            Ok(())
        })?;
        if !batch.is_empty() {
            sender
                .blocking_send(GraphStreamEvent::Batch(batch))
                .map_err(|_| anyhow::anyhow!("CANDIDATE_STREAM_CLOSED"))?;
        }
        sender
            .blocking_send(GraphStreamEvent::VerifiedEnd)
            .map_err(|_| anyhow::anyhow!("CANDIDATE_STREAM_CLOSED"))?;
        eprintln!(
            "codeana-rust-timing records_and_backpressure_ms={}",
            started.elapsed().as_millis()
        );
        Ok((count, format!("{:x}", digest.finalize())))
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
    write_frame(writer, &envelope(&attempt, Body::Begin(header))).await?;
    let mut seq = 0;
    while let Some(item) = records.recv().await {
        match item.event {
            GraphStreamEvent::Batch(rows) => {
                write_frame(
                    writer,
                    &envelope(&attempt, Body::Chunk(w::Chunk { seq, rows })),
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
            &attempt,
            Body::End(w::End {
                chunks: seq,
                count,
                checksum,
            }),
        ),
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
    async fn partial_control_frame_cancels_with_unconsumed_output() -> Result<()> {
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
                "discover",
                Body::Discover(w::Discover {
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
                    ..Default::default()
                }),
            ),
        )
        .await?;
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
