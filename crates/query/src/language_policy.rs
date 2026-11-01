//! Capability checks occur on explicit producer metadata, never names or paths.
use super::*;
pub(super) fn require(fact: &Fact, operation: &str) -> Result<LanguagePolicy> {
    let language = fact.attributes.language.as_ref().map(String::as_str);
    LanguagePolicy::lookup(language).ok_or_else(|| {
        DomainError::new(
            ErrorCode::LanguageQueryCapabilityUnsupported,
            format!(
                "operation={operation} language={}",
                language.unwrap_or("<missing>")
            ),
        )
        .into()
    })
}
