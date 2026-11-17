//! Read the explicit native inventory. This module never runs Go, Node or a
//! target build and never imports the private engine protobuf.
use super::*;
use codeana_model::typescript::Inputs;

pub fn configuration(
    config: &ProjectConfig,
) -> Result<Option<(&ScriptDiscovery, Vec<SourceLanguage>)>> {
    let mut discovery: Option<&ScriptDiscovery> = None;
    let mut enabled = Vec::new();
    for (language, script) in [
        (SourceLanguage::JavaScript, &config.languages.javascript),
        (SourceLanguage::TypeScript, &config.languages.typescript),
    ] {
        let Some(script) = script
            .as_ref()
            .filter(|script| script.enabled == Some(true))
        else {
            continue;
        };
        if let Some(previous) = discovery {
            ensure!(
                previous.inputs_manifest == script.discovery.inputs_manifest
                    && previous.dependency_roots == script.discovery.dependency_roots,
                "TYPESCRIPT_CONTEXT_CONFLICT: JS/TS in one backend require the same inventory and ordered dependency roots"
            );
        }
        for root in &script.discovery.dependency_roots {
            ensure!(
                !root.is_empty()
                    && !root.contains('\0')
                    && (Path::new(root).is_absolute() || relative(root)),
                "TYPESCRIPT_DEPENDENCY_ROOT"
            );
            ensure!(
                root != "/"
                    && !Path::new(root)
                        .components()
                        .any(|part| matches!(part, std::path::Component::ParentDir)),
                "TYPESCRIPT_DEPENDENCY_ROOT"
            );
        }
        ensure!(
            script
                .discovery
                .dependency_roots
                .iter()
                .collect::<BTreeSet<_>>()
                .len()
                == script.discovery.dependency_roots.len(),
            "TYPESCRIPT_DEPENDENCY_ROOT_DUPLICATE"
        );
        discovery = Some(&script.discovery);
        enabled.push(language);
    }
    Ok(discovery.map(|discovery| (discovery, enabled)))
}

pub(crate) fn discover_using(
    project: &Path,
    config: &ProjectConfig,
    semantic: bool,
    reader: &dyn InputReader,
) -> Result<Option<Inputs>> {
    let Some((discovery, enabled)) = configuration(config)? else {
        return Ok(None);
    };
    if !semantic {
        return Ok(None);
    }
    let path = discovery
        .inputs_manifest
        .as_deref()
        .context("TYPESCRIPT_INPUTS_REQUIRED: run explicit input preparation")?;
    ensure!(safe_path(path), "TYPESCRIPT_MANIFEST_PATH");
    let file = project.join(path);
    ensure!(
        !file.is_symlink() && file.canonicalize()?.starts_with(project),
        "TYPESCRIPT_MANIFEST_PATH"
    );
    let inputs: Inputs = serde_json::from_slice(&reader.read(&file, 32 << 20)?)?;
    inputs.validate().map_err(anyhow::Error::msg)?;
    let dependencies = discovery
        .dependency_roots
        .iter()
        .map(|path| {
            let path = Path::new(path);
            if path.is_absolute() {
                path.to_path_buf()
            } else {
                project.join(path)
            }
        })
        .collect::<Vec<_>>();
    for context in &inputs.contexts {
        ensure!(
            Path::new(&context.root).starts_with(project)
                && Path::new(&context.config_path).starts_with(project),
            "TYPESCRIPT_PROJECT_ROOT"
        );
        ensure!(
            context
                .publish_languages
                .iter()
                .all(|language| enabled.contains(language)),
            "TYPESCRIPT_PUBLICATION_DISABLED: prepare inputs after changing enabled languages"
        );
        for file in &context.files {
            let path = Path::new(&file.path);
            ensure!(
                path.starts_with(project) || dependencies.iter().any(|root| path.starts_with(root)),
                "TYPESCRIPT_INPUT_OUTSIDE_DECLARED_ROOTS: {}",
                file.path
            );
            if file.role == codeana_model::typescript::FileRole::Publish {
                ensure!(
                    path.starts_with(project),
                    "TYPESCRIPT_PUBLICATION_OUTSIDE_PROJECT"
                );
            }
        }
    }
    Ok(Some(inputs))
}

fn captured_path(project: &Path, path: &Path) -> Result<String> {
    if let Ok(relative) = path.strip_prefix(project) {
        let path = relative.to_str().context("NON_UTF8_CAPTURE_PATH")?;
        ensure!(safe_path(path), "TYPESCRIPT_CAPTURE_PATH");
        return Ok(path.into());
    }
    Ok(format!(
        ".code-ana/typescript-dependencies/{}",
        hash(path.as_os_str().as_encoded_bytes())
    ))
}
fn within(path: &Path, project: &Path, dependencies: &[PathBuf]) -> bool {
    path.starts_with(project) || dependencies.iter().any(|root| path.starts_with(root))
}
fn ancestor_lookup(path: &Path, project: &Path, dependencies: &[PathBuf]) -> bool {
    std::iter::once(project)
        .chain(dependencies.iter().map(PathBuf::as_path))
        .any(|root| {
            root.ancestors().any(|ancestor| {
                path == ancestor.join("package.json")
                    || path.starts_with(ancestor.join("node_modules"))
            })
        })
}

