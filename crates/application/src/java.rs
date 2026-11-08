//! Prepare a Java task from the captured bytes and the daemon's frozen user generation.
use crate::resources::DiskBudget;
use anyhow::{Context, Result, ensure};
use codeana_config::{Capture, UserConfig};
use codeana_contracts::{map, worker as w};
use codeana_model::*;
use serde::Serialize;
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};

pub struct PreparedJava {
    pub library: PathBuf,
    pub backend: &'static str,
    pub config: w::JavaConfig,
    pub disk_budget: DiskBudget,
    _scratch: tempfile::TempDir,
    toolchain: Vec<(PathBuf, String)>,
}
impl PreparedJava {
    pub fn verify(&self) -> Result<()> {
        self.disk_budget.check()?;
        for (path, expected) in &self.toolchain {
            ensure!(
                hash_file(path)? == *expected,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::ToolchainInputDrift,
                    format!("{}", path.display())
                )
            );
        }
        Ok(())
    }
}
pub fn installed_library() -> Result<PathBuf> {
    let exe = std::env::current_exe()?;
    let bin = exe.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::BinaryDirectory,
        "",
    ))?;
    let backend = std::env::var("CODEANA_JAVA_BACKEND").unwrap_or_else(|_| "native".into());
    ensure!(
        matches!(backend.as_str(), "native" | "jvm"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::JavaBackendUnsupported, "")
    );
    if backend == "jvm" {
        let installed = bin.join("../lib/java/jvm/runner.jar");
        if installed.is_file() {
            return Ok(installed.canonicalize()?);
        }
        if cfg!(debug_assertions) {
            let dev = Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../lang-java-adaptor/target/jvm/runner.jar");
            if dev.is_file() {
                return Ok(dev.canonicalize()?);
            }
        }
        anyhow::bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::UnsupportedAdapter,
            "explicit Java JVM bundle is not installed".to_string()
        ));
    }
    let name = if cfg!(target_os = "macos") {
        "libcodeana_java.dylib"
    } else {
        "libcodeana_java.so"
    };
    let installed = bin.join("../lib/java/native").join(name);
    if installed.is_file() {
        return Ok(installed.canonicalize()?);
    }
    if cfg!(debug_assertions) {
        let dev = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../lang-java-adaptor/target/native")
            .join(name);
        if dev.is_file() {
            return Ok(dev.canonicalize()?);
        }
    }
    anyhow::bail!(codeana_model::DomainError::new(
        codeana_model::ErrorCode::UnsupportedAdapter,
        "Java native bundle is not installed".to_string()
    ))
}

fn host_toolchain_inputs(home: &Path) -> Result<(String, Vec<(PathBuf, String)>)> {
    let mut files = Vec::new();
    for entry in walkdir::WalkDir::new(home).follow_links(false) {
        let entry = entry?;
        if !entry.path().is_file() {
            continue;
        }
        let relative = entry.path().strip_prefix(home)?;
        if relative.starts_with("bin")
            || relative.starts_with("lib")
            || relative == Path::new("release")
        {
            files.push((entry.path().to_path_buf(), hash_file(entry.path())?));
        }
    }
    files.sort();
    let identity = digest(
        &files
            .iter()
            .map(|(path, sha)| (path.strip_prefix(home).unwrap(), sha))
            .collect::<Vec<_>>(),
    );
    Ok((identity, files))
}
fn compiler_parallelism(
    memory: u64,
    heap: u64,
    requested: Option<u32>,
    native_heap: u64,
) -> Result<u32> {
    let mib = 1024 * 1024;
    let host = native_heap + 256 * mib;
    if let Some(count) = requested {
        ensure!(
            (1..=8).contains(&count),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaParallelismRange, "")
        );
        // Reserve native host/model space and per-JVM non-heap space. RSS monitoring
        // remains authoritative; a reservation is not a capacity guarantee.
        ensure!(
            memory >= host + u64::from(count) * (heap + 64 * mib),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaParallelismMemoryBudget,
                ""
            )
        );
        return Ok(count);
    }
    Ok(if memory >= host + 2 * (heap + 64 * mib) {
        2
    } else {
        1
    })
}
fn context_worlds(
    manifest: &Manifest,
    module: &str,
    xml_enabled: bool,
) -> BTreeMap<String, String> {
    manifest
        .files
        .iter()
        .filter(|source| {
            source.world == key("world", &[module, &source.scope])
                && (source.language == "java"
                    || (xml_enabled
                        && source.module == module
                        && source.language.is_empty()
                        && matches!(source.scope.as_str(), "MAIN" | "TEST")
                        && source.path.ends_with(".xml")))
        })
        .map(|source| (source.scope.clone(), source.world.clone()))
        .collect()
}

