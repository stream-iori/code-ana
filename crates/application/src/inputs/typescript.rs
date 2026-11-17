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
    ensure!(
        candidates.len() <= 1,
        "TYPESCRIPT_CONTEXT_CONFLICT: ambiguous script modules at compiler root"
    );
    Ok(module_id(entry, &entry.root, config))
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
    let inputs: codeana_model::typescript::Inputs = serde_json::from_slice(&fs::read(raw)?)?;
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
    write_json(normalized, &inputs)?;
    Ok(inputs)
}
