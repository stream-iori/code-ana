//! Capture declaration controls separately from published sources and runtime resources.
use super::*;

pub(super) fn capture(
    root: &Path,
    manifest: &mut Manifest,
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    spool: &std::sync::Arc<tempfile::TempDir>,
    excludes: &globset::GlobSet,
    timing: (std::time::Instant, std::time::Duration),
) -> Result<()> {
    let mut directories = BTreeSet::new();
    for source in manifest.files.iter().filter(|s| s.language == "java") {
        let mut directory = Path::new(&source.path).parent();
        let mut depth = 0;
        while let Some(path) = directory {
            depth += 1;
            ensure!(
                depth <= 256,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::LombokConfigDepthBudget,
                    ""
                )
            );
            if !directories.insert(path.to_path_buf()) {
                break;
            }
            ensure!(
                directories.len() <= 200_000,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::LombokConfigEntryBudget,
                    ""
                )
            );
            directory = path.parent();
        }
    }
    let mut files = Vec::new();
    for directory in directories {
        let path = directory.join("lombok.config");
        let relative = path
            .to_str()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NonUtf8LombokConfig,
                "",
            ))?
            .to_owned();
        let physical = root.join(&path);
        if physical.try_exists()? {
            ensure!(
                !excludes.is_match(&relative),
                codeana_model::DomainError::new(codeana_model::ErrorCode::LombokConfigExcluded, "")
            );
            files.push((relative, physical));
        }
    }
    for (index, directory) in root.ancestors().skip(1).enumerate() {
        ensure!(
            index < 256,
            codeana_model::DomainError::new(codeana_model::ErrorCode::LombokConfigDepthBudget, "")
        );
        let physical = directory.join("lombok.config");
        if physical.try_exists()? {
            // Depth 1 is the immediate project parent. Workers only receive sealed bytes.
            files.push((
                format!(".code-ana/lombok-ancestors/{:06}/lombok.config", index + 1),
                physical,
            ));
        }
    }
    ensure!(
        files.len() <= 4096,
        codeana_model::DomainError::new(codeana_model::ErrorCode::LombokConfigFileBudget, "")
    );
    let mut configuration_bytes = 0;
    for (relative, physical) in files {
        ensure!(
            timing.0.elapsed() < timing.1,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CaptureTimeout, "")
        );
        let bytes = bounded_read(&physical, 1024 * 1024)?;
        configuration_bytes += bytes.len();
        ensure!(
            configuration_bytes <= 8 * 1024 * 1024,
            codeana_model::DomainError::new(codeana_model::ErrorCode::LombokConfigByteBudget, "")
        );
        add_captured(
            manifest,
            blobs,
            total,
            spool,
            relative.clone(),
            bytes,
            (
                String::new(),
                "JAVA_CONFIG".into(),
                String::new(),
                String::new(),
            ),
        )?;
        // A generic resource inventory may have captured this file earlier. Its hash must
        // agree; the dedicated control scope then provides shared semantic visibility.
        let unit = manifest
            .files
            .iter_mut()
            .find(|s| s.path == relative)
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::LombokConfigCapture,
                "",
            ))?;
        unit.module.clear();
        unit.language.clear();
        unit.scope = "JAVA_CONFIG".into();
        unit.world = "files".into();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> (tempfile::TempDir, PathBuf) {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().join("project");
        std::fs::create_dir_all(root.join(".code-ana")).unwrap();
        std::fs::create_dir_all(root.join("src/p")).unwrap();
        std::fs::write(root.join("src/p/A.java"), "class A {}").unwrap();
        std::fs::write(
            root.join(".code-ana/config.toml"),
            r#"schema_version=1
kind="project"
[discovery]
mode="explicit"
[scan]
include=["src/**/*.java"]
scopes=["MAIN"]
[languages.java]
enabled=true
[languages.java.analysis]
source_version="8"
classpath="none"
[languages.java.lombok]
mode="model"
[[modules]]
id="app"
root="."
language="java"
source_roots=[{path="src",scope="MAIN"}]
"#,
        )
        .unwrap();
        (directory, root)
    }
    #[test]
    fn ancestors_and_source_controls_are_sealed_independently_of_scan_include() {
        let (directory, root) = fixture();
        let parent = b"lombok.log.fieldName=parentLogger\n";
        let local = b"config.stopBubbling=true\n";
        std::fs::write(directory.path().join("lombok.config"), parent).unwrap();
        std::fs::write(root.join("src/p/lombok.config"), local).unwrap();
        let captured = super::super::capture(&root, &Request::default()).unwrap();
        let configs: BTreeMap<_, _> = captured
            .manifest
            .files
            .iter()
            .filter(|s| s.scope == "JAVA_CONFIG")
            .map(|s| (s.path.clone(), s.clone()))
            .collect();
        assert_eq!(configs.len(), 2);
        assert_eq!(
            configs[".code-ana/lombok-ancestors/000001/lombok.config"].hash,
            hash(parent)
        );
        let unit = &configs["src/p/lombok.config"];
        assert_eq!(
            (&*unit.module, &*unit.world, &*unit.language),
            ("", "files", "")
        );
        std::fs::write(root.join("src/p/lombok.config"), b"changed").unwrap();
        assert_eq!(captured.blobs[&unit.hash].read(1024).unwrap(), local);
        let changed = super::super::capture(&root, &Request::default()).unwrap();
        assert_ne!(
            changed
                .manifest
                .files
                .iter()
                .find(|s| s.path == unit.path)
                .unwrap()
                .hash,
            unit.hash
        );
    }
    #[test]
    fn disabled_model_does_not_discover_ancestor_controls() {
        let (directory, root) = fixture();
        std::fs::write(directory.path().join("lombok.config"), b"unknown=true").unwrap();
        let config = root.join(".code-ana/config.toml");
        std::fs::write(
            &config,
            std::fs::read_to_string(&config)
                .unwrap()
                .replace("mode=\"model\"", "mode=\"off\""),
        )
        .unwrap();
        let captured = super::super::capture(&root, &Request::default()).unwrap();
        assert!(
            captured
                .manifest
                .files
                .iter()
                .all(|s| s.scope != "JAVA_CONFIG")
        );
    }
    #[test]
    fn configuration_file_budget_and_exclusion_are_hard_failures() {
        let (_directory, root) = fixture();
        std::fs::write(root.join("lombok.config"), vec![b'#'; 1024 * 1024 + 1]).unwrap();
        let error = super::super::capture(&root, &Request::default())
            .err()
            .unwrap()
            .to_string();
        assert!(error.contains("INPUT_BYTE_BUDGET"), "{error}");
        std::fs::write(root.join("lombok.config"), b"config.stopBubbling=true").unwrap();
        let config = root.join(".code-ana/config.toml");
        std::fs::write(
            &config,
            std::fs::read_to_string(&config)
                .unwrap()
                .replace("[scan]", "[scan]\nexclude=[\"lombok.config\"]"),
        )
        .unwrap();
        let error = super::super::capture(&root, &Request::default())
            .err()
            .unwrap()
            .to_string();
        assert!(error.contains("LOMBOK_CONFIG_EXCLUDED"), "{error}");
    }
}
