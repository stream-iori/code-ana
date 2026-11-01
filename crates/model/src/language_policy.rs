//! Explicit capabilities of published language facts. Unknown metadata never
//! selects a language implementation. SQL and query algorithms live with consumers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LanguagePolicy {
    Java,
    Rust,
    Python,
}
impl LanguagePolicy {
    pub const ALL: [Self; 3] = [Self::Java, Self::Rust, Self::Python];
    pub fn lookup(language: Option<&str>) -> Option<Self> {
        match language {
            Some("java") => Some(Self::Java),
            Some("rust") => Some(Self::Rust),
            Some("python") => Some(Self::Python),
            _ => None,
        }
    }
    pub const fn language(self) -> &'static str {
        match self {
            Self::Java => "java",
            Self::Rust => "rust",
            Self::Python => "python",
        }
    }
    /// Identify untyped FILES captured after an input context became unavailable.
    pub fn owns_source_path(self, path: &str) -> bool {
        let extension = std::path::Path::new(path)
            .extension()
            .and_then(|s| s.to_str());
        match self {
            Self::Java => extension == Some("java"),
            Self::Rust => extension == Some("rs"),
            Self::Python => matches!(extension, Some("py" | "pyi")),
        }
    }
    /// Only Java currently declares scoped symbol/reflection/arity completion.
    /// Other producers deliver exact IDs; those references bypass completion.
    pub const fn reference_completion(self) -> bool {
        matches!(self, Self::Java)
    }
    pub const fn static_dispatch(self) -> Option<StaticDispatchPolicy> {
        match self {
            Self::Java => None,
            Self::Rust => Some(StaticDispatchPolicy {
                algorithm: "rust-analyzer-static",
                reason_prefix: "rust",
                cha_error: "RUST_CHA_UNSUPPORTED",
            }),
            Self::Python => Some(StaticDispatchPolicy {
                algorithm: "pyrefly-static",
                reason_prefix: "python",
                cha_error: "PYTHON_CHA_UNSUPPORTED",
            }),
        }
    }
    pub const fn runtime_error(self) -> Option<&'static str> {
        match self {
            Self::Java => None,
            Self::Rust => Some("RUST_RUNTIME_IMPLEMENTATIONS_UNSUPPORTED"),
            Self::Python => Some("PYTHON_RUNTIME_IMPLEMENTATIONS_UNSUPPORTED"),
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct StaticDispatchPolicy {
    pub algorithm: &'static str,
    pub reason_prefix: &'static str,
    pub cha_error: &'static str,
}
