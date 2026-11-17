use anyhow::Result;
use codeana_config::{EntryKind, InputCaptureReader, ResolutionObservation};
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
#[test]
fn file_directory_and_absence_are_distinct_without_reading_contents() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("file"), b"content")?;
    std::fs::create_dir(root.join("directory"))?;
    let mut capture = reader(&root)?;
    assert_eq!(
        capture.entry_kind(&root.join("file"))?,
        Some(EntryKind::File)
    );
    assert_eq!(
        capture.entry_kind(&root.join("directory"))?,
        Some(EntryKind::Directory)
    );
    assert_eq!(capture.entry_kind(&root.join("missing"))?, None);
    assert_eq!(capture.entry_kind(&root.join("file/child"))?, None);
    assert_eq!(capture.entry_kind(&root)?, Some(EntryKind::Directory));
    let sealed = capture.seal()?;
    assert!(sealed.blobs.is_empty());
    assert_eq!(
        sealed
            .observations
            .iter()
            .filter(|observation| matches!(observation, ResolutionObservation::Entry { .. }))
            .count(),
        5
    );
    Ok(())
}
#[test]
fn changing_kind_or_creating_a_missing_entry_refuses_sealing() -> Result<()> {
    for create_missing in [false, true] {
        let project = tempfile::tempdir()?;
        let root = project.path().canonicalize()?;
        if !create_missing {
            std::fs::write(root.join("path"), b"file")?;
        }
        let mut capture = reader(&root)?;
        capture.entry_kind(&root.join("path"))?;
        if !create_missing {
            std::fs::remove_file(root.join("path"))?;
        }
        std::fs::create_dir(root.join("path"))?;
        assert!(
            capture
                .seal()
                .err()
                .unwrap()
                .to_string()
                .contains("CAPTURE_ENTRY_KIND_CHANGED")
        );
    }
    Ok(())
}
#[test]
fn presence_does_not_observe_unrelated_directory_children_but_enumeration_does() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::create_dir(root.join("directory"))?;
    let mut presence = reader(&root)?;
    presence.entry_kind(&root.join("directory"))?;
    std::fs::write(root.join("directory/one"), b"one")?;
    presence.seal()?;
    let mut listing = reader(&root)?;
    assert_eq!(
        listing.directory_view(&root.join("directory"))?,
        [("one".into(), "file".into())]
    );
    std::fs::write(root.join("directory/two"), b"two")?;
    assert!(listing.seal().is_err());
    Ok(())
}
#[test]
fn typed_lookups_do_not_follow_undeclared_links_or_borrow_other_unit_permissions() -> Result<()> {
    use codeana_config::DeclaredLink;
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    std::fs::write(root.join("file"), b"file")?;
    std::os::unix::fs::symlink("file", root.join("alias"))?;
    let mut capture = reader(&root)?;
    assert!(capture.entry_kind(&root.join("alias")).is_err());
    let mut capture = reader(&root)?;
    capture.enter_unit(&"one".into())?;
    capture.declare_links(&[DeclaredLink {
        path: "alias".into(),
        target: "file".into(),
    }])?;
    assert_eq!(
        capture.entry_kind(&root.join("alias"))?,
        Some(EntryKind::File)
    );
    capture.leave_unit();
    capture.enter_unit(&"two".into())?;
    assert!(capture.entry_kind(&root.join("alias")).is_err());
    Ok(())
}

#[test]
fn missing_external_parents_can_be_observed_without_authorizing_sibling_contents() -> Result<()> {
    let project = tempfile::tempdir()?;
    let root = project.path().canonicalize()?;
    let external = tempfile::tempdir()?;
    let outside = external.path().canonicalize()?;
    std::fs::write(outside.join("secret"), b"outside")?;
    let missing = outside.join("not-created/child/package.json");
    let mut capture = reader(&root)?;
    capture.allow_lookup(&missing)?;
    assert_eq!(capture.entry_kind(&missing)?, None);
    assert!(capture.file(&outside.join("secret"), 1024).is_err());
    capture.seal()?;
    let mut capture = reader(&root)?;
    capture.allow_lookup(&missing)?;
    capture.entry_kind(&missing)?;
    std::fs::create_dir_all(missing.parent().unwrap())?;
    std::fs::write(&missing, b"created")?;
    assert!(capture.seal().is_err());
    Ok(())
}
