//! Source tags and execution backends are different identities: JS and TS share an engine.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SourceLanguage {
    Java,
    Rust,
    Python,
    JavaScript,
    TypeScript,
}

impl SourceLanguage {
    pub fn lookup(value: &str) -> Option<Self> {
        match value {
            "java" => Some(Self::Java),
            "rust" => Some(Self::Rust),
            "python" => Some(Self::Python),
            "javascript" => Some(Self::JavaScript),
            "typescript" => Some(Self::TypeScript),
            _ => None,
        }
    }
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Java => "java",
            Self::Rust => "rust",
            Self::Python => "python",
            Self::JavaScript => "javascript",
            Self::TypeScript => "typescript",
        }
    }
    pub const fn backend(self) -> AnalysisBackend {
        match self {
            Self::Java => AnalysisBackend::Java,
            Self::Rust => AnalysisBackend::Rust,
            Self::Python => AnalysisBackend::Python,
            Self::JavaScript | Self::TypeScript => AnalysisBackend::TypeScript,
        }
    }
}

/// Declaration order is the deterministic budget partition order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AnalysisBackend {
    Java,
    Rust,
    Python,
    TypeScript,
}

impl AnalysisBackend {
    pub const ALL: [Self; 4] = [Self::Java, Self::Rust, Self::Python, Self::TypeScript];
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Java => "java",
            Self::Rust => "rust",
            Self::Python => "python",
            Self::TypeScript => "typescript",
        }
    }
    pub const fn supports(self, language: SourceLanguage) -> bool {
        matches!(
            (self, language),
            (Self::Java, SourceLanguage::Java)
                | (Self::Rust, SourceLanguage::Rust)
                | (Self::Python, SourceLanguage::Python)
                | (
                    Self::TypeScript,
                    SourceLanguage::JavaScript | SourceLanguage::TypeScript
                )
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn source_tags_select_only_registered_backends() {
        for tag in ["java", "rust", "python", "javascript", "typescript"] {
            let source = SourceLanguage::lookup(tag).unwrap();
            assert_eq!(source.as_str(), tag);
            assert!(source.backend().supports(source));
            assert_eq!(
                serde_json::to_string(&source).unwrap(),
                format!("\"{tag}\"")
            );
        }
        assert_eq!(
            SourceLanguage::JavaScript.backend(),
            SourceLanguage::TypeScript.backend()
        );
        assert!(!AnalysisBackend::Java.supports(SourceLanguage::TypeScript));
        assert!(SourceLanguage::lookup("producer-controlled-name").is_none());
    }
}
