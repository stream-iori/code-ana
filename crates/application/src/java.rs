//! Prepare a Java task from the captured bytes and the daemon's frozen user generation.
use anyhow::{Context, Result, ensure};
use codeana_config::{Capture, UserConfig};
use codeana_contracts::{map, worker as w};
use codeana_model::*;
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    ffi::OsString,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::SystemTime,
};

pub struct PreparedJava {
    pub library: PathBuf,
    pub backend: &'static str,
    pub config: w::JavaConfig,
    pub disk_budget: DiskBudget,
    _scratch: tempfile::TempDir,
    toolchain: Vec<(PathBuf, String)>,
}
/// Task scratch excludes published revisions and shared caches; reserves cache-write and blob-copy transients.
#[derive(Clone, Debug)]
pub struct DiskBudget {
    pub root: PathBuf,
    pub max_bytes: u64,
    captured_bytes: u64,
    input_bytes: u64,
    reserve_bytes: u64,
    directory_entries: Arc<Mutex<DirectoryEntries>>,
}
// Cache names only: every budget check still stats every live entry. Appending to a
// file does not change its parent's mtime, so caching byte totals would be unsound.
#[derive(Debug, PartialEq, Eq)]
struct DirectoryStamp {
    modified: SystemTime,
    #[cfg(unix)]
    identity: (u64, u64, i64, i64),
}
impl DirectoryStamp {
    fn of(metadata: &std::fs::Metadata) -> Option<Self> {
        #[cfg(unix)]
        use std::os::unix::fs::MetadataExt;
        Some(Self {
            modified: metadata.modified().ok()?,
            #[cfg(unix)]
            identity: (
                metadata.dev(),
                metadata.ino(),
                metadata.ctime(),
                metadata.ctime_nsec(),
            ),
        })
    }
}
#[derive(Debug)]
struct DirectoryListing {
    stamp: DirectoryStamp,
    names: Arc<Vec<OsString>>,
    bytes: usize,
}
#[derive(Debug, Default)]
struct DirectoryEntries {
    listings: HashMap<PathBuf, DirectoryListing>,
    names: usize,
    bytes: usize,
    #[cfg(test)]
    reads: usize,
}
impl DirectoryEntries {
    fn remove(&mut self, path: &Path) {
        if let Some(old) = self.listings.remove(path) {
            self.names -= old.names.len();
            self.bytes -= old.bytes;
        }
    }
    fn names(&mut self, path: &Path, metadata: &std::fs::Metadata) -> Result<Arc<Vec<OsString>>> {
        let stamp = DirectoryStamp::of(metadata);
        if let Some(current) = &stamp
            && let Some(cached) = self.listings.get(path)
            && &cached.stamp == current
        {
            return Ok(cached.names.clone());
        }
        self.remove(path);
        #[cfg(test)]
        {
            self.reads += 1;
        }
        let directory = match std::fs::read_dir(path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Arc::new(vec![]));
            }
            Err(error) => return Err(error.into()),
        };
        let mut names = vec![];
        for entry in directory {
            match entry {
                Ok(entry) => names.push(entry.file_name()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        }
        let bytes = path.as_os_str().len() + names.iter().map(|name| name.len()).sum::<usize>();
        let names = Arc::new(names);
        if let Some(stamp) = stamp
            && self.listings.len() < 4096
            && self.names + names.len() <= 65536
            && self.bytes + bytes <= 16 * 1024 * 1024
        {
            self.names += names.len();
            self.bytes += bytes;
            self.listings.insert(
                path.to_path_buf(),
                DirectoryListing {
                    stamp,
                    names: names.clone(),
                    bytes,
                },
            );
        }
        Ok(names)
    }
}
impl DiskBudget {
    fn directory_bytes(&self, root: &Path, skip: Option<&Path>) -> Result<u64> {
        let mut total = 0u64;
        let mut pending = vec![root.to_path_buf()];
        let mut visited = HashSet::new();
        let mut entries = self.directory_entries.lock().unwrap();
        while let Some(path) = pending.pop() {
            if skip == Some(path.as_path()) {
                continue;
            }
            let metadata = match std::fs::symlink_metadata(&path) {
                Ok(value) => value,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    entries.remove(&path);
                    continue;
                }
                Err(error) => return Err(error.into()),
            };
            if metadata.file_type().is_symlink() {
                anyhow::bail!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::TaskScratchSymlink,
                    ""
                ));
            }
            if metadata.is_file() {
                entries.remove(&path);
                total =
                    total
                        .checked_add(metadata.len())
                        .context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::TaskScratchOverflow,
                            "",
                        ))?;
            } else if metadata.is_dir() {
                visited.insert(path.clone());
                let names = entries.names(&path, &metadata)?;
                pending.extend(names.iter().map(|name| path.join(name)));
            }
        }
        let stale: Vec<_> = entries
            .listings
            .keys()
            .filter(|path| path.starts_with(root) && !visited.contains(*path))
            .cloned()
            .collect();
        for path in stale {
            entries.remove(&path);
        }
        Ok(total)
    }
    pub fn check(&self) -> Result<()> {
        self.check_candidate(None)
    }
    pub fn check_candidate(&self, candidate: Option<&Path>) -> Result<()> {
        let input = self.root.join("input");
        let dynamic = self.directory_bytes(&self.root, Some(&input))?;
        let staged = candidate
            .map(|path| self.directory_bytes(path, None))
            .transpose()?
            .unwrap_or(0);
        let total = self
            .captured_bytes
            .checked_add(self.input_bytes)
            .and_then(|n| n.checked_add(self.reserve_bytes))
            .and_then(|n| n.checked_add(dynamic))
            .and_then(|n| n.checked_add(staged))
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::TaskScratchOverflow,
                "",
            ))?;
        ensure!(
            total <= self.max_bytes,
            "JAVA_SCRATCH_BUDGET bytes={total} limit={}",
            self.max_bytes
        );
        Ok(())
    }
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
                .join("../../lang-java-adapotr/target/jvm/runner.jar");
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
            .join("../../lang-java-adapotr/target/native")
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

