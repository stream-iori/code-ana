//! Explicit component entry until the product session is wired in T6.
use anyhow::{Context, Result, ensure};
use clap::Parser;
use codeana_contracts::{analysis_budget, protobuf::typescript::v1 as p};
use codeana_model::{AnalysisBackend, AnalysisContext, AnalysisGrant, AnalysisLimits};
use codeana_typescript_worker::native::Native;
use codeana_worker_runtime::ffi::{BudgetHandoff, Notification, Owner};
use prost::Message;
use std::{os::fd::AsRawFd, path::PathBuf, time::Duration};

#[derive(Parser)]
struct Args {
    #[arg(long)]
    sdk: PathBuf,
    #[arg(long)]
    component: Option<PathBuf>,
    #[arg(long)]
    evidence: Option<PathBuf>,
    #[arg(long)]
    runtime_info: bool,
    #[arg(long)]
    abi_check: bool,
    #[arg(long)]
    dev_abi_fixture: bool,
    #[arg(long, default_value = "success")]
    case: String,
}

fn source(name: &str, content: Vec<u8>, publish: bool) -> p::FrozenFile {
    p::FrozenFile {
        file: Some(p::FileIdentity {
            path: format!("/src/{name}"),
            sha256: codeana_model::hash(&content),
            size: content.len() as u64,
            role: if publish {
                p::FileRole::Publish
            } else {
                p::FileRole::Control
            } as i32,
            language: if publish {
                p::SourceLanguage::Typescript as i32
            } else {
                0
            },
            module: if publish { "fixture" } else { "" }.into(),
            scope: if publish { "MAIN" } else { "" }.into(),
            world: if publish { "main" } else { "" }.into(),
        }),
        content,
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let args = Args::parse();
    let sdk = args.sdk.clone();
    let allow_fixture = args.dev_abi_fixture;
    let mut owner = Owner::spawn(move || Native::load_with_fixture(&sdk, allow_fixture)).await?;
    let runtime = owner.call(|native| Ok(native.runtime.clone())).await?;
    if args.abi_check {
        let checks = owner.call(Native::conformance).await?;
        owner.shutdown().await?;
        println!(
            "{}",
            serde_json::json!({"component_only":true,"status":"passed","checks":checks.len(),"case_ids":checks,"runtime":runtime})
        );
        return Ok(());
    }
    if args.runtime_info {
        println!("{}", serde_json::to_string(&runtime)?);
        owner.shutdown().await?;
        return Ok(());
    }
    let fixture = args.component.context(
        "TypeScript product IPC is pending T6; use --component for explicit component verification",
    )?;
    ensure!(
        matches!(
            args.case.as_str(),
            "success"
                | "prepare"
                | "quota"
                | "invalid"
                | "cancel-before-start"
                | "panic"
                | "running-close"
        ),
        "UNKNOWN_COMPONENT_CASE"
    );
    if matches!(args.case.as_str(), "panic" | "running-close") {
        ensure!(
            args.dev_abi_fixture && runtime.adapter.ends_with("-abi-fixture"),
            "EXPLICIT_ABI_FIXTURE_REQUIRED"
        );
        ensure!(
            std::env::var("CODEANA_TS_ABI_FIXTURE").as_deref() == Ok(args.case.as_str()),
            "ABI_FIXTURE_MODE_REQUIRED"
        );
    }
    let output = args
        .evidence
        .context("--evidence must be a new absolute directory")?;
    ensure!(
        output.is_absolute() && !output.exists(),
        "NEW_ABSOLUTE_EVIDENCE_REQUIRED"
    );
    std::fs::create_dir_all(&output)?;
    let output = output.canonicalize()?;
    let scratch = output.join("engine");
    std::fs::create_dir(&scratch)?;
    let live = output.join("live");
    std::fs::create_dir(&live)?;
    let mut files = Vec::new();
    for name in ["a.ts", "b.ts"] {
        let path = fixture.join(name);
        ensure!(
            std::fs::metadata(&path)?.len() <= 1 << 20,
            "COMPONENT_FIXTURE_BUDGET"
        );
        let bytes = std::fs::read(path)?;
        std::fs::write(live.join(name), &bytes)?;
        files.push(source(name, bytes, true));
    }
    files.push(source("tsconfig.json",br#"{"compilerOptions":{"skipLibCheck":true,"module":"ESNext","moduleResolution":"bundler"},"files":["a.ts","b.ts"]}"#.to_vec(),false));
    let limits = AnalysisLimits {
        max_work: if args.case == "quota" { 64 } else { 10_000_000 },
        ..Default::default()
    };
    let grant = AnalysisGrant {
        id: "component/typescript/instance/fixture".into(),
        language: AnalysisBackend::TypeScript,
        limits,
    };
    let budget = AnalysisContext::new(limits, None)?;
    let mut request = p::Request {
        version: if args.case == "invalid" { 99 } else { 1 },
        request_id: "component/fixture/1".into(),
        expected_runtime: Some(runtime.clone()),
        grant: Some(analysis_budget::encode_grant(&grant)),
        seed: Some(Default::default()),
        deadline_ms: Some(0),
        operation: Some(p::request::Operation::Analyze(p::Analyze {
            project: Some(p::FrozenProject {
                context: Some(p::ProjectContext {
                    case_sensitive: Some(true),
                    unit: "fixture".into(),
                    root: "/src".into(),
                    config_path: "/src/tsconfig.json".into(),
                    publish_languages: vec![p::SourceLanguage::Typescript as i32],
                    directories: vec![
                        p::Directory {
                            listing_complete: true,
                            path: "/".into(),
                            files: vec![],
                            directories: vec!["src".into()],
                        },
                        p::Directory {
                            listing_complete: true,
                            path: "/src".into(),
                            files: vec!["a.ts".into(), "b.ts".into(), "tsconfig.json".into()],
                            directories: vec![],
                        },
                    ],
                    root_files: vec!["/src/a.ts".into(), "/src/b.ts".into()],
                    ..Default::default()
                }),
                files,
            }),
            semantic_profile: "typescript-component-v1".into(),
            scratch: scratch.to_string_lossy().into_owned(),
            output: Some(p::OutputLimits {
                archive_bytes: 1 << 20,
                rows: 1000,
                scratch_bytes: 1 << 20,
            }),
        })),
    };
    if args.case == "prepare" {
        std::fs::write(live.join("tsconfig.json"), br#"{"compilerOptions":{"noLib":true,"module":"ESNext","moduleResolution":"bundler"},"files":["a.ts","b.ts"]}"#)?;
        // Probe the actual task volume using the directory we just created;
        // never infer path case rules from the worker executable's volume.
        use std::os::unix::fs::MetadataExt;
        let actual = std::fs::metadata(&live)?;
        let case_sensitive = match std::fs::metadata(output.join("LIVE")) {
            Ok(other) => actual.dev() != other.dev() || actual.ino() != other.ino(),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
            Err(error) => return Err(error.into()),
        };
        request.operation = Some(p::request::Operation::Prepare(p::Prepare {
            unit: "fixture".into(),
            project_root: live.to_string_lossy().into_owned(),
            config_path: live.join("tsconfig.json").to_string_lossy().into_owned(),
            publish_languages: vec![p::SourceLanguage::Typescript as i32],
            allowed_roots: vec!["/".into()],
            module: "fixture".into(),
            scope: "MAIN".into(),
            world: "main".into(),
            scratch: scratch.to_string_lossy().into_owned(),
            inventory_bytes: 8 << 20,
            file_limit: 1000,
            scratch_bytes: 8 << 20,
            case_sensitive: Some(case_sensitive),
        }));
    }
    // Fund encoding, copied Go input, decoded model and terminal reception before
    // handing over this same account. No Rust debit while it is EngineOwned.
    budget.charge(1, 16, (request.encoded_len() as u64 + 128) * 16 + (4 << 20))?;
    let seed = budget.usage();
    request.seed = Some(analysis_budget::encode_usage(&seed));
    let data = request.encode_to_vec();
    if args.case != "prepare" {
        std::fs::write(live.join("b.ts"), b"changed live file")?;
        std::fs::remove_file(live.join("a.ts"))?;
    }
    let (notification, writer) = Notification::pair()?;
    let mut handoff = BudgetHandoff::begin(&budget, &seed)?;
    let cancel = args.case == "cancel-before-start";
    owner
        .call(move |native| {
            native.prepare(data, writer.as_raw_fd())?;
            if cancel {
                native.cancel()
            } else {
                native.run()
            }
        })
        .await?;
    let mut running_close_pending_observed = false;
    if args.case == "running-close" {
        wait_fixture_phase(&owner, 1).await?;
        ensure!(
            owner.call(Native::try_close).await? == 2,
            "TYPESCRIPT_RUNNING_CLOSE_RELEASED_INPUT"
        );
        wait_fixture_phase(&owner, 2).await?;
        ensure!(
            budget.accounting_owner() == codeana_model::AccountingOwner::EngineOwned
                && budget.usage() == seed,
            "TYPESCRIPT_RUNNING_CLOSE_CHANGED_ACCOUNT"
        );
        ensure!(
            owner.call(Native::state).await? == 4,
            "TYPESCRIPT_RUNNING_CLOSE_FALSE_TERMINAL"
        );
        running_close_pending_observed = true;
        owner.call(Native::fixture_release).await?;
    }
    tokio::time::timeout(Duration::from_secs(30), notification.wait_eof()).await??;
    let bytes = owner.call(Native::result).await?;
    let result = codeana_contracts::typescript::decode_result(&bytes)?;
    ensure!(
        result.runtime.as_ref() == Some(&runtime),
        "TYPESCRIPT_RESULT_RUNTIME"
    );
    let receipt = result
        .receipt
        .as_ref()
        .context("TYPESCRIPT_RESULT_RECEIPT")?;
    if receipt.usage_known == Some(true) {
        ensure!(
            result.request_id == request.request_id,
            "TYPESCRIPT_RESULT_REQUEST"
        );
        let usage = analysis_budget::decode_receipt(receipt, &grant)?;
        handoff.complete(usage)?;
    }
    drop(handoff);
    let mut samples = Vec::new();
    let mut verified = None;
    let success = matches!(
        &result.outcome,
        Some(p::result::Outcome::Analysis(_) | p::result::Outcome::Prepared(_))
    );
    let mut prepared = None;
    if let Some(p::result::Outcome::Prepared(value)) = &result.outcome {
        ensure!(
            receipt.usage_known == Some(true) && budget.usage().failure.is_none(),
            "TYPESCRIPT_PREPARATION_USAGE"
        );
        let archive = value
            .inventory
            .as_ref()
            .context("TYPESCRIPT_INVENTORY_REQUIRED")?;
        ensure!(
            codeana_model::safe_path(&archive.path)
                && archive.bytes <= 1 << 20
                && archive.count <= 1000,
            "TYPESCRIPT_INVENTORY_LIMIT"
        );
        let path = scratch.join(&archive.path);
        let metadata = std::fs::symlink_metadata(&path)?;
        ensure!(
            metadata.is_file()
                && !metadata.file_type().is_symlink()
                && metadata.len() == archive.bytes,
            "TYPESCRIPT_INVENTORY_FILE"
        );
        budget.charge(
            archive.count + 1,
            archive.bytes + 1,
            archive.bytes * 128 + 4096,
        )?;
        let data = std::fs::read(path)?;
        ensure!(
            data.len() as u64 == archive.bytes && codeana_model::hash(&data) == archive.sha256,
            "TYPESCRIPT_INVENTORY_SHA"
        );
        let inventory = p::PreparationInventory::decode(data.as_slice())?;
        ensure!(
            inventory.files.len() as u64 == archive.count,
            "TYPESCRIPT_INVENTORY_COUNT"
        );
        let context = inventory
            .context
            .as_ref()
            .context("TYPESCRIPT_INVENTORY_CONTEXT")?;
        ensure!(
            context.case_sensitive.is_some() && context.root_files.len() == 2,
            "TYPESCRIPT_INVENTORY_CONTEXT"
        );
        prepared = Some(inventory);
    }
    if let Some(p::result::Outcome::Analysis(analysis)) = &result.outcome {
        ensure!(
            receipt.usage_known == Some(true) && budget.usage().failure.is_none(),
            "TYPESCRIPT_SUCCESS_USAGE"
        );
        let graph = analysis
            .graph
            .as_ref()
            .context("TYPESCRIPT_GRAPH_REQUIRED")?;
        ensure!(
            codeana_model::safe_path(&graph.path),
            "TYPESCRIPT_GRAPH_PATH"
        );
        let descriptor = codeana_contracts::graph_delivery::Descriptor {
            format: "GRA1".into(),
            path: scratch.join(&graph.path),
            count: graph.count,
            bytes: graph.bytes,
            sha256: graph.sha256.clone(),
        };
        let proof = codeana_graph_io::reader::read_descriptor(
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
                if row.table == codeana_contracts::graph::Table::Diagnostic as i32 {
                    samples.push(row.cells.last().cloned());
                }
                Ok(())
            },
            || {
                budget.checkpoint()?;
                Ok(())
            },
        )?;
        verified = Some(
            serde_json::json!({"count":proof.count(),"bytes":proof.bytes(),"sha256":proof.checksum()}),
        );
    }
    owner.call(Native::close).await?;
    owner.shutdown().await?;
    let report = serde_json::json!({"component_only":true,"semantic_partial":true,"complete_plan":false,"native_accepted":false,"runtime":runtime,"case":args.case,"outcome":result.outcome,"analysis_usage":budget.usage(),"accounting_owner":format!("{:?}",budget.accounting_owner()),"verified_graph":verified,"prepared_inventory":prepared,"binding_samples":samples,"development_fixture":args.dev_abi_fixture,"running_close_pending_observed":running_close_pending_observed});
    std::fs::write(
        output.join("receipt.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    println!(
        "{}",
        serde_json::json!({"component_only":true,"success":success,"receipt":output.join("receipt.json")})
    );
    if !success {
        std::process::exit(
            if matches!(args.case.as_str(), "cancel-before-start" | "running-close") {
                130
            } else {
                1
            },
        )
    }
    Ok(())
}

async fn wait_fixture_phase(owner: &Owner<Native>, expected: u32) -> Result<()> {
    tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            if owner.call(Native::fixture_phase).await? == expected {
                return Ok::<(), anyhow::Error>(());
            }
            tokio::task::yield_now().await;
        }
    })
    .await??;
    Ok(())
}
