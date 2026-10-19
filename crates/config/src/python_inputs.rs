//! Python indexing consumes an explicit, hashed inventory. No interpreter runs.
use super::*;
use codeana_model::python::{InputRoot, Inputs};
use std::sync::Arc;

pub fn source_path(root: &InputRoot, relative: &str) -> String {
    if root.publish {
        if root.path == "." {
            relative.into()
        } else {
            format!("{}/{relative}", root.path)
        }
    } else {
        format!(".code-ana/python-dependencies/{}/{relative}", root.id)
    }
}

pub fn discover(project: &Path, config: &ProjectConfig, semantic: bool) -> Result<Option<Inputs>> {
    let Some(python) = config
        .languages
        .python
        .as_ref()
        .filter(|p| p.enabled == Some(true))
    else {
        return Ok(None);
    };
    if !semantic {
        return Ok(None);
    }
    let path = python
        .discovery
        .inputs_manifest
        .as_deref()
        .context("PYTHON_INPUTS_REQUIRED: prepare an explicit inputs_manifest")?;
    ensure!(safe_path(path), "PYTHON_MANIFEST_PATH");
    let file = project.join(path);
    ensure!(
        !file.is_symlink() && file.canonicalize()?.starts_with(project),
        "PYTHON_MANIFEST_PATH"
    );
    let inputs: Inputs = serde_json::from_slice(&bounded_read(&file, 32 << 20)?)?;
    inputs.validate().map_err(anyhow::Error::msg)?;
    Ok(Some(inputs))
}