pub fn prepare(c: &Capture, user: &UserConfig, user_home: &Path) -> Result<PreparedJava> {
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
    let mut disk_budget = DiskBudget {
        root: scratch.path().to_path_buf(),
        max_bytes: c
            .effective
            .config
            .resources
            .index
            .java_scratch_bytes
            .unwrap_or(2 * 1024 * 1024 * 1024),
        captured_bytes,
        directory_entries: Arc::default(),
        input_bytes: 0,
        reserve_bytes: largest_blob
            + if user.daemon.java_signature_cache.unwrap_or(true) {
                64 * 1024 * 1024
            } else {
                0
            },
    };
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
        disk_budget.input_bytes = disk_budget
            .input_bytes
            .checked_add(bytes.as_ref().metadata()?.len())
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::TaskScratchOverflow,
                "",
            ))?;
        disk_budget.check()?;
        std::fs::copy(bytes.as_ref(), &path)?;
        ensure!(
            hash_file(&path)? == unit.hash,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureBlobHash, "")
        );
    }
    let modules: Vec<_> = c
        .effective
        .config
        .modules
        .iter()
        .filter(|m| m.language == "java")
        .collect();
    let mut homes = Vec::new();
    for module in &modules {
        ensure!(
            module.analysis.source_version.as_deref() == Some("8"),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::SourceVersionUnsupported,
                "Java 8 must be explicit".to_string()
            )
        );
        let alias = module.analysis.toolchain.as_deref().unwrap_or("auto");
        let candidates: Vec<_> = user
            .toolchains
            .iter()
            .filter(|(id, t)| t.language == "java" && (alias == "auto" || alias == id.as_str()))
            .filter(|(_, t)| {
                std::fs::read_to_string(Path::new(&t.home).join("release"))
                    .is_ok_and(|s| s.lines().any(|l| l.starts_with("JAVA_VERSION=\"1.8.")))
            })
            .collect();
        ensure!(
            candidates.len() == 1,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::TargetJdkRequired,
                "register one Java 8 SDKMAN home or select an explicit alias".to_string()
            )
        );
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
    ensure!(
        home.join("lib/tools.jar").is_file(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::Jdk8CompilerRequired, "")
    );
    let mut toolchain = Vec::new();
    // Hash the actual SDK execution/compiler/platform inputs, preserving relative identities.
    for entry in walkdir::WalkDir::new(&home).follow_links(false) {
        let entry = entry?;
        if entry.file_type().is_file() {
            let rel = entry.path().strip_prefix(&home)?;
            if rel.starts_with("bin")
                || rel.starts_with("lib")
                || rel.starts_with("jre")
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
                .find(|m| m.id == id && m.resolved_classpath)
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
    let mut contexts = Vec::new();
    for module in modules {
        let selected = !c.effective.java_inputs.as_ref().is_some_and(|inputs| {
            inputs
                .modules
                .iter()
                .any(|m| m.id == module.id && !m.selected)
        });
        // A captured MAIN prerequisite may supply module-local Java evidence only
        // when the external Maven receipt provides that module's own classpath.
        let upstream = !selected
            && prerequisites.contains(&module.id)
            && c.effective.java_inputs.as_ref().is_some_and(|inputs| {
                inputs
                    .modules
                    .iter()
                    .any(|m| m.id == module.id && m.resolved_classpath)
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
                // Schema 3 carries Maven's complete mediated scope inputs, not direct edges.
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
    let mut config = w::JavaConfig {
        java_home: home.to_string_lossy().into_owned(),
        toolchain_digest,
        bridge: bundle
            .join("compiler-bridge.jar")
            .to_string_lossy()
            .into_owned(),
        scratch: scratch.path().to_string_lossy().into_owned(),
        semantic_identity: digest(&(
            &c.effective.java_inputs,
            &c.effective.config.resources.index,
            hash(std::fs::read(&library)?),
            hash(std::fs::read(bundle.join("compiler-bridge.jar"))?),
            &host_identity,
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
        contexts,
        ..Default::default()
    };
    codeana_contracts::java::compact(&mut config)?;
    Ok(PreparedJava {
        library,
        backend,
        config,
        disk_budget,
        _scratch: scratch,
        toolchain,
    })
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
    fn task_disk_listing_cache_is_bounded_and_fallback_still_counts() {
        let root = tempfile::tempdir().unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 10,
            captured_bytes: 0,
            input_bytes: 0,
            reserve_bytes: 0,
            directory_entries: Arc::default(),
        };
        for index in 0..4112 {
            std::fs::create_dir(root.path().join(format!("dir-{index}"))).unwrap();
        }
        budget.check().unwrap();
        let uncached = {
            let cache = budget.directory_entries.lock().unwrap();
            assert!(
                cache.listings.len() <= 4096
                    && cache.names <= 65536
                    && cache.bytes <= 16 * 1024 * 1024
            );
            (0..4112)
                .map(|index| root.path().join(format!("dir-{index}")))
                .find(|path| !cache.listings.contains_key(path))
                .unwrap()
        };
        let file = uncached.join("growth");
        std::fs::File::create(&file).unwrap().set_len(11).unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_file(file).unwrap();
        budget.check().unwrap();
    }

    #[test]
    fn task_disk_listing_cache_keeps_live_sizes_and_nested_changes() {
        let root = tempfile::tempdir().unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 10,
            captured_bytes: 0,
            input_bytes: 0,
            reserve_bytes: 0,
            directory_entries: Arc::default(),
        };
        let nested = root.path().join("nested");
        std::fs::create_dir(&nested).unwrap();
        let file = nested.join("data");
        std::fs::File::create(&file).unwrap().set_len(5).unwrap();
        budget.check().unwrap();
        let reads = budget.directory_entries.lock().unwrap().reads;
        for _ in 0..20 {
            budget.clone().check().unwrap();
        }
        assert_eq!(budget.directory_entries.lock().unwrap().reads, reads);
        // Appending an existing file leaves the directory listing unchanged.
        std::fs::OpenOptions::new()
            .write(true)
            .open(&file)
            .unwrap()
            .set_len(11)
            .unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        assert_eq!(budget.directory_entries.lock().unwrap().reads, reads);
        std::fs::OpenOptions::new()
            .write(true)
            .open(&file)
            .unwrap()
            .set_len(5)
            .unwrap();
        let deeper = nested.join("deeper");
        std::fs::create_dir(&deeper).unwrap();
        std::fs::File::create(deeper.join("more"))
            .unwrap()
            .set_len(6)
            .unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_dir_all(&deeper).unwrap();
        budget.check().unwrap();
        assert!(
            !budget
                .directory_entries
                .lock()
                .unwrap()
                .listings
                .contains_key(&deeper)
        );
        std::fs::remove_file(&file).unwrap();
        std::os::unix::fs::symlink(root.path(), &file).unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("TASK_SCRATCH_SYMLINK")
        );
    }

    #[test]
    fn task_disk_listing_cache_accounts_candidate_growth_with_shared_clones() {
        let root = tempfile::tempdir().unwrap();
        let candidate = tempfile::tempdir().unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 15,
            captured_bytes: 2,
            input_bytes: 0,
            reserve_bytes: 3,
            directory_entries: Arc::default(),
        };
        std::fs::File::create(root.path().join("result"))
            .unwrap()
            .set_len(5)
            .unwrap();
        let database = candidate.path().join("facts.db");
        std::fs::File::create(&database)
            .unwrap()
            .set_len(5)
            .unwrap();
        budget.check_candidate(Some(candidate.path())).unwrap();
        let before = budget.directory_entries.lock().unwrap().reads;
        budget
            .clone()
            .check_candidate(Some(candidate.path()))
            .unwrap();
        assert_eq!(budget.directory_entries.lock().unwrap().reads, before);
        std::fs::OpenOptions::new()
            .write(true)
            .open(&database)
            .unwrap()
            .set_len(6)
            .unwrap();
        assert!(
            budget
                .check_candidate(Some(candidate.path()))
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_file(&database).unwrap();
        budget.check_candidate(Some(candidate.path())).unwrap();
        assert_eq!(budget.directory_entries.lock().unwrap().names, 1);
    }

    #[test]
    fn task_disk_budget_counts_captures_results_and_candidate_without_double_counting_input() {
        let root = tempfile::tempdir().unwrap();
        let candidate = tempfile::tempdir().unwrap();
        std::fs::create_dir(root.path().join("input")).unwrap();
        std::fs::File::create(root.path().join("input/frozen.jar"))
            .unwrap()
            .set_len(4096)
            .unwrap();
        let result = root.path().join("result.bin");
        std::fs::File::create(&result)
            .unwrap()
            .set_len(8192)
            .unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 2048 + 4096 + 8192 + 64,
            captured_bytes: 2048,
            input_bytes: 4096,
            reserve_bytes: 64,
            directory_entries: Arc::default(),
        };
        budget.check().unwrap();
        std::fs::File::create(candidate.path().join("facts.db"))
            .unwrap()
            .set_len(1)
            .unwrap();
        assert!(
            budget
                .check_candidate(Some(candidate.path()))
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_file(result).unwrap();
        budget.check_candidate(Some(candidate.path())).unwrap();
        std::os::unix::fs::symlink(candidate.path(), root.path().join("unowned-link")).unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("TASK_SCRATCH_SYMLINK")
        );
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
