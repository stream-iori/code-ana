use crate::backend::{Backend, ProductEngine, verify_bundle};
use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::performance::{Metric, measure};
use codeana_contracts::{convert, envelope, worker as w, worker::envelope::Body, write_frame};
use codeana_model::*;
use codeana_storage::derived_cache::{DerivedCacheLimits, DerivedCacheSession};
use serde_json::json;
use std::{
    os::fd::FromRawFd,
    path::{Path, PathBuf},
};
use tokio::net::UnixStream;

pub async fn serve(library: &Path, fd: i32, backend: Backend) -> Result<()> {
    ensure!(fd >= 3, "DEDICATED_FD_REQUIRED");
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
            match frame.body.context("EMPTY_FRAME")? {
                Body::Hello(h) => {
                    ensure!(!hello && h.protocol==WORKER_PROTOCOL_VERSION && h.fact_ir==VERSION,"WORKER_VERSION_MISMATCH");
                    hello = true;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Hello(w::Hello {protocol:WORKER_PROTOCOL_VERSION,fact_ir:VERSION,producer:"javac-java8-v1".into()}))).await?;
                }
                Body::Discover(d) => {
                    ensure!(hello && config.is_none() && !awaiting_ack,"DISCOVER_STATE");
                    let c = d.java.context("JAVA_CONFIG_REQUIRED")?;
                    ensure!(Path::new(&c.bridge).canonicalize()?==bridge,"BRIDGE_BUNDLE_MISMATCH");
                    codeana_contracts::java::validate(&c)?;
                    if c.compact_inputs {
                        let manifest = d.manifest.as_ref().context("MANIFEST_REQUIRED")?;
                        let units: std::collections::BTreeMap<_,_> = manifest.files.iter().map(|u| (u.path.as_str(),u)).collect();
                        for input in &c.input_table {
                            let unit = input.unit.as_ref().context("JAVA_INPUT_UNIT")?;
                            ensure!(units.get(unit.path.as_str()).is_some_and(|captured| *captured==unit),"JAVA_UNCAPTURED_INPUT_TABLE");
                        }
                    }
                    let context = w::BuildContext {language:"java".into(),modules:c.contexts.iter().map(|m|m.id.clone()).collect(),source_version:"8".into(),classpath:vec![],producer:"javac-java8-v1".into(),capabilities:vec!["declarations".into(),"calls".into(),"references".into(),"relations".into(),format!("toolchain:{}",c.toolchain_digest),format!("adapter:{}",c.semantic_identity)]};
                    codeana_contracts::java::OutputLimits::from_config(&c)?;
                    config=Some(c);
                    write_frame(&mut ipc,&envelope(&attempt,Body::Context(context))).await?;
                }
                Body::Analyze(a) => {
                    ensure!(!awaiting_ack,"BASELINE_NOT_SEALED");
                    let c=config.as_ref().context("DISCOVER_REQUIRED")?;
                    let manifest: Manifest=convert(&a.manifest.context("MANIFEST_REQUIRED")?)?;
                    let contexts=c.contexts.iter().map(|ctx| {
                        let sources=ctx.sources.iter().map(|s| {
                            let u=s.unit.as_ref().context("SOURCE_UNIT_REQUIRED")?;
                            Ok(json!({"path":u.path,"hash":u.hash,"module":u.module,"world":u.world,"scope":u.scope,"auxiliary":u.auxiliary,"input":s.input,"publish":s.publish}))
                        }).collect::<Result<Vec<_>>>()?;
                        Ok(json!({"version":9,"type_graph":true,"context":ctx.id,"module":ctx.module,"world":ctx.world,"scope":ctx.scope,"prerequisite":ctx.prerequisite,"dependency_modules":ctx.dependency_modules,"source_roots":ctx.source_roots,"sources":sources,"classpath":ctx.classpath,"digest":ctx.digest,"lombok":ctx.lombok,"lombok_strict":ctx.lombok_strict,"external_policy_configured":ctx.external_policy_configured,"external_exclude_patterns":ctx.external_exclude_patterns,"spring":ctx.spring,"spring_xml":ctx.spring_xml,"sofa":ctx.sofa,
                            "model_decode_bytes":if ctx.model_decode_bytes==0 {512*1024*1024} else {ctx.model_decode_bytes},
                            "model_archive_bytes":if ctx.model_archive_bytes==0 {64*1024*1024} else {ctx.model_archive_bytes},
                            "model_record_limit":if ctx.model_record_limit==0 {100000} else {ctx.model_record_limit},
                            "resources":ctx.resources.iter().map(|r|json!({"unit":r.unit,"input":r.input})).collect::<Vec<_>>(),
                            "source_indices":ctx.source_indices,"publish_indices":ctx.publish_indices,
                            "classpath_indices":ctx.classpath_indices,"resource_indices":ctx.resource_indices}))
                    }).collect::<Result<Vec<_>>>()?;
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
                    let request=json!({"version":9,"compact_inputs":c.compact_inputs,"input_table":c.input_table,"classpath_table":c.classpath_table,"facts":true,"java_home":c.java_home,"bridge":c.bridge,"scratch":c.scratch,"contexts":contexts,"parallelism":c.parallelism,"full":a.full,"baseline_summary":if a.baseline_summary_json.is_empty(){json!({})}else{serde_json::from_slice::<serde_json::Value>(&a.baseline_summary_json)?},"profile":a.profile,"cache_root":cache_root,"signature_cache":derived_cache.is_some(),"signature_pin_bytes":if derived_cache.is_some() {128_u64<<20} else {256_u64<<20},"result_bytes":limits.bytes,"fact_limit":limits.facts,"compiler_heap_bytes":c.compiler_heap_bytes,"compiler_timeout_seconds":if c.compiler_timeout_seconds==0 {120} else {c.compiler_timeout_seconds}});
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
                            frame=&mut control => { ensure!(matches!(frame?.body,Some(Body::Cancel(_))),"UNEXPECTED_DURING_ANALYSIS"); cancelled=true; return Ok(()); },
                            loaded=ProductEngine::load(library,heap,backend,capture.clone()) => loaded?,
                        };
                        native=Some(loaded);
                    }
                    let lib=native.as_mut().context("NATIVE_REQUIRED")?;
                    lib.set_capture(capture.clone());
                    let computation = async {
                        ensure!(lib.heap_bytes().await?==heap,"WARM_NATIVE_HEAP_MISMATCH");
                        lib.start(serde_json::to_vec(&request)?).await.context("JAVA_ENGINE_START")?;
                        if let Some(e)=evidence.as_mut() {e.phase(backend.started_phase());}
                        if let Some(t)=timings.as_mut() {t.phase(backend.started_phase());}
                        let state = lib.ready().await?;
                        let output=lib.result_file(limits.bytes.min(64<<20),Path::new(&c.scratch)).await.context("JAVA_DESCRIPTOR_READ")?;
                        Ok::<_,anyhow::Error>((state,output))
                    };
                    enum Wake { Control(Box<Result<w::Envelope>>), Finished(Result<(i32,tempfile::NamedTempFile)>) }
                    let completed = tokio::select! {
                        biased;
                        frame=&mut control => Wake::Control(Box::new(frame)),
                        result=computation => Wake::Finished(result),
                    };
                    let (state,output)= match completed {
                        Wake::Control(frame) => { ensure!(matches!((*frame)?.body,Some(Body::Cancel(_))),"UNEXPECTED_DURING_ANALYSIS"); lib.cancel()?; cancelled=true; return Ok(()); },
                        Wake::Finished(result) => result?,
                    };
                    measure(Metric::DescriptorBytes, output.as_file().metadata()?.len(), true, || ());
                    if let Some(e)=evidence.as_mut() {e.phase(backend.finished_phase());}
                    if let Some(t)=timings.as_mut() {t.phase(backend.finished_phase());}
                    if state!=1 {
                        ensure!(output.as_file().metadata()?.len()<=64*1024,"NATIVE_FAILURE_BUDGET");
                        let error=std::fs::read(output.path())?;
                        bail!("JAVA_ANALYSIS: {}",String::from_utf8_lossy(&error));
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
                            crate::result::read_owned_stream(std::io::BufReader::new(output.reopen().context("JAVA_DESCRIPTOR_REOPEN")?),
                                &reader_manifest, limits, &root,
                                |mode, replacements| tx.blocking_send(crate::delivery::Event::Begin {
                                    mode: mode.into(), replacements: replacements.to_vec(),
                                }).context("DELIVERY_CANCELLED"),
                                |fact| encoder.push(fact),
                                || { ensure!(!reader_cancel.load(std::sync::atomic::Ordering::Relaxed), "CANCELLED"); Ok(()) })?;
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
                                    ensure!(matches!(frame?.body, Some(Body::Cancel(_))), "UNEXPECTED_DURING_DELIVERY");
                                    lib.cancel()?; cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
                                    anyhow::bail!("CANCELLED");
                                },
                                event = rx.recv() => match event { Some(event) => event, None => break },
                            };
                            let body = match event {
                                crate::delivery::Event::Begin { mode, replacements } => {
                                    ensure!(!begun, "DUPLICATE_RESULT_BEGIN"); begun = true;
                                    let r = AnalysisHeader { mode, baseline: a.baseline.clone(), manifest: manifest.id(),
                                        profile: a.profile.clone(), producer: "javac-java8-v1".into(), replacements };
                                    r.validate(&manifest).map_err(anyhow::Error::msg)?;
                                    Body::Begin(w::Begin { mode:r.mode, baseline:r.baseline, manifest:r.manifest,
                                        profile:r.profile, producer:r.producer,
                                        replacements:r.replacements.iter().map(convert).collect::<Result<_>>()? })
                                },
                                crate::delivery::Event::Chunk(facts) => {
                                    ensure!(begun && !verified, "DELIVERY_STATE");
                                    // Sequence is counted here as messages are sent.
                                    Body::Chunk(w::Chunk { seq: delivery_seq,  rows:facts })
                                },
                                crate::delivery::Event::End { chunks, count, checksum } => {
                                    ensure!(begun && !verified && chunks == delivery_seq, "DELIVERY_STATE");
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
                                    ensure!(matches!(request?.body, Some(Body::Cancel(_))), "UNEXPECTED_DURING_DELIVERY");
                                    lib.cancel()?; anyhow::bail!("CANCELLED");
                                },
                                written = write_frame(&mut outgoing, &frame) => written?,
                            }
                            if is_chunk { delivery_seq += 1; }
                            if verified { break; }
                        }
                        ensure!(verified, "UNVERIFIED_NATIVE_DELIVERY");
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
                    ensure!(awaiting_ack && !ack.revision.is_empty(),"PUBLISH_ACK_STATE");
                    let lib=native.as_mut().context("NATIVE_REQUIRED")?;
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
                _=>bail!("UNEXPECTED_WORKER_MESSAGE")
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