struct JavaBundle {
    library: PathBuf,
    backend: &'static str,
    root: PathBuf,
}
struct PreparedInputs {
    scratch: tempfile::TempDir,
    disk_budget: DiskBudget,
    input: PathBuf,
}
struct JavaToolchain {
    release: u32,
    actual_version: String,
    aliases: Vec<String>,
    home: PathBuf,
    inputs: Vec<(PathBuf, String)>,
    digest: String,
    host_identity: Option<String>,
}

pub fn prepare(c: &Capture, user: &UserConfig, user_home: &Path) -> Result<PreparedJava> {
    let bundle = prepare_bundle()?;
    let inputs = prepare_captured_inputs(c, user)?;
    let modules: Vec<_> = c
        .effective
        .config
        .modules
        .iter()
        .filter(|m| m.language == "java")
        .collect();
    let toolchain = prepare_toolchain(user, &modules)?;
    let contexts = prepare_contexts(c, &inputs.input, modules)?;
    let config = prepare_config(
        c,
        user,
        user_home,
        &bundle,
        &inputs.scratch,
        &toolchain,
        contexts,
    )?;
    Ok(PreparedJava {
        library: bundle.library,
        backend: bundle.backend,
        config,
        disk_budget: inputs.disk_budget,
        _scratch: inputs.scratch,
        toolchain: toolchain.inputs,
    })
}
fn prepare_bundle() -> Result<JavaBundle> {
    let library = installed_library()?;
    let backend = if library.extension().is_some_and(|s| s == "jar") {
        "jvm"
    } else {
        "native"
    };
    let parent = library.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::BundleRoot,
        "",
    ))?;
    let bundle = if backend == "jvm" {
        parent
    } else {
        parent.parent().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::BundleRoot,
            "",
        ))?
    };
    let root = bundle.to_path_buf();
    Ok(JavaBundle {
        library,
        backend,
        root,
    })
}
fn prepare_captured_inputs(c: &Capture, user: &UserConfig) -> Result<PreparedInputs> {
    let scratch = tempfile::Builder::new().prefix("codeana-java-").tempdir()?;
    let captured_bytes = c
        .blobs
        .values()
        .try_fold(0u64, |total, blob| -> Result<u64> {
            total.checked_add(blob.as_ref().metadata()?.len()).context(
                codeana_model::DomainError::new(codeana_model::ErrorCode::TaskScratchOverflow, ""),
            )
        })?;
    let largest_blob = c
        .blobs
        .values()
        .map(|blob| blob.as_ref().metadata().map(|m| m.len()))
        .collect::<std::io::Result<Vec<_>>>()?
        .into_iter()
        .max()
        .unwrap_or(0);
    let mut disk_budget = DiskBudget::new(
        scratch.path().to_path_buf(),
        c.effective
            .config
            .resources
            .index
            .java_scratch_bytes
            .unwrap_or(2 * 1024 * 1024 * 1024),
        captured_bytes,
        largest_blob
            + if user.daemon.java_signature_cache.unwrap_or(true) {
                64 * 1024 * 1024
            } else {
                0
            },
        "JAVA_SCRATCH_BUDGET",
    );
    disk_budget.check()?;
    let input = scratch.path().join("input");
    std::fs::create_dir(&input)?;
    for unit in &c.manifest.files {
        ensure!(
            safe_path(&unit.path),
            codeana_model::DomainError::new(codeana_model::ErrorCode::UnsafeCapturePath, "")
        );
        let bytes = c
            .blobs
            .get(&unit.hash)
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::CaptureBlobMissing,
                "",
            ))?;
        let path = input.join(&unit.path);
        std::fs::create_dir_all(path.parent().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::InputParent,
            "",
        ))?)?;
        disk_budget.include_input(bytes.as_ref().metadata()?.len())?;
        std::fs::copy(bytes.as_ref(), &path)?;
        ensure!(
            hash_file(&path)? == unit.hash,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureBlobHash, "")
        );
    }
    Ok(PreparedInputs {
        scratch,
        disk_budget,
        input,
    })
}
#[derive(Serialize)]
pub(super) struct ToolchainEvidence {
    target_java: u32,
    actual_java_version: String,
    aliases: Vec<String>,
    home: PathBuf,
    sha256: String,
}
pub(super) fn toolchain_evidence(
    user: &UserConfig,
    effective: &codeana_config::Effective,
) -> Result<ToolchainEvidence> {
    let modules = effective
        .config
        .modules
        .iter()
        .filter(|m| m.language == "java")
        .collect::<Vec<_>>();
    let toolchain = prepare_toolchain(user, &modules)?;
    let release = std::fs::read_to_string(toolchain.home.join("release"))?;
    let actual = release
        .lines()
        .find_map(|line| line.strip_prefix("JAVA_VERSION="))
        .unwrap_or("")
        .trim_matches('"')
        .to_owned();
    Ok(ToolchainEvidence {
        target_java: toolchain.release,
        actual_java_version: actual,
        aliases: toolchain.aliases,
        home: toolchain.home,
        sha256: toolchain.digest,
    })
}
fn jdk_major(release: &str) -> Option<u32> {
    let version = release
        .lines()
        .find_map(|line| line.strip_prefix("JAVA_VERSION=\""))?;
    let version = version.strip_prefix("1.").unwrap_or(version);
    version
        .split(|c: char| !c.is_ascii_digit())
        .next()?
        .parse()
        .ok()
}
fn prepare_toolchain(
    user: &UserConfig,
    modules: &[&codeana_config::Module],
) -> Result<JavaToolchain> {
    let release = modules
        .first()
        .and_then(|m| m.analysis.source_version.as_deref())
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|v| matches!(v, 8 | 17))
        .context(DomainError::new(
            ErrorCode::SourceVersionUnsupported,
            "explicit Java 8 or 17 required",
        ))?;
    let mut homes = Vec::new();
    let mut aliases = std::collections::BTreeSet::new();
    for module in modules {
        ensure!(
            module.analysis.source_version.as_deref() == Some(release.to_string().as_str()),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::SourceVersionUnsupported,
                "all Java modules must have the same explicit target version (8 or 17)".to_string()
            )
        );
        let alias = module.analysis.toolchain.as_deref().unwrap_or("auto");
        let mut candidates = Vec::new();
        let mut observed = Vec::new();
        for (id, toolchain) in user
            .toolchains
            .iter()
            .filter(|(id, t)| t.language == "java" && (alias == "auto" || alias == id.as_str()))
        {
            let description = std::fs::read_to_string(Path::new(&toolchain.home).join("release"));
            if let Ok(text) = &description
                && jdk_major(text) == Some(release)
            {
                candidates.push((id, toolchain));
            }
            if observed.len() < 8 {
                let version = description
                    .as_ref()
                    .ok()
                    .and_then(|text| {
                        text.lines()
                            .find_map(|line| line.strip_prefix("JAVA_VERSION="))
                    })
                    .unwrap_or("unavailable")
                    .chars()
                    .take(64)
                    .collect::<String>();
                observed.push(format!(
                    "{id}: home={}, actual={version}",
                    toolchain.home.chars().take(256).collect::<String>()
                ));
            }
        }
        ensure!(
            candidates.len() == 1,
            DomainError::new(
                ErrorCode::TargetJdkRequired,
                format!(
                    "target=Java {release}, alias={alias}: expected one registered SDKMAN JDK; matching={}; observed=[{}]",
                    candidates.len(),
                    observed.join("; ")
                )
            )
        );
        aliases.insert(candidates[0].0.clone());
        homes.push(PathBuf::from(&candidates[0].1.home).canonicalize()?);
    }
    let home = homes
        .first()
        .context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::JavaModuleRequired,
            "",
        ))?
        .clone();
    ensure!(
        homes.iter().all(|p| p == &home),
        codeana_model::DomainError::new(codeana_model::ErrorCode::MixedToolchainUnsupported, "")
    );
    for name in if release == 8 {
        vec!["bin/java", "bin/javac", "lib/tools.jar", "jre/lib/rt.jar"]
    } else {
        vec![
            "bin/java",
            "bin/javac",
            "lib/modules",
            "lib/ct.sym",
            "jmods/jdk.compiler.jmod",
        ]
    } {
        ensure!(
            home.join(name).is_file(),
            DomainError::new(
                ErrorCode::TargetJdkRequired,
                format!("Java {release}: missing {}", home.join(name).display())
            )
        );
        if name.starts_with("bin/") {
            use std::os::unix::fs::PermissionsExt;
            ensure!(
                std::fs::metadata(home.join(name))?.permissions().mode() & 0o111 != 0,
                DomainError::new(
                    ErrorCode::TargetJdkRequired,
                    format!("non-executable Java {release} tool: {name}")
                )
            );
        }
    }
    let mut toolchain = Vec::new();
    // Hash the actual SDK execution/compiler/platform inputs, preserving relative identities.
    for entry in walkdir::WalkDir::new(&home).follow_links(true) {
        let entry = entry?;
        if entry.path().is_file() {
            let rel = entry.path().strip_prefix(&home)?;
            if rel.starts_with("bin")
                || rel.starts_with("lib")
                || rel.starts_with("jre")
                || rel.starts_with("jmods")
                || rel == Path::new("release")
            {
                toolchain.push((entry.path().to_path_buf(), hash_file(entry.path())?));
            }
        }
    }
    toolchain.sort();
    let toolchain_digest = digest(
        &toolchain
            .iter()
            .map(|(p, h)| (p.strip_prefix(&home).unwrap(), h))
            .collect::<Vec<_>>(),
    );
    let host_identity = if std::env::var("CODEANA_JAVA_BACKEND").as_deref() == Ok("jvm") {
        let (identity, inputs) = host_toolchain_inputs(&codeana_contracts::java::jvm_host_home()?)?;
        toolchain.extend(inputs);
        Some(identity)
    } else {
        None
    };
    let release_text = std::fs::read_to_string(home.join("release"))?;
    let actual_version = release_text
        .lines()
        .find_map(|line| line.strip_prefix("JAVA_VERSION="))
        .context("JAVA_VERSION_REQUIRED")?
        .trim_matches('"')
        .to_owned();
    Ok(JavaToolchain {
        release,
        actual_version,
        aliases: aliases.into_iter().collect(),
        home,
        inputs: toolchain,
        digest: toolchain_digest,
        host_identity,
    })
}
fn prerequisite_modules(c: &Capture) -> std::collections::BTreeSet<String> {
    let mut prerequisites = std::collections::BTreeSet::new();
    if let Some(inputs) = &c.effective.java_inputs {
        let mut pending = Vec::new();
        for module in inputs.modules.iter().filter(|m| m.selected) {
            let xml_enabled = c.effective.config.modules.iter().any(|configured| {
                configured.id == module.id
                    && ((configured.extensions.spring.enabled.unwrap_or(false)
                        && configured.extensions.spring.xml.unwrap_or(false))
                        || configured.extensions.sofa.enabled.unwrap_or(false))
            });
            let worlds = context_worlds(&c.manifest, &module.id, xml_enabled);
            for scope in ["MAIN", "TEST"] {
                if worlds.contains_key(scope) {
                    pending.extend(
                        module
                            .dependencies
                            .get(scope)
                            .into_iter()
                            .flatten()
                            .cloned(),
                    );
                }
            }
        }
        while let Some(id) = pending.pop() {
            if !prerequisites.insert(id.clone()) {
                continue;
            }
            if let Some(module) = inputs
                .modules
                .iter()
                .find(|m| m.id == id && (m.resolved_classpath || !inputs.resolved_classpath))
            {
                pending.extend(
                    module
                        .dependencies
                        .get("MAIN")
                        .into_iter()
                        .flatten()
                        .cloned(),
                );
            }
        }
    }
    prerequisites
}
fn prepare_contexts(
    c: &Capture,
    input: &Path,
    modules: Vec<&codeana_config::Module>,
) -> Result<Vec<w::JavaContext>> {
    let prerequisites = prerequisite_modules(c);
    let mut contexts = Vec::new();
    for module in modules {
        let selected = !c.effective.java_inputs.as_ref().is_some_and(|inputs| {
            inputs
                .modules
                .iter()
                .any(|m| m.id == module.id && !m.selected)
        });
        // A captured MAIN prerequisite may supply module-local Java evidence only
        // when the receipt provides its classpath or a validated source-only topology.
        let upstream = !selected
            && prerequisites.contains(&module.id)
            && c.effective.java_inputs.as_ref().is_some_and(|inputs| {
                inputs.modules.iter().any(|m| {
                    m.id == module.id && (m.resolved_classpath || !inputs.resolved_classpath)
                })
            });
        if !selected && !upstream {
            continue;
        }
        let xml_enabled = !upstream
            && ((module.extensions.spring.enabled.unwrap_or(false)
                && module.extensions.spring.xml.unwrap_or(false))
                || module.extensions.sofa.enabled.unwrap_or(false));
        let worlds = context_worlds(&c.manifest, &module.id, xml_enabled);
        for (scope, world) in worlds {
            let prerequisite = upstream
                || (c
                    .manifest
                    .files
                    .iter()
                    .any(|s| s.language == "java" && s.world == world)
                    && c.manifest
                        .files
                        .iter()
                        .filter(|s| s.language == "java" && s.world == world)
                        .all(|s| s.auxiliary));
            if prerequisite && scope != "MAIN" {
                continue;
            }
            ensure!(
                matches!(scope.as_str(), "MAIN" | "TEST"),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::GeneratedScopeRequiresMetadata,
                    ""
                )
            );
            let mut dependencies = std::collections::BTreeSet::new();
            dependencies.insert(module.id.clone());
            if let Some(metadata) = &c.effective.java_inputs {
                let m = metadata
                    .modules
                    .iter()
                    .find(|m| m.id == module.id)
                    .context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaMetadataModule,
                        "",
                    ))?;
                // Schema 4 carries Maven's complete mediated scope inputs, not direct edges.
                // Recursing through another module would reintroduce optional/excluded artifacts.
                dependencies.extend(m.dependencies.get(&scope).into_iter().flatten().cloned());
            }
            let mut input_worlds: std::collections::BTreeSet<_> = dependencies
                .iter()
                .map(|id| key("world", &[id, "MAIN"]))
                .collect();
            if scope == "TEST" {
                input_worlds.insert(world.clone());
            }
            let units: Vec<_> = c
                .manifest
                .files
                .iter()
                .filter(|s| s.language == "java" && input_worlds.contains(&s.world))
                .collect();
            let sources = units
                .iter()
                .map(|s| {
                    Ok(w::JavaSource {
                        unit: Some(map(*s)?),
                        input: input.join(&s.path).to_string_lossy().into_owned(),
                        publish: s.world == world && !s.auxiliary,
                    })
                })
                .collect::<Result<Vec<_>>>()?;
            let resources = c
                .manifest
                .files
                .iter()
                .filter(|s| {
                    if s.scope == "JAVA_CONFIG" {
                        return module.lombok.mode.as_deref() == Some("model")
                            && (s.path.starts_with(".code-ana/lombok-ancestors/")
                                || Path::new(&s.path).parent().is_some_and(|directory| {
                                    units.iter().any(|source| {
                                        Path::new(&source.path).starts_with(directory)
                                    })
                                }));
                    }
                    !prerequisite
                        && s.language.is_empty()
                        && (s.module == module.id || (s.module.is_empty() && module.root == "."))
                        && (s.scope == scope || s.scope == "RESOURCE")
                })
                .map(|s| {
                    Ok(w::JavaSource {
                        unit: Some(map(s)?),
                        input: input.join(&s.path).to_string_lossy().into_owned(),
                        publish: true,
                    })
                })
                .collect::<Result<Vec<_>>>()?;
            let mut classpath = Vec::new();
            let mut classpath_identity = Vec::new();
            if module.analysis.classpath.as_deref() != Some("none")
                && let Some(metadata) = &c.effective.java_inputs
            {
                let m = metadata
                    .modules
                    .iter()
                    .find(|m| m.id == module.id)
                    .context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaMetadataModule,
                        "",
                    ))?;
                let cp = m.classpath.get(&scope).or_else(|| m.classpath.get("MAIN"));
                for a in cp.into_iter().flatten() {
                    let rel = format!(".code-ana/inputs/{}.jar", a.sha256);
                    ensure!(
                        c.manifest
                            .files
                            .iter()
                            .any(|u| u.path == rel && u.hash == a.sha256),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::CapturedClasspathRequired,
                            ""
                        )
                    );
                    classpath.push(input.join(rel).to_string_lossy().into_owned());
                    classpath_identity.push(a.sha256.clone());
                }
            }
            let resource_identity = resources.iter().map(|r| &r.unit).collect::<Vec<_>>();
            contexts.push(w::JavaContext {
                id: format!("{}@{}", module.id, scope),
                module: module.id.clone(),
                source_roots: module
                    .source_roots
                    .iter()
                    .filter(|r| r.scope == scope)
                    .map(|r| {
                        Path::new(&module.root)
                            .join(&r.path)
                            .to_string_lossy()
                            .into_owned()
                    })
                    .collect(),
                world,
                scope,
                digest: digest(&(
                    &units,
                    c.effective
                        .java_inputs
                        .as_ref()
                        .map(|inputs| inputs.dependency_mode),
                    classpath_identity,
                    resource_identity,
                    &module.lombok,
                    &module.extensions,
                    &module.external,
                    &module.source_roots,
                )),
                sources,
                classpath,
                resources,
                lombok: module.lombok.mode.clone().unwrap_or_else(|| "off".into()),
                lombok_strict: module.lombok.strict.unwrap_or(false),
                external_policy_configured: module.external.exclude_patterns.is_some(),
                external_exclude_patterns: module
                    .external
                    .exclude_patterns
                    .clone()
                    .unwrap_or_default(),
                prerequisite,
                dependency_modules: dependencies
                    .iter()
                    .filter(|id| *id != &module.id)
                    .cloned()
                    .collect(),
                spring: !prerequisite && module.extensions.spring.enabled.unwrap_or(false),
                spring_xml: !prerequisite && module.extensions.spring.xml.unwrap_or(false),
                sofa: !prerequisite && module.extensions.sofa.enabled.unwrap_or(false),
                model_decode_bytes: c
                    .effective
                    .config
                    .resources
                    .index
                    .java_model_decode_bytes
                    .unwrap_or(512 * 1024 * 1024),
                model_archive_bytes: c
                    .effective
                    .config
                    .resources
                    .index
                    .java_model_archive_bytes
                    .unwrap_or(64 * 1024 * 1024),
                model_record_limit: c
                    .effective
                    .config
                    .resources
                    .index
                    .java_model_record_limit
                    .unwrap_or(100000),
                ..Default::default()
            });
        }
    }
    Ok(contexts)
}
fn prepare_config(
    c: &Capture,
    user: &UserConfig,
    user_home: &Path,
    bundle: &JavaBundle,
    scratch: &tempfile::TempDir,
    toolchain: &JavaToolchain,
    contexts: Vec<w::JavaContext>,
) -> Result<w::JavaConfig> {
    let home = &toolchain.home;
    let toolchain_digest = toolchain.digest.clone();
    let host_identity = &toolchain.host_identity;
    let library = &bundle.library;
    let bundle = &bundle.root;
    let bridge_name = if toolchain.release == 8 {
        "compiler-bridge.jar"
    } else {
        "compiler-bridge17.jar"
    };
    let mut config = w::JavaConfig {
        target_java: toolchain.release,
        actual_java_version: toolchain.actual_version.clone(),
        toolchain_aliases: toolchain.aliases.clone(),
        java_home: home.to_string_lossy().into_owned(),
        toolchain_digest,
        bridge: bundle.join(bridge_name).to_string_lossy().into_owned(),
        scratch: scratch.path().to_string_lossy().into_owned(),
        semantic_identity: digest(&(
            &c.effective.java_inputs,
            &c.effective.config.resources.index,
            hash(std::fs::read(library)?),
            hash(std::fs::read(bundle.join(bridge_name))?),
            &host_identity,
            toolchain.release,
            home,
            &toolchain.digest,
        )),
        parallelism: compiler_parallelism(
            c.effective
                .config
                .resources
                .index
                .worker_memory_bytes
                .unwrap_or(
                    user.limits
                        .max_worker_memory_bytes
                        .unwrap_or(256 * 1024 * 1024),
                ),
            c.effective
                .config
                .resources
                .index
                .java_compiler_heap_bytes
                .unwrap_or(128 * 1024 * 1024),
            c.effective.config.resources.index.java_parallelism,
            c.effective
                .config
                .resources
                .index
                .java_native_heap_bytes
                .unwrap_or(256 * 1024 * 1024),
        )?,
        cache_root: user_home
            .join("cache/java-signatures")
            .to_string_lossy()
            .into_owned(),
        signature_cache: user.daemon.java_signature_cache.unwrap_or(true),
        result_bytes: c
            .effective
            .config
            .resources
            .index
            .java_result_bytes
            .unwrap_or(MAX_RESULT as u64),
        fact_limit: c
            .effective
            .config
            .resources
            .index
            .java_fact_limit
            .unwrap_or(MAX_FACTS as u32),
        native_heap_bytes: c
            .effective
            .config
            .resources
            .index
            .java_native_heap_bytes
            .unwrap_or(256 * 1024 * 1024),
        compiler_timeout_seconds: c
            .effective
            .config
            .resources
            .index
            .java_compiler_timeout_seconds
            .unwrap_or(120),
        compiler_heap_bytes: c
            .effective
            .config
            .resources
            .index
            .java_compiler_heap_bytes
            .unwrap_or(128 * 1024 * 1024),
        dependency_mode: c.effective.java_inputs.as_ref().map_or(
            w::JavaDependencyMode::Resolved,
            |inputs| match inputs.dependency_mode {
                codeana_config::java_inputs::DependencyMode::Resolved => {
                    w::JavaDependencyMode::Resolved
                }
                codeana_config::java_inputs::DependencyMode::SourceOnlyRequested => {
                    w::JavaDependencyMode::SourceOnlyRequested
                }
                codeana_config::java_inputs::DependencyMode::SourceOnlyFallback => {
                    w::JavaDependencyMode::SourceOnlyFallback
                }
            },
        ) as i32,
        dependency_failure: c
            .effective
            .java_inputs
            .as_ref()
            .and_then(|inputs| inputs.dependency_failure.as_ref())
            .map(|f| w::MavenDependencyFailure {
                module: f.module.clone(),
                pom: f.pom.clone(),
                coordinate: f.coordinate.clone(),
                reason: f.reason.clone(),
                round: f.round,
            }),
        contexts,
        ..Default::default()
    };
    codeana_contracts::java::compact(&mut config)?;
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn xml_only_main_and_test_worlds_require_enabled_extensions_and_exact_module() {
        let unit = |path: &str, module: &str, scope: &str, language: &str| SourceUnit {
            path: path.into(),
            hash: "frozen".into(),
            size: 1,
            module: module.into(),
            world: key("world", &[module, scope]),
            scope: scope.into(),
            language: language.into(),
            auxiliary: false,
        };
        let manifest = Manifest {
            files: vec![
                unit("app/resources/beans.xml", "app", "MAIN", ""),
                unit("app/test-resources/beans.xml", "app", "TEST", ""),
                unit("app/resources/readme.txt", "app", "MAIN", ""),
                unit("other/resources/beans.xml", "other", "MAIN", ""),
            ],
        };
        assert!(context_worlds(&manifest, "app", false).is_empty());
        assert_eq!(
            context_worlds(&manifest, "app", true),
            BTreeMap::from([
                ("MAIN".into(), key("world", &["app", "MAIN"])),
                ("TEST".into(), key("world", &["app", "TEST"])),
            ])
        );
        let mut source_only = manifest.clone();
        source_only
            .files
            .push(unit("app/src/Api.java", "app", "MAIN", "java"));
        assert_eq!(context_worlds(&source_only, "app", false).len(), 1);
        source_only
            .files
            .retain(|source| !source.path.ends_with(".xml") && source.language != "java");
        assert!(context_worlds(&source_only, "app", true).is_empty());
    }

    #[test]
    fn java17_selection_and_platform_bytes_are_frozen_without_environment_fallback() {
        use std::os::unix::fs::PermissionsExt;
        let directory = tempfile::tempdir().unwrap();
        let home = directory.path();
        for file in [
            "bin/java",
            "bin/javac",
            "lib/modules",
            "lib/ct.sym",
            "jmods/jdk.compiler.jmod",
            "jmods/java.base.jmod",
        ] {
            let path = home.join(file);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, file).unwrap();
            if file.starts_with("bin/") {
                std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
            }
        }
        std::fs::write(home.join("release"), "JAVA_VERSION=\"17.0.17\"\n").unwrap();
        let mut user = UserConfig::default();
        user.toolchains.insert(
            "java17".into(),
            codeana_config::Toolchain {
                language: "java".into(),
                home: home.to_string_lossy().into_owned(),
            },
        );
        let mut module = codeana_config::Module {
            language: "java".into(),
            ..Default::default()
        };
        module.analysis.source_version = Some("17".into());
        module.analysis.toolchain = Some("java17".into());
        let selected = prepare_toolchain(&user, &[&module]).unwrap();
        assert_eq!(selected.release, 17);
        assert_eq!(selected.actual_version, "17.0.17");
        assert_eq!(selected.aliases, ["java17"]);
        std::fs::write(home.join("jmods/java.base.jmod"), "different platform").unwrap();
        assert_ne!(
            selected.digest,
            prepare_toolchain(&user, &[&module]).unwrap().digest
        );
        module.analysis.source_version = Some("8".into());
        let mismatch = prepare_toolchain(&user, &[&module])
            .err()
            .unwrap()
            .to_string();
        assert!(
            mismatch.contains("target=Java 8")
                && mismatch.contains("17.0.17")
                && mismatch.contains("java17")
        );
        module.analysis.source_version = Some("17".into());
        module.analysis.toolchain = Some("auto".into());
        user.toolchains
            .insert("another".into(), user.toolchains["java17"].clone());
        assert!(prepare_toolchain(&user, &[&module]).is_err());
        module.analysis.toolchain = Some("java17".into());
        std::fs::remove_file(home.join("lib/ct.sym")).unwrap();
        assert!(prepare_toolchain(&user, &[&module]).is_err());
    }
    #[test]
    fn host_toolchain_identity_follows_bytes_and_survives_relocation() {
        let first = tempfile::tempdir().unwrap();
        let relocated = tempfile::tempdir().unwrap();
        for root in [first.path(), relocated.path()] {
            std::fs::create_dir_all(root.join("bin")).unwrap();
            std::fs::create_dir_all(root.join("lib/server")).unwrap();
            std::fs::write(root.join("release"), "JAVA_VERSION=\"25\"").unwrap();
            std::fs::write(root.join("bin/java"), "launcher").unwrap();
            std::fs::write(root.join("lib/modules"), "modules").unwrap();
            std::fs::write(root.join("lib/server/libjvm.dylib"), "vm").unwrap();
        }
        let (original, files) = host_toolchain_inputs(first.path()).unwrap();
        assert_eq!(files.len(), 4);
        assert_eq!(original, host_toolchain_inputs(relocated.path()).unwrap().0);
        for name in [
            "release",
            "bin/java",
            "lib/modules",
            "lib/server/libjvm.dylib",
        ] {
            let path = relocated.path().join(name);
            let bytes = std::fs::read(&path).unwrap();
            std::fs::write(&path, b"changed").unwrap();
            assert_ne!(
                original,
                host_toolchain_inputs(relocated.path()).unwrap().0,
                "{name}"
            );
            std::fs::write(path, bytes).unwrap();
        }
    }

    #[test]
    fn explicit_pool_size_must_fit_worker_process_tree_budget() {
        let mib = 1024 * 1024;
        assert_eq!(
            compiler_parallelism(256 * mib, 128 * mib, None, 256 * mib).unwrap(),
            1
        );
        assert_eq!(
            compiler_parallelism(1024 * mib, 128 * mib, None, 256 * mib).unwrap(),
            2
        );
        assert_eq!(
            compiler_parallelism(2048 * mib, 128 * mib, Some(8), 256 * mib).unwrap(),
            8
        );
        assert!(compiler_parallelism(1024 * mib, 128 * mib, Some(8), 256 * mib).is_err());
        assert!(compiler_parallelism(2048 * mib, 512 * mib, Some(8), 256 * mib).is_err());
        assert!(compiler_parallelism(2048 * mib, 128 * mib, Some(9), 256 * mib).is_err());
        assert!(compiler_parallelism(1024 * mib, 512 * mib, Some(1), 512 * mib).is_err());
        assert_eq!(
            compiler_parallelism(1536 * mib, 512 * mib, Some(1), 512 * mib).unwrap(),
            1
        );
    }
}
