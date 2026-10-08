//! Strict project configuration and bounded, immutable source capture.
pub mod java_inputs;
mod lombok;
use anyhow::{Context, Result, bail, ensure};
use codeana_model::*;
use java_inputs::JavaInputs;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
};
macro_rules! schema { ($name:ident {$($field:ident:$ty:ty),* $(,)?}) => { #[derive(Debug,Clone,Default,Serialize,Deserialize)] #[serde(default,deny_unknown_fields)] pub struct $name { $(pub $field:$ty),* } }; }
schema!(ProjectConfig {schema_version:u32,kind:String,project:Project,discovery:Discovery,scan:Scan,build:Build,index:Index,resources:Resources,query:Query,observability:Observability,languages:Languages,modules:Vec<Module>,versions:Versions,search:Search});
schema!(Project { name: String });
schema!(Discovery {
    mode: String,
    build_system: String,
    inputs_manifest: Option<String>
});
schema!(Scan {scopes:Vec<String>,include:Vec<String>,exclude:Vec<String>,text_only:Vec<String>});
schema!(Build {suggestions:Option<bool>});
schema!(Index { mode: String });
schema!(Resources {
    index: IndexResources,
    query: QueryResources
});
schema!(IndexResources {worker_memory_bytes:Option<u64>,java_compiler_heap_bytes:Option<u64>,java_parallelism:Option<u32>,java_model_record_limit:Option<u32>,java_model_archive_bytes:Option<u64>,java_compiler_timeout_seconds:Option<u32>,java_native_heap_bytes:Option<u64>,java_result_bytes:Option<u64>,java_fact_limit:Option<u32>,java_scratch_bytes:Option<u64>});
schema!(QueryResources {diff_spill_bytes:Option<u64>});
schema!(Query {scopes:Vec<String>,max_output_bytes:Option<u64>});
schema!(Observability { mode: String });
schema!(Languages {java:Option<Language>,rust:Option<Language>});
schema!(Language {enabled:Option<bool>,mode:String,discovery:Discovery,analysis:Analysis,lombok:Lombok,extensions:Extensions,external:External});
schema!(Analysis {source_version:Option<String>,toolchain:Option<String>,classpath:Option<String>,vm_classpath:Option<bool>,edition:Option<String>});
schema!(Lombok {mode:Option<String>,strict:Option<bool>});
schema!(Extensions {
    spring: Spring,
    sofa: Sofa
});
schema!(Spring {enabled:Option<bool>,xml:Option<bool>});
schema!(Sofa {enabled:Option<bool>});
schema!(External {exclude_patterns:Vec<String>});
schema!(Module {id:String,language:String,root:String,manifest:Option<String>,source_roots:Vec<SourceRoot>,analysis:Analysis,lombok:Lombok,extensions:Extensions,external:External,scan:Scan,worlds:Vec<World>});
schema!(SourceRoot {
    path: String,
    scope: String,
    label: Option<String>
});
schema!(World {id:String,target:String,default_features:Option<bool>,features:Vec<String>,cfg:Vec<String>});
schema!(Versions {
    capture: CapturePolicy,
    gc: GcPolicy
});
schema!(CapturePolicy {include_ignored:Vec<String>});
schema!(GcPolicy {auto:Option<bool>,keep:Option<u32>,max_bytes:Option<u64>,max_age_days:Option<u32>,include_caches:Option<bool>});
schema!(Search { text: TextSearch });
schema!(TextSearch {backend:String,include:Vec<String>});
schema!(UserConfig {schema_version:u32,kind:String,daemon:DaemonPolicy,limits:Limits,toolchains:BTreeMap<String,Toolchain>});
schema!(DaemonPolicy {idle_timeout_seconds:Option<u64>,java_warm_workers:Option<bool>,java_signature_cache:Option<bool>});
schema!(Limits {max_workers:Option<usize>,max_worker_memory_bytes:Option<u64>,max_total_worker_memory_bytes:Option<u64>});
schema!(Toolchain {
    language: String,
    home: String
});
/// Immutable task-owned capture; clones pin its private spool until all consumers finish.
#[derive(Debug, Clone)]
pub struct CapturedBlob {
    path: PathBuf,
    _spool: std::sync::Arc<tempfile::TempDir>,
}
impl AsRef<Path> for CapturedBlob {
    fn as_ref(&self) -> &Path {
        &self.path
    }
}
impl CapturedBlob {
    pub fn read(&self, limit: usize) -> Result<Vec<u8>> {
        bounded_read(&self.path, limit)
    }
    fn bytes(spool: &std::sync::Arc<tempfile::TempDir>, bytes: &[u8]) -> Result<Self> {
        let path = spool.path().join(hash(bytes));
        if !path.exists() {
            std::fs::write(&path, bytes)?;
        }
        Ok(Self {
            path,
            _spool: spool.clone(),
        })
    }
    fn file(
        spool: &std::sync::Arc<tempfile::TempDir>,
        source: &Path,
        limit: usize,
    ) -> Result<(Self, String, usize)> {
        use std::io::Read;
        ensure!(
            source.metadata()?.len() <= limit as u64,
            "JAVA_ARTIFACT_BYTE_BUDGET"
        );
        let temporary = tempfile::NamedTempFile::new_in(spool.path())?;
        let mut output = temporary.reopen()?;
        let size = std::io::copy(
            &mut std::fs::File::open(source)?.take(limit as u64 + 1),
            &mut output,
        )?;
        ensure!(size <= limit as u64, "JAVA_ARTIFACT_BYTE_BUDGET");
        let digest = hash_file(temporary.path())?;
        let path = spool.path().join(&digest);
        if !path.exists() {
            temporary.persist(&path)?;
        }
        Ok((
            Self {
                path,
                _spool: spool.clone(),
            },
            digest,
            size as usize,
        ))
    }
}
const MAX_CAPTURE_BYTES: usize = 2 * 1024 * 1024 * 1024;
const MAX_ARTIFACT_BYTES: usize = 256 * 1024 * 1024;
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Effective {
    pub config: ProjectConfig,
    pub origins: BTreeMap<String, String>,
    pub config_digest: String,
    pub input_hash: String,
    pub semantic_digest: String,
    pub capture_digest: String,
    #[serde(default)]
    pub java_inputs: Option<JavaInputs>,
}
#[derive(Debug, Clone)]
pub struct Capture {
    pub root: PathBuf,
    pub project: String,
    pub checkout: String,
    pub target: String,
    pub effective: Effective,
    pub manifest: Manifest,
    pub blobs: BTreeMap<String, CapturedBlob>,
}
pub fn load_user(home: &Path) -> Result<UserConfig> {
    let path = home.join("config.toml");
    let mut u = if path.exists() {
        let u: UserConfig =
            toml::from_str(std::str::from_utf8(&bounded_read(&path, 1024 * 1024)?)?)?;
        ensure!(
            u.schema_version == 1 && u.kind == "user",
            "INVALID_USER_CONFIG"
        );
        u
    } else {
        UserConfig {
            schema_version: 1,
            kind: "user".into(),
            ..Default::default()
        }
    };
    let workers = u.limits.max_workers.get_or_insert(2);
    ensure!((1..=8).contains(workers), "INVALID_WORKER_LIMIT");
    for t in u.toolchains.values() {
        ensure!(
            Path::new(&t.home).is_absolute(),
            "TOOLCHAIN_HOME_MUST_BE_ABSOLUTE"
        );
    }
    Ok(u)
}
fn glob(patterns: &[String]) -> Result<globset::GlobSet> {
    let mut b = globset::GlobSetBuilder::new();
    for p in patterns {
        b.add(globset::Glob::new(p)?);
    }
    Ok(b.build()?)
}
fn relative(p: &str) -> bool {
    p == "." || safe_path(p)
}
pub fn resolve(root: &Path, request: &Request) -> Result<Effective> {
    resolve_for(root, request, true)
}
pub fn resolve_files(root: &Path, request: &Request) -> Result<Effective> {
    resolve_for(root, request, false)
}
fn resolve_for(root: &Path, request: &Request, language_required: bool) -> Result<Effective> {
    let path = root.join(".code-ana/config.toml");
    ensure!(path.canonicalize()?.starts_with(root), "CONFIG_PATH_ESCAPE");
    let config_bytes = bounded_read(&path, 1024 * 1024)
        .context("PROJECT_CONFIG_REQUIRED: .code-ana/config.toml")?;
    let text = std::str::from_utf8(&config_bytes).context("INVALID_PROJECT_CONFIG_ENCODING")?;
    let mut c: ProjectConfig = toml::from_str(text).context("INVALID_PROJECT_CONFIG")?;
    ensure!(
        c.schema_version == 1 && c.kind == "project",
        "UNSUPPORTED_CONFIG_SCHEMA"
    );
    let mut origins = BTreeMap::new();
    let value: toml::Value = toml::from_str(text)?;
    fn origins_walk(v: &toml::Value, p: String, m: &mut BTreeMap<String, String>) {
        if let Some(t) = v.as_table() {
            for (k, v) in t {
                origins_walk(
                    v,
                    if p.is_empty() {
                        k.clone()
                    } else {
                        format!("{p}.{k}")
                    },
                    m,
                )
            }
        } else {
            m.insert(p, ".code-ana/config.toml".into());
        }
    }
    origins_walk(&value, String::new(), &mut origins);
    if c.discovery.mode.is_empty() {
        c.discovery.mode = "explicit".into();
        origins.insert("discovery.mode".into(), "builtin".into());
    }
    ensure!(
        matches!(c.discovery.mode.as_str(), "explicit" | "auto"),
        "INVALID_DISCOVERY_MODE"
    );
    if c.scan.scopes.is_empty() {
        c.scan.scopes = vec!["MAIN".into()];
    }
    for s in &c.scan.scopes {
        ensure!(
            matches!(s.as_str(), "MAIN" | "TEST" | "GENERATED"),
            "INVALID_SCOPE: {s}"
        );
    }
    if c.scan.include.is_empty() {
        c.scan.include.push("**".into());
    }
    ensure!(
        c.index.mode.is_empty() || matches!(c.index.mode.as_str(), "auto" | "full"),
        "INVALID_INDEX_MODE"
    );
    ensure!(
        c.observability.mode.is_empty()
            || matches!(
                c.observability.mode.as_str(),
                "off" | "basic" | "full" | "detailed"
            ),
        "INVALID_OBSERVABILITY_MODE"
    );
    if let Some(heap) = c.resources.index.java_compiler_heap_bytes {
        ensure!(
            (8 * 1024 * 1024..=512 * 1024 * 1024).contains(&heap) && heap % (1024 * 1024) == 0,
            "INVALID_JAVA_COMPILER_HEAP"
        );
    }
    if let Some(parallelism) = c.resources.index.java_parallelism {
        ensure!((1..=8).contains(&parallelism), "JAVA_PARALLELISM_RANGE");
    }
    if let Some(limit) = c.resources.index.java_model_record_limit {
        ensure!(
            (1..=5000000).contains(&limit),
            "JAVA_MODEL_RECORD_LIMIT_RANGE"
        );
    }
    if let Some(bytes) = c.resources.index.java_model_archive_bytes {
        ensure!(
            (64 * 1024..=1024 * 1024 * 1024).contains(&bytes),
            "JAVA_MODEL_ARCHIVE_BYTES_RANGE"
        );
    }
    if let Some(bytes) = c.resources.index.java_scratch_bytes {
        ensure!(
            (64 * 1024..=codeana_model::MAX_JAVA_SCRATCH_BYTES).contains(&bytes),
            "JAVA_SCRATCH_BYTES_RANGE"
        );
    }
    if let Some(bytes) = c.resources.index.java_result_bytes {
        ensure!(
            (64 * 1024..=codeana_model::MAX_JAVA_RESULT_BYTES).contains(&bytes),
            "JAVA_RESULT_BYTES_RANGE"
        );
    }
    if let Some(count) = c.resources.index.java_fact_limit {
        ensure!((1..=5000000).contains(&count), "JAVA_FACT_LIMIT_RANGE");
    }
    if let Some(heap) = c.resources.index.java_native_heap_bytes {
        ensure!(
            (8 * 1024 * 1024..=2 * 1024 * 1024 * 1024).contains(&heap) && heap % (1024 * 1024) == 0,
            "JAVA_NATIVE_HEAP_RANGE"
        );
    }
    if let Some(seconds) = c.resources.index.java_compiler_timeout_seconds {
        ensure!((1..=3600).contains(&seconds), "COMPILER_TIMEOUT_BUDGET");
    }
    if let Some(v) = request.suggestions {
        c.build.suggestions = Some(v);
        origins.insert("build.suggestions".into(), "CLI".into());
    }
    ensure!(
        !language_required
            || c.languages
                .java
                .as_ref()
                .is_some_and(|l| l.enabled == Some(true))
            || c.languages
                .rust
                .as_ref()
                .is_some_and(|l| l.enabled == Some(true)),
        "NO_ENABLED_LANGUAGE"
    );
    for (name, language) in [
        ("java", &mut c.languages.java),
        ("rust", &mut c.languages.rust),
    ] {
        if let Some(l) = language {
            ensure!(l.enabled.is_some(), "LANGUAGE_ENABLED_REQUIRED: {name}");
            if name == "java" {
                ensure!(
                    l.mode.is_empty() || l.mode == "semantic",
                    "JAVA_REQUIRES_SEMANTIC"
                );
                if l.mode.is_empty() {
                    l.mode = "semantic".into();
                }
                l.analysis.source_version.get_or_insert("auto".into());
                l.analysis.toolchain.get_or_insert("auto".into());
                l.analysis.classpath.get_or_insert("existing".into());
                l.analysis.vm_classpath.get_or_insert(true);
                l.lombok.mode.get_or_insert("off".into());
                l.lombok.strict.get_or_insert(false);
                l.extensions.spring.enabled.get_or_insert(true);
                l.extensions.spring.xml.get_or_insert(false);
                l.extensions.sofa.enabled.get_or_insert(true);
                ensure!(l.analysis.edition.is_none(), "JAVA_EDITION_INVALID");
                ensure!(
                    l.analysis.vm_classpath != Some(false),
                    "VM_CLASSPATH_REQUIRED"
                );
                ensure!(
                    l.lombok
                        .mode
                        .as_deref()
                        .is_none_or(|v| matches!(v, "off" | "model")),
                    "INVALID_LOMBOK_MODE"
                );
                if let Some(v) = &request.source_version {
                    l.analysis.source_version = Some(v.clone());
                    origins.insert(
                        "languages.java.analysis.source_version".into(),
                        "CLI".into(),
                    );
                }
                if let Some(v) = &request.toolchain {
                    l.analysis.toolchain = Some(v.clone());
                    origins.insert("languages.java.analysis.toolchain".into(), "CLI".into());
                }
            } else {
                ensure!(
                    l.analysis.source_version.is_none() && l.lombok.mode.is_none(),
                    "JAVA_FIELD_IN_RUST"
                );
            }
        }
    }
    let java_inputs = if language_required {
        if let Some(java) = &c.languages.java {
            if java.discovery.build_system == "maven" || java.discovery.inputs_manifest.is_some() {
                let suggestions = c.build.suggestions.unwrap_or(true);
                let path = java.discovery.inputs_manifest.as_deref().ok_or_else(|| {
                    java_inputs::missing_inputs(root, ".code-ana/java-inputs.json", suggestions)
                })?;
                if !root.join(path).is_file() {
                    return Err(java_inputs::missing_inputs(root, path, suggestions));
                }
                let inputs = JavaInputs::read(root, path)?;
                if c.modules.is_empty() {
                    c.modules = inputs
                        .modules
                        .iter()
                        .map(|m| Module {
                            id: m.id.clone(),
                            language: "java".into(),
                            root: m.root.clone(),
                            source_roots: m
                                .source_roots
                                .iter()
                                .filter(|r| {
                                    c.scan.scopes.contains(&r.scope)
                                        || (r.scope == "MAIN"
                                            && c.scan.scopes.iter().any(|s| s == "TEST"))
                                })
                                .map(|r| SourceRoot {
                                    path: r.path.clone(),
                                    scope: r.scope.clone(),
                                    label: None,
                                })
                                .collect(),
                            analysis: Analysis {
                                source_version: Some(m.source_version.clone()),
                                ..Default::default()
                            },
                            ..Default::default()
                        })
                        .collect();
                }
                Some(inputs)
            } else {
                None
            }
        } else {
            None
        }
    } else {
        None
    };
    let mut ids = BTreeSet::new();
    let mut entries = BTreeSet::new();
    for m in &mut c.modules {
        ensure!(
            !m.id.is_empty() && ids.insert(m.id.clone()),
            "DUPLICATE_MODULE"
        );
        ensure!(relative(&m.root), "INVALID_MODULE_PATH");
        ensure!(
            entries.insert((m.language.clone(), m.root.clone(), m.manifest.clone())),
            "DUPLICATE_MODULE_ENTRY"
        );
        let l = match m.language.as_str() {
            "java" => c.languages.java.as_ref(),
            "rust" => c.languages.rust.as_ref(),
            _ => None,
        };
        ensure!(
            l.is_some_and(|l| l.enabled == Some(true)),
            "MODULE_LANGUAGE_NOT_ENABLED"
        );
        if let Some(p) = &m.manifest {
            ensure!(safe_path(p), "INVALID_MANIFEST_PATH");
        }
        if m.language == "rust" {
            ensure!(
                m.lombok.mode.is_none() && m.analysis.source_version.is_none(),
                "JAVA_FIELD_IN_RUST"
            );
        }
        if m.language == "java" {
            let defaults = l.context("MODULE_LANGUAGE_NOT_ENABLED")?;
            macro_rules! inherit {
                ($field:ident) => {
                    if m.analysis.$field.is_none() {
                        m.analysis.$field = defaults.analysis.$field.clone();
                    }
                };
            }
            inherit!(source_version);
            inherit!(toolchain);
            inherit!(classpath);
            inherit!(vm_classpath);
            if m.lombok.mode.is_none() {
                m.lombok.mode = defaults.lombok.mode.clone();
            }
            if m.lombok.strict.is_none() {
                m.lombok.strict = defaults.lombok.strict;
            }
            if m.extensions.spring.enabled.is_none() {
                m.extensions.spring.enabled = defaults.extensions.spring.enabled;
            }
            if m.extensions.spring.xml.is_none() {
                m.extensions.spring.xml = defaults.extensions.spring.xml;
            }
            if m.extensions.sofa.enabled.is_none() {
                m.extensions.sofa.enabled = defaults.extensions.sofa.enabled;
            }
            ensure!(
                m.analysis.vm_classpath != Some(false) && m.analysis.edition.is_none(),
                "INVALID_JAVA_MODULE_ANALYSIS"
            );
            ensure!(
                m.lombok
                    .mode
                    .as_deref()
                    .is_some_and(|v| matches!(v, "off" | "model")),
                "INVALID_LOMBOK_MODE"
            );
            ensure!(m.worlds.is_empty(), "JAVA_WORLDS_DERIVED_FROM_SOURCE_ROOTS");
        }
        if let Some(v) = &request.source_version
            && m.language == "java"
        {
            m.analysis.source_version = Some(v.clone());
        }
        if let Some(v) = &request.toolchain
            && m.language == "java"
        {
            m.analysis.toolchain = Some(v.clone());
        }
        let mut worlds = BTreeSet::new();
        for w in &m.worlds {
            ensure!(!w.id.is_empty() && worlds.insert(&w.id), "DUPLICATE_WORLD");
        }
        for s in &m.source_roots {
            if m.language == "java" && java_inputs.is_some() {
                java_inputs::root_path(&m.root, &s.path)?;
            } else {
                ensure!(relative(&s.path), "INVALID_SOURCE_ROOT");
            }
            ensure!(
                c.scan.scopes.contains(&s.scope)
                    || (m.language == "java"
                        && s.scope == "MAIN"
                        && c.scan.scopes.iter().any(|scope| scope == "TEST")),
                "SOURCE_SCOPE_EXCLUDED"
            );
            if let Some(label) = &s.label {
                ensure!(m.language == "java", "SOURCE_LABEL_REQUIRES_JAVA");
                ensure!(
                    !label.trim().is_empty()
                        && label.len() <= 256
                        && !label.chars().any(char::is_control),
                    "INVALID_SOURCE_LABEL"
                );
            }
        }
        glob(&m.scan.include)?;
        glob(&m.scan.exclude)?;
    }
    glob(&c.scan.include)?;
    glob(&c.scan.exclude)?;
    glob(&c.scan.text_only)?;
    glob(&c.search.text.include)?;
    c.build.suggestions.get_or_insert(true);
    if c.index.mode.is_empty() {
        c.index.mode = "auto".into();
    }
    if c.observability.mode.is_empty() {
        c.observability.mode = "basic".into();
    }
    if c.query.scopes.is_empty() {
        c.query.scopes.push("ALL".into());
    }
    // Sources for defaulted scalar fields are materialized for doctor.
    fn default_origins(
        value: &serde_json::Value,
        path: String,
        origins: &mut BTreeMap<String, String>,
    ) {
        if let Some(object) = value.as_object() {
            for (k, v) in object {
                default_origins(
                    v,
                    if path.is_empty() {
                        k.clone()
                    } else {
                        format!("{path}.{k}")
                    },
                    origins,
                );
            }
        } else if !value.is_null() {
            origins.entry(path).or_insert("builtin/inherited".into());
        }
    }
    default_origins(&serde_json::to_value(&c)?, String::new(), &mut origins);
    let semantic_digest = digest(&(&c.languages, &c.modules));
    let capture_digest = digest(&(&c.scan, &c.versions.capture));
    let config_digest = digest(&c);
    Ok(Effective {
        config: c,
        origins,
        config_digest,
        input_hash: hash(text.as_bytes()),
        semantic_digest,
        capture_digest,
        java_inputs,
    })
}
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
fn source_ownership(
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
    ensure!(owners.len() <= 1, "AMBIGUOUS_SOURCE_OWNER: {path}");
    Ok(owners
        .pop()
        .map(|(_, module, scope, language, physical)| (module, scope, language, physical)))
}

fn add_captured(
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
            "JAVA_INPUT_CHANGED_DURING_CAPTURE"
        );
        if !module.is_empty() {
            ensure!(
                existing.module.is_empty()
                    || (existing.module == module
                        && existing.scope == scope
                        && existing.world == key("world", &[&physical_module, &scope])),
                "AMBIGUOUS_SOURCE_OWNER"
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
        "JAVA_CAPTURE_BUDGET"
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
pub fn capture(root: &Path, request: &Request) -> Result<Capture> {
    let started = std::time::Instant::now();
    let deadline = std::time::Duration::from_secs(if request.deadline_seconds == 0 {
        120
    } else {
        request.deadline_seconds.min(MAX_TASK_DEADLINE_SECONDS)
    });
    let root = root.canonicalize()?;
    let text_search = request.operation == "search-text";
    let files_only = text_search || (request.operation == "snapshot" && request.name != "analysis");
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
    let mut manifest = Manifest::default();
    let mut blobs = BTreeMap::new();
    let spool = std::sync::Arc::new(
        tempfile::Builder::new()
            .prefix("codeana-capture-")
            .tempdir()?,
    );
    let mut total = 0;
    let ignored_output = git(
        &root,
        &[
            "ls-files",
            "--others",
            "--ignored",
            "--exclude-standard",
            "-z",
        ],
    );
    let in_git = git(&root, &["rev-parse", "--is-inside-work-tree"]).as_deref() == Some("true");
    ensure!(
        !in_git || ignored_output.is_some(),
        "GIT_IGNORED_ENUMERATION_FAILED"
    );
    let ignored: BTreeSet<String> = ignored_output
        .unwrap_or_default()
        .split('\0')
        .map(str::to_owned)
        .collect();
    let include_ignored = glob(&c.versions.capture.include_ignored)?;
    let mut scanned = 0usize;
    for entry in walkdir::WalkDir::new(&root)
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
        scanned += 1;
        ensure!(scanned <= 100_000, "SCAN_ENTRY_BUDGET");
        ensure!(started.elapsed() < deadline, "CAPTURE_TIMEOUT");
        let entry = entry?;
        if entry.depth() == 0 || entry.file_type().is_dir() {
            continue;
        }
        let p = entry
            .path()
            .strip_prefix(&root)?
            .to_str()
            .context("NON_UTF8_PATH")?
            .to_owned();
        ensure!(safe_path(&p), "UNSAFE_PATH");
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
        ensure!(
            !entry.file_type().is_symlink(),
            "SYMLINK_SOURCE_UNSUPPORTED: {p}"
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
            "CAPTURE_FILE_BUDGET"
        );
        let bytes = bounded_read(entry.path(), MAX_FRAME)?;
        total += bytes.len();
        ensure!(
            bytes.len() <= MAX_FRAME && total <= MAX_CAPTURE_BYTES,
            "CAPTURE_BUDGET"
        );
        ensure!(manifest.files.len() < 10_000, "CAPTURE_FILE_BUDGET");
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
        blobs.insert(h, CapturedBlob::bytes(&spool, &bytes)?);
    }
    if !files_only && let Some(inputs) = &effective.java_inputs {
        let metadata_path = c
            .languages
            .java
            .as_ref()
            .and_then(|l| l.discovery.inputs_manifest.as_deref())
            .context("JAVA_METADATA_PATH")?;
        let metadata = bounded_read(&root.join(metadata_path), 32 * 1024 * 1024)?;
        let reparsed: JavaInputs = serde_json::from_slice(&metadata)?;
        ensure!(
            digest(&reparsed) == digest(inputs),
            "JAVA_METADATA_CHANGED_DURING_CAPTURE"
        );
        add_captured(
            &mut manifest,
            &mut blobs,
            &mut total,
            &spool,
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
            ensure!(!excludes.is_match(path), "JAVA_CONTROL_EXCLUDED");
            let bytes = bounded_read(&root.join(path), MAX_FRAME)?;
            ensure!(hash(&bytes) == *expected, "JAVA_METADATA_STALE");
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
        }
        // External settings and Maven executable are identities, not captured source blobs.
        // Recheck them at capture time so an export cannot race a settings edit.
        for (path, expected) in &inputs.external_controls {
            ensure!(
                hash(&bounded_read(Path::new(path), MAX_FRAME)?) == *expected,
                "JAVA_METADATA_STALE: {path}"
            );
        }
        for m in &inputs.modules {
            ensure!(started.elapsed() < deadline, "CAPTURE_TIMEOUT");
            let config = c
                .modules
                .iter()
                .find(|v| v.id == m.id)
                .context("JAVA_MODULE_CONFIG_REQUIRED")?;
            ensure!(config.root == m.root, "JAVA_MODULE_ROOT_MISMATCH");
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
                    ensure!(!excludes.is_match(proof), "JAVA_GENERATED_PROOF_EXCLUDED");
                    let bytes = bounded_read(&root.join(proof), MAX_FRAME)?;
                    ensure!(hash(&bytes) == *expected, "GENERATED_INPUT_STALE");
                    add_captured(
                        &mut manifest,
                        &mut blobs,
                        &mut total,
                        &spool,
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
                        "BUILD_INPUT_REQUIRED: generated source root is missing"
                    );
                    continue;
                }
                ensure!(
                    dir.canonicalize()?.starts_with(&root),
                    "JAVA_INPUT_OUTSIDE_PROJECT"
                );
                for entry in walkdir::WalkDir::new(&dir).follow_links(false) {
                    let entry = entry?;
                    ensure!(started.elapsed() < deadline, "CAPTURE_TIMEOUT");
                    if entry.file_type().is_dir() {
                        continue;
                    }
                    ensure!(entry.file_type().is_file(), "JAVA_INPUT_SYMLINK");
                    scanned += 1;
                    ensure!(scanned <= 100000, "SCAN_ENTRY_BUDGET");
                    let path = entry
                        .path()
                        .strip_prefix(&root)?
                        .to_str()
                        .context("NON_UTF8_PATH")?
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
                            "AMBIGUOUS_SOURCE_OWNER: {path}"
                        );
                        owner
                    } else {
                        (m.id.clone(), r.scope.clone(), String::new(), m.id.clone())
                    };
                    add_captured(
                        &mut manifest,
                        &mut blobs,
                        &mut total,
                        &spool,
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
                ensure!(started.elapsed() < deadline, "CAPTURE_TIMEOUT");
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
                let (blob, h, size) = CapturedBlob::file(&spool, &path, MAX_ARTIFACT_BYTES)
                    .context("BUILD_INPUT_REQUIRED: dependency artifact")?;
                ensure!(h == artifact.sha256, "JAVA_ARTIFACT_STALE");
                total += size;
                ensure!(
                    total <= MAX_CAPTURE_BYTES && manifest.files.len() < 10000,
                    "JAVA_CAPTURE_BUDGET"
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
            &excludes,
            (started, deadline),
        )?;
    }
    // Validate the control input on every capture. Persist it for FILES/analysis;
    // current text search only exposes paths selected by its own include policy.
    ensure!(
        !excludes.is_match(".code-ana/config.toml"),
        "CONTROL_INPUT_EXCLUDED"
    );
    let bytes = bounded_read(&root.join(".code-ana/config.toml"), 1024 * 1024)?;
    let h = hash(&bytes);
    ensure!(h == effective.input_hash, "CONFIG_CHANGED_DURING_CAPTURE");
    if !text_search {
        total += bytes.len();
        ensure!(total <= MAX_CAPTURE_BYTES, "CAPTURE_BUDGET");
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
                    || effective.java_inputs.as_ref().is_some_and(|inputs| {
                        inputs
                            .modules
                            .iter()
                            .any(|m| m.id == module.id && !m.selected)
                    })
            });
        }
    }
    manifest.files.sort_by(|a, b| a.path.cmp(&b.path));
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
        "UNSUPPORTED_DISCOVERY_MODE: auto requires an adapter discovery provider"
    );
    if c.effective
        .config
        .languages
        .rust
        .as_ref()
        .is_some_and(|l| l.enabled == Some(true))
    {
        bail!("UNSUPPORTED_LANGUAGE: Rust source analysis is P3");
    }
    ensure!(
        c.manifest.files.iter().any(|s| s.language == "java")
            || c.effective
                .config
                .modules
                .iter()
                .any(|m| m.language == "java"),
        "NO_JAVA_MODULE: explicit modules or exported inputs required"
    );
    Ok(())
}

