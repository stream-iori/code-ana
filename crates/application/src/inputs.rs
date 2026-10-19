//! Explicit CLI-side input preparation. The daemon never invokes these tools.
use crate::input_process::run;
use anyhow::{Context, Result, bail, ensure};
use codeana_config::{Module, ProjectConfig, inputs::*};
use codeana_model::{hash, hash_file, python, rust};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::Write,
    os::fd::AsRawFd,
    path::{Path, PathBuf},
    process::Command,
    sync::atomic::{AtomicBool, Ordering},
};

#[derive(serde::Serialize)]
pub struct PreparationSummary {
    pub status: String,
    pub manifest: String,
    pub units: Vec<PreparedUnit>,
    pub report: String,
}
fn artifact(project: &Path, path: &Path) -> Result<InputArtifact> {
    Ok(InputArtifact {
        path: path
            .strip_prefix(project)?
            .to_str()
            .context("INPUT_PATH_UTF8")?
            .into(),
        sha256: hash_file(path)?,
        bytes: path.metadata()?.len(),
    })
}
fn write_json(path: &Path, value: &impl serde::Serialize) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(value)?;
    ensure!(bytes.len() <= 32 << 20, "INPUT_ARTIFACT_BUDGET");
    let mut file = File::create(path)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    Ok(())
}
fn script(language: &str) -> Result<PathBuf> {
    let executable = std::env::current_exe()?;
    let installed = executable
        .parent()
        .context("INPUT_EXECUTABLE_DIRECTORY")?
        .join(format!("codeana-export-{language}-inputs"));
    if installed.is_file() {
        return Ok(installed);
    }
    let source = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join(format!("../../scripts/export-{language}-inputs.py"));
    ensure!(source.is_file(), "INPUT_PROVIDER_MISSING: {language}");
    Ok(source)
}
fn child_config(config: &ProjectConfig, language: &str, root: &str) -> ProjectConfig {
    let mut child = config.clone();
    child.inputs = None;
    if language != "java" {
        child.languages.java = None;
    }
    if language != "rust" {
        child.languages.rust = None;
    }
    if language != "python" {
        child.languages.python = None;
    }
    child.modules.retain(|m| {
        m.language == language
            && (root == "." || m.root == root || m.root.starts_with(&format!("{root}/")))
    });
    child
}
fn module_id(entry: &InputEntry, root: &str, config: &ProjectConfig) -> String {
    config
        .modules
        .iter()
        .find(|m| m.language == entry.language && m.root == root)
        .map(|m| m.id.clone())
        .unwrap_or_else(|| {
            if root == entry.root {
                entry.id.clone()
            } else {
                format!("{}-{}", entry.id, &hash(root)[..16])
            }
        })
}

