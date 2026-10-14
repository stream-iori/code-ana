//! Versioned, externally prepared Maven inputs. This module never starts a build tool.
use super::*;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JavaInputs {
    pub schema_version: u32,
    pub controls: BTreeMap<String, String>,
    /// Maven settings live outside the project; record only their hashes, never their contents.
    #[serde(default)]
    pub external_controls: BTreeMap<String, String>,
    #[serde(default)]
    pub effective_model_sha256: String,
    #[serde(default)]
    pub active_profiles_sha256: String,
    #[serde(default)]
    pub selected_profiles: Vec<String>,
    #[serde(default)]
    pub scopes: Vec<String>,
    #[serde(default)]
    pub resolved_classpath: bool,
    /// Default is only for historical report snapshots; live inputs require schema 4 and this field.
    #[serde(default)]
    pub dependency_mode: DependencyMode,
    #[serde(default)]
    pub dependency_failure: Option<DependencyFailure>,
    #[serde(default)]
    pub receipt: Option<ExportReceipt>,
    pub modules: Vec<InputModule>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExportReceipt {
    pub nonce: String,
    pub metadata_sha256: String,
    pub inputs_sha256: String,
    pub extension_sha256: String,
    pub maven_processes: u32,
    #[serde(default)]
    pub prerequisite_classpath_rounds: u32,
    #[serde(default)]
    pub dependency_failure_sha256: Option<String>,
}
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DependencyMode {
    #[default]
    Resolved,
    SourceOnlyRequested,
    SourceOnlyFallback,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DependencyFailure {
    pub module: String,
    pub pom: String,
    pub coordinate: String,
    pub reason: String,
    pub round: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputModule {
    pub id: String,
    pub root: String,
    #[serde(default)]
    pub physical_id: String,
    #[serde(default)]
    pub coordinate: String,
    #[serde(default)]
    pub packaging: String,
    #[serde(default = "enabled")]
    pub selected: bool,
    #[serde(default = "enabled")]
    pub resolved_classpath: bool,
    #[serde(default)]
    pub effective_model_sha256: String,
    pub source_version: String,
    pub source_roots: Vec<InputRoot>,
    #[serde(default)]
    pub resource_roots: Vec<InputRoot>,
    #[serde(default)]
    pub dependencies: BTreeMap<String, Vec<String>>,
    #[serde(default)]
    pub classpath: BTreeMap<String, Vec<Artifact>>,
    #[serde(default)]
    pub output_roots: Vec<String>,
    #[serde(default)]
    pub dependency_order: BTreeMap<String, Vec<DependencyInput>>,
}
fn enabled() -> bool {
    true
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum DependencyInput {
    Classpath {
        index: usize,
    },
    Reactor {
        module: String,
        coordinate: String,
        classifier: String,
        extension: String,
    },
    External {
        path: String,
        sha256: String,
        coordinate: String,
        classifier: String,
        extension: String,
    },
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputRoot {
    pub path: String,
    pub scope: String,
    #[serde(default)]
    pub generated: bool,
    #[serde(default)]
    pub generated_from: BTreeMap<String, String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Artifact {
    pub path: String,
    pub sha256: String,
    #[serde(default)]
    pub coordinate: String,
    #[serde(default)]
    pub classifier: String,
    #[serde(default)]
    pub extension: String,
}

/// Normalize a module-relative root without letting a shared root escape the project.
pub fn root_path(module: &str, path: &str) -> Result<PathBuf> {
    let mut result = PathBuf::new();
    for part in Path::new(module).join(path).components() {
        match part {
            std::path::Component::Normal(p) => result.push(p),
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => ensure!(
                result.pop(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaInputRootEscape, "")
            ),
            _ => anyhow::bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaInputRootAbsolute,
                ""
            )),
        }
    }
    Ok(result)
}
impl JavaInputs {
    pub fn read(root: &Path, path: &str) -> Result<Self> {
        let canonical_root = root.canonicalize()?;
        let root = canonical_root.as_path();
        ensure!(
            safe_path(path),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaInputsPath, "")
        );
        ensure!(
            root.join(path).canonicalize()?.starts_with(root),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaMetadataPathEscape, "")
        );
        let raw: serde_json::Value =
            serde_json::from_slice(&bounded_read(&root.join(path), 32 * 1024 * 1024)?)?;
        ensure!(
            raw.get("schema_version").and_then(|v| v.as_u64()) == Some(4),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaInputsSchema,
                "regenerate inputs with the schema 4 exporter"
            )
        );
        ensure!(
            raw.get("dependency_mode").is_some(),
            "JAVA_DEPENDENCY_MODE_REQUIRED"
        );
        let value: Self = serde_json::from_value(raw)?;
        ensure!(
            value.schema_version == 4 && !value.modules.is_empty() && value.modules.len() <= 256,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaInputsSchema,
                "regenerate inputs with the schema 4 exporter".to_string()
            )
        );
        ensure!(
            value.resolved_classpath == (value.dependency_mode == DependencyMode::Resolved)
                && value.dependency_failure.is_some()
                    == (value.dependency_mode == DependencyMode::SourceOnlyFallback),
            "JAVA_DEPENDENCY_MODE"
        );
        if value.dependency_mode == DependencyMode::Resolved {
            ensure!(
                value
                    .modules
                    .iter()
                    .filter(|m| m.selected)
                    .all(|m| m.resolved_classpath),
                "JAVA_SELECTED_CLASSPATH_REQUIRED"
            );
        }
        if value.dependency_mode != DependencyMode::Resolved {
            ensure!(
                value.modules.iter().all(|m| !m.resolved_classpath
                    && m.classpath.values().all(Vec::is_empty)
                    && m.dependency_order
                        .values()
                        .flatten()
                        .all(|d| matches!(d, DependencyInput::Reactor { .. }))),
                "JAVA_SOURCE_ONLY_CLASSPATH"
            );
        }
        if let Some(failure) = &value.dependency_failure {
            ensure!(
                value.modules.iter().any(|m| m.id == failure.module)
                    && safe_path(&failure.pom)
                    && value.controls.contains_key(&failure.pom)
                    && failure.round <= 256
                    && failure.reason.len() <= 4096
                    && failure.coordinate.len() <= 4096,
                "JAVA_DEPENDENCY_FAILURE"
            );
        }
        let failure_hash = value
            .receipt
            .as_ref()
            .and_then(|r| r.dependency_failure_sha256.as_ref());
        ensure!(
            failure_hash.is_some() == value.dependency_failure.is_some()
                && failure_hash
                    .is_none_or(|h| h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit())),
            "JAVA_DEPENDENCY_FAILURE_RECEIPT"
        );
        if let Some(receipt) = &value.receipt {
            if let Some(failure) = &value.dependency_failure {
                ensure!(
                    receipt.inputs_sha256 == receipt.metadata_sha256
                        && receipt.prerequisite_classpath_rounds == failure.round,
                    "JAVA_DEPENDENCY_FAILURE_RECEIPT"
                );
            }
            ensure!(
                receipt.maven_processes == 1
                    && receipt.prerequisite_classpath_rounds <= 256
                    && receipt.nonce.len() == 36
                    && [
                        &receipt.metadata_sha256,
                        &receipt.inputs_sha256,
                        &receipt.extension_sha256
                    ]
                    .iter()
                    .all(|h| h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit())),
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaMavenReceipt, "")
            );
        }
        ensure!(
            !value.controls.is_empty(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaInputsControlsRequired,
                ""
            )
        );
        ensure!(
            value.effective_model_sha256.len() == 64
                && value
                    .effective_model_sha256
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit())
                && value.active_profiles_sha256.len() == 64
                && value
                    .active_profiles_sha256
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit())
                && value
                    .selected_profiles
                    .iter()
                    .all(|p| !p.is_empty() && !p.contains(',')),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaEffectiveModelIdentity,
                ""
            )
        );
        for (path, expected) in &value.controls {
            ensure!(
                safe_path(path) && root.join(path).canonicalize()?.starts_with(root),
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaControlPath, "")
            );
            ensure!(
                hash(bounded_read(&root.join(path), MAX_FRAME)?) == *expected,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaMetadataStale,
                    path.to_string()
                )
            );
        }
        for (path, expected) in &value.external_controls {
            ensure!(
                Path::new(path).is_absolute()
                    && expected.len() == 64
                    && expected.bytes().all(|b| b.is_ascii_hexdigit()),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaExternalControlPath,
                    ""
                )
            );
            ensure!(
                hash(bounded_read(Path::new(path), MAX_FRAME)?) == *expected,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaMetadataStale,
                    path.to_string()
                )
            );
        }
        let ids: BTreeSet<_> = value.modules.iter().map(|m| m.id.as_str()).collect();
        ensure!(
            ids.len() == value.modules.len(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaDuplicateModule, "")
        );
        for m in &value.modules {
            ensure!(
                relative(&m.root)
                    && m.source_version == "8"
                    && (m.physical_id.is_empty() || m.physical_id == m.root),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaModuleUnsupported,
                    ""
                )
            );
            for output in &m.output_roots {
                root_path(&m.root, output)?;
            }
            for r in m.source_roots.iter().chain(&m.resource_roots) {
                root_path(&m.root, &r.path)?;
                ensure!(
                    !r.path.is_empty() && matches!(r.scope.as_str(), "MAIN" | "TEST"),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::JavaInputRoot, "")
                );
                ensure!(
                    !r.generated || !r.generated_from.is_empty(),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::GeneratedInputProofRequired,
                        ""
                    )
                );
                for (p, h) in &r.generated_from {
                    ensure!(
                        safe_path(p) && hash(bounded_read(&root.join(p), MAX_FRAME)?) == *h,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GeneratedInputStale,
                            ""
                        )
                    );
                }
            }
            for (scope, deps) in &m.dependencies {
                ensure!(
                    matches!(scope.as_str(), "MAIN" | "TEST")
                        && deps.iter().all(|d| ids.contains(d.as_str())),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaDependencyModule,
                        ""
                    )
                );
            }
            for (scope, artifacts) in &m.classpath {
                ensure!(
                    matches!(scope.as_str(), "MAIN" | "TEST"),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaClasspathScope,
                        ""
                    )
                );
                for a in artifacts {
                    ensure!(
                        a.sha256.len() == 64
                            && a.sha256.bytes().all(|b| b.is_ascii_hexdigit())
                            && (Path::new(&a.path).is_absolute() || safe_path(&a.path)),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaArtifactIdentity,
                            ""
                        )
                    );
                }
            }
            for (scope, order) in &m.dependency_order {
                ensure!(
                    matches!(scope.as_str(), "MAIN" | "TEST"),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaClasspathScope,
                        ""
                    )
                );
                let reactor: Vec<_> = order
                    .iter()
                    .filter_map(|d| match d {
                        DependencyInput::Reactor {
                            module,
                            classifier,
                            extension,
                            ..
                        } => Some((module, classifier, extension)),
                        _ => None,
                    })
                    .collect();
                ensure!(
                    reactor
                        .iter()
                        .all(|(id, classifier, extension)| ids.contains(id.as_str())
                            && classifier.is_empty()
                            && extension.as_str() == "jar"),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaReactorArtifactUnsupported,
                        ""
                    )
                );
                let emitted: Vec<_> = reactor.iter().map(|(id, _, _)| (*id).clone()).collect();
                ensure!(
                    emitted == m.dependencies.get(scope).cloned().unwrap_or_default(),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaDependencyOrderMismatch,
                        ""
                    )
                );
                let mut next_external = 0;
                let external: Vec<_> = order
                    .iter()
                    .filter_map(|d| match d {
                        DependencyInput::Classpath { index } => {
                            // Each external entry must consume the next classpath slot,
                            // so duplicate/out-of-order/omitted references cannot pass.
                            let valid = *index == next_external;
                            next_external += 1;
                            if !valid {
                                return Some(("INVALID", "", "", "", ""));
                            }
                            let artifact = m.classpath.get(scope).and_then(|cp| cp.get(*index));
                            Some(
                                artifact
                                    .map(|a| {
                                        (
                                            a.path.as_str(),
                                            a.sha256.as_str(),
                                            a.coordinate.as_str(),
                                            a.classifier.as_str(),
                                            a.extension.as_str(),
                                        )
                                    })
                                    .unwrap_or(("INVALID", "", "", "", "")),
                            )
                        }
                        DependencyInput::External {
                            path,
                            sha256,
                            coordinate,
                            classifier,
                            extension,
                        } => {
                            next_external += 1;
                            Some((
                                path.as_str(),
                                sha256.as_str(),
                                coordinate.as_str(),
                                classifier.as_str(),
                                extension.as_str(),
                            ))
                        }
                        _ => None,
                    })
                    .collect();
                let cp: Vec<_> = m
                    .classpath
                    .get(scope)
                    .into_iter()
                    .flatten()
                    .map(|a| {
                        (
                            a.path.as_str(),
                            a.sha256.as_str(),
                            a.coordinate.as_str(),
                            a.classifier.as_str(),
                            a.extension.as_str(),
                        )
                    })
                    .collect();
                ensure!(
                    external == cp,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaClasspathOrderMismatch,
                        ""
                    )
                );
            }
        }
        // A cycle has no supported javac compilation plan.
        for m in &value.modules {
            let mut pending = vec![m.id.clone()];
            let mut visited = BTreeSet::new();
            while let Some(id) = pending.pop() {
                if !visited.insert(id.clone()) {
                    continue;
                }
                let module = value.modules.iter().find(|m| m.id == id).context(
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaModuleMissing,
                        "",
                    ),
                )?;
                for dep in module.dependencies.values().flatten() {
                    ensure!(
                        dep != &m.id,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaModuleCycle,
                            ""
                        )
                    );
                    pending.push(dep.clone());
                }
            }
        }
        Ok(value)
    }
}

