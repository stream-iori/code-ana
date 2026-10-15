//! Frozen Rust analysis inputs, independent of rust-analyzer and the filesystem.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const INPUT_SCHEMA: u32 = 2;
pub const DEFAULT_WORKER_MEMORY: u64 = 256 * 1024 * 1024;
pub const PRODUCER: &str = "rust-core";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Inputs {
    pub schema_version: u32,
    pub toolchain: String,
    pub crates: Vec<CrateInput>,
    pub controls: BTreeMap<String, String>,
    #[serde(default)]
    pub generated: BTreeMap<String, String>,
    pub policy: String,
    /// Exact auxiliary source allowlists; stale files beside them are never scanned.
    pub dependency_sources: BTreeMap<String, Vec<String>>,
    pub omitted_dependencies: BTreeMap<String, BTreeMap<String, String>>,
    #[serde(default)]
    pub selection: SelectionSummary,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SelectionSummary {
    pub candidate_crates: u64,
    pub retained_crates: u64,
    pub filtered_source_instances: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CrateInput {
    pub id: String,
    pub name: String,
    pub module: String,
    pub world: String,
    pub scope: String,
    pub root: String,
    pub directory: String,
    pub edition: String,
    pub target: String,
    pub cfg: Vec<String>,
    pub dependencies: BTreeMap<String, String>,
    #[serde(default)]
    pub environment: BTreeMap<String, String>,
    pub publish: bool,
    #[serde(default)]
    pub proc_macro: bool,
    #[serde(default)]
    pub gaps: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FrozenFile {
    pub path: String,
    pub hash: String,
    pub input: std::path::PathBuf,
    pub crates: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnalysisConfig {
    #[serde(default)]
    pub baseline: Option<Vec<SourceState>>,
    #[serde(default)]
    pub excluded: std::collections::BTreeSet<(String, String)>,
    pub inputs: Inputs,
    pub files: Vec<FrozenFile>,
    pub mode: RustAnalysisMode,
    pub scratch: std::path::PathBuf,
    pub result_bytes: u64,
    pub fact_limit: u64,
}

impl Inputs {
    pub fn semantic_digest(&self) -> String {
        crate::digest(&(
            &self.schema_version,
            &self.toolchain,
            &self.policy,
            &self.crates,
            &self.controls,
            &self.generated,
            &self.dependency_sources,
            &self.omitted_dependencies,
        ))
    }
    pub fn validate(&self) -> Result<(), String> {
        use std::collections::BTreeSet;
        if self.schema_version != INPUT_SCHEMA || self.toolchain.trim().is_empty() {
            return Err("RUST_INPUT_SCHEMA".into());
        }
        if self.policy.len() != 64 || !self.policy.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("RUST_INPUT_POLICY".into());
        }
        if self.crates.is_empty() || self.crates.len() > 10_000 {
            return Err("RUST_CRATE_BUDGET".into());
        }
        let mut ids = BTreeSet::new();
        let mut worlds = BTreeSet::new();
        for input in &self.crates {
            if input.id.is_empty()
                || !ids.insert(input.id.as_str())
                || input.world.is_empty()
                || !worlds.insert(input.world.as_str())
            {
                return Err("RUST_DUPLICATE_CRATE_WORLD".into());
            }
            if !crate::safe_path(&input.root)
                || !(input.directory == "." || crate::safe_path(&input.directory))
                || input.module.is_empty()
            {
                return Err("RUST_INPUT_PATH".into());
            }
            if !matches!(input.edition.as_str(), "2015" | "2018" | "2021" | "2024")
                || !matches!(input.scope.as_str(), "MAIN" | "TEST" | "GENERATED")
                || input.target.is_empty()
            {
                return Err("RUST_BUILD_CONTEXT".into());
            }
        }
        for (id, paths) in &self.dependency_sources {
            if !self.crates.iter().any(|c| &c.id == id && !c.publish)
                || paths.iter().any(|p| !crate::safe_path(p))
                || paths.iter().collect::<BTreeSet<_>>().len() != paths.len()
            {
                return Err("RUST_DEPENDENCY_SOURCES".into());
            }
        }
        for input in self.crates.iter().filter(|c| !c.publish) {
            if !self
                .dependency_sources
                .get(&input.id)
                .is_some_and(|p| p.contains(&input.root))
            {
                return Err("RUST_DEPENDENCY_SOURCES".into());
            }
        }
        if self
            .omitted_dependencies
            .keys()
            .any(|id| !ids.contains(id.as_str()))
        {
            return Err("RUST_DEPENDENCY_INVALID".into());
        }
        for input in &self.crates {
            for (name, dependency) in &input.dependencies {
                if name.is_empty() || !ids.contains(dependency.as_str()) || dependency == &input.id
                {
                    return Err("RUST_DEPENDENCY_INVALID".into());
                }
            }
        }
        let permitted = self
            .crates
            .iter()
            .filter(|c| c.publish)
            .flat_map(|c| {
                std::iter::once(c.id.as_str()).chain(c.dependencies.values().map(String::as_str))
            })
            .collect::<BTreeSet<_>>();
        if permitted.len() != self.crates.len() {
            return Err("RUST_DEPENDENCY_DEPTH".into());
        }
        // Kahn's algorithm avoids stack exhaustion on untrusted input graphs.
        let mut remaining = self
            .crates
            .iter()
            .map(|c| {
                (
                    c.id.as_str(),
                    c.dependencies
                        .values()
                        .map(String::as_str)
                        .collect::<BTreeSet<_>>(),
                )
            })
            .collect::<BTreeMap<_, _>>();
        while !remaining.is_empty() {
            let ready = remaining
                .iter()
                .filter(|(_, deps)| deps.is_empty())
                .map(|(id, _)| *id)
                .collect::<BTreeSet<_>>();
            if ready.is_empty() {
                return Err("RUST_DEPENDENCY_CYCLE".into());
            }
            remaining.retain(|id, _| !ready.contains(id));
            for dependencies in remaining.values_mut() {
                dependencies.retain(|id| !ready.contains(id));
            }
        }
        for (path, hash) in self.controls.iter().chain(&self.generated) {
            if !crate::safe_path(path)
                || hash.len() != 64
                || !hash.bytes().all(|v| v.is_ascii_hexdigit())
            {
                return Err("RUST_INPUT_DIGEST".into());
            }
        }
        Ok(())
    }
}

/// Persisted dependencies are independent of Salsa's in-process memoization.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct SourceState {
    pub file: String,
    pub world: String,
    pub source_hash: String,
    pub interface_hash: String,
    pub names: Vec<String>,
    pub dependencies: Vec<Dependency>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum DependencyKind {
    File,
    Symbol,
    Lookup,
    Macro,
    ImplSet,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(deny_unknown_fields)]
pub struct Dependency {
    pub kind: DependencyKind,
    pub world: String,
    pub key: String,
}

impl DependencyKind {
    pub const fn as_str(&self) -> &'static str {
        match self {
            Self::File => "file",
            Self::Symbol => "symbol",
            Self::Lookup => "lookup",
            Self::Macro => "macro",
            Self::ImplSet => "impl_set",
        }
    }
}

// Rust producer choices are closed; shared producer payloads remain extensible.
macro_rules! rust_choice {
    ($name:ident, $($variant:ident = $text:literal),+ $(,)?) => {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
        pub enum $name { $(#[serde(rename = $text)] $variant,)+ }
        impl $name {pub const fn as_str(self)->&'static str {match self {$(Self::$variant=>$text,)+}}}
        impl std::fmt::Display for $name {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {f.write_str(self.as_str())}}
        impl std::str::FromStr for $name {type Err=crate::DomainError;fn from_str(value:&str)->Result<Self,Self::Err> {match value {$($text=>Ok(Self::$variant),)+ _=>Err(crate::DomainError::new(crate::ErrorCode::from("RUST_ANALYSIS_MODE"),format!("{}: {value}",stringify!($name))))}}}
        impl PartialEq<&str> for $name {fn eq(&self,other:&&str)->bool {self.as_str()==*other}}
    };
}
rust_choice!(
    DeclarationKind,
    Function = "function",
    Struct = "struct",
    Enum = "enum",
    Trait = "trait",
    Union = "union",
    TypeAlias = "type_alias",
    Constant = "constant",
    Static = "static",
    Module = "module",
    Field = "field",
    Variant = "variant",
    Macro = "macro",
    Impl = "impl"
);
rust_choice!(
    RustVisibility,
    Public = "public",
    Crate = "crate",
    Private = "private",
    Restricted = "restricted"
);
rust_choice!(
    RustRelation,
    Calls = "CALLS",
    References = "REFERENCES",
    Implements = "IMPLEMENTS",
    Contains = "CONTAINS"
);
rust_choice!(RustResolution, Exact = "exact", Unresolved = "unresolved");
rust_choice!(RustAnalysisMode, Semantic = "semantic", Syntax = "syntax");