pub fn prepare(project: &Path, cancel: &AtomicBool) -> Result<PreparationSummary> {
    let project = project.canonicalize()?;
    let config_path = checked_path(&project, ".code-ana/config.toml")?;
    let before = hash_file(&config_path)?;
    let config = load(&project)?;
    let preparation = config.inputs.as_ref().unwrap();
    let state = project.join(".code-ana/inputs");
    ensure!(!state.is_symlink(), "INPUT_STATE_SYMLINK");
    fs::create_dir_all(&state)?;
    let lock_path = state.join("prepare.lock");
    ensure!(!lock_path.is_symlink(), "INPUT_LOCK_SYMLINK");
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(lock_path)?;
    ensure!(
        unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "INPUT_PREPARATION_BUSY"
    );
    let generation = state.join(codeana_model::new_id("generation"));
    fs::create_dir(&generation)?;
    let mut catalog = InputCatalog {
        schema_version: 1,
        config_sha256: before.clone(),
        units: vec![],
    };
    let result = (|| -> Result<()> {
        let mut handled = BTreeSet::new();
        for entry in &preparation.entries {
            ensure!(!cancel.load(Ordering::Acquire), "CANCELLED");
            if !handled.insert(entry.id.clone()) {
                continue;
            }
            let members = if entry.language == "python" {
                preparation
                    .entries
                    .iter()
                    .filter(|e| {
                        e.language == "python"
                            && (e.id == entry.id
                                || entry.python.environment.is_some()
                                    && e.python.environment == entry.python.environment)
                    })
                    .map(|e| e.id.clone())
                    .collect::<Vec<_>>()
            } else {
                vec![entry.id.clone()]
            };
            handled.extend(members.clone());
            let directory = generation.join(&entry.id);
            fs::create_dir(&directory)?;
            let raw = directory.join("raw.json");
            let normalized = directory.join("inputs.json");
            let log = directory.join("prepare.log");
            let mut child = child_config(&config, &entry.language, &entry.root);
            let input_config = directory.join("request.toml");
            fs::write(&input_config, toml_text(&child)?)?;
            let manifest = Path::new(&entry.root).join(entry.manifest.as_deref().unwrap_or(
                match entry.language.as_str() {
                    "java" => "pom.xml",
                    "rust" => "Cargo.toml",
                    _ => "pyproject.toml",
                },
            ));
            let mut mode = InputMode::Semantic;
            let mut reason = None;
            let plain = entry.language == "java"
                && child
                    .languages
                    .java
                    .as_ref()
                    .is_some_and(|v| v.discovery.build_system == "plain");
            let success = if plain {
                ensure!(
                    !child.modules.is_empty()
                        && child.modules.iter().all(|m| !m.source_roots.is_empty()),
                    "JAVA_PLAIN_SOURCE_ROOT_REQUIRED"
                );
                fs::write(&log, "prepared explicit Java sources; no Maven execution\n")?;
                true
            } else {
                match entry.language.as_str() {
                    "java" => {
                        let options = crate::maven_inputs::ExportOptions {
                            project: project.clone(),
                            manifest: Some(project.join(&manifest)),
                            output: raw.clone(),
                            maven: entry.java.maven.clone(),
                            settings: entry.java.settings.clone(),
                            profiles: entry.java.profiles.clone(),
                            modules: vec![],
                            scopes: if config.scan.scopes.is_empty() {
                                vec!["MAIN".into()]
                            } else {
                                config
                                    .scan
                                    .scopes
                                    .iter()
                                    .filter(|s| matches!(s.as_str(), "MAIN" | "TEST"))
                                    .cloned()
                                    .collect()
                            },
                            offline: entry.java.offline,
                            source_only: entry.java.source_only,
                            timeout: entry.java.timeout,
                            extension: entry.java.extension.clone(),
                            java_home: entry.java.java_home.clone(),
                        };
                        match crate::maven_inputs::export(&options, cancel) {
                            Ok(mut inputs) => {
                                codeana_config::java_inputs::JavaInputs::read(
                                    &project,
                                    &artifact(&project, &raw)?.path,
                                )?;
                                let names = inputs
                                    .modules
                                    .iter()
                                    .map(|m| (m.id.clone(), module_id(entry, &m.root, &config)))
                                    .collect::<BTreeMap<_, _>>();
                                for m in &mut inputs.modules {
                                    m.id = names[&m.id].clone();
                                    for deps in m.dependencies.values_mut() {
                                        for id in deps {
                                            *id = names[id].clone();
                                        }
                                    }
                                    for dep in m.dependency_order.values_mut().flatten() {
                                        if let codeana_config::java_inputs::DependencyInput::Reactor {
                                        module,
                                        ..
                                    } = dep
                                    {
                                        *module = names[module].clone();
                                    }
                                    }
                                }
                                if let Some(failure) = &mut inputs.dependency_failure {
                                    failure.module = names[&failure.module].clone();
                                }
                                if !inputs.resolved_classpath {
                                    mode = InputMode::SourceOnly;
                                    reason = Some(if inputs.dependency_mode == codeana_config::java_inputs::DependencyMode::SourceOnlyRequested { "SOURCE_ONLY_REQUESTED" } else { "CLASSPATH_UNAVAILABLE" }.into());
                                }
                                child.modules = inputs
                                    .modules
                                    .iter()
                                    .map(|m| {
                                        let mut module = config
                                            .modules
                                            .iter()
                                            .find(|v| v.language == "java" && v.root == m.root)
                                            .cloned()
                                            .unwrap_or_default();
                                        module.id = m.id.clone();
                                        module.language = "java".into();
                                        module.root = m.root.clone();
                                        if module.source_roots.is_empty() {
                                            module.source_roots = m
                                                .source_roots
                                                .iter()
                                                .filter(|r| {
                                                    options.scopes.contains(&r.scope)
                                                        || r.scope == "MAIN"
                                                            && options
                                                                .scopes
                                                                .iter()
                                                                .any(|s| s == "TEST")
                                                })
                                                .map(|r| codeana_config::SourceRoot {
                                                    path: r.path.clone(),
                                                    scope: r.scope.clone(),
                                                    label: None,
                                                })
                                                .collect();
                                        }
                                        module.analysis.source_version =
                                            Some(m.source_version.clone());
                                        module
                                    })
                                    .collect();
                                write_json(&normalized, &inputs)?;
                                fs::write(&log, reason.as_deref().unwrap_or("prepared"))?;
                                true
                            }
                            Err(error) => {
                                let message = format!("{error:#}");
                                let ordinary = message
                                    .starts_with("MAVEN_INPUTS_FAILED status=exit status:")
                                    || message.contains("pom.xml required inside project")
                                    || message.contains("Maven executable required");
                                ensure!(ordinary && !cancel.load(Ordering::Acquire), "{message}");
                                fs::write(&log, crate::input_process::failure_details(&message))?;
                                false
                            }
                        }
                    }
                    "rust" => {
                        let mut command = Command::new("python3");
                        command
                            .arg("-I")
                            .arg(script("rust")?)
                            .arg("--project")
                            .arg(&project)
                            .arg("--output")
                            .arg(&raw)
                            .arg("--entry-manifest")
                            .arg(&manifest)
                            .arg("--namespace")
                            .arg(&entry.id)
                            .arg("--config-file")
                            .arg(&input_config)
                            .arg("--cargo")
                            .arg(&entry.rust.cargo)
                            .arg("--rustc")
                            .arg(&entry.rust.rustc)
                            .current_dir(&project);
                        for value in &entry.rust.cargo_configs {
                            command.arg("--cargo-config").arg(value);
                        }
                        if let Some(path) = &entry.rust.build_messages {
                            command.arg("--build-messages").arg(path);
                        }
                        if run(&mut command, &log, cancel, 300)? {
                            let mut inputs: rust::Inputs =
                                serde_json::from_slice(&fs::read(&raw)?)?;
                            inputs.validate().map_err(anyhow::Error::msg)?;
                            child.modules.clear();
                            let mut seen = BTreeSet::new();
                            for input in inputs.crates.iter_mut().filter(|c| c.publish) {
                                input.module = module_id(entry, &input.directory, &config);
                                if seen.insert(input.module.clone()) {
                                    let mut module = config
                                        .modules
                                        .iter()
                                        .find(|m| m.language == "rust" && m.root == input.directory)
                                        .cloned()
                                        .unwrap_or_default();
                                    module.id = input.module.clone();
                                    module.root = input.directory.clone();
                                    module.language = "rust".into();
                                    child.modules.push(module);
                                }
                            }
                            inputs.policy = codeana_config::rust_inputs::policy(&child)?;
                            write_json(&normalized, &inputs)?;
                            true
                        } else {
                            false
                        }
                    }
                    "python" => {
                        #[derive(serde::Serialize)]
                        struct PythonRequest<'a> {
                            project: &'a Path,
                            output: &'a Path,
                            config: &'a ProjectConfig,
                            members: &'a [String],
                        }
                        let request = directory.join("request.json");
                        write_json(
                            &request,
                            &PythonRequest {
                                project: &project,
                                output: &raw,
                                config: &config,
                                members: &members,
                            },
                        )?;
                        let mut command = Command::new("python3");
                        command
                            .arg("-I")
                            .arg(script("python")?)
                            .arg("--prepare-request")
                            .arg(request)
                            .current_dir(&project);
                        if run(&mut command, &log, cancel, 300)? {
                            let inputs: python::Inputs = serde_json::from_slice(&fs::read(&raw)?)?;
                            inputs.validate().map_err(anyhow::Error::msg)?;
                            child.modules.clear();
                            let mut seen = BTreeSet::new();
                            for root in inputs
                                .roots
                                .iter()
                                .filter(|r| inputs.contexts.iter().any(|c| c.publishes(r)))
                            {
                                let owner = root.module.as_ref().context("PYTHON_ROOT_MODULE")?;
                                if seen.insert(owner.clone()) {
                                    let original = config.modules.iter().find(|m| &m.id == owner);
                                    let mut module = original.cloned().unwrap_or_else(|| Module {
                                        id: owner.clone(),
                                        language: "python".into(),
                                        root: root.path.clone(),
                                        ..Default::default()
                                    });
                                    module.source_roots.clear();
                                    child.modules.push(module);
                                }
                            }
                            if inputs.contexts.iter().any(|c| !c.gaps.is_empty()) {
                                reason = Some("PYTHON_DEPENDENCY_ROOT_MISSING".into());
                            }
                            write_json(&normalized, &inputs)?;
                            true
                        } else {
                            false
                        }
                    }
                    _ => bail!("INPUT_LANGUAGE"),
                }
            };
            let mut artifacts = vec![];
            let configuration = if success {
                let normalized_path = if plain {
                    String::new()
                } else {
                    artifact(&project, &normalized)?.path
                };
                match entry.language.as_str() {
                    "java" if plain => {}
                    "java" => {
                        child
                            .languages
                            .java
                            .as_mut()
                            .unwrap()
                            .discovery
                            .inputs_manifest = Some(normalized_path)
                    }
                    "rust" => {
                        child
                            .languages
                            .rust
                            .as_mut()
                            .unwrap()
                            .discovery
                            .inputs_manifest = Some(normalized_path)
                    }
                    "python" => {
                        child
                            .languages
                            .python
                            .as_mut()
                            .unwrap()
                            .discovery
                            .inputs_manifest = Some(normalized_path)
                    }
                    _ => unreachable!(),
                }
                let path = directory.join("config.toml");
                fs::write(&path, toml_text(&child)?)?;
                if !plain {
                    artifacts.push(artifact(&project, &raw)?);
                    artifacts.push(artifact(&project, &normalized)?);
                }
                Some(artifact(&project, &path)?)
            } else {
                mode = InputMode::FilesOnly;
                reason = Some("INPUT_CONTEXT_UNAVAILABLE".into());
                None
            };
            catalog.units.push(PreparedUnit {
                id: entry.id.clone(),
                language: entry.language.clone(),
                entries: members.clone(),
                modules: if child.modules.is_empty() {
                    members.clone()
                } else {
                    child.modules.iter().map(|m| m.id.clone()).collect()
                },
                roots: members
                    .iter()
                    .map(|id| {
                        preparation
                            .entries
                            .iter()
                            .find(|e| &e.id == id)
                            .unwrap()
                            .root
                            .clone()
                    })
                    .collect(),
                mode,
                reason,
                log: Some(artifact(&project, &log)?.path),
                config: configuration,
                artifacts,
            });
        }
        ensure!(!cancel.load(Ordering::Acquire), "CANCELLED");
        ensure!(hash_file(&config_path)? == before, "INPUT_CONFIG_CHANGED");
        let temporary = generation.join("catalog.json");
        write_json(&temporary, &catalog)?;
        // Validate normalized views before making any generation active.
        let mut module_ids = BTreeSet::new();
        let mut module_roots = BTreeSet::new();
        for unit in &catalog.units {
            if let Some(config) = &unit.config {
                let bytes = read_artifact(&project, config)?;
                let effective = validate_configuration(&project, std::str::from_utf8(&bytes)?)?;
                for module in &effective.config.modules {
                    ensure!(
                        module_ids.insert(module.id.clone())
                            && module_roots.insert((module.language.clone(), module.root.clone())),
                        "INPUT_OVERLAPPING_NATIVE_WORKSPACE: {}",
                        module.root
                    );
                }
            }
        }
        let output = project.join(&preparation.manifest);
        let parent = output.parent().context("INPUT_MANIFEST_PARENT")?;
        ensure!(
            parent.canonicalize()?.starts_with(&project) && !output.is_symlink(),
            "INPUT_MANIFEST_PATH"
        );
        fs::rename(temporary, &output)?;
        File::open(parent)?.sync_all()?;
        Ok(())
    })();
    let summary = PreparationSummary {
        status: if result.is_err() {
            "failed"
        } else if catalog
            .units
            .iter()
            .any(|u| u.mode != InputMode::Semantic || u.reason.is_some())
        {
            "partial"
        } else {
            "prepared"
        }
        .into(),
        manifest: preparation.manifest.clone(),
        units: catalog.units,
        report: generation
            .join("report.json")
            .strip_prefix(&project)?
            .to_str()
            .context("INPUT_PATH_UTF8")?
            .into(),
    };
    #[derive(serde::Serialize)]
    struct PreparationReport<'a> {
        schema_version: u32,
        status: &'a str,
        manifest: &'a str,
        units_total: usize,
        units_omitted: usize,
        units: Vec<crate::reports::UnitReport>,
        error: Option<String>,
    }
    write_json(
        &generation.join("report.json"),
        &PreparationReport {
            schema_version: 1,
            status: &summary.status,
            manifest: &summary.manifest,
            units_total: summary.units.len(),
            units_omitted: summary.units.len().saturating_sub(20),
            units: summary
                .units
                .iter()
                .take(20)
                .map(|u| crate::reports::UnitReport {
                    id: u.id.clone(),
                    language: u.language.clone(),
                    entries: u.entries.clone(),
                    mode: u.mode,
                    reason_code: u.reason.clone(),
                    log: u.log.clone(),
                })
                .collect(),
            error: result
                .as_ref()
                .err()
                .map(|e| crate::input_process::failure_details(&format!("{e:#}"))),
        },
    )?;
    result.with_context(|| {
        format!(
            "preparation report: {}",
            generation.join("report.json").display()
        )
    })?;
    Ok(summary)
}
fn toml_text(config: &ProjectConfig) -> Result<String> {
    // Keep TOML serialization with configuration ownership.
    codeana_config::inputs::config_text(config)
}
