//! Strict project configuration and bounded, immutable source capture.
mod capture;
mod discovery;
mod execution;
pub mod inputs;
pub mod java_inputs;
mod lombok;
pub mod python_inputs;
mod resolution;
pub mod rust_inputs;
mod schema;
use anyhow::{Context, Result, ensure};
use capture::add_captured;
#[cfg(test)]
use capture::source_ownership;
pub use capture::{capture, capture_query, identity, require_java};
use codeana_model::*;
pub use execution::{analysis_limits, comment_context, query_limits};
use java_inputs::JavaInputs;
pub(crate) use resolution::resolve_text;
pub use resolution::{load_user, resolve, resolve_files};
pub use schema::*;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
};
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
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaArtifactByteBudget, "")
        );
        let temporary = tempfile::NamedTempFile::new_in(spool.path())?;
        let mut output = temporary.reopen()?;
        let size = std::io::copy(
            &mut std::fs::File::open(source)?.take(limit as u64 + 1),
            &mut output,
        )?;
        ensure!(
            size <= limit as u64,
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaArtifactByteBudget, "")
        );
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
    #[serde(default)]
    pub rust_inputs: Option<codeana_model::rust::Inputs>,
    #[serde(default)]
    pub python_inputs: Option<codeana_model::python::Inputs>,
    #[serde(default)]
    pub input_units: Vec<inputs::EffectiveUnit>,
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
pub fn bounded_read(path: &Path, limit: usize) -> Result<Vec<u8>> {
    use std::io::Read;
    ensure!(
        !std::fs::symlink_metadata(path)?.file_type().is_symlink(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::SymlinkInputUnsupported, "")
    );
    let file = std::fs::File::open(path)?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1).read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() <= limit,
        codeana_model::DomainError::new(codeana_model::ErrorCode::InputByteBudget, "")
    );
    Ok(bytes)
}
#[cfg(test)]
mod tests {
    #[test]
    fn external_exclusions_inherit_and_explicit_empty_overrides() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        let base = include_str!("../../../tests/fixtures/java/base/.code-ana/config.toml");
        let path = root.join(".code-ana/config.toml");
        let text =
            format!("{base}\n[languages.java.external]\nexclude_patterns=[\"java.util.*\"]\n");
        std::fs::write(&path, &text).unwrap();
        let inherited = resolve(&root, &Request::default()).unwrap();
        assert_eq!(
            serde_json::to_value(&inherited.config.modules[0].external).unwrap()["exclude_patterns"],
            serde_json::json!(["java.util.*"])
        );
        std::fs::write(
            &path,
            format!("{text}\n[modules.external]\nexclude_patterns=[]\n"),
        )
        .unwrap();
        let empty = resolve(&root, &Request::default()).unwrap();
        assert_eq!(
            serde_json::to_value(&empty.config.modules[0].external).unwrap()["exclude_patterns"],
            serde_json::json!([])
        );
        assert_ne!(inherited.semantic_digest, empty.semantic_digest);
    }
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
                operation: codeana_model::Operation::Index,
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
                operation: codeana_model::Operation::Snapshot,
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
        let metadata = serde_json::json!({"schema_version":4,"resolved_classpath":true,"dependency_mode":"resolved","controls":{"pom.xml":hash(b"<project/>")},
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
                operation: codeana_model::Operation::Index,
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
        let metadata = serde_json::json!({"schema_version":4,"resolved_classpath":true,"dependency_mode":"resolved",
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
        let metadata = serde_json::json!({"schema_version":4,"resolved_classpath":true,"dependency_mode":"resolved",
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
                "java_model_decode_bytes",
                vec![65536, 536870912, 2147483648],
                vec![0, 65535, 2147483649],
                "JAVA_MODEL_DECODE_BYTES_RANGE",
            ),
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

#[cfg(test)]
mod query_limit_tests {
    #[test]
    fn user_query_capacity_is_bounded_and_defaults_independently_of_workers() {
        let home = tempfile::tempdir().unwrap();
        assert_eq!(
            super::load_user(home.path())
                .unwrap()
                .limits
                .max_query_tasks,
            Some(2)
        );
        for value in [0, 1, 8, 9] {
            std::fs::write(
                home.path().join("config.toml"),
                format!("schema_version=1\nkind=\"user\"\n[limits]\nmax_query_tasks={value}\n"),
            )
            .unwrap();
            assert_eq!(
                super::load_user(home.path()).is_ok(),
                (1..=8).contains(&value)
            );
        }
    }
}

#[cfg(test)]
mod query_resource_tests {
    use super::*;
    #[test]
    fn inherited_limits_hot_reload_and_missing_project() {
        let home = tempfile::tempdir().unwrap();
        let root = tempfile::tempdir().unwrap();
        std::fs::write(home.path().join("config.toml"), "schema_version=1\nkind=\"user\"\n[resources.query]\nmax_work=4294967296\nmax_payload_bytes=12345\n").unwrap();
        assert_eq!(
            query_limits(root.path(), home.path()).unwrap().max_work,
            4294967296
        );
        std::fs::create_dir(root.path().join(".code-ana")).unwrap();
        let path = root.path().join(".code-ana/config.toml");
        std::fs::write(
            &path,
            "schema_version=1\nkind=\"project\"\n[resources.query]\nmax_work=7\n",
        )
        .unwrap();
        let limits = query_limits(root.path(), home.path()).unwrap();
        assert_eq!(
            limits,
            ResourceLimits {
                max_work: 7,
                max_payload_bytes: 12345
            }
        );
        std::fs::write(
            &path,
            "schema_version=1\nkind=\"project\"\n[resources.query]\nmax_work=8\n",
        )
        .unwrap();
        assert_eq!(query_limits(root.path(), home.path()).unwrap().max_work, 8);
        assert_eq!(
            query_limits(&root.path().join("deleted"), home.path())
                .unwrap()
                .max_work,
            4294967296
        );
        for value in [
            "0",
            "-1",
            "\"100\"",
            "9223372036854775808",
            "18446744073709551616",
        ] {
            std::fs::write(
                &path,
                format!(
                    "schema_version=1\nkind=\"project\"\n[resources.query]\nmax_work={value}\n"
                ),
            )
            .unwrap();
            assert!(query_limits(root.path(), home.path()).is_err(), "{value}");
        }
    }
}
