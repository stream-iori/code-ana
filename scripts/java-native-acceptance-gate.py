#!/usr/bin/env python3
"""Seal full native correctness only after every required real gate succeeds."""
import argparse
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('jvm_acceptance', ROOT / 'scripts/java-jvm-acceptance-gate.py')
jvm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jvm)
REQUIRED = {'jvm', 'abi', 'shared_bytecode', 'lifecycle', 'probe', 'product', 'models',
            'publication', 'semantics', 'known_upstream', 'fact_equivalence', 'audit',
            'queries', 'representative', 'matrix', 'query_lifecycle', 'history', 'jvm_history', 'release', 'crash'}


def receipt_location(entry):
    if isinstance(entry, str):
        return jvm.input_path(entry), ROOT
    jvm.require(isinstance(entry, dict) and set(entry) == {'receipt', 'input_root'},
                'EXPLICIT_RECEIPT_INPUT_ROOT_REQUIRED')
    root = pathlib.Path(entry['input_root'])
    jvm.require(root.is_absolute() and root.is_dir(), 'ABSOLUTE_RECEIPT_INPUT_ROOT_REQUIRED')
    return jvm.input_path(entry['receipt']), root.resolve()


def proof_location(filename, root):
    path = pathlib.Path(filename)
    return (path if path.is_absolute() else root / path).resolve()


def runtime_lineage(reports):
    publication = reports['publication']
    bundles = {name: digest for name, digest in publication.get('artifacts', {}).items()
               if name.startswith('target/lib/java/')}
    jvm.require(any(name.endswith(('.dylib', '.so')) for name in bundles)
                and 'target/lib/java/compiler-bridge.jar' in bundles
                and 'target/lib/java/maven-input-worker.jar' in bundles
                and 'target/lib/java/native/manifest.json' in bundles, 'NATIVE_BUNDLE_LINEAGE_REQUIRED')
    for group in ['lifecycle', 'probe', 'product']:
        recorded = reports[group].get('sources_and_artifacts', {})
        for name, digest in bundles.items():
            values = {checksum for path, checksum in recorded.items() if path.endswith('/runtime/' + name)}
            jvm.require(values == {digest}, 'MIXED_NATIVE_RUNTIME: ' + group + ':' + name)


