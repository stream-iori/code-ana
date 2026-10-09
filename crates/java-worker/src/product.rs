use crate::native::{Native, verify_bundle};
use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::{
    archive::{FactArchive, FactArchiveBuilder},
    convert, envelope, fact_from_wire, read_frame, worker as w,
    worker::envelope::Body,
    write_frame,
};
use codeana_model::*;
use codeana_storage::derived_cache::{DerivedCacheLimits, DerivedCacheSession};
use serde_json::json;
use std::{os::fd::FromRawFd, path::Path};
use tokio::net::UnixStream;

pub async fn serve(library: &Path, fd: i32) -> Result<()> {
    ensure!(fd >= 3, "DEDICATED_FD_REQUIRED");
    let bridge = verify_bundle(library)?.canonicalize()?;
    let socket = unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) };
    socket.set_nonblocking(true)?;
    let mut ipc = UnixStream::from_std(socket)?;
    let mut config: Option<w::JavaConfig> = None;
    let mut baseline_input: Option<FactArchiveBuilder> = None;
    let mut baseline: Option<FactArchive> = None;
    let mut baseline_seq = 0;
    let mut baseline_sealed = false;
    let mut native: Option<Native> = None;
    let mut derived_cache: Option<DerivedCacheSession> = None;
    let mut hello = false;
    let mut awaiting_ack = false;
    loop {
        let frame = read_frame(&mut ipc).await?;
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
                    let limits = codeana_contracts::java::OutputLimits::from_config(&c)?;
                    baseline_input=Some(FactArchiveBuilder::new_in(limits.bytes,limits.facts,Path::new(&c.scratch))?);
                    config=Some(c);
                    write_frame(&mut ipc,&envelope(&attempt,Body::Context(context))).await?;
                }
                Body::BaselineChunk(chunk) => {
                    ensure!(config.is_some() && !awaiting_ack && !baseline_sealed && chunk.seq==baseline_seq,"BASELINE_SEQUENCE"); baseline_seq+=1;
                    for fact in chunk.facts {
                        baseline_input.as_mut().context("BASELINE_NOT_OPEN")?.push(&fact_from_wire(fact)?)?;
                    }
                }
                Body::BaselineEnd(end) => {
                    ensure!(config.is_some() && !awaiting_ack && !baseline_sealed,"BASELINE_CHECKSUM");
                    let sealed=baseline_input.take().context("BASELINE_NOT_OPEN")?.seal()?;
                    ensure!(end.count==sealed.count && end.checksum==sealed.checksum,"BASELINE_CHECKSUM");
                    baseline=Some(sealed); baseline_sealed=true;
                }
                Body::Analyze(a) => {
                    ensure!(!awaiting_ack && baseline_sealed && a.baseline_facts.is_empty(),"BASELINE_NOT_SEALED");
                    let c=config.as_ref().context("DISCOVER_REQUIRED")?;
                    let manifest: Manifest=convert(&a.manifest.context("MANIFEST_REQUIRED")?)?;
                    let contexts=c.contexts.iter().map(|ctx| {
                        let sources=ctx.sources.iter().map(|s| {
                            let u=s.unit.as_ref().context("SOURCE_UNIT_REQUIRED")?;
                            Ok(json!({"path":u.path,"hash":u.hash,"module":u.module,"world":u.world,"scope":u.scope,"auxiliary":u.auxiliary,"input":s.input,"publish":s.publish}))
                        }).collect::<Result<Vec<_>>>()?;
                        Ok(json!({"version":8,"type_graph":true,"context":ctx.id,"module":ctx.module,"world":ctx.world,"scope":ctx.scope,"prerequisite":ctx.prerequisite,"dependency_modules":ctx.dependency_modules,"source_roots":ctx.source_roots,"sources":sources,"classpath":ctx.classpath,"digest":ctx.digest,"lombok":ctx.lombok,"lombok_strict":ctx.lombok_strict,"spring":ctx.spring,"spring_xml":ctx.spring_xml,"sofa":ctx.sofa,
                            "model_archive_bytes":if ctx.model_archive_bytes==0 {64*1024*1024} else {ctx.model_archive_bytes},
                            "model_record_limit":if ctx.model_record_limit==0 {100000} else {ctx.model_record_limit},
                            "resources":ctx.resources.iter().map(|r|json!({"unit":r.unit,"input":r.input})).collect::<Vec<_>>(),
                            "source_indices":ctx.source_indices,"publish_indices":ctx.publish_indices,
                            "classpath_indices":ctx.classpath_indices,"resource_indices":ctx.resource_indices}))
                    }).collect::<Result<Vec<_>>>()?;
                    let limits=codeana_contracts::java::OutputLimits::from_config(c)?;
                    let baseline=baseline.as_ref().context("BASELINE_NOT_SEALED")?;
                    derived_cache = if c.signature_cache {
                        let root = Path::new(&c.cache_root).with_extension("managed-v1");
                        match DerivedCacheSession::open(&root, Path::new(&c.scratch),
                            &["anatomist-signatures-v50-reloc2"], DerivedCacheLimits::default()) {
                            Ok(cache) => Some(cache),
                            Err(error) => { eprintln!("DERIVED_CACHE_UNAVAILABLE: {error}"); None }
                        }
                    } else { None };
                    let cache_root = derived_cache.as_ref().map(|cache| cache.namespace_path("anatomist-signatures-v50-reloc2")).transpose()?;
                    let request=json!({"version":8,"compact_inputs":c.compact_inputs,"input_table":c.input_table,"classpath_table":c.classpath_table,"facts":true,"java_home":c.java_home,"bridge":c.bridge,"scratch":c.scratch,"contexts":contexts,"parallelism":c.parallelism,"full":a.full,"baseline_archive":{"path":baseline.path(),"sha256":baseline.file_hash()?,"count":baseline.count,"bytes":baseline.bytes},"profile":a.profile,"cache_root":cache_root,"signature_cache":derived_cache.is_some(),"signature_pin_bytes":if derived_cache.is_some() {128_u64<<20} else {256_u64<<20},"result_bytes":limits.bytes,"fact_limit":limits.facts,"compiler_heap_bytes":c.compiler_heap_bytes,"compiler_timeout_seconds":if c.compiler_timeout_seconds==0 {120} else {c.compiler_timeout_seconds}});
                    let heap=if c.native_heap_bytes==0 {256*1024*1024} else {c.native_heap_bytes};
                    let mut evidence=crate::diagnostics::ReplayEvidence::start(&request,Path::new(&c.scratch),library)?;
                    if native.is_none() { native=Some(Native::load_with_heap(library,heap)?); }
                    ensure!(native.as_ref().context("NATIVE_REQUIRED")?.heap_bytes()?==heap,"WARM_NATIVE_HEAP_MISMATCH");
                    let lib=native.as_mut().context("NATIVE_REQUIRED")?;
                    lib.start(&serde_json::to_vec(&request)?)?;
                    if let Some(e)=evidence.as_mut() {e.phase("native_started");}
                    {
                        let control=read_frame(&mut ipc);tokio::pin!(control);
                        while lib.poll()?==0 {
                            tokio::select! {
                                frame=&mut control=>{ensure!(matches!(frame?.body,Some(Body::Cancel(_))),"UNEXPECTED_DURING_ANALYSIS");lib.cancel()?;cancelled = true;return Ok(());},
                                _=tokio::time::sleep(std::time::Duration::from_millis(20))=>{}
                            }
                        }
                    }
                    let output=lib.result_file_with_limit(limits.bytes.min(64<<20),Some(Path::new(&c.scratch)))?;
                    if let Some(e)=evidence.as_mut() {e.phase("native_finished");}
                    if lib.poll()?!=1 {
                        ensure!(output.as_file().metadata()?.len()<=64*1024,"NATIVE_FAILURE_BUDGET");
                        let error=std::fs::read(output.path())?;
                        bail!("JAVA_ANALYSIS: {}",String::from_utf8_lossy(&error));
                    }
                    let result=crate::result::read_owned(std::io::BufReader::new(output.reopen()?),&manifest,limits,Some(Path::new(&c.scratch)))?;
                    if let Some(e)=evidence.as_mut() {e.phase("archive_validated_and_deduplicated");}
                    let r=AnalysisHeader{mode:result.mode,baseline:a.baseline,manifest:manifest.id(),profile:a.profile,producer:"javac-java8-v1".into(),replacements:result.replacements};
                    r.validate(&manifest).map_err(anyhow::Error::msg)?;
                    write_frame(&mut ipc,&envelope(&attempt,Body::Begin(w::Begin{mode:r.mode,baseline:r.baseline,manifest:r.manifest,profile:r.profile,producer:r.producer,replacements:r.replacements.iter().map(convert).collect::<Result<_>>()?}))).await?;
                    let mut seq=0;
                    for chunk in result.records.chunks()? {
                        write_frame(&mut ipc,&envelope(&attempt,Body::Chunk(w::Chunk{seq,facts:chunk?}))).await?;
                        seq+=1;
                    }
                    write_frame(&mut ipc,&envelope(&attempt,Body::End(w::End{chunks:seq,count:result.records.count,checksum:result.records.checksum}))).await?;
                    awaiting_ack=true;
                    if let Some(e)=evidence.as_mut() {e.phase("result_end_sent");}
                }
                Body::Ack(ack)=> {
                    ensure!(awaiting_ack && !ack.revision.is_empty(),"PUBLISH_ACK_STATE");
                    let lib=native.as_mut().context("NATIVE_REQUIRED")?;
                    lib.ack()?;
                    lib.finish()?;
                    if let Some(mut cache) = derived_cache.take() {
                        match cache.commit() {
                            Ok(stats) => eprintln!("DERIVED_CACHE: {}", serde_json::to_string(&stats)?),
                            Err(error) => eprintln!("DERIVED_CACHE_WRITE_UNAVAILABLE: {error}"),
                        }
                    }
                    config=None;
                    baseline=None;
                    baseline_input=None;
                    baseline_seq=0;
                    baseline_sealed=false;
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
                        message: error.to_string(),
                    }),
                ),
            )
            .await;
            return Err(error);
        }
        if cancelled {
            return Ok(());
        }
    }
}
