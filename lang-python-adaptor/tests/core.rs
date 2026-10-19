use codeana_python_adaptor::{FrozenModule, analyze};

fn input(name: &str, source: &str) -> FrozenModule {
    FrozenModule {
        name: name.into(),
        path: format!("{name}.py"),
        source: source.into(),
    }
}

#[test]
fn inferred_factories_higher_order_union_and_any() {
    let result = analyze(
        &[input(
            "main",
            include_str!("../../fixtures/python/inference/main.py"),
        )],
        "3.12",
        "darwin",
    )
    .unwrap();
    let calls = &result.calls;
    for syntax in ["factory().run", "higher_order()().run"] {
        let call = calls.iter().find(|c| c.syntax == syntax).unwrap();
        assert_eq!(
            call.targets
                .iter()
                .map(|t| t.name.as_str())
                .collect::<Vec<_>>(),
            ["main.Service.run"]
        );
        assert!(call.unknown_reasons.is_empty());
    }
    let union = calls.iter().find(|c| c.syntax == "selected.run").unwrap();
    assert_eq!(
        union
            .targets
            .iter()
            .map(|t| t.name.as_str())
            .collect::<Vec<_>>(),
        ["main.Other.run", "main.Service.run"]
    );
    let any = calls.iter().find(|c| c.syntax == "value.run").unwrap();
    assert!(any.targets.is_empty());
    assert_eq!(any.unknown_reasons, ["dynamic_any"]);
    let partial = calls
        .iter()
        .filter(|c| c.syntax == "value.run")
        .nth(1)
        .unwrap();
    assert_eq!(
        partial
            .targets
            .iter()
            .map(|t| t.name.as_str())
            .collect::<Vec<_>>(),
        ["main.Service.run"]
    );
    assert_eq!(partial.unknown_reasons, ["dynamic_any"]);
    assert!(result.declarations.iter().any(|d| d.name == "factory"));
    assert!(result.references.iter().any(|r| !r.targets.is_empty()));
}

#[test]
fn exception_alias_has_a_declaration_at_the_backend_definition_range() {
    let source = "try:\n    pass\nexcept Exception as exc:\n    saved = exc\n";
    let result = analyze(&[input("main", source)], "3.12", "darwin").unwrap();
    let start = source.find("exc:").unwrap() as u32;
    assert!(
        result
            .declarations
            .iter()
            .any(|d| d.name == "exc" && d.kind == "variable" && d.start == start)
    );
    assert!(result.references.iter().any(|r| {
        r.targets
            .iter()
            .any(|t| t.path == "main.py" && t.start == start)
    }));
}

#[test]
fn assignment_targets_are_declarations_not_read_references() {
    let result = analyze(
        &[input("main", "assigned = 1\nother = assigned\n")],
        "3.12",
        "darwin",
    )
    .unwrap();
    assert_eq!(result.references.len(), 1);
    assert_eq!(result.references[0].start, 21);
    assert_eq!(
        result
            .declarations
            .iter()
            .filter(|d| d.kind == "variable")
            .count(),
        2
    );
}

#[test]
fn circular_imports_use_only_supplied_sources() {
    let result = analyze(
        &[
            input("a", include_str!("../../fixtures/python/imports/a.py")),
            input("b", include_str!("../../fixtures/python/imports/b.py")),
        ],
        "3.12",
        "darwin",
    )
    .unwrap();
    let targets = result
        .calls
        .iter()
        .flat_map(|c| c.targets.iter().map(|t| t.name.as_str()))
        .collect::<Vec<_>>();
    assert_eq!(targets, ["b.second", "a.first"]);
    let missing = analyze(
        &[input(
            "a",
            include_str!("../../fixtures/python/imports/a.py"),
        )],
        "3.12",
        "darwin",
    )
    .unwrap();
    assert!(!missing.diagnostics.is_empty());
    assert!(missing.calls.iter().all(|c| c.targets.is_empty()));
}

#[test]
fn target_code_is_never_executed() {
    let temp = tempfile::tempdir().unwrap();
    let marker = temp.path().join("executed");
    let source = format!(
        "open({:?}, 'w').write('BAD')\ndef f():\n    return 1\nf()\n",
        marker.to_string_lossy()
    );
    let result = analyze(&[input("trap", &source)], "3.12", "darwin").unwrap();
    assert!(!marker.exists());
    assert!(
        result
            .calls
            .iter()
            .any(|c| c.targets.iter().any(|t| t.name == "trap.f"))
    );
}

#[test]
fn explicit_python_version_controls_semantics() {
    let source = "import sys\nif sys.version_info >= (3, 13):\n    def target():\n        return 13\nelse:\n    def target():\n        return 12\ntarget()\n";
    let v12 = analyze(&[input("versioned", source)], "3.12", "darwin").unwrap();
    let v13 = analyze(&[input("versioned", source)], "3.13", "darwin").unwrap();
    let start12 = v12.calls.last().unwrap().targets.first().unwrap().start;
    let start13 = v13.calls.last().unwrap().targets.first().unwrap().start;
    assert!(start12 > start13);
}

