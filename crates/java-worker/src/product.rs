use crate::backend::{Backend, ProductEngine, verify_bundle};
use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::performance::{Metric, measure};
use codeana_contracts::{envelope, map, worker as w, worker::envelope::Body, write_frame};
use codeana_model::*;
use codeana_storage::derived_cache::{DerivedCacheLimits, DerivedCacheSession};

use std::{
    os::fd::FromRawFd,
    path::{Path, PathBuf},
};
use tokio::net::UnixStream;

pub async fn serve(library: &Path, fd: i32, backend: Backend) -> Result<()> {
    ensure!(
        fd >= 3,
        codeana_model::DomainError::new(codeana_model::ErrorCode::DedicatedFdRequired, "")
    );
    let bridge = {
        let diagnostics = codeana_contracts::performance::Report::new("worker-bootstrap");
        diagnostics
            .capture
            .sync(|| {
                measure(Metric::BundleVerify, 0, false, || {
                    verify_bundle(library, backend)
                })
            })?
            .canonicalize()?
    };
    let socket = unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) };
    socket.set_nonblocking(true)?;
    let mut ipc = UnixStream::from_std(socket)?;
    let mut config: Option<w::JavaConfig> = None;
    let mut native: Option<ProductEngine> = None;
    let mut derived_cache: Option<DerivedCacheSession> = None;
    let mut hello = false;
    let mut awaiting_ack = false;
    let mut frame_reader = codeana_contracts::FrameReader::default();
    loop {
        let frame = frame_reader.next(&mut ipc).await?;
        let attempt = frame.attempt;
        let mut cancelled = false;
        let outcome: Result<()> = async {
            match frame.body.context(codeana_model::DomainError::new(codeana_model::ErrorCode::EmptyFrame, ""))? {
                Body::Hello(h) => {
                    ensure!(!hello && h.protocol==WORKER_PROTOCOL_VERSION && h.fact_ir==VERSION, codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerVersionMismatch, ""));
                    hello = true;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Hello(w::Hello {protocol:WORKER_PROTOCOL_VERSION,fact_ir:VERSION,producer:"javac-java8-v1".into()}))).await?;
                }
                Body::Discover(d) => {
                    ensure!(d.rust.is_none() && d.python.is_none(), "JAVA_DISCOVERY_CONFIG");
                    ensure!(hello && config.is_none() && !awaiting_ack, codeana_model::DomainError::new(codeana_model::ErrorCode::DiscoverState, ""));
                    let c = d.java.context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaConfigRequired, ""))?;
                    ensure!(Path::new(&c.bridge).canonicalize()?==bridge, codeana_model::DomainError::new(codeana_model::ErrorCode::BridgeBundleMismatch, ""));
                    codeana_contracts::java::validate(&c)?;
                    if c.compact_inputs {
                        let manifest = d.manifest.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::ManifestRequired, ""))?;
                        let units: std::collections::BTreeMap<_,_> = manifest.files.iter().map(|u| (u.path.as_str(),u)).collect();
                        for input in &c.input_table {
                            let unit = input.unit.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaInputUnit, ""))?;
                            ensure!(units.get(unit.path.as_str()).is_some_and(|captured| *captured==unit), codeana_model::DomainError::new(codeana_model::ErrorCode::JavaUncapturedInputTable, ""));
                        }
                    }
                    let context = w::BuildContext {language:"java".into(),modules:c.contexts.iter().map(|m|m.id.clone()).collect(),source_version:"8".into(),classpath:vec![],producer:"javac-java8-v1".into(),capabilities:vec!["declarations".into(),"calls".into(),"references".into(),"relations".into(),format!("toolchain:{}",c.toolchain_digest),format!("adapter:{}",c.semantic_identity)]};
                    codeana_contracts::java::OutputLimits::from_config(&c)?;
                    config=Some(c);
                    write_frame(&mut ipc,&envelope(&attempt,Body::Context(context))).await?;
                }
                Body::Analyze(a) => {
                    ensure!(!awaiting_ack, codeana_model::DomainError::new(codeana_model::ErrorCode::BaselineNotSealed, ""));
                    let c=config.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::DiscoverRequired, ""))?;
                    let manifest: Manifest=map(a.manifest.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::ManifestRequired, ""))?)?;
                    let limits=codeana_contracts::java::OutputLimits::from_config(c)?;
                    derived_cache = if c.signature_cache {
                        let root = Path::new(&c.cache_root).with_extension("managed-v1");
                        match DerivedCacheSession::open(&root, Path::new(&c.scratch),
                            &["anatomist-signatures-v50-reloc2"], DerivedCacheLimits::default()) {
                            Ok(cache) => Some(cache),
                            Err(error) => { eprintln!("DERIVED_CACHE_UNAVAILABLE: {error}"); None }
                        }
                    } else { None };
                    let cache_root = derived_cache.as_ref().map(|cache| cache.namespace_path("anatomist-signatures-v50-reloc2")).transpose()?;
                    let request=codeana_contracts::engine_request::EngineRequest::new(c,&a,cache_root)?;
                    let heap=if c.native_heap_bytes==0 {256*1024*1024} else {c.native_heap_bytes};
                    let mut timings=crate::timings::PerformanceTimings::start(Path::new(&c.scratch))?;
                    let capture=timings.as_ref().map(|t|t.capture.clone()).unwrap_or_default();
                    capture.scope(async {
                    let mut evidence=crate::diagnostics::ReplayEvidence::start(&request,Path::new(&c.scratch),library)?;
                    let (mut incoming, mut outgoing) = tokio::io::split(&mut ipc);
                    let control=frame_reader.next(&mut incoming); tokio::pin!(control);
                    if native.is_none() {
                        let loaded = tokio::select! {
                            biased;
                            frame=&mut control => { ensure!(matches!(frame?.body,Some(Body::Cancel(_))), codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedDuringAnalysis, "")); cancelled=true; return Ok(()); },
                            loaded=ProductEngine::load(library,heap,backend,capture.clone()) => loaded?,
                        };
                        native=Some(loaded);
                    }
                    let lib=native.as_mut().context(codeana_model::DomainError::new(codeana_model::ErrorCode::NativeRequired, ""))?;
                    lib.set_capture(capture.clone());
                    let computation = async {
                        ensure!(lib.heap_bytes().await?==heap, codeana_model::DomainError::new(codeana_model::ErrorCode::WarmNativeHeapMismatch, ""));
                        lib.start(serde_json::to_vec(&request)?).await.context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaEngineStart, ""))?;
                        if let Some(e)=evidence.as_mut() {e.phase(backend.started_phase());}
                        if let Some(t)=timings.as_mut() {t.phase(backend.started_phase());}
                        let state = lib.ready().await?;
                        let output=lib.result_file(limits.bytes.min(64<<20),Path::new(&c.scratch)).await.context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaDescriptorRead, ""))?;
                        Ok::<_,anyhow::Error>((state,output))
                    };
                    enum Wake { Control(Box<Result<w::Envelope>>), Finished(Result<(crate::state::RunState,tempfile::NamedTempFile)>) }
                    let completed = tokio::select! {
                        biased;
                        frame=&mut control => Wake::Control(Box::new(frame)),
                        result=computation => Wake::Finished(result),
                    };
                    let (state,output)= match completed {
                        Wake::Control(frame) => { ensure!(matches!((*frame)?.body,Some(Body::Cancel(_))), codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedDuringAnalysis, "")); lib.cancel()?; cancelled=true; return Ok(()); },
                        Wake::Finished(result) => result?,
                    };
                    measure(Metric::DescriptorBytes, output.as_file().metadata()?.len(), true, || ());
                    if let Some(e)=evidence.as_mut() {e.phase(backend.finished_phase());}
                    if let Some(t)=timings.as_mut() {t.phase(backend.finished_phase());}
                    if state!=crate::state::RunState::Complete {
                        ensure!(output.as_file().metadata()?.len()<=64*1024, codeana_model::DomainError::new(codeana_model::ErrorCode::NativeFailureBudget, ""));
                        let error=std::fs::read(output.path())?;
                        bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaAnalysis, format!("{}", String::from_utf8_lossy(&error))));
                    }
                    if let Some(e)=evidence.as_ref() {e.sealed_descriptor(output.path())?;}
                    let (tx, mut rx) = tokio::sync::mpsc::channel(4);
                    let reader_manifest = manifest.clone();
                    let root = PathBuf::from(&c.scratch);
                    let cancelled = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
                    let reader_cancel = cancelled.clone();
                    let capture_reader = capture.clone();
                    let reader = tokio::task::spawn_blocking(move || {
                        capture_reader.sync(|| {
                            let mut encoder = crate::delivery::Encoder::new(tx.clone(), limits);
                            crate::result::read_owned_stream(std::io::BufReader::new(output.reopen().context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaDescriptorReopen, ""))?),
                                &reader_manifest, limits, &root,
                                |mode, replacements| tx.blocking_send(crate::delivery::Event::Begin {
                                    mode: mode.into(), replacements: replacements.to_vec(),
                                }).context(codeana_model::DomainError::new(codeana_model::ErrorCode::DeliveryCancelled, "")),
                                |fact| encoder.push(fact),
                                || { ensure!(!reader_cancel.load(std::sync::atomic::Ordering::Relaxed), codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")); Ok(()) })?;
                            encoder.finish()
                        })
                    });
                    let mut delivery_seq = 0u64;
                    let mut begun = false;
                    let mut verified = false;
                    let outcome: Result<()> = async {
                        loop {
                            let event = tokio::select! {
                                biased;
                                frame = &mut control => {
                                    ensure!(matches!(frame?.body, Some(Body::Cancel(_))), codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedDuringDelivery, ""));
                                    lib.cancel()?; cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
                                    anyhow::bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, ""));
                                },
                                event = rx.recv() => match event { Some(event) => event, None => break },
                            };
                            let body = match event {
                                crate::delivery::Event::Begin { mode, replacements } => {
                                    ensure!(!begun, codeana_model::DomainError::new(codeana_model::ErrorCode::DuplicateResultBegin, "")); begun = true;
                                    let r = AnalysisHeader { mode:mode.parse()?, baseline: a.baseline.clone(), manifest: manifest.id(),
                                        profile: a.profile.clone(), producer: "javac-java8-v1".into(), replacements };
                                    r.validate(&manifest).map_err(anyhow::Error::msg)?;
                                    Body::Begin(w::Begin { mode:r.mode.to_string(), baseline:r.baseline, manifest:r.manifest,
                                        profile:r.profile, producer:r.producer,
                                        replacements:r.replacements.iter().map(map).collect::<Result<_>>()? })
                                },
                                crate::delivery::Event::Chunk(facts) => {
                                    ensure!(begun && !verified, codeana_model::DomainError::new(codeana_model::ErrorCode::DeliveryState, ""));
                                    // Sequence is counted here as messages are sent.
                                    Body::Chunk(w::Chunk { seq: delivery_seq,  rows:facts })
                                },
                                crate::delivery::Event::End { chunks, count, checksum } => {
                                    ensure!(begun && !verified && chunks == delivery_seq, codeana_model::DomainError::new(codeana_model::ErrorCode::DeliveryState, ""));
                                    verified = true;
                                    if let Some(e)=evidence.as_mut() {e.phase("archive_validated_and_deduplicated");}
                                    if let Some(t)=timings.as_mut() {t.phase("archive_validated_and_deduplicated");}
                                    Body::End(w::End { chunks, count, checksum })
                                },
                            };
                            let is_chunk = matches!(body, Body::Chunk(_));
                            let frame = envelope(&attempt, body);
                            tokio::select! {
                                biased;
                                request = &mut control => {
                                    ensure!(matches!(request?.body, Some(Body::Cancel(_))), codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedDuringDelivery, ""));
                                    lib.cancel()?; anyhow::bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, ""));
                                },
                                written = write_frame(&mut outgoing, &frame) => written?,
                            }
                            if is_chunk { delivery_seq += 1; }
                            if verified { break; }
                        }
                        ensure!(verified, codeana_model::DomainError::new(codeana_model::ErrorCode::UnverifiedNativeDelivery, ""));
                        Ok(())
                    }.await;
                    cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
                    drop(rx); drop(outgoing);
                    let read_result = reader.await?;
                    read_result?; outcome?;
                    awaiting_ack=true;
                    if let Some(e)=evidence.as_mut() {e.phase("result_end_sent");}
                    if let Some(t)=timings.as_mut() {t.phase("result_end_sent");}
                    Ok(())
                    }).await?;
                }
                Body::Ack(ack)=> {
                    ensure!(awaiting_ack && !ack.revision.is_empty(), codeana_model::DomainError::new(codeana_model::ErrorCode::PublishAckState, ""));
                    let lib=native.as_mut().context(codeana_model::DomainError::new(codeana_model::ErrorCode::NativeRequired, ""))?;
                    lib.ack().await?;
                    lib.finish().await?;
                    if let Some(mut cache) = derived_cache.take() {
                        match cache.commit() {
                            Ok(stats) => eprintln!("DERIVED_CACHE: {}", serde_json::to_string(&stats)?),
                            Err(error) => eprintln!("DERIVED_CACHE_WRITE_UNAVAILABLE: {error}"),
                        }
                    }
                    config=None;
                    awaiting_ack=false;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Acked(w::Acked{revision:ack.revision}))).await?;
                }
                Body::Cancel(_)=> {
                    if awaiting_ack && let Some(lib)=&native { lib.cancel()?; }
                    cancelled = true;
                    return Ok(());
                }
                _=>bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::UnexpectedWorkerMessage, ""))
            }
            Ok(())
        }.await;
        if let Err(error) = outcome {
            let _ = write_frame(
                &mut ipc,
                &envelope(
                    &attempt,
                    Body::Failure(w::Failure {
                        code: "JAVA_WORKER_FAILED".into(),
                        message: format!("{error:#}"),
                    }),
                ),
            )
            .await;
            if let Some(lib) = &mut native {
                let _ = lib.shutdown().await;
            }
            return Err(error);
        }
        if cancelled {
            if let Some(lib) = &mut native {
                let _ = lib.shutdown().await;
            }
            return Ok(());
        }
    }
}
