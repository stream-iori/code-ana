//! Actual bundled-SQLite resource behavior, independent of language fixtures.
use super::*;
use std::sync::atomic::{AtomicU64, Ordering};

fn accounts(limits: AnalysisLimits) -> Result<(AnalysisContext, GraphStagingContext)> {
    let host = AnalysisContext::new(limits, None)?;
    Ok((
        host.clone(),
        GraphStagingContext {
            hosts: BTreeMap::from([(AnalysisBackend::Rust, host)]),
            source: Arc::new(|_, _| {
                Err(DomainError::new(
                    ErrorCode::SourceHashMismatch,
                    "not a source fixture",
                ))
            }),
        },
    ))
}

#[test]
fn sql_cache_memory_is_admitted_before_opening_a_database_file() -> Result<()> {
    let root = tempfile::tempdir()?;
    let (host, context) = accounts(AnalysisLimits {
        max_payload_bytes: 1 << 20,
        ..Default::default()
    })?;
    let scope =
        crate::sql_budget::Scope::new(root.path(), Some(&context), &|| Ok(()), &|_, _| Ok(()))?;
    let error = scope
        .open(&root.path().join("never-created.db"))
        .err()
        .context("unadmitted SQLite workspace opened")?;
    assert_eq!(
        error.downcast_ref::<DomainError>().unwrap().code,
        ErrorCode::AnalysisResourceLimit
    );
    assert!(
        matches!(host.usage().failure, Some(AnalysisFailure::Limit { resource, .. }) if resource == "payload_bytes")
    );
    assert_eq!(std::fs::read_dir(root.path())?.count(), 0);
    Ok(())
}

#[test]
fn sql_vm_work_exhaustion_preserves_the_original_task_failure() -> Result<()> {
    let root = tempfile::tempdir()?;
    let (host, context) = accounts(AnalysisLimits {
        max_work: 100,
        ..Default::default()
    })?;
    let scope =
        crate::sql_budget::Scope::new(root.path(), Some(&context), &|| Ok(()), &|_, _| Ok(()))?;
    let db = scope.open(&root.path().join("work.db"))?;
    let result = db.query_row("WITH RECURSIVE n(i) AS(SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<1000000000) SELECT sum(i) FROM n", [], |r| r.get::<_, i64>(0)).map_err(Into::into);
    let error = scope.result(result).unwrap_err();
    assert_eq!(
        error.downcast_ref::<DomainError>().unwrap().code,
        ErrorCode::AnalysisResourceLimit
    );
    assert!(
        matches!(host.usage().failure, Some(AnalysisFailure::Limit { resource, .. }) if resource == "work")
    );
    assert!(host.usage().work <= 100);
    Ok(())
}

#[test]
fn sql_cancellation_is_checked_inside_the_vm_and_remains_cancellation() -> Result<()> {
    let root = tempfile::tempdir()?;
    let cancelled = std::cell::Cell::new(false);
    let check = || {
        ensure!(
            !cancelled.get(),
            DomainError::new(ErrorCode::Cancelled, "SQL fixture cancelled")
        );
        Ok(())
    };
    let disk = |_: &Path, _: u64| Ok(());
    let scope = crate::sql_budget::Scope::new(root.path(), None, &check, &disk)?;
    let db = scope.open(&root.path().join("cancel.db"))?;
    cancelled.set(true);
    let result = db.query_row("WITH RECURSIVE n(i) AS(SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<1000000000) SELECT sum(i) FROM n", [], |r| r.get::<_, i64>(0)).map_err(Into::into);
    let error = scope.result(result).unwrap_err();
    assert_eq!(
        error.downcast_ref::<DomainError>().unwrap().code,
        ErrorCode::Cancelled
    );
    Ok(())
}

