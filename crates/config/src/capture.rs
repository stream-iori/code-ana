//! Freeze sources and controls; all language capture paths share the same spool.
use super::*;

fn git(root: &Path, args: &[&str]) -> Option<String> {
    use std::io::Read;
    let mut child = std::process::Command::new("git")
        .arg("-C")
        .arg(root)
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .ok()?;
    let mut bytes = Vec::new();
    child
        .stdout
        .take()?
        .take(MAX_RESULT as u64 + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() > MAX_RESULT {
        let _ = child.kill();
        let _ = child.wait();
        return None;
    }
    child.wait().ok()?.success().then(|| {
        String::from_utf8_lossy(&bytes)
            .trim_end_matches('\n')
            .to_owned()
    })
}
pub fn identity(root: &Path) -> (String, String) {
    let checkout = key("checkout", &[&root.to_string_lossy()]);
    let project = if let (Some(common), Some(prefix)) = (
        git(
            root,
            &["rev-parse", "--path-format=absolute", "--git-common-dir"],
        ),
        git(root, &["rev-parse", "--show-prefix"]),
    ) {
        key("project", &[&common, &prefix])
    } else {
        key("project", &[&root.to_string_lossy()])
    };
    (project, checkout)
}
/// Longest source root chooses a publication label; compiler ownership stays physical.
pub(super) fn source_ownership(
    modules: &[Module],
    path: &str,
) -> Result<Option<(String, String, String, String)>> {
    let mut owners = Vec::new();
    for module in modules {
        for source in &module.source_roots {
            let root = java_inputs::root_path(&module.root, &source.path)?;
            if Path::new(path).starts_with(&root)
                && (module.scan.scopes.is_empty()
                    || module.scan.scopes.contains(&source.scope)
                    || (module.language == "java"
                        && source.scope == "MAIN"
                        && module.scan.scopes.iter().any(|s| s == "TEST")))
                && (module.scan.include.is_empty() || glob(&module.scan.include)?.is_match(path))
                && !glob(&module.scan.exclude)?.is_match(path)
                && ((module.language == "java" && path.ends_with(".java"))
                    || (module.language == "rust" && path.ends_with(".rs")))
            {
                owners.push((
                    root.components().count(),
                    source.label.as_ref().unwrap_or(&module.id).clone(),
                    source.scope.clone(),
                    module.language.clone(),
                    module.id.clone(),
                ));
            }
        }
    }
    if let Some(depth) = owners.iter().map(|owner| owner.0).max() {
        owners.retain(|owner| owner.0 == depth);
        owners.sort();
        owners.dedup();
    }
    ensure!(
        owners.len() <= 1,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::AmbiguousSourceOwner,
            path.to_string()
        )
    );
    Ok(owners
        .pop()
        .map(|(_, module, scope, language, physical)| (module, scope, language, physical)))
}

pub(super) fn add_captured(
    manifest: &mut Manifest,
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    spool: &std::sync::Arc<tempfile::TempDir>,
    path: String,
    bytes: Vec<u8>,
    ownership: (String, String, String, String),
) -> Result<()> {
    let (module, scope, language, physical_module) = ownership;
    if let Some(existing) = manifest.files.iter_mut().find(|s| s.path == path) {
        ensure!(
            existing.hash == hash(&bytes),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaInputChangedDuringCapture,
                ""
            )
        );
        if !module.is_empty() {
            ensure!(
                existing.module.is_empty()
                    || (existing.module == module
                        && existing.scope == scope
                        && existing.world == key("world", &[&physical_module, &scope])),
                codeana_model::DomainError::new(codeana_model::ErrorCode::AmbiguousSourceOwner, "")
            );
            existing.world = key("world", &[&physical_module, &scope]);
            existing.module = module;
            existing.scope = scope;
            existing.language = language;
        }
        return Ok(());
    }
    *total += bytes.len();
    ensure!(
        *total <= MAX_CAPTURE_BYTES
            && bytes.len() <= 32 * 1024 * 1024
            && manifest.files.len() < 10000,
        codeana_model::DomainError::new(codeana_model::ErrorCode::JavaCaptureBudget, "")
    );
    let h = hash(&bytes);
    let world = if module.is_empty() {
        "files".into()
    } else {
        key("world", &[&physical_module, &scope])
    };
    manifest.files.push(SourceUnit {
        auxiliary: false,
        path,
        hash: h.clone(),
        size: bytes.len() as u64,
        module,
        world,
        scope,
        language,
    });
    blobs.insert(h, CapturedBlob::bytes(spool, &bytes)?);
    Ok(())
}

