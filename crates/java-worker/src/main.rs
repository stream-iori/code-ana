mod actor;
mod backend;
mod delivery;
mod diagnostics;
mod jvm;
mod native;
mod product;
mod result;
mod timings;
use anyhow::{Result, ensure};
use clap::Parser;
use std::path::PathBuf;
#[derive(Parser)]
struct Args {
    library: PathBuf,
    #[arg(long, value_enum, default_value = "native")]
    backend: backend::Backend,
    #[arg(long)]
    fd: Option<i32>,
    #[arg(long)]
    probe: Option<PathBuf>,
    /// Preserve a validated diagnostic archive instead of printing a fact array.
    #[arg(long, requires = "probe")]
    probe_archive: Option<PathBuf>,
    #[arg(long, requires = "probe", value_parser = clap::value_parser!(u64).range(1..=86400))]
    probe_timeout_seconds: Option<u64>,
    #[arg(long, requires = "probe")]
    cancel_after_ms: Option<u64>,
    #[arg(long)]
    native_heap_bytes: Option<u64>,
    #[arg(long)]
    heap_probe: bool,
    /// Verify the bundle and report the versions compiled into this worker.
    #[arg(long, conflicts_with_all = ["fd", "probe", "heap_probe", "native_heap_bytes"])]
    runtime_info: bool,
}
#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let args = Args::parse();
    if args.runtime_info {
        backend::verify_bundle(&args.library, args.backend)?;
        let manifest: serde_json::Value = serde_json::from_slice(&std::fs::read(
            args.library
                .parent()
                .ok_or_else(|| anyhow::anyhow!("BUNDLE_PATH"))?
                .join("manifest.json"),
        )?)?;
        println!(
            "{}",
            serde_json::json!({
                "worker_protocol": codeana_model::WORKER_PROTOCOL_VERSION,
                "fact_ir": codeana_model::VERSION,
                "adapter": manifest["adapter"],
                "analysis_request": manifest["analysis_request"],
                "abi": manifest["abi"],
                "backend": manifest["backend"],
            })
        );
        return Ok(());
    }
    if let Some(fd) = args.fd {
        return product::serve(&args.library, fd, args.backend).await;
    }
    let _ = backend::verify_bundle(&args.library, args.backend)?;
    let mut library = match args.backend {
        backend::Backend::Native => backend::Engine::Native(match args.native_heap_bytes {
            Some(heap) => native::Native::load_with_heap(&args.library, heap)?,
            None => native::Native::load(&args.library)?,
        }),
        backend::Backend::Jvm => backend::Engine::Jvm(jvm::Jvm::load(
            &args.library,
            args.native_heap_bytes.unwrap_or(256 << 20),
        )?),
    };
    if args.heap_probe {
        println!("{}", library.heap_bytes()?);
        return Ok(());
    }
    if let Some(request) = args.probe {
        let request_bytes = std::fs::read(request)?;
        let request_value: serde_json::Value = serde_json::from_slice(&request_bytes)?;
        let scratch = std::path::Path::new(
            request_value["scratch"]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("PROBE_SCRATCH"))?,
        );
        library.start(&request_bytes)?;
        let started = std::time::Instant::now();
        let deadline =
            started + std::time::Duration::from_secs(args.probe_timeout_seconds.unwrap_or(130));
        let mut cancelled = false;
        while library.poll()? == 0 {
            if !cancelled
                && args
                    .cancel_after_ms
                    .is_some_and(|ms| started.elapsed().as_millis() >= ms as u128)
            {
                library.cancel()?;
                cancelled = true;
            }
            if std::time::Instant::now() > deadline {
                library.cancel()?;
                anyhow::bail!(args.backend.timeout_code());
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        let result = library.result()?;
        ensure!(
            library.poll()? == 1,
            "{}: {}",
            args.backend.analysis_code(),
            String::from_utf8_lossy(&result)
        );
        if let Some(destination) = args.probe_archive {
            let limits = codeana_contracts::java::OutputLimits {
                bytes: request_value["result_bytes"]
                    .as_u64()
                    .unwrap_or(codeana_model::MAX_RESULT as u64),
                facts: request_value["fact_limit"]
                    .as_u64()
                    .unwrap_or(codeana_model::MAX_FACTS as u64),
            };
            println!(
                "{}",
                result::probe_archive(&result, scratch, limits, &destination)?
            );
        } else {
            println!("{}", String::from_utf8(result::probe(&result, scratch)?)?);
        }
        library.ack()?;
    } else {
        match library {
            backend::Engine::Native(ref native) => {
                println!("Hello from Java 25 via FFI: {}", native.hello()?)
            }
            backend::Engine::Jvm(_) => anyhow::bail!("JVM_PROBE_REQUIRED"),
        }
    }
    Ok(())
}