pub fn missing_inputs(root: &Path, path: &str, suggestions: bool) -> anyhow::Error {
    let advice = BuildAdvice {
        missing: vec![path.into()],
        cwd: root.to_string_lossy().into_owned(),
        argv: if suggestions { vec!["codeana-export-java-inputs".into(),"--project".into(),root.to_string_lossy().into_owned(),"--output".into(),root.join(path).to_string_lossy().into_owned()] } else { vec![] },
        purpose: "Externally export the effective Maven model and ordered classpath".into(),
        expected: vec![path.into()],
        impact: "The explicit exporter runs Maven extensions and may download dependencies; index never executes it".into(),
        retry: "Submit a new index task after preparing the input".into(),
        ..Default::default()
    };
    anyhow::anyhow!(codeana_model::DomainError::new(
        codeana_model::ErrorCode::BuildInputRequired,
        serde_json::to_string(&advice)
            .expect("build advice serialization")
            .to_string()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn input(root: &Path) -> serde_json::Value {
        std::fs::write(root.join("pom.xml"), b"<project/>").unwrap();
        serde_json::json!({"schema_version":4,"resolved_classpath":true,"dependency_mode":"resolved","controls":{"pom.xml":hash(b"<project/>")},"effective_model_sha256":hash(b"effective"),"active_profiles_sha256":hash(b"profiles"),"modules":[{"id":"app","root":".","source_version":"8","source_roots":[{"path":"src","scope":"MAIN"}]}]})
    }
    #[test]
    fn source_only_state_rejects_partial_jars_and_requires_failure_receipt() {
        let root = tempfile::tempdir().unwrap();
        let mut value = input(root.path());
        value["resolved_classpath"] = serde_json::json!(false);
        value["dependency_mode"] = serde_json::json!("source_only_requested");
        value["modules"][0]["resolved_classpath"] = serde_json::json!(false);
        let check = |value: &serde_json::Value| {
            std::fs::write(
                root.path().join("inputs.json"),
                serde_json::to_vec(value).unwrap(),
            )
            .unwrap();
            JavaInputs::read(root.path(), "inputs.json")
        };
        assert!(check(&value).is_ok());
        value["modules"][0]["classpath"] =
            serde_json::json!({"MAIN":[{"path":"old.jar","sha256":hash(b"old")}]});
        assert!(
            check(&value)
                .unwrap_err()
                .to_string()
                .contains("JAVA_SOURCE_ONLY_CLASSPATH")
        );
        value["modules"][0]["classpath"] = serde_json::json!({});
        value["dependency_mode"] = serde_json::json!("source_only_fallback");
        assert!(check(&value).is_err());
        value["dependency_failure"] = serde_json::json!({"module":"app","pom":"pom.xml","coordinate":"fixture:missing:1","reason":"offline","round":0});
        assert!(
            check(&value)
                .unwrap_err()
                .to_string()
                .contains("JAVA_DEPENDENCY_FAILURE_RECEIPT")
        );
        value["receipt"] = serde_json::json!({"nonce":"n".repeat(36),"maven_processes":1,"metadata_sha256":hash(b"m"),"inputs_sha256":hash(b"m"),"extension_sha256":hash(b"e"),"dependency_failure_sha256":hash(b"f")});
        assert!(check(&value).is_ok());
        value["dependency_failure"]["pom"] = serde_json::json!("unknown.xml");
        assert!(check(&value).is_err());
        value["schema_version"] = serde_json::json!(3);
        value.as_object_mut().unwrap().remove("dependency_mode");
        assert!(
            check(&value)
                .unwrap_err()
                .to_string()
                .contains("schema 4 exporter")
        );
    }
    #[test]
    fn historical_report_inputs_remain_readable_but_live_inputs_require_explicit_mode() {
        let root = tempfile::tempdir().unwrap();
        let mut value = input(root.path());
        value.as_object_mut().unwrap().remove("dependency_mode");
        std::fs::write(
            root.path().join("inputs.json"),
            serde_json::to_vec(&value).unwrap(),
        )
        .unwrap();
        assert!(
            JavaInputs::read(root.path(), "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("MODE_REQUIRED")
        );
        value["schema_version"] = serde_json::json!(3);
        let historical: JavaInputs = serde_json::from_value(value).unwrap();
        assert_eq!(historical.schema_version, 3);
        assert_eq!(historical.dependency_mode, DependencyMode::Resolved);
    }
    #[test]
    fn compact_classpath_order_rejects_duplicate_missing_and_reordered_slots() {
        let root = tempfile::tempdir().unwrap();
        let mut value = input(root.path());
        value["modules"][0]["classpath"] = serde_json::json!({"MAIN":[
            {"path":"a.jar", "sha256":hash(b"a")},
            {"path":"b.jar", "sha256":hash(b"b")}]});
        for (slots, accepted) in [
            (vec![0, 1], true),
            (vec![1, 0], false),
            (vec![0, 0], false),
            (vec![0], false),
            (vec![0, 2], false),
        ] {
            value["modules"][0]["dependency_order"] = serde_json::json!({"MAIN":
                slots.into_iter().map(|index| serde_json::json!({"kind":"classpath","index":index})).collect::<Vec<_>>()});
            std::fs::write(
                root.path().join("inputs.json"),
                serde_json::to_vec(&value).unwrap(),
            )
            .unwrap();
            assert_eq!(
                JavaInputs::read(root.path(), "inputs.json").is_ok(),
                accepted
            );
        }
    }
    #[test]
    fn rejects_stale_controls_cycles_and_unproved_generated_inputs() {
        let root = tempfile::tempdir().unwrap();
        let mut value = input(root.path());
        let path = root.path().join("inputs.json");
        std::fs::write(&path, serde_json::to_vec(&value).unwrap()).unwrap();
        assert!(JavaInputs::read(root.path(), "inputs.json").is_ok());
        std::fs::write(root.path().join("pom.xml"), b"changed").unwrap();
        assert!(
            JavaInputs::read(root.path(), "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("STALE")
        );
        std::fs::write(root.path().join("pom.xml"), b"<project/>").unwrap();
        value["modules"][0]["dependencies"] = serde_json::json!({"MAIN":["app"]});
        std::fs::write(&path, serde_json::to_vec(&value).unwrap()).unwrap();
        assert!(
            JavaInputs::read(root.path(), "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("CYCLE")
        );
        value["modules"][0]["dependencies"] = serde_json::json!({});
        value["modules"][0]["source_roots"][0]["generated"] = serde_json::json!(true);
        std::fs::write(&path, serde_json::to_vec(&value).unwrap()).unwrap();
        assert!(
            JavaInputs::read(root.path(), "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("PROOF_REQUIRED")
        );
    }
    #[test]
    fn disabled_suggestions_keep_missing_input_evidence() {
        let message =
            missing_inputs(Path::new("/fixture"), ".code-ana/inputs.json", false).to_string();
        let advice: BuildAdvice =
            serde_json::from_str(message.strip_prefix("BUILD_INPUT_REQUIRED: ").unwrap()).unwrap();
        assert!(advice.argv.is_empty());
        assert_eq!(advice.missing, vec![".code-ana/inputs.json"]);
    }
    #[test]
    fn prerequisite_receipt_is_backward_compatible_and_bounded() {
        let root = tempfile::tempdir().unwrap();
        let mut value = input(root.path());
        value["receipt"] = serde_json::json!({"nonce":"00000000-0000-0000-0000-000000000000",
            "metadata_sha256":"0".repeat(64),"inputs_sha256":"1".repeat(64),
            "extension_sha256":"2".repeat(64),"maven_processes":1});
        let path = root.path().join("inputs.json");
        for round in [None, Some(2), Some(257)] {
            if let Some(round) = round {
                value["receipt"]["prerequisite_classpath_rounds"] = serde_json::json!(round);
            }
            std::fs::write(&path, serde_json::to_vec(&value).unwrap()).unwrap();
            assert_eq!(
                JavaInputs::read(root.path(), "inputs.json").is_ok(),
                round != Some(257)
            );
        }
    }

    #[test]
    fn rejects_changed_external_maven_settings() {
        let root = tempfile::tempdir().unwrap();
        let settings = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(settings.path(), b"<settings/>").unwrap();
        let mut value = input(root.path());
        value["external_controls"] =
            serde_json::json!({settings.path().to_str().unwrap():hash(b"<settings/>")});
        let path = root.path().join("inputs.json");
        std::fs::write(&path, serde_json::to_vec(&value).unwrap()).unwrap();
        assert!(JavaInputs::read(root.path(), "inputs.json").is_ok());
        std::fs::write(settings.path(), b"<settings><profiles/></settings>").unwrap();
        assert!(
            JavaInputs::read(root.path(), "inputs.json")
                .unwrap_err()
                .to_string()
                .contains("JAVA_METADATA_STALE")
        );
    }
}
