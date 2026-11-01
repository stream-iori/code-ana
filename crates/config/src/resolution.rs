//! Strict parsing, defaults and validation of project/user configuration.
use super::discovery::{discover_java, discover_rust, resolve_modules};
use super::execution::validate_query_resources;
use super::*;

pub fn load_user(home: &Path) -> Result<UserConfig> {
    let path = home.join("config.toml");
    let mut u = if path.exists() {
        let u: UserConfig =
            toml::from_str(std::str::from_utf8(&bounded_read(&path, 1024 * 1024)?)?)?;
        ensure!(
            u.schema_version == 1 && u.kind == "user",
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidUserConfig, "")
        );
        u
    } else {
        UserConfig {
            schema_version: 1,
            kind: "user".into(),
            ..Default::default()
        }
    };
    validate_query_resources(&u.resources.query)?;
    u.resources.index.analysis.validate()?;
    let workers = u.limits.max_workers.get_or_insert(2);
    ensure!(
        (1..=8).contains(workers),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidWorkerLimit, "")
    );
    ensure!(
        (1..=8).contains(u.limits.max_query_tasks.get_or_insert(2)),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidQueryLimit, "")
    );
    for t in u.toolchains.values() {
        ensure!(
            Path::new(&t.home).is_absolute(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::ToolchainHomeMustBeAbsolute,
                ""
            )
        );
    }
    Ok(u)
}
pub fn resolve(root: &Path, request: &Request) -> Result<Effective> {
    resolve_for(root, request, true)
}
pub fn resolve_files(root: &Path, request: &Request) -> Result<Effective> {
    resolve_for(root, request, false)
}
fn resolve_for(root: &Path, request: &Request, language_required: bool) -> Result<Effective> {
    let path = root.join(".code-ana/config.toml");
    ensure!(
        path.canonicalize()?.starts_with(root),
        codeana_model::DomainError::new(codeana_model::ErrorCode::ConfigPathEscape, "")
    );
    let config_bytes = bounded_read(&path, 1024 * 1024)
        .context("PROJECT_CONFIG_REQUIRED: .code-ana/config.toml")?;
    let text = std::str::from_utf8(&config_bytes).context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::InvalidProjectConfigEncoding,
        "",
    ))?;
    resolve_text(root, request, language_required, text, true)
}

pub(crate) fn resolve_text(
    root: &Path,
    request: &Request,
    language_required: bool,
    text: &str,
    catalog: bool,
) -> Result<Effective> {
    let mut c: ProjectConfig = toml::from_str(text).context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::InvalidProjectConfig,
        "",
    ))?;
    c.query.comments.validate()?;
    if catalog && c.inputs.is_some() {
        return inputs::resolve(root, request, language_required, c, text);
    }
    let rust_policy = c
        .languages
        .rust
        .as_ref()
        .filter(|l| l.enabled == Some(true))
        .map(|_| rust_inputs::policy(&c))
        .transpose()?;
    if let Some(java) = &c.languages.java {
        ensure!(java.dependencies.is_none(), "RUST_DEPENDENCIES_IN_JAVA");
    }
    ensure!(
        c.schema_version == 1 && c.kind == "project",
        codeana_model::DomainError::new(codeana_model::ErrorCode::UnsupportedConfigSchema, "")
    );
    if c.languages.rust.is_some() {
        rust_inputs::InputFilter::new(&c)?;
    }
    let mut origins = BTreeMap::new();
    let value: toml::Value = toml::from_str(text)?;

    origins_walk(&value, String::new(), &mut origins);
    resolve_common(&mut c, &mut origins)?;
    validate_index_resources(&c.resources.index)?;
    resolve_languages(&mut c, request, &mut origins, language_required)?;
    let java_inputs = discover_java(root, &mut c, language_required)?;
    let rust_inputs = discover_rust(root, &mut c, language_required, rust_policy.as_ref())?;
    let python_inputs = python_inputs::discover(root, &c, language_required)?;
    resolve_modules(&mut c, request, java_inputs.is_some())?;
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

    default_origins(&serde_json::to_value(&c)?, String::new(), &mut origins);
    validate_query_resources(&c.resources.query)?;
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
        rust_inputs,
        python_inputs,
        input_units: vec![],
    })
}
fn resolve_common(c: &mut ProjectConfig, origins: &mut BTreeMap<String, String>) -> Result<()> {
    if c.discovery.mode.is_empty() {
        c.discovery.mode = "explicit".into();
        origins.insert("discovery.mode".into(), "builtin".into());
    }
    ensure!(
        matches!(c.discovery.mode.as_str(), "explicit" | "auto"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidDiscoveryMode, "")
    );
    if c.scan.scopes.is_empty() {
        c.scan.scopes = vec!["MAIN".into()];
    }
    for s in &c.scan.scopes {
        ensure!(
            matches!(s.as_str(), "MAIN" | "TEST" | "GENERATED"),
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidScope, s.to_string())
        );
    }
    if c.scan.include.is_empty() {
        c.scan.include.push("**".into());
    }
    ensure!(
        c.index.mode.is_empty() || matches!(c.index.mode.as_str(), "auto" | "full"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidIndexMode, "")
    );
    ensure!(
        c.observability.mode.is_empty()
            || matches!(
                c.observability.mode.as_str(),
                "off" | "basic" | "full" | "detailed"
            ),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidObservabilityMode, "")
    );
    Ok(())
}