pub(super) fn capture(
    project: &Path,
    effective: &ResolvedContext,
    manifest: &mut Manifest,
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    spool: &std::sync::Arc<tempfile::TempDir>,
    reader: &std::cell::RefCell<capture::CaptureReader>,
) -> Result<()> {
    use codeana_model::typescript::{FileRole, LookupKind};
    let Some(inputs) = effective.typescript_inputs() else {
        return Ok(());
    };
    let (discovery, _) =
        configuration(&effective.config)?.context("TYPESCRIPT_CONFIGURATION_REQUIRED")?;
    let descriptor = discovery
        .inputs_manifest
        .as_deref()
        .context("TYPESCRIPT_INPUTS_REQUIRED")?;
    let bytes = reader
        .borrow_mut()
        .read(&project.join(descriptor), 32 << 20)?;
    let mut current: Inputs = serde_json::from_slice(&bytes)?;
    current.validate().map_err(anyhow::Error::msg)?;
    let selected = inputs
        .contexts
        .iter()
        .map(|context| &context.unit)
        .collect::<BTreeSet<_>>();
    current
        .contexts
        .retain(|context| selected.contains(&context.unit));
    ensure!(
        current.semantic_digest() == inputs.semantic_digest(),
        "TYPESCRIPT_MANIFEST_CHANGED"
    );
    add_captured(
        manifest,
        blobs,
        total,
        spool,
        descriptor.into(),
        bytes,
        (
            String::new(),
            "PREPARATION".into(),
            String::new(),
            String::new(),
        ),
    )?;
    let dependencies = discovery
        .dependency_roots
        .iter()
        .map(|root| {
            if Path::new(root).is_absolute() {
                PathBuf::from(root)
            } else {
                project.join(root)
            }
        })
        .collect::<Vec<_>>();
    for root in &dependencies {
        // Declared dependency roots authorize their descendants. Absent roots
        // are observed below without broadening access to a parent directory.
        if root.is_dir() {
            reader.borrow_mut().allow_directory(root)?;
        }
    }
    let includes = glob(&effective.config.scan.include)?;
    let excludes = glob(&effective.config.scan.exclude)?;
    let text_only = glob(&effective.config.scan.text_only)?;
    let modules = effective
        .config
        .modules
        .iter()
        .map(|module| {
            Ok((
                module.id.as_str(),
                (
                    module,
                    glob(&module.scan.include)?,
                    glob(&module.scan.exclude)?,
                    glob(&module.scan.text_only)?,
                ),
            ))
        })
        .collect::<Result<BTreeMap<_, _>>>()?;
    for context in &inputs.contexts {
        ensure!(
            reader
                .borrow_mut()
                .case_sensitive(Path::new(&context.config_path))?
                == context.case_sensitive,
            "TYPESCRIPT_CASE_RULES_STALE"
        );
        for link in &context.links {
            ensure!(
                within(Path::new(&link.path), project, &dependencies)
                    && within(Path::new(&link.allowed_root), project, &dependencies),
                "TYPESCRIPT_LINK_OUTSIDE_DECLARED_ROOTS"
            );
        }
        reader.borrow_mut().declare_links(
            &context
                .links
                .iter()
                .map(|link| DeclaredLink {
                    path: link.path.clone().into(),
                    target: link.target.clone().into(),
                })
                .collect::<Vec<_>>(),
        )?;
        for directory in &context.directories {
            let path = Path::new(&directory.path);
            let inside = within(path, project, &dependencies);
            ensure!(
                inside
                    || (!directory.listing_complete
                        && (project.starts_with(path)
                            || dependencies.iter().any(|root| root.starts_with(path)))),
                "TYPESCRIPT_DIRECTORY_OUTSIDE_DECLARED_ROOTS"
            );
            if !inside {
                reader.borrow_mut().allow_lookup(path)?;
            }
            ensure!(
                reader.borrow_mut().entry_kind(path)? == Some(EntryKind::Directory),
                "TYPESCRIPT_DIRECTORY_STALE: {}",
                path.display()
            );
            if directory.listing_complete {
                let entries = reader.borrow_mut().directory_copy(path)?;
                let mut files = BTreeSet::new();
                let mut directories = BTreeSet::new();
                for (name, kind) in entries {
                    if path == Path::new(&context.root)
                        && (name == ".code-ana"
                            || (!context.case_sensitive && name.eq_ignore_ascii_case(".code-ana")))
                    {
                        continue;
                    }
                    let is_directory = if kind == "link" {
                        let target = reader.borrow_mut().entry_kind(&path.join(&name))?;
                        ensure!(
                            matches!(target, Some(EntryKind::File | EntryKind::Directory)),
                            "TYPESCRIPT_DIRECTORY_STALE"
                        );
                        target == Some(EntryKind::Directory)
                    } else {
                        ensure!(
                            matches!(kind.as_str(), "file" | "directory"),
                            "TYPESCRIPT_DIRECTORY_ENTRY_UNSUPPORTED"
                        );
                        kind == "directory"
                    };
                    if is_directory {
                        directories.insert(name);
                    } else {
                        files.insert(name);
                    }
                }
                ensure!(
                    files
                        .iter()
                        .eq(directory.files.iter().collect::<BTreeSet<_>>().into_iter())
                        && directories.iter().eq(directory
                            .directories
                            .iter()
                            .collect::<BTreeSet<_>>()
                            .into_iter()),
                    "TYPESCRIPT_DIRECTORY_STALE: {}",
                    path.display()
                );
            }
        }
        for absent in &context.absent {
            let path = Path::new(&absent.path);
            ensure!(
                within(path, project, &dependencies)
                    || ancestor_lookup(path, project, &dependencies),
                "TYPESCRIPT_LOOKUP_OUTSIDE_DECLARED_ROOTS"
            );
            if !path.starts_with(project) {
                reader.borrow_mut().allow_lookup(path)?;
            }
            let actual = reader.borrow_mut().entry_kind(path)?;
            let wanted = match absent.kind {
                LookupKind::File => EntryKind::File,
                LookupKind::Directory => EntryKind::Directory,
            };
            ensure!(
                actual != Some(wanted),
                "TYPESCRIPT_MISSING_LOOKUP_STALE: {}",
                path.display()
            );
        }
        for file in &context.files {
            let path = Path::new(&file.path);
            ensure!(
                within(path, project, &dependencies),
                "TYPESCRIPT_INPUT_OUTSIDE_DECLARED_ROOTS"
            );
            let name = captured_path(project, path)?;
            if file.role == FileRole::Control {
                ensure!(!excludes.is_match(&name), "CONTROL_INPUT_EXCLUDED: {name}");
            }
            let (blob, digest, size) = reader.borrow_mut().file(path, 32 << 20)?;
            ensure!(
                digest == file.sha256 && size as u64 == file.bytes,
                "TYPESCRIPT_INPUT_STALE: {}",
                path.display()
            );
            capture::registry::retain_blob(blobs, total, digest.clone(), blob, size)?;
            let publish = file.role == FileRole::Publish;
            let scope = file.scope.clone().unwrap_or_else(|| {
                if file.role == FileRole::Control {
                    "CONTROL".into()
                } else {
                    "DEPENDENCY".into()
                }
            });
            let module_selected = if let Some((module, include, exclude, text)) =
                file.module.as_deref().and_then(|id| modules.get(id))
            {
                ensure!(
                    SourceLanguage::lookup(&module.language)
                        .is_some_and(|language| language.backend() == AnalysisBackend::TypeScript),
                    "TYPESCRIPT_MODULE_OWNER_CONFLICT"
                );
                (module.scan.scopes.is_empty() || module.scan.scopes.contains(&scope))
                    && (module.scan.include.is_empty() || include.is_match(&name))
                    && !exclude.is_match(&name)
                    && !text.is_match(&name)
                    && path.starts_with(project.join(&module.root))
                    && (module.source_roots.is_empty()
                        || module.source_roots.iter().any(|root| {
                            root.scope == scope
                                && path.starts_with(project.join(&module.root).join(&root.path))
                        }))
            } else {
                true
            };
            let selected = publish
                && module_selected
                && includes.is_match(&name)
                && !excludes.is_match(&name)
                && !text_only.is_match(&name)
                && effective.config.scan.scopes.contains(&scope);
            let owner = SourceUnit {
                path: name,
                hash: digest,
                size: size as u64,
                module: file.module.clone().unwrap_or_default(),
                scope,
                language: file
                    .language
                    .map(|language| language.as_str().to_owned())
                    .unwrap_or_default(),
                world: file
                    .world
                    .as_ref()
                    .map(|world| world.to_string())
                    .unwrap_or_else(|| {
                        if file.role == FileRole::Control {
                            "files".into()
                        } else {
                            key("typescript-auxiliary", &[&context.unit])
                        }
                    }),
                auxiliary: !selected && file.role != FileRole::Control,
            };
            capture::registry::register(manifest, owner)?;
        }
    }
    Ok(())
}
