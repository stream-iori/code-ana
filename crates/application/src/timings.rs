//! Optional task-local performance counters; no changes to public task records.
use std::{collections::BTreeMap, path::PathBuf, time::Instant};

pub(crate) struct IndexTimings {
    scope: &'static str,
    output: PathBuf,
    started: Instant,
    phases: BTreeMap<String, u64>,
    storage: BTreeMap<String, u64>,
}

impl IndexTimings {
    pub(crate) fn start(task: &str) -> Option<Self> {
        Self::start_named("index", task)
    }

    pub(crate) fn capture(task: &str) -> Option<Self> {
        Self::start_named("capture", task)
    }

    fn start_named(scope: &'static str, task: &str) -> Option<Self> {
        let root = PathBuf::from(std::env::var_os("CODEANA_PERFORMANCE_TIMINGS_DIR")?);
        if let Err(error) = std::fs::create_dir_all(&root) {
            eprintln!("PERFORMANCE_TIMINGS_WRITE_FAILED: {error}");
            return None;
        }
        Some(Self {
            scope,
            output: root.join(format!("{scope}-{task}.json")),
            started: Instant::now(),
            phases: BTreeMap::new(),
            storage: BTreeMap::new(),
        })
    }

    pub(crate) fn phase(&mut self, name: &str) {
        self.phases
            .insert(name.into(), self.started.elapsed().as_millis() as u64);
    }

    pub(crate) fn storage(&mut self, phases: &BTreeMap<String, u64>) {
        self.storage.clone_from(phases);
    }
}

impl Drop for IndexTimings {
    fn drop(&mut self) {
        let data = serde_json::json!({
            "phase_elapsed_ms": self.phases,
            "storage_phase_ms": self.storage,
            "elapsed_ms": self.started.elapsed().as_millis() as u64,
            "measurement_scope": self.scope,
        });
        match serde_json::to_vec(&data)
            .map_err(std::io::Error::other)
            .and_then(|bytes| {
                use std::io::Write;
                let mut output = std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&self.output)?;
                output.write_all(&bytes)
            }) {
            Ok(()) => {}
            Err(error) => eprintln!("PERFORMANCE_TIMINGS_WRITE_FAILED: {error}"),
        }
    }
}
