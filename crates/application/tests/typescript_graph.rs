//! Actual ABI output through public archive verification, store and query.
use anyhow::{Context, Result, ensure};
use codeana_contracts::graph_delivery::{Descriptor, OutputLimits};
use codeana_graph_io::records::Origin;
use codeana_model::*;
use codeana_storage::Store;
use serde_json::{Value, json};
use std::{collections::BTreeMap, path::PathBuf, process::Command, sync::Arc};

#[test]
#[ignore = "requires a built ordinary SDK; mandatory in test-typescript-graph"]
fn actual_typescript_archive_publishes_and_queries_real_declarations_and_calls() -> Result<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()?;
    let sdk = PathBuf::from(std::env::var("CODEANA_TYPESCRIPT_SDK")?);
    let evidence = PathBuf::from(std::env::var("CODEANA_TYPESCRIPT_GRAPH_EVIDENCE")?);
    ensure!(
        evidence.is_absolute() && !evidence.exists(),
        "NEW_GRAPH_EVIDENCE_REQUIRED"
    );
    std::fs::create_dir_all(&evidence)?;
    let fixture = evidence.join("fixture");
    std::fs::create_dir(&fixture)?;
    for name in ["a.ts", "b.ts"] {
        let original = std::fs::read_to_string(
            root.join("lang-typescript-adaptor/probe/fixtures")
                .join(name),
        )?;
        let content = if name == "a.ts" {
            format!(
                "/** 类型脚本文档 中文😀 */\r\n{}",
                original
                    .replace("return value", "/* 调用内部说明 */ return value")
                    .replace("value + 1", "value + 'bad'")
            )
        } else {
            original
        };
        std::fs::write(fixture.join(name), content)?;
    }
    let output = evidence.join("worker");
    let execution = Command::new(root.join("target/debug/codeana-typescript-worker"))
        .args([
            "--sdk",
            sdk.to_str().context("SDK_UTF8")?,
            "--component",
            fixture.to_str().context("FIXTURE_UTF8")?,
            "--evidence",
            output.to_str().context("OUTPUT_UTF8")?,
        ])
        .output()?;
    std::fs::write(evidence.join("worker.stdout"), &execution.stdout)?;
    std::fs::write(evidence.join("worker.stderr"), &execution.stderr)?;
    ensure!(
        execution.status.success(),
        "ACTUAL_WORKER_FAILED: {}",
        String::from_utf8_lossy(&execution.stderr)
    );
    let receipt: Value = serde_json::from_slice(&std::fs::read(output.join("receipt.json"))?)?;
    let graph = &receipt["outcome"]["Analysis"]["graph"];
    let descriptor = Descriptor {
        format: "GRA1".into(),
        path: output
            .join("engine")
            .join(graph["path"].as_str().context("GRAPH_PATH")?),
        count: graph["count"].as_u64().context("GRAPH_COUNT")?,
        bytes: graph["bytes"].as_u64().context("GRAPH_BYTES")?,
        sha256: graph["sha256"].as_str().context("GRAPH_SHA")?.into(),
    };
    let mut rows = Vec::new();
    let proof = codeana_graph_io::reader::read_descriptor(
        &descriptor,
        OutputLimits {
            bytes: 1 << 20,
            facts: 1000,
        },
        &output.join("engine"),
        false,
        None,
        || Ok(()),
        |row| {
            rows.push(row);
            Ok(())
        },
        || Ok(()),
    )?;
    let store = Arc::new(Store::open(&evidence.join("home"))?);
    let mut sources = Vec::new();
    let mut blobs = BTreeMap::new();
    for name in ["a.ts", "b.ts"] {
        let bytes = std::fs::read(fixture.join(name))?;
        let digest = hash(&bytes);
        sources.push(SourceUnit {
            path: name.into(),
            hash: digest.clone(),
            size: bytes.len() as u64,
            module: "fixture".into(),
            world: "main".into(),
            scope: "MAIN".into(),
            language: "typescript".into(),
            auxiliary: false,
        });
        blobs.insert(digest, bytes);
    }
    store.put_blobs(&blobs)?;
    let unit: AnalysisUnitId = "typescript-fixture".into();
    let profile = hash("typescript-component-v1");
    let manifest = Manifest {
        files: sources.clone(),
    };
    let revision = Revision {
        unit_ownership: Some(UnitOwnershipLedger {
            version: UNIT_OWNERSHIP_VERSION,
            units: vec![UnitOwnership {
                unit: unit.clone(),
                backend: AnalysisBackend::TypeScript,
                availability: UnitAvailability::Ready,
                source_languages: vec![SourceLanguage::TypeScript],
                modules: vec!["fixture".into()],
                worlds: vec!["main".into()],
                scopes: vec!["MAIN".into()],
                producer: typescript::PRODUCER.into(),
                semantic_profile: profile.clone(),
                input_digest: hash("captured-fixture"),
                inputs: sources.clone(),
                sources,
            }],
        }),
        id: String::new(),
        target: "t".into(),
        project: "p".into(),
        checkout: "c".into(),
        manifest,
        profile: profile.clone(),
        producer: typescript::PRODUCER.into(),
        created: 0,
    };
    let header = AnalysisHeader {
        mode: AnalysisMode::Full,
        baseline: String::new(),
        manifest: revision.manifest.id(),
        profile,
        producer: typescript::PRODUCER.into(),
        replacements: vec![],
    };
    let origin = Origin::Unit(Arc::new(unit));
    let before_count = rows.len();
    let source_store = store.clone();
    let staging = GraphStagingContext {
        hosts: BTreeMap::from([(
            AnalysisBackend::TypeScript,
            AnalysisContext::new(AnalysisLimits::default(), None)?,
        )]),
        source: Arc::new(move |hash, _| {
            source_store
                .blob(hash)
                .map_err(|error| DomainError::new(ErrorCode::SourceHashMismatch, error.to_string()))
        }),
    };
    let candidate = store.stage_owned_graph_records_with_context(
        revision,
        &header,
        rows.into_iter().map(|row| Ok(origin.record(row))),
        "",
        || Ok(()),
        |_, _| Ok(()),
        Some(&staging),
    )?;
    let published = store.publish_staged(candidate, "typescript-public-graph")?;
    let reader = store.read(&published.id)?;
    let declarations = reader.select(&FactFilter {
        kinds: vec![FactKind::Declaration],
        ..Default::default()
    })?;
    let greet = declarations
        .iter()
        .filter(|f| {
            f.name == "greet"
                && f.attributes.element_kind.as_ref().map(String::as_str) == Some("function")
        })
        .collect::<Vec<_>>();
    ensure!(
        greet.len() == 1,
        "PUBLIC_FUNCTION_DECLARATION: {:?}",
        declarations
    );
    let target = &greet[0].id;
    let diagnostics = reader.select(&FactFilter {
        kinds: vec![FactKind::Diagnostic],
        ..Default::default()
    })?;
    ensure!(
        diagnostics
            .iter()
            .any(|d| d.name == "TS2322" && d.source == "a.ts"),
        "PUBLIC_COMPILER_DIAGNOSTIC: {diagnostics:?}"
    );
    let call_sites = reader.select(&FactFilter {
        kinds: vec![FactKind::CallSite],
        ..Default::default()
    })?;
    ensure!(call_sites.len() == 2, "PUBLIC_CALL_SITES: {:?}", call_sites);
    let targets = reader.select(&FactFilter {
        kinds: vec![FactKind::CallTarget],
        ..Default::default()
    })?;
    ensure!(
        targets.len() == 2 && targets.iter().all(|f| f.target == target.as_str()),
        "PUBLIC_CALL_TARGETS: {:?}",
        targets
    );
    for site in &call_sites {
        let dispatch = codeana_query::run(
            &reader,
            &Request {
                operation: Operation::Dispatch,
                selector: site.id.to_string(),
                ..Default::default()
            },
            &|s| store.blob(&s.hash),
        )?;
        ensure!(dispatch.records.len() == 1, "PUBLIC_STATIC_DISPATCH");
        let error = codeana_query::run(
            &reader,
            &Request {
                operation: Operation::Dispatch,
                selector: site.id.to_string(),
                algorithm: DispatchAlgorithm::Cha,
                ..Default::default()
            },
            &|s| store.blob(&s.hash),
        )
        .unwrap_err();
        ensure!(
            error.to_string().contains("TYPESCRIPT_CHA_UNSUPPORTED"),
            "STATIC_CHA_BOUNDARY: {error:#}"
        );
    }
    let source = codeana_query::run(
        &reader,
        &Request {
            operation: Operation::Source,
            selector: target.to_string(),
            ..Default::default()
        },
        &|s| store.blob(&s.hash),
    )?;
    ensure!(!source.records.is_empty(), "PUBLIC_CAPTURED_SOURCE");
    let resolve = codeana_query::run(
        &reader,
        &Request {
            operation: Operation::Resolve,
            selector: "greet".into(),
            ..Default::default()
        },
        &|s| store.blob(&s.hash),
    )?;
    ensure!(!resolve.records.is_empty(), "PUBLIC_RESOLVE");
    let comments = codeana_query::run(
        &reader,
        &Request {
            operation: Operation::SearchComments,
            selector: "类型脚本文档".into(),
            ..Default::default()
        },
        &|s| store.blob(&s.hash),
    )?;
    ensure!(
        comments.records.len() == 1,
        "PUBLIC_TYPESCRIPT_COMMENT_SEARCH"
    );
    let Record::Comment(hit) = &comments.records[0] else {
        anyhow::bail!("PUBLIC_COMMENT_RECORD")
    };
    ensure!(
        hit.comment.kind == CommentKind::Doc
            && hit.comment.language == "typescript"
            && hit
                .links
                .iter()
                .any(|link| link.relation == CommentRelation::Documents
                    && link.declaration.as_ref() == Some(target)),
        "PUBLIC_JSDOC_LINK"
    );
    let inner = codeana_query::run(
        &reader,
        &Request {
            operation: Operation::SearchComments,
            selector: "调用内部说明".into(),
            ..Default::default()
        },
        &|s| store.blob(&s.hash),
    )?;
    ensure!(inner.records.len() == 1, "PUBLIC_ENCLOSING_COMMENT");
    let Record::Comment(hit) = &inner.records[0] else {
        anyhow::bail!("PUBLIC_INNER_COMMENT_RECORD")
    };
    ensure!(
        hit.links
            .iter()
            .any(|link| link.relation == CommentRelation::EnclosingCallable
                && link.declaration.as_ref() == Some(target)),
        "PUBLIC_ENCLOSING_CALLABLE_LINK"
    );
    std::fs::write(
        evidence.join("proof.json"),
        serde_json::to_vec_pretty(
            &json!({"status":"passed","actual_abi":true,"raw_archive_verified":true,"verified_rows":proof.count(),"imported_rows":before_count,"published_revision":published.id,"declarations":declarations.len(),"call_sites":call_sites.len(),"call_targets":targets.len(),"function_id":target,"public_static_dispatch":true,"cha_rejected":true,"captured_source":true,"public_resolve":true,"public_comment_search":true,"jsdoc_link":true,"enclosing_callable_link":true,"public_compiler_diagnostic":true,"T5_complete":false,"product_ipc":false,"formal_release":false}),
        )?,
    )?;
    Ok(())
}
