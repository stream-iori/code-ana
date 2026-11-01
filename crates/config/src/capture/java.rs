//! Java metadata and dependency capture; common workspace traversal stays in capture.
use super::*;
pub(super) fn capture_java_metadata(
    policy: &CaptureSelection<'_>,
    accumulator: &mut CaptureAccumulator,
) -> Result<()> {
    let root = policy.root;
    let effective = policy.effective;
    let c = &policy.effective.config;
    let includes = &policy.includes;
    let excludes = &policy.excludes;
    let text_only = &policy.text_only;
    let files_only = policy.files_only;
    let started = policy.started;
    let deadline = policy.deadline;
    let CaptureAccumulator {
        manifest,
        blobs,
        total,
        scanned,
        spool,
        ..
    } = accumulator;
    if !files_only && let Some(inputs) = &effective.java_inputs {
        let metadata_path = c
            .languages
            .java
            .as_ref()
            .and_then(|l| l.discovery.inputs_manifest.as_deref())
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaMetadataPath,
                "",
            ))?;
        let metadata = bounded_read(&root.join(metadata_path), 32 * 1024 * 1024)?;
        let reparsed: JavaInputs = serde_json::from_slice(&metadata)?;
        ensure!(
            digest(&reparsed) == digest(inputs),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaMetadataChangedDuringCapture,
                ""
            )
        );
        add_captured(
            manifest,
            blobs,
            total,
            spool,
            metadata_path.into(),
            metadata,
            (
                String::new(),
                "CONTROL".into(),
                String::new(),
                String::new(),
            ),
        )?;
        for (path, expected) in &inputs.controls {
            ensure!(
                !excludes.is_match(path),
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaControlExcluded, "")
            );
            let bytes = bounded_read(&root.join(path), MAX_FRAME)?;
            ensure!(
                hash(&bytes) == *expected,
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaMetadataStale, "")
            );
            add_captured(
                manifest,
                blobs,
                total,
                spool,
                path.clone(),
                bytes,
                (
                    String::new(),
                    "CONTROL".into(),
                    String::new(),
                    String::new(),
                ),
            )?;
        }
        // External settings and Maven executable are identities, not captured source blobs.
        // Recheck them at capture time so an export cannot race a settings edit.
        for (path, expected) in &inputs.external_controls {
            ensure!(
                hash(&bounded_read(Path::new(path), MAX_FRAME)?) == *expected,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaMetadataStale,
                    path.to_string()
                )
            );
        }
        for m in &inputs.modules {
            ensure!(
                started.elapsed() < deadline,
                codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureTimeout, "")
            );
            let config = c.modules.iter().find(|v| v.id == m.id).context(
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaModuleConfigRequired,
                    "",
                ),
            )?;
            ensure!(
                config.root == m.root,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaModuleRootMismatch,
                    ""
                )
            );
            let module_include = glob(&config.scan.include)?;
            let module_exclude = glob(&config.scan.exclude)?;
            for (r, is_source) in m
                .source_roots
                .iter()
                .map(|r| (r, true))
                .chain(m.resource_roots.iter().map(|r| (r, false)))
            {
                let prerequisite =
                    is_source && r.scope == "MAIN" && c.scan.scopes.iter().any(|s| s == "TEST");
                if (!c.scan.scopes.contains(&r.scope)
                    || (!config.scan.scopes.is_empty() && !config.scan.scopes.contains(&r.scope)))
                    && !prerequisite
                {
                    continue;
                }
                for (proof, expected) in &r.generated_from {
                    ensure!(
                        !excludes.is_match(proof),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaGeneratedProofExcluded,
                            ""
                        )
                    );
                    let bytes = bounded_read(&root.join(proof), MAX_FRAME)?;
                    ensure!(
                        hash(&bytes) == *expected,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GeneratedInputStale,
                            ""
                        )
                    );
                    add_captured(
                        manifest,
                        blobs,
                        total,
                        spool,
                        proof.clone(),
                        bytes,
                        (
                            String::new(),
                            "CONTROL".into(),
                            String::new(),
                            String::new(),
                        ),
                    )?;
                }
                let dir = root.join(java_inputs::root_path(&m.root, &r.path)?);
                if !dir.exists() {
                    ensure!(
                        !r.generated,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::BuildInputRequired,
                            "generated source root is missing".to_string()
                        )
                    );
                    continue;
                }
                ensure!(
                    dir.canonicalize()?.starts_with(root),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaInputOutsideProject,
                        ""
                    )
                );
                for entry in walkdir::WalkDir::new(&dir).follow_links(false) {
                    let entry = entry?;
                    ensure!(
                        started.elapsed() < deadline,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::CaptureTimeout,
                            ""
                        )
                    );
                    if entry.file_type().is_dir() {
                        continue;
                    }
                    ensure!(
                        entry.file_type().is_file(),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaInputSymlink,
                            ""
                        )
                    );
                    *scanned += 1;
                    ensure!(
                        *scanned <= 100000,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::ScanEntryBudget,
                            ""
                        )
                    );
                    let path = entry
                        .path()
                        .strip_prefix(root)?
                        .to_str()
                        .context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::NonUtf8Path,
                            "",
                        ))?
                        .to_owned();
                    if excludes.is_match(&path)
                        || text_only.is_match(&path)
                        || !includes.is_match(&path)
                        || (!config.scan.include.is_empty() && !module_include.is_match(&path))
                        || module_exclude.is_match(&path)
                    {
                        continue;
                    }
                    if is_source && !path.ends_with(".java") {
                        continue;
                    }
                    let ownership = if is_source {
                        let owner = source_ownership(&c.modules, &path)?.unwrap_or((
                            m.id.clone(),
                            r.scope.clone(),
                            "java".into(),
                            m.id.clone(),
                        ));
                        ensure!(
                            owner.3 == m.id && owner.1 == r.scope,
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::AmbiguousSourceOwner,
                                path.to_string()
                            )
                        );
                        owner
                    } else {
                        (m.id.clone(), r.scope.clone(), String::new(), m.id.clone())
                    };
                    add_captured(
                        manifest,
                        blobs,
                        total,
                        spool,
                        path,
                        bounded_read(entry.path(), MAX_FRAME)?,
                        ownership,
                    )?;
                }
            }
            if config.analysis.classpath.as_deref() == Some("none") {
                continue;
            }
            for artifact in m.classpath.values().flatten() {
                ensure!(
                    started.elapsed() < deadline,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureTimeout, "")
                );
                let path = if Path::new(&artifact.path).is_absolute() {
                    PathBuf::from(&artifact.path)
                } else {
                    root.join(&artifact.path)
                };
                // Large dependency archives are captured through a bounded file spool;
                // their size is independent of the IPC frame and semantic-result budgets.
                let captured_path = format!(".code-ana/inputs/{}.jar", artifact.sha256);
                if manifest.files.iter().any(|s| s.path == captured_path) {
                    continue;
                }
                let (blob, h, size) = CapturedBlob::file(spool, &path, MAX_ARTIFACT_BYTES)
                    .context("BUILD_INPUT_REQUIRED: dependency artifact")?;
                ensure!(
                    h == artifact.sha256,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaArtifactStale,
                        ""
                    )
                );
                *total += size;
                ensure!(
                    *total <= MAX_CAPTURE_BYTES && manifest.files.len() < 10000,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaCaptureBudget,
                        ""
                    )
                );
                manifest.files.push(SourceUnit {
                    auxiliary: false,
                    path: captured_path,
                    hash: h.clone(),
                    size: size as u64,
                    module: String::new(),
                    world: "files".into(),
                    scope: "DEPENDENCY".into(),
                    language: String::new(),
                });
                blobs.insert(h, blob);
            }
        }
    }
    Ok(())
}
