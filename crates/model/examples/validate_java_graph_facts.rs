//! Acceptance-only consumer of actual SDK8 extraction output, using product validators.
use codeana_model::{AnalysisResult, Fact, FactValidator, Manifest, Owner};
use serde::Deserialize;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Input {
    manifest: Manifest,
    facts: Vec<Fact>,
    baseline: Vec<Fact>,
    replacements: Vec<Owner>,
    mode: String,
}
fn main() -> Result<(), String> {
    let path = std::env::args().nth(1).ok_or("INPUT_REQUIRED")?;
    let input: Input = serde_json::from_slice(&std::fs::read(path).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    let validator = FactValidator::new(&input.manifest);
    for fact in &input.facts {
        validator.validate(fact)?;
    }
    let result = AnalysisResult {
        mode: input.mode,
        baseline: "fixture-baseline".into(),
        manifest: input.manifest.id(),
        profile: "graph-fixture".into(),
        producer: "javac-java8-v1".into(),
        replacements: input.replacements,
        facts: input.facts,
    };
    let merged = result.validate(&input.manifest, &input.baseline)?;
    println!(
        "validated {} facts with Rust publication validators",
        merged.len()
    );
    Ok(())
}
