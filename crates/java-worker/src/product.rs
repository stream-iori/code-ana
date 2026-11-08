use crate::backend::{Backend, ProductEngine, verify_bundle};
use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::performance::{Metric, measure};
use codeana_contracts::{
    envelope, map, worker as w, worker::envelope::Body, write_frame, write_frame_with_budget,
};
use codeana_derived_cache::{DerivedCacheLimits, DerivedCacheSession};
use codeana_model::*;

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
    let mut task_meter = AnalysisTaskMeter::default();
    let mut active_phase = String::from("input_decode");
    let mut ffi_usage_known = true;
    let mut active_analysis: Option<(Option<AnalysisGrant>, AnalysisContext)> = None;
    let mut hello = false;
    let mut session_attempt: Option<String> = None;
    let mut awaiting_ack = false;
    let mut frame_reader = codeana_contracts::FrameReader::default();
    loop {
        let read = if let Some((_, budget)) = task_meter.active() {
            frame_reader.next_with_budget(&mut ipc, &budget).await
        } else {
            frame_reader.next_bootstrap(&mut ipc).await
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
                            frame_reader
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
        let mut cancelled = false;
        let outcome: Result<()> = async {
            match &frame.body {
                Some(Body::Hello(_) | Body::Discover(_) | Body::Analyze(_))=>session_attempt=Some(attempt.clone()),
                _=>ensure!(session_attempt.as_ref()==Some(&attempt),"ATTEMPT_MISMATCH"),
            }
            match frame.body.context(codeana_model::DomainError::new(codeana_model::ErrorCode::EmptyFrame, ""))? {
                Body::Hello(h) => {
                    ensure!(task_meter.active().is_none() && config.is_none() && !awaiting_ack && h.protocol==WORKER_PROTOCOL_VERSION && h.fact_ir==VERSION, codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerVersionMismatch, ""));
                    let initialized=codeana_contracts::analysis_budget::begin_hello(&mut task_meter,&h,AnalysisLanguage::Java);
                    active_analysis=task_meter.active(); active_phase="discover".into(); ffi_usage_known=true;
                    let (grant,budget)=initialized?;
                    hello = true;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Hello(w::Hello {protocol:WORKER_PROTOCOL_VERSION,fact_ir:VERSION,producer:"javac-v3".into(),analysis_usage:grant.as_ref().map(|g|codeana_contracts::analysis_budget::receipt(g,&budget.usage(),"discover")),..Default::default()}))).await?;
                }
                Body::Discover(d) => {
                    let initialized=codeana_contracts::analysis_budget::begin_discovery(&mut task_meter,&d,AnalysisLanguage::Java);
                    active_analysis=task_meter.active();
                    active_phase="discover".into();
                    let (grant,budget)=initialized?;
                    ensure!(d.rust.is_none() && d.python.is_none(), "JAVA_DISCOVERY_CONFIG");
                    ensure!(hello && config.is_none() && !awaiting_ack, codeana_model::DomainError::new(codeana_model::ErrorCode::DiscoverState, ""));
                    let c = d.java.context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaConfigRequired, ""))?;
                    codeana_contracts::java::validate(&c)?;
                    let expected_bridge = if c.target_java == 8 { bridge.clone() } else { bridge.with_file_name("compiler-bridge17.jar") };
                    ensure!(Path::new(&c.bridge).canonicalize()?==expected_bridge, codeana_model::DomainError::new(codeana_model::ErrorCode::BridgeBundleMismatch, ""));
                    codeana_contracts::java::validate(&c)?;
                    if c.compact_inputs {
                        let manifest = d.manifest.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::ManifestRequired, ""))?;
                        let units: std::collections::BTreeMap<_,_> = manifest.files.iter().map(|u| (u.path.as_str(),u)).collect();
                        for input in &c.input_table {
                            let unit = input.unit.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaInputUnit, ""))?;
                            ensure!(units.get(unit.path.as_str()).is_some_and(|captured| *captured==unit), codeana_model::DomainError::new(codeana_model::ErrorCode::JavaUncapturedInputTable, ""));
                        }
                    }
                    let context = w::BuildContext {analysis_usage:grant.as_ref().map(|g|codeana_contracts::analysis_budget::receipt(g,&budget.usage(),"discover")),language:"java".into(),modules:c.contexts.iter().map(|m|m.id.clone()).collect(),source_version:c.target_java.to_string(),classpath:vec![],producer:"javac-v3".into(),capabilities:vec!["analysis-resources-v1".into(),"declarations".into(),"calls".into(),"references".into(),"relations".into(),format!("toolchain:{}",c.toolchain_digest),format!("target-java:{}",c.target_java),format!("java-version:{}",c.actual_java_version),format!("toolchain-aliases:{}",c.toolchain_aliases.join(",")),format!("java-home:{}",c.java_home),format!("compiler-bridge:{}",codeana_model::hash_file(Path::new(&c.bridge))?),format!("adapter:{}",c.semantic_identity)]};
                    codeana_contracts::java::OutputLimits::from_config(&c)?;
                    config=Some(c);
                    write_frame_with_budget(&mut ipc,&envelope(&attempt,Body::Context(context)),Some(&budget)).await?;
                }
                Body::Analyze(a) => {
                    ensure!(!awaiting_ack, codeana_model::DomainError::new(codeana_model::ErrorCode::BaselineNotSealed, ""));
                    let c=config.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::DiscoverRequired, ""))?;
                    let manifest: Manifest=map(a.manifest.as_ref().context(codeana_model::DomainError::new(codeana_model::ErrorCode::ManifestRequired, ""))?)?;
                    let limits=codeana_contracts::java::OutputLimits::from_config(c)?;
                    let grant=Some(codeana_contracts::analysis_budget::required_grant(a.analysis_grant.as_ref(),AnalysisLanguage::Java)?);
                    let budget=task_meter.resume(&grant,a.deadline_ms.ok_or(AnalysisFailure::InvalidUsage)?).map_err(DomainError::analysis)?;
                    active_analysis=Some((grant.clone(),budget.clone()));
                    ffi_usage_known=true;
                    active_phase="backend".into();
                    derived_cache = if c.signature_cache {
                        let root = Path::new(&c.cache_root).with_extension("managed-v1");
                        match DerivedCacheSession::open(&root, Path::new(&c.scratch),
                            &["anatomist-signatures-v50-reloc2"], DerivedCacheLimits::default()) {
                            Ok(cache) => Some(cache),
                            Err(error) => { eprintln!("DERIVED_CACHE_UNAVAILABLE: {error}"); None }
                        }
                    } else { None };
                    let cache_root = derived_cache.as_ref().map(|cache| cache.namespace_path("anatomist-signatures-v50-reloc2")).transpose()?;
                    // Admit DTO clones and JSON baseline projection before constructing them.
                    let dto_bytes=prost::Message::encoded_len(c).saturating_add(prost::Message::encoded_len(&a)) as u64;
                    budget.charge(1,0,dto_bytes.saturating_mul(32).saturating_add(8192)).map_err(DomainError::analysis)?;
                    codeana_contracts::json_budget::admit_owned_copy(c,&budget)?;
                    let mut request=codeana_contracts::engine_request::EngineRequest::new(c,&a,cache_root,&budget)?;
                    let heap=if c.native_heap_bytes==0 {256*1024*1024} else {c.native_heap_bytes};
                    let mut timings=crate::timings::PerformanceTimings::start(Path::new(&c.scratch))?;
                    let capture=timings.as_ref().map(|t|t.capture.clone()).unwrap_or_default();
                    capture.scope(async {
                    let mut evidence=None;
                    let (mut incoming, mut outgoing) = tokio::io::split(&mut ipc);
                    let control=frame_reader.next_cancel(&mut incoming); tokio::pin!(control);
                    if native.is_none() {
                        let loaded = tokio::select! {
                            biased;
                            frame=&mut control => { codeana_contracts::analysis_budget::expect_cancel(frame?,&attempt)?; cancelled=true; return Ok(()); },
                            loaded=ProductEngine::load(library,heap,backend,capture.clone()) => loaded?,
                        };
                        native=Some(loaded);
                    }
                    let lib=native.as_mut().context(codeana_model::DomainError::new(codeana_model::ErrorCode::NativeRequired, ""))?;
                    lib.set_capture(capture.clone());
                    let computation = async {
                        ensure!(lib.heap_bytes().await?==heap, codeana_model::DomainError::new(codeana_model::ErrorCode::WarmNativeHeapMismatch, ""));
                        // Reserve serialization before allocating the request buffer. The seed
                        // resumes this same worker account inside the Java implementation.
                        let mut counter=ByteCounter(0);
                        serde_json::to_writer(&mut counter,&request)?;
                        // Prepay the Rust/FFI request copies, Java request tree and fixed
                        // cancellation/receipt-probe lane before forwarding the seed.
                        // No Rust task debit may race Java's cumulative account.
                        budget.charge(1,0,counter.0.saturating_mul(32).saturating_add(64*1024)).map_err(DomainError::analysis)?;
                        codeana_contracts::json_budget::admit_serialization(&request,&budget,16,1000000)?;
                        request.analysis_usage=Some(budget.usage());
                        request.deadline_ms=budget.remaining_millis().map_err(DomainError::analysis)?.unwrap_or(0);
                        evidence=crate::diagnostics::ReplayEvidence::start(&request,Path::new(&c.scratch),library)?;
                        budget.checkpoint().map_err(DomainError::analysis)?;
                        ffi_usage_known=false;
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
                        Wake::Control(frame) => { codeana_contracts::analysis_budget::expect_cancel((*frame)?,&attempt)?; lib.cancel()?; cancelled=true; return Ok(()); },
                        Wake::Finished(result) => result?,
                    };
                    measure(Metric::DescriptorBytes, output.as_file().metadata()?.len(), true, || ());
                    if let Some(e)=evidence.as_mut() {e.phase(backend.finished_phase());}
                    if let Some(t)=timings.as_mut() {t.phase(backend.finished_phase());}
                    if let Some(grant)=&grant {
                        if let Some(receipt)=crate::result::analysis_receipt(std::io::BufReader::new(output.reopen()?))? {
                            active_phase=receipt.phase.clone();
                            receipt.reconcile(grant,&budget)?;
                            ffi_usage_known=true;
                            if let Some(failure)=budget.usage().failure { return Err(DomainError::analysis(failure).into()); }
                        } else {
                            ensure!(state!=crate::state::RunState::Complete,"ANALYSIS_USAGE_REQUIRED");
                        }
                    }
                    if state!=crate::state::RunState::Complete {
                        ensure!(output.as_file().metadata()?.len()<=64*1024, codeana_model::DomainError::new(codeana_model::ErrorCode::NativeFailureBudget, ""));
                        let error=std::fs::read(output.path())?;
                        bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaAnalysis, format!("{}", String::from_utf8_lossy(&error))));
                    }
                    active_phase="archive".into();
                    if let Some(e)=evidence.as_ref() {e.sealed_descriptor(output.path())?;}
                    let (tx, mut rx) = tokio::sync::mpsc::channel(4);
                    let root = PathBuf::from(&c.scratch);
                    let cancelled = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
                    let reader_cancel = cancelled.clone();
                    let capture_reader = capture.clone();
                    let reader_budget=budget.clone();
                    let reader = tokio::task::spawn_blocking(move || {
                        capture_reader.sync(|| {
                            let mut encoder = crate::delivery::Encoder::with_budget(tx.clone(), limits, reader_budget.clone())?;
                            reader_budget.charge(1,0,output.as_file().metadata()?.len().saturating_mul(2)).map_err(DomainError::analysis)?;
                            crate::result::read_owned_stream_with_budget(std::io::BufReader::new(output.reopen().context(codeana_model::DomainError::new(codeana_model::ErrorCode::JavaDescriptorReopen, ""))?),
                                limits, &root, &reader_budget,
                                |mode, replacements| tx.blocking_send(crate::delivery::Event::Begin {
                                    mode: mode.into(), replacements: replacements.to_vec(),
                                }).context(codeana_model::DomainError::new(codeana_model::ErrorCode::DeliveryCancelled, "")),
                                |fact| encoder.push(fact),
                                || { reader_budget.checkpoint().map_err(DomainError::analysis)?; ensure!(!reader_cancel.load(std::sync::atomic::Ordering::Relaxed), codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")); Ok(()) })?;
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
                                    codeana_contracts::analysis_budget::expect_cancel(frame?,&attempt)?;
                                    lib.cancel()?; cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
                                    anyhow::bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, ""));
                                },
                                event = rx.recv() => match event { Some(event) => event, None => break },
                            };
                            let body = match event {
                                crate::delivery::Event::Begin { mode, replacements } => {
                                    ensure!(!begun, codeana_model::DomainError::new(codeana_model::ErrorCode::DuplicateResultBegin, "")); begun = true;
                                    let r = AnalysisHeader { mode:mode.parse()?, baseline: a.baseline.clone(), manifest: manifest.id(),
                                        profile: a.profile.clone(), producer: "javac-v3".into(), replacements };
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
                                    Body::End(w::End {
analysis_usage: grant.as_ref().map(|g| codeana_contracts::analysis_budget::receipt(g,&budget.usage(),"archive")),
 chunks, count, checksum })
                                },
                            };
                            let is_chunk = matches!(body, Body::Chunk(_));
                            let frame = envelope(&attempt, body);
                            tokio::select! {
                                biased;
                                request = &mut control => {
                                    codeana_contracts::analysis_budget::expect_cancel(request?,&attempt)?;
                                    lib.cancel()?; anyhow::bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, ""));
                                },
                                written = write_frame_with_budget(&mut outgoing, &frame, Some(&budget)) => written?,
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
                    task_meter=AnalysisTaskMeter::default();
                    active_analysis=None;
                    awaiting_ack=false;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Acked(w::Acked{revision:ack.revision}))).await?;
                }
                Body::Reuse(_) => {
                    ensure!(config.is_some() && !awaiting_ack,"ANALYSIS_REUSE_STATE");
                    let (grant,budget)=active_analysis.as_ref().context("DISCOVER_REQUIRED")?;
                    budget.checkpoint()?;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Reused(w::Reused {
                        analysis_usage:grant.as_ref().map(|g|codeana_contracts::analysis_budget::receipt(g,&budget.usage(),"reused")),
                    }))).await?;
                    config=None;active_analysis=None;task_meter=AnalysisTaskMeter::default();
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
                        analysis_usage: active_analysis.as_ref().and_then(|(grant, budget)| {
                            grant.as_ref().map(|g| {
                                let mut receipt = codeana_contracts::analysis_budget::receipt(
                                    g,
                                    &budget.usage(),
                                    &active_phase,
                                );
                                receipt.usage_known = Some(ffi_usage_known);
                                receipt
                            })
                        }),
                        code: active_analysis
                            .as_ref()
                            .and_then(|(_, budget)| budget.usage().failure)
                            .map(|f| DomainError::analysis(f).code.to_string())
                            .unwrap_or_else(|| "JAVA_WORKER_FAILED".into()),
                        message: codeana_contracts::analysis_budget::bounded_error(&error),
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
            if let Some((grant, budget)) = &active_analysis {
                let failure = codeana_contracts::analysis_budget::cancelled_receipt(
                    grant,
                    budget,
                    &active_phase,
                    ffi_usage_known,
                );
                let _ = tokio::time::timeout(
                    std::time::Duration::from_millis(50),
                    write_frame(&mut ipc, &envelope(&attempt, Body::Failure(failure))),
                )
                .await;
            }
            if let Some(lib) = &mut native {
                let _ = lib.shutdown().await;
            }
            return Ok(());
        }
    }
}

struct ByteCounter(u64);
impl std::io::Write for ByteCounter {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0 = self.0.saturating_add(bytes.len() as u64);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