#[test]
fn sql_anonymous_sort_growth_is_rejected_before_it_can_exceed_disk_quota() -> Result<()> {
    let root = tempfile::tempdir()?;
    let limit = 48 << 20;
    let peak_extra = AtomicU64::new(0);
    let disk = |path: &Path, extra: u64| -> Result<()> {
        peak_extra.fetch_max(extra, Ordering::Relaxed);
        let visible = std::fs::read_dir(path)?
            .map(|e| Ok(e?.metadata()?.len()))
            .collect::<Result<Vec<_>>>()?
            .iter()
            .sum::<u64>();
        ensure!(
            visible + extra <= limit,
            DomainError::new(ErrorCode::DiskFull, "SQLite spill quota")
        );
        Ok(())
    };
    let scope = crate::sql_budget::Scope::new(root.path(), None, &|| Ok(()), &disk)?;
    let db = scope.open(&root.path().join("sort.db"))?;
    assert!(Path::new(db.path().unwrap()).starts_with(root.path().canonicalize()?));
    db.execute_batch(
        "CREATE TABLE samples(value BLOB);
      WITH RECURSIVE n(i) AS(SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<4095)
      INSERT INTO samples SELECT randomblob(8192) FROM n;",
    )?;
    let result = (|| -> Result<()> {
        let mut statement = db.prepare("SELECT length(value) FROM samples ORDER BY value")?;
        let mut rows = statement.query([])?;
        while rows.next()?.is_some() {}
        Ok(())
    })();
    let error = scope
        .result(result)
        .err()
        .context("anonymous spill escaped the directory-only quota")?;
    assert_eq!(
        error.downcast_ref::<DomainError>().unwrap().code,
        ErrorCode::DiskFull
    );
    assert!(peak_extra.load(Ordering::Relaxed) > 1 << 20);
    // A rejected sort does not make the connection or private VFS uncloseable.
    drop(db);
    Ok(())
}

fn anonymous_regular_bytes() -> u64 {
    let Ok(entries) = std::fs::read_dir("/dev/fd") else {
        return 0;
    };
    entries
        .filter_map(|entry| {
            let descriptor = entry.ok()?.file_name().to_str()?.parse::<i32>().ok()?;
            let mut status = std::mem::MaybeUninit::<libc::stat>::uninit();
            // The descriptor is borrowed from this process; fstat neither closes nor
            // changes it. A racing close is simply ignored by its error return.
            if unsafe { libc::fstat(descriptor, status.as_mut_ptr()) } != 0 {
                return None;
            }
            let status = unsafe { status.assume_init() };
            (status.st_mode & libc::S_IFMT == libc::S_IFREG && status.st_nlink == 0)
                .then(|| u64::try_from(status.st_size).unwrap_or(0))
        })
        .sum()
}

#[test]
fn bundled_sqlite_sort_spills_to_open_unlinked_files_outside_candidate_directory() -> Result<()> {
    let root = tempfile::tempdir()?;
    let database = root.path().join("sort.db");
    let db = Connection::open(&database)?;
    db.execute_batch(
        "PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE;
      PRAGMA cache_size=-64; PRAGMA mmap_size=0; CREATE TABLE samples(value BLOB);
      WITH RECURSIVE n(i) AS(SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i<4095)
      INSERT INTO samples SELECT randomblob(8192) FROM n;",
    )?;
    let peak = Arc::new(AtomicU64::new(0));
    let observed = peak.clone();
    let callbacks = Arc::new(AtomicU64::new(0));
    let count = callbacks.clone();
    db.progress_handler(
        2048,
        Some(move || {
            count.fetch_add(1, Ordering::Relaxed);
            observed.fetch_max(anonymous_regular_bytes(), Ordering::Relaxed);
            false
        }),
    )?;
    let mut statement = db.prepare("SELECT length(value) FROM samples ORDER BY value")?;
    let mut rows = statement.query([])?;
    let mut sorted = 0;
    while let Some(row) = rows.next()? {
        assert_eq!(row.get::<_, i64>(0)?, 8192);
        sorted += 1;
    }
    assert_eq!(sorted, 4096);
    assert!(callbacks.load(Ordering::Relaxed) > 0);
    assert!(
        peak.load(Ordering::Relaxed) > 1 << 20,
        "fixture must actually spill, not merely sort in cache"
    );
    let visible: u64 = std::fs::read_dir(root.path())?
        .map(|e| Ok(e?.metadata()?.len()))
        .collect::<Result<Vec<_>>>()?
        .iter()
        .sum();
    assert_eq!(
        visible,
        database.metadata()?.len(),
        "the live sort file must not appear in the candidate directory"
    );
    eprintln!(
        "{}",
        serde_json::json!({"sqlite":rusqlite::version(),"rows":sorted,"candidate_directory_bytes":visible,"peak_unlinked_bytes":peak.load(Ordering::Relaxed),"progress_callbacks":callbacks.load(Ordering::Relaxed)})
    );
    Ok(())
}

