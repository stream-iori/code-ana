//! CLI-only preparation via a fresh native worker. No compiler is loaded here.
use super::*;

pub(super) fn is_script(language: &str) -> bool {
    matches!(language, "javascript" | "typescript")
}
fn compiler_config(entry: &InputEntry) -> PathBuf {
    let manifest = entry
        .manifest
        .as_deref()
        .unwrap_or(if entry.language == "javascript" {
            "jsconfig.json"
        } else {
            "tsconfig.json"
        });
    if entry.root == "." {
        PathBuf::from(manifest)
    } else {
        Path::new(&entry.root).join(manifest)
    }
}

fn module(entry: &InputEntry, config: &ProjectConfig) -> Result<String> {
    let candidates = config
        .modules
        .iter()
        .filter(|module| is_script(&module.language) && module.root == entry.root)
        .collect::<Vec<_>>();
    if candidates.len() > 1
        || (candidates.is_empty() && script_modules(entry, config).next().is_some())
    {
        return Ok(codeana_model::key(
            "typescript-context-owner",
            &[
                compiler_config(entry).to_str().context("INPUT_PATH_UTF8")?,
                &entry.typescript.scope,
            ],
        ));
    }
    Ok(module_id(entry, &entry.root, config))
}

/// Module language selects the shared backend, rather than splitting one Program
/// into separate JavaScript and TypeScript executions.
fn script_modules<'a>(
    entry: &'a InputEntry,
    config: &'a ProjectConfig,
) -> impl Iterator<Item = &'a Module> {
    config.modules.iter().filter(move |module| {
        is_script(&module.language)
            && (entry.root == "." || Path::new(&module.root).starts_with(&entry.root))
    })
}

fn assign_owners(
    project: &Path,
    config: &ProjectConfig,
    entry: &InputEntry,
    inputs: &mut codeana_model::typescript::Inputs,
) -> Result<()> {
    use codeana_model::typescript::FileRole;
    let modules = script_modules(entry, config).collect::<Vec<_>>();
    if modules.is_empty() {
        return Ok(());
    }
    for context in &mut inputs.contexts {
        for file in &mut context.files {
            if file.role != FileRole::Publish {
                continue;
            }
            let path = Path::new(&file.path);
            let mut best = None;
            let mut owners = BTreeSet::new();
            for module in &modules {
                let module_root = project.join(&module.root);
                let roots = if module.source_roots.is_empty() {
                    vec![module_root]
                } else {
                    module
                        .source_roots
                        .iter()
                        .filter(|root| root.scope == entry.typescript.scope)
                        .map(|root| module_root.join(&root.path))
                        .collect()
                };
                for root in roots {
                    if !path.starts_with(&root) {
                        continue;
                    }
                    let depth = root.components().count();
                    if best.is_none_or(|previous| depth > previous) {
                        best = Some(depth);
                        owners.clear();
                    }
                    if best == Some(depth) {
                        owners.insert(module.id.as_str());
                    }
                }
            }
            ensure!(
                owners.len() <= 1,
                "TYPESCRIPT_MODULE_OWNER_CONFLICT: {}",
                file.path
            );
            if let Some(owner) = owners.first() {
                file.module = Some((*owner).to_owned());
                file.scope = Some(entry.typescript.scope.clone());
                file.world = Some(
                    codeana_model::key(
                        "typescript-world",
                        &[&context.unit, owner, &entry.typescript.scope],
                    )
                    .into(),
                );
            } else {
                file.role = FileRole::Auxiliary;
                file.module = None;
                file.scope = None;
                file.world = None;
            }
        }
    }
    inputs.validate().map_err(anyhow::Error::msg)
}

pub(super) fn members(entry: &InputEntry, config: &ProjectConfig) -> Result<Vec<String>> {
    let mut members = Vec::new();
    let module = module(entry, config)?;
    for candidate in &config.inputs.as_ref().context("INPUTS_REQUIRED")?.entries {
        if is_script(&candidate.language)
            && candidate.root == entry.root
            && compiler_config(candidate) == compiler_config(entry)
            && candidate.typescript.scope == entry.typescript.scope
        {
            ensure!(
                self::module(candidate, config)? == module,
                "TYPESCRIPT_CONTEXT_CONFLICT: same compiler context has different modules"
            );
            members.push(candidate.id.clone());
        }
    }
    members.sort();
    Ok(members)
}

