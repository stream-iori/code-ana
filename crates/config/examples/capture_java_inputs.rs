//! Read-only acceptance probe of the production capture path; never invokes a target build.
use anyhow::{Context, Result, ensure};
use codeana_model::{Request, hash_file};
use std::path::PathBuf;

fn main() -> Result<()> {
    let root = PathBuf::from(std::env::args().nth(1).context("project path required")?);
    let start = std::time::Instant::now();
    let capture = codeana_config::capture(&root, &Request::default())?;
    for (expected, blob) in &capture.blobs {
        ensure!(
            hash_file(blob.as_ref())? == *expected,
            "CAPTURE_HASH_MISMATCH"
        );
    }
    let dependencies: Vec<_> = capture
        .manifest
        .files
        .iter()
        .filter(|s| s.scope == "DEPENDENCY")
        .collect();
    println!(
        "{}",
        serde_json::json!({
            "modules": capture.effective.config.modules.len(),
            "sources": capture.manifest.files.iter().filter(|s| s.language == "java").count(),
            "captured_files": capture.manifest.files.len(),
            "captured_bytes": capture.manifest.files.iter().map(|s| s.size).sum::<u64>(),
            "dependency_archives": dependencies.len(),
            "dependency_bytes": dependencies.iter().map(|s| s.size).sum::<u64>(),
            "manifest": capture.manifest.id(),
            "elapsed_ms": start.elapsed().as_millis(),
            "verified_blobs": capture.blobs.len(),
        })
    );
    Ok(())
}
