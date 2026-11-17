//! Content identity and context ownership are independent: one blob, many world/path views.
use super::*;

/// Unit-local registration already checked role consistency. The task FILES view
/// publishes a shared world/path if any independent unit publishes it.
pub(crate) fn merge_view(manifest: &mut Manifest, unit: &SourceUnit) -> Result<()> {
    if let Some(previous) = manifest
        .files
        .iter_mut()
        .find(|s| s.path == unit.path && s.world == unit.world)
    {
        ensure!(
            previous.hash == unit.hash && previous.size == unit.size,
            DomainError::new(ErrorCode::CaptureContentConflict, &unit.path)
        );
        if previous.module.is_empty()
            && previous.language.is_empty()
            && previous.scope == "RESOURCE"
            && unit.module.is_empty()
            && unit.language.is_empty()
            && matches!(
                unit.scope.as_str(),
                "CONTROL" | "PREPARATION" | "JAVA_CONFIG" | "DEPENDENCY"
            )
        {
            *previous = unit.clone();
            return Ok(());
        }
        let mut compared = unit.clone();
        compared.auxiliary = previous.auxiliary;
        ensure!(
            previous == &compared,
            DomainError::new(ErrorCode::CaptureOwnerConflict, &unit.path)
        );
        previous.auxiliary &= unit.auxiliary;
        Ok(())
    } else {
        register(manifest, unit.clone())
    }
}

pub(crate) fn register(manifest: &mut Manifest, unit: SourceUnit) -> Result<()> {
    ensure!(
        safe_path(&unit.path),
        DomainError::new(ErrorCode::UnsafeCapturePath, &unit.path)
    );
    for previous in manifest.files.iter().filter(|s| s.path == unit.path) {
        ensure!(
            previous.hash == unit.hash && previous.size == unit.size,
            DomainError::new(ErrorCode::CaptureContentConflict, &unit.path)
        );
        if previous.world == unit.world {
            ensure!(
                previous == &unit,
                DomainError::new(ErrorCode::CaptureOwnerConflict, &unit.path)
            );
            return Ok(());
        }
    }
    // Generic FILES discovery is superseded by an explicit semantic view of the
    // same bytes. Other semantic worlds, including auxiliary views, stay intact.
    if !unit.language.is_empty() || !unit.module.is_empty() {
        manifest
            .files
            .retain(|s| s.path != unit.path || !s.language.is_empty() || !s.module.is_empty());
    } else if manifest
        .files
        .iter()
        .any(|s| s.path == unit.path && (!s.language.is_empty() || !s.module.is_empty()))
    {
        return Ok(());
    }
    ensure!(
        manifest.files.len() < 10_000,
        DomainError::new(ErrorCode::CaptureReferenceBudget, "")
    );
    manifest.files.push(unit);
    Ok(())
}

pub(crate) fn store(
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    spool: &std::sync::Arc<tempfile::TempDir>,
    bytes: &[u8],
) -> Result<String> {
    let digest = hash(bytes);
    if !blobs.contains_key(&digest) {
        let next = total.checked_add(bytes.len()).context("CAPTURE_BUDGET")?;
        ensure!(next <= MAX_CAPTURE_BYTES, "CAPTURE_BUDGET");
        let blob = CapturedBlob::bytes(spool, bytes)?;
        blobs.insert(digest.clone(), blob);
        *total = next;
    }
    Ok(digest)
}

pub(crate) fn retain_blob(
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    digest: String,
    blob: CapturedBlob,
    size: usize,
) -> Result<()> {
    if let std::collections::btree_map::Entry::Vacant(entry) = blobs.entry(digest) {
        let next = total.checked_add(size).context("CAPTURE_BUDGET")?;
        ensure!(next <= MAX_CAPTURE_BYTES, "CAPTURE_BUDGET");
        entry.insert(blob);
        *total = next;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn source(world: &str, auxiliary: bool) -> SourceUnit {
        SourceUnit {
            path: "src/shared.py".into(),
            hash: hash(b"shared"),
            size: 6,
            module: "app".into(),
            world: world.into(),
            scope: "MAIN".into(),
            language: "python".into(),
            auxiliary,
        }
    }
    #[test]
    fn a_unit_control_promotes_only_the_generic_workspace_placeholder() -> Result<()> {
        let mut resource = source("files", false);
        resource.module.clear();
        resource.language.clear();
        resource.scope = "RESOURCE".into();
        let mut control = resource.clone();
        control.scope = "CONTROL".into();
        let mut manifest = Manifest {
            files: vec![resource],
        };
        merge_view(&mut manifest, &control)?;
        assert_eq!(manifest.files, [control.clone()]);
        let mut incompatible = control;
        incompatible.scope = "PREPARATION".into();
        assert!(merge_view(&mut manifest, &incompatible).is_err());
        Ok(())
    }
    #[test]
    fn independent_views_merge_publication_roles_without_relaxing_local_registration() -> Result<()>
    {
        let mut local = Manifest::default();
        register(&mut local, source("shared", true))?;
        assert!(register(&mut local, source("shared", false)).is_err());
        let mut global = Manifest::default();
        merge_view(&mut global, &local.files[0])?;
        merge_view(&mut global, &source("shared", false))?;
        assert_eq!(global.files, [source("shared", false)]);
        merge_view(&mut global, &source("shared", true))?;
        assert!(!global.files[0].auxiliary);
        let mut conflict = source("shared", false);
        conflict.module = "different".into();
        assert!(merge_view(&mut global, &conflict).is_err());
        Ok(())
    }
    #[test]
    fn distinct_worlds_keep_their_own_roles_but_share_content() -> Result<()> {
        let spool = std::sync::Arc::new(tempfile::tempdir()?);
        let mut blobs = BTreeMap::new();
        let mut total = 0;
        let mut manifest = Manifest::default();
        for (world, auxiliary) in [("a", false), ("b", true), ("c", false)] {
            store(&mut blobs, &mut total, &spool, b"shared")?;
            register(&mut manifest, source(world, auxiliary))?;
        }
        assert_eq!(blobs.len(), 1);
        assert_eq!(total, 6);
        assert_eq!(manifest.files.len(), 3);
        assert!(manifest.files[1].auxiliary);
        register(&mut manifest, source("a", false))?;
        assert_eq!(manifest.files.len(), 3);
        Ok(())
    }
    #[test]
    fn same_file_content_and_same_world_ownership_cannot_change() {
        let mut manifest = Manifest::default();
        register(&mut manifest, source("a", false)).unwrap();
        for mut changed in [source("a", true), source("b", false), source("a", false)] {
            if changed.world == "b" {
                changed.hash = hash(b"edited");
            } else if !changed.auxiliary {
                changed.module = "other".into();
            }
            assert!(register(&mut manifest, changed).is_err());
            assert_eq!(manifest.files, [source("a", false)]);
        }
    }
}
