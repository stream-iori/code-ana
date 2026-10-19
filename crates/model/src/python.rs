//! Explicit Python input inventory. Python imports may form cycles.
use crate::{digest, safe_path};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::PathBuf,
};

pub const PRODUCER: &str = "python-core";
pub const ADAPTER_VERSION: &str = "pyrefly-1.4.0-codeana-v3";
pub const INPUT_SCHEMA: u32 = 3;
pub const BACKEND_COMMIT: &str = "feeee254e65844f23f973a0a29cae02d09931068";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Inputs {
    pub schema_version: u32,
    pub roots: Vec<InputRoot>,
    pub contexts: Vec<Context>,
    #[serde(default)]
    pub controls: Vec<Control>,
    #[serde(default)]
    pub metadata: Option<ProjectMetadata>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub module_metadata: BTreeMap<String, ProjectMetadata>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Control {
    pub path: String,
    pub sha256: String,
    pub bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProjectMetadata {
    pub name: Option<String>,
    pub requires_python: Option<String>,
    pub dependencies: Vec<String>,
    pub selected_extras: Vec<String>,
    pub dynamic: Vec<String>,
    pub build_backend: Option<String>,
    pub layout_source: String,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputRoot {
    pub id: String,
    /// Project-relative for published sources; dependencies may be absolute.
    pub path: String,
    pub publish: bool,
    pub files: Vec<InputFile>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub module: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputFile {
    pub path: String,
    pub sha256: String,
    pub bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Context {
    pub id: String,
    pub module: String,
    pub scope: String,
    pub python_version: String,
    pub platform: String,
    /// Ordered root IDs, including explicitly supplied dependency roots.
    pub roots: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub publish_roots: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub gaps: Vec<String>,
}

impl Context {
    pub fn publishes(&self, root: &InputRoot) -> bool {
        root.publish
            && self
                .publish_roots
                .as_ref()
                .is_none_or(|ids| ids.contains(&root.id))
    }
    pub fn world(&self, roots: &[InputRoot]) -> String {
        let identities = self
            .roots
            .iter()
            .map(|id| {
                roots
                    .iter()
                    .find(|r| &r.id == id)
                    .map(|r| (&r.id, &r.path, r.publish))
            })
            .collect::<Vec<_>>();
        if roots.iter().any(|r| r.module.is_some()) {
            let owners = roots
                .iter()
                .filter(|r| self.roots.contains(&r.id))
                .map(|r| (&r.id, &r.module))
                .collect::<Vec<_>>();
            format!("python-world-{}", digest(&(self, identities, owners)))
        } else {
            format!("python-world-{}", digest(&(self, identities)))
        }
    }
}

impl Inputs {
    pub fn validate(&self) -> Result<(), &'static str> {
        if !matches!(self.schema_version, 1 | 2 | INPUT_SCHEMA)
            || (self.schema_version == 1 && (!self.controls.is_empty() || self.metadata.is_some()))
            || (self.schema_version < 3
                && (!self.module_metadata.is_empty()
                    || self.roots.iter().any(|r| r.module.is_some())
                    || self
                        .contexts
                        .iter()
                        .any(|c| c.publish_roots.is_some() || !c.gaps.is_empty())))
            || self.roots.is_empty()
            || self.contexts.is_empty()
        {
            return Err("PYTHON_INPUT_SCHEMA");
        }
        let mut controls = BTreeSet::new();
        for control in &self.controls {
            if !safe_path(&control.path)
                || !controls.insert(&control.path)
                || control.bytes > 8 << 20
                || control.sha256.len() != 64
                || !control.sha256.bytes().all(|b| b.is_ascii_hexdigit())
            {
                return Err("PYTHON_CONTROL");
            }
        }
        if self.controls.len() > 1024 {
            return Err("PYTHON_INPUT_BUDGET");
        }
        if self.roots.len() > 1024 || self.contexts.len() > 128 {
            return Err("PYTHON_INPUT_BUDGET");
        }
        let valid_id = |id: &str| {
            !id.is_empty()
                && id.len() <= 128
                && id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
        };
        let mut ids = BTreeSet::new();
        let mut total_files = 0usize;
        for root in &self.roots {
            if root
                .module
                .as_ref()
                .is_some_and(|m| !valid_id(m) || !root.publish)
            {
                return Err("PYTHON_ROOT_MODULE");
            }
            if !valid_id(&root.id) || !ids.insert(root.id.as_str()) {
                return Err("PYTHON_ROOT_ID");
            }
            if root.path.is_empty() || (root.publish && root.path != "." && !safe_path(&root.path))
            {
                return Err("PYTHON_ROOT_PATH");
            }
            let mut files = BTreeSet::new();
            for file in &root.files {
                if !safe_path(&file.path)
                    || !files.insert(&file.path)
                    || !(file.path.ends_with(".py")
                        || file.path.ends_with(".pyi")
                        || file.path == "py.typed"
                        || file.path.ends_with("/py.typed"))
                {
                    return Err("PYTHON_SOURCE_PATH");
                }
                if file.sha256.len() != 64 || !file.sha256.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return Err("PYTHON_SOURCE_HASH");
                }
                if file.bytes > 8 << 20 {
                    return Err("PYTHON_SOURCE_BUDGET");
                }
            }
            total_files += root.files.len();
        }
        if total_files > 100_000 {
            return Err("PYTHON_INPUT_BUDGET");
        }
        let mut contexts = BTreeSet::new();
        for context in &self.contexts {
            if !valid_id(&context.id) || !valid_id(&context.module) || !contexts.insert(&context.id)
            {
                return Err("PYTHON_CONTEXT_ID");
            }
            // First declared target: others require separate language validation.
            if context.python_version != "3.12" || context.platform != "darwin" {
                return Err("PYTHON_TARGET_UNSUPPORTED");
            }
            if !matches!(context.scope.as_str(), "MAIN" | "TEST") {
                return Err("PYTHON_SCOPE");
            }
            if context.roots.is_empty()
                || context.roots.iter().collect::<BTreeSet<_>>().len() != context.roots.len()
                || context.roots.iter().any(|r| !ids.contains(r.as_str()))
            {
                return Err("PYTHON_CONTEXT_ROOTS");
            }
            if let Some(published) = &context.publish_roots
                && (published.is_empty()
                    || published.iter().collect::<BTreeSet<_>>().len() != published.len()
                    || published.iter().any(|id| !context.roots.contains(id)))
            {
                return Err("PYTHON_PUBLICATION_ROOTS");
            }
        }
        if self
            .roots
            .iter()
            .any(|r| !self.contexts.iter().any(|c| c.roots.contains(&r.id)))
        {
            return Err("PYTHON_UNUSED_ROOT");
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FrozenFile {
    pub root: String,
    pub relative: String,
    pub source: String,
    pub input: PathBuf,
    pub sha256: String,
    pub bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnalysisConfig {
    pub inputs: Inputs,
    pub files: Vec<FrozenFile>,
    #[serde(default)]
    pub excluded: BTreeSet<(String, String)>,
    pub scratch: PathBuf,
    pub result_bytes: u64,
    pub fact_limit: u64,
    pub scratch_bytes: u64,
}