def validate(reports):
    jvm.require(set(reports) == REQUIRED, 'COMPLETE_NATIVE_RECEIPT_SET_REQUIRED')
    base = reports['jvm']
    jvm.require(base.get('kind') == 'complete_jvm_migration_acceptance' and base.get('status') == 'passed'
                and base.get('jvm_migration_accepted') is True, 'COMPLETE_JVM_REQUIRED')
    for group, count in [('lifecycle', 6), ('probe', 17), ('product', 58)]:
        report = reports[group]
        jvm.cases(report, count)
        jvm.require(report.get('backend') == 'native' and report.get('group') == group
                    and report.get('complete_group_gate') is True, 'COMPLETE_NATIVE_GROUP_REQUIRED: ' + group)
    publication = reports['publication']
    jvm.require(publication.get('backend') == 'native' and publication.get('state') == 'finished'
                and publication.get('publication_accepted') is True
                and publication.get('unexpected_signal_observed') is False
                and publication.get('changed_artifacts') == [] and publication.get('changed_source_inputs') == []
                and publication.get('inputs_manifest_changed') is False and publication.get('config_restored') is True,
                'COMPLETE_NATIVE_PUBLICATION_REQUIRED')
    runtime_lineage(reports)
    model = reports['models']
    jvm.cases(model, 160)
    jvm.require(model.get('backend') == 'native' and model.get('complete_native_model_gate') is True
                and model.get('changed_inputs') == []
                and all(row.get('jvm_differences') == [] and row.get('execution', {}).get('unexpected_signal') is None for row in model['cases']),
                'COMPLETE_NATIVE_MODEL_REPLAY_REQUIRED')
    abi = reports['abi']
    jvm.require(abi.get('status') == 'passed' and abi.get('layouts', {}).get('c') == abi.get('layouts', {}).get('rs')
                and len(abi.get('fields', [])) == 12, 'ACTUAL_C_RUST_LAYOUT_REQUIRED')
    shared = reports['shared_bytecode']
    jvm.require(shared.get('status') == 'passed' and shared.get('shared_entries', 0) > 0
                and shared.get('changed_entries') == [] and shared.get('unexpected_shared_entries') == [], 'SHARED_JAVA_PRODUCER_BYTES_REQUIRED')
    equality = reports['fact_equivalence']
    jvm.require(equality.get('status') == 'passed' and equality.get('whole_fact_ir_equal') is True
                and equality.get('changed_inputs') == []
                and equality.get('graphs', {}).get('jvm', {}).get('count', 0) > 0
                and equality['graphs']['jvm'] == equality['graphs'].get('native'), 'WHOLE_FACT_IR_EQUALITY_REQUIRED')
    semantics = reports['semantics']; known = reports['known_upstream']
    jvm.require(semantics.get('backend') == 'native' and len(semantics.get('domains', [])) == 14
                and len({row['id'] if 'id' in row else row['name'] for row in semantics['domains']}) == 14,
                'ALL_NATIVE_GRAPH_DOMAINS_REQUIRED')
    jvm.require(known.get('status') == 'passed' and known.get('migration_semantics_accepted') is True
                and known.get('unexplained_failures') == [] and known.get('revision') == publication['published_revision'],
                'EXACT_NATIVE_UPSTREAM_CLASSIFICATION_REQUIRED')
    for name, count in [('queries', 198), ('representative', 21), ('matrix', 19), ('query_lifecycle', 7)]:
        report = reports[name]
        jvm.cases(report, count)
        jvm.require(report.get('backend') == 'native' and report.get('revision') == publication['published_revision'],
                    'NATIVE_QUERY_REVISION_REQUIRED: ' + name)
    audit = reports['audit']
    jvm.require(audit.get('status') == 'passed' and audit.get('errors') == []
                and audit.get('before_sha256') == audit.get('after_sha256')
                and audit.get('database') == publication['published_facts'], 'READ_ONLY_NATIVE_AUDIT_REQUIRED')
    history = reports['history']; baseline = reports['jvm_history']
    jvm.require(history.get('status') == 'passed' and history.get('backend') == 'native'
                and history.get('complete_committed_history_gate') is True
                and all(history.get(key) is True for key in ['full_delta_equal', 'historical_source_equal', 'restart_equal'])
                and history.get('changed_inputs') == [], 'COMPLETE_NATIVE_COMMITTED_HISTORY_REQUIRED')
    phases = {row['id']: row for row in history['phases']}; before = {row['id']: row for row in baseline['phases']}
    jvm.require(set(phases) == set(before) == {'baseline-full', 'baseline-cold-repeat', 'target-incremental', 'target-full'}
                and all(phases[name]['graph'] == before[name]['graph'] for name in phases), 'NATIVE_JVM_COMMITTED_HISTORY_GRAPH_EQUALITY_REQUIRED')
    release = reports['release']
    jvm.require(release.get('all_ok') is True and len(release.get('gates', [])) == 4
                and all(gate['exit_code'] == 0 for gate in release['gates'])
                and all(release.get(key) == [] for key in ['changed_package', 'changed_inputs', 'changed_artifacts']), 'COMPLETE_NATIVE_RELEASE_REQUIRED')
    crash = reports['crash']
    jvm.require(crash.get('status') == 'passed' and crash.get('actual_signal') == 11
                and all(crash.get(key) is True for key in ['old_head_preserved', 'daemon_alive', 'worker_reaped']), 'ACTUAL_NATIVE_SIGSEGV_ISOLATION_REQUIRED')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('NEW_NATIVE_SEAL_REQUIRED')
    inputs = {str(path.resolve()): jvm.sha(path) for path in [args.manifest, pathlib.Path(__file__), ROOT / 'scripts/java-jvm-acceptance-gate.py']}
    report = dict(kind='complete_native_migration_acceptance', status='failed', native_accepted=False,
                  coverage_closed=False, performance_accepted=False, strict_reference_equal=False, inputs=inputs,
                  unexplained_failures=[])
    try:
        paths = json.loads(args.manifest.read_text())
        jvm.require(set(paths) == REQUIRED, 'COMPLETE_NATIVE_RECEIPT_SET_REQUIRED')
        reports = {}
        for name, filename in paths.items():
            path, input_root = receipt_location(filename)
            reports[name] = json.loads(path.read_text())
            inputs[str(path)] = jvm.sha(path)
            for field in ['inputs', 'sources_and_artifacts']:
                for filename, digest in reports[name].get(field, {}).items():
                    resolved = str(proof_location(filename, input_root))
                    jvm.require(resolved not in inputs or inputs[resolved] == digest, 'NATIVE_PROOF_CONFLICT')
                    inputs[resolved] = digest
        validate(reports)
        for filename, digest in inputs.items():
            jvm.require(jvm.sha(filename) == digest, 'NATIVE_PROOF_DRIFT: ' + filename)
        report.update(status='passed', native_accepted=True, published_revision=reports['publication']['published_revision'],
                      jvm_migration_accepted=True, receipt_count=len(reports))
    except Exception as error:
        report['unexplained_failures'].append(str(error))
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