struct CaptureSelection<'a> {
    budget: Option<&'a ResourceBudget>,
    root: &'a Path,
    effective: &'a Effective,
    includes: globset::GlobSet,
    excludes: globset::GlobSet,
    text_only: globset::GlobSet,
    text_include: globset::GlobSet,
    text_search: bool,
    files_only: bool,
    started: std::time::Instant,
    deadline: std::time::Duration,
}
struct CaptureAccumulator {
    manifest: Manifest,
    blobs: BTreeMap<String, CapturedBlob>,
    total: usize,
    scanned: usize,
    spool: std::sync::Arc<tempfile::TempDir>,
}
fn capture_workspace(
    policy: &CaptureSelection<'_>,
    accumulator: &mut CaptureAccumulator,
) -> Result<()> {
    let root = policy.root;
    let effective = policy.effective;
    let c = &policy.effective.config;
    let includes = &policy.includes;
    let excludes = &policy.excludes;
    let text_only = &policy.text_only;
    let text_include = &policy.text_include;
    let text_search = policy.text_search;
    let files_only = policy.files_only;
    let started = policy.started;
    let deadline = policy.deadline;
    let CaptureAccumulator {
        manifest,
        blobs,
        total,
        scanned,
        spool,
        ..
    } = accumulator;
    let ignored_output = git(
        root,
        &[
            "ls-files",
            "--others",
            "--ignored",
            "--exclude-standard",
            "-z",
        ],
    );
    let in_git = git(root, &["rev-parse", "--is-inside-work-tree"]).as_deref() == Some("true");
    ensure!(
        !in_git || ignored_output.is_some(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::GitIgnoredEnumerationFailed, "")
    );
    let ignored: BTreeSet<String> = ignored_output
        .unwrap_or_default()
        .split('\0')
        .map(str::to_owned)
        .collect();
    let include_ignored = glob(&c.versions.capture.include_ignored)?;
    for entry in walkdir::WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| {
            e.depth() == 0
                || !matches!(
                    e.file_name().to_str(),
                    Some(".git" | "target" | "node_modules" | ".code-ana")
                )
        })
    {
        if let Some(b) = policy.budget {
            b.charge(1, 0)?;
        }
        *scanned += 1;
        ensure!(
            *scanned <= 100_000,
            codeana_model::DomainError::new(codeana_model::ErrorCode::ScanEntryBudget, "")
        );
        ensure!(
            started.elapsed() < deadline,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureTimeout, "")
        );
        let entry = entry?;
        if entry.depth() == 0 || entry.file_type().is_dir() {
            continue;
        }
        if let Some(b) = policy.budget {
            b.charge(1, entry.path().as_os_str().as_encoded_bytes().len() as u64)?;
        }
        let p = entry
            .path()
            .strip_prefix(root)?
            .to_str()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NonUtf8Path,
                "",
            ))?
            .to_owned();
        ensure!(
            safe_path(&p),
            codeana_model::DomainError::new(codeana_model::ErrorCode::UnsafePath, "")
        );
        if excludes.is_match(&p)
            || (text_search
                && if c.search.text.include.is_empty() {
                    text_only.is_match(&p) || !includes.is_match(&p)
                } else {
                    !text_include.is_match(&p)
                })
            || (!text_search && (text_only.is_match(&p) || !includes.is_match(&p)))
            || (ignored.contains(&p) && !include_ignored.is_match(&p))
        {
            continue;
        }
        if !files_only && effective.rust_inputs.is_some() && p.ends_with(".rs") {
            continue; // Rust capture applies its filters before reading any source bytes.
        }
        if !files_only
            && effective.python_inputs.is_some()
            && (p.ends_with(".py") || p.ends_with(".pyi") || p.ends_with("py.typed"))
        {
            continue;
        }
        ensure!(
            !entry.file_type().is_symlink(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::SymlinkSourceUnsupported,
                p.to_string()
            )
        );
        if !entry.file_type().is_file() {
            continue;
        }
        let (module, scope, language, physical_module) = source_ownership(&c.modules, &p)?
            .unwrap_or((
                String::new(),
                "RESOURCE".into(),
                String::new(),
                String::new(),
            ));
        ensure!(
            entry.metadata()?.len() <= MAX_FRAME as u64,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureFileBudget, "")
        );
        let size = entry.metadata()?.len();
        if let Some(b) = policy.budget {
            b.charge(1, size)?;
        }
        let bytes = bounded_read(
            entry.path(),
            usize::try_from(size).unwrap_or(usize::MAX).min(MAX_FRAME),
        )?;
        *total += bytes.len();
        ensure!(
            bytes.len() <= MAX_FRAME && *total <= MAX_CAPTURE_BYTES,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureBudget, "")
        );
        ensure!(
            manifest.files.len() < 10_000,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureFileBudget, "")
        );
        let h = hash(&bytes);
        let world = if module.is_empty() {
            "files".into()
        } else {
            key("world", &[&physical_module, &scope])
        };
        manifest.files.push(SourceUnit {
            auxiliary: false,
            path: p,
            hash: h.clone(),
            size: bytes.len() as u64,
            module,
            world,
            scope,
            language,
        });
        blobs.insert(h, CapturedBlob::bytes(spool, &bytes)?);
    }
    Ok(())
}
fn capture_java_metadata(
    policy: &CaptureSelection<'_>,
    accumulator: &mut CaptureAccumulator,
) -> Result<()> {
    let root = policy.root;
    let effective = policy.effective;
    let c = &policy.effective.config;
    let includes = &policy.includes;
    let excludes = &policy.excludes;
    let text_only = &policy.text_only;
    let files_only = policy.files_only;
    let started = policy.started;
    let deadline = policy.deadline;
    let CaptureAccumulator {
        manifest,
        blobs,
        total,
        scanned,
        spool,
        ..
    } = accumulator;
    if !files_only && let Some(inputs) = &effective.java_inputs {
        let metadata_path = c
            .languages
            .java
            .as_ref()
            .and_then(|l| l.discovery.inputs_manifest.as_deref())
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaMetadataPath,
                "",
            ))?;
        let metadata = bounded_read(&root.join(metadata_path), 32 * 1024 * 1024)?;
        let reparsed: JavaInputs = serde_json::from_slice(&metadata)?;
        ensure!(
            digest(&reparsed) == digest(inputs),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaMetadataChangedDuringCapture,
                ""
            )
        );
        add_captured(
            manifest,
            blobs,
            total,
            spool,
            metadata_path.into(),
            metadata,
            (
                String::new(),
                "CONTROL".into(),
                String::new(),
                String::new(),
            ),
        )?;
        for (path, expected) in &inputs.controls {
            ensure!(
                !excludes.is_match(path),
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaControlExcluded, "")
            );
            let bytes = bounded_read(&root.join(path), MAX_FRAME)?;
            ensure!(
                hash(&bytes) == *expected,
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaMetadataStale, "")
            );
            add_captured(
                manifest,
                blobs,
                total,
                spool,
                path.clone(),
                bytes,
                (
                    String::new(),
                    "CONTROL".into(),
                    String::new(),
                    String::new(),
                ),
            )?;
        }
        // External settings and Maven executable are identities, not captured source blobs.
        // Recheck them at capture time so an export cannot race a settings edit.
        for (path, expected) in &inputs.external_controls {
            ensure!(
                hash(&bounded_read(Path::new(path), MAX_FRAME)?) == *expected,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaMetadataStale,
                    path.to_string()
                )
            );
        }
        for m in &inputs.modules {
            ensure!(
                started.elapsed() < deadline,
                codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureTimeout, "")
            );
            let config = c.modules.iter().find(|v| v.id == m.id).context(
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaModuleConfigRequired,
                    "",
                ),
            )?;
            ensure!(
                config.root == m.root,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaModuleRootMismatch,
                    ""
                )
            );
            let module_include = glob(&config.scan.include)?;
            let module_exclude = glob(&config.scan.exclude)?;
            for (r, is_source) in m
                .source_roots
                .iter()
                .map(|r| (r, true))
                .chain(m.resource_roots.iter().map(|r| (r, false)))
            {
                let prerequisite =
                    is_source && r.scope == "MAIN" && c.scan.scopes.iter().any(|s| s == "TEST");
                if (!c.scan.scopes.contains(&r.scope)
                    || (!config.scan.scopes.is_empty() && !config.scan.scopes.contains(&r.scope)))
                    && !prerequisite
                {
                    continue;
                }
                for (proof, expected) in &r.generated_from {
                    ensure!(
                        !excludes.is_match(proof),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaGeneratedProofExcluded,
                            ""
                        )
                    );
                    let bytes = bounded_read(&root.join(proof), MAX_FRAME)?;
                    ensure!(
                        hash(&bytes) == *expected,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GeneratedInputStale,
                            ""
                        )
                    );
                    add_captured(
                        manifest,
                        blobs,
                        total,
                        spool,
                        proof.clone(),
                        bytes,
                        (
                            String::new(),
                            "CONTROL".into(),
                            String::new(),
                            String::new(),
                        ),
                    )?;
                }
                let dir = root.join(java_inputs::root_path(&m.root, &r.path)?);
                if !dir.exists() {
                    ensure!(
                        !r.generated,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::BuildInputRequired,
                            "generated source root is missing".to_string()
                        )
                    );
                    continue;
                }
                ensure!(
                    dir.canonicalize()?.starts_with(root),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaInputOutsideProject,
                        ""
                    )
                );
                for entry in walkdir::WalkDir::new(&dir).follow_links(false) {
                    let entry = entry?;
                    ensure!(
                        started.elapsed() < deadline,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::CaptureTimeout,
                            ""
                        )
                    );
                    if entry.file_type().is_dir() {
                        continue;
                    }
                    ensure!(
                        entry.file_type().is_file(),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaInputSymlink,
                            ""
                        )
                    );
                    *scanned += 1;
                    ensure!(
                        *scanned <= 100000,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::ScanEntryBudget,
                            ""
                        )
                    );
                    let path = entry
                        .path()
                        .strip_prefix(root)?
                        .to_str()
                        .context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::NonUtf8Path,
                            "",
                        ))?
                        .to_owned();
                    if excludes.is_match(&path)
                        || text_only.is_match(&path)
                        || !includes.is_match(&path)
                        || (!config.scan.include.is_empty() && !module_include.is_match(&path))
                        || module_exclude.is_match(&path)
                    {
                        continue;
                    }
                    if is_source && !path.ends_with(".java") {
                        continue;
                    }
                    let ownership = if is_source {
                        let owner = source_ownership(&c.modules, &path)?.unwrap_or((
                            m.id.clone(),
                            r.scope.clone(),
                            "java".into(),
                            m.id.clone(),
                        ));
                        ensure!(
                            owner.3 == m.id && owner.1 == r.scope,
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::AmbiguousSourceOwner,
                                path.to_string()
                            )
                        );
                        owner
                    } else {
                        (m.id.clone(), r.scope.clone(), String::new(), m.id.clone())
                    };
                    add_captured(
                        manifest,
                        blobs,
                        total,
                        spool,
                        path,
                        bounded_read(entry.path(), MAX_FRAME)?,
                        ownership,
                    )?;
                }
            }
            if config.analysis.classpath.as_deref() == Some("none") {
                continue;
            }
            for artifact in m.classpath.values().flatten() {
                ensure!(
                    started.elapsed() < deadline,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureTimeout, "")
                );
                let path = if Path::new(&artifact.path).is_absolute() {
                    PathBuf::from(&artifact.path)
                } else {
                    root.join(&artifact.path)
                };
                // Large dependency archives are captured through a bounded file spool;
                // their size is independent of the IPC frame and semantic-result budgets.
                let captured_path = format!(".code-ana/inputs/{}.jar", artifact.sha256);
                if manifest.files.iter().any(|s| s.path == captured_path) {
                    continue;
                }
                let (blob, h, size) = CapturedBlob::file(spool, &path, MAX_ARTIFACT_BYTES)
                    .context("BUILD_INPUT_REQUIRED: dependency artifact")?;
                ensure!(
                    h == artifact.sha256,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaArtifactStale,
                        ""
                    )
                );
                *total += size;
                ensure!(
                    *total <= MAX_CAPTURE_BYTES && manifest.files.len() < 10000,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaCaptureBudget,
                        ""
                    )
                );
                manifest.files.push(SourceUnit {
                    auxiliary: false,
                    path: captured_path,
                    hash: h.clone(),
                    size: size as u64,
                    module: String::new(),
                    world: "files".into(),
                    scope: "DEPENDENCY".into(),
                    language: String::new(),
                });
                blobs.insert(h, blob);
            }
        }
    }
    Ok(())
}

