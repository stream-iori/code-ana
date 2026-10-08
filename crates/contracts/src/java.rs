//! Shared sealed input descriptions; contexts retain ordered indices and publication roles.
use crate::worker as w;
use anyhow::{Context, Result, ensure};
use std::collections::{BTreeMap, BTreeSet};

fn input(
    mut value: w::JavaSource,
    table: &mut Vec<w::JavaSource>,
    known: &mut BTreeMap<String, u32>,
) -> Result<u32> {
    value.publish = false;
    let path = value.unit.as_ref().context("JAVA_INPUT_UNIT")?.path.clone();
    if let Some(&slot) = known.get(&path) {
        ensure!(table[slot as usize] == value, "JAVA_INPUT_TABLE_IDENTITY");
        return Ok(slot);
    }
    ensure!(table.len() < 10000, "JAVA_INPUT_TABLE_BUDGET");
    let slot = table.len() as u32;
    known.insert(path, slot);
    table.push(value);
    Ok(slot)
}

pub fn compact(config: &mut w::JavaConfig) -> Result<()> {
    ensure!(
        !config.compact_inputs
            && config.input_table.is_empty()
            && config.classpath_table.is_empty(),
        "JAVA_INPUT_TABLE_STATE"
    );
    let mut inputs = BTreeMap::new();
    let mut classpath = BTreeMap::new();
    for context in &mut config.contexts {
        ensure!(
            context.source_indices.is_empty()
                && context.publish_indices.is_empty()
                && context.resource_indices.is_empty()
                && context.classpath_indices.is_empty(),
            "JAVA_INPUT_TABLE_STATE"
        );
        for source in std::mem::take(&mut context.sources) {
            let publish = source.publish;
            let slot = input(source, &mut config.input_table, &mut inputs)?;
            context.source_indices.push(slot);
            if publish {
                context.publish_indices.push(slot);
            }
        }
        for resource in std::mem::take(&mut context.resources) {
            context
                .resource_indices
                .push(input(resource, &mut config.input_table, &mut inputs)?);
        }
        for path in std::mem::take(&mut context.classpath) {
            let slot = if let Some(&slot) = classpath.get(&path) {
                slot
            } else {
                ensure!(
                    config.classpath_table.len() < 10000,
                    "JAVA_CLASSPATH_TABLE_BUDGET"
                );
                let slot = config.classpath_table.len() as u32;
                classpath.insert(path.clone(), slot);
                config.classpath_table.push(path);
                slot
            };
            context.classpath_indices.push(slot);
        }
    }
    config.compact_inputs = true;
    validate(config)
}

