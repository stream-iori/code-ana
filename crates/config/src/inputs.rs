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
    pub typescript: TypeScriptPreparation,
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
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TypeScriptPreparation {
    pub scope: String,
    pub timeout: u64,
}
impl Default for TypeScriptPreparation {
    fn default() -> Self {
        Self {
            scope: "MAIN".into(),
            timeout: 120,
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
    pub backend: AnalysisBackend,
    pub source_languages: Vec<SourceLanguage>,
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
/// Prepared native view: task policy is inherited exclusively from the project.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UnitConfig {
    pub discovery: Discovery,
    pub languages: Languages,
    pub modules: Vec<Module>,
}
impl UnitConfig {
    pub fn from_project(config: &ProjectConfig) -> Self {
        Self {
            discovery: config.discovery.clone(),
            languages: config.languages.clone(),
            modules: config.modules.clone(),
        }
    }
    fn with_task(self, task: std::sync::Arc<TaskConfig>) -> ProjectConfig {
        ProjectConfig {
            task,
            discovery: self.discovery,
            languages: self.languages,
            modules: self.modules,
            inputs: None,
        }
    }
}
pub fn unit_config_text(config: &ProjectConfig) -> Result<String> {
    Ok(toml::to_string(&UnitConfig::from_project(config))?)
}
/// The supplied parent must have already resolved common task policy.
pub fn resolve_unit(
    project: &Path,
    request: &Request,
    parent: &ResolvedContext,
    text: &str,
) -> Result<ResolvedContext> {
    resolve_unit_using(project, request, parent, text, &LiveInputs)
}
fn resolve_unit_using(
    project: &Path,
    request: &Request,
    parent: &ResolvedContext,
    text: &str,
    reader: &dyn InputReader,
) -> Result<ResolvedContext> {
    let native: UnitConfig = toml::from_str(text)?;
    let child = super::resolution::resolve_config_using(
        project,
        request,
        true,
        native.with_task(parent.config.task.clone()),
        text,
        Some(&parent.origins),
        reader,
    )?;
    ensure!(
        std::sync::Arc::ptr_eq(&child.config.task, &parent.config.task),
        "INPUT_UNIT_TASK_POLICY_MUTATION"
    );
    ensure!(child.inputs.len() == 1, "INPUT_UNIT_BACKEND_REQUIRED");
    Ok(child.context)
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResolvedUnit {
    pub prepared: PreparedUnit,
    pub context: Option<ResolvedContext>,
}

/// Distinct compiler contexts can share an identical script module policy.
/// Their files/worlds remain in separate unit views; conflicting policies and
/// duplicate module identities in all other backends are still rejected.
pub fn merge_prepared_modules(
    current: &mut Vec<Module>,
    additions: &[Module],
    backend: AnalysisBackend,
) -> Result<()> {
    for module in additions {
        if let Some(previous) = current.iter().find(|existing| existing.id == module.id) {
            ensure!(
                backend == AnalysisBackend::TypeScript
                    && SourceLanguage::lookup(&previous.language)
                        .is_some_and(|language| language.backend() == backend)
                    && SourceLanguage::lookup(&module.language)
                        .is_some_and(|language| language.backend() == backend)
                    && digest(previous) == digest(module),
                "INPUT_DUPLICATE_MODULE: {}",
                module.id
            );
        } else {
            current.push(module.clone());
        }
    }
    Ok(())
}

/// Prepared inputs require the exact configuration bytes they were exported from.
fn matches_configuration(catalog: &InputCatalog, _config: &ProjectConfig, raw_hash: &str) -> bool {
    catalog.config_sha256 == raw_hash
}

pub fn load(project: &Path) -> Result<ProjectConfig> {
    let config: ProjectConfig = super::parse_project(std::str::from_utf8(&bounded_read(
        &project.join(".code-ana/config.toml"),
        1 << 20,
    )?)?)?;
    validate(project, &config)?;
    Ok(config)
}
pub fn config_text(config: &ProjectConfig) -> Result<String> {
    Ok(toml::to_string(config)?)
}
pub fn resolve_task_configuration(project: &Path, config: ProjectConfig) -> Result<Effective> {
    let text = config_text(&config)?;
    super::resolution::resolve_config(project, &Request::default(), false, config, &text, None)
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
                "javascript" | "typescript" => {
                    let source = SourceLanguage::lookup(&entry.language).unwrap();
                    let enabled = match source {
                        SourceLanguage::JavaScript => config.languages.javascript.as_ref(),
                        _ => config.languages.typescript.as_ref(),
                    }
                    .is_some_and(|language| language.enabled == Some(true));
                    enabled
                        && crate::typescript_inputs::configuration(config)?
                            .is_some_and(|(discovery, _)| discovery.inputs_manifest.is_none())
                }
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
        if matches!(entry.language.as_str(), "javascript" | "typescript") {
            ensure!(
                matches!(entry.typescript.scope.as_str(), "MAIN" | "TEST")
                    && (1..=3600).contains(&entry.typescript.timeout),
                "TYPESCRIPT_PREPARATION_OPTIONS"
            );
        }
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
    if crate::typescript_inputs::configuration(config)?.is_some() {
        ensure!(
            preparation
                .entries
                .iter()
                .any(|entry| matches!(entry.language.as_str(), "javascript" | "typescript")),
            "INPUT_LANGUAGE_ENTRY_REQUIRED: typescript"
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
    read_artifact_using(project, artifact, &LiveInputs)
}
fn read_artifact_using(
    project: &Path,
    artifact: &InputArtifact,
    reader: &dyn InputReader,
) -> Result<Vec<u8>> {
    let path = checked_path(project, &artifact.path)?;
    ensure!(artifact.bytes <= 32 << 20, "INPUT_ARTIFACT_BUDGET");
    let bytes = reader.read(&path, 32 << 20)?;
    ensure!(
        bytes.len() as u64 == artifact.bytes && hash(&bytes) == artifact.sha256,
        "INPUT_ARTIFACT_STALE: {}",
        artifact.path
    );
    Ok(bytes)
}
#[cfg(test)]
pub(super) fn resolve(
    project: &Path,
    request: &Request,
    semantic: bool,
    config: ProjectConfig,
    text: &str,
) -> Result<Effective> {
    resolve_using(project, request, semantic, config, text, &LiveInputs)
}
pub(super) fn resolve_using(
    project: &Path,
    request: &Request,
    semantic: bool,
    config: ProjectConfig,
    text: &str,
    reader: &dyn InputReader,
) -> Result<Effective> {
    validate(project, &config)?;
    let mut basic = config.clone();
    basic.inputs = None;
    // Preparation metadata must not affect FILES/text policy or force native inputs.
    let mut effective = super::resolution::resolve_config_using(
        project, request, false, basic, text, None, reader,
    )?;
    effective.config.inputs = config.inputs.clone();
    effective.input_hash = hash(text.as_bytes());
    effective.config_digest = digest(&config);
    effective.semantic_digest = semantic::configuration(&config);
    if !semantic {
        return Ok(effective);
    }
    let preparation = config.inputs.as_ref().unwrap();
    let path = checked_path(project, &preparation.manifest)
        .context("INPUT_PREPARATION_REQUIRED: run index --prepare")?;
    let catalog_bytes = reader.read(&path, 32 << 20)?;
    #[derive(Deserialize)]
    struct CatalogVersion {
        schema_version: u32,
    }
    ensure!(
        serde_json::from_slice::<CatalogVersion>(&catalog_bytes)?.schema_version == 3,
        "INPUT_CATALOG_STALE: run index --prepare"
    );
    let catalog: InputCatalog = serde_json::from_slice(&catalog_bytes)?;
    ensure!(
        catalog.schema_version == 3
            && matches_configuration(&catalog, &config, &effective.input_hash)
            && !catalog.units.is_empty()
            && catalog.units.len() <= 256,
        "INPUT_CATALOG_STALE: run index --prepare"
    );
    let mut seen = BTreeSet::new();
    effective.config.modules.clear();
    for unit in catalog.units {
        reader.enter_unit(&AnalysisUnitId::from(unit.id.clone()))?;
        ensure!(
            seen.insert(unit.id.clone()) && !unit.entries.is_empty(),
            "INPUT_CATALOG_DUPLICATE_UNIT"
        );
        ensure!(
            (unit.backend == AnalysisBackend::TypeScript || unit.entries.contains(&unit.id))
                && !unit.source_languages.is_empty()
                && unit
                    .source_languages
                    .iter()
                    .all(|source| unit.backend.supports(*source))
                && unit.source_languages.iter().collect::<BTreeSet<_>>().len()
                    == unit.source_languages.len()
                && unit.source_languages.iter().all(|language| match language {
                    SourceLanguage::Java => config
                        .languages
                        .java
                        .as_ref()
                        .is_some_and(|language| language.enabled == Some(true)),
                    SourceLanguage::Rust => config
                        .languages
                        .rust
                        .as_ref()
                        .is_some_and(|language| language.enabled == Some(true)),
                    SourceLanguage::Python => config
                        .languages
                        .python
                        .as_ref()
                        .is_some_and(|language| language.enabled == Some(true)),
                    SourceLanguage::JavaScript => config
                        .languages
                        .javascript
                        .as_ref()
                        .is_some_and(|language| language.enabled == Some(true)),
                    SourceLanguage::TypeScript => config
                        .languages
                        .typescript
                        .as_ref()
                        .is_some_and(|language| language.enabled == Some(true)),
                })
                && unit.entries.iter().collect::<BTreeSet<_>>().len() == unit.entries.len()
                && unit.entries.len() == unit.roots.len()
                && unit
                    .entries
                    .iter()
                    .zip(&unit.roots)
                    .all(|(id, root)| preparation.entries.iter().any(|e| &e.id == id
                        && &e.root == root
                        && SourceLanguage::lookup(&e.language)
                            .is_some_and(|source| source.backend() == unit.backend))),
            "INPUT_CATALOG_UNIT_IDENTITY"
        );
        for artifact in &unit.artifacts {
            read_artifact_using(project, artifact, reader)?;
        }
        let child = if let Some(artifact) = &unit.config {
            ensure!(unit.mode != InputMode::FilesOnly, "INPUT_UNIT_MODE");
            let bytes = read_artifact_using(project, artifact, reader)?;
            let child = resolve_unit_using(
                project,
                request,
                &effective.context,
                std::str::from_utf8(&bytes)?,
                reader,
            )?;
            ensure!(
                child.inputs.len() == 1 && child.inputs[0].backend() == unit.backend,
                "INPUT_UNIT_BACKEND_MISMATCH"
            );
            if unit.backend == AnalysisBackend::TypeScript {
                let native = child
                    .typescript_inputs()
                    .context("TYPESCRIPT_INPUTS_REQUIRED")?;
                ensure!(
                    native.contexts.len() == 1
                        && native.contexts[0].unit == unit.id
                        && native.contexts[0]
                            .publish_languages
                            .iter()
                            .copied()
                            .collect::<BTreeSet<_>>()
                            == unit
                                .source_languages
                                .iter()
                                .copied()
                                .collect::<BTreeSet<_>>(),
                    "INPUT_UNIT_CONTEXT_MISMATCH"
                );
            }
            merge_prepared_modules(
                &mut effective.config.modules,
                &child.config.modules,
                unit.backend,
            )?;
            Some(child)
        } else {
            ensure!(
                unit.mode == InputMode::FilesOnly && unit.reason.is_some(),
                "INPUT_UNIT_MODE"
            );
            None
        };
        effective.input_units.push(ResolvedUnit {
            prepared: unit,
            context: child,
        });
        reader.leave_unit();
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
                .filter(|unit| unit.prepared.backend != AnalysisBackend::TypeScript)
                .flat_map(|unit| &unit.prepared.entries)
                .collect::<Vec<_>>()
                .len()
                == effective
                    .input_units
                    .iter()
                    .filter(|unit| unit.prepared.backend != AnalysisBackend::TypeScript)
                    .flat_map(|unit| &unit.prepared.entries)
                    .collect::<BTreeSet<_>>()
                    .len()
            && preparation.entries.iter().all(|e| entries.contains(&e.id)),
        "INPUT_CATALOG_ENTRY_COVERAGE"
    );
    effective.semantic_digest = semantic::prepared(&effective);
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
    fn repeated_script_modules_share_policy_only_when_identical() {
        let module = Module {
            id: "app".into(),
            language: "typescript".into(),
            root: ".".into(),
            ..Default::default()
        };
        let mut modules = vec![];
        merge_prepared_modules(
            &mut modules,
            std::slice::from_ref(&module),
            AnalysisBackend::TypeScript,
        )
        .unwrap();
        merge_prepared_modules(
            &mut modules,
            std::slice::from_ref(&module),
            AnalysisBackend::TypeScript,
        )
        .unwrap();
        assert_eq!(modules.len(), 1);
        let mut conflict = module.clone();
        conflict.root = "different".into();
        assert!(
            merge_prepared_modules(&mut modules, &[conflict], AnalysisBackend::TypeScript).is_err()
        );
        assert!(merge_prepared_modules(&mut modules, &[module], AnalysisBackend::Python).is_err());
        assert_eq!(modules.len(), 1);
    }
    #[test]
    fn native_views_inherit_task_policy_and_reject_policy_injection() {
        let directory = tempfile::tempdir().unwrap();
        let text = "schema_version=1\nkind='project'\n[languages.java]\nenabled=true\n[[modules]]\nid='app'\nroot='.'\nlanguage='java'\nsource_roots=[{path='src',scope='MAIN'}]\n";
        let request = Request {
            suggestions: Some(false),
            ..Default::default()
        };
        let mut parent =
            super::super::resolution::resolve_text(directory.path(), &request, false, text, false)
                .unwrap();
        let native = unit_config_text(&parent.config).unwrap();
        assert!(!native.contains("schema_version"));
        assert!(!native.contains("[scan]"));
        let first = resolve_unit(directory.path(), &request, &parent.context, &native).unwrap();
        let second = resolve_unit(directory.path(), &request, &parent.context, &native).unwrap();
        assert!(std::sync::Arc::ptr_eq(
            &first.config.task,
            &second.config.task
        ));
        assert!(std::sync::Arc::ptr_eq(
            &first.config.task,
            &parent.config.task
        ));
        assert_eq!(first.config.build.suggestions, Some(false));
        assert_eq!(first.config.scan.scopes, ["MAIN"]);
        for injected in [
            "\n[scan]\nscopes=['TEST']\n",
            "\n[resources.index]\nworker_memory_bytes=16777216\n",
        ] {
            assert!(
                resolve_unit(
                    directory.path(),
                    &request,
                    &parent.context,
                    &format!("{native}{injected}")
                )
                .is_err()
            );
        }
        parent.input_units = vec![ResolvedUnit {
            prepared: PreparedUnit {
                id: "app".into(),
                backend: AnalysisBackend::Java,
                source_languages: vec![SourceLanguage::Java],
                entries: vec!["app".into()],
                modules: vec!["app".into()],
                roots: vec![".".into()],
                mode: InputMode::Semantic,
                reason: None,
                log: None,
                config: None,
                artifacts: vec![],
            },
            context: Some(first),
        }];
        parent.set_worker_memory_limit(128 << 20);
        let child = parent.input_units[0].context.as_ref().unwrap();
        assert!(std::sync::Arc::ptr_eq(
            &child.config.task,
            &parent.config.task
        ));
        assert_eq!(
            child.config.resources.index.worker_memory_bytes,
            Some(128 << 20)
        );
        assert_eq!(second.config.resources.index.worker_memory_bytes, None);
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

#[cfg(test)]
mod strict_config_tests {
    use super::*;
    #[test]
    fn prepared_configuration_requires_original_bytes() {
        let original = hash(b"original TOML");
        let catalog = InputCatalog {
            schema_version: 3,
            config_sha256: original.clone(),
            units: vec![],
        };
        assert!(matches_configuration(
            &catalog,
            &ProjectConfig::default(),
            &original
        ));
        assert!(!matches_configuration(
            &catalog,
            &ProjectConfig::default(),
            &hash(b"changed TOML")
        ));
    }
    #[test]
    fn removed_comment_configuration_has_a_removal_hint() {
        let error = super::super::parse_project(
            "schema_version=1\nkind='project'\n[query.comments]\ncontext_before=0\n",
        )
        .unwrap_err();
        assert!(
            error
                .to_string()
                .contains("Remove retired [query.comments]")
        );
    }
}