pub fn bounded_read(path: &Path, limit: usize) -> Result<Vec<u8>> {
    use std::io::Read;
    ensure!(
        !std::fs::symlink_metadata(path)?.file_type().is_symlink(),
        "SYMLINK_INPUT_UNSUPPORTED"
    );
    let file = std::fs::File::open(path)?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1).read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= limit, "INPUT_BYTE_BUDGET");
    Ok(bytes)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_semantic_encoding_preserves_captured_bytes_and_publication_role() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        std::fs::create_dir(root.join("src")).unwrap();
        let bytes = b"class A {} // \xb5\xa5\xd4\xaa";
        std::fs::write(root.join("src/A.java"), bytes).unwrap();
        std::fs::write(root.join(".code-ana/config.toml"),"schema_version=1\nkind=\"project\"\n[discovery]\nmode=\"explicit\"\n[languages.java]\nenabled=true\n[[modules]]\nid=\"app\"\nroot=\".\"\nlanguage=\"java\"\nsource_roots=[{path=\"src\",scope=\"MAIN\"}]\n").unwrap();
        let semantic = capture(
            root,
            &Request {
                operation: "index".into(),
                ..Default::default()
            },
        )
        .unwrap();
        let source = semantic
            .manifest
            .files
            .iter()
            .find(|s| s.path == "src/A.java")
            .unwrap();
        assert_eq!(source.hash, hash(bytes));
        assert_eq!(source.language, "java");
        assert!(!source.auxiliary);
        assert_eq!(
            semantic.blobs[&source.hash].read(32 * 1024 * 1024).unwrap(),
            bytes
        );
        assert_eq!(std::fs::read(root.join("src/A.java")).unwrap(), bytes);
        let files = capture(
            root,
            &Request {
                operation: "snapshot".into(),
                name: "files".into(),
                ..Default::default()
            },
        )
        .unwrap();
        assert!(
            files
                .manifest
                .files
                .iter()
                .any(|s| s.path == "src/A.java" && s.hash == hash(bytes))
        );
    }
    #[test]
    fn test_only_capture_retains_main_as_explicit_auxiliary_input() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        for path in [".code-ana", "src", "tests", "lib/src"] {
            std::fs::create_dir_all(root.join(path)).unwrap();
        }
        std::fs::write(root.join("src/App.java"), "class App {}").unwrap();
        std::fs::write(root.join("tests/Check.java"), "class Check { App app; }").unwrap();
        std::fs::write(root.join("lib/src/Api.java"), "class Api {}").unwrap();
        std::fs::write(root.join("pom.xml"), "<project/>").unwrap();
        let metadata = serde_json::json!({"schema_version":3,"controls":{"pom.xml":hash(b"<project/>")},
            "effective_model_sha256":hash(b"model"),"active_profiles_sha256":hash(b"profiles"),"scopes":["TEST"],
            "modules":[{"id":"app","root":".","source_version":"8","source_roots":[{"path":"src","scope":"MAIN"},{"path":"tests","scope":"TEST"}],"dependencies":{"MAIN":["lib"],"TEST":["lib"]}},
              {"id":"lib","root":"lib","source_version":"8","selected":false,"source_roots":[{"path":"src","scope":"MAIN"}]}]});
        std::fs::write(
            root.join(".code-ana/inputs.json"),
            serde_json::to_vec(&metadata).unwrap(),
        )
        .unwrap();
        std::fs::write(root.join(".code-ana/config.toml"),"schema_version=1\nkind=\"project\"\n[scan]\nscopes=[\"TEST\"]\n[languages.java]\nenabled=true\n[languages.java.discovery]\nbuild_system=\"maven\"\ninputs_manifest=\".code-ana/inputs.json\"\n").unwrap();
        let captured = capture(
            root,
            &Request {
                operation: "index".into(),
                ..Default::default()
            },
        )
        .unwrap();
        for path in ["src/App.java", "lib/src/Api.java"] {
            let source = captured
                .manifest
                .files
                .iter()
                .find(|s| s.path == path)
                .unwrap();
            assert!(source.auxiliary);
            assert_eq!(source.language, "java");
            assert_eq!(source.scope, "MAIN");
            assert_eq!(source.hash, hash(std::fs::read(root.join(path)).unwrap()));
        }
        assert!(
            !captured
                .manifest
                .files
                .iter()
                .find(|s| s.path == "tests/Check.java")
                .unwrap()
                .auxiliary
        );
    }
    #[test]
    fn dependency_capture_exceeds_frame_budget_and_pins_immutable_spool() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        std::fs::create_dir(root.join("src")).unwrap();
        std::fs::write(root.join("src/A.java"), "class A {}").unwrap();
        std::fs::write(root.join("pom.xml"), "<project/>").unwrap();
        std::fs::create_dir(root.join("resources")).unwrap();
        std::fs::write(
            root.join("resources/app.properties"),
            "feature.enabled=true",
        )
        .unwrap();
        let archive = root.join(".code-ana/external.jar");
        let bytes = vec![7u8; MAX_FRAME + 1024];
        std::fs::write(&archive, &bytes).unwrap();
        let expected = hash(&bytes);
        let metadata = serde_json::json!({"schema_version":3,
            "controls":{"pom.xml":hash(b"<project/>")},
            "effective_model_sha256":hash(b"model"), "active_profiles_sha256":hash(b"profiles"),
            "modules":[{"id":"app","root":".","source_version":"8",
                "source_roots":[{"path":"src","scope":"MAIN"}],
                "resource_roots":[{"path":"resources","scope":"MAIN"}],
                "classpath":{"MAIN":[{"path":archive,"sha256":expected}]}}]});
        std::fs::write(
            root.join(".code-ana/inputs.json"),
            serde_json::to_vec(&metadata).unwrap(),
        )
        .unwrap();
        std::fs::write(
            root.join(".code-ana/config.toml"),
            r#"schema_version=1
kind="project"
[discovery]
mode="auto"
[scan]
scopes=["MAIN"]
[languages.java]
enabled=true
[languages.java.analysis]
source_version="8"
classpath="explicit"
[languages.java.discovery]
build_system="maven"
inputs_manifest=".code-ana/inputs.json"
"#,
        )
        .unwrap();
        let capture = capture(root, &Request::default()).unwrap();
        let resource = capture
            .manifest
            .files
            .iter()
            .find(|s| s.path == "resources/app.properties")
            .unwrap();
        assert_eq!(
            (&*resource.module, &*resource.scope, &*resource.language),
            ("app", "MAIN", "")
        );
        assert_eq!(resource.world, key("world", &["app", "MAIN"]));
        let blob = capture.blobs[&expected].clone();
        let path = blob.as_ref().to_path_buf();
        assert_eq!(hash_file(&path).unwrap(), expected);
        std::fs::write(archive, b"changed after capture").unwrap();
        drop(capture);
        assert_eq!(blob.read(bytes.len()).unwrap(), bytes);
        assert!(blob.read(MAX_FRAME).is_err());
        drop(blob);
        assert!(!path.exists());
    }

    #[test]
    fn source_labels_keep_physical_worlds_and_reject_ambiguous_roots() {
        let mut module = Module {
            id: "physical".into(),
            language: "java".into(),
            root: ".".into(),
            source_roots: vec![
                SourceRoot {
                    path: "src".into(),
                    scope: "MAIN".into(),
                    label: Some("api".into()),
                },
                SourceRoot {
                    path: "src/bridge".into(),
                    scope: "MAIN".into(),
                    label: Some("bridge".into()),
                },
                SourceRoot {
                    path: "tests".into(),
                    scope: "TEST".into(),
                    label: Some("tests".into()),
                },
            ],
            ..Default::default()
        };
        for (path, label, scope) in [
            ("src/A.java", "api", "MAIN"),
            ("src/bridge/B.java", "bridge", "MAIN"),
            ("tests/T.java", "tests", "TEST"),
        ] {
            assert_eq!(
                source_ownership(std::slice::from_ref(&module), path).unwrap(),
                Some((label.into(), scope.into(), "java".into(), "physical".into()))
            );
        }
        assert!(
            source_ownership(std::slice::from_ref(&module), "outside/A.java")
                .unwrap()
                .is_none()
        );
        module.source_roots.push(SourceRoot {
            path: "src".into(),
            scope: "MAIN".into(),
            label: Some("other".into()),
        });
        assert!(
            source_ownership(std::slice::from_ref(&module), "src/A.java")
                .unwrap_err()
                .to_string()
                .contains("AMBIGUOUS_SOURCE_OWNER")
        );
        let mut second = module.clone();
        module.source_roots.pop();
        second.source_roots.pop();
        second.id = "another-physical".into();
        assert!(source_ownership(&[module, second], "src/A.java").is_err());
    }

    #[test]
    fn maven_capture_preserves_labels_through_metadata_recapture() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        for path in ["src/A.java", "bridge/B.java", "tests/T.java"] {
            let file = root.join(path);
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, "class Fixture {}").unwrap();
        }
        std::fs::write(root.join("pom.xml"), "<project/>").unwrap();
        let metadata = serde_json::json!({"schema_version":3,
            "controls":{"pom.xml":hash(b"<project/>")},
            "effective_model_sha256":hash(b"model"), "active_profiles_sha256":hash(b"profiles"),
            "modules":[{"id":"physical","root":".","source_version":"8",
                "source_roots":[{"path":"src","scope":"MAIN"}, {"path":"bridge","scope":"MAIN"}, {"path":"tests","scope":"TEST"}]}]});
        std::fs::write(
            root.join(".code-ana/inputs.json"),
            serde_json::to_vec(&metadata).unwrap(),
        )
        .unwrap();
        let text = r#"schema_version=1