pub fn capture(root: &Path, request: &Request) -> Result<Capture> {
    capture_metered(root, request, None)
}
pub fn capture_query(root: &Path, request: &Request, budget: &ResourceBudget) -> Result<Capture> {
    capture_metered(root, request, Some(budget))
}
fn capture_metered(
    root: &Path,
    request: &Request,
    budget: Option<&ResourceBudget>,
) -> Result<Capture> {
    let started = std::time::Instant::now();
    let deadline = std::time::Duration::from_secs(if request.deadline_seconds == 0 {
        120
    } else {
        request.deadline_seconds.min(MAX_TASK_DEADLINE_SECONDS)
    });
    let root = root.canonicalize()?;
    let text_search = request.operation == codeana_model::Operation::SearchText;
    let files_only = text_search
        || (request.operation == codeana_model::Operation::Snapshot && request.name != "analysis");
    let effective = if files_only {
        resolve_files(&root, request)?
    } else {
        resolve(&root, request)?
    };
    let c = &effective.config;
    let includes = glob(&c.scan.include)?;
    let excludes = glob(&c.scan.exclude)?;
    let text_only = glob(&c.scan.text_only)?;
    let text_include = glob(&c.search.text.include)?;
    let manifest = Manifest::default();
    let blobs = BTreeMap::new();
    let spool = std::sync::Arc::new(
        tempfile::Builder::new()
            .prefix("codeana-capture-")
            .tempdir()?,
    );
    let total = 0;
    let policy = CaptureSelection {
        budget,
        root: &root,
        effective: &effective,
        includes,
        excludes,
        text_only,
        text_include,
        text_search,
        files_only,
        started,
        deadline,
    };
    let mut accumulator = CaptureAccumulator {
        manifest,
        blobs,
        total,
        scanned: 0,
        spool,
    };
    capture_workspace(&policy, &mut accumulator)?;
    capture_java_metadata(&policy, &mut accumulator)?;
    for unit in &effective.input_units {
        if let Some(child) = &unit.effective {
            let child_policy = CaptureSelection {
                budget,
                effective: child,
                root: &root,
                includes: policy.includes.clone(),
                excludes: policy.excludes.clone(),
                text_only: policy.text_only.clone(),
                text_include: policy.text_include.clone(),
                text_search,
                files_only,
                started,
                deadline,
            };
            capture_java_metadata(&child_policy, &mut accumulator)?;
        }
    }
    let CaptureAccumulator {
        mut manifest,
        mut blobs,
        mut total,
        spool,
        ..
    } = accumulator;
    let excludes = &policy.excludes;
    if !files_only
        && !text_search
        && c.modules
            .iter()
            .any(|m| m.language == "java" && m.lombok.mode.as_deref() == Some("model"))
    {
        lombok::capture(
            &root,
            &mut manifest,
            &mut blobs,
            &mut total,
            &spool,
            excludes,
            (started, deadline),
        )?;
    }
    // Validate the control input on every capture. Persist it for FILES/analysis;
    // current text search only exposes paths selected by its own include policy.
    ensure!(
        !excludes.is_match(".code-ana/config.toml"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::ControlInputExcluded, "")
    );
    let bytes = bounded_read(&root.join(".code-ana/config.toml"), 1024 * 1024)?;
    let h = hash(&bytes);
    ensure!(
        h == effective.input_hash,
        codeana_model::DomainError::new(codeana_model::ErrorCode::ConfigChangedDuringCapture, "")
    );
    if !text_search {
        total += bytes.len();
        ensure!(
            total <= MAX_CAPTURE_BYTES,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureBudget, "")
        );
        manifest.files.push(SourceUnit {
            auxiliary: false,
            path: ".code-ana/config.toml".into(),
            hash: h.clone(),
            size: bytes.len() as u64,
            module: String::new(),
            world: "files".into(),
            scope: "CONTROL".into(),
            language: String::new(),
        });
        blobs.insert(h, CapturedBlob::bytes(&spool, &bytes)?);
    }
    // Keep compiler prerequisites in the sealed capture, with an explicit publication role.
    // Their real language/world/hash stay available for dependency invalidation and FILES.
    if !files_only {
        for source in manifest.files.iter_mut().filter(|s| s.language == "java") {
            let physical = c
                .modules
                .iter()
                .find(|m| source.world == key("world", &[&m.id, &source.scope]));
            // Capture owns bytes and publication roles. The Java adapter diagnoses
            // malformed source text per owner while retaining healthy semantic inputs.
            source.auxiliary = physical.is_none_or(|module| {
                !c.scan.scopes.contains(&source.scope)
                    || (!module.scan.scopes.is_empty()
                        && !module.scan.scopes.contains(&source.scope))
                    || effective
                        .java_inputs
                        .as_ref()
                        .or_else(|| {
                            effective
                                .input_units
                                .iter()
                                .filter_map(|u| u.effective.as_ref())
                                .filter_map(|e| e.java_inputs.as_ref())
                                .find(|inputs| inputs.modules.iter().any(|m| m.id == module.id))
                        })
                        .is_some_and(|inputs| {
                            inputs
                                .modules
                                .iter()
                                .any(|m| m.id == module.id && !m.selected)
                        })
            });
        }
    }
    if !files_only {
        rust_inputs::capture(
            &root,
            &effective,
            &mut manifest,
            &mut blobs,
            &mut total,
            &spool,
        )?;
        python_inputs::capture(
            &root,
            &effective,
            &mut manifest,
            &mut blobs,
            &mut total,
            &spool,
        )?;
        for unit in &effective.input_units {
            if let Some(child) = &unit.effective {
                if child
                    .config
                    .modules
                    .iter()
                    .any(|m| m.language == "java" && m.lombok.mode.as_deref() == Some("model"))
                {
                    lombok::capture(
                        &root,
                        &mut manifest,
                        &mut blobs,
                        &mut total,
                        &spool,
                        excludes,
                        (started, deadline),
                    )?;
                }
                rust_inputs::capture(&root, child, &mut manifest, &mut blobs, &mut total, &spool)?;
                python_inputs::capture(
                    &root,
                    child,
                    &mut manifest,
                    &mut blobs,
                    &mut total,
                    &spool,
                )?;
            }
        }
        if let Some(preparation) = &c.inputs {
            let path = &preparation.manifest;
            ensure!(!excludes.is_match(path), "CONTROL_INPUT_EXCLUDED: {path}");
            let bytes = bounded_read(&root.join(path), 32 << 20)?;
            let catalog: inputs::InputCatalog = serde_json::from_slice(&bytes)?;
            ensure!(
                catalog.units.len() == effective.input_units.len()
                    && catalog
                        .units
                        .iter()
                        .zip(&effective.input_units)
                        .all(|(a, b)| digest(a) == digest(&b.prepared)),
                "INPUT_CATALOG_CHANGED"
            );
            add_captured(
                &mut manifest,
                &mut blobs,
                &mut total,
                &spool,
                path.clone(),
                bytes,
                (
                    String::new(),
                    "CONTROL".into(),
                    String::new(),
                    String::new(),
                ),
            )?;
            for unit in &effective.input_units {
                for artifact in unit.prepared.config.iter().chain(&unit.prepared.artifacts) {
                    ensure!(
                        !excludes.is_match(&artifact.path),
                        "CONTROL_INPUT_EXCLUDED: {}",
                        artifact.path
                    );
                    let bytes = inputs::read_artifact(&root, artifact)?;
                    add_captured(
                        &mut manifest,
                        &mut blobs,
                        &mut total,
                        &spool,
                        artifact.path.clone(),
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
        }
    }
    manifest
        .files
        .sort_by(|a, b| (&a.path, &a.world).cmp(&(&b.path, &b.world)));
    let (project, checkout) = identity(&root);
    let target = key("target", &[&project, &checkout, &effective.capture_digest]);
    Ok(Capture {
        root,
        project,
        checkout,
        target,
        effective,
        manifest,
        blobs,
    })
}
pub fn require_java(c: &Capture) -> Result<()> {
    ensure!(
        c.effective.config.discovery.mode == "explicit" || c.effective.java_inputs.is_some(),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::UnsupportedDiscoveryMode,
            "auto requires an adapter discovery provider".to_string()
        )
    );
    ensure!(
        c.manifest.files.iter().any(|s| s.language == "java")
            || c.effective
                .config
                .modules
                .iter()
                .any(|m| m.language == "java"),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::NoJavaModule,
            "explicit modules or exported inputs required".to_string()
        )
    );
    Ok(())
}
