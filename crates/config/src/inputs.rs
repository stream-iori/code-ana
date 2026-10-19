//! Shared preparation configuration and verified catalog. Never executes tools.
use super::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct InputPreparation {
    pub manifest: String,
    pub entries: Vec<InputEntry>,
}
impl Default for InputPreparation {
    fn default() -> Self {
        Self {
            manifest: ".code-ana/inputs.json".into(),
            entries: vec![],
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct InputEntry {
    pub id: String,
    pub language: String,
    pub root: String,
    pub manifest: Option<String>,
    pub java: JavaPreparation,
    pub rust: RustPreparation,
    pub python: PythonPreparation,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct JavaPreparation {
    pub maven: String,
    pub settings: Option<PathBuf>,
    pub profiles: Vec<String>,
    pub offline: bool,
    pub source_only: bool,
    pub timeout: u64,
    pub extension: Option<PathBuf>,
    pub java_home: Option<PathBuf>,
}
impl Default for JavaPreparation {
    fn default() -> Self {
        Self {
            maven: "mvn".into(),
            settings: None,
            profiles: vec![],
            offline: false,
            source_only: false,
            timeout: 300,
            extension: None,
            java_home: None,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct RustPreparation {
    pub cargo: String,
    pub rustc: String,
    pub cargo_configs: Vec<String>,
    pub build_messages: Option<PathBuf>,
}
impl Default for RustPreparation {
    fn default() -> Self {
        Self {
            cargo: "cargo".into(),
            rustc: "rustc".into(),
            cargo_configs: vec![],
            build_messages: None,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct PythonPreparation {
    pub python_version: String,
    pub platform: String,
    pub extras: Vec<String>,
    pub dependency_roots: Vec<String>,
    pub dependencies: Vec<String>,
    pub environment: Option<String>,
}
impl Default for PythonPreparation {
    fn default() -> Self {
        Self {
            python_version: "3.12".into(),
            platform: "darwin".into(),
            extras: vec![],
            dependency_roots: vec![],
            dependencies: vec![],
            environment: None,
        }
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum InputMode {
    Semantic,
    SourceOnly,
    FilesOnly,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputArtifact {
    pub path: String,
    pub sha256: String,
    pub bytes: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PreparedUnit {
    pub id: String,
    pub language: String,
    pub entries: Vec<String>,
    #[serde(default)]
    pub modules: Vec<String>,
    pub roots: Vec<String>,
    pub mode: InputMode,
    pub reason: Option<String>,
    pub log: Option<String>,
    pub config: Option<InputArtifact>,
    pub artifacts: Vec<InputArtifact>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputCatalog {
    pub schema_version: u32,
    pub config_sha256: String,
    pub units: Vec<PreparedUnit>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EffectiveUnit {
    pub prepared: PreparedUnit,
    pub effective: Option<Box<Effective>>,
}

pub fn load(project: &Path) -> Result<ProjectConfig> {
    let config: ProjectConfig = toml::from_str(std::str::from_utf8(&bounded_read(
        &project.join(".code-ana/config.toml"),
        1 << 20,
    )?)?)?;
    validate(project, &config)?;
    Ok(config)
}
pub fn config_text(config: &ProjectConfig) -> Result<String> {
    Ok(toml::to_string(config)?)
}
pub fn validate_configuration(project: &Path, text: &str) -> Result<Effective> {
    super::resolve_text(project, &Request::default(), true, text, false)
}
pub fn validate(project: &Path, config: &ProjectConfig) -> Result<()> {
    ensure!(
        config.schema_version == 1 && config.kind == "project",
        "INPUT_CONFIG_SCHEMA"
    );
    let preparation = config
        .inputs
        .as_ref()
        .context("INPUT_PREPARATION_CONFIG_REQUIRED")?;
    ensure!(
        safe_path(&preparation.manifest)
            && !preparation.entries.is_empty()
            && preparation.entries.len() <= 256,
        "INPUT_PREPARATION_CONFIG"
    );
    let mut ids = BTreeSet::new();
    let mut roots = BTreeSet::new();
    for entry in &preparation.entries {
        ensure!(
            !entry.id.is_empty()
                && entry.id.len() <= 64
                && entry
                    .id
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-'))
                && ids.insert(&entry.id),
            "INPUT_ENTRY_ID"
        );
        ensure!(relative(&entry.root), "INPUT_ENTRY_ROOT");
        checked_path(project, &entry.root)?;
        ensure!(
            roots.insert((&entry.language, &entry.root, &entry.manifest)),
            "INPUT_DUPLICATE_ENTRY"
        );
        let enabled =
            match entry.language.as_str() {
                "java" => config.languages.java.as_ref().is_some_and(|v| {
                    v.enabled == Some(true) && v.discovery.inputs_manifest.is_none()
                }),
                "rust" => config.languages.rust.as_ref().is_some_and(|v| {
                    v.enabled == Some(true) && v.discovery.inputs_manifest.is_none()
                }),
                "python" => config.languages.python.as_ref().is_some_and(|v| {
                    v.enabled == Some(true) && v.discovery.inputs_manifest.is_none()
                }),
                _ => false,
            };
        ensure!(
            enabled,
            "INPUT_LANGUAGE_DISABLED_OR_LEGACY_MANIFEST: {}",
            entry.language
        );
        if let Some(manifest) = &entry.manifest {
            ensure!(safe_path(manifest), "INPUT_ENTRY_MANIFEST");
        }
        ensure!(
            (1..=3600).contains(&entry.java.timeout),
            "INPUT_PREPARE_TIMEOUT"
        );
        if entry.language == "python" {
            ensure!(
                entry.python.python_version == "3.12" && entry.python.platform == "darwin",
                "PYTHON_TARGET_UNSUPPORTED"
            );
            for dependency in &entry.python.dependencies {
                ensure!(
                    preparation
                        .entries
                        .iter()
                        .any(|e| &e.id == dependency && e.language == "python"),
                    "PYTHON_DEPENDENCY_ENTRY: {dependency}"
                );
            }
        }
    }
    for (language, enabled) in [
        (
            "java",
            config
                .languages
                .java
                .as_ref()
                .is_some_and(|v| v.enabled == Some(true)),
        ),
        (
            "rust",
            config
                .languages
                .rust
                .as_ref()
                .is_some_and(|v| v.enabled == Some(true)),
        ),
        (
            "python",
            config
                .languages
                .python
                .as_ref()
                .is_some_and(|v| v.enabled == Some(true)),
        ),
    ] {
        ensure!(
            !enabled || preparation.entries.iter().any(|v| v.language == language),
            "INPUT_LANGUAGE_ENTRY_REQUIRED: {language}"
        );
    }
    Ok(())
}
pub fn checked_path(project: &Path, path: &str) -> Result<PathBuf> {
    ensure!(relative(path), "INPUT_PATH");
    let mut current = project.to_path_buf();
    for component in Path::new(path).components() {
        current.push(component);
        ensure!(!current.is_symlink(), "INPUT_PATH_SYMLINK: {path}");
    }
    ensure!(
        current.canonicalize()?.starts_with(project),
        "INPUT_PATH_ESCAPE"
    );
    Ok(current)
}
pub fn read_artifact(project: &Path, artifact: &InputArtifact) -> Result<Vec<u8>> {
    let path = checked_path(project, &artifact.path)?;
    ensure!(artifact.bytes <= 32 << 20, "INPUT_ARTIFACT_BUDGET");
    let bytes = bounded_read(&path, 32 << 20)?;
    ensure!(
        bytes.len() as u64 == artifact.bytes && hash(&bytes) == artifact.sha256,
        "INPUT_ARTIFACT_STALE: {}",
        artifact.path
    );
    Ok(bytes)
}
pub(super) fn resolve(
    project: &Path,
    request: &Request,
    semantic: bool,
    config: ProjectConfig,
    text: &str,
) -> Result<Effective> {
    validate(project, &config)?;
    let mut basic = config.clone();
    basic.inputs = None;
    // Preparation metadata must not affect FILES/text policy or force native inputs.
    let mut effective =
        super::resolve_text(project, request, false, &toml::to_string(&basic)?, false)?;
    effective.config.inputs = config.inputs.clone();
    effective.input_hash = hash(text.as_bytes());
    effective.config_digest = digest(&config);
    effective.semantic_digest = digest(&(&config.languages, &config.modules, &config.inputs));
    if !semantic {
        return Ok(effective);
    }
    let preparation = config.inputs.as_ref().unwrap();
    let path = checked_path(project, &preparation.manifest)
        .context("INPUT_PREPARATION_REQUIRED: run index --prepare")?;
    let catalog: InputCatalog = serde_json::from_slice(&bounded_read(&path, 32 << 20)?)?;
    ensure!(
        catalog.schema_version == 1
            && catalog.config_sha256 == effective.input_hash
            && !catalog.units.is_empty()
            && catalog.units.len() <= 256,
        "INPUT_CATALOG_STALE: run index --prepare"
    );
    let mut seen = BTreeSet::new();
    effective.config.modules.clear();
    for unit in catalog.units {
        ensure!(
            seen.insert(unit.id.clone()) && !unit.entries.is_empty(),
            "INPUT_CATALOG_DUPLICATE_UNIT"
        );
        ensure!(
            unit.entries.contains(&unit.id)
                && unit.entries.len() == unit.roots.len()
                && unit
                    .entries
                    .iter()
                    .zip(&unit.roots)
                    .all(|(id, root)| preparation
                        .entries
                        .iter()
                        .any(|e| &e.id == id && &e.root == root && e.language == unit.language)),
            "INPUT_CATALOG_UNIT_IDENTITY"
        );
        for artifact in &unit.artifacts {
            read_artifact(project, artifact)?;
        }
        let child = if let Some(artifact) = &unit.config {
            ensure!(unit.mode != InputMode::FilesOnly, "INPUT_UNIT_MODE");
            let bytes = read_artifact(project, artifact)?;
            let child =
                super::resolve_text(project, request, true, std::str::from_utf8(&bytes)?, false)?;
            ensure!(child.config.inputs.is_none(), "INPUT_RECURSIVE_CATALOG");
            effective
                .config
                .modules
                .extend(child.config.modules.clone());
            Some(Box::new(child))
        } else {
            ensure!(
                unit.mode == InputMode::FilesOnly && unit.reason.is_some(),
                "INPUT_UNIT_MODE"
            );
            None
        };
        effective.input_units.push(EffectiveUnit {
            prepared: unit,
            effective: child,
        });
    }
    let mut ids = BTreeSet::new();
    let mut roots = BTreeSet::new();
    for module in &effective.config.modules {
        ensure!(
            ids.insert(&module.id),
            "INPUT_DUPLICATE_MODULE: {}",
            module.id
        );
        ensure!(
            roots.insert((&module.language, &module.root)),
            "INPUT_OVERLAPPING_NATIVE_WORKSPACE: {}",
            module.root
        );
    }
    let entries = effective
        .input_units
        .iter()
        .flat_map(|u| &u.prepared.entries)
        .collect::<BTreeSet<_>>();
    ensure!(
        entries.len() == preparation.entries.len()
            && effective
                .input_units
                .iter()
                .map(|u| u.prepared.entries.len())
                .sum::<usize>()
                == entries.len()
            && preparation.entries.iter().all(|e| entries.contains(&e.id)),
        "INPUT_CATALOG_ENTRY_COVERAGE"
    );
    effective.semantic_digest = digest(&(&effective.semantic_digest, &effective.input_units));
    Ok(effective)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn project() -> (tempfile::TempDir, ProjectConfig) {
        let temp = tempfile::tempdir().unwrap();
        std::fs::create_dir(temp.path().join(".code-ana")).unwrap();
        let config: ProjectConfig = toml::from_str("schema_version=1\nkind='project'\n[languages.python]\nenabled=true\n[inputs]\n[[inputs.entries]]\nid='app'\nlanguage='python'\nroot='.'\n").unwrap();
        (temp, config)
    }
    #[test]
    fn readiness_does_not_require_catalog_and_preserves_default_scopes() {
        let (temp, config) = project();
        let root = temp.path().canonicalize().unwrap();
        let text = config_text(&config).unwrap();
        std::fs::write(root.join(".code-ana/config.toml"), &text).unwrap();
        let effective = super::resolve(&root, &Request::default(), false, config, &text).unwrap();
        assert_eq!(effective.config.scan.scopes, ["MAIN"]);
        assert!(!root.join(".code-ana/inputs.json").exists());
    }
    #[test]
    fn legacy_conflicts_disabled_languages_and_duplicate_entries_are_rejected() {
        let (temp, mut config) = project();
        config
            .languages
            .python
            .as_mut()
            .unwrap()
            .discovery
            .inputs_manifest = Some("old.json".into());
        assert!(validate(temp.path(), &config).is_err());
        config
            .languages
            .python
            .as_mut()
            .unwrap()
            .discovery
            .inputs_manifest = None;
        let entry = config.inputs.as_ref().unwrap().entries[0].clone();
        config.inputs.as_mut().unwrap().entries.push(entry);
        assert!(validate(temp.path(), &config).is_err());
    }
    #[test]
    fn artifact_bytes_and_every_symlink_component_are_verified() {
        let (temp, _) = project();
        let root = temp.path().canonicalize().unwrap();
        std::fs::write(root.join("input.json"), b"before").unwrap();
        let artifact = InputArtifact {
            path: "input.json".into(),
            sha256: hash(b"before"),
            bytes: 6,
        };
        read_artifact(&root, &artifact).unwrap();
        std::fs::write(root.join("input.json"), b"after!").unwrap();
        assert!(read_artifact(&root, &artifact).is_err());
        std::os::unix::fs::symlink(root.join(".code-ana"), root.join("alias")).unwrap();
        std::fs::write(root.join(".code-ana/hidden"), b"value").unwrap();
        assert!(checked_path(&root, "alias/hidden").is_err());
    }
}
