//! Prepare a Java task from the captured bytes and the daemon's frozen user generation.
use anyhow::{Context, Result, ensure};
use codeana_config::{Capture, UserConfig};
use codeana_contracts::{convert, worker as w};
use codeana_model::*;
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};

pub struct PreparedJava {
    pub library: PathBuf,
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
}
impl DiskBudget {
    fn directory_bytes(path: &Path, skip: Option<&Path>) -> Result<u64> {
        let mut total = 0u64;
        for entry in walkdir::WalkDir::new(path)
            .follow_links(false)
            .into_iter()
            .filter_entry(|entry| skip != Some(entry.path()))
        {
            let entry = match entry {
                Ok(value) => value,
                Err(error)
                    if error
                        .io_error()
                        .is_some_and(|e| e.kind() == std::io::ErrorKind::NotFound) =>
                {
                    continue;
                }
                Err(error) => return Err(error.into()),
            };
            if entry.file_type().is_symlink() {
                anyhow::bail!("TASK_SCRATCH_SYMLINK");
            }
            if entry.file_type().is_file() {
                match entry.metadata() {
                    Ok(metadata) => {
                        total = total
                            .checked_add(metadata.len())
                            .context("TASK_SCRATCH_OVERFLOW")?
                    }
                    Err(error)
                        if error
                            .io_error()
                            .is_some_and(|e| e.kind() == std::io::ErrorKind::NotFound) => {}
                    Err(error) => return Err(error.into()),
                }
            }
        }
        Ok(total)
    }
    pub fn check(&self) -> Result<()> {
        self.check_candidate(None)
    }
    pub fn check_candidate(&self, candidate: Option<&Path>) -> Result<()> {
        let input = self.root.join("input");
        let dynamic = Self::directory_bytes(&self.root, Some(&input))?;
        let staged = candidate
            .map(|path| Self::directory_bytes(path, None))
            .transpose()?
            .unwrap_or(0);
        let total = self
            .captured_bytes
            .checked_add(self.input_bytes)
            .and_then(|n| n.checked_add(self.reserve_bytes))
            .and_then(|n| n.checked_add(dynamic))
            .and_then(|n| n.checked_add(staged))
            .context("TASK_SCRATCH_OVERFLOW")?;
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
                "TOOLCHAIN_INPUT_DRIFT: {}",
                path.display()
            );
        }
        Ok(())
    }
}
pub fn installed_library() -> Result<PathBuf> {
    let exe = std::env::current_exe()?;
    let bin = exe.parent().context("BINARY_DIRECTORY")?;
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
            .join("../../lang-adaptor/target/native")
            .join(name);
        if dev.is_file() {
            return Ok(dev.canonicalize()?);
        }
    }
    anyhow::bail!("UNSUPPORTED_ADAPTER: Java native bundle is not installed")
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
        ensure!((1..=8).contains(&count), "JAVA_PARALLELISM_RANGE");
        // Reserve native host/model space and per-JVM non-heap space. RSS monitoring
        // remains authoritative; a reservation is not a capacity guarantee.
        ensure!(
            memory >= host + u64::from(count) * (heap + 64 * mib),
            "JAVA_PARALLELISM_MEMORY_BUDGET"
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
    let bundle = library
        .parent()
        .and_then(Path::parent)
        .context("BUNDLE_ROOT")?;
    let scratch = tempfile::Builder::new().prefix("codeana-java-").tempdir()?;
    let captured_bytes = c
        .blobs
        .values()
        .try_fold(0u64, |total, blob| -> Result<u64> {
            total
                .checked_add(blob.as_ref().metadata()?.len())
                .context("TASK_SCRATCH_OVERFLOW")
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
        ensure!(safe_path(&unit.path), "UNSAFE_CAPTURE_PATH");
        let bytes = c.blobs.get(&unit.hash).context("CAPTURE_BLOB_MISSING")?;
        let path = input.join(&unit.path);
        std::fs::create_dir_all(path.parent().context("INPUT_PARENT")?)?;
        disk_budget.input_bytes = disk_budget
            .input_bytes
            .checked_add(bytes.as_ref().metadata()?.len())
            .context("TASK_SCRATCH_OVERFLOW")?;
        disk_budget.check()?;
        std::fs::copy(bytes.as_ref(), &path)?;
        ensure!(hash_file(&path)? == unit.hash, "CAPTURE_BLOB_HASH");
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
            "SOURCE_VERSION_UNSUPPORTED: Java 8 must be explicit"
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
            "TARGET_JDK_REQUIRED: register one Java 8 SDKMAN home or select an explicit alias"
        );
        homes.push(PathBuf::from(&candidates[0].1.home).canonicalize()?);
    }
    let home = homes.first().context("JAVA_MODULE_REQUIRED")?.clone();
    ensure!(
        homes.iter().all(|p| p == &home),
        "MIXED_TOOLCHAIN_UNSUPPORTED"
    );
    ensure!(
        home.join("lib/tools.jar").is_file(),
        "JDK8_COMPILER_REQUIRED"
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
                "GENERATED_SCOPE_REQUIRES_METADATA"
            );
            let mut dependencies = std::collections::BTreeSet::new();
            dependencies.insert(module.id.clone());
            if let Some(metadata) = &c.effective.java_inputs {
                let m = metadata
                    .modules
                    .iter()
                    .find(|m| m.id == module.id)
                    .context("JAVA_METADATA_MODULE")?;
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
                        unit: Some(convert(*s)?),
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
                        unit: Some(convert(s)?),
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
                    .context("JAVA_METADATA_MODULE")?;
                let cp = m.classpath.get(&scope).or_else(|| m.classpath.get("MAIN"));
                for a in cp.into_iter().flatten() {
                    let rel = format!(".code-ana/inputs/{}.jar", a.sha256);
                    ensure!(
                        c.manifest
                            .files
                            .iter()
                            .any(|u| u.path == rel && u.hash == a.sha256),
                        "CAPTURED_CLASSPATH_REQUIRED"
                    );
                    classpath.push(input.join(rel).to_string_lossy().into_owned());
                    classpath_identity.push(a.sha256.clone());
                }
            }
            let resource_identity = resources.iter().map(|r| &r.unit).collect::<Vec<_>>();
            contexts.push(w::JavaContext {
                id: format!("{}@{}", module.id, scope),
                module: module.id.clone(),
                world,
                scope,
                digest: digest(&(
                    &units,
                    classpath_identity,
                    resource_identity,
                    &module.lombok,
                    &module.extensions,
                )),
                sources,
                classpath,
                resources,
                lombok: module.lombok.mode.clone().unwrap_or_else(|| "off".into()),
                lombok_strict: module.lombok.strict.unwrap_or(false),
                prerequisite,
                spring: !prerequisite && module.extensions.spring.enabled.unwrap_or(false),
                spring_xml: !prerequisite && module.extensions.spring.xml.unwrap_or(false),
                sofa: !prerequisite && module.extensions.sofa.enabled.unwrap_or(false),
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
