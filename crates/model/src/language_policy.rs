//! Explicit capabilities of published language facts. Unknown metadata never
//! selects a language implementation. SQL and query algorithms live with consumers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LanguagePolicy {
    Java,
    Rust,
    Python,
    JavaScript,
    TypeScript,
}
impl LanguagePolicy {
    pub const ALL: [Self; 5] = [
        Self::Java,
        Self::Rust,
        Self::Python,
        Self::JavaScript,
        Self::TypeScript,
    ];
    pub fn lookup(language: Option<&str>) -> Option<Self> {
        match language {
            Some("java") => Some(Self::Java),
            Some("rust") => Some(Self::Rust),
            Some("python") => Some(Self::Python),
            Some("javascript") => Some(Self::JavaScript),
            Some("typescript") => Some(Self::TypeScript),
            _ => None,
        }
    }
    pub const fn language(self) -> &'static str {
        match self {
            Self::Java => "java",
            Self::Rust => "rust",
            Self::Python => "python",
            Self::JavaScript => "javascript",
            Self::TypeScript => "typescript",
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
            Self::JavaScript => matches!(extension, Some("js" | "jsx" | "mjs" | "cjs")),
            Self::TypeScript => matches!(extension, Some("ts" | "tsx" | "mts" | "cts")),
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
            Self::JavaScript => Some(StaticDispatchPolicy {
                algorithm: "typescript-go-static",
                reason_prefix: "javascript",
                cha_error: "JAVASCRIPT_CHA_UNSUPPORTED",
            }),
            Self::TypeScript => Some(StaticDispatchPolicy {
                algorithm: "typescript-go-static",
                reason_prefix: "typescript",
                cha_error: "TYPESCRIPT_CHA_UNSUPPORTED",
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
            Self::JavaScript => Some("JAVASCRIPT_RUNTIME_IMPLEMENTATIONS_UNSUPPORTED"),
            Self::TypeScript => Some("TYPESCRIPT_RUNTIME_IMPLEMENTATIONS_UNSUPPORTED"),
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct StaticDispatchPolicy {
    pub algorithm: &'static str,
    pub reason_prefix: &'static str,
    pub cha_error: &'static str,
}
