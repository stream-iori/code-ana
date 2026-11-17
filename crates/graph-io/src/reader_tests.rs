use super::*;
use crate::{
    delivery::Encoder,
    stream::{GraphStream, GraphStreamEvent},
};
use prost::Message;
fn archive(root: &Path, name: &str, rows: &[Vec<u8>]) -> Descriptor {
    let mut bytes = archive::MAGIC.to_vec();
    for row in rows {
        bytes.extend_from_slice(&(row.len() as u32).to_be_bytes());
        bytes.extend_from_slice(row);
    }
    let path = root.join(name);
    std::fs::write(&path, &bytes).unwrap();
    Descriptor {
        format: "GRA1".into(),
        path,
        count: rows.len() as u64,
        bytes: bytes.len() as u64,
        sha256: format!("{:x}", Sha256::digest(&bytes)),
    }
}
fn row() -> Row {
    Row {
        table: 1,
        world: "w".into(),
        cells: vec![],
    }
}
#[test]
fn original_sha_is_checked_without_reencoding_and_owned_file_is_released() {
    let root = tempfile::tempdir().unwrap();
    // Both field orders are legal protobuf. Original-byte identity must remain distinct.
    let raw = vec![26, 1, b'w', 8, 1];
    assert_ne!(raw, row().encode_to_vec());
    let d = archive(root.path(), "original.rows", &[raw]);
    let mut emitted = vec![];
    let verified = read_descriptor(
        &d,
        OutputLimits::default(),
        root.path(),
        true,
        None,
        || Ok(()),
        |r| {
            emitted.push(r);
            Ok(())
        },
        || Ok(()),
    )
    .unwrap();
    assert_eq!(verified.count(), 1);
    assert_eq!(verified.bytes(), d.bytes);
    assert_eq!(verified.checksum(), d.sha256);
    assert_eq!(emitted, vec![row()]);
    assert!(!d.path.exists());
    let canonical = archive(root.path(), "canonical.rows", &[row().encode_to_vec()]);
    assert_ne!(canonical.sha256, verified.checksum());
}
#[test]
fn corruption_count_tail_and_decode_amplification_never_release_the_input() {
    let root = tempfile::tempdir().unwrap();
    for fault in 0..7 {
        let mut d = archive(
            root.path(),
            &format!("{fault}.rows"),
            &[row().encode_to_vec()],
        );
        let mut bytes = std::fs::read(&d.path).unwrap();
        match fault {
            0 => bytes[0] = b'X',
            1 => d.count = 0,
            2 => d.count = 2,
            3 => bytes.push(0),
            4 => {
                bytes.pop();
            }
            5 => d.sha256 = "wrong".into(),
            _ => {
                bytes = archive::MAGIC.to_vec();
                bytes.extend_from_slice(&((archive::RECORD_LIMIT + 1) as u32).to_be_bytes());
            }
        }
        std::fs::write(&d.path, &bytes).unwrap();
        d.bytes = bytes.len() as u64;
        if fault != 5 {
            d.sha256 = format!("{:x}", Sha256::digest(&bytes));
        }
        assert!(
            read_descriptor(
                &d,
                OutputLimits::default(),
                root.path(),
                true,
                None,
                || Ok(()),
                |_| Ok(()),
                || Ok(())
            )
            .is_err(),
            "fault={fault}"
        );
        assert!(d.path.exists(), "failed validation must preserve the input");
    }
    let raw = Row {
        table: 1,
        world: "w".into(),
        cells: vec![crate::graph::Cell { value: None }; 35],
    }
    .encode_to_vec();
    let d = archive(root.path(), "too-many-cells.rows", &[raw]);
    assert!(
        read_descriptor(
            &d,
            OutputLimits::default(),
            root.path(),
            true,
            None,
            || Ok(()),
            |_| panic!("bad row cannot be emitted"),
            || Ok(())
        )
        .is_err()
    );
    assert!(d.path.exists());
}
#[test]
fn symlinks_parent_escape_nonfiles_and_outside_roots_are_rejected() {
    use std::os::unix::fs::symlink;
    let root = tempfile::tempdir().unwrap();
    let other = tempfile::tempdir().unwrap();
    let d = archive(root.path(), "valid.rows", &[row().encode_to_vec()]);
    symlink(&d.path, root.path().join("link.rows")).unwrap();
    symlink(root.path(), root.path().join("parent-link")).unwrap();
    let outside = archive(other.path(), "outside.rows", &[row().encode_to_vec()]);
    for path in [
        root.path().join("link.rows"),
        root.path().join("parent-link/valid.rows"),
        root.path().join("../escape"),
        root.path().to_path_buf(),
        outside.path.clone(),
    ] {
        let mut bad = d.clone();
        bad.path = path;
        assert!(
            read_descriptor(
                &bad,
                OutputLimits::default(),
                root.path(),
                true,
                None,
                || panic!("bad path cannot begin"),
                |_| Ok(()),
                || Ok(())
            )
            .is_err()
        );
        assert!(d.path.exists());
        assert!(outside.path.exists());
    }
}
#[test]
fn a_replaced_path_is_preserved_instead_of_deleting_an_unowned_file() {
    let root = tempfile::tempdir().unwrap();
    let d = archive(root.path(), "owned.rows", &[row().encode_to_vec()]);
    let original = root.path().join("original.rows");
    let emitted = std::cell::Cell::new(false);
    let replaced = std::cell::Cell::new(false);
    let result = read_descriptor(
        &d,
        OutputLimits::default(),
        root.path(),
        true,
        None,
        || Ok(()),
        |_| {
            emitted.set(true);
            Ok(())
        },
        || {
            if emitted.get() && !replaced.replace(true) {
                std::fs::rename(&d.path, &original)?;
                std::fs::write(&d.path, b"unrelated input")?;
            }
            Ok(())
        },
    );
    assert!(
        result
            .unwrap_err()
            .to_string()
            .contains("GRAPH_ARCHIVE_RELEASE")
    );
    assert_eq!(std::fs::read(&d.path).unwrap(), b"unrelated input");
    assert!(original.exists());
}
#[test]
fn descriptor_path_amplification_is_rejected_before_filesystem_traversal() {
    let root = tempfile::tempdir().unwrap();
    let d = archive(root.path(), "path.rows", &[row().encode_to_vec()]);
    for (relative, message) in [
        ("a/".repeat(65) + "graph.rows", "depth"),
        ("x".repeat(16 * 1024 + 1), "byte budget"),
    ] {
        let mut bad = d.clone();
        bad.path = root.path().join(relative);
        let error = read_descriptor(
            &bad,
            OutputLimits::default(),
            root.path(),
            true,
            None,
            || panic!("path admission precedes Begin"),
            |_| Ok(()),
            || Ok(()),
        )
        .unwrap_err();
        assert!(error.to_string().contains(message), "{error:#}");
        assert!(d.path.exists());
    }
}
#[test]
fn budget_and_consumer_cancellation_do_not_certify_or_release_the_file() {
    let root = tempfile::tempdir().unwrap();
    let d = archive(root.path(), "input.rows", &[row().encode_to_vec()]);
    let budget = AnalysisContext::new(
        codeana_model::AnalysisLimits {
            max_payload_bytes: 1,
            ..Default::default()
        },
        None,
    )
    .unwrap();
    assert!(
        read_descriptor(
            &d,
            OutputLimits::default(),
            root.path(),
            true,
            Some(&budget),
            || panic!("budget must be admitted before Begin"),
            |_| Ok(()),
            || Ok(())
        )
        .is_err()
    );
    assert_eq!(budget.usage().payload_bytes, 0);
    assert!(
        read_descriptor(
            &d,
            OutputLimits::default(),
            root.path(),
            true,
            None,
            || Ok(()),
            |_| anyhow::bail!("CONSUMER_CANCELLED"),
            || Ok(())
        )
        .is_err()
    );
    assert!(d.path.exists());
}
#[tokio::test]
async fn late_sha_error_can_emit_provisional_batches_but_never_verified_end() {
    let root = tempfile::tempdir().unwrap();
    let root_path = root.path().to_path_buf();
    let mut d = archive(root.path(), "late.rows", &vec![row().encode_to_vec(); 100]);
    d.sha256 = "wrong".into();
    let path = d.path.clone();
    let (tx, mut stream) = GraphStream::channel();
    let work = tokio::task::spawn_blocking(move || -> Result<()> {
        let mut encoder = Encoder::new(tx, OutputLimits::default(), None);
        read_descriptor(
            &d,
            OutputLimits::default(),
            &root_path,
            true,
            None,
            || Ok(()),
            |r| encoder.push(r),
            || Ok(()),
        )?;
        encoder.finish()?;
        Ok(())
    });
    let mut rows = 0;
    while let Some(item) = stream.recv().await {
        match item.event {
            GraphStreamEvent::Batch(batch) => rows += batch.len(),
            GraphStreamEvent::VerifiedEnd(_) => panic!("unverified EOF cannot certify"),
            GraphStreamEvent::Begin { .. } => panic!("row reader cannot manufacture Begin"),
        }
    }
    assert!(work.await.unwrap().is_err());
    assert_eq!(rows, 64);
    assert!(path.exists());
}
