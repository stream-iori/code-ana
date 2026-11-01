//! Compile frozen language inputs into host-owned execution and budget participants.
use super::*;
use std::collections::BTreeSet;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum ExecutionPolicy {
    Java,
    RustIncremental,
    Conservative,
    Prepared,
}

#[derive(Debug)]
pub(super) struct AnalysisUnit {
    pub language: AnalysisLanguage,
    pub execute: bool,
    // Reference the frozen configuration instead of cloning IDs before host admission.
    pub prepared_index: Option<usize>,
}

pub(super) struct IndexPlan {
    pub policy: ExecutionPolicy,
    pub participants: Vec<AnalysisLanguage>,
    pub units: Vec<AnalysisUnit>,
}

// Compile-time registration only. No target-controlled plugins or parser fallback.
pub(super) fn language(name: &str) -> Option<AnalysisLanguage> {
    match name {
        "java" => Some(AnalysisLanguage::Java),
        "rust" => Some(AnalysisLanguage::Rust),
        "python" => Some(AnalysisLanguage::Python),
        _ => None,
    }
}

impl IndexPlan {
    pub fn executes(&self, language: AnalysisLanguage) -> bool {
        self.units
            .iter()
            .any(|unit| unit.language == language && unit.execute)
    }
    pub fn build(capture: &Capture, budget: Option<&AnalysisContext>) -> Result<Self> {
        let effective = &capture.effective;
        // Preserve empty/deleted modules in the static resource split.
        let mut participants: BTreeSet<_> = capture
            .manifest
            .files
            .iter()
            .map(|file| file.language.as_str())
            .chain(
                effective
                    .config
                    .modules
                    .iter()
                    .map(|module| module.language.as_str()),
            )
            .filter_map(language)
            .collect();
        for (present, language) in [
            (effective.java_inputs.is_some(), AnalysisLanguage::Java),
            (effective.rust_inputs.is_some(), AnalysisLanguage::Rust),
            (effective.python_inputs.is_some(), AnalysisLanguage::Python),
        ] {
            if present {
                participants.insert(language);
            }
        }
        let policy = if !effective.input_units.is_empty() {
            ExecutionPolicy::Prepared
        } else if effective.python_inputs.is_some() {
            ExecutionPolicy::Conservative
        } else if effective.rust_inputs.is_some() {
            ExecutionPolicy::RustIncremental
        } else {
            ExecutionPolicy::Java
        };
        let count = if policy == ExecutionPolicy::Prepared {
            effective
                .input_units
                .iter()
                .filter(|unit| unit.effective.is_some())
                .count()
        } else {
            participants.len()
        };
        if let Some(budget) = budget {
            budget
                .charge(1, 0, (count * std::mem::size_of::<AnalysisUnit>()) as u64)
                .map_err(DomainError::analysis)?;
        }
        let mut units = Vec::with_capacity(count);
        if policy == ExecutionPolicy::Prepared {
            for (index, unit) in effective.input_units.iter().enumerate() {
                if unit.effective.is_some() {
                    units.push(AnalysisUnit {
                        language: language(&unit.prepared.language).context("INPUT_LANGUAGE")?,
                        execute: true,
                        prepared_index: Some(index),
                    });
                }
            }
        } else {
            for language in &participants {
                units.push(AnalysisUnit {
                    language: *language,
                    execute: match language {
                        AnalysisLanguage::Java => {
                            policy == ExecutionPolicy::Java
                                || effective
                                    .config
                                    .modules
                                    .iter()
                                    .any(|m| m.language == "java")
                        }
                        AnalysisLanguage::Rust => effective.rust_inputs.is_some(),
                        AnalysisLanguage::Python => effective.python_inputs.is_some(),
                    },
                    prepared_index: None,
                });
            }
        }
        Ok(Self {
            policy,
            participants: participants.into_iter().collect(),
            units,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_config::inputs::{EffectiveUnit, InputMode, PreparedUnit};

    fn capture() -> Capture {
        Capture {
            root: PathBuf::new(),
            project: String::new(),
            checkout: String::new(),
            target: String::new(),
            manifest: Manifest::default(),
            blobs: BTreeMap::new(),
            effective: codeana_config::Effective {
                config: Default::default(),
                origins: BTreeMap::new(),
                config_digest: String::new(),
                input_hash: String::new(),
                semantic_digest: String::new(),
                capture_digest: String::new(),
                java_inputs: None,
                rust_inputs: None,
                python_inputs: None,
                input_units: vec![],
            },
        }
    }

    fn unit(
        id: &str,
        language: &str,
        effective: Option<codeana_config::Effective>,
    ) -> EffectiveUnit {
        EffectiveUnit {
            prepared: PreparedUnit {
                id: id.into(),
                language: language.into(),
                entries: vec![],
                modules: vec![],
                roots: vec![],
                mode: if effective.is_some() {
                    InputMode::Semantic
                } else {
                    InputMode::FilesOnly
                },
                reason: None,
                log: None,
                config: None,
                artifacts: vec![],
            },
            effective: effective.map(Box::new),
        }
    }

    #[test]
    fn empty_and_deleted_modules_keep_their_resource_reservation() {
        let mut c = capture();
        for name in ["python", "java", "rust", "python"] {
            c.effective.config.modules.push(codeana_config::Module {
                language: name.into(),
                ..Default::default()
            });
        }
        let plan = IndexPlan::build(&c, None).unwrap();
        assert_eq!(plan.participants.len(), 3);
        assert_eq!(plan.units.len(), 3);
        assert!(plan.executes(AnalysisLanguage::Java));
        assert!(!plan.executes(AnalysisLanguage::Rust));
        assert!(!plan.executes(AnalysisLanguage::Python));
        assert!(plan.units.iter().all(|unit| unit.prepared_index.is_none()));
        assert_eq!(plan.policy, ExecutionPolicy::Java);
    }

    #[test]
    fn prepared_units_preserve_instance_order_and_omit_unavailable_contexts() {
        let mut c = capture();
        c.effective.input_units = vec![
            unit("python-b", "python", Some(c.effective.clone())),
            unit("missing", "java", None),
            unit("rust-a", "rust", Some(c.effective.clone())),
            unit("python-a", "python", Some(c.effective.clone())),
        ];
        let plan = IndexPlan::build(&c, None).unwrap();
        assert_eq!(plan.policy, ExecutionPolicy::Prepared);
        assert_eq!(
            plan.units
                .iter()
                .map(|unit| c.effective.input_units[unit.prepared_index.unwrap()]
                    .prepared
                    .id
                    .as_str())
                .collect::<Vec<_>>(),
            ["python-b", "rust-a", "python-a"]
        );
        assert!(plan.units.iter().all(|unit| unit.execute));
        assert_eq!(plan.units[0].language, AnalysisLanguage::Python);
        assert_eq!(plan.units[1].language, AnalysisLanguage::Rust);
    }

    #[test]
    fn unknown_ready_languages_are_rejected_but_files_only_units_need_no_worker() {
        let mut c = capture();
        c.effective.input_units = vec![unit("external", "unknown", Some(c.effective.clone()))];
        assert!(
            IndexPlan::build(&c, None)
                .err()
                .unwrap()
                .to_string()
                .contains("INPUT_LANGUAGE")
        );
        c.effective.input_units[0].effective = None;
        assert!(IndexPlan::build(&c, None).unwrap().units.is_empty());
    }
    #[test]
    fn plan_allocation_uses_the_existing_sticky_task_account() {
        let mut c = capture();
        c.effective.config.modules.push(codeana_config::Module {
            language: "java".into(),
            ..Default::default()
        });
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let error = IndexPlan::build(&c, Some(&budget)).err().unwrap();
        assert!(error.to_string().contains("ANALYSIS_RESOURCE_LIMIT"));
        assert!(budget.checkpoint().is_err());
    }
}
