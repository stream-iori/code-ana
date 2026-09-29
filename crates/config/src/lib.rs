//! Strict project configuration and bounded, immutable source capture.
use anyhow::{Context, Result, bail, ensure};
use codeana_model::*;
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
    build_system: String
});
schema!(Scan {scopes:Vec<String>,include:Vec<String>,exclude:Vec<String>,text_only:Vec<String>});
schema!(Build {suggestions:Option<bool>});
schema!(Index { mode: String });
schema!(Resources {
    index: IndexResources,
    query: QueryResources
});
schema!(IndexResources {worker_memory_bytes:Option<u64>});
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
    scope: String
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
schema!(DaemonPolicy {idle_timeout_seconds:Option<u64>});
schema!(Limits {max_workers:Option<usize>,max_worker_memory_bytes:Option<u64>,max_total_worker_memory_bytes:Option<u64>});
schema!(Toolchain {
    language: String,
    home: String
});
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Effective {
    pub config: ProjectConfig,
    pub origins: BTreeMap<String, String>,
    pub config_digest: String,
    pub input_hash: String,
    pub semantic_digest: String,
    pub capture_digest: String,
}
#[derive(Debug, Clone)]
pub struct Capture {
    pub root: PathBuf,
    pub project: String,
    pub checkout: String,
    pub target: String,
    pub effective: Effective,
    pub manifest: Manifest,
    pub blobs: BTreeMap<String, Vec<u8>>,
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
                ensure!(
                    l.extensions.spring.enabled != Some(false)
                        || l.extensions.sofa.enabled == Some(false),
                    "SOFA_REQUIRES_SPRING"
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
            ensure!(
                m.extensions.spring.enabled != Some(false)
                    || m.extensions.sofa.enabled == Some(false),
                "SOFA_REQUIRES_SPRING"
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
            ensure!(relative(&s.path), "INVALID_SOURCE_ROOT");
            ensure!(c.scan.scopes.contains(&s.scope), "SOURCE_SCOPE_EXCLUDED");
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
pub fn capture(root: &Path, request: &Request) -> Result<Capture> {
    let started = std::time::Instant::now();
    let deadline = std::time::Duration::from_secs(if request.deadline_seconds == 0 {
        120
    } else {
        request.deadline_seconds.min(3600)
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
        let mut owners = Vec::new();
        for m in &c.modules {
            for s in &m.source_roots {
                let dir = Path::new(&m.root).join(&s.path);
                let norm = dir
                    .components()
                    .filter_map(|x| {
                        if let std::path::Component::Normal(x) = x {
                            Some(x)
                        } else {
                            None
                        }
                    })
                    .collect::<PathBuf>();
                if Path::new(&p).starts_with(norm)
                    && (m.scan.scopes.is_empty() || m.scan.scopes.contains(&s.scope))
                    && (m.scan.include.is_empty() || glob(&m.scan.include)?.is_match(&p))
                    && !glob(&m.scan.exclude)?.is_match(&p)
                    && ((m.language == "java" && p.ends_with(".java"))
                        || (m.language == "rust" && p.ends_with(".rs")))
                {
                    owners.push((m.id.clone(), s.scope.clone(), m.language.clone()));
                }
            }
        }
        ensure!(owners.len() <= 1, "AMBIGUOUS_SOURCE_OWNER: {p}");
        let (module, scope, language) =
            owners
                .pop()
                .unwrap_or((String::new(), "RESOURCE".into(), String::new()));
        ensure!(
            entry.metadata()?.len() <= MAX_FRAME as u64,
            "CAPTURE_FILE_BUDGET"
        );
        let bytes = bounded_read(entry.path(), MAX_FRAME)?;
        total += bytes.len();
        ensure!(
            bytes.len() <= MAX_FRAME && total <= MAX_RESULT,
            "CAPTURE_BUDGET"
        );
        ensure!(manifest.files.len() < 10_000, "CAPTURE_FILE_BUDGET");
        let h = hash(&bytes);
        let world = if module.is_empty() {
            "files".into()
        } else {
            key("world", &[&module, &scope])
        };
        manifest.files.push(SourceUnit {
            path: p,
            hash: h.clone(),
            size: bytes.len() as u64,
            module,
            world,
            scope,
            language,
        });
        blobs.insert(h, bytes);
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
        ensure!(total <= MAX_RESULT, "CAPTURE_BUDGET");
        manifest.files.push(SourceUnit {
            path: ".code-ana/config.toml".into(),
            hash: h.clone(),
            size: bytes.len() as u64,
            module: String::new(),
            world: "files".into(),
            scope: "CONTROL".into(),
            language: String::new(),
        });
        blobs.insert(h, bytes);
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
        c.effective.config.discovery.mode == "explicit",
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
        c.manifest.files.iter().any(|s| s.language == "java"),
        "NO_JAVA_SOURCES: explicit source_roots required"
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
