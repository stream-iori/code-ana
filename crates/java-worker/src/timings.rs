//! Optional bounded task timing sidecars, without diagnostic snapshots or fact payloads.
use anyhow::Result;
use std::{
    path::{Path, PathBuf},
    time::Instant,
};

pub struct PerformanceTimings {
    directory: PathBuf,
    scratch: PathBuf,
    started: Instant,
    phases: Vec<serde_json::Value>,
    pub capture: codeana_contracts::performance::Capture,
}
impl PerformanceTimings {
    pub fn start(scratch: &Path) -> Result<Option<Self>> {
        let Some(root) = std::env::var_os("CODEANA_PERFORMANCE_TIMINGS_DIR") else {
            return Ok(None);
        };
        std::fs::create_dir_all(&root)?;
        let root = std::fs::canonicalize(root)?;
        let scratch = scratch.canonicalize()?;
        anyhow::ensure!(!root.starts_with(&scratch), "TIMING_CAPTURE_RECURSION");
        let directory = tempfile::Builder::new()
            .prefix("worker-")
            .tempdir_in(root)?
            .keep();
        Ok(Some(Self {
            directory,
            scratch,
            started: Instant::now(),
            phases: Vec::new(),
            capture: codeana_contracts::performance::Capture::from_env(),
        }))
    }
    pub fn phase(&mut self, name: &str) {
        self.phases.push(
            serde_json::json!({"phase":name,"elapsed_ms":self.started.elapsed().as_millis()}),
        );
    }
}
impl Drop for PerformanceTimings {
    fn drop(&mut self) {
        // Native telemetry is small and contains counters only. Missing JVM telemetry is explicit.
        let source = self.scratch.join("native-timings.json");
        if source.metadata().is_ok_and(|m| m.len() <= (8 << 20)) {
            let _ = std::fs::copy(source, self.directory.join("java-timings.json"));
        }
        let recording = self.scratch.join("allocations.jfr");
        if recording
            .metadata()
            .is_ok_and(|m| m.is_file() && m.len() <= (256 << 20))
        {
            let _ = std::fs::copy(recording, self.directory.join("allocations.jfr"));
        }
        if let Ok(bytes) = serde_json::to_vec(&self.capture.snapshot())
            && let Err(error) = std::fs::write(self.directory.join("rust-timings.json"), bytes)
        {
            eprintln!("PERFORMANCE_TIMINGS_WRITE_FAILED: {error}");
        }
        if let Ok(bytes) = serde_json::to_vec(&self.phases)
            && let Err(error) = std::fs::write(self.directory.join("phases.json"), bytes)
        {
            eprintln!("PERFORMANCE_TIMINGS_WRITE_FAILED: {error}");
        }
    }
}