fn validate_index_resources(limits: &IndexResources) -> Result<()> {
    limits.analysis.validate()?;
    if let Some(memory) = limits.worker_memory_bytes {
        ensure!(
            (16 * 1024 * 1024..=4 * 1024 * 1024 * 1024).contains(&memory),
            "PROJECT_WORKER_MEMORY_LIMIT"
        );
    }
    if let Some(heap) = limits.java_compiler_heap_bytes {
        ensure!(
            (8 * 1024 * 1024..=512 * 1024 * 1024).contains(&heap) && heap % (1024 * 1024) == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidJavaCompilerHeap, "")
        );
    }
    if let Some(parallelism) = limits.java_parallelism {
        ensure!(
            (1..=8).contains(&parallelism),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaParallelismRange, "")
        );
    }
    if let Some(limit) = limits.java_model_record_limit {
        ensure!(
            (1..=5000000).contains(&limit),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaModelRecordLimitRange,
                ""
            )
        );
    }
    if let Some(bytes) = limits.java_model_decode_bytes {
        ensure!(
            (64 * 1024..=2 * 1024 * 1024 * 1024).contains(&bytes),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaModelDecodeBytesRange,
                ""
            )
        );
    }
    if let Some(bytes) = limits.java_model_archive_bytes {
        ensure!(
            (64 * 1024..=1024 * 1024 * 1024).contains(&bytes),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaModelArchiveBytesRange,
                ""
            )
        );
    }
    if let Some(bytes) = limits.java_scratch_bytes {
        ensure!(
            (64 * 1024..=codeana_model::MAX_JAVA_SCRATCH_BYTES).contains(&bytes),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaScratchBytesRange, "")
        );
    }
    if let Some(bytes) = limits.java_result_bytes {
        ensure!(
            (64 * 1024..=codeana_model::MAX_JAVA_RESULT_BYTES).contains(&bytes),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaResultBytesRange, "")
        );
    }
    if let Some(count) = limits.java_fact_limit {
        ensure!(
            (1..=5000000).contains(&count),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaFactLimitRange, "")
        );
    }
    if let Some(heap) = limits.java_native_heap_bytes {
        ensure!(
            (8 * 1024 * 1024..=2 * 1024 * 1024 * 1024).contains(&heap) && heap % (1024 * 1024) == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaNativeHeapRange, "")
        );
    }
    if let Some(seconds) = limits.java_compiler_timeout_seconds {
        ensure!(
            (1..=3600).contains(&seconds),
            codeana_model::DomainError::new(codeana_model::ErrorCode::CompilerTimeoutBudget, "")
        );
    }
    Ok(())
}

fn resolve_languages(
    c: &mut ProjectConfig,
    request: &Request,
    origins: &mut BTreeMap<String, String>,
    language_required: bool,
) -> Result<()> {
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
                .is_some_and(|l| l.enabled == Some(true))
            || c.languages
                .python
                .as_ref()
                .is_some_and(|l| l.enabled == Some(true)),
        codeana_model::DomainError::new(codeana_model::ErrorCode::NoEnabledLanguage, "")
    );
    for (name, language) in [
        ("java", &mut c.languages.java),
        ("rust", &mut c.languages.rust),
    ] {
        if let Some(l) = language {
            ensure!(
                l.enabled.is_some(),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::LanguageEnabledRequired,
                    name.to_string()
                )
            );
            if name == "java" {
                ensure!(
                    l.mode.is_empty() || l.mode == "semantic",
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaRequiresSemantic,
                        ""
                    )
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
                ensure!(
                    l.analysis.edition.is_none(),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaEditionInvalid,
                        ""
                    )
                );
                ensure!(
                    l.analysis.vm_classpath != Some(false),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::VmClasspathRequired,
                        ""
                    )
                );
                ensure!(
                    l.lombok
                        .mode
                        .as_deref()
                        .is_none_or(|v| matches!(v, "off" | "model")),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::InvalidLombokMode,
                        ""
                    )
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
                if l.mode.is_empty() {
                    l.mode = "semantic".into();
                }
                ensure!(
                    matches!(l.mode.as_str(), "syntax" | "semantic"),
                    "RUST_ANALYSIS_MODE"
                );
                ensure!(
                    l.analysis.source_version.is_none() && l.lombok.mode.is_none(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::JavaFieldInRust, "")
                );
            }
        }
    }
    if let Some(python) = &c.languages.python {
        ensure!(
            python.enabled.is_some(),
            "LANGUAGE_ENABLED_REQUIRED: python"
        );
    }
    Ok(())
}

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
