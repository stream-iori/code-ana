//! JS/TS semantic inputs; task-owned content references live in the frozen submodule.
use crate::{AnalysisBackend, AnalysisUnitId, SourceLanguage, WorldId, digest};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Component, Path},
};

mod frozen;
pub mod semantics;
pub use frozen::{FROZEN_INPUT_SCHEMA, FrozenFile, FrozenInputs};

pub const INPUT_SCHEMA: u32 = 1;
/// Minimum bridge semantics, independently of the stable C calling convention.
pub const ADAPTER_REVISION: u32 = 6;
pub const ABI_VERSION: u32 = 1;
pub const ANALYSIS_VERSION: u32 = 1;
pub const PRODUCER: &str = "typescript-core";

/// Semantic SDK identity is independent of its installation directory. In
/// particular, the adapter name alone cannot certify a changed Go overlay.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CompilerIdentity {
    pub adapter: String,
    pub upstream_commit: String,
    pub typescript_commit: String,
    pub compiler_version: String,
    pub go_version: String,
    pub library_sha256: String,
    pub header_sha256: String,
    pub standard_library_digest: String,
    pub analysis_proto_sha256: String,
    pub graph_proto_sha256: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Inputs {
    pub schema_version: u32,
    pub compiler: CompilerIdentity,
    pub contexts: Vec<Context>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Context {
    pub unit: AnalysisUnitId,
    pub root: String,
    pub config_path: String,
    pub publish_languages: Vec<SourceLanguage>,
    pub case_sensitive: bool,
    /// Compiler order is semantic; sorting these would hide a changed project.
    pub root_files: Vec<String>,
    pub references: Vec<String>,
    pub files: Vec<InputFile>,
    pub directories: Vec<Directory>,
    pub links: Vec<SymbolicLink>,
    pub absent: Vec<AbsentLookup>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FileRole {
    Publish,
    Auxiliary,
    Control,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputFile {
    pub path: String,
    pub sha256: String,
    pub bytes: u64,
    pub role: FileRole,
    pub language: Option<SourceLanguage>,
    pub module: Option<String>,
    pub scope: Option<String>,
    pub world: Option<WorldId>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Directory {
    pub path: String,
    pub files: Vec<String>,
    pub directories: Vec<String>,
    pub listing_complete: bool,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SymbolicLink {
    pub path: String,
    pub target: String,
    pub allowed_root: String,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LookupKind {
    File,
    Directory,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AbsentLookup {
    pub path: String,
    pub kind: LookupKind,
}

fn hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}
fn text(value: &str) -> bool {
    !value.is_empty() && value.len() <= 4096 && !value.contains('\0')
}
fn absolute(value: &str) -> bool {
    text(value)
        && value.starts_with('/')
        && !value.contains('\\')
        && (value == "/" || (!value.ends_with('/') && !value.contains("//")))
        && !value.split('/').any(|part| matches!(part, "." | ".."))
        && Path::new(value)
            .components()
            .all(|part| matches!(part, Component::RootDir | Component::Normal(_)))
}
fn child(value: &str) -> bool {
    text(value) && value != "." && value != ".." && !value.contains(['/', '\\'])
}
fn unique<'a>(values: impl Iterator<Item = &'a str>) -> bool {
    let mut seen = BTreeSet::new();
    values.into_iter().all(|value| seen.insert(value))
}

impl CompilerIdentity {
    pub fn validate(&self) -> Result<(), String> {
        if !text(&self.adapter)
            || !text(&self.compiler_version)
            || !text(&self.go_version)
            || !hex(&self.upstream_commit, 40)
            || !hex(&self.typescript_commit, 40)
            || [
                &self.library_sha256,
                &self.header_sha256,
                &self.standard_library_digest,
                &self.analysis_proto_sha256,
                &self.graph_proto_sha256,
            ]
            .iter()
            .any(|value| !hex(value, 64))
        {
            return Err("TYPESCRIPT_COMPILER_IDENTITY".into());
        }
        Ok(())
    }
}
impl Context {
    pub fn validate(&self) -> Result<(), String> {
        if !text(&self.unit)
            || self.unit.len() > 256
            || !absolute(&self.root)
            || !absolute(&self.config_path)
            || self.publish_languages.is_empty()
            || self.publish_languages.len() > 2
            || self
                .publish_languages
                .iter()
                .any(|language| !AnalysisBackend::TypeScript.supports(*language))
            || self.publish_languages.iter().collect::<BTreeSet<_>>().len()
                != self.publish_languages.len()
            || (self.root_files.is_empty() && self.references.is_empty())
            || self
                .root_files
                .iter()
                .chain(&self.references)
                .any(|name| !absolute(name))
            || !unique(self.root_files.iter().map(String::as_str))
            || !unique(self.references.iter().map(String::as_str))
        {
            return Err("TYPESCRIPT_PROJECT_CONTEXT".into());
        }
        if self.files.len() > 100_000
            || self.directories.len() > 100_000
            || self.links.len() > 100_000
            || self.absent.len() > 500_000
            || !unique(self.files.iter().map(|file| file.path.as_str()))
            || !unique(
                self.directories
                    .iter()
                    .map(|directory| directory.path.as_str()),
            )
            || !unique(self.links.iter().map(|link| link.path.as_str()))
        {
            return Err("TYPESCRIPT_OBSERVATION_IDENTITY".into());
        }
        let mut positive = BTreeMap::new();
        for file in &self.files {
            if !absolute(&file.path)
                || !hex(&file.sha256, 64)
                || file.bytes > 256 << 20
                || file
                    .language
                    .is_some_and(|language| !AnalysisBackend::TypeScript.supports(language))
            {
                return Err("TYPESCRIPT_INPUT_FILE".into());
            }
            match file.role {
                FileRole::Publish => {
                    if self.root_files.is_empty()
                        || file
                            .language
                            .is_none_or(|language| !self.publish_languages.contains(&language))
                        || file.module.as_deref().is_none_or(|module| !text(module))
                        || !matches!(file.scope.as_deref(), Some("MAIN" | "TEST"))
                        || file.world.as_ref().is_none_or(|world| !text(world))
                    {
                        return Err("TYPESCRIPT_PUBLICATION_OWNER".into());
                    }
                }
                FileRole::Auxiliary | FileRole::Control => {
                    if (file.role == FileRole::Auxiliary && file.language.is_none())
                        || file.module.is_some()
                        || file.scope.is_some()
                        || file.world.is_some()
                    {
                        return Err("TYPESCRIPT_AUXILIARY_OWNER".into());
                    }
                }
            }
            positive.insert(file.path.as_str(), LookupKind::File);
        }
        let mut listings = BTreeMap::new();
        for directory in &self.directories {
            if !absolute(&directory.path)
                || positive
                    .insert(&directory.path, LookupKind::Directory)
                    .is_some()
                || (!directory.listing_complete
                    && (!directory.files.is_empty() || !directory.directories.is_empty()))
                || directory
                    .files
                    .iter()
                    .chain(&directory.directories)
                    .any(|name| !child(name))
                || !unique(
                    directory
                        .files
                        .iter()
                        .chain(&directory.directories)
                        .map(String::as_str),
                )
            {
                return Err("TYPESCRIPT_DIRECTORY_OBSERVATION".into());
            }
            if directory.listing_complete {
                for (names, kind) in [
                    (&directory.files, LookupKind::File),
                    (&directory.directories, LookupKind::Directory),
                ] {
                    for name in names {
                        listings.insert(Path::new(&directory.path).join(name), kind);
                    }
                }
            }
        }
        for link in &self.links {
            if !absolute(&link.path)
                || !text(&link.target)
                || !absolute(&link.allowed_root)
                || positive.contains_key(link.path.as_str())
            {
                return Err("TYPESCRIPT_LINK_OBSERVATION".into());
            }
        }
        let mut absent = BTreeSet::new();
        for entry in &self.absent {
            if !absolute(&entry.path)
                || !absent.insert((&entry.path, entry.kind))
                || positive.get(entry.path.as_str()) == Some(&entry.kind)
                || listings.get(Path::new(&entry.path)) == Some(&entry.kind)
            {
                return Err("TYPESCRIPT_MISSING_OBSERVATION".into());
            }
        }
        // Kernel-specific case folding and full link replay belong to Go;
        // Rust capture independently validates actual filesystem observations.
        Ok(())
    }
}
impl Inputs {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema_version != INPUT_SCHEMA
            || self.contexts.is_empty()
            || self.contexts.len() > 256
            || !unique(self.contexts.iter().map(|context| context.unit.as_str()))
        {
            return Err("TYPESCRIPT_INPUT_SCHEMA".into());
        }
        self.compiler.validate()?;
        let mut content = BTreeMap::new();
        for context in &self.contexts {
            context.validate()?;
            for file in &context.files {
                if let Some(previous) = content.insert(&file.path, (&file.sha256, file.bytes))
                    && previous != (&file.sha256, file.bytes)
                {
                    return Err("TYPESCRIPT_INPUT_CONTENT_CONFLICT".into());
                }
            }
        }
        Ok(())
    }
    /// Enumeration order is incidental; compiler root/reference order is not.
    /// There is intentionally no SDK location or preparation generation field.
    pub fn semantic_digest(&self) -> String {
        let mut normalized = self.clone();
        normalized.contexts.sort_by(|a, b| a.unit.cmp(&b.unit));
        for context in &mut normalized.contexts {
            context.publish_languages.sort();
            context.files.sort_by(|a, b| a.path.cmp(&b.path));
            context.directories.sort_by(|a, b| a.path.cmp(&b.path));
            for directory in &mut context.directories {
                directory.files.sort();
                directory.directories.sort();
            }
            context.links.sort_by(|a, b| a.path.cmp(&b.path));
            context
                .absent
                .sort_by(|a, b| (&a.path, a.kind).cmp(&(&b.path, b.kind)));
        }
        digest(&(INPUT_SCHEMA, ABI_VERSION, ANALYSIS_VERSION, normalized))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Inputs {
        Inputs {
            schema_version: INPUT_SCHEMA,
            compiler: CompilerIdentity {
                adapter: "typescript-fixture-graph6".into(),
                upstream_commit: "a".repeat(40),
                typescript_commit: "b".repeat(40),
                compiler_version: "7.1.0-dev".into(),
                go_version: "go1.26.2".into(),
                library_sha256: "a".repeat(64),
                header_sha256: "b".repeat(64),
                standard_library_digest: "c".repeat(64),
                analysis_proto_sha256: "d".repeat(64),
                graph_proto_sha256: "e".repeat(64),
            },
            contexts: vec![Context {
                unit: "typescript/project/main".into(),
                root: "/virtual/project".into(),
                config_path: "/virtual/project/tsconfig.json".into(),
                publish_languages: vec![SourceLanguage::JavaScript, SourceLanguage::TypeScript],
                case_sensitive: true,
                root_files: vec![
                    "/virtual/project/a.ts".into(),
                    "/virtual/project/b.js".into(),
                ],
                references: vec![],
                files: vec![
                    InputFile {
                        path: "/virtual/project/a.ts".into(),
                        sha256: "1".repeat(64),
                        bytes: 100,
                        role: FileRole::Publish,
                        language: Some(SourceLanguage::TypeScript),
                        module: Some("project".into()),
                        scope: Some("MAIN".into()),
                        world: Some("typescript/main".into()),
                    },
                    InputFile {
                        path: "/virtual/project/b.js".into(),
                        sha256: "2".repeat(64),
                        bytes: 50,
                        role: FileRole::Publish,
                        language: Some(SourceLanguage::JavaScript),
                        module: Some("project".into()),
                        scope: Some("MAIN".into()),
                        world: Some("typescript/main".into()),
                    },
                    InputFile {
                        path: "/virtual/project/tsconfig.json".into(),
                        sha256: "3".repeat(64),
                        bytes: 50,
                        role: FileRole::Control,
                        language: None,
                        module: None,
                        scope: None,
                        world: None,
                    },
                ],
                directories: vec![Directory {
                    path: "/virtual/project".into(),
                    files: vec!["tsconfig.json".into(), "b.js".into(), "a.ts".into()],
                    directories: vec![],
                    listing_complete: true,
                }],
                links: vec![],
                absent: vec![
                    AbsentLookup {
                        path: "/virtual/project/package.json".into(),
                        kind: LookupKind::File,
                    },
                    AbsentLookup {
                        path: "/virtual/project/node_modules".into(),
                        kind: LookupKind::Directory,
                    },
                ],
            }],
        }
    }
    #[test]
    fn dual_source_tags_share_one_typed_context_without_live_io() {
        let mut inputs = fixture();
        inputs.validate().unwrap();
        assert_eq!(inputs.contexts.len(), 1);
        inputs.contexts[0].publish_languages = vec![SourceLanguage::TypeScript];
        assert_eq!(
            inputs.validate().unwrap_err(),
            "TYPESCRIPT_PUBLICATION_OWNER"
        );
        let js = &mut inputs.contexts[0].files[1];
        js.role = FileRole::Auxiliary;
        js.module = None;
        js.scope = None;
        js.world = None;
        inputs.validate().unwrap();
    }
    #[test]
    fn enumeration_and_context_order_do_not_change_profile() {
        let original = fixture();
        let mut reordered = original.clone();
        reordered.contexts[0].publish_languages.reverse();
        reordered.contexts[0].files.reverse();
        reordered.contexts[0].directories[0].files.reverse();
        reordered.contexts[0].absent.reverse();
        assert_eq!(original.semantic_digest(), reordered.semantic_digest());
        let mut other = original.contexts[0].clone();
        other.unit = "typescript/project/test".into();
        for file in &mut other.files {
            if file.role == FileRole::Publish {
                file.scope = Some("TEST".into());
                file.world = Some("typescript/test".into());
            }
        }
        reordered.contexts.push(other);
        reordered.validate().unwrap();
        let same = reordered.semantic_digest();
        reordered.contexts.reverse();
        assert_eq!(same, reordered.semantic_digest());
    }
    #[test]
    fn root_order_and_every_resolution_identity_invalidate_profile() {
        let original = fixture();
        let expected = original.semantic_digest();
        let edits: Vec<fn(&mut Inputs)> = vec![
            |i| i.contexts[0].root_files.reverse(),
            |i| i.contexts[0].case_sensitive = false,
            |i| i.contexts[0].absent.pop().map(|_| ()).unwrap(),
            |i| i.contexts[0].files[0].sha256 = "4".repeat(64),
            |i| i.compiler.library_sha256 = "5".repeat(64),
            |i| i.compiler.standard_library_digest = "6".repeat(64),
            |i| {
                i.contexts[0]
                    .references
                    .push("/virtual/ref/tsconfig.json".into())
            },
            |i| i.contexts[0].directories[0].files.push("new.ts".into()),
        ];
        for edit in edits {
            let mut changed = original.clone();
            edit(&mut changed);
            changed.validate().unwrap();
            assert_ne!(expected, changed.semantic_digest());
        }
    }
    #[test]
    fn same_bytes_can_have_independent_worlds_but_conflicting_bytes_cannot() {
        let mut inputs = fixture();
        let mut second = inputs.contexts[0].clone();
        second.unit = "typescript/project/other".into();
        second.files[0].world = Some("other-world".into());
        inputs.contexts.push(second);
        inputs.validate().unwrap();
        inputs.contexts[1].files[0].sha256 = "9".repeat(64);
        assert_eq!(
            inputs.validate().unwrap_err(),
            "TYPESCRIPT_INPUT_CONTENT_CONFLICT"
        );
    }
    #[test]
    fn missing_case_or_enumeration_contract_and_generation_fields_are_rejected() {
        let value = serde_json::to_value(fixture()).unwrap();
        for field in ["case_sensitive", "root_files", "references"] {
            let mut changed = value.clone();
            changed["contexts"][0]
                .as_object_mut()
                .unwrap()
                .remove(field);
            assert!(
                serde_json::from_value::<Inputs>(changed).is_err(),
                "{field}"
            );
        }
        let mut changed = value.clone();
        changed["contexts"][0]["directories"][0]
            .as_object_mut()
            .unwrap()
            .remove("listing_complete");
        assert!(serde_json::from_value::<Inputs>(changed).is_err());
        let mut changed = value;
        changed["generation"] = serde_json::json!("temporary-location");
        assert!(serde_json::from_value::<Inputs>(changed).is_err());
    }
    #[test]
    fn rootless_solution_requires_explicit_references() {
        let mut inputs = fixture();
        inputs.contexts[0].root_files.clear();
        assert_eq!(inputs.validate().unwrap_err(), "TYPESCRIPT_PROJECT_CONTEXT");
        inputs.contexts[0]
            .references
            .push("/virtual/leaf/tsconfig.json".into());
        for file in &mut inputs.contexts[0].files {
            if file.role == FileRole::Publish {
                file.role = FileRole::Auxiliary;
                file.module = None;
                file.scope = None;
                file.world = None;
            }
        }
        inputs.validate().unwrap();
    }
    #[test]
    fn malformed_observations_and_ownership_cannot_certify_inputs() {
        let edits: Vec<fn(&mut Inputs)> = vec![
            |i| i.schema_version = 0,
            |i| i.compiler.library_sha256 = "BAD".into(),
            |i| i.contexts.push(i.contexts[0].clone()),
            |i| i.contexts[0].files[0].path = "/virtual/../escape.ts".into(),
            |i| i.contexts[0].files[0].language = Some(SourceLanguage::Java),
            |i| i.contexts[0].files[0].world = None,
            |i| i.contexts[0].directories[0].listing_complete = false,
            |i| {
                i.contexts[0].absent.push(AbsentLookup {
                    path: "/virtual/project/a.ts".into(),
                    kind: LookupKind::File,
                })
            },
            |i| {
                i.contexts[0].links.push(SymbolicLink {
                    path: "/virtual/project/a.ts".into(),
                    target: "other.ts".into(),
                    allowed_root: "/virtual/project".into(),
                })
            },
        ];
        for edit in edits {
            let mut changed = fixture();
            edit(&mut changed);
            assert!(changed.validate().is_err());
        }
    }
}