#[test]
fn ambient_environment_and_live_files_are_not_inputs() {
    let temp = tempfile::tempdir().unwrap();
    std::fs::write(
        temp.path().join("ambient_dependency.py"),
        "def run():\n    return 99\n",
    )
    .unwrap();
    std::fs::write(
        temp.path().join("main.py"),
        "this is not the captured source !",
    )
    .unwrap();
    std::fs::write(
        temp.path().join("pyrefly.toml"),
        "invalid configuration = !",
    )
    .unwrap();
    std::fs::write(
        temp.path().join("execute.pth"),
        "import pathlib; pathlib.Path('PTH_EXECUTED').touch()\n",
    )
    .unwrap();
    let child = std::process::Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "frozen_process_child", "--nocapture"])
        .current_dir(temp.path())
        .env("CODEANA_FROZEN_PROBE", "1")
        .env("PYTHONPATH", temp.path())
        .env("VIRTUAL_ENV", temp.path())
        .output()
        .unwrap();
    assert!(
        child.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&child.stdout),
        String::from_utf8_lossy(&child.stderr)
    );
    assert!(!temp.path().join("PTH_EXECUTED").exists());
}

#[test]
fn frozen_process_child() {
    if std::env::var_os("CODEANA_FROZEN_PROBE").is_none() {
        return;
    }
    let result = analyze(&[input("main", "import ambient_dependency\ndef captured():\n    return 1\ncaptured()\nambient_dependency.run()\n")], "3.12", "darwin").unwrap();
    assert!(
        result
            .calls
            .iter()
            .any(|c| c.targets.iter().any(|t| t.name == "main.captured"))
    );
    let missing = result
        .calls
        .iter()
        .find(|c| c.syntax == "ambient_dependency.run")
        .unwrap();
    assert!(missing.targets.is_empty());
    assert!(!missing.unknown_reasons.is_empty());
    assert!(!result.diagnostics.is_empty());
}

#[test]
fn inheritance_aliases_and_nested_declarations() {
    let result = analyze(
        &[
            input(
                "base",
                include_str!("../../fixtures/python/semantic-core/base.py"),
            ),
            input(
                "main",
                include_str!("../../fixtures/python/semantic-core/main.py"),
            ),
        ],
        "3.12",
        "darwin",
    )
    .unwrap();
    let inheritance = result
        .references
        .iter()
        .find(|r| r.relation == "INHERITS")
        .unwrap();
    assert_eq!(inheritance.targets[0].name, "base.Base");
    for expected in [
        "main.Child",
        "main.Child.run",
        "base.Base.static",
        "base.Base.create",
        "main.nested.run",
    ] {
        assert!(
            result
                .calls
                .iter()
                .flat_map(|c| &c.targets)
                .any(|t| t.name == expected),
            "{expected}"
        );
    }
    assert!(
        result
            .declarations
            .iter()
            .any(|d| d.kind == "import" && d.name == "Parent")
    );
    assert!(
        result
            .declarations
            .iter()
            .any(|d| d.kind == "parameter" && d.name == "self")
    );
}

#[test]
fn dataclass_protocol_and_unicode_are_semantic_inputs() {
    let service = analyze(
        &[input(
            "service",
            include_str!("../../fixtures/python/mini-service/service.py"),
        )],
        "3.12",
        "darwin",
    )
    .unwrap();
    let call = service
        .calls
        .iter()
        .find(|c| c.syntax == "repository().get")
        .unwrap();
    assert_eq!(call.targets[0].name, "service.MemoryRepository.get");
    let unicode = analyze(
        &[input(
            "main",
            include_str!("../../fixtures/python/identity-source/main.py"),
        )],
        "3.12",
        "darwin",
    )
    .unwrap();
    let names = unicode
        .declarations
        .iter()
        .filter(|d| d.name == "同名")
        .map(|d| &d.qualified_name)
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(names.len(), 2);
    for c in unicode.calls {
        assert_eq!(c.targets.len(), 1);
    }
}

#[test]
fn ignored_missing_import_still_has_incomplete_semantics() {
    let result = analyze(
        &[input(
            "main",
            "# pyrefly: ignore-errors\nimport definitely_absent\n",
        )],
        "3.12",
        "darwin",
    )
    .unwrap();
    assert!(
        result
            .diagnostics
            .iter()
            .any(|d| d.code == "missing-import")
    );
}

#[test]
fn dynamic_calls_do_not_gain_targets_from_spelling() {
    let result = analyze(
        &[input(
            "main",
            include_str!("../../fixtures/python/dynamic/main.py"),
        )],
        "3.12",
        "darwin",
    )
    .unwrap();
    for syntax in ["value.run", "getattr(value, name)", "__import__(name).run"] {
        let call = result.calls.iter().find(|c| c.syntax == syntax).unwrap();
        assert!(call.targets.is_empty(), "{syntax}");
        assert!(!call.unknown_reasons.is_empty(), "{syntax}");
    }
}
