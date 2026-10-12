//! Replay an accepted immutable graph to isolate archive/storage cost from javac.
use anyhow::{Context, Result, ensure};
use codeana_contracts::archive::FactArchiveBuilder;
use codeana_model::{AnalysisHeader, Fact, Revision, hash_file, new_id};
use codeana_storage::Store;
use rusqlite::{Connection, OpenFlags};
use serde_json::json;
use std::{collections::BTreeMap, path::PathBuf, time::Instant};

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args
        .first()
        .is_some_and(|arg| arg == "--diagnostic-calibration")
    {
        ensure!(
            args.len() == 3,
            "calibration FACT_JSON NEW_OUTPUT_DIRECTORY"
        );
        return calibration(PathBuf::from(&args[1]), PathBuf::from(&args[2]));
    }
    ensure!(
        args.len() == 2,
        "usage: fact_replay SOURCE_REVISION_DIRECTORY NEW_DESTINATION_HOME"
    );
    let source = PathBuf::from(&args[0]);
    let destination = PathBuf::from(&args[1]);
    ensure!(!destination.exists(), "NEW_REPLAY_HOME_REQUIRED");
    let database = source.join("facts.db");
    let before = hash_file(&database)?;
    let mut revision: Revision =
        serde_json::from_slice(&std::fs::read(source.join("revision.json"))?)?;
    let source_home = source
        .parent()
        .and_then(|p| p.parent())
        .context("SOURCE_HOME")?;
    let start = Instant::now();
    let store = Store::open(&destination)?;
    let blobs = revision
        .manifest
        .files
        .iter()
        .map(|unit| {
            (
                unit.hash.clone(),
                source_home.join("blobs").join(&unit.hash),
            )
        })
        .collect::<BTreeMap<_, _>>();
    store.put_blob_files(&blobs)?;
    let blob_seconds = start.elapsed().as_secs_f64();
    let start = Instant::now();
    let connection = Connection::open_with_flags(&database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut statement = connection.prepare("SELECT body FROM facts ORDER BY id")?;
    let mut rows = statement.query([])?;
    let mut archive = FactArchiveBuilder::new_in(8 << 30, 5_000_000, &destination)?;
    let mut count = 0u64;
    while let Some(row) = rows.next()? {
        let body: String = row.get(0)?;
        let fact: Fact = serde_json::from_str(&body)?;
        archive.push(&fact)?;
        count += 1;
    }
    let archive = archive.seal()?;
    let export_archive_seconds = start.elapsed().as_secs_f64();
    ensure!(count > 0, "NONEMPTY_ACCEPTED_FACTS_REQUIRED");
    revision.id.clear();
    revision.target = "frozen-fact-replay".into();
    let header = AnalysisHeader {
        mode: "full".into(),
        baseline: String::new(),
        manifest: revision.manifest.id(),
        profile: revision.profile.clone(),
        producer: revision.producer.clone(),
        replacements: vec![],
    };
    let start = Instant::now();
    let candidate = store.stage_archive(revision, &header, &archive, "")?;
    let stage_seconds = start.elapsed().as_secs_f64();
    let statistics = candidate.summary.statistics.clone();
    let phase_ms = candidate.summary.phase_ms.clone();
    ensure!(
        candidate.summary.fact_count == count,
        "REPLAY_COUNT_MISMATCH"
    );
    let start = Instant::now();
    let published = store.publish_staged(candidate, &new_id("replay"))?;
    let publish_seconds = start.elapsed().as_secs_f64();
    ensure!(hash_file(&database)? == before, "SOURCE_DATABASE_MUTATED");
    println!(
        "{}",
        json!({"status":"published", "measurement_scope":"immutable_fact_replay_no_java", "source_database_sha256":before, "facts":count, "blob_seconds":blob_seconds, "export_archive_seconds":export_archive_seconds, "stage_seconds":stage_seconds, "publish_seconds":publish_seconds, "statistics":statistics, "storage_phase_ms":phase_ms, "published_revision":published.id, "published_facts":destination.join("revisions").join(&published.id).join("facts.db"), "performance_accepted":false})
    );
    Ok(())
}

fn calibration(input: PathBuf, output: PathBuf) -> Result<()> {
    use codeana_contracts::{fact_from_wire, fact_to_wire, performance::Capture};
    ensure!(!output.exists(), "NEW_CALIBRATION_DIRECTORY_REQUIRED");
    std::fs::create_dir(&output)?;
    let fact: Fact = serde_json::from_slice(&std::fs::read(&input)?)?;
    let trial = |name: &str, records: u64, observed: bool| -> Result<serde_json::Value> {
        let directory = output.join(name);
        std::fs::create_dir(&directory)?;
        let capture = if observed {
            Capture::enabled()
        } else {
            Capture::default()
        };
        let start = Instant::now();
        let (checksum, count, bytes) = capture.sync(|| -> Result<_> {
            let mut source = FactArchiveBuilder::new_in(8 << 30, records, &directory)?;
            for _ in 0..records {
                source.push(&fact)?;
            }
            let source = source.seal()?;
            let mut target = FactArchiveBuilder::new_in(8 << 30, records, &directory)?;
            for row in source.iter()? {
                let row = row?;
                target.push(&fact_from_wire(fact_to_wire(&row)?)?)?;
            }
            let target = target.seal()?;
            ensure!(
                source.checksum == target.checksum,
                "CALIBRATION_GRAPH_DRIFT"
            );
            Ok((target.checksum.clone(), target.count, target.bytes))
        })?;
        let seconds = start.elapsed().as_secs_f64();
        Ok(
            json!({"seconds":seconds,"count":count,"bytes":bytes,"checksum":checksum,"diagnostics":capture.snapshot()}),
        )
    };
    trial("warm-off", 10000, false)?;
    trial("warm-on", 10000, true)?;
    let control = trial("control", 1_000_000, false)?;
    let observed = trial("observed", 1_000_000, true)?;
    ensure!(
        control["checksum"] == observed["checksum"],
        "CALIBRATION_RESULT_DRIFT"
    );
    let overhead =
        observed["seconds"].as_f64().unwrap() / control["seconds"].as_f64().unwrap() - 1.;
    let report = json!({"status":if overhead<=0.03 {"passed"} else {"failed"},"control":control,"observed":observed,
        "overhead_fraction":overhead,"maximum_overhead_fraction":0.03,
        "scope":"one warmed Rust real-fact encode/decode/wire/CAF pair; not full-project overhead proof"});
    std::fs::write(
        output.join("receipt.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    println!("{report}");
    ensure!(overhead <= 0.03, "OBSERVER_OVERHEAD_EXCEEDED");
    Ok(())
}
