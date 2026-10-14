//! Explicit fixture-only worker. It never parses or guesses facts from Java text.
use anyhow::{Result, bail, ensure};
use clap::Parser;
use codeana_contracts::{
    envelope, map, read_frame, worker as w, worker::envelope::Body, write_frame,
};
use codeana_model::*;
use std::{collections::BTreeSet, os::fd::FromRawFd, path::PathBuf};
use tokio::net::UnixStream;
#[derive(Parser)]
struct Args {
    #[arg(long)]
    fd: i32,
    #[arg(long)]
    fixtures: PathBuf,
}
#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    ensure!(
        args.fd >= 3,
        codeana_model::DomainError::new(codeana_model::ErrorCode::DedicatedFdRequired, "")
    );
    let bytes = std::fs::read(args.fixtures)?;
    ensure!(
        bytes.len() <= MAX_RESULT,
        codeana_model::DomainError::new(codeana_model::ErrorCode::FixtureBudget, "")
    );
    let fixture: Fixture = serde_json::from_slice(&bytes)?;
    ensure!(
        fixture.schema_version == 1,
        codeana_model::DomainError::new(codeana_model::ErrorCode::FixtureSchema, "")
    );
    // SAFETY: daemon exclusively transfers this inherited socket descriptor to the worker.
    let socket = unsafe { std::os::unix::net::UnixStream::from_raw_fd(args.fd) };
    socket.set_nonblocking(true)?;
    let mut ipc = UnixStream::from_std(socket)?;
    let mut selected: Option<FixtureState> = None;
    loop {
        let frame = read_frame(&mut ipc).await?;
        let attempt = frame.attempt;
        match frame.body {
            Some(Body::Hello(h)) => {
                ensure!(
                    h.protocol == WORKER_PROTOCOL_VERSION && h.fact_ir == VERSION,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::UnsupportedProtocol,
                        ""
                    )
                );
                write_frame(
                    &mut ipc,
                    &envelope(
                        &attempt,
                        Body::Hello(w::Hello {
                            protocol: WORKER_PROTOCOL_VERSION,
                            fact_ir: VERSION,
                            producer: "mock-fixture-v1".into(),
                        }),
                    ),
                )
                .await?;
            }
            Some(Body::Discover(d)) => {
                let manifest: Manifest = map(&d.manifest.ok_or_else(|| {
                    anyhow::anyhow!(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::ManifestRequired,
                        ""
                    ))
                })?)?;
                selected = fixture
                    .states
                    .iter()
                    .find(|s| s.content_digest == manifest.content_digest())
                    .cloned();
                if let Some(s) = &selected {
                    if let Some(advice) = &s.advice {
                        write_frame(&mut ipc, &envelope(&attempt, Body::Advice(map(advice)?)))
                            .await?;
                        continue;
                    }
                    ensure!(
                        s.context.producer.starts_with("mock-"),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::MockProvenanceRequired,
                            ""
                        )
                    );
                    write_frame(
                        &mut ipc,
                        &envelope(&attempt, Body::Context(map(&s.context)?)),
                    )
                    .await?;
                } else {
                    write_frame(
                        &mut ipc,
                        &envelope(
                            &attempt,
                            Body::Failure(w::Failure {
                                code: "UNKNOWN_FIXTURE_INPUT".into(),
                                message: manifest.content_digest(),
                            }),
                        ),
                    )
                    .await?;
                }
            }
            Some(Body::Analyze(a)) => {
                let s = selected.as_ref().ok_or_else(|| {
                    anyhow::anyhow!(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::DiscoverRequired,
                        ""
                    ))
                })?;
                let manifest: Manifest = map(&a.manifest.ok_or_else(|| {
                    anyhow::anyhow!(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::ManifestRequired,
                        ""
                    ))
                })?)?;
                ensure!(
                    manifest.content_digest() == s.content_digest,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::InputDrift, "")
                );
                if s.behavior == "crash" {
                    std::process::exit(17);
                }
                if s.behavior == "hang" || s.delay_ms > 0 {
                    let duration = if s.behavior == "hang" {
                        3600000
                    } else {
                        s.delay_ms
                    };
                    tokio::select! {_ = tokio::time::sleep(std::time::Duration::from_millis(duration))=>{},v=read_frame(&mut ipc)=>{if matches!(v?.body,Some(Body::Cancel(_))){return Ok(());}bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedWhileAnalyzing, ""));}}
                }
                if s.behavior == "replan" && !a.full {
                    write_frame(
                        &mut ipc,
                        &envelope(
                            &attempt,
                            Body::Replan(w::Replan {
                                reason: "fixture requires full replacement".into(),
                            }),
                        ),
                    )
                    .await?;
                    continue;
                }
                let summary: BaselineSummary = if a.baseline_summary_json.is_empty() {
                    BaselineSummary::default()
                } else {
                    serde_json::from_slice(&a.baseline_summary_json)?
                };
                let mut replacements: BTreeSet<Owner> = manifest
                    .files
                    .iter()
                    .map(|f| Owner {
                        world: f.world.clone(),
                        producer: s.context.producer.clone(),
                        kind: "source".into(),
                        id: f.path.clone(),
                    })
                    .collect();
                {
                    for f in &summary.files {
                        replacements.insert(Owner {
                            world: f.world.clone(),
                            producer: s.context.producer.clone(),
                            kind: "source".into(),
                            id: f.path.clone(),
                        });
                    }
                }
                let begin = w::Begin {
                    mode: if a.full { "full" } else { "delta" }.into(),
                    baseline: a.baseline,
                    manifest: manifest.id(),
                    profile: a.profile,
                    producer: s.context.producer.clone(),
                    replacements: replacements.iter().map(map).collect::<Result<_>>()?,
                };
                write_frame(&mut ipc, &envelope(&attempt, Body::Begin(begin))).await?;
                if s.behavior == "oversize" {
                    use tokio::io::AsyncWriteExt;
                    ipc.write_u32((MAX_FRAME + 1) as u32).await?;
                    return Ok(());
                }
                let graph_rows = codeana_contracts::fixture_graph::rows(&manifest, &s.facts);
                let mut spool = codeana_contracts::graph_archive::Builder::new(
                    None,
                    MAX_RESULT as u64,
                    MAX_FACTS as u64,
                )?;
                for row in &graph_rows {
                    spool.push(row)?;
                }
                let archive = spool.seal()?;
                for (i, chunk) in graph_rows.chunks(64).enumerate() {
                    write_frame(
                        &mut ipc,
                        &envelope(
                            &attempt,
                            Body::Chunk(w::Chunk {
                                seq: if s.behavior == "wrong-sequence" {
                                    99
                                } else {
                                    i as u64
                                },
                                rows: chunk.to_vec(),
                            }),
                        ),
                    )
                    .await?;
                }
                if s.behavior == "missing-end" {
                    return Ok(());
                }
                let checksum = if s.behavior == "bad-checksum" {
                    "invalid".into()
                } else {
                    archive.checksum.clone()
                };
                write_frame(
                    &mut ipc,
                    &envelope(
                        &attempt,
                        Body::End(w::End {
                            chunks: graph_rows.len().div_ceil(64) as u64,
                            count: graph_rows.len() as u64,
                            checksum,
                        }),
                    ),
                )
                .await?;
            }
            Some(Body::Ack(_)) | Some(Body::Cancel(_)) => return Ok(()),
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnexpectedWorkerMessage,
                ""
            )),
        }
    }
}
