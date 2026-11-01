//! Resolve language inputs and module ownership without capturing source bytes.
use super::*;

pub(super) fn discover_java(
    root: &Path,
    c: &mut ProjectConfig,
    language_required: bool,
) -> Result<Option<JavaInputs>> {
    Ok(if language_required {
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
                if !c.modules.iter().any(|m| m.language == "java") {
                    c.modules.extend(
                        inputs
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
                            .collect::<Vec<_>>(),
                    );
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
    })
}

pub(super) fn discover_rust(
    root: &Path,
    c: &mut ProjectConfig,
    language_required: bool,
    rust_policy: Option<&String>,
) -> Result<Option<codeana_model::rust::Inputs>> {
    Ok(
        if language_required
            && c.languages
                .rust
                .as_ref()
                .is_some_and(|r| r.enabled == Some(true))
        {
            let path = c
                .languages
                .rust
                .as_ref()
                .and_then(|r| r.discovery.inputs_manifest.as_deref())
                .unwrap_or(".code-ana/rust-inputs.json")
                .to_owned();
            let inputs = rust_inputs::read(root, &path)?;
            ensure!(
                Some(&inputs.policy) == rust_policy,
                "RUST_INPUTS_STALE: selection policy changed; re-export Rust inputs"
            );
            c.languages.rust.as_mut().unwrap().discovery.inputs_manifest = Some(path);
            rust_inputs::discover(c, &inputs);
            Some(inputs)
        } else {
            None
        },
    )
}

pub(super) fn resolve_modules(
    c: &mut ProjectConfig,
    request: &Request,
    has_java_inputs: bool,
) -> Result<()> {
    let mut ids = BTreeSet::new();
    let mut entries = BTreeSet::new();
    for m in &mut c.modules {
        ensure!(
            !m.id.is_empty() && ids.insert(m.id.clone()),
            codeana_model::DomainError::new(codeana_model::ErrorCode::DuplicateModule, "")
        );
        ensure!(
            relative(&m.root),
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidModulePath, "")
        );
        ensure!(
            entries.insert((m.language.clone(), m.root.clone(), m.manifest.clone())),
            codeana_model::DomainError::new(codeana_model::ErrorCode::DuplicateModuleEntry, "")
        );
        if m.language == "python" {
            ensure!(
                c.languages
                    .python
                    .as_ref()
                    .is_some_and(|p| p.enabled == Some(true)),
                "MODULE_LANGUAGE_NOT_ENABLED: python"
            );
            for source in &m.source_roots {
                ensure!(
                    relative(&source.path) && matches!(source.scope.as_str(), "MAIN" | "TEST"),
                    "PYTHON_SOURCE_ROOT"
                );
            }
            continue;
        }
        let enabled = match m.language.as_str() {
            "java" => c.languages.java.as_ref().and_then(|l| l.enabled),
            "rust" => c.languages.rust.as_ref().and_then(|l| l.enabled),
            _ => None,
        };
        ensure!(
            enabled == Some(true),
            codeana_model::DomainError::new(codeana_model::ErrorCode::ModuleLanguageNotEnabled, "")
        );
        if let Some(p) = &m.manifest {
            ensure!(
                safe_path(p),
                codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidManifestPath, "")
            );
        }
        if m.language == "rust" {
            ensure!(
                m.lombok.mode.is_none() && m.analysis.source_version.is_none(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaFieldInRust, "")
            );
        }
        if m.language == "java" {
            let defaults = c
                .languages
                .java
                .as_ref()
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::ModuleLanguageNotEnabled,
                    "",
                ))?;
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
            if m.external.exclude_patterns.is_none() {
                m.external.exclude_patterns = defaults.external.exclude_patterns.clone();
            }
            if let Some(patterns) = &m.external.exclude_patterns {
                ensure!(
                    patterns.iter().all(|p| {
                        !p.trim().is_empty()
                            && !p.chars().any(char::is_whitespace)
                            && (!p.contains('*')
                                || p.strip_suffix(".**")
                                    .or_else(|| p.strip_suffix(".*"))
                                    .is_some_and(|prefix| {
                                        !prefix.is_empty() && !prefix.contains('*')
                                    }))
                    }),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::InvalidExternalExcludePattern,
                        ""
                    )
                );
            }
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
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::InvalidJavaModuleAnalysis,
                    ""
                )
            );
            ensure!(
                m.lombok
                    .mode
                    .as_deref()
                    .is_some_and(|v| matches!(v, "off" | "model")),
                codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidLombokMode, "")
            );
            ensure!(
                m.worlds.is_empty(),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaWorldsDerivedFromSourceRoots,
                    ""
                )
            );
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
            ensure!(
                !w.id.is_empty() && worlds.insert(&w.id),
                codeana_model::DomainError::new(codeana_model::ErrorCode::DuplicateWorld, "")
            );
        }
        for s in &m.source_roots {
            if m.language == "java" && has_java_inputs {
                java_inputs::root_path(&m.root, &s.path)?;
            } else {
                ensure!(
                    relative(&s.path),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::InvalidSourceRoot,
                        ""
                    )
                );
            }
            ensure!(
                c.scan.scopes.contains(&s.scope)
                    || (m.language == "java"
                        && s.scope == "MAIN"
                        && c.scan.scopes.iter().any(|scope| scope == "TEST")),
                codeana_model::DomainError::new(codeana_model::ErrorCode::SourceScopeExcluded, "")
            );
            if let Some(label) = &s.label {
                ensure!(
                    m.language == "java",
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::SourceLabelRequiresJava,
                        ""
                    )
                );
                ensure!(
                    !label.trim().is_empty()
                        && label.len() <= 256
                        && !label.chars().any(char::is_control),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::InvalidSourceLabel,
                        ""
                    )
                );
            }
        }
        glob(&m.scan.include)?;
        glob(&m.scan.exclude)?;
    }
    Ok(())
}
