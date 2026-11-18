//! Materialize the private VFS strictly from authenticated captured content.
use anyhow::{Context, Result, ensure};
use codeana_contracts::protobuf::typescript::v1 as p;
use codeana_model::{AnalysisContext, AnalysisUnitId, SourceLanguage, typescript as m};
use prost::Message;
use std::{collections::BTreeMap, io::Read, os::unix::fs::OpenOptionsExt};

const MAX_PROJECT_BYTES: usize = (32 << 20) - (64 << 10);
fn language(value: Option<SourceLanguage>) -> i32 {
    match value {
        Some(SourceLanguage::JavaScript) => p::SourceLanguage::Javascript as i32,
        Some(SourceLanguage::TypeScript) => p::SourceLanguage::Typescript as i32,
        None => p::SourceLanguage::Unspecified as i32,
        _ => unreachable!("validated TypeScript source language"),
    }
}

pub fn load(
    snapshot: &m::FrozenInputs,
    unit: &AnalysisUnitId,
    compiler: &m::CompilerIdentity,
    budget: &AnalysisContext,
) -> Result<p::FrozenProject> {
    let metadata_bytes = codeana_model::retained_size(snapshot)?;
    budget.charge(
        1,
        1,
        metadata_bytes
            .checked_mul(8)
            .context("TYPESCRIPT_FROZEN_LIMIT")?
            + 4096,
    )?;
    snapshot.validate().map_err(anyhow::Error::msg)?;
    ensure!(
        &snapshot.inputs.compiler == compiler,
        "TYPESCRIPT_COMPILER_IDENTITY_MISMATCH"
    );
    let context = &snapshot.inputs.contexts[0];
    ensure!(&context.unit == unit, "TYPESCRIPT_FROZEN_UNIT_MISMATCH");
    let mut project = p::FrozenProject {
        context: Some(p::ProjectContext {
            unit: unit.to_string(),
            root: context.root.clone(),
            config_path: context.config_path.clone(),
            publish_languages: context
                .publish_languages
                .iter()
                .map(|value| language(Some(*value)))
                .collect(),
            case_sensitive: Some(context.case_sensitive),
            root_files: context.root_files.clone(),
            references: context.references.clone(),
            directories: context
                .directories
                .iter()
                .map(|entry| p::Directory {
                    path: entry.path.clone(),
                    files: entry.files.clone(),
                    directories: entry.directories.clone(),
                    listing_complete: entry.listing_complete,
                })
                .collect(),
            links: context
                .links
                .iter()
                .map(|entry| p::SymbolicLink {
                    path: entry.path.clone(),
                    target: entry.target.clone(),
                    allowed_root: entry.allowed_root.clone(),
                })
                .collect(),
            absent: context
                .absent
                .iter()
                .map(|entry| p::AbsentLookup {
                    path: entry.path.clone(),
                    kind: match entry.kind {
                        m::LookupKind::File => p::LookupKind::File as i32,
                        m::LookupKind::Directory => p::LookupKind::Directory as i32,
                    },
                })
                .collect(),
        }),
        files: Vec::with_capacity(context.files.len()),
    };
    let references = snapshot
        .files
        .iter()
        .map(|file| (file.source.as_str(), file))
        .collect::<BTreeMap<_, _>>();
    let mut encoded = project.encoded_len();
    ensure!(encoded <= MAX_PROJECT_BYTES, "TYPESCRIPT_FROZEN_LIMIT");
    for file in &context.files {
        budget.checkpoint()?;
        let reference = references
            .get(file.path.as_str())
            .context("TYPESCRIPT_FROZEN_FILE_MISSING")?;
        let publish = reference.publish;
        let identity = p::FileIdentity {
            path: file.path.clone(),
            sha256: file.sha256.clone(),
            size: file.bytes,
            role: if publish {
                p::FileRole::Publish
            } else if file.role == m::FileRole::Control {
                p::FileRole::Control
            } else {
                p::FileRole::Auxiliary
            } as i32,
            language: language(file.language),
            module: if publish {
                file.module.clone().unwrap_or_default()
            } else {
                String::new()
            },
            scope: if publish {
                file.scope.clone().unwrap_or_default()
            } else {
                String::new()
            },
            world: if publish {
                file.world
                    .as_ref()
                    .map(ToString::to_string)
                    .unwrap_or_default()
            } else {
                String::new()
            },
        };
        let size = usize::try_from(file.bytes)?;
        encoded = encoded
            .checked_add(identity.encoded_len())
            .and_then(|n| n.checked_add(size))
            .and_then(|n| n.checked_add(32))
            .context("TYPESCRIPT_FROZEN_LIMIT")?;
        ensure!(encoded <= MAX_PROJECT_BYTES, "TYPESCRIPT_FROZEN_LIMIT");
        budget.charge(
            file.bytes / 4096 + 1,
            1,
            file.bytes
                .checked_mul(3)
                .context("TYPESCRIPT_FROZEN_LIMIT")?
                + 512,
        )?;
        let input = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&reference.input)?;
        let metadata = input.metadata()?;
        ensure!(
            metadata.is_file() && metadata.len() == file.bytes,
            "TYPESCRIPT_CAPTURE_FILE_CHANGED"
        );
        let mut content = Vec::new();
        content.try_reserve_exact(size + 1)?;
        input.take(file.bytes + 1).read_to_end(&mut content)?;
        budget.checkpoint()?;
        ensure!(
            content.len() == size && codeana_model::hash(&content) == file.sha256,
            "TYPESCRIPT_CAPTURE_FILE_CHANGED"
        );
        ensure!(
            std::str::from_utf8(&content).is_ok(),
            "TYPESCRIPT_CAPTURE_ENCODING"
        );
        project.files.push(p::FrozenFile {
            file: Some(identity),
            content,
        });
    }
    // Keep compiler root/reference order. Incidental inventory order does not
    // change VFS materialization or raw graph traversal ordering.
    project.files.sort_by(|a, b| {
        a.file
            .as_ref()
            .unwrap()
            .path
            .cmp(&b.file.as_ref().unwrap().path)
    });
    let context = project.context.as_mut().unwrap();
    context.publish_languages.sort();
    context.directories.sort_by(|a, b| a.path.cmp(&b.path));
    for entry in &mut context.directories {
        entry.files.sort();
        entry.directories.sort();
    }
    context.links.sort_by(|a, b| a.path.cmp(&b.path));
    context
        .absent
        .sort_by(|a, b| (&a.path, a.kind).cmp(&(&b.path, b.kind)));
    ensure!(
        project.encoded_len() <= MAX_PROJECT_BYTES,
        "TYPESCRIPT_FROZEN_LIMIT"
    );
    Ok(project)
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::{AnalysisLimits, hash};
    use std::sync::Arc;
    fn fixture() -> Result<(tempfile::TempDir, m::FrozenInputs)> {
        let (inventory, compiler) = crate::inputs::tests::fixture();
        let mut inputs = crate::inputs::from_inventory(inventory, compiler)?;
        let body = b"sealed source";
        let metadata = &mut inputs.contexts[0].files[0];
        metadata.sha256 = hash(body);
        metadata.bytes = body.len() as u64;
        let spool = tempfile::tempdir()?;
        let input = spool.path().join(&metadata.sha256);
        std::fs::write(&input, body)?;
        let file = m::FrozenFile {
            source: metadata.path.clone(),
            input,
            publish: true,
        };
        Ok((
            spool,
            m::FrozenInputs {
                schema_version: m::FROZEN_INPUT_SCHEMA,
                publication_root: inputs.contexts[0].root.clone().into(),
                inputs: Arc::new(inputs),
                files: vec![file],
            },
        ))
    }
    fn budget() -> AnalysisContext {
        AnalysisContext::new(AnalysisLimits::default(), None).unwrap()
    }
    #[test]
    fn content_comes_from_sealed_references_and_publication_follows_the_view() -> Result<()> {
        let (_spool, mut snapshot) = fixture()?;
        let context = &snapshot.inputs.contexts[0];
        assert!(!std::path::Path::new(&snapshot.files[0].source).exists());
        let project = load(
            &snapshot,
            &context.unit,
            &snapshot.inputs.compiler,
            &budget(),
        )?;
        assert_eq!(project.files[0].content, b"sealed source");
        assert_eq!(
            project.files[0].file.as_ref().unwrap().path,
            snapshot.files[0].source
        );
        assert_ne!(
            project.files[0].file.as_ref().unwrap().path,
            snapshot.files[0].input.to_str().unwrap()
        );
        snapshot.files[0].publish = false;
        let project = load(
            &snapshot,
            &context.unit,
            &snapshot.inputs.compiler,
            &budget(),
        )?;
        let file = project.files[0].file.as_ref().unwrap();
        assert_eq!(file.role, p::FileRole::Auxiliary as i32);
        assert!(file.module.is_empty() && file.scope.is_empty() && file.world.is_empty());
        Ok(())
    }
    #[test]
    fn absent_corrupt_or_symbolic_content_never_falls_back_to_origin_paths() -> Result<()> {
        let (_spool, snapshot) = fixture()?;
        let context = &snapshot.inputs.contexts[0];
        let input = &snapshot.files[0].input;
        std::fs::write(input, b"changed bytes")?;
        assert!(
            load(
                &snapshot,
                &context.unit,
                &snapshot.inputs.compiler,
                &budget()
            )
            .is_err()
        );
        std::fs::remove_file(input)?;
        assert!(
            load(
                &snapshot,
                &context.unit,
                &snapshot.inputs.compiler,
                &budget()
            )
            .is_err()
        );
        let target = input.with_extension("target");
        std::fs::write(&target, b"sealed source")?;
        std::os::unix::fs::symlink(&target, input)?;
        assert!(
            load(
                &snapshot,
                &context.unit,
                &snapshot.inputs.compiler,
                &budget()
            )
            .is_err()
        );
        Ok(())
    }
    #[test]
    fn identity_and_funding_are_checked_before_content_io() -> Result<()> {
        let (_spool, snapshot) = fixture()?;
        std::fs::remove_file(&snapshot.files[0].input)?;
        let context = &snapshot.inputs.contexts[0];
        let mut compiler = snapshot.inputs.compiler.clone();
        compiler.library_sha256 = "e".repeat(64);
        let error = load(&snapshot, &context.unit, &compiler, &budget()).unwrap_err();
        assert!(error.to_string().contains("COMPILER_IDENTITY_MISMATCH"));
        let error = load(
            &snapshot,
            &"different-unit".into(),
            &snapshot.inputs.compiler,
            &budget(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("FROZEN_UNIT_MISMATCH"));
        let quota = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )?;
        let error = load(&snapshot, &context.unit, &snapshot.inputs.compiler, &quota).unwrap_err();
        assert!(error.to_string().contains("ANALYSIS_RESOURCE_LIMIT"));
        assert!(quota.checkpoint().is_err());
        Ok(())
    }
}
