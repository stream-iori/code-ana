//! A frozen unit owns its references; only immutable content and observation storage are shared.
use super::*;
use std::sync::Arc;

#[derive(Clone, Default)]
pub struct ObservationSet {
    all: Arc<[ResolutionObservation]>,
    selected: Option<Arc<[usize]>>,
}
impl ObservationSet {
    pub fn iter(&self) -> impl Iterator<Item = &ResolutionObservation> {
        self.selected
            .as_deref()
            .unwrap_or(&[])
            .iter()
            .map(|i| &self.all[*i])
            .chain(self.all.iter().take(if self.selected.is_none() {
                self.all.len()
            } else {
                0
            }))
    }
    pub fn len(&self) -> usize {
        self.selected.as_ref().map_or(self.all.len(), |s| s.len())
    }
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}
impl std::fmt::Debug for ObservationSet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_list().entries(self.iter()).finish()
    }
}

pub(super) struct ObservationIndex {
    all: Arc<[ResolutionObservation]>,
    paths: BTreeMap<PathBuf, Vec<usize>>,
    roots: BTreeMap<PathBuf, Vec<usize>>,
}
impl ObservationIndex {
    pub(super) fn new(observations: Vec<ResolutionObservation>) -> Self {
        let all: Arc<[ResolutionObservation]> = observations.into();
        let mut paths: BTreeMap<PathBuf, Vec<usize>> = BTreeMap::new();
        let mut roots: BTreeMap<PathBuf, Vec<usize>> = BTreeMap::new();
        for (index, observation) in all.iter().enumerate() {
            match observation {
                ResolutionObservation::Root { path, real, exact } => {
                    roots
                        .entry(
                            exact
                                .as_ref()
                                .map_or_else(|| path.clone(), |p| path.join(p)),
                        )
                        .or_default()
                        .push(index);
                    if real != path {
                        roots
                            .entry(
                                exact
                                    .as_ref()
                                    .map_or_else(|| real.clone(), |p| real.join(p)),
                            )
                            .or_default()
                            .push(index);
                    }
                }
                ResolutionObservation::Link { path, .. }
                | ResolutionObservation::File { path, .. }
                | ResolutionObservation::Directory { path, .. }
                | ResolutionObservation::Missing { path }
                | ResolutionObservation::Entry { path, .. } => {
                    paths.entry(path.clone()).or_default().push(index);
                }
            }
        }
        Self { all, paths, roots }
    }
    pub(super) fn all(&self) -> ObservationSet {
        ObservationSet {
            all: self.all.clone(),
            selected: None,
        }
    }
    pub(super) fn select(&self, paths: &BTreeSet<PathBuf>) -> ObservationSet {
        let mut indices = BTreeSet::new();
        for path in paths {
            if let Some(index) = self.paths.get(path) {
                indices.extend(index);
            }
            for ancestor in path.ancestors() {
                if let Some(roots) = self.roots.get(ancestor) {
                    indices.extend(roots);
                }
            }
        }
        ObservationSet {
            all: self.all.clone(),
            selected: Some(indices.into_iter().collect::<Vec<_>>().into()),
        }
    }
}

#[derive(Debug, Clone)]
pub struct FrozenUnitView {
    pub unit: AnalysisUnitId,
    pub backend: AnalysisBackend,
    pub semantic_digest: String,
    pub manifest: Manifest,
    pub blobs: BTreeMap<String, CapturedBlob>,
    pub observations: ObservationSet,
}
impl Capture {
    pub fn backend_capture(&self, backend: AnalysisBackend) -> Result<Self> {
        if matches!(self.scope, CaptureScope::Unit(_)) {
            ensure!(
                self.effective.inputs.len() == 1 && self.effective.inputs[0].backend() == backend,
                "CAPTURE_UNIT_BACKEND_MISMATCH"
            );
            return Ok(self.clone());
        }
        self.unit_capture(&backend.as_str().into())
    }
    /// Only the selected unit's immutable references enter language preparation.
    pub fn unit_capture(&self, unit: &AnalysisUnitId) -> Result<Self> {
        let view = self
            .unit_views
            .get(unit)
            .context("CAPTURE_UNIT_VIEW_MISSING")?;
        let context = if self.effective.input_units.is_empty() {
            self.effective.for_backend(view.backend)?
        } else {
            self.effective
                .input_units
                .iter()
                .find(|u| u.prepared.id == unit.as_str())
                .and_then(|u| u.context.as_ref())
                .context("CAPTURE_UNIT_CONTEXT_MISSING")?
                .clone()
        };
        ensure!(
            context.inputs.len() == 1 && context.inputs[0].backend() == view.backend,
            "CAPTURE_UNIT_BACKEND_MISMATCH"
        );
        Ok(Self {
            scope: CaptureScope::Unit(unit.clone()),
            root: self.root.clone(),
            project: self.project.clone(),
            checkout: self.checkout.clone(),
            target: self.target.clone(),
            effective: Effective::from_context(context),
            manifest: view.manifest.clone(),
            blobs: view.blobs.clone(),
            observations: view.observations.clone(),
            unit_views: BTreeMap::new(),
        })
    }
}