pub(super) fn prepare(
    project: &Path,
    config: &ProjectConfig,
    entry: &InputEntry,
    raw: &Path,
    normalized: &Path,
    log: &Path,
    cancel: &AtomicBool,
) -> Result<codeana_model::typescript::Inputs> {
    let binary = std::env::current_exe()?.with_file_name("codeana-typescript-worker");
    ensure!(binary.is_file(), "TYPESCRIPT_PREPARER_MISSING");
    let sdk = std::env::var_os("CODEANA_TYPESCRIPT_SDK")
        .map(PathBuf::from)
        .unwrap_or_else(|| binary.parent().unwrap().join("../lib/typescript/current"));
    ensure!(
        sdk.is_absolute() && sdk.is_dir(),
        "TYPESCRIPT_SDK_REQUIRED: install the TypeScript SDK or set CODEANA_TYPESCRIPT_SDK"
    );
    let sdk = sdk.canonicalize()?;
    let root = checked_path(project, &entry.root)?.canonicalize()?;
    ensure!(
        !root.starts_with(project.join(".code-ana")),
        "TYPESCRIPT_PRIVATE_ROOT"
    );
    let manifest = checked_path(
        project,
        compiler_config(entry).to_str().context("INPUT_PATH_UTF8")?,
    )?;
    let module = module(entry, config)?;
    let relative = manifest
        .strip_prefix(project)?
        .to_str()
        .context("INPUT_PATH_UTF8")?;
    let unit = codeana_model::key(
        "typescript-context",
        &[relative, &module, &entry.typescript.scope],
    );
    let world = codeana_model::key(
        "typescript-world",
        &[&unit, &module, &entry.typescript.scope],
    );
    let (discovery, languages) = codeana_config::typescript_inputs::configuration(config)?
        .context("TYPESCRIPT_CONFIGURATION_REQUIRED")?;
    let limits = config
        .resources
        .index
        .analysis
        .resolve(&Default::default())?;
    let scratch = tempfile::Builder::new()
        .prefix("codeana-ts-prepare-")
        .tempdir()?;
    let mut command = Command::new(binary);
    command
        .arg("--sdk")
        .arg(sdk)
        .arg("--prepare-project")
        .arg(root)
        .arg("--prepare-config")
        .arg(manifest)
        .arg("--output")
        .arg(raw)
        .arg("--scratch")
        .arg(scratch.path().join("engine"))
        .arg("--unit")
        .arg(&unit)
        .arg("--module")
        .arg(module)
        .arg("--world")
        .arg(world)
        .arg("--scope")
        .arg(&entry.typescript.scope)
        .arg("--allowed-root")
        .arg(project)
        .arg("--prepare-deadline-ms")
        .arg((entry.typescript.timeout * 1000).to_string())
        .arg("--max-work")
        .arg(limits.max_work.to_string())
        .arg("--max-nodes")
        .arg(limits.max_nodes.to_string())
        .arg("--max-payload-bytes")
        .arg(limits.max_payload_bytes.to_string())
        .arg("--max-depth")
        .arg(limits.max_depth.to_string());
    for language in &languages {
        command.arg("--publish-language").arg(language.as_str());
    }
    for root in &discovery.dependency_roots {
        command
            .arg("--allowed-root")
            .arg(if Path::new(root).is_absolute() {
                PathBuf::from(root)
            } else {
                project.join(root)
            });
    }
    ensure!(
        run(&mut command, log, cancel, entry.typescript.timeout + 3)?,
        "TYPESCRIPT_PREPARATION_FAILED: {}",
        log.display()
    );
    ensure!(raw.metadata()?.len() <= 32 << 20, "INPUT_ARTIFACT_BUDGET");
    let mut inputs: codeana_model::typescript::Inputs = serde_json::from_slice(&fs::read(raw)?)?;
    inputs.validate().map_err(anyhow::Error::msg)?;
    ensure!(
        inputs.contexts.len() == 1
            && inputs.contexts[0].unit == unit
            && inputs.contexts[0]
                .publish_languages
                .iter()
                .copied()
                .collect::<BTreeSet<_>>()
                == languages.into_iter().collect::<BTreeSet<_>>(),
        "TYPESCRIPT_PREPARATION_IDENTITY"
    );
    assign_owners(project, config, entry, &mut inputs)?;
    write_json(normalized, &inputs)?;
    Ok(inputs)
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::typescript::{Context as ProjectContext, FileRole, InputFile, Inputs};

    fn fixture() -> (ProjectConfig, InputEntry, Inputs) {
        let config = serde_json::from_value(serde_json::json!({
            "schema_version":1,"kind":"project",
            "modules":[
                {"id":"app","language":"typescript","root":".","source_roots":[{"path":"src","scope":"MAIN"}]},
                {"id":"lib","language":"javascript","root":"src/lib"}
            ]
        })).unwrap();
        let entry = InputEntry {
            id: "entry".into(),
            root: ".".into(),
            language: "typescript".into(),
            ..Default::default()
        };
        let compiler = serde_json::from_value(serde_json::json!({
            "adapter":"fixture","upstream_commit":"a".repeat(40),"typescript_commit":"b".repeat(40),
            "compiler_version":"7","go_version":"go1.26.2",
            "library_sha256":"c".repeat(64),"header_sha256":"d".repeat(64),"standard_library_digest":"e".repeat(64),
            "analysis_proto_sha256":"f".repeat(64),"graph_proto_sha256":"0".repeat(64)
        })).unwrap();
        let context = ProjectContext {
            unit: "context".into(),
            root: "/project".into(),
            config_path: "/project/tsconfig.json".into(),
            publish_languages: vec![SourceLanguage::JavaScript, SourceLanguage::TypeScript],
            case_sensitive: true,
            root_files: vec!["/project/src/a.ts".into()],
            references: vec![],
            files: ["src/a.ts", "src/lib/b.js", "other/c.ts", "src/library/d.ts"]
                .into_iter()
                .map(|name| InputFile {
                    path: format!("/project/{name}"),
                    sha256: "a".repeat(64),
                    bytes: 1,
                    role: FileRole::Publish,
                    language: Some(if name.ends_with(".js") {
                        SourceLanguage::JavaScript
                    } else {
                        SourceLanguage::TypeScript
                    }),
                    module: Some("initial".into()),
                    scope: Some("MAIN".into()),
                    world: Some("initial".into()),
                })
                .collect(),
            directories: vec![],
            links: vec![],
            absent: vec![],
        };
        (
            config,
            entry,
            Inputs {
                schema_version: 1,
                compiler,
                contexts: vec![context],
            },
        )
    }

    #[test]
    fn longest_source_root_owns_each_file_in_one_shared_context() {
        let (mut config, entry, mut inputs) = fixture();
        assign_owners(Path::new("/project"), &config, &entry, &mut inputs).unwrap();
        let files = &inputs.contexts[0].files;
        assert_eq!(files[0].module.as_deref(), Some("app"));
        assert_eq!(files[1].module.as_deref(), Some("lib"));
        assert_ne!(files[0].world, files[1].world);
        assert_eq!(files[2].role, FileRole::Auxiliary);
        assert!(files[2].module.is_none() && files[2].scope.is_none() && files[2].world.is_none());
        assert_eq!(files[3].module.as_deref(), Some("app"));
        let expected = inputs.semantic_digest();
        config.modules.reverse();
        let (_, _, mut reordered) = fixture();
        assign_owners(Path::new("/project"), &config, &entry, &mut reordered).unwrap();
        assert_eq!(reordered.semantic_digest(), expected);
    }

    #[test]
    fn equal_depth_owners_conflict_but_other_scope_is_auxiliary() {
        let (mut config, entry, inputs) = fixture();
        let mut duplicate = config.modules[0].clone();
        duplicate.id = "duplicate".into();
        config.modules.push(duplicate);
        assert!(
            assign_owners(Path::new("/project"), &config, &entry, &mut inputs.clone())
                .unwrap_err()
                .to_string()
                .contains("TYPESCRIPT_MODULE_OWNER_CONFLICT")
        );
        config.modules.pop();
        config.modules[0].source_roots[0].scope = "TEST".into();
        let mut inputs = inputs;
        assign_owners(Path::new("/project"), &config, &entry, &mut inputs).unwrap();
        assert_eq!(inputs.contexts[0].files[0].role, FileRole::Auxiliary);
        assert_eq!(inputs.contexts[0].files[1].module.as_deref(), Some("lib"));
    }
}
