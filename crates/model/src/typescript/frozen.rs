//! Task-owned content references, separate from compiler semantic identity.
use super::{FileRole, Inputs};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Component, Path, PathBuf},
    sync::Arc,
};

pub const FROZEN_INPUT_SCHEMA: u32 = 1;

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
    pub inputs: Arc<Inputs>,
    pub files: Vec<FrozenFile>,
}
impl FrozenInputs {
    pub fn validate(&self) -> Result<(), String> {
        self.inputs.validate()?;
        if self.schema_version != FROZEN_INPUT_SCHEMA || self.inputs.contexts.len() != 1 {
            return Err("TYPESCRIPT_FROZEN_CONTEXT_REQUIRED".into());
        }
        let context = &self.inputs.contexts[0];
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