/// Ownership for plain Java is selected from the shared workspace inventory.
/// Metadata-backed contexts use their native roots and inventories instead.
pub(super) fn plain_sources(
    policy: &CaptureSelection<'_>,
    workspace: &Manifest,
    local: &mut Manifest,
) -> Result<()> {
    if !policy
        .effective
        .inputs
        .iter()
        .any(|i| matches!(i, UnitInput::Java { metadata: None }))
    {
        return Ok(());
    }
    let roots = policy
        .effective
        .config
        .modules
        .iter()
        .flat_map(|m| {
            m.source_roots
                .iter()
                .map(move |r| java_inputs::root_path(&m.root, &r.path))
        })
        .map(|p| p.map(|p| policy.root.join(p)))
        .collect::<Result<Vec<_>>>()?;
    policy.reader.borrow_mut().share_directories(&roots)?;
    for source in &workspace.files {
        if let Some((module, scope, language, physical)) =
            source_ownership(&policy.effective.config.modules, &source.path)?
        {
            policy
                .reader
                .borrow_mut()
                .file(&policy.root.join(&source.path), MAX_FRAME)?;
            registry::register(
                local,
                SourceUnit {
                    module,
                    world: key("world", &[&physical, &scope]),
                    scope,
                    language,
                    ..source.clone()
                },
            )?;
        }
    }
    Ok(())
}

pub(super) fn java_roles(context: &ResolvedContext, manifest: &mut Manifest) {
    for source in manifest.files.iter_mut().filter(|s| s.language == "java") {
        let physical = context
            .config
            .modules
            .iter()
            .find(|m| source.world == key("world", &[&m.id, &source.scope]));
        source.auxiliary = physical.is_none_or(|module| {
            !context.config.scan.scopes.contains(&source.scope)
                || (!module.scan.scopes.is_empty() && !module.scan.scopes.contains(&source.scope))
                || context.java_inputs().is_some_and(|inputs| {
                    inputs
                        .modules
                        .iter()
                        .any(|m| m.id == module.id && !m.selected)
                })
        });
    }
}

/// Preserve Java's project-root XML resource policy without passing unrelated
/// workspace controls or source languages into the unit. Native roots keep
/// their explicit owner; Lombok controls are captured independently below.
fn java_workspace_resources(
    policy: &CaptureSelection<'_>,
    workspace: &Manifest,
    local: &mut Manifest,
) -> Result<()> {
    if !policy
        .effective
        .inputs
        .iter()
        .any(|i| i.backend() == AnalysisBackend::Java)
        || !policy.effective.config.modules.iter().any(|m| {
            m.root == "."
                && ((m.extensions.spring.enabled.unwrap_or(false)
                    && m.extensions.spring.xml.unwrap_or(false))
                    || m.extensions.sofa.enabled.unwrap_or(false))
        })
    {
        return Ok(());
    }
    policy
        .reader
        .borrow_mut()
        .share_directories(&[policy.root.to_owned()])?;
    for source in &workspace.files {
        if source.language.is_empty()
            && source.module.is_empty()
            && source.scope == "RESOURCE"
            && source.path.ends_with(".xml")
            && Path::new(&source.path)
                .file_name()
                .is_some_and(|n| n != "pom.xml")
            && !local.files.iter().any(|s| s.path == source.path)
        {
            policy
                .reader
                .borrow_mut()
                .file(&policy.root.join(&source.path), MAX_FRAME)?;
            registry::register(local, source.clone())?;
        }
    }
    Ok(())
}