kind="project"
[discovery]
mode="auto"
[scan]
scopes=["MAIN","TEST"]
[languages.java]
enabled=true
[languages.java.analysis]
source_version="8"
[languages.java.discovery]
build_system="maven"
inputs_manifest=".code-ana/inputs.json"
[[modules]]
id="physical"
root="."
language="java"
source_roots=[{path="src",scope="MAIN",label="api"},{path="bridge",scope="MAIN",label="bridge"},{path="tests",scope="TEST",label="tests"}]
"#;
        let config = root.join(".code-ana/config.toml");
        std::fs::write(&config, text).unwrap();
        let captured = capture(root, &Request::default()).unwrap();
        for (path, label, scope) in [
            ("src/A.java", "api", "MAIN"),
            ("bridge/B.java", "bridge", "MAIN"),
            ("tests/T.java", "tests", "TEST"),
        ] {
            let unit = captured
                .manifest
                .files
                .iter()
                .find(|s| s.path == path)
                .unwrap();
            assert_eq!(unit.module, label);
            assert_eq!(unit.world, key("world", &["physical", scope]));
        }
        std::fs::write(
            &config,
            text.replace(",label=\"api\"", "")
                .replace(",label=\"bridge\"", "")
                .replace(",label=\"tests\"", ""),
        )
        .unwrap();
        let defaulted = capture(root, &Request::default()).unwrap();
        for unit in defaulted
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "java")
        {
            assert_eq!(unit.module, "physical");
        }
        std::fs::write(&config, text.replace(r#"label="api""#, r#"label=" ""#)).unwrap();
        assert!(
            capture(root, &Request::default())
                .unwrap_err()
                .to_string()
                .contains("INVALID_SOURCE_LABEL")
        );
    }

    #[test]
    fn dependency_spool_rejects_oversize_before_copy() {
        let root = tempfile::tempdir().unwrap();
        let source = root.path().join("oversize.jar");
        std::fs::File::create(&source)
            .unwrap()
            .set_len(MAX_ARTIFACT_BYTES as u64 + 1)
            .unwrap();
        let spool = std::sync::Arc::new(tempfile::tempdir().unwrap());
        assert!(CapturedBlob::file(&spool, &source, MAX_ARTIFACT_BYTES).is_err());
        assert_eq!(std::fs::read_dir(spool.path()).unwrap().count(), 0);
    }
    #[test]
    fn config_examples_parse() {
        for s in [
            include_str!("../../../docs/config.toml"),
            include_str!("../../../docs/examples/java-fixture-config.toml"),
        ] {
            let _: ProjectConfig = toml::from_str(s).unwrap();
        }
        let _: UserConfig =
            toml::from_str(include_str!("../../../docs/examples/user-config.toml")).unwrap();
    }
    #[test]
    fn java_model_budget_is_explicit_and_bounded() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        let text = include_str!("../../../tests/fixtures/java/base/.code-ana/config.toml");
        let path = root.join(".code-ana/config.toml");
        std::fs::write(&path, text).unwrap();
        assert_eq!(
            resolve(&root, &Request::default())
                .unwrap()
                .config
                .resources
                .index
                .java_model_record_limit,
            None
        );
        for limit in [1, 100000, 1000000, 5000000] {
            std::fs::write(
                &path,
                format!("{text}\n[resources.index]\njava_model_record_limit={limit}\n"),
            )
            .unwrap();
            assert_eq!(
                resolve(&root, &Request::default())
                    .unwrap()
                    .config
                    .resources
                    .index
                    .java_model_record_limit,
                Some(limit)
            );
        }
        for limit in [0, 5000001] {
            std::fs::write(
                &path,
                format!("{text}\n[resources.index]\njava_model_record_limit={limit}\n"),
            )
            .unwrap();
            assert!(
                resolve(&root, &Request::default())
                    .unwrap_err()
                    .to_string()
                    .contains("JAVA_MODEL_RECORD_LIMIT_RANGE")
            );
        }
    }
    #[test]
    fn model_archive_and_context_timeout_are_explicit_and_bounded() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        let text = include_str!("../../../tests/fixtures/java/base/.code-ana/config.toml");
        let path = root.join(".code-ana/config.toml");
        for (field, good, bad, reason) in [
            (
                "java_model_archive_bytes",
                vec![65536, 67108864, 1073741824],
                vec![0, 65535, 1073741825],
                "JAVA_MODEL_ARCHIVE_BYTES_RANGE",
            ),
            (
                "java_native_heap_bytes",
                vec![8388608, 268435456, 1073741824, 2147483648],
                vec![0, 8388609, 2147483649],
                "JAVA_NATIVE_HEAP_RANGE",
            ),
            (
                "java_result_bytes",
                vec![
                    65536u64,
                    67108864,
                    4294967296,
                    codeana_model::MAX_JAVA_RESULT_BYTES,
                ],
                vec![0, 65535, codeana_model::MAX_JAVA_RESULT_BYTES + 1],
                "JAVA_RESULT_BYTES_RANGE",
            ),
            (
                "java_scratch_bytes",
                vec![
                    65536,
                    2147483648,
                    17179869184,
                    codeana_model::MAX_JAVA_SCRATCH_BYTES,
                ],
                vec![0, 65535, codeana_model::MAX_JAVA_SCRATCH_BYTES + 1],
                "JAVA_SCRATCH_BYTES_RANGE",
            ),
            (
                "java_fact_limit",
                vec![1, 100000, 5000000],
                vec![0, 5000001],
                "JAVA_FACT_LIMIT_RANGE",
            ),
            (
                "java_compiler_timeout_seconds",
                vec![1, 120, 3600],
                vec![0, 3601],
                "COMPILER_TIMEOUT_BUDGET",
            ),
        ] {
            for value in good {
                std::fs::write(
                    &path,
                    format!("{text}\n[resources.index]\n{field}={value}\n"),
                )
                .unwrap();
                assert!(resolve(&root, &Request::default()).is_ok());
            }
            for value in bad {
                std::fs::write(
                    &path,
                    format!("{text}\n[resources.index]\n{field}={value}\n"),
                )
                .unwrap();
                assert!(
                    resolve(&root, &Request::default())
                        .unwrap_err()
                        .to_string()
                        .contains(reason)
                );
            }
        }
    }
    #[test]
    fn explicit_false_and_semantic_digest_ignore_comments() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        let text = include_str!("../../../tests/fixtures/java/base/.code-ana/config.toml");
        let path = root.join(".code-ana/config.toml");
        std::fs::write(&path, text).unwrap();
        let a = resolve(&root, &Request::default()).unwrap();
        std::fs::write(&path, format!("# changed comment\n{text}")).unwrap();
        let b = resolve(
            &root,
            &Request {
                suggestions: Some(false),
                ..Default::default()
            },
        )
        .unwrap();
        assert_eq!(a.semantic_digest, b.semantic_digest);
        assert_ne!(a.input_hash, b.input_hash);
        assert_eq!(b.config.build.suggestions, Some(false));
        assert_eq!(
            b.config.modules[0].analysis.source_version.as_deref(),
            Some("17")
        );
    }
    #[test]
    fn unknown_and_false() {
        assert!(toml::from_str::<ProjectConfig>("javaparser = true").is_err());
        let l: Language = toml::from_str("enabled=false").unwrap();
        assert_eq!(l.enabled, Some(false));
    }
    #[test]
    fn cli_overrides_are_frozen_and_invalid_project_values_are_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        let path = root.join(".code-ana/config.toml");
        let source = include_str!("../../../tests/fixtures/java/base/.code-ana/config.toml");
        std::fs::write(&path, source).unwrap();
        let original = resolve(&root, &Request::default()).unwrap();
        let overridden = resolve(
            &root,
            &Request {
                source_version: Some("21".into()),
                toolchain: Some("cli-jdk".into()),
                suggestions: Some(false),
                ..Default::default()
            },
        )
        .unwrap();
        let java = overridden.config.languages.java.as_ref().unwrap();
        assert_eq!(java.analysis.source_version.as_deref(), Some("21"));
        assert_eq!(java.analysis.toolchain.as_deref(), Some("cli-jdk"));
        assert_eq!(
            overridden.config.modules[0]
                .analysis
                .source_version
                .as_deref(),
            Some("21")
        );
        assert_eq!(
            overridden.config.modules[0].analysis.toolchain.as_deref(),
            Some("cli-jdk")
        );
        assert_eq!(overridden.config.build.suggestions, Some(false));
        assert_eq!(
            overridden.origins["languages.java.analysis.source_version"],
            "CLI"
        );
        assert_ne!(overridden.semantic_digest, original.semantic_digest);
        for (old, new, code) in [
            ("scopes = [\"MAIN\"]", "scopes = [\"BAD\"]", "INVALID_SCOPE"),
            ("enabled = true", "enabled = false", "NO_ENABLED_LANGUAGE"),
            (
                "vm_classpath = true",
                "vm_classpath = false",
                "VM_CLASSPATH_REQUIRED",
            ),
        ] {
            assert!(source.contains(old));
            std::fs::write(&path, source.replace(old, new)).unwrap();
            assert!(
                resolve(&root, &Request::default())
                    .unwrap_err()
                    .to_string()
                    .contains(code)
            );
        }
    }
    #[test]
    fn user_worker_limit_boundaries() {
        let dir = tempfile::tempdir().unwrap();
        for (workers, accepted) in [(0, false), (1, true), (8, true), (9, false)] {
            std::fs::write(
                dir.path().join("config.toml"),
                format!("schema_version=1\nkind=\"user\"\n[limits]\nmax_workers={workers}\n"),
            )
            .unwrap();
            assert_eq!(
                load_user(dir.path()).is_ok(),
                accepted,
                "max_workers={workers}"
            );
        }
    }
}