#[derive(Clone, Copy, Debug)]
pub struct OutputLimits {
    pub bytes: u64,
    pub facts: u64,
}
impl Default for OutputLimits {
    fn default() -> Self {
        Self {
            bytes: codeana_model::MAX_RESULT as u64,
            facts: codeana_model::MAX_FACTS as u64,
        }
    }
}
impl OutputLimits {
    pub fn from_config(config: &w::JavaConfig) -> Result<Self> {
        let defaults = Self::default();
        let limits = Self {
            bytes: if config.result_bytes == 0 {
                defaults.bytes
            } else {
                config.result_bytes
            },
            facts: if config.fact_limit == 0 {
                defaults.facts
            } else {
                u64::from(config.fact_limit)
            },
        };
        ensure!(
            (64 * 1024..=codeana_model::MAX_JAVA_RESULT_BYTES).contains(&limits.bytes),
            "JAVA_RESULT_BYTES_RANGE"
        );
        ensure!(
            (1..=5000000).contains(&limits.facts),
            "JAVA_FACT_LIMIT_RANGE"
        );
        Ok(limits)
    }
}
pub fn validate(config: &w::JavaConfig) -> Result<()> {
    OutputLimits::from_config(config)?;
    for context in &config.contexts {
        ensure!(
            !context.prerequisite
                || (context.scope == "MAIN"
                    && context.publish_indices.is_empty()
                    && context.sources.iter().all(|s| !s.publish)),
            "JAVA_AUXILIARY_PUBLICATION"
        );
        ensure!(
            context
                .sources
                .iter()
                .all(|s| !s.publish || s.unit.as_ref().is_some_and(|u| !u.auxiliary)),
            "JAVA_AUXILIARY_PUBLICATION"
        );
    }
    ensure!(
        config
            .contexts
            .iter()
            .all(|context| context.model_record_limit <= 5000000),
        "JAVA_MODEL_RECORD_LIMIT_RANGE"
    );
    ensure!(
        config
            .contexts
            .iter()
            .all(|context| context.model_archive_bytes == 0
                || (64 * 1024..=1024 * 1024 * 1024).contains(&context.model_archive_bytes)),
        "JAVA_MODEL_ARCHIVE_BYTES_RANGE"
    );
    ensure!(
        config.native_heap_bytes == 0
            || ((8 * 1024 * 1024..=2 * 1024 * 1024 * 1024).contains(&config.native_heap_bytes)
                && config.native_heap_bytes.is_multiple_of(1024 * 1024)),
        "JAVA_NATIVE_HEAP_RANGE"
    );
    ensure!(
        config.compiler_timeout_seconds <= 3600,
        "COMPILER_TIMEOUT_BUDGET"
    );
    if !config.compact_inputs {
        ensure!(
            config.input_table.is_empty()
                && config.classpath_table.is_empty()
                && config.contexts.iter().all(|c| c.source_indices.is_empty()
                    && c.publish_indices.is_empty()
                    && c.classpath_indices.is_empty()
                    && c.resource_indices.is_empty()),
            "JAVA_INPUT_TABLE_STATE"
        );
        return Ok(());
    }
    ensure!(
        config.contexts.len() <= 256
            && config.input_table.len() <= 10000
            && config.classpath_table.len() <= 10000,
        "JAVA_INPUT_TABLE_BUDGET"
    );
    let mut paths = BTreeSet::new();
    for value in &config.input_table {
        let unit = value.unit.as_ref().context("JAVA_INPUT_UNIT")?;
        ensure!(
            !value.publish
                && !value.input.is_empty()
                && !unit.path.is_empty()
                && paths.insert(&unit.path),
            "JAVA_INPUT_TABLE_IDENTITY"
        );
    }
    ensure!(
        config.classpath_table.iter().all(|p| !p.is_empty()),
        "JAVA_CLASSPATH_TABLE_IDENTITY"
    );
    let mut references = 0usize;
    for context in &config.contexts {
        ensure!(
            context.sources.is_empty()
                && context.resources.is_empty()
                && context.classpath.is_empty(),
            "JAVA_INPUT_TABLE_STATE"
        );
        for slots in [
            &context.source_indices,
            &context.publish_indices,
            &context.classpath_indices,
            &context.resource_indices,
        ] {
            references += slots.len();
            ensure!(
                slots.len() <= 10000 && references <= 1000000,
                "JAVA_INDEX_BUDGET"
            );
        }
        let mut sources = BTreeSet::new();
        for &slot in &context.source_indices {
            let unit = config
                .input_table
                .get(slot as usize)
                .context("JAVA_INPUT_INDEX")?
                .unit
                .as_ref()
                .context("JAVA_INPUT_UNIT")?;
            ensure!(
                unit.language == "java" && sources.insert(slot),
                "JAVA_SOURCE_INDEX_IDENTITY"
            );
        }
        let mut published = BTreeSet::new();
        for &slot in &context.publish_indices {
            ensure!(
                sources.contains(&slot) && published.insert(slot),
                "JAVA_PUBLICATION_INDEX"
            );
            let unit = config.input_table[slot as usize]
                .unit
                .as_ref()
                .context("JAVA_INPUT_UNIT")?;
            ensure!(
                !unit.auxiliary
                    && !context.prerequisite
                    && unit.world == context.world
                    && unit.scope == context.scope,
                "JAVA_PUBLICATION_WORLD"
            );
        }
        let mut resources = BTreeSet::new();
        for &slot in &context.resource_indices {
            let unit = config
                .input_table
                .get(slot as usize)
                .context("JAVA_INPUT_INDEX")?
                .unit
                .as_ref()
                .context("JAVA_INPUT_UNIT")?;
            ensure!(
                unit.language != "java" && resources.insert(slot),
                "JAVA_RESOURCE_INDEX_IDENTITY"
            );
        }
        ensure!(
            context
                .classpath_indices
                .iter()
                .all(|&slot| (slot as usize) < config.classpath_table.len()),
            "JAVA_CLASSPATH_INDEX"
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    fn source() -> w::JavaSource {
        w::JavaSource {
            unit: Some(w::SourceUnit {
                path: "src/A.java".into(),
                hash: "a".repeat(64),
                world: "main".into(),
                scope: "MAIN".into(),
                language: "java".into(),
                ..Default::default()
            }),
            input: "/sealed/src/A.java".into(),
            publish: true,
        }
    }
    fn fixture() -> w::JavaConfig {
        w::JavaConfig {
            contexts: vec![
                w::JavaContext {
                    world: "main".into(),
                    scope: "MAIN".into(),
                    sources: vec![source()],
                    classpath: vec!["b.jar".into(), "a.jar".into()],
                    ..Default::default()
                },
                w::JavaContext {
                    world: "test".into(),
                    scope: "TEST".into(),
                    sources: vec![w::JavaSource {
                        publish: false,
                        ..source()
                    }],
                    classpath: vec!["a.jar".into(), "b.jar".into()],
                    ..Default::default()
                },
            ],
            ..Default::default()
        }
    }
    #[test]
    fn auxiliary_sources_roundtrip_and_cannot_be_published() {
        let mut source = source();
        source.unit.as_mut().unwrap().auxiliary = true;
        let model: codeana_model::SourceUnit =
            crate::convert(source.unit.as_ref().unwrap()).unwrap();
        assert!(model.auxiliary);
        let restored: w::SourceUnit = crate::convert(&model).unwrap();
        assert_eq!(restored, source.unit.clone().unwrap());
        let mut config = fixture();
        config.contexts[0].sources[0] = source;
        assert!(compact(&mut config).is_err());
        let mut config = fixture();
        config.contexts[0].prerequisite = true;
        assert!(compact(&mut config).is_err());
        let mut config = fixture();
        config.contexts[0].sources[0].publish = false;
        config.contexts[0].prerequisite = true;
        compact(&mut config).unwrap();
        let mut model = model;
        model.auxiliary = false;
        let old_json = serde_json::to_value(&model).unwrap();
        assert!(old_json.get("auxiliary").is_none());
        let restored: w::SourceUnit = crate::convert(&model).unwrap();
        assert!(!restored.auxiliary);
    }
    #[test]
    fn tables_preserve_order_identity_and_publication_roles() {
        let mut config = fixture();
        compact(&mut config).unwrap();
        assert_eq!(config.input_table.len(), 1);
        assert_eq!(config.contexts[0].publish_indices, vec![0]);
        assert!(config.contexts[1].publish_indices.is_empty());
        assert_eq!(config.contexts[0].classpath_indices, vec![0, 1]);
        assert_eq!(config.contexts[1].classpath_indices, vec![1, 0]);
        let mut invalid = config.clone();
        invalid.contexts[0].source_indices.push(99);
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.contexts[0].source_indices.push(0);
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.contexts[1].publish_indices.push(0);
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.contexts[0].resources.push(source());
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.contexts[0].model_record_limit = 5000001;
        assert!(validate(&invalid).is_err());
        for limit in [1, 65535, 1024 * 1024 * 1024 + 1] {
            invalid = config.clone();
            invalid.contexts[0].model_archive_bytes = limit;
            assert!(validate(&invalid).is_err());
        }
        invalid = config.clone();
        invalid.compiler_timeout_seconds = 3601;
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.result_bytes = codeana_model::MAX_JAVA_RESULT_BYTES + 1;
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.fact_limit = 5000001;
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.native_heap_bytes = 8388609;
        assert!(validate(&invalid).is_err());
        invalid = config.clone();
        invalid.native_heap_bytes = 2 * 1024 * 1024 * 1024;
        assert!(validate(&invalid).is_ok());
        invalid.native_heap_bytes += 1024 * 1024;
        assert!(validate(&invalid).is_err());
        let mut conflicting = fixture();
        conflicting.contexts[1].sources[0].input = "/unsealed/changed.java".into();
        assert!(compact(&mut conflicting).is_err());
    }
    #[test]
    fn reactor_references_fit_frame_without_raising_frame_limit() {
        let sources: Vec<_> = (0..3200)
            .map(|i| {
                let mut value = source();
                value.unit.as_mut().unwrap().path = format!("src/Class{i}.java");
                value.input = format!("/sealed/physical/src/Class{i}.java");
                value.publish = false;
                value
            })
            .collect();
        let mut config = w::JavaConfig {
            contexts: (0..66)
                .map(|_| w::JavaContext {
                    sources: sources.clone(),
                    classpath: (0..933)
                        .map(|i| format!("/sealed/inputs/{i:064}.jar"))
                        .collect(),
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        assert!(config.encoded_len() > codeana_model::MAX_FRAME);
        compact(&mut config).unwrap();
        let frame = crate::envelope(
            "a",
            w::envelope::Body::Discover(w::Discover {
                java: Some(config),
                manifest: None,
            }),
        );
        assert!(frame.encoded_len() < codeana_model::MAX_FRAME);
        crate::validate_envelope_wire(&frame.encode_to_vec()).unwrap();
    }

    #[test]
    fn resource_labels_survive_compaction_without_becoming_compiler_sources() {
        for language in ["", "xml"] {
            let mut config = fixture();
            let mut resource = source();
            let unit = resource.unit.as_mut().unwrap();
            unit.path = "resources/beans.xml".into();
            unit.language = language.into();
            resource.input = "/sealed/resources/beans.xml".into();
            resource.publish = false;
            config.contexts[0].resources.push(resource);
            compact(&mut config).unwrap();
            let slot = config.contexts[0].resource_indices[0];
            assert_eq!(
                config.input_table[slot as usize]
                    .unit
                    .as_ref()
                    .unwrap()
                    .language,
                language
            );
            assert!(!config.contexts[0].source_indices.contains(&slot));
            config.input_table[slot as usize]
                .unit
                .as_mut()
                .unwrap()
                .language = "java".into();
            assert!(validate(&config).is_err());
        }
    }
}
