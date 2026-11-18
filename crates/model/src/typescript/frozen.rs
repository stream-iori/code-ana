//! Task-owned content references, separate from compiler semantic identity.
use super::{FileRole, Inputs};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Component, Path, PathBuf},
    sync::Arc,
};

pub const FROZEN_INPUT_SCHEMA: u32 = 2;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FrozenFile {
    pub source: String,
    pub input: PathBuf,
    /// Publication follows the actual frozen view, including scan filters.
    pub publish: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FrozenInputs {
    pub schema_version: u32,
    pub publication_root: PathBuf,
    pub inputs: Arc<Inputs>,
    pub files: Vec<FrozenFile>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnalysisConfig {
    pub snapshot: FrozenInputs,
    pub scratch: PathBuf,
    pub result_bytes: u64,
    pub fact_limit: u64,
    pub scratch_bytes: u64,
}
impl FrozenInputs {
    pub fn validate(&self) -> Result<(), String> {
        self.inputs.validate()?;
        if self.schema_version != FROZEN_INPUT_SCHEMA || self.inputs.contexts.len() != 1 {
            return Err("TYPESCRIPT_FROZEN_CONTEXT_REQUIRED".into());
        }
        let context = &self.inputs.contexts[0];
        if !self.publication_root.is_absolute()
            || self
                .publication_root
                .components()
                .any(|c| matches!(c, Component::ParentDir | Component::CurDir))
            || !Path::new(&context.root).starts_with(&self.publication_root)
        {
            return Err("TYPESCRIPT_PUBLICATION_ROOT".into());
        }
        if self.files.len() != context.files.len() {
            return Err("TYPESCRIPT_FROZEN_FILE_COVERAGE".into());
        }
        let metadata = context
            .files
            .iter()
            .map(|file| (file.path.as_str(), file))
            .collect::<BTreeMap<_, _>>();
        let mut seen = BTreeSet::new();
        for file in &self.files {
            let original = metadata
                .get(file.source.as_str())
                .ok_or("TYPESCRIPT_FROZEN_FILE_UNKNOWN")?;
            if !seen.insert(&file.source)
                || (file.publish && original.role != FileRole::Publish)
                || !file.input.is_absolute()
                || file.input == Path::new(&file.source)
                || file
                    .input
                    .components()
                    .any(|part| matches!(part, Component::ParentDir | Component::CurDir))
                || file
                    .input
                    .to_str()
                    .is_none_or(|path| path.len() > 16 * 1024 || path.contains('\0'))
                || file.input.file_name().and_then(|name| name.to_str())
                    != Some(original.sha256.as_str())
            {
                return Err("TYPESCRIPT_FROZEN_FILE_REFERENCE".into());
            }
        }
        Ok(())
    }
}
