//! Python worker input construction shared by ordinary and prepared entry points.
use super::*;
use codeana_contracts::worker::PythonConfig;

// Preserve the entry points' existing error identities during structural migration.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum InputMode {
    Standard,
    Prepared,
}

pub(super) struct PreparedPython {
    pub descriptor: PythonConfig,
    pub config: python::AnalysisConfig,
    pub source_bytes: u64,
}

pub(super) fn prepare_inputs(
    capture: &Capture,
    scratch: &Path,
    mode: InputMode,
) -> Result<PreparedPython> {
    let inputs = capture
        .effective
        .python_inputs
        .clone()
        .context("PYTHON_INPUTS_REQUIRED")?;
    let mut files = Vec::new();
    for root in &inputs.roots {
        for file in &root.files {
            let source = codeana_config::python_inputs::source_path(root, &file.path);
            let blob = capture
                .blobs
                .get(&file.sha256)
                .context("PYTHON_CAPTURE_MISSING")?;
            files.push(python::FrozenFile {
                root: root.id.clone(),
                relative: file.path.clone(),
                source,
                input: blob.as_ref().to_path_buf(),
                sha256: file.sha256.clone(),
                bytes: file.bytes,
            });
        }
    }
    let resources = &capture.effective.config.resources.index;
    let result_bytes = resources.python_result_bytes.unwrap_or(MAX_RESULT as u64);
    let fact_limit = resources.python_fact_limit.unwrap_or(MAX_FACTS as u64);
    let scratch_bytes = resources.python_scratch_bytes.unwrap_or(512 << 20);
    ensure!(
        (65536..=1073741824).contains(&result_bytes)
            && (1..=2000000).contains(&fact_limit)
            && (mode == InputMode::Prepared || scratch_bytes >= result_bytes)
            && scratch_bytes <= 32u64 << 30,
        "PYTHON_OUTPUT_LIMIT_RANGE"
    );
    let source_bytes = files.iter().try_fold(0u64, |n, file| {
        n.checked_add(file.bytes).context("PYTHON_SCRATCH_BUDGET")
    })?;
    if mode == InputMode::Standard {
        ensure!(
            source_bytes
                .checked_add(result_bytes)
                .is_some_and(|n| n <= scratch_bytes),
            "PYTHON_SCRATCH_BUDGET"
        );
    }
    let config = python::AnalysisConfig {
        inputs,
        files,
        excluded: capture
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "python" && s.auxiliary)
            .map(|s| (s.path.clone(), s.world.clone()))
            .collect(),
        scratch: scratch.into(),
        result_bytes,
        fact_limit,
        scratch_bytes,
    };
    let input = scratch.join("inputs.json");
    let bytes = serde_json::to_vec(&config)?;
    if mode == InputMode::Standard {
        ensure!(bytes.len() <= 32 << 20, "PYTHON_INPUT_BUDGET");
    }
    ensure!(
        bytes.len() <= 32 << 20
            && source_bytes
                .checked_add(result_bytes)
                .and_then(|n| n.checked_add(bytes.len() as u64))
                .is_some_and(|n| n <= scratch_bytes),
        "PYTHON_SCRATCH_BUDGET"
    );
    let descriptor = PythonConfig {
        input: match mode {
            InputMode::Standard => input.to_string_lossy().into_owned(),
            InputMode::Prepared => input.to_str().context("INPUT_PATH_UTF8")?.into(),
        },
        sha256: hash(&bytes),
        bytes: bytes.len() as u64,
        result_bytes,
        fact_limit,
        scratch_bytes,
    };
    std::fs::write(input, bytes)?;
    Ok(PreparedPython {
        descriptor,
        config,
        source_bytes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn capture() -> (tempfile::TempDir, Capture) {
        let project = tempfile::tempdir().unwrap();
        let root = project.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        std::fs::create_dir(root.join("src")).unwrap();
        let source = b"def stock():\n    return 1\n";
        std::fs::write(root.join("src/api.py"), source).unwrap();
        let inputs = python::Inputs {
            schema_version: python::INPUT_SCHEMA,
            roots: vec![python::InputRoot {
                id: "app".into(),
                path: "src".into(),
                publish: true,
                module: None,
                files: vec![python::InputFile {
                    path: "api.py".into(),
                    sha256: hash(source),
                    bytes: source.len() as u64,
                }],
            }],
            contexts: vec![python::Context {
                id: "main".into(),
                module: "app".into(),
                scope: "MAIN".into(),
                python_version: "3.12".into(),
                platform: "darwin".into(),
                roots: vec!["app".into()],
                publish_roots: None,
                gaps: vec![],
            }],
            controls: vec![],
            metadata: None,
            module_metadata: BTreeMap::new(),
        };
        std::fs::write(
            root.join(".code-ana/python-inputs.json"),
            serde_json::to_vec(&inputs).unwrap(),
        )
        .unwrap();
        std::fs::write(root.join(".code-ana/config.toml"),
            "schema_version=1\nkind=\"project\"\n[languages.python]\nenabled=true\n[languages.python.discovery]\ninputs_manifest=\".code-ana/python-inputs.json\"\n").unwrap();
        let capture = codeana_config::capture(&root, &Request::default()).unwrap();
        (project, capture)
    }

    #[test]
    fn entry_points_deliver_identical_frozen_inputs_and_descriptor() {
        let (_project, capture) = capture();
        let scratch = tempfile::tempdir().unwrap();
        let ordinary = prepare_inputs(&capture, scratch.path(), InputMode::Standard).unwrap();
        let bytes = std::fs::read(&ordinary.descriptor.input).unwrap();
        let prepared = prepare_inputs(&capture, scratch.path(), InputMode::Prepared).unwrap();
        assert_eq!(bytes, std::fs::read(&prepared.descriptor.input).unwrap());
        assert_eq!(ordinary.descriptor, prepared.descriptor);
        assert_eq!(ordinary.source_bytes, prepared.source_bytes);
        for file in ordinary.config.files {
            assert_eq!(hash_file(&file.input).unwrap(), file.sha256);
            assert!(file.input.starts_with(std::env::temp_dir()));
        }
    }

    #[test]
    fn both_entry_points_reject_missing_captured_sources() {
        let (_project, mut capture) = capture();
        capture.blobs.clear();
        let scratch = tempfile::tempdir().unwrap();
        for mode in [InputMode::Standard, InputMode::Prepared] {
            let error = prepare_inputs(&capture, scratch.path(), mode)
                .err()
                .unwrap();
            assert!(error.to_string().contains("PYTHON_CAPTURE_MISSING"));
            assert!(!scratch.path().join("inputs.json").exists());
        }
    }

    #[test]
    fn entry_specific_budget_errors_are_preserved_before_writing() {
        let (_project, mut capture) = capture();
        capture
            .effective
            .config
            .resources
            .index
            .python_scratch_bytes = Some(1);
        let scratch = tempfile::tempdir().unwrap();
        for (mode, code) in [
            (InputMode::Standard, "PYTHON_OUTPUT_LIMIT_RANGE"),
            (InputMode::Prepared, "PYTHON_SCRATCH_BUDGET"),
        ] {
            let error = prepare_inputs(&capture, scratch.path(), mode)
                .err()
                .unwrap();
            assert!(error.to_string().contains(code));
            assert!(!scratch.path().join("inputs.json").exists());
        }
    }
}
