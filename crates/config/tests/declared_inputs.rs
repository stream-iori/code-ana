use anyhow::Result;
use codeana_config::{DeclaredLink, InputCaptureReader, ResolutionObservation};
use std::os::unix::fs::symlink;
use std::{
    path::Path,
    sync::Arc,
    time::{Duration, Instant},
};

fn reader(root: &Path) -> Result<InputCaptureReader> {
    InputCaptureReader::new(
        root,
        Arc::new(tempfile::tempdir()?),
        Instant::now(),
        Duration::from_secs(30),
        None,
        None,
    )
}
fn link(path: &str, target: &str) -> DeclaredLink {
    DeclaredLink {
        path: path.into(),
        target: target.into(),
    }
}
#[test]
fn frozen_file_survives_source_removal_and_records_both_proofs() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("source"), b"frozen")?;
    symlink("source", root.join("alias"))?;
    assert!(reader(&root)?.read(&root.join("alias"), 1024).is_err());
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias", "source")])?;
    let (blob, _, _) = inputs.file(&root.join("alias"), 1024)?;
    let sealed = inputs.seal()?;
    assert!(
        sealed
            .observations
            .iter()
            .any(|o| matches!(o, ResolutionObservation::Link {path,..} if path.ends_with("alias")))
    );
    assert!(
        sealed
            .observations
            .iter()
            .any(|o| matches!(o, ResolutionObservation::File {path,..} if path.ends_with("alias")))
    );
    drop(project);
    assert_eq!(blob.read(1024)?, b"frozen");
    Ok(())
}
#[test]
fn nested_directory_alias_uses_physical_parent_and_order_independent_map() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    for dir in ["store/pkg", "store/shared", "shared"] {
        std::fs::create_dir_all(root.join(dir))?;
    }
    std::fs::write(root.join("store/shared/file"), "right")?;
    std::fs::write(root.join("shared/file"), "wrong")?;
    symlink("store/pkg", root.join("alias"))?;
    symlink("../shared", root.join("store/pkg/dep"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias/dep", "../shared"), link("alias", "store/pkg")])?;
    assert_eq!(inputs.read(&root.join("alias/dep/file"), 1024)?, b"right");
    inputs.directory(&root.join("alias/dep"))?;
    inputs.seal()?;
    Ok(())
}
#[test]
fn cycles_undeclared_intermediates_and_invalid_declarations_poison_seal() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    symlink("b", root.join("a"))?;
    symlink("a", root.join("b"))?;
    std::fs::write(root.join("regular"), "data")?;
    for declarations in [
        vec![link("a", "b"), link("b", "a")],
        vec![link("a", "b")],
        vec![link("regular", "b")],
        vec![link("a", "wrong")],
        vec![link("a", "b"), link("a", "b")],
    ] {
        let mut inputs = reader(&root)?;
        assert!(inputs.declare_links(&declarations).is_err());
        assert!(inputs.seal().is_err());
    }
    Ok(())
}
#[test]
fn external_target_needs_its_own_permission() -> Result<()> {
    let project = tempfile::tempdir()?;
    let outside = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    let target = outside.path().canonicalize()?.join("file");
    std::fs::write(&target, "outside")?;
    symlink(&target, root.join("alias"))?;
    let declaration = DeclaredLink {
        path: "alias".into(),
        target: target.clone(),
    };
    assert!(
        reader(&root)?
            .declare_links(std::slice::from_ref(&declaration))
            .is_err()
    );
    let mut inputs = reader(&root)?;
    inputs.allow_file(&target)?;
    inputs.declare_links(&[declaration])?;
    assert_eq!(inputs.read(&root.join("alias"), 1024)?, b"outside");
    inputs.seal()?;
    Ok(())
}
#[test]
fn unit_without_declaration_cannot_reuse_alias_cache() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("file"), "data")?;
    symlink("file", root.join("alias"))?;
    let mut inputs = reader(&root)?;
    inputs.enter_unit(&"first".into())?;
    inputs.declare_links(&[link("alias", "file")])?;
    inputs.read(&root.join("alias"), 1024)?;
    inputs.leave_unit();
    inputs.enter_unit(&"second".into())?;
    assert!(inputs.read(&root.join("alias"), 1024).is_err());
    inputs.declare_links(&[link("alias", "file")])?;
    assert_eq!(inputs.read(&root.join("alias"), 1024)?, b"data");
    inputs.seal()?;
    Ok(())
}
#[test]
fn retarget_same_bytes_and_target_edits_fail_sealing() -> Result<()> {
    for retarget in [false, true] {
        let project = tempfile::tempdir()?;
        let root = project.path().canonicalize()?;
        std::fs::write(root.join("file"), "same")?;
        std::fs::write(root.join("other"), "same")?;
        symlink("file", root.join("alias"))?;
        let mut inputs = reader(&root)?;
        inputs.declare_links(&[link("alias", "file")])?;
        inputs.read(&root.join("alias"), 1024)?;
        if retarget {
            std::fs::remove_file(root.join("alias"))?;
            symlink("other", root.join("alias"))?;
        } else {
            std::fs::write(root.join("file"), "edit")?;
        }
        assert!(inputs.seal().is_err());
    }
    Ok(())
}
#[test]
fn dangling_target_creation_and_directory_changes_fail_sealing() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    symlink("missing", root.join("dangling"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("dangling", "missing")])?;
    assert!(!inputs.exists(&root.join("dangling"))?);
    std::fs::write(root.join("missing"), "created")?;
    assert!(inputs.seal().is_err());
    std::fs::create_dir(root.join("dir"))?;
    symlink("dir", root.join("alias"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias", "dir")])?;
    inputs.directory(&root.join("alias"))?;
    std::fs::write(root.join("dir/new"), "new")?;
    assert!(inputs.seal().is_err());
    Ok(())
}
#[test]
fn identity_only_alias_never_retains_target_content() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("secret"), "credential")?;
    symlink("secret", root.join("alias"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias", "secret")])?;
    inputs.digest(&root.join("alias"), 1024)?;
    assert!(inputs.read(&root.join("alias"), 1024).is_err());
    assert!(inputs.seal()?.blobs.is_empty());
    Ok(())
}

#[test]
fn parent_component_after_link_uses_resolved_directory() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::create_dir_all(root.join("store/pkg"))?;
    std::fs::write(root.join("store/file"), "physical")?;
    std::fs::write(root.join("file"), "lexical decoy")?;
    symlink("store/pkg", root.join("dir"))?;
    symlink("dir/../file", root.join("alias"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias", "dir/../file"), link("dir", "store/pkg")])?;
    assert_eq!(inputs.read(&root.join("alias"), 1024)?, b"physical");
    inputs.seal()?;
    Ok(())
}

#[test]
fn finite_chain_is_bounded_and_walk_can_be_cancelled() -> Result<()> {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("end"), "end")?;
    let mut declarations = Vec::new();
    for index in 0..41 {
        let target = if index == 40 {
            "end".to_string()
        } else {
            format!("l{}", index + 1)
        };
        symlink(&target, root.join(format!("l{index}")))?;
        declarations.push(link(&format!("l{index}"), &target));
    }
    assert!(reader(&root)?.declare_links(&declarations).is_err());
    let checks = Arc::new(AtomicUsize::new(0));
    let observed = checks.clone();
    let mut inputs = InputCaptureReader::new(
        &root,
        Arc::new(tempfile::tempdir()?),
        Instant::now(),
        Duration::from_secs(30),
        None,
        Some(Arc::new(move || {
            observed.fetch_add(1, Ordering::Relaxed) > 15
        })),
    )?;
    assert!(inputs.declare_links(&declarations).is_err());
    assert!(checks.load(Ordering::Relaxed) > 15);
    assert!(inputs.seal().is_err());
    Ok(())
}

#[test]
fn parent_lookup_cannot_skip_a_missing_or_non_directory_component() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("file"), "data")?;
    for (name, target) in [
        ("missing-parent", "missing/../file"),
        ("file-parent", "file/../file"),
    ] {
        symlink(target, root.join(name))?;
        assert!(reader(&root)?.declare_links(&[link(name, target)]).is_err());
    }
    Ok(())
}

#[test]
fn repeated_alias_reads_reuse_retained_proofs_without_exhausting_metadata() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("file"), "data")?;
    symlink("file", root.join("alias"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias", "file")])?;
    for _ in 0..2500 {
        assert_eq!(inputs.read(&root.join("alias"), 1024)?, b"data");
    }
    assert_eq!(inputs.seal()?.blobs.len(), 1);
    Ok(())
}

#[test]
fn directory_alias_to_root_is_finite_when_the_remaining_path_is_finite() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("file"), "data")?;
    symlink(".", root.join("alias"))?;
    let mut inputs = reader(&root)?;
    inputs.declare_links(&[link("alias", ".")])?;
    assert_eq!(inputs.read(&root.join("alias/alias/file"), 1024)?, b"data");
    inputs.directory(&root.join("alias"))?;
    inputs.seal()?;
    Ok(())
}
