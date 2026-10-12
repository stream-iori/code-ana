//! Explicit JVM host. Shares CAF1 validation/publication with the native backend.
use anyhow::{Context, Result, ensure};
use serde_json::Value;
use std::{
    cell::RefCell,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
};

pub fn verify_bundle(runner: &Path) -> Result<PathBuf> {
    let root = runner.parent().context("JVM_BUNDLE_PATH")?.canonicalize()?;
    let manifest: Value = serde_json::from_slice(&std::fs::read(root.join("manifest.json"))?)?;
    ensure!(
        manifest["version"] == 1
            && manifest["backend"] == "jvm"
            && manifest["analysis_request"] == 8
            && manifest["worker_protocol"] == codeana_model::WORKER_PROTOCOL_VERSION
            && manifest["fact_ir"] == codeana_model::VERSION
            && manifest["adapter"] == "anatomist-dff2e395-codeana-jvm1"
            && manifest["compiler_batch_protocol"] == 2
            && manifest["java_model"] == "javac-model-v50"
            && manifest["target_java"] == serde_json::json!([8]),
        "JVM_BUNDLE_VERSION_MISMATCH"
    );
    let files = manifest["files"].as_object().context("JVM_BUNDLE_FILES")?;
    ensure!(
        manifest["os"]
            == if cfg!(target_os = "macos") {
                "Darwin"
            } else if cfg!(target_os = "linux") {
                "Linux"
            } else {
                "unsupported"
            }
            && manifest["arch"]
                == if cfg!(target_arch = "aarch64") {
                    "arm64"
                } else {
                    "x86_64"
                },
        "JVM_BUNDLE_PLATFORM_MISMATCH"
    );
    ensure!(
        files.contains_key("runner.jar")
            && files.contains_key("compiler-bridge.jar")
            && files.contains_key("maven-input-worker.jar"),
        "JVM_BUNDLE_FILES_REQUIRED"
    );
    ensure!(
        runner.canonicalize()? == root.join("runner.jar"),
        "JVM_RUNNER_IDENTITY"
    );
    for (name, expected) in files {
        ensure!(codeana_model::safe_path(name), "JVM_BUNDLE_UNSAFE_PATH");
        let path = root.join(name);
        ensure!(
            !std::fs::symlink_metadata(&path)?.file_type().is_symlink()
                && path.canonicalize()?.starts_with(&root),
            "JVM_BUNDLE_UNSAFE_PATH"
        );
        ensure!(
            codeana_model::hash_file(&path)? == expected.as_str().context("JVM_BUNDLE_HASH")?,
            "JVM_BUNDLE_HASH_MISMATCH: {name}"
        );
    }
    Ok(root.join("compiler-bridge.jar"))
}

pub struct Jvm {
    runner: PathBuf,
    heap: u64,
    child: RefCell<Option<Child>>,
    task: Option<tempfile::TempDir>,
}
impl Jvm {
    pub fn load(runner: &Path, heap: u64) -> Result<Self> {
        verify_bundle(runner)?;
        ensure!(
            (8 << 20..=2_u64 << 30).contains(&heap) && heap.is_multiple_of(1 << 20),
            "JVM_HEAP_RANGE"
        );
        Ok(Self {
            runner: runner.canonicalize()?,
            heap,
            child: RefCell::new(None),
            task: None,
        })
    }
    pub fn heap_bytes(&self) -> Result<u64> {
        Ok(self.heap)
    }
    pub fn start(&mut self, request: &[u8]) -> Result<()> {
        ensure!(
            self.task.is_none() && request.len() <= codeana_model::MAX_RESULT,
            "JVM_TASK_STATE"
        );
        let mut value: Value = serde_json::from_slice(request)?;
        let scratch = Path::new(value["scratch"].as_str().context("JVM_SCRATCH")?);
        let task = tempfile::Builder::new()
            .prefix("jvm-host-")
            .tempdir_in(scratch)?;
        let task_scratch = task.path().join("scratch");
        std::fs::create_dir(&task_scratch)?;
        value["scratch"] = serde_json::json!(task_scratch);
        std::fs::write(
            task.path().join("request.json"),
            serde_json::to_vec(&value)?,
        )?;
        let java_home = codeana_contracts::java::jvm_host_home()?;
        let child = Command::new(java_home.join("bin/java"))
            .arg(format!("-Xmx{}m", self.heap / (1 << 20)))
            .arg("-jar")
            .arg(&self.runner)
            .arg(task.path().join("request.json"))
            .arg(task.path().join("result.json"))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()?;
        *self.child.borrow_mut() = Some(child);
        self.task = Some(task);
        Ok(())
    }
    pub fn poll(&self) -> Result<i32> {
        let mut child = self.child.borrow_mut();
        Ok(
            match child.as_mut().context("JVM_TASK_REQUIRED")?.try_wait()? {
                None => 0,
                Some(status) if status.success() => 1,
                Some(_) => -1,
            },
        )
    }
    pub fn cancel(&self) -> Result<()> {
        // Cooperatively interrupt the shared analysis session; let its finally blocks reap javac.
        if let Some(task) = &self.task {
            std::fs::write(task.path().join("cancel"), [])?;
        }
        Ok(())
    }
    pub fn result_file_with_limit(
        &self,
        max: u64,
        directory: Option<&Path>,
    ) -> Result<tempfile::NamedTempFile> {
        let result = self
            .task
            .as_ref()
            .context("JVM_TASK_REQUIRED")?
            .path()
            .join("result.json");
        ensure!(
            std::fs::metadata(&result)?.len() <= max,
            "JVM_RESULT_BUDGET"
        );
        let output = match directory {
            Some(path) => tempfile::NamedTempFile::new_in(path)?,
            None => tempfile::NamedTempFile::new()?,
        };
        std::fs::copy(result, output.path())?;
        if std::env::var_os("CODEANA_PERFORMANCE_TIMINGS_DIR").is_some() {
            let telemetry = self
                .task
                .as_ref()
                .context("JVM_TASK_REQUIRED")?
                .path()
                .join("scratch/native-timings.json");
            if let Some(directory) = directory
                && telemetry.metadata().is_ok_and(|m| m.len() <= 8 << 20)
                && let Err(error) = std::fs::copy(telemetry, directory.join("native-timings.json"))
            {
                eprintln!("PERFORMANCE_TIMINGS_WRITE_FAILED: {error}");
            }
        }
        Ok(output)
    }
    pub fn result(&self) -> Result<Vec<u8>> {
        Ok(std::fs::read(
            self.result_file_with_limit(codeana_model::MAX_RESULT as u64, None)?
                .path(),
        )?)
    }
    pub fn ack(&self) -> Result<()> {
        ensure!(self.poll()? == 1, "JVM_ACK_STATE");
        Ok(())
    }
    pub fn finish(&mut self) -> Result<()> {
        if let Some(mut child) = self.child.borrow_mut().take()
            && child.try_wait()?.is_none()
        {
            self.cancel()?;
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
            while child.try_wait()?.is_none() && std::time::Instant::now() < deadline {
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            self.cancel_child(&mut child);
        }
        self.task.take();
        Ok(())
    }
    fn cancel_child(&self, child: &mut Child) {
        let _ = child.kill();
        let _ = child.wait();
    }
}
impl Drop for Jvm {
    fn drop(&mut self) {
        let _ = self.finish();
    }
}
