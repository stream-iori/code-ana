//! Actual captured-byte handoff and public semantic rows; product IPC is separate.
use anyhow::{Context, Result, ensure};
use codeana_contracts::{analysis_budget, protobuf::typescript::v1 as p};
use codeana_model::{AnalysisBackend, AnalysisContext, AnalysisGrant, AnalysisLimits, Request};
use codeana_typescript_worker::{frozen, native::Native};
use codeana_worker_runtime::ffi::{BudgetHandoff, Notification, Owner};
use prost::Message;
use std::{os::fd::AsRawFd, path::PathBuf, time::Duration};

#[tokio::test]
#[ignore = "requires actual SDK inventory; mandatory in test-typescript-handoff"]
async fn actual_capture_handoff_survives_deleted_live_inputs() -> Result<()> {
    let native_path = PathBuf::from(std::env::var("CODEANA_TYPESCRIPT_PREPARED_FIXTURE")?);
    let evidence = native_path.parent().context("EVIDENCE_PARENT")?;
    let metadata: codeana_model::typescript::Inputs =
        serde_json::from_slice(&std::fs::read(&native_path)?)?;
    metadata.validate().map_err(anyhow::Error::msg)?;
    ensure!(metadata.contexts.len() == 1, "ONE_FIXTURE_CONTEXT_REQUIRED");
    let root = PathBuf::from(&metadata.contexts[0].root).canonicalize()?;
    ensure!(
        root == evidence.join("live") && !root.join(".code-ana").exists(),
        "FRESH_OWNED_FIXTURE_REQUIRED"
    );
    std::fs::create_dir(root.join(".code-ana"))?;
    std::fs::copy(&native_path, root.join(".code-ana/native.json"))?;
    std::fs::write(
        root.join(".code-ana/config.toml"),
        "schema_version=1\nkind='project'\n[languages.typescript]\nenabled=true\n[languages.typescript.discovery]\ninputs_manifest='.code-ana/native.json'\n",
    )?;
    let capture = codeana_config::capture(&root, &Request::default())?;
    let unit = metadata.contexts[0].unit.clone();
    let selected = capture.unit_capture(&unit)?;
    let snapshot = codeana_config::typescript_inputs::frozen_inputs(&selected)?;
    ensure!(
        snapshot.files.len() == 3 && snapshot.files.iter().filter(|file| file.publish).count() == 2,
        "FIXTURE_FILE_COUNTS"
    );
    for file in &metadata.contexts[0].files {
        std::fs::remove_file(&file.path)?;
    }
    let sdk = PathBuf::from(std::env::var("CODEANA_TYPESCRIPT_SDK")?);
    let mut owner = Owner::spawn(move || Native::load(&sdk)).await?;
    let (runtime, compiler) = owner
        .call(|native| Ok((native.runtime.clone(), native.compiler.clone())))
        .await?;
    let limits = AnalysisLimits::default();
    let budget = AnalysisContext::new(limits, None)?;
    let project = frozen::load(&snapshot, &unit, &compiler, &budget)?;
    let scratch = evidence.join("handoff-engine");
    std::fs::create_dir(&scratch)?;
    let grant = AnalysisGrant {
        id: format!("handoff/{unit}"),
        language: AnalysisBackend::TypeScript,
        limits,
    };
    let mut request = p::Request {
        version: codeana_model::typescript::ANALYSIS_VERSION,
        request_id: "captured-handoff/1".into(),
        expected_runtime: Some(runtime.clone()),
        grant: Some(analysis_budget::encode_grant(&grant)),
        seed: Some(Default::default()),
        deadline_ms: Some(30_000),
        operation: Some(p::request::Operation::Analyze(p::Analyze {
            publication_root: snapshot
                .publication_root
                .to_str()
                .context("PUBLICATION_ROOT_UTF8")?
                .into(),
            project: Some(project),
            semantic_profile: metadata.semantic_digest(),
            scratch: scratch.to_string_lossy().into_owned(),
            output: Some(p::OutputLimits {
                archive_bytes: 1 << 20,
                rows: 1000,
                scratch_bytes: 1 << 20,
            }),
        })),
    };
    budget.charge(1, 16, (request.encoded_len() as u64 + 128) * 16 + (4 << 20))?;
    let seed = budget.usage();
    request.seed = Some(analysis_budget::encode_usage(&seed));
    let data = request.encode_to_vec();
    let (notification, writer) = Notification::pair()?;
    let mut handoff = BudgetHandoff::begin(&budget, &seed)?;
    owner
        .call(move |native| {
            native.prepare(data, writer.as_raw_fd())?;
            native.run()
        })
        .await?;
    tokio::time::timeout(Duration::from_secs(35), notification.wait_eof()).await??;
    let result = owner.call(Native::result).await?;
    let result = codeana_contracts::typescript::decode_result(&result)?;
    ensure!(
        result.runtime.as_ref() == Some(&runtime) && result.request_id == request.request_id,
        "HANDOFF_RESULT_IDENTITY"
    );
    let receipt = result.receipt.as_ref().context("HANDOFF_RECEIPT")?;
    ensure!(receipt.usage_known == Some(true), "HANDOFF_USAGE_UNKNOWN");
    handoff.complete(analysis_budget::decode_receipt(receipt, &grant)?)?;
    drop(handoff);
    let Some(p::result::Outcome::Analysis(analysis)) = result.outcome else {
        anyhow::bail!("HANDOFF_ANALYSIS_FAILED: {:?}", result.outcome)
    };
    let graph = analysis.graph.context("HANDOFF_GRAPH")?;
    let descriptor = codeana_contracts::graph_delivery::Descriptor {
        format: "GRA1".into(),
        path: scratch.join(&graph.path),
        count: graph.count,
        bytes: graph.bytes,
        sha256: graph.sha256,
    };
    let mut semantics = codeana_typescript_worker::evidence::SemanticEvidence::default();
    let verified = codeana_graph_io::reader::read_descriptor(
        &descriptor,
        codeana_contracts::graph_delivery::OutputLimits {
            bytes: 1 << 20,
            facts: 1000,
        },
        &scratch,
        false,
        Some(&budget),
        || Ok(()),
        |row| {
            semantics.record(&row, &budget)?;
            Ok(())
        },
        || {
            budget.checkpoint()?;
            Ok(())
        },
    )?;
    semantics.verify_greet_fixture()?;
    owner.call(Native::close).await?;
    owner.shutdown().await?;
    std::fs::write(
        evidence.join("capture-proof.json"),
        serde_json::to_vec_pretty(
            &serde_json::json!({"real_go_inventory":true,"public_capture":true,"live_inputs_deleted":true,"captured_bodies_verified":snapshot.files.len(),"published_sources":2,"units":1,"real_c_abi_handoff":true,"verified_rows":verified.count(),"semantic_declarations":semantics.declarations,"semantic_calls":semantics.calls,"compiler":compiler,"component_only":true,"public_semantic_rows":true,"product_indexing":false,"analysis_usage":budget.usage()}),
        )?,
    )?;
    Ok(())
}
