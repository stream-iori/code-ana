mod native;
mod product;
mod result;
use anyhow::{Result, ensure};
use clap::Parser;
use std::path::PathBuf;
#[derive(Parser)]
struct Args {
    library: PathBuf,
    #[arg(long)]
    fd: Option<i32>,
    #[arg(long)]
    probe: Option<PathBuf>,
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
        native::verify_bundle(&args.library)?;
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
            })
        );
        return Ok(());
    }
    if let Some(fd) = args.fd {
        return product::serve(&args.library, fd).await;
    }
    let _ = native::verify_bundle(&args.library)?;
    let mut library = match args.native_heap_bytes {
        Some(heap) => native::Native::load_with_heap(&args.library, heap)?,
        None => native::Native::load(&args.library)?,
    };
    if args.heap_probe {
        println!("{}", library.heap_bytes()?);
        return Ok(());
    }
    if let Some(request) = args.probe {
        library.start(&std::fs::read(request)?)?;
        let started = std::time::Instant::now();
        let deadline = started + std::time::Duration::from_secs(130);
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
                anyhow::bail!("NATIVE_TIMEOUT");
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        let result = library.result()?;
        ensure!(
            library.poll()? == 1,
            "NATIVE_ANALYSIS: {}",
            String::from_utf8_lossy(&result)
        );
        println!("{}", String::from_utf8(result)?);
        library.ack()?;
    } else {
        println!("Hello from Java 25 via FFI: {}", library.hello()?);
    }
    Ok(())
}
