//! Semantic identity excludes preparation locations and integrity receipts.
use super::*;

pub(crate) fn configuration(config: &ProjectConfig) -> String {
    let mut languages = config.languages.clone();
    if let Some(java) = &mut languages.java {
        java.discovery.inputs_manifest = None;
    }
    if let Some(rust) = &mut languages.rust {
        rust.discovery.inputs_manifest = None;
    }
    if let Some(python) = &mut languages.python {
        python.discovery.inputs_manifest = None;
    }
    for script in [&mut languages.javascript, &mut languages.typescript]
        .into_iter()
        .flatten()
    {
        script.discovery.inputs_manifest = None;
    }
    digest(&(&languages, &config.modules))
}

pub(crate) fn context(config: &ProjectConfig, inputs: &[UnitInput]) -> String {
    let mut identities = inputs
        .iter()
        .map(|input| {
            let identity = match input {
                UnitInput::Java { metadata } => metadata.as_ref().map(|m| m.semantic_digest()),
                UnitInput::Rust { metadata } => Some(metadata.semantic_digest()),
                UnitInput::Python { metadata } => Some(digest(metadata)),
                UnitInput::TypeScript { metadata } => Some(metadata.semantic_digest()),
            };
            (input.backend(), identity)
        })
        .collect::<Vec<_>>();
    identities.sort_by_key(|(backend, _)| *backend);
    digest(&(configuration(config), identities))
}

pub(crate) fn prepared(effective: &Effective) -> String {
    let mut units = effective
        .input_units
        .iter()
        .map(|unit| {
            let p = &unit.prepared;
            (
                &p.id,
                &p.language,
                &p.entries,
                &p.roots,
                &p.modules,
                p.mode,
                &p.reason,
                unit.context.as_ref().map(|c| &c.semantic_digest),
            )
        })
        .collect::<Vec<_>>();
    units.sort_by(|a, b| a.0.cmp(b.0));
    digest(&(configuration(&effective.config), units))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inputs::{InputArtifact, InputMode, PreparedUnit, ResolvedUnit};
    #[test]
    fn prepared_identity_ignores_generation_receipts_and_runtime_policy_but_keeps_context_rules() {
        let root = tempfile::tempdir().unwrap();
        let text = "schema_version=1\nkind='project'\n[languages.java]\nenabled=true\n[[modules]]\nid='app'\nlanguage='java'\nroot='.'\nsource_roots=[{path='src',scope='MAIN'}]\n";
        let mut effective =
            crate::resolution::resolve_text(root.path(), &Request::default(), false, text, false)
                .unwrap();
        let child = effective.context.clone();
        effective.input_units.push(ResolvedUnit {
            prepared: PreparedUnit {
                id: "app".into(),
                language: "java".into(),
                entries: vec!["app".into()],
                modules: vec!["app".into()],
                roots: vec![".".into()],
                mode: InputMode::Semantic,
                reason: None,
                log: Some("generation-a/preparation.log".into()),
                config: Some(InputArtifact {
                    path: "generation-a/config.toml".into(),
                    sha256: hash(b"a"),
                    bytes: 1,
                }),
                artifacts: vec![],
            },
            context: Some(child),
        });
        let expected = prepared(&effective);
        effective.config.resources.index.worker_memory_bytes = Some(16 << 20);
        let unit = &mut effective.input_units[0];
        unit.prepared.log = Some("generation-b/preparation.log".into());
        let artifact = unit.prepared.config.as_mut().unwrap();
        artifact.path = "generation-b/config.toml".into();
        artifact.sha256 = hash(b"new receipt and location");
        artifact.bytes = 24;
        let child = unit.context.as_mut().unwrap();
        child
            .config
            .languages
            .java
            .as_mut()
            .unwrap()
            .discovery
            .inputs_manifest = Some("generation-b/java.json".into());
        child.config_digest = hash(b"different config bytes");
        child.input_hash = hash(b"different integrity receipt");
        child.semantic_digest = context(&child.config, &child.inputs);
        assert_eq!(expected, prepared(&effective));
        let child = effective.input_units[0].context.as_mut().unwrap();
        child.config.modules[0].source_roots[0].scope = "TEST".into();
        child.semantic_digest = context(&child.config, &child.inputs);
        assert_ne!(expected, prepared(&effective));
    }
}