pub(super) struct CapturePlan<'a> {
    pub unit: AnalysisUnitId,
    pub context: std::borrow::Cow<'a, ResolvedContext>,
    preparation: Option<&'a inputs::PreparedUnit>,
}
pub(super) fn unit_plans(effective: &Effective) -> Result<Vec<CapturePlan<'_>>> {
    if !effective.input_units.is_empty() {
        Ok(effective
            .input_units
            .iter()
            .filter_map(|unit| {
                unit.context.as_ref().map(|context| CapturePlan {
                    unit: unit.prepared.id.clone().into(),
                    context: std::borrow::Cow::Borrowed(context),
                    preparation: Some(&unit.prepared),
                })
            })
            .collect())
    } else {
        let mut plans = Vec::new();
        for input in &effective.inputs {
            let context = effective.for_backend(input.backend())?;
            if let UnitInput::TypeScript { metadata } = input {
                for native in &metadata.contexts {
                    let mut context = context.clone();
                    context.inputs = vec![UnitInput::TypeScript {
                        metadata: std::sync::Arc::new(codeana_model::typescript::Inputs {
                            schema_version: metadata.schema_version,
                            compiler: metadata.compiler.clone(),
                            contexts: vec![native.clone()],
                        }),
                    }];
                    context.semantic_digest = semantic::context(&context.config, &context.inputs);
                    plans.push(CapturePlan {
                        unit: native.unit.clone(),
                        context: std::borrow::Cow::Owned(context),
                        preparation: None,
                    });
                }
            } else {
                plans.push(CapturePlan {
                    unit: input.backend().as_str().into(),
                    context: std::borrow::Cow::Owned(context),
                    preparation: None,
                });
            }
        }
        ensure!(
            plans
                .iter()
                .map(|plan| &plan.unit)
                .collect::<BTreeSet<_>>()
                .len()
                == plans.len(),
            "CAPTURE_DUPLICATE_UNIT"
        );
        Ok(plans)
    }
}
pub(super) fn capture_units(
    policy: &CaptureSelection<'_>,
    accumulator: &mut CaptureAccumulator,
    units: &[CapturePlan<'_>],
) -> Result<BTreeMap<AnalysisUnitId, Manifest>> {
    let mut manifests = BTreeMap::new();
    let mut references = 0usize;
    for unit in units {
        let context = unit.context.as_ref();
        let id = unit.unit.clone();
        policy.reader.borrow_mut().enter_unit(&id)?;
        let child_policy = CaptureSelection {
            effective: context,
            includes: policy.includes.clone(),
            excludes: policy.excludes.clone(),
            text_only: policy.text_only.clone(),
            text_include: policy.text_include.clone(),
            ..*policy
        };
        let mut local = CaptureAccumulator {
            manifest: Manifest::default(),
            blobs: BTreeMap::new(),
            total: 0,
            scanned: 0,
            spool: accumulator.spool.clone(),
        };
        plain_sources(&child_policy, &accumulator.manifest, &mut local.manifest)?;
        capture_java_metadata(&child_policy, &mut local)?;
        java_workspace_resources(&child_policy, &accumulator.manifest, &mut local.manifest)?;
        java_roles(context, &mut local.manifest);
        rust_inputs::capture(
            policy.root,
            context,
            &mut local.manifest,
            &mut local.blobs,
            &mut local.total,
            &local.spool,
            policy.reader,
        )?;
        python_inputs::capture(
            policy.root,
            context,
            &mut local.manifest,
            &mut local.blobs,
            &mut local.total,
            &local.spool,
            policy.reader,
        )?;
        typescript_inputs::capture(
            policy.root,
            context,
            &mut local.manifest,
            &mut local.blobs,
            &mut local.total,
            &local.spool,
            policy.reader,
        )?;
        if context
            .config
            .modules
            .iter()
            .any(|m| m.language == "java" && m.lombok.mode.as_deref() == Some("model"))
        {
            lombok::capture(
                policy.reader,
                &mut local.manifest,
                &mut local.blobs,
                &mut local.total,
                &local.spool,
                &policy.excludes,
                (policy.started, policy.deadline),
            )?;
        }
        let common = std::iter::once(".code-ana/config.toml").chain(
            policy
                .effective
                .config
                .inputs
                .iter()
                .map(|p| p.manifest.as_str()),
        );
        for path in common.chain(
            unit.preparation
                .into_iter()
                .flat_map(|p| p.config.iter().chain(&p.artifacts))
                .map(|a| a.path.as_str()),
        ) {
            ensure!(
                !policy.excludes.is_match(path),
                "CONTROL_INPUT_EXCLUDED: {path}"
            );
            let bytes = policy
                .reader
                .borrow_mut()
                .read(&policy.root.join(path), 32 << 20)?;
            add_captured(
                &mut local.manifest,
                &mut local.blobs,
                &mut local.total,
                &local.spool,
                path.into(),
                bytes,
                (
                    String::new(),
                    if path == ".code-ana/config.toml" {
                        "CONTROL"
                    } else {
                        "PREPARATION"
                    }
                    .into(),
                    String::new(),
                    String::new(),
                ),
            )?;
        }
        let preparation_paths = unit
            .preparation
            .into_iter()
            .flat_map(|p| p.config.iter().chain(&p.artifacts))
            .map(|a| a.path.as_str())
            .chain(
                policy
                    .effective
                    .config
                    .inputs
                    .iter()
                    .map(|p| p.manifest.as_str()),
            )
            .collect::<BTreeSet<_>>();
        for source in &mut local.manifest.files {
            if source.module.is_empty()
                && source.language.is_empty()
                && preparation_paths.contains(source.path.as_str())
            {
                source.scope = "PREPARATION".into();
            }
        }
        references = references
            .checked_add(local.manifest.files.len())
            .context("CAPTURE_REFERENCE_BUDGET")?;
        ensure!(
            references <= 10_000,
            DomainError::new(ErrorCode::CaptureReferenceBudget, "unit views")
        );
        for source in &local.manifest.files {
            registry::merge_view(&mut accumulator.manifest, source)?;
        }
        for (digest, blob) in local.blobs {
            let size = usize::try_from(blob.as_ref().metadata()?.len())?;
            registry::retain_blob(
                &mut accumulator.blobs,
                &mut accumulator.total,
                digest,
                blob,
                size,
            )?;
        }
        accumulator.scanned = accumulator
            .scanned
            .checked_add(local.scanned)
            .context("SCAN_ENTRY_BUDGET")?;
        ensure!(
            accumulator.scanned <= 100_000,
            DomainError::new(ErrorCode::ScanEntryBudget, "")
        );
        manifests.insert(id, local.manifest);
        policy.reader.borrow_mut().leave_unit();
    }
    Ok(manifests)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selected_unit_keeps_link_and_content_observations_at_the_same_path() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let root = directory.path().canonicalize()?;
        std::fs::write(root.join("file"), "input")?;
        std::os::unix::fs::symlink("file", root.join("alias"))?;
        let mut reader = CaptureReader::new(
            &root,
            Arc::new(tempfile::tempdir()?),
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?;
        let unit = "unit".into();
        reader.enter_unit(&unit)?;
        reader.declare_links(&[DeclaredLink {
            path: "alias".into(),
            target: "file".into(),
        }])?;
        reader.read(&root.join("alias"), 1024)?;
        let sealed = reader.seal()?;
        let index = ObservationIndex::new(sealed.observations);
        let selected = index.select(&sealed.unit_paths[&unit]);
        assert!(
            selected
                .iter()
                .any(|o| matches!(o, ResolutionObservation::Link { .. }))
        );
        assert!(
            selected
                .iter()
                .any(|o| matches!(o, ResolutionObservation::File { .. }))
        );
        assert!(
            selected
                .iter()
                .any(|o| matches!(o, ResolutionObservation::Root { .. }))
        );
        Ok(())
    }

    #[test]
    fn selected_alias_proof_retains_its_external_target_root_permission() -> Result<()> {
        let project = tempfile::tempdir()?;
        let external = tempfile::tempdir()?;
        let root = project.path().canonicalize()?;
        let target = external.path().canonicalize()?.join("dependency");
        std::fs::write(&target, "external")?;
        std::os::unix::fs::symlink(&target, root.join("alias"))?;
        let mut reader = CaptureReader::new(
            &root,
            Arc::new(tempfile::tempdir()?),
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?;
        let unit = "unit".into();
        reader.enter_unit(&unit)?;
        reader.allow_file(&target)?;
        reader.declare_links(&[DeclaredLink {
            path: "alias".into(),
            target: target.clone(),
        }])?;
        reader.read(&root.join("alias"), 1024)?;
        let sealed = reader.seal()?;
        let selected = ObservationIndex::new(sealed.observations).select(&sealed.unit_paths[&unit]);
        assert!(selected.iter().any(|o| matches!(o, ResolutionObservation::Root {path, exact:Some(exact),..} if path.join(exact) == target)));
        Ok(())
    }

    fn artifact(root: &Path, path: &str, bytes: &[u8]) -> Result<inputs::InputArtifact> {
        std::fs::write(root.join(path), bytes)?;
        Ok(inputs::InputArtifact {
            path: path.into(),
            sha256: hash(bytes),
            bytes: bytes.len() as u64,
        })
    }
    fn shared_project() -> Result<(tempfile::TempDir, PathBuf)> {
        let project = tempfile::tempdir()?;
        let root = project.path().canonicalize()?;
        std::fs::create_dir(root.join(".code-ana"))?;
        std::fs::create_dir(root.join("src"))?;
        std::fs::write(root.join("src/Shared.java"), b"class Shared {}")?;
        let config = "schema_version=1\nkind='project'\n[scan]\nscopes=['MAIN','TEST']\n[languages.java]\nenabled=true\n[inputs]\n[[inputs.entries]]\nid='first'\nlanguage='java'\nroot='.'\n[[inputs.entries]]\nid='second'\nlanguage='java'\nroot='.'\n";
        std::fs::write(root.join(".code-ana/config.toml"), config)?;
        let mut units = Vec::new();
        for id in ["first", "second"] {
            let scopes = if id == "first" {
                "['MAIN']"
            } else {
                "['TEST']"
            };
            let native = format!(
                "[discovery]\nmode='explicit'\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version='8'\nclasspath='none'\n[[modules]]\nid='{id}'\nlanguage='java'\nroot='.'\nsource_roots=[{{path='src',scope='MAIN'}}]\n[modules.scan]\nscopes={scopes}\n"
            );
            let config = artifact(&root, &format!(".code-ana/{id}.toml"), native.as_bytes())?;
            let proof = artifact(&root, &format!(".code-ana/{id}-proof.txt"), id.as_bytes())?;
            units.push(inputs::PreparedUnit {
                id: id.into(),
                language: "java".into(),
                entries: vec![id.into()],
                modules: vec![id.into()],
                roots: vec![".".into()],
                mode: inputs::InputMode::Semantic,
                reason: None,
                log: None,
                config: Some(config),
                artifacts: vec![proof],
            });
        }
        let catalog = inputs::InputCatalog {
            schema_version: 2,
            config_sha256: hash(config),
            units,
        };
        std::fs::write(
            root.join(".code-ana/inputs.json"),
            serde_json::to_vec(&catalog)?,
        )?;
        Ok((project, root))
    }
    #[test]
    fn ordinary_backends_use_isolated_views_and_shared_task_policy() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let root = directory.path().canonicalize()?;
        for path in [".code-ana", "java-src", "rust-src"] {
            std::fs::create_dir(root.join(path))?;
        }
        std::fs::write(root.join("java-src/A.java"), b"class A {}")?;
        std::fs::write(root.join("beans.xml"), b"<beans/>")?;
        std::fs::write(root.join("rust-src/lib.rs"), b"pub fn rust_function() {}")?;
        std::fs::write(root.join("Cargo.toml"), b"fixture Rust control")?;
        let config = "schema_version=1\nkind='project'\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version='8'\nclasspath='none'\n[languages.java.extensions.spring]\nenabled=true\nxml=true\n[languages.rust]\nenabled=true\n[languages.rust.discovery]\ninputs_manifest='.code-ana/rust.json'\n[[modules]]\nid='java-app'\nlanguage='java'\nroot='.'\nsource_roots=[{path='java-src',scope='MAIN'}]\n";
        std::fs::write(root.join(".code-ana/config.toml"), config)?;
        let policy = rust_inputs::policy(&parse_project(config)?)?;
        let rust = serde_json::json!({"schema_version":2,"policy":policy,"toolchain":"fixture",
            "dependency_sources":{},"omitted_dependencies":{},"controls":{"Cargo.toml":hash(b"fixture Rust control")},
            "crates":[{"id":"rust-app","name":"rust-app","module":"rust-app","world":"rust-world","scope":"MAIN",
                "root":"rust-src/lib.rs","directory":"rust-src","edition":"2024","target":"fixture","cfg":[],"dependencies":{},"publish":true}]});
        std::fs::write(root.join(".code-ana/rust.json"), serde_json::to_vec(&rust)?)?;
        let capture = crate::capture(&root, &Request::default())?;
        assert_eq!(capture.scope, CaptureScope::Task);
        assert_eq!(
            capture
                .unit_views
                .keys()
                .map(|id| id.as_str())
                .collect::<Vec<_>>(),
            ["java", "rust"]
        );
        let java = capture.backend_capture(AnalysisBackend::Java)?;
        let rust = capture.backend_capture(AnalysisBackend::Rust)?;
        for (backend, view, other) in [
            (AnalysisBackend::Java, &java, AnalysisBackend::Rust),
            (AnalysisBackend::Rust, &rust, AnalysisBackend::Java),
        ] {
            assert_eq!(view.scope, CaptureScope::Unit(backend.as_str().into()));
            assert_eq!(view.effective.inputs.len(), 1);
            assert!(
                view.effective
                    .config
                    .modules
                    .iter()
                    .all(|m| m.language == backend.as_str())
            );
            assert!(std::sync::Arc::ptr_eq(
                &view.effective.config.task,
                &capture.effective.config.task
            ));
            assert!(view.backend_capture(other).is_err());
            assert!(view.unit_views.is_empty());
        }
        assert!(java.effective.config.languages.rust.is_none());
        assert!(rust.effective.config.languages.java.is_none());
        assert!(
            java.manifest
                .files
                .iter()
                .any(|s| s.path == "beans.xml" && s.scope == "RESOURCE")
        );
        assert!(java.blobs.contains_key(&hash(b"<beans/>")));
        assert!(!java.blobs.contains_key(&hash(b"fixture Rust control")));
        assert!(rust.blobs.contains_key(&hash(b"fixture Rust control")));
        assert!(
            !java
                .manifest
                .files
                .iter()
                .any(|s| s.path == ".code-ana/rust.json")
        );
        assert!(!java.observations.iter().any(|o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with("rust.json") || path.ends_with("Cargo.toml"))));
        assert!(!rust.observations.iter().any(
            |o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with("A.java"))
        ));
        assert!(rust.observations.iter().any(
            |o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with("Cargo.toml"))
        ));
        Ok(())
    }
    #[test]
    fn shared_physical_sources_have_separate_roles_and_bounded_unit_inputs() -> Result<()> {
        let (_project, root) = shared_project()?;
        let capture = crate::capture(&root, &Request::default())?;
        assert_eq!(capture.unit_views.len(), 2);
        let first = capture.unit_capture(&"first".into())?;
        let second = capture.unit_capture(&"second".into())?;
        let source = |c: &Capture| {
            c.manifest
                .files
                .iter()
                .find(|s| s.path == "src/Shared.java")
                .unwrap()
                .clone()
        };
        let a = source(&first);
        let b = source(&second);
        assert_eq!(a.hash, b.hash);
        assert_ne!(a.world, b.world);
        assert!(!a.auxiliary);
        assert!(b.auxiliary);
        assert_eq!(
            first.blobs[&a.hash].as_ref(),
            second.blobs[&b.hash].as_ref()
        );
        for (id, other, c) in [("first", "second", &first), ("second", "first", &second)] {
            assert!(
                c.manifest
                    .files
                    .iter()
                    .any(|s| s.path == format!(".code-ana/{id}-proof.txt"))
            );
            assert!(!c.manifest.files.iter().any(|s| s.path.contains(other)));
            let referenced = c
                .manifest
                .files
                .iter()
                .map(|s| &s.hash)
                .collect::<BTreeSet<_>>();
            assert_eq!(referenced.len(), c.blobs.len());
            assert!(c.observations.iter().any(|o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with(format!("{id}.toml")))));
            assert!(!c.observations.iter().any(|o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with(format!("{other}.toml")) || path.ends_with(format!("{other}-proof.txt")))));
            assert!(c.observations.iter().any(
                |o| matches!(o, ResolutionObservation::Directory {path,..} if path.ends_with("src"))
            ));
        }
        // The view pins its immutable bytes independently of the task and live tree.
        drop(capture);
        std::fs::remove_file(root.join("src/Shared.java"))?;
        assert_eq!(first.blobs[&a.hash].read(1024)?, b"class Shared {}");
        Ok(())
    }
    #[test]
    fn native_views_keep_their_own_dependency_bytes_controls_and_observations() -> Result<()> {
        let (_project, root) = shared_project()?;
        let external = tempfile::tempdir()?;
        let catalog_path = root.join(".code-ana/inputs.json");
        let mut catalog: inputs::InputCatalog =
            serde_json::from_slice(&std::fs::read(&catalog_path)?)?;
        for unit in &mut catalog.units {
            let id = &unit.id;
            let jar = external.path().join(format!("{id}.jar"));
            let jar_bytes = format!("frozen dependency of {id}");
            std::fs::write(&jar, &jar_bytes)?;
            let pom = format!(".code-ana/{id}-pom.xml");
            artifact(&root, &pom, id.as_bytes())?;
            let metadata = serde_json::json!({
                "schema_version":4, "resolved_classpath":true, "dependency_mode":"resolved",
                "controls":{pom:hash(id)}, "effective_model_sha256":hash(b"model"), "active_profiles_sha256":hash(b"profiles"),
                "modules":[{"id":id,"root":".","source_version":"8",
                    "source_roots":[{"path":"src","scope":"MAIN"}],
                    "classpath":{"MAIN":[{"path":jar,"sha256":hash(&jar_bytes)}]}}]
            });
            let inventory = format!(".code-ana/{id}-inputs.json");
            unit.artifacts.push(artifact(
                &root,
                &inventory,
                &serde_json::to_vec(&metadata)?,
            )?);
            let native = format!(
                "modules=[]\n[discovery]\nmode='auto'\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version='8'\n[languages.java.discovery]\nbuild_system='maven'\ninputs_manifest='{inventory}'\n"
            );
            unit.config = Some(artifact(
                &root,
                &format!(".code-ana/{id}.toml"),
                native.as_bytes(),
            )?);
        }
        std::fs::write(catalog_path, serde_json::to_vec(&catalog)?)?;
        let capture = crate::capture(&root, &Request::default())?;
        for (id, other) in [("first", "second"), ("second", "first")] {
            let c = capture.unit_capture(&id.into())?;
            let jars = c
                .manifest
                .files
                .iter()
                .filter(|s| s.scope == "DEPENDENCY")
                .collect::<Vec<_>>();
            assert_eq!(jars.len(), 1);
            assert_eq!(
                c.blobs[&jars[0].hash].read(1024)?,
                format!("frozen dependency of {id}").as_bytes()
            );
            assert!(
                !c.blobs
                    .contains_key(&hash(format!("frozen dependency of {other}")))
            );
            assert!(
                c.manifest
                    .files
                    .iter()
                    .any(|s| s.path == format!(".code-ana/{id}-pom.xml"))
            );
            assert!(
                !c.manifest
                    .files
                    .iter()
                    .any(|s| s.path == format!(".code-ana/{other}-pom.xml"))
            );
            assert!(c.observations.iter().any(|o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with(format!("{id}.jar")))));
            assert!(!c.observations.iter().any(|o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with(format!("{other}.jar")))));
        }
        Ok(())
    }
    #[test]
    fn duplicate_module_identity_remains_an_error_across_independent_units() -> Result<()> {
        let (_project, root) = shared_project()?;
        let file = root.join(".code-ana/second.toml");
        let bytes = std::fs::read_to_string(&file)?.replace("id='second'", "id='first'");
        std::fs::write(file, &bytes)?;
        let catalog = root.join(".code-ana/inputs.json");
        let mut value: inputs::InputCatalog = serde_json::from_slice(&std::fs::read(&catalog)?)?;
        let config = value.units[1].config.as_mut().unwrap();
        config.sha256 = hash(&bytes);
        config.bytes = bytes.len() as u64;
        std::fs::write(catalog, serde_json::to_vec(&value)?)?;
        assert!(
            crate::capture(&root, &Request::default())
                .unwrap_err()
                .to_string()
                .contains("INPUT_DUPLICATE_MODULE")
        );
        Ok(())
    }
}