pub(super) fn capture(
    project: &Path,
    effective: &Effective,
    manifest: &mut Manifest,
    blobs: &mut BTreeMap<String, CapturedBlob>,
    total: &mut usize,
    spool: &Arc<tempfile::TempDir>,
) -> Result<()> {
    let Some(inputs) = &effective.python_inputs else {
        return Ok(());
    };
    let config = &effective.config;
    let includes = glob(&config.scan.include)?;
    let excludes = glob(&config.scan.exclude)?;
    let text_only = glob(&config.scan.text_only)?;
    let module_filters = config
        .modules
        .iter()
        .filter(|m| m.language == "python")
        .map(|m| {
            Ok((
                m.id.clone(),
                (
                    glob(&m.scan.include)?,
                    glob(&m.scan.exclude)?,
                    glob(&m.scan.text_only)?,
                    m.scan.clone(),
                ),
            ))
        })
        .collect::<Result<BTreeMap<_, _>>>()?;
    let inventory = config
        .languages
        .python
        .as_ref()
        .unwrap()
        .discovery
        .inputs_manifest
        .as_ref()
        .unwrap();
    let bytes = bounded_read(&project.join(inventory), 32 << 20)?;
    let current: Inputs = serde_json::from_slice(&bytes)?;
    ensure!(
        digest(&current) == digest(inputs),
        "PYTHON_MANIFEST_CHANGED"
    );
    add_captured(
        manifest,
        blobs,
        total,
        spool,
        inventory.clone(),
        bytes,
        (
            String::new(),
            "CONTROL".into(),
            String::new(),
            String::new(),
        ),
    )?;
    for control in &inputs.controls {
        ensure!(
            !excludes.is_match(&control.path),
            "CONTROL_INPUT_EXCLUDED: {}",
            control.path
        );
        let path = project.join(&control.path);
        let mut component = project.to_path_buf();
        for part in Path::new(&control.path).components() {
            component.push(part);
            ensure!(!component.is_symlink(), "PYTHON_CONTROL_SYMLINK");
        }
        ensure!(
            path.canonicalize()?.starts_with(project),
            "PYTHON_CONTROL_ESCAPE"
        );
        let bytes = bounded_read(&path, 8 << 20)?;
        ensure!(
            bytes.len() as u64 == control.bytes && hash(&bytes) == control.sha256,
            "PYTHON_CONTROL_STALE: {}",
            control.path
        );
        add_captured(
            manifest,
            blobs,
            total,
            spool,
            control.path.clone(),
            bytes,
            (
                String::new(),
                "CONTROL".into(),
                String::new(),
                String::new(),
            ),
        )?;
        // A text/resource scan may have captured the same unowned file first.
        // Preserve its bytes and identity while recording its control role.
        for unit in manifest
            .files
            .iter_mut()
            .filter(|u| u.path == control.path && u.module.is_empty() && u.language.is_empty())
        {
            unit.scope = "CONTROL".into();
        }
    }
    for input_root in &inputs.roots {
        let directory = project.join(&input_root.path);
        ensure!(!directory.is_symlink(), "PYTHON_ROOT_SYMLINK");
        let directory = directory.canonicalize()?;
        ensure!(
            !input_root.publish || directory.starts_with(project),
            "PYTHON_ROOT_ESCAPE"
        );
        for file in &input_root.files {
            let live = directory.join(&file.path);
            // Reject every symlink component, not only the final file.
            let mut component = directory.clone();
            for part in Path::new(&file.path).components() {
                component.push(part);
                ensure!(!component.is_symlink(), "PYTHON_SOURCE_SYMLINK");
            }
            ensure!(
                live.canonicalize()?.starts_with(&directory),
                "PYTHON_SOURCE_ESCAPE"
            );
            let bytes = bounded_read(&live, MAX_FRAME)?;
            ensure!(
                bytes.len() as u64 == file.bytes && hash(&bytes) == file.sha256,
                "PYTHON_INPUTS_STALE: {}",
                file.path
            );
            let source = source_path(input_root, &file.path);
            ensure!(safe_path(&source), "PYTHON_SOURCE_PATH");
            let selected = input_root.publish
                && (config.scan.include.is_empty() || includes.is_match(&source))
                && !excludes.is_match(&source)
                && !text_only.is_match(&source);
            let selected = selected
                && input_root
                    .module
                    .as_ref()
                    .and_then(|id| module_filters.get(id))
                    .is_none_or(|(include, exclude, text_only, scan)| {
                        (scan.include.is_empty() || include.is_match(&source))
                            && !exclude.is_match(&source)
                            && !text_only.is_match(&source)
                    });
            if !blobs.contains_key(&file.sha256) {
                *total += bytes.len();
                ensure!(*total <= MAX_CAPTURE_BYTES, "PYTHON_CAPTURE_BUDGET");
                blobs.insert(file.sha256.clone(), CapturedBlob::bytes(spool, &bytes)?);
            }
            manifest
                .files
                .retain(|f| f.path != source || f.language == "python");
            for context in inputs
                .contexts
                .iter()
                .filter(|c| c.roots.contains(&input_root.id))
            {
                let unit = SourceUnit {
                    path: source.clone(),
                    hash: file.sha256.clone(),
                    size: file.bytes,
                    module: input_root
                        .module
                        .clone()
                        .unwrap_or_else(|| context.module.clone()),
                    world: context.world(&inputs.roots),
                    scope: context.scope.clone(),
                    language: "python".into(),
                    auxiliary: !selected
                        || !context.publishes(input_root)
                        || !config.scan.scopes.contains(&context.scope)
                        || input_root
                            .module
                            .as_ref()
                            .and_then(|id| module_filters.get(id))
                            .is_some_and(|(_, _, _, scan)| {
                                !scan.scopes.is_empty() && !scan.scopes.contains(&context.scope)
                            })
                        || file.path.ends_with("py.typed"),
                };
                if let Some(existing) = manifest
                    .files
                    .iter()
                    .find(|f| f.path == unit.path && f.world == unit.world)
                {
                    ensure!(
                        existing.hash == unit.hash && existing.auxiliary == unit.auxiliary,
                        "PYTHON_SOURCE_IDENTITY_CONFLICT"
                    );
                } else {
                    manifest.files.push(unit);
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::python::{Context as PythonContext, InputFile};

    fn project() -> (tempfile::TempDir, PathBuf, Inputs) {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().canonicalize().unwrap();
        std::fs::create_dir(root.join(".code-ana")).unwrap();
        std::fs::create_dir(root.join("src")).unwrap();
        let source = b"def f():\n    return 1\nf()\n";
        std::fs::write(root.join("src/main.py"), source).unwrap();
        std::fs::write(root.join(".code-ana/config.toml"), "schema_version=1\nkind='project'\n[languages.python]\nenabled=true\n[languages.python.discovery]\ninputs_manifest='.code-ana/python-inputs.json'\n").unwrap();
        let inputs = Inputs {
            module_metadata: Default::default(),
            schema_version: 1,
            controls: vec![],
            metadata: None,
            roots: vec![InputRoot {
                module: None,
                id: "app".into(),
                path: "src".into(),
                publish: true,
                files: vec![InputFile {
                    path: "main.py".into(),
                    sha256: hash(source),
                    bytes: source.len() as u64,
                }],
            }],
            contexts: vec![PythonContext {
                publish_roots: None,
                gaps: vec![],
                id: "main".into(),
                module: "app".into(),
                scope: "MAIN".into(),
                python_version: "3.12".into(),
                platform: "darwin".into(),
                roots: vec!["app".into()],
            }],
        };
        std::fs::write(
            root.join(".code-ana/python-inputs.json"),
            serde_json::to_vec(&inputs).unwrap(),
        )
        .unwrap();
        (temp, root, inputs)
    }

    #[test]
    fn optional_controls_are_frozen_and_stale_metadata_is_rejected() {
        let (_temp, root, mut inputs) = project();
        let metadata = b"[project]\nname='fixture'\n";
        std::fs::write(root.join("pyproject.toml"), metadata).unwrap();
        inputs.schema_version = 2;
        inputs.controls.push(codeana_model::python::Control {
            path: "pyproject.toml".into(),
            sha256: hash(metadata),
            bytes: metadata.len() as u64,
        });
        std::fs::write(
            root.join(".code-ana/python-inputs.json"),
            serde_json::to_vec(&inputs).unwrap(),
        )
        .unwrap();
        let captured = crate::capture(&root, &Request::default()).unwrap();
        assert!(
            captured
                .manifest
                .files
                .iter()
                .any(|f| f.path == "pyproject.toml" && f.scope == "CONTROL")
        );
        std::fs::write(root.join("pyproject.toml"), "changed").unwrap();
        assert_eq!(
            captured.blobs[&hash(metadata)].read(MAX_FRAME).unwrap(),
            metadata
        );
        assert!(
            format!(
                "{:#}",
                crate::capture(&root, &Request::default()).unwrap_err()
            )
            .contains("PYTHON_CONTROL_STALE")
        );
        inputs.schema_version = 1;
        assert_eq!(inputs.validate().unwrap_err(), "PYTHON_INPUT_SCHEMA");
    }

    #[test]
    fn captured_bytes_survive_live_changes_and_stale_inventory_is_rejected() {
        let (_temp, root, _) = project();
        let captured = crate::capture(&root, &Request::default()).unwrap();
        let source = captured
            .manifest
            .files
            .iter()
            .find(|f| f.language == "python")
            .unwrap();
        assert_eq!(source.path, "src/main.py");
        assert!(!source.auxiliary);
        std::fs::write(root.join("src/main.py"), "def changed(): pass\n").unwrap();
        assert_eq!(
            hash(captured.blobs[&source.hash].read(MAX_FRAME).unwrap()),
            source.hash
        );
        assert!(
            format!(
                "{:#}",
                crate::capture(&root, &Request::default()).unwrap_err()
            )
            .contains("PYTHON_INPUTS_STALE")
        );
    }

    #[test]
    fn worlds_are_distinct_and_root_order_changes_world() {
        let (_temp, root, mut inputs) = project();
        let mut second = inputs.contexts[0].clone();
        second.id = "second".into();
        inputs.contexts.push(second);
        std::fs::write(
            root.join(".code-ana/python-inputs.json"),
            serde_json::to_vec(&inputs).unwrap(),
        )
        .unwrap();
        let captured = crate::capture(&root, &Request::default()).unwrap();
        let sources = captured
            .manifest
            .files
            .iter()
            .filter(|f| f.language == "python")
            .collect::<Vec<_>>();
        assert_eq!(sources.len(), 2);
        assert_ne!(sources[0].world, sources[1].world);
        let mut context = inputs.contexts[0].clone();
        context.roots.push("dep".into());
        let before = context.world(&inputs.roots);
        context.roots.reverse();
        assert_ne!(before, context.world(&inputs.roots));
    }

    #[test]
    fn nested_symlink_is_rejected() {
        let (_temp, root, mut inputs) = project();
        std::os::unix::fs::symlink(root.join("src"), root.join("src/link")).unwrap();
        inputs.roots[0].files[0].path = "link/main.py".into();
        std::fs::write(
            root.join(".code-ana/python-inputs.json"),
            serde_json::to_vec(&inputs).unwrap(),
        )
        .unwrap();
        assert!(
            format!(
                "{:#}",
                crate::capture(&root, &Request::default()).unwrap_err()
            )
            .contains("SYMLINK")
        );
    }
}