#[test]
fn analysis_baseline_does_not_decode_another_units_large_same_path_world() -> Result<()> {
    let directory = tempfile::tempdir()?;
    let scratch = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let bytes = b"x".to_vec();
    let hash = hash(&bytes);
    store.put_blobs(&BTreeMap::from([(hash.clone(), bytes)]))?;
    let mut revision = crate::tests::revision();
    let mut units = Vec::new();
    for world in ["left", "right"] {
        let source = SourceUnit {
            path: "A.rs".into(),
            hash: hash.clone(),
            size: 1,
            module: "app".into(),
            world: world.into(),
            scope: "MAIN".into(),
            language: "rust".into(),
            auxiliary: false,
        };
        revision.manifest.files.push(source.clone());
        units.push(UnitOwnership {
            unit: world.into(),
            backend: AnalysisBackend::Rust,
            availability: UnitAvailability::Ready,
            source_languages: vec![SourceLanguage::Rust],
            modules: vec!["app".into()],
            worlds: vec![world.into()],
            scopes: vec!["MAIN".into()],
            producer: "mock".into(),
            semantic_profile: hash.clone(),
            input_digest: hash.clone(),
            inputs: vec![source.clone()],
            sources: vec![source],
        });
    }
    revision.unit_ownership = Some(UnitOwnershipLedger {
        version: UNIT_OWNERSHIP_VERSION,
        units,
    });
    let mut analysis = crate::tests::result();
    analysis.manifest = revision.manifest.id();
    let published = store.publish(revision, &analysis, "", "baseline-worlds")?;
    {
        let db = Connection::open(
            store
                .home
                .join("revisions")
                .join(&published.id)
                .join("facts.db"),
        )?;
        for world in ["left", "right"] {
            let name = if world == "left" {
                "small".into()
            } else {
                "x".repeat(8 << 20)
            };
            db.execute("INSERT INTO nodes(id,symbol_id,label,qualified_name,kind,module,scope,source_file,metadata,world) VALUES(?,?,'fixture','fixture','declaration','app','MAIN','A.rs',?,?)", params![world, world, &name, world])?;
            let state = serde_json::json!({"file":"A.rs","world":world,"source_hash":hash,"interface_hash":hash,"names":[name]});
            db.execute(
                "INSERT INTO rust_source_states(source_file,world,state) VALUES('A.rs',?,?)",
                params![world, state.to_string()],
            )?;
            db.execute("INSERT INTO rust_analysis_dependencies(source_file,world,kind,target_world,target_key) VALUES('A.rs',?,'lookup',?,'key')", [world, world])?;
        }
    }
    let (host, context) = accounts(AnalysisLimits {
        max_payload_bytes: 16 << 20,
        ..Default::default()
    })?;
    let check = |_: &Path, extra: u64| {
        ensure!(extra < 1 << 20, "immutable baseline charged as scratch");
        Ok(())
    };
    let summary = store.with_analysis_reader(
        &published.id,
        AnalysisRead {
            root: scratch.path(),
            context: Some(&context),
            cancellation: &|| Ok(()),
            check_directory: &check,
        },
        |reader| reader.rust_baseline_summary_for(&"left".into()),
    )?;
    assert_eq!(summary.rust_states.len(), 1);
    assert_eq!(summary.rust_states[0].world, "left");
    assert_eq!(summary.rust_states[0].names, ["small"]);
    assert_eq!(summary.rust_states[0].dependencies.len(), 1);
    assert_eq!(summary.rust_states[0].dependencies[0].world, "left");
    let (_, node_context) = accounts(AnalysisLimits {
        max_payload_bytes: 16 << 20,
        ..Default::default()
    })?;
    let left = Manifest {
        files: published.unit_ownership.as_ref().unwrap().units[0]
            .inputs
            .clone(),
    };
    let nodes = store.with_analysis_reader(
        &published.id,
        AnalysisRead {
            root: scratch.path(),
            context: Some(&node_context),
            cancellation: &|| Ok(()),
            check_directory: &check,
        },
        |reader| reader.baseline_summary_for(&left, &"left".into()),
    )?;
    assert_eq!(nodes.nodes.len(), 1);
    assert_eq!(nodes.nodes[0].id, "left");
    assert!(host.usage().payload_bytes < 16 << 20);
    assert!(store.coord.lock().unwrap().readers.is_empty());
    let (host, context) = accounts(AnalysisLimits {
        max_payload_bytes: 16 << 20,
        ..Default::default()
    })?;
    let error = store
        .with_analysis_reader(
            &published.id,
            AnalysisRead {
                root: scratch.path(),
                context: Some(&context),
                cancellation: &|| Ok(()),
                check_directory: &check,
            },
            |reader| reader.rust_baseline_summary_for(&"right".into()),
        )
        .unwrap_err();
    assert_eq!(
        error.downcast_ref::<DomainError>().unwrap().code,
        ErrorCode::AnalysisResourceLimit
    );
    assert!(
        matches!(host.usage().failure, Some(AnalysisFailure::Limit { resource, .. }) if resource == "payload_bytes")
    );
    assert!(store.coord.lock().unwrap().readers.is_empty());
    assert_eq!(store.head("t")?, published.id);
    Ok(())
}

