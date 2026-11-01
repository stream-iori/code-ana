//! Java type rules; never selected by missing or unknown language metadata.
use super::*;

pub(super) fn instantiability(fact: &Fact) -> (&'static str, &'static str) {
    let modifier = |name| {
        fact.attributes
            .modifiers
            .as_ref()
            .is_some_and(|items| items.iter().any(|item| item == name))
    };
    match fact
        .attributes
        .element_kind
        .as_ref()
        .map(String::as_str)
        .unwrap_or("")
    {
        "INTERFACE" => ("no", "java.interface"),
        "ANNOTATION" | "ANNOTATION_TYPE" => ("no", "java.annotation"),
        "ENUM" => ("yes", "java.enum"),
        "RECORD" => ("yes", "java.record"),
        "ANONYMOUS_CLASS" => ("yes", "java.anonymous_class"),
        "CLASS" if modifier("abstract") => ("no", "java.abstract_class"),
        "CLASS" if modifier("final") => ("yes", "java.final_class"),
        "CLASS" => ("yes", "java.concrete_class"),
        _ => ("unknown", "java.kind_unknown"),
    }
}

pub(super) fn require(fact: &Fact) -> Result<()> {
    // External symbols supply binding evidence, not declaration/type rules.
    if fact.kind != FactKind::Declaration {
        return Ok(());
    }
    ensure!(
        language_policy::require(fact, "java-dispatch")? == LanguagePolicy::Java,
        DomainError::new(
            ErrorCode::LanguageQueryCapabilityUnsupported,
            "operation=java-dispatch"
        )
    );
    Ok(())
}
