use anyhow::Result;
use codeana_config::{UnitInput, capture, resolve, resolve_files};
use codeana_model::{Request, SourceLanguage, typescript as ts};
use serde_json::json;
use std::path::{Path, PathBuf};
fn fixture() -> Result<(tempfile::TempDir, PathBuf, ts::Inputs)> {
    let temp = tempfile::tempdir()?;
    let root = temp.path().canonicalize()?;
    std::fs::create_dir(root.join(".code-ana"))?;
    std::fs::create_dir(root.join("src"))?;
    let files = [
        (
            "src/a.ts",
            "export const a=1;",
            Some(SourceLanguage::TypeScript),
        ),
        (
            "src/b.js",
            "export const b=2;",
            Some(SourceLanguage::JavaScript),
        ),
        (
            "tsconfig.json",
            "{\"files\":[\"src/a.ts\",\"src/b.js\"]}",
            None,
        ),
    ];
    let mut records = Vec::new();
    for (name, text, language) in files {
        std::fs::write(root.join(name), text)?;
        let publish = language.is_some();
        records.push(json!({"path":root.join(name),"sha256":codeana_model::hash(text),"bytes":text.len(),"role":if publish {"publish"} else {"control"},"language":language,"module":publish.then_some("app"),"scope":publish.then_some("MAIN"),"world":publish.then_some("typescript/main")}));
    }
    let case_sensitive =
        codeana_input_io::case_sensitive_path(&root.join("tsconfig.json"), |_, _| Ok(()))?;
    let inputs: ts::Inputs = serde_json::from_value(
        json!({"schema_version":1,"compiler":{"adapter":"typescript-fixture","upstream_commit":"a".repeat(40),"typescript_commit":"b".repeat(40),"compiler_version":"7.1.0-dev","go_version":"go1.26.2","library_sha256":"a".repeat(64),"header_sha256":"b".repeat(64),"standard_library_digest":"c".repeat(64),"analysis_proto_sha256":"d".repeat(64),"graph_proto_sha256":"e".repeat(64)},"contexts":[{"unit":"typescript/app/main","root":root,"config_path":root.join("tsconfig.json"),"publish_languages":["javascript","typescript"],"case_sensitive":case_sensitive,"root_files":[root.join("src/a.ts"),root.join("src/b.js")],"references":[],"files":records,"directories":[{"path":root.join("src"),"files":["a.ts","b.js"],"directories":[],"listing_complete":true}],"links":[],"absent":[{"path":root.join("src/missing.ts"),"kind":"file"},{"path":root.join("src/a.ts"),"kind":"directory"},{"path":root.join("src"),"kind":"file"}]}]}),
    )?;
    write_inputs(&root, &inputs)?;
    write_config(&root, true, true, "native.json")?;
    Ok((temp, root, inputs))
}
fn write_inputs(root: &Path, inputs: &ts::Inputs) -> Result<()> {
    std::fs::write(
        root.join(".code-ana/native.json"),
        serde_json::to_vec(inputs)?,
    )?;
    Ok(())
}
fn write_config(root: &Path, js: bool, ts: bool, manifest: &str) -> Result<()> {
    std::fs::write(
        root.join(".code-ana/config.toml"),
        format!(
            "schema_version=1\nkind='project'\n[languages.javascript]\nenabled={js}\n[languages.javascript.discovery]\ninputs_manifest='.code-ana/{manifest}'\n[languages.typescript]\nenabled={ts}\n[languages.typescript.discovery]\ninputs_manifest='.code-ana/{manifest}'\n"
        ),
    )?;
    Ok(())
}
#[test]
fn two_explicit_source_languages_resolve_one_backend_and_freeze_both() -> Result<()> {
    let (_temp, root, _) = fixture()?;
    let effective = resolve(&root, &Request::default())?;
    assert_eq!(effective.inputs.len(), 1);
    assert!(matches!(effective.inputs[0], UnitInput::TypeScript { .. }));
    let frozen = capture(&root, &Request::default())?;
    assert_eq!(frozen.unit_views.len(), 1);
    let published = frozen
        .manifest
        .files
        .iter()
        .filter(|file| {
            !file.auxiliary && matches!(file.language.as_str(), "typescript" | "javascript")
        })
        .collect::<Vec<_>>();
    assert_eq!(published.len(), 2);
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .any(|file| file.path == "tsconfig.json" && file.scope == "CONTROL")
    );
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .any(|file| file.path == ".code-ana/native.json" && file.scope == "PREPARATION")
    );
    let source = published
        .iter()
        .find(|file| file.language == "typescript")
        .unwrap();
    std::fs::remove_file(root.join("src/a.ts"))?;
    assert_eq!(frozen.blobs[&source.hash].read(1024)?, b"export const a=1;");
    Ok(())
}
#[test]
fn each_compiler_context_has_its_own_capture_view_and_world() -> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    let mut second = inputs.contexts[0].clone();
    second.unit = "typescript/app/test".into();
    for file in &mut second.files {
        if file.role == ts::FileRole::Publish {
            file.world = Some("typescript/test".into());
        }
    }
    inputs.contexts.push(second);
    write_inputs(&root, &inputs)?;
    let frozen = capture(&root, &Request::default())?;
    assert_eq!(frozen.unit_views.len(), 2);
    assert!(
        frozen
            .backend_capture(codeana_model::AnalysisBackend::TypeScript)
            .is_err()
    );
    for (id, view) in &frozen.unit_views {
        let selected = frozen.unit_capture(id)?;
        let native = selected.effective.typescript_inputs().unwrap();
        assert_eq!(native.contexts.len(), 1);
        assert_eq!(&native.contexts[0].unit, id);
        assert_eq!(selected.manifest, view.manifest);
        assert!(
            view.manifest
                .files
                .iter()
                .filter(|file| file.language == "typescript")
                .all(|file| if id.ends_with("/main") {
                    file.world == "typescript/main"
                } else {
                    file.world == "typescript/test"
                })
        );
    }
    Ok(())
}
#[test]
fn disabling_a_language_requires_repreparation_and_auxiliary_sources_stay_private() -> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    write_config(&root, false, true, "native.json")?;
    assert!(
        format!("{:#}", resolve(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_PUBLICATION_DISABLED")
    );
    inputs.contexts[0].publish_languages = vec![SourceLanguage::TypeScript];
    let js = &mut inputs.contexts[0].files[1];
    js.role = ts::FileRole::Auxiliary;
    js.module = None;
    js.scope = None;
    js.world = None;
    write_inputs(&root, &inputs)?;
    let frozen = capture(&root, &Request::default())?;
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .filter(|file| file.language == "javascript")
            .all(|file| file.auxiliary)
    );
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .any(|file| file.language == "typescript" && !file.auxiliary)
    );
    Ok(())
}
#[test]
fn stale_source_directory_and_negative_queries_refuse_capture() -> Result<()> {
    for change in ["source", "directory", "missing"] {
        let (_temp, root, _) = fixture()?;
        match change {
            "source" => std::fs::write(root.join("src/a.ts"), "changed")?,
            "directory" => std::fs::write(root.join("src/new.ts"), "new")?,
            _ => std::fs::create_dir(root.join("src/missing.ts"))?,
        }
        // A directory at a failed file lookup is still a failed file lookup,
        // but its addition contradicts the previously complete parent listing.
        assert!(capture(&root, &Request::default()).is_err(), "{change}");
    }
    Ok(())
}
#[test]
fn descriptor_location_is_incidental_but_sdk_content_is_semantic() -> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    let first = resolve(&root, &Request::default())?;
    std::fs::write(
        root.join(".code-ana/other.json"),
        serde_json::to_vec(&inputs)?,
    )?;
    write_config(&root, true, true, "other.json")?;
    let second = resolve(&root, &Request::default())?;
    assert_eq!(first.semantic_digest, second.semantic_digest);
    inputs.compiler.library_sha256 = "f".repeat(64);
    write_inputs(&root, &inputs)?;
    write_config(&root, true, true, "native.json")?;
    assert_ne!(
        first.semantic_digest,
        resolve(&root, &Request::default())?.semantic_digest
    );
    Ok(())
}
#[test]
fn missing_inventory_is_explicit_but_files_mode_needs_no_semantic_inventory() -> Result<()> {
    let (_temp, root, _) = fixture()?;
    std::fs::remove_file(root.join(".code-ana/native.json"))?;
    assert!(resolve(&root, &Request::default()).is_err());
    assert!(resolve_files(&root, &Request::default())?.inputs.is_empty());
    let text = "schema_version=1\nkind='project'\n[languages.typescript]\nenabled=true\n";
    std::fs::write(root.join(".code-ana/config.toml"), text)?;
    assert!(
        format!("{:#}", resolve(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_INPUTS_REQUIRED")
    );
    Ok(())
}
#[test]
fn conflicting_source_configuration_and_undeclared_content_are_rejected() -> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    let config = root.join(".code-ana/config.toml");
    let text = std::fs::read_to_string(&config)?;
    let mut other = inputs.clone();
    other.compiler.library_sha256 = "c".repeat(64);
    std::fs::write(
        root.join(".code-ana/other.json"),
        serde_json::to_vec(&other)?,
    )?;
    std::fs::write(&config, text.replacen("native.json", "other.json", 1))?;
    assert!(
        format!("{:#}", resolve(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_CONTEXT_CONFLICT")
    );
    write_config(&root, true, true, "native.json")?;
    inputs.contexts[0].files.push(ts::InputFile {
        path: "/outside/undeclared.d.ts".into(),
        sha256: "1".repeat(64),
        bytes: 10,
        role: ts::FileRole::Auxiliary,
        language: Some(SourceLanguage::TypeScript),
        module: None,
        scope: None,
        world: None,
    });
    write_inputs(&root, &inputs)?;
    assert!(
        format!("{:#}", resolve(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_INPUT_OUTSIDE_DECLARED_ROOTS")
    );
    Ok(())
}

#[test]
fn explicit_dependency_sources_and_ancestor_negatives_are_frozen_without_global_fallback()
-> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    let external = tempfile::tempdir()?;
    let dependency = external.path().canonicalize()?;
    let source = dependency.join("package.d.ts");
    std::fs::write(&source, b"export declare function dep(): void;")?;
    inputs.contexts[0].files.push(ts::InputFile {
        path: source.to_str().unwrap().into(),
        sha256: codeana_model::hash(std::fs::read(&source)?),
        bytes: std::fs::metadata(&source)?.len(),
        role: ts::FileRole::Auxiliary,
        language: Some(SourceLanguage::TypeScript),
        module: None,
        scope: None,
        world: None,
    });
    // A failed lookup outside the project is permitted only for the compiler's
    // ancestor package/type search; it never permits a body read there.
    let absent = root
        .parent()
        .unwrap()
        .join("node_modules/nonexistent-codeana-fixture/package.json");
    assert!(!absent.exists());
    inputs.contexts[0].absent.push(ts::AbsentLookup {
        path: absent.to_str().unwrap().into(),
        kind: ts::LookupKind::File,
    });
    write_inputs(&root, &inputs)?;
    let config = root.join(".code-ana/config.toml");
    let text = std::fs::read_to_string(&config)?;
    let root_literal = serde_json::to_string(dependency.to_str().unwrap())?;
    std::fs::write(
        config,
        text.replace(
            "inputs_manifest='.code-ana/native.json'",
            &format!("inputs_manifest='.code-ana/native.json'\ndependency_roots=[{root_literal}]"),
        ),
    )?;
    let frozen = capture(&root, &Request::default())?;
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .any(|file| file.auxiliary
                && file.path.starts_with(".code-ana/typescript-dependencies/"))
    );
    let expected = codeana_model::hash(std::fs::read(&source)?);
    std::fs::remove_file(source)?;
    assert!(frozen.blobs.contains_key(&expected));
    Ok(())
}

#[test]
fn an_observed_file_at_a_failed_directory_lookup_invalidates_when_replaced_by_a_directory()
-> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    // Isolate the typed negative observation from content/listing checks.
    let path = root.join("marker");
    std::fs::write(&path, b"file")?;
    inputs.contexts[0].absent.push(ts::AbsentLookup {
        path: path.to_str().unwrap().into(),
        kind: ts::LookupKind::Directory,
    });
    write_inputs(&root, &inputs)?;
    capture(&root, &Request::default())?;
    std::fs::remove_file(&path)?;
    std::fs::create_dir(&path)?;
    assert!(
        format!("{:#}", capture(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_MISSING_LOOKUP_STALE")
    );
    Ok(())
}

#[test]
fn filters_change_publication_but_preserve_required_checker_inputs() -> Result<()> {
    let (_temp, root, _) = fixture()?;
    let config = root.join(".code-ana/config.toml");
    let text = std::fs::read_to_string(&config)?;
    std::fs::write(
        config,
        format!(
            "{text}\n[[modules]]\nid='app'\nlanguage='typescript'\nroot='.'\n[modules.scan]\nexclude=['src/b.js']\n"
        ),
    )?;
    let frozen = capture(&root, &Request::default())?;
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .any(|file| file.path == "src/a.ts" && !file.auxiliary)
    );
    assert!(
        frozen
            .manifest
            .files
            .iter()
            .any(|file| file.path == "src/b.js" && file.auxiliary)
    );
    Ok(())
}

#[test]
fn enabled_switches_are_required_and_dependency_roots_cannot_be_the_filesystem_root() -> Result<()>
{
    let (_temp, root, _) = fixture()?;
    let config = root.join(".code-ana/config.toml");
    std::fs::write(
        &config,
        "schema_version=1\nkind='project'\n[languages.typescript]\n",
    )?;
    assert!(resolve_files(&root, &Request::default()).is_err());
    write_config(&root, true, true, "native.json")?;
    let text = std::fs::read_to_string(&config)?;
    std::fs::write(
        &config,
        text.replace(
            "inputs_manifest='.code-ana/native.json'",
            "inputs_manifest='.code-ana/native.json'\ndependency_roots=['/']",
        ),
    )?;
    assert!(
        format!("{:#}", resolve(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_DEPENDENCY_ROOT")
    );
    Ok(())
}

#[test]
#[ignore = "requires fresh real Go preparation; mandatory in just test-typescript-inputs"]
fn actual_compiler_inventory_uses_public_capture_without_live_fallback() -> Result<()> {
    let path = PathBuf::from(std::env::var("CODEANA_TYPESCRIPT_PREPARED_FIXTURE")?);
    let path = path.canonicalize()?;
    let repository = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()?;
    anyhow::ensure!(
        path.starts_with(repository.join("target/typescript-c-abi")),
        "NEW_COMPONENT_FIXTURE_REQUIRED"
    );
    let evidence = path.parent().unwrap();
    let original: ts::Inputs = serde_json::from_slice(&std::fs::read(&path)?)?;
    original.validate().map_err(anyhow::Error::msg)?;
    anyhow::ensure!(
        original.contexts.len() == 1,
        "ONE_REAL_FIXTURE_CONTEXT_REQUIRED"
    );
    let root = PathBuf::from(&original.contexts[0].root).canonicalize()?;
    anyhow::ensure!(
        root == evidence.join("live") && !root.join(".code-ana").exists(),
        "FRESH_OWNED_COMPONENT_REQUIRED"
    );
    let receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(evidence.join("receipt.json"))?)?;
    anyhow::ensure!(
        receipt["case"] == "prepare"
            && receipt["component_only"] == true
            && receipt["runtime"]["upstream_commit"] == original.compiler.upstream_commit,
        "REAL_PREPARATION_RECEIPT_REQUIRED"
    );
    std::fs::create_dir(root.join(".code-ana"))?;
    std::fs::write(root.join(".code-ana/native.json"), std::fs::read(&path)?)?;
    std::fs::write(
        root.join(".code-ana/config.toml"),
        "schema_version=1\nkind='project'\n[languages.typescript]\nenabled=true\n[languages.typescript.discovery]\ninputs_manifest='.code-ana/native.json'\n",
    )?;
    let mut bodies = std::collections::BTreeMap::new();
    for file in &original.contexts[0].files {
        bodies.insert(file.sha256.clone(), std::fs::read(&file.path)?);
    }
    let frozen = capture(&root, &Request::default())?;
    assert_eq!(frozen.unit_views.len(), 1);
    let published = frozen
        .manifest
        .files
        .iter()
        .filter(|file| !file.auxiliary && file.language == "typescript")
        .count();
    assert_eq!(published, 2);
    for file in &original.contexts[0].files {
        std::fs::remove_file(&file.path)?;
    }
    for (digest, body) in &bodies {
        assert_eq!(&frozen.blobs[digest].read(1 << 20)?, body);
    }
    assert!(capture(&root, &Request::default()).is_err());
    let proof = json!({"real_go_inventory":true,"public_capture":true,"live_inputs_deleted":true,"captured_bodies_verified":bodies.len(),"published_sources":published,"units":frozen.unit_views.len(),"compiler":original.compiler,"component_only":true,"product_indexing":false});
    std::fs::write(
        evidence.join("capture-proof.json"),
        serde_json::to_vec_pretty(&proof)?,
    )?;
    Ok(())
}

#[test]
fn a_changed_target_volume_case_rule_cannot_be_silently_replayed() -> Result<()> {
    let (_temp, root, mut inputs) = fixture()?;
    inputs.contexts[0].case_sensitive = !inputs.contexts[0].case_sensitive;
    write_inputs(&root, &inputs)?;
    assert!(
        format!("{:#}", capture(&root, &Request::default()).err().unwrap())
            .contains("TYPESCRIPT_CASE_RULES_STALE")
    );
    Ok(())
}

#[test]
#[ignore = "requires explicit actual Go resolution inventories; mandatory in test-typescript-resolution"]
fn actual_resolution_inventories_freeze_roles_and_reject_stale_observations() -> Result<()> {
    let evidence = PathBuf::from(std::env::var("CODEANA_TYPESCRIPT_RESOLUTION_FIXTURE")?);
    let cases: Vec<(String, usize)> =
        serde_json::from_slice(&std::fs::read(evidence.join("cases.json"))?)?;
    assert_eq!(cases.len(), 5, "complete real resolver matrix required");
    let mut proofs = Vec::new();
    for (case, expected) in cases {
        anyhow::ensure!(codeana_model::safe_path(&case), "FIXTURE_CASE_PATH");
        let root = evidence.join(&case).canonicalize()?;
        let inputs: ts::Inputs =
            serde_json::from_slice(&std::fs::read(root.join(".code-ana/native.json"))?)?;
        inputs.validate().map_err(anyhow::Error::msg)?;
        assert_eq!(inputs.contexts.len(), 1);
        let context = &inputs.contexts[0];
        assert_eq!(Path::new(&context.root), root);
        let bodies = context
            .files
            .iter()
            .map(|file| Ok((file.sha256.clone(), std::fs::read(&file.path)?)))
            .collect::<Result<std::collections::BTreeMap<_, _>>>()?;
        let frozen = capture(&root, &Request::default())?;
        assert_eq!(frozen.unit_views.len(), 1);
        for descriptor in [".code-ana/native.json", ".code-ana/alias.json"] {
            assert!(
                frozen
                    .manifest
                    .files
                    .iter()
                    .any(|file| file.path == descriptor && file.scope == "PREPARATION")
            );
        }
        let published = frozen
            .manifest
            .files
            .iter()
            .filter(|file| file.language == "typescript" && !file.auxiliary)
            .count();
        assert_eq!(published, expected);
        assert!(published > 0);
        for file in &frozen.manifest.files {
            if file.path.contains("node_modules/") && file.language == "typescript" {
                assert!(file.auxiliary, "dependency became published: {}", file.path);
            }
        }
        // A control edit invalidates the old inventory, even when sources stay identical.
        let config = Path::new(&context.config_path);
        let original = std::fs::read(config)?;
        let mut changed = original.clone();
        changed.push(b' ');
        std::fs::write(config, &changed)?;
        assert!(capture(&root, &Request::default()).is_err());
        std::fs::write(config, original)?;
        // A previously failed resolution becoming positive must invalidate preparation.
        let lookup = context
            .absent
            .iter()
            .find(|entry| {
                entry.kind == ts::LookupKind::File
                    && Path::new(&entry.path).starts_with(&root)
                    && !Path::new(&entry.path).exists()
                    && Path::new(&entry.path).parent().is_some_and(Path::is_dir)
            })
            .ok_or_else(|| anyhow::anyhow!("REAL_FAILED_LOOKUP_REQUIRED: {case}"))?;
        std::fs::write(&lookup.path, b"{}")?;
        assert!(capture(&root, &Request::default()).is_err());
        std::fs::remove_file(&lookup.path)?;
        capture(&root, &Request::default())?;
        for file in &context.files {
            std::fs::remove_file(&file.path)?;
        }
        for (digest, body) in &bodies {
            assert_eq!(&frozen.blobs[digest].read(32 << 20)?, body);
        }
        assert!(capture(&root, &Request::default()).is_err());
        proofs.push(
            json!({"case":case,"captured_bodies":bodies.len(),"published":published,
            "live_files_deleted":true,"control_and_failed_lookup_staleness":true}),
        );
    }
    std::fs::write(
        evidence.join("capture-proof.json"),
        serde_json::to_vec_pretty(&proofs)?,
    )?;
    Ok(())
}

#[test]
fn equivalent_inventory_locations_are_normalized_before_context_conflict_checks() -> Result<()> {
    let (_temp, root, inputs) = fixture()?;
    let baseline = resolve(&root, &Request::default())?.semantic_digest.clone();
    let mut reordered = inputs.clone();
    reordered.contexts[0].files.reverse();
    reordered.contexts[0].absent.reverse();
    std::fs::write(
        root.join(".code-ana/alias.json"),
        serde_json::to_vec_pretty(&reordered)?,
    )?;
    let path = root.join(".code-ana/config.toml");
    let text = std::fs::read_to_string(&path)?;
    std::fs::write(&path, text.replacen("native.json", "alias.json", 1))?;
    let resolved = resolve(&root, &Request::default())?;
    assert_eq!(resolved.inputs.len(), 1);
    assert_eq!(resolved.semantic_digest, baseline);
    let frozen = capture(&root, &Request::default())?;
    assert_eq!(frozen.unit_views.len(), 1);
    for descriptor in [".code-ana/native.json", ".code-ana/alias.json"] {
        assert!(
            frozen
                .manifest
                .files
                .iter()
                .any(|file| file.path == descriptor && file.scope == "PREPARATION")
        );
    }
    assert_eq!(
        frozen
            .manifest
            .files
            .iter()
            .filter(|file| !file.auxiliary && !file.language.is_empty())
            .count(),
        2
    );
    // One locator can supply the shared context when the other enabled source
    // language declares no separate locator.
    std::fs::write(
        &path,
        text.replacen("inputs_manifest='.code-ana/native.json'\n", "", 1),
    )?;
    assert_eq!(
        resolve(&root, &Request::default())?.semantic_digest,
        baseline
    );
    assert_eq!(capture(&root, &Request::default())?.unit_views.len(), 1);
    Ok(())
}