#[test]
fn candidate_sql_failure_keeps_the_head_and_removes_all_private_files() -> Result<()> {
    for disk_denied in [false, true] {
        let root = tempfile::tempdir()?;
        let store = Store::open(root.path())?;
        let first = store.publish(
            crate::tests::revision(),
            &crate::tests::result(),
            "",
            "initial",
        )?;
        let (host, context) = accounts(AnalysisLimits {
            max_work: if disk_denied { 10_000_000 } else { 100 },
            ..Default::default()
        })?;
        let check_disk = |path: &Path, extra: u64| {
            let bytes = std::fs::read_dir(path)?.try_fold(0u64, |n, file| {
                Ok::<_, anyhow::Error>(n + file?.metadata()?.len())
            })?;
            ensure!(
                !disk_denied || bytes + extra <= 64 << 10,
                DomainError::new(ErrorCode::HostScratchBudget, "fixture limit")
            );
            Ok(())
        };
        let result = store.stage_graph_records_with_context(
            crate::tests::revision(),
            &crate::tests::result().header(),
            std::iter::empty(),
            &first.id,
            || Ok(()),
            check_disk,
            Some(&context),
        );
        let error = result.err().context("denied SQL candidate survived")?;
        let code = &error
            .downcast_ref::<DomainError>()
            .context(format!("{error:#}"))?
            .code;
        assert_eq!(
            *code,
            if disk_denied {
                ErrorCode::HostScratchBudget
            } else {
                ErrorCode::AnalysisResourceLimit
            }
        );
        if !disk_denied {
            assert!(
                matches!(host.usage().failure, Some(AnalysisFailure::Limit { resource, .. }) if resource == "work")
            );
        }
        assert_eq!(store.head("t")?, first.id);
        assert!(store.coord.lock().unwrap().readers.is_empty());
        assert_eq!(std::fs::read_dir(store.home.join("candidates"))?.count(), 0);
    }
    Ok(())
}

#[test]
fn sql_workspace_reuse_keeps_the_funded_host_charge_and_counts_concurrent_connections() -> Result<()>
{
    let root = tempfile::tempdir()?;
    let (host, context) = accounts(AnalysisLimits::default())?;
    let scope =
        crate::sql_budget::Scope::new(root.path(), Some(&context), &|| Ok(()), &|_, _| Ok(()))?;
    let first = scope.open(&root.path().join("first.db"))?;
    let initial = host.usage().payload_bytes;
    let second = scope.open(&root.path().join("second.db"))?;
    let concurrent = host.usage().payload_bytes;
    assert!(concurrent >= initial + (7 << 20));
    drop(second);
    drop(first);
    assert_eq!(host.usage().payload_bytes, concurrent);
    let sealed = scope.reopen_for_seal(&root.path().join("first.db"))?;
    assert!(host.usage().payload_bytes < concurrent + (1 << 20));
    assert!(sealed.query_row("SELECT 1", [], |r| r.get::<_, i64>(0))? == 1);
    drop(sealed);
    Ok(())
}

#[test]
fn sql_first_disk_failure_survives_nested_mapping_and_later_cancellation() -> Result<()> {
    use std::cell::Cell;
    let root = tempfile::tempdir()?;
    let cancelled = Cell::new(false);
    let denied = Cell::new(false);
    let cancellation = || {
        ensure!(
            !cancelled.get(),
            DomainError::new(ErrorCode::Cancelled, "later cancellation")
        );
        Ok(())
    };
    let disk = |_: &Path, _: u64| {
        ensure!(
            !denied.get(),
            DomainError::new(ErrorCode::HostScratchBudget, "first failure")
        );
        Ok(())
    };
    let scope = crate::sql_budget::Scope::new(root.path(), None, &cancellation, &disk)?;
    let db = scope.open(&root.path().join("sticky.db"))?;
    denied.set(true);
    let first = scope
        .result(
            db.execute_batch("CREATE TABLE denied(value TEXT)")
                .map_err(Into::into),
        )
        .unwrap_err();
    assert_eq!(
        first.downcast_ref::<DomainError>().unwrap().code,
        ErrorCode::HostScratchBudget
    );
    cancelled.set(true);
    let later = scope
        .result::<()>(Err(anyhow::anyhow!("later cleanup error")))
        .unwrap_err();
    assert_eq!(
        later.downcast_ref::<DomainError>().unwrap(),
        first.downcast_ref::<DomainError>().unwrap()
    );
    Ok(())
}
