//! Explicit local replay capture, outside the worker's disposable task directory.
use anyhow::{Result, ensure};
use serde_json::Value;
use std::{
    path::{Path, PathBuf},
    time::Instant,
};

pub struct ReplayEvidence {
    directory: PathBuf,
    scratch: PathBuf,
    started: Instant,
    phases: Vec<Value>,
}
impl ReplayEvidence {
    pub fn start(request: &Value, scratch: &Path, library: &Path) -> Result<Option<Self>> {
        let Some(root) = std::env::var_os("CODEANA_JAVA_DIAGNOSTICS_DIR") else {
            return Ok(None);
        };
        let root = PathBuf::from(root);
        std::fs::create_dir_all(&root)?;
        let root = root.canonicalize()?;
        let scratch = scratch.canonicalize()?;
        ensure!(!root.starts_with(&scratch), "DIAGNOSTIC_CAPTURE_RECURSION");
        let directory = tempfile::Builder::new()
            .prefix("java-replay-")
            .tempdir_in(root)?
            .keep();
        let started = Instant::now();
        copy_tree(&scratch, &directory.join("snapshot"))?;
        std::fs::write(
            directory.join("request.json"),
            serde_json::to_vec_pretty(request)?,
        )?;
        let mut replay = request.clone();
        rebase(&mut replay, &scratch, &directory.join("snapshot"));
        std::fs::write(
            directory.join("replay-request.json"),
            serde_json::to_vec_pretty(&replay)?,
        )?;
        let manifest_path = library.parent().unwrap().join("manifest.json");
        let manifest: Value = serde_json::from_slice(&std::fs::read(&manifest_path)?)?;
        let jvm = manifest["backend"] == "jvm";
        let manifest_name = if jvm {
            "jvm-manifest.json"
        } else {
            "native-manifest.json"
        };
        std::fs::copy(&manifest_path, directory.join(manifest_name))?;
        let bundle = if jvm {
            library.parent().unwrap()
        } else {
            library.parent().unwrap().parent().unwrap()
        };
        let runtime = directory.join("runtime");
        std::fs::create_dir(&runtime)?;
        for (relative, expected) in manifest["files"]
            .as_object()
            .ok_or_else(|| anyhow::anyhow!("NATIVE_MANIFEST_FILES"))?
        {
            let relative = Path::new(relative);
            ensure!(
                !relative.is_absolute()
                    && !relative
                        .components()
                        .any(|p| matches!(p, std::path::Component::ParentDir)),
                "NATIVE_MANIFEST_PATH"
            );
            let target = runtime.join(relative);
            std::fs::create_dir_all(target.parent().unwrap())?;
            ensure!(
                codeana_model::hash_file(&bundle.join(relative))?
                    == expected.as_str().unwrap_or(""),
                "NATIVE_MANIFEST_IDENTITY"
            );
            std::fs::copy(bundle.join(relative), target)?;
        }
        if jvm {
            std::fs::copy(&manifest_path, runtime.join("manifest.json"))?;
        } else {
            std::fs::copy(&manifest_path, runtime.join("native/manifest.json"))?;
        }
        std::fs::copy(
            std::env::current_exe()?,
            runtime.join("codeana-java-worker"),
        )?;
        std::fs::write(
            directory.join("provenance.json"),
            serde_json::to_vec_pretty(&serde_json::json!({
            "worker_protocol":codeana_model::WORKER_PROTOCOL_VERSION,"library":library,
            "library_sha256":codeana_model::hash_file(library)?,
            "worker_sha256":codeana_model::hash_file(&std::env::current_exe()?)?,
            "request_sha256":codeana_model::hash(serde_json::to_vec(request)?),
            "snapshot":directory.join("snapshot"),"capture_elapsed_ms":started.elapsed().as_millis()}))?,
        )?;
        eprintln!("JAVA_REPLAY_EVIDENCE: {}", directory.display());
        Ok(Some(Self {
            directory,
            scratch,
            started: Instant::now(),
            phases: Vec::new(),
        }))
    }
    pub fn phase(&mut self, phase: &str) {
        self.phases.push(
            serde_json::json!({"phase":phase,"elapsed_ms":self.started.elapsed().as_millis()}),
        );
    }
}
impl Drop for ReplayEvidence {
    fn drop(&mut self) {
        for name in [
            "native-timings.json",
            "fact-conflict.json",
            "invalid-fact.json",
        ] {
            let source = self.scratch.join(name);
            if source.is_file() {
                let _ = std::fs::copy(source, self.directory.join(name));
            }
        }
        if let Ok(bytes) = serde_json::to_vec_pretty(&self.phases) {
            let _ = std::fs::write(self.directory.join("worker-phases.json"), bytes);
        }
    }
}
fn copy_tree(source: &Path, destination: &Path) -> Result<()> {
    std::fs::create_dir(destination)?;
    for entry in std::fs::read_dir(source)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        ensure!(!kind.is_symlink(), "DIAGNOSTIC_CAPTURE_SYMLINK");
        let target = destination.join(entry.file_name());
        if kind.is_dir() {
            copy_tree(&entry.path(), &target)?;
        } else if kind.is_file() {
            std::fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}
fn rebase(value: &mut Value, source: &Path, destination: &Path) {
    match value {
        Value::String(text) => {
            let path = Path::new(text);
            // macOS /var is an alias of /private/var. The captured scratch is
            // canonical, while request paths can still use the lexical alias.
            let canonical = if path.is_absolute() {
                path.canonicalize().ok()
            } else {
                None
            };
            if let Ok(relative) = path
                .strip_prefix(source)
                .or_else(|_| canonical.as_deref().unwrap_or(path).strip_prefix(source))
            {
                *text = destination.join(relative).to_string_lossy().into_owned();
            }
        }
        Value::Array(values) => values
            .iter_mut()
            .for_each(|v| rebase(v, source, destination)),
        Value::Object(values) => values
            .values_mut()
            .for_each(|v| rebase(v, source, destination)),
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn replay_rebases_aliases_and_keeps_external_inputs() {
        let temporary = tempfile::tempdir().unwrap();
        let source = temporary.path().join("source");
        std::fs::create_dir(&source).unwrap();
        std::fs::write(source.join("A.java"), "class A {}").unwrap();
        let alias = temporary.path().join("alias");
        std::os::unix::fs::symlink(&source, &alias).unwrap();
        let destination = temporary.path().join("snapshot");
        let external = temporary.path().join("external.jar");
        std::fs::write(&external, "external").unwrap();
        let mut value = serde_json::json!({"inputs":[alias.join("A.java"), source.join("A.java"), external],"hash":"unchanged"});
        rebase(&mut value, &source.canonicalize().unwrap(), &destination);
        assert_eq!(
            value["inputs"][0],
            destination.join("A.java").to_string_lossy().as_ref()
        );
        assert_eq!(value["inputs"][1], value["inputs"][0]);
        assert_eq!(value["inputs"][2], external.to_string_lossy().as_ref());
        assert_eq!(value["hash"], "unchanged");
    }
}
