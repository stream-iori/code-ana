//! Validate externally prepared Rust inputs; indexing never invokes Cargo.
use super::*;
use codeana_model::rust::Inputs;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PrepareRequest {
    pub config: ProjectConfig,
    pub paths: Vec<InputPath>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputPath {
    pub path: String,
    pub scope: String,
    pub module: Option<String>,
    pub dependency: bool,
}
#[derive(Serialize)]
pub struct PreparedPaths {
    pub policy: String,
    pub selected: Vec<bool>,
}

/// Shared by the explicit Python exporter and index capture: one glob engine.
pub fn prepare(request: PrepareRequest) -> Result<PreparedPaths> {
    ensure!(request.paths.len() <= 100_000, "RUST_SOURCE_BUDGET");
    let filter = InputFilter::new(&request.config)?;
    Ok(PreparedPaths {
        policy: policy(&request.config)?,
        selected: request.paths.iter().map(|p| filter.matches(p)).collect(),
    })
}

pub fn policy(config: &ProjectConfig) -> Result<String> {
    let language = config
        .languages
        .rust
        .as_ref()
        .context("RUST_LANGUAGE_REQUIRED")?;
    let mut scan = config.scan.clone();
    if scan.scopes.is_empty() {
        scan.scopes.push("MAIN".into());
    }
    if scan.include.is_empty() {
        scan.include.push("**".into());
    }
    let mut modules = config
        .modules
        .iter()
        .filter(|m| m.language == "rust")
        .collect::<Vec<_>>();
    modules.sort_by_key(|m| &m.id);
    // Execution budgets, report policy and the manifest location do not change meaning.
    Ok(digest(&(
        2,
        "direct-dependencies",
        "no-explicit-macro-expansion",
        if language.mode.is_empty() {
            "semantic"
        } else {
            &language.mode
        },
        &language.analysis,
        &language.dependencies,
        scan,
        modules,
    )))
}

struct Selection {
    include: globset::GlobSet,
    exclude: globset::GlobSet,
    text_only: globset::GlobSet,
    scopes: Vec<String>,
}
impl Selection {
    fn new(scan: &Scan) -> Result<Self> {
        let all = vec!["**".to_owned()];
        Ok(Self {
            include: glob(if scan.include.is_empty() {
                &all
            } else {
                &scan.include
            })?,
            exclude: glob(&scan.exclude)?,
            text_only: glob(&scan.text_only)?,
            scopes: scan.scopes.clone(),
        })
    }
    fn matches(&self, path: &InputPath) -> bool {
        (path.scope.is_empty() || self.scopes.is_empty() || self.scopes.contains(&path.scope))
            && self.include.is_match(&path.path)
            && !self.exclude.is_match(&path.path)
            && !self.text_only.is_match(&path.path)
    }
}
pub struct InputFilter {
    project: Selection,
    modules: BTreeMap<String, Selection>,
    dependency: Selection,
}
impl InputFilter {
    pub fn new(config: &ProjectConfig) -> Result<Self> {
        let mut scan = config.scan.clone();
        if scan.scopes.is_empty() {
            scan.scopes.push("MAIN".into());
        }
        let deps = config
            .languages
            .rust
            .as_ref()
            .and_then(|l| l.dependencies.as_ref())
            .cloned()
            .unwrap_or_default();
        Ok(Self {
            project: Selection::new(&scan)?,
            modules: config
                .modules
                .iter()
                .filter(|m| m.language == "rust")
                .map(|m| Ok((m.id.clone(), Selection::new(&m.scan)?)))
                .collect::<Result<_>>()?,
            dependency: Selection::new(&Scan {
                include: deps.include,
                exclude: deps.exclude,
                ..Default::default()
            })?,
        })
    }
    pub fn matches(&self, path: &InputPath) -> bool {
        if path.dependency {
            return self.dependency.matches(path);
        }
        self.project.matches(path)
            && path
                .module
                .as_ref()
                .and_then(|m| self.modules.get(m))
                .is_none_or(|m| m.matches(path))
    }
}

pub fn read(root: &Path, path: &str) -> Result<Inputs> {
    ensure!(safe_path(path), "RUST_INPUT_PATH");
    let location = root.join(path);
    if !location.is_file() {
        return Err(DomainError::new(
            ErrorCode::BuildInputRequired,
            format!("Rust inputs missing: {path}; run codeana-export-rust-inputs explicitly"),
        )
        .into());
    }
    ensure!(
        location.canonicalize()?.starts_with(root),
        "RUST_INPUT_PATH"
    );
    let bytes = bounded_read(&location, 32 << 20)?;
    #[derive(Deserialize)]
    struct Schema {
        schema_version: u32,
    }
    let schema: Schema = serde_json::from_slice(&bytes)?;
    ensure!(
        schema.schema_version == codeana_model::rust::INPUT_SCHEMA,
        "RUST_INPUT_SCHEMA: re-export Rust inputs for schema 2"
    );
    let inputs: Inputs = serde_json::from_slice(&bytes)?;
    inputs.validate().map_err(anyhow::Error::msg)?;
    for (path, expected) in inputs.controls.iter().chain(&inputs.generated) {
        let file = root.join(path);
        ensure!(
            !file.is_symlink() && file.canonicalize()?.starts_with(root),
            "RUST_INPUT_PATH"
        );
        ensure!(
            hash(bounded_read(&file, 32 << 20)?) == *expected,
            "RUST_INPUTS_STALE: {path}; re-export Rust inputs"
        );
    }
    Ok(inputs)
}

pub fn discover(config: &mut ProjectConfig, inputs: &Inputs) {
    for input in inputs.crates.iter().filter(|c| c.publish) {
        if !config
            .modules
            .iter()
            .any(|m| m.language == "rust" && m.id == input.module)
        {
            config.modules.push(Module {
                id: input.module.clone(),
                language: "rust".into(),
                root: input.directory.clone(),
                manifest: Some("Cargo.toml".into()),
                ..Default::default()
            });
        }
    }
}

pub(super) fn capture(
    root: &Path,
    effective: &Effective,
    manifest: &mut Manifest,
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    spool: &std::sync::Arc<tempfile::TempDir>,
) -> Result<()> {
    let Some(inputs) = &effective.rust_inputs else {
        return Ok(());
    };
    let config = &effective.config;
    let path = config
        .languages
        .rust
        .as_ref()
        .and_then(|r| r.discovery.inputs_manifest.as_deref())
        .context("RUST_INPUT_PATH")?;
    let bytes = bounded_read(&root.join(path), 32 << 20)?;
    ensure!(
        digest(&serde_json::from_slice::<Inputs>(&bytes)?) == digest(inputs),
        "RUST_INPUTS_CHANGED"
    );
    let filter = InputFilter::new(config)?;
    let mut selected = BTreeMap::<String, BTreeSet<usize>>::new();
    for (index, input) in inputs.crates.iter().enumerate() {
        let mut candidates = BTreeSet::new();
        if input.publish {
            let directory = root.join(&input.directory);
            ensure!(
                directory.canonicalize()?.starts_with(root),
                "RUST_INPUT_PATH"
            );
            for entry in walkdir::WalkDir::new(&directory)
                .follow_links(false)
                .into_iter()
                .filter_entry(|e| {
                    e.depth() == 0
                        || !matches!(
                            e.file_name().to_str(),
                            Some("target" | ".git" | "node_modules" | ".code-ana")
                        )
                })
            {
                let entry = entry?;
                if entry.path().extension().is_none_or(|v| v != "rs") || entry.file_type().is_dir()
                {
                    continue;
                }
                let path = entry
                    .path()
                    .strip_prefix(root)?
                    .to_str()
                    .context("RUST_NON_UTF8_PATH")?
                    .to_owned();
                if filter.matches(&InputPath {
                    path: path.clone(),
                    scope: input.scope.clone(),
                    module: Some(input.module.clone()),
                    dependency: false,
                }) {
                    ensure!(entry.file_type().is_file(), "RUST_SOURCE_SYMLINK");
                    candidates.insert(path);
                }
            }
            for path in inputs.generated.keys().filter(|p| {
                input
                    .environment
                    .get("OUT_DIR")
                    .is_some_and(|d| Path::new(p).starts_with(d))
            }) {
                if filter.matches(&InputPath {
                    path: path.clone(),
                    scope: input.scope.clone(),
                    module: Some(input.module.clone()),
                    dependency: false,
                }) {
                    candidates.insert(path.clone());
                }
            }
        } else {
            candidates.extend(inputs.dependency_sources[&input.id].iter().cloned());
        }
        ensure!(
            candidates.contains(&input.root),
            "RUST_CRATE_ROOT_MISSING: {}",
            input.root
        );
        for path in candidates {
            selected.entry(path).or_default().insert(index);
            ensure!(selected.len() <= 100_000, "RUST_SOURCE_BUDGET");
        }
    }
    for (path, owners) in selected {
        let file = root.join(&path);
        ensure!(
            !file.is_symlink() && file.canonicalize()?.starts_with(root),
            "RUST_INPUT_PATH"
        );
        let bytes = bounded_read(&file, 8 << 20)?;
        let digest = hash(&bytes);
        if !blobs.contains_key(&digest) {
            *total += bytes.len();
            blobs.insert(digest.clone(), CapturedBlob::bytes(spool, &bytes)?);
        }
        ensure!(*total <= MAX_CAPTURE_BYTES, "RUST_CAPTURE_BUDGET");
        manifest.files.retain(|f| f.path != path);
        for index in owners {
            let owner = &inputs.crates[index];
            manifest.files.push(SourceUnit {
                path: path.clone(),
                hash: digest.clone(),
                size: bytes.len() as u64,
                module: owner.module.clone(),
                world: owner.world.clone(),
                scope: owner.scope.clone(),
                language: "rust".into(),
                auxiliary: !owner.publish,
            });
        }
    }
    for control in std::iter::once(path.to_owned()).chain(inputs.controls.keys().cloned()) {
        let bytes = bounded_read(&root.join(&control), 32 << 20)?;
        if let Some(expected) = inputs.controls.get(&control) {
            ensure!(hash(&bytes) == *expected, "RUST_INPUTS_STALE");
        }
        if !manifest.files.iter().any(|f| f.path == control) {
            add_captured(
                manifest,
                blobs,
                total,
                spool,
                control,
                bytes,
                (
                    String::new(),
                    "CONTROL".into(),
                    String::new(),
                    String::new(),
                ),
            )?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn filters_are_shared_and_budgets_do_not_change_input_policy() -> Result<()> {
        let mut config: ProjectConfig = toml::from_str(
            r#"
schema_version=1
kind="project"
[languages.rust]
enabled=true
[languages.rust.dependencies]
include=["src/**", "**/tests/**"]
exclude=["**/tests/**"]
[scan]
include=["app/**"]
exclude=["**/excluded/**"]
"#,
        )?;
        let policy_before = policy(&config)?;
        config.resources.index.worker_memory_bytes = Some(512 << 20);
        assert_eq!(policy_before, policy(&config)?);
        let filter = InputFilter::new(&config)?;
        let path = |p: &str, dependency| InputPath {
            path: p.into(),
            scope: "MAIN".into(),
            module: None,
            dependency,
        };
        assert!(filter.matches(&path("app/src/lib.rs", false)));
        assert!(!filter.matches(&path("app/excluded/large.rs", false)));
        assert!(filter.matches(&path("src/lib.rs", true)));
        assert!(!filter.matches(&path("tests/data.rs", true)));
        assert!(!filter.matches(&path("src/tests/data.rs", true)));
        config.scan.exclude.push("app/generated/**".into());
        assert_ne!(policy_before, policy(&config)?);
        config.scan.include = vec!["[".into()];
        assert!(InputFilter::new(&config).is_err());
        Ok(())
    }
    fn inputs(root: &Path) -> Inputs {
        std::fs::write(root.join("Cargo.toml"), b"fixture").unwrap();
        serde_json::from_value(serde_json::json!({"schema_version":2,"policy":hash(b"fixture-policy"),"dependency_sources":{},"omitted_dependencies":{},"toolchain":"fixture","crates":[{"id":"app","name":"app","module":"app","world":"main","scope":"MAIN","root":"src/lib.rs","directory":"src","edition":"2024","target":"fixture","cfg":[],"dependencies":{},"publish":true}],"controls":{"Cargo.toml":hash(b"fixture")}})).unwrap()
    }
    #[test]
    fn stale_controls_unsafe_roots_and_cycles_are_rejected() -> Result<()> {
        let directory = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
        let root = directory.path().canonicalize()?;
        let mut value = inputs(&root);
        std::fs::write(root.join("inputs.json"), serde_json::to_vec(&value)?)?;
        assert!(read(&root, "inputs.json").is_ok());
        std::fs::write(root.join("Cargo.toml"), b"changed")?;
        assert!(
            read(&root, "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("RUST_INPUTS_STALE")
        );
        value.crates[0].root = "../outside.rs".into();
        assert!(value.validate().is_err());
        value.crates[0].root = "src/lib.rs".into();
        value.crates[0]
            .dependencies
            .insert("app".into(), "app".into());
        assert!(value.validate().is_err());
        assert!(read(&root, "../inputs.json").is_err());
        Ok(())
    }

    #[test]
    fn old_schema_and_transitive_source_graph_are_rejected() -> Result<()> {
        let directory = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
        let root = directory.path().canonicalize()?;
        let mut value = inputs(&root);
        value.schema_version = 1;
        std::fs::write(root.join("inputs.json"), serde_json::to_vec(&value)?)?;
        assert!(
            read(&root, "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("re-export")
        );
        value.schema_version = 2;
        let mut dependency = value.crates[0].clone();
        dependency.id = "dep".into();
        dependency.world = "dep".into();
        dependency.publish = false;
        dependency.root = "dep/lib.rs".into();
        dependency.directory = "dep".into();
        value.crates[0]
            .dependencies
            .insert("dep".into(), "dep".into());
        value
            .dependency_sources
            .insert("dep".into(), vec![dependency.root.clone()]);
        value.crates.push(dependency.clone());
        assert!(value.validate().is_ok());
        dependency.id = "deep".into();
        dependency.world = "deep".into();
        value.crates[1]
            .dependencies
            .insert("deep".into(), "deep".into());
        value
            .dependency_sources
            .insert("deep".into(), vec![dependency.root.clone()]);
        value.crates.push(dependency);
        assert_eq!(value.validate().unwrap_err(), "RUST_DEPENDENCY_DEPTH");
        Ok(())
    }

    #[test]
    fn project_memory_range_is_validated_without_loading_inputs() -> Result<()> {
        let directory = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
        let root = directory.path().canonicalize()?;
        std::fs::create_dir(root.join(".code-ana"))?;
        for (bytes, valid) in [
            (0, false),
            (16 * 1024 * 1024 - 1, false),
            (16 * 1024 * 1024, true),
            (256 * 1024 * 1024, true),
            (4u64 * 1024 * 1024 * 1024, true),
            (4u64 * 1024 * 1024 * 1024 + 1, false),
        ] {
            std::fs::write(
                root.join(".code-ana/config.toml"),
                format!(
                    "schema_version=1\nkind=\"project\"\n[resources.index]\nworker_memory_bytes={bytes}\n"
                ),
            )?;
            assert_eq!(
                resolve_files(&root, &Request::default()).is_ok(),
                valid,
                "{bytes}"
            );
        }
        Ok(())
    }
}
