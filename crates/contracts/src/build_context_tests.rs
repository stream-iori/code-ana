use super::*;
use codeana_model::{BackendContext, JavaToolchainContext};
use prost::Message;
fn context(details: BackendContext) -> BuildContext {
    let mut c = BuildContext::new("entry", details);
    c.producer = "test".into();
    c.modules = vec!["app".into()];
    c.seal();
    c
}
#[test]
fn all_backends_roundtrip_and_reject_other_engines_settings() {
    for details in [
        BackendContext::Java {
            source_version: "17".into(),
            classpath: vec!["dep.jar".into()],
            toolchain: JavaToolchainContext {
                digest: "jdk".into(),
                home: "/jdk".into(),
                version: "17".into(),
                aliases: vec!["java17".into()],
                compiler_bridge: "sha".into(),
                adapter: "javac".into(),
            },
        },
        BackendContext::Rust {
            toolchain: "rustc".into(),
            adapter: "ra".into(),
        },
        BackendContext::Python {
            python_version: "3.12".into(),
            platform: "darwin".into(),
            adapter: "pyrefly".into(),
            backend_commit: "commit".into(),
        },
        BackendContext::TypeScript {
            compiler_version: "7".into(),
            upstream_commit: "commit".into(),
            adapter: "ts".into(),
        },
    ] {
        let mut model = context(details);
        if model.backend() == AnalysisBackend::TypeScript {
            model.source_languages = vec![SourceLanguage::JavaScript, SourceLanguage::TypeScript];
            model.seal();
        }
        let wire = w::BuildContext::from(&model);
        let decoded = BuildContext::try_from(&wire).unwrap();
        assert_eq!(
            serde_json::to_value(&decoded).unwrap(),
            serde_json::to_value(&model).unwrap()
        );
        let mut wrong = wire.clone();
        wrong.source_languages = vec![
            if model.backend() == AnalysisBackend::Java {
                "rust"
            } else {
                "java"
            }
            .into(),
        ];
        assert!(BuildContext::try_from(&wrong).is_err());
        let mut missing = wire;
        missing.details = None;
        assert!(BuildContext::try_from(&missing).is_err());
    }
}
#[test]
fn admission_checks_instance_worlds_and_digest_but_keeps_body_edits_stable() {
    let mut c = context(BackendContext::Rust {
        toolchain: "rustc".into(),
        adapter: "ra".into(),
    });
    let mut manifest = Manifest::default();
    manifest.files.push(codeana_model::SourceUnit {
        path: "src/a.rs".into(),
        world: "world".into(),
        language: "rust".into(),
        hash: "before".into(),
        size: 6,
        module: "app".into(),
        scope: "MAIN".into(),
        auxiliary: false,
    });
    c.worlds = BuildContext::worlds_for(&manifest, c.backend());
    c.seal();
    let check = |c: &BuildContext, m: &Manifest| {
        accept(
            c,
            "entry",
            AnalysisBackend::Rust,
            &[SourceLanguage::Rust],
            "test",
            m,
        )
    };
    check(&c, &manifest).unwrap();
    assert!(
        accept(
            &c,
            "other",
            AnalysisBackend::Rust,
            &[SourceLanguage::Rust],
            "test",
            &manifest
        )
        .is_err()
    );
    let mut wrong_producer = c.clone();
    wrong_producer.producer = "other".into();
    wrong_producer.seal();
    assert!(check(&wrong_producer, &manifest).is_err());
    let digest = c.semantic_digest.clone();
    manifest.files[0].hash = "after".into();
    check(&c, &manifest).unwrap();
    assert_eq!(c.expected_digest(), digest);
    manifest.files[0].world = "other-world".into();
    assert!(check(&c, &manifest).is_err());
    c.semantic_digest = "forged".into();
    assert!(validate(&c).is_err());
    c.unit = "".into();
    c.seal();
    assert!(validate(&c).is_err());
}
fn frame(body: &[u8]) -> Vec<u8> {
    let mut out = vec![10, 1, b'a', 34];
    let mut n = body.len() as u64;
    while n >= 128 {
        out.push((n as u8) | 128);
        n >>= 7;
    }
    out.push(n as u8);
    out.extend_from_slice(body);
    out
}
#[test]
fn raw_duplicate_variants_identity_and_legacy_fields_rejected_before_decode() {
    let model = context(BackendContext::Rust {
        toolchain: "rustc".into(),
        adapter: "ra".into(),
    });
    let body = w::BuildContext::from(&model).encode_to_vec();
    crate::validate_envelope_wire(&frame(&body)).unwrap();
    for extra in [
        vec![0x5a, 0],
        vec![0x52, 0],
        vec![10, 1, b'x'],
        vec![0x1a, 1, b'8'],
        vec![0x22, 0],
    ] {
        let mut bytes = body.clone();
        bytes.extend(extra);
        assert!(crate::validate_envelope_wire(&frame(&bytes)).is_err());
    }
    let mut missing = w::BuildContext::from(&model);
    missing.details = None;
    assert!(crate::validate_envelope_wire(&frame(&missing.encode_to_vec())).is_err());
    let mut missing = w::BuildContext::from(&model);
    missing.unit.clear();
    assert!(crate::validate_envelope_wire(&frame(&missing.encode_to_vec())).is_err());
}
#[test]
fn discovery_rejects_empty_duplicate_and_wrong_source_languages() {
    let mut d = w::Discover {
        unit: "entry".into(),
        source_languages: vec!["typescript".into(), "javascript".into()],
        ..Default::default()
    };
    assert_eq!(discovery(&d, AnalysisBackend::TypeScript).unwrap().len(), 2);
    for languages in [
        vec![],
        vec!["rust".into()],
        vec!["typescript".into(), "typescript".into()],
    ] {
        d.source_languages = languages;
        assert!(discovery(&d, AnalysisBackend::TypeScript).is_err());
    }
    d.source_languages = vec!["typescript".into()];
    d.unit = "../entry".into();
    assert!(discovery(&d, AnalysisBackend::TypeScript).is_err());
}
