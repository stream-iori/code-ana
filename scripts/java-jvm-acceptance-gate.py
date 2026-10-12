#!/usr/bin/env python3
"""Seal the complete JVM migration gate, preserving strict upstream failures."""
import argparse
import hashlib
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
REQUIRED = {'components', 'probe', 'product', 'models', 'model_producer',
            'runtime_equivalence', 'publication', 'semantics', 'classification',
            'audit', 'queries', 'representative', 'query_matrix', 'lifecycle', 'history'}
EMPTY = ('missing', 'duplicate', 'unexpected', 'expected_duplicate', 'artifact_drift',
         'changed_inputs', 'changed_source_inputs', 'changed_artifacts')


def sha(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def input_path(filename):
    path = pathlib.Path(filename)
    return (path if path.is_absolute() else ROOT / path).resolve()


def cases(report, count):
    rows = report.get('cases', [])
    require(report.get('status') == 'passed' and len(rows) == count
            and len({row['id'] for row in rows}) == count
            and all(row.get('status') == 'passed' for row in rows), 'CASE_COVERAGE')
    require(all(not report.get(key) for key in EMPTY), 'CASE_INPUT_DRIFT_OR_OMISSION')


def bundle_hash(report, name):
    values = {value for path, value in report.get('sources_and_artifacts', {}).items()
              if path.endswith('/runtime/target/lib/java/jvm/' + name)}
    require(len(values) == 1, 'RECORDED_RUNTIME_BUNDLE_REQUIRED: ' + name)
    return next(iter(values))


def verify_producer(reports):
    before = next(pathlib.Path(name).parent for name in reports['models']['inputs']
                  if name.endswith('/lang-java-adapotr/target/jvm/runner.jar'))
    after = next(pathlib.Path(name).parent for name in reports['probe']['sources_and_artifacts']
                 if name.endswith('/runtime/target/lib/java/jvm/runner.jar'))
    witness = pathlib.Path(reports['model_producer']['witness'])
    tool = ROOT / 'scripts/java-model-runtime-equivalence.py'
    spec = importlib.util.spec_from_file_location('model_producer_proof', tool)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    require(module.compare(before, after, witness) == reports['model_producer'],
            'MODEL_PRODUCER_PROOF_NOT_REPRODUCIBLE')
    paths = [tool, witness, *[folder / name for folder in (before, after)
             for name in ('runner.jar', 'compiler-bridge.jar', 'maven-input-worker.jar')]]
    return {str(path.resolve()): sha(path) for path in paths}


def validate(reports):
    require(set(reports) == REQUIRED, 'COMPLETE_GATE_MANIFEST_REQUIRED')
    for name, count in [('components', 654), ('probe', 17), ('product', 58)]:
        value = reports[name]
        cases(value, count)
        require(value.get('backend') == 'jvm' and value.get('expected_count') == count
                and value.get('executed_count') == count, 'JVM_FIXTURE_COVERAGE')
        if name != 'components':
            require(value.get('complete_group_suite') is True
                    and value.get('complete_group_gate') is True, 'JVM_FULL_GROUP_REQUIRED')
    model = reports['models']
    require(model.get('scope_model_inventory_accepted') is True
            and model.get('all_contexts_executed') is True
            and model.get('expected_count') == model.get('executed_count') == 160
            and model.get('unexplained_failures') == []
            and model.get('changed_inputs') == [], 'COMPLETE_MODEL_INVENTORY_REQUIRED')
    require(reports['model_producer'].get('status') == 'passed'
            and reports['runtime_equivalence'].get('status') == 'passed'
            and reports['runtime_equivalence'].get('production_entries_identical') is True,
            'PRODUCTION_MODEL_LINEAGE_REQUIRED')
    publication = reports['publication']
    require(publication.get('backend') == 'jvm' and publication.get('state') == 'finished'
            and publication.get('publication_accepted') is True
            and publication.get('unexpected_signal_observed') is False
            and publication.get('changed_artifacts') == []
            and publication.get('changed_source_inputs') == []
            and publication.get('inputs_manifest_changed') is False
            and publication.get('config_restored') is True, 'FULL_JVM_PUBLICATION_REQUIRED')
    revision = publication['published_revision']
    expected_bundles = {'runner.jar', 'compiler-bridge.jar', 'maven-input-worker.jar'}
    producer = {row['name']: row for row in reports['model_producer']['bundles']}
    equivalence = {row['name']: row for row in reports['runtime_equivalence']['bundles']}
    require(set(producer) == set(equivalence) == expected_bundles, 'BUNDLE_LINEAGE_COVERAGE')
    for name in expected_bundles:
        current = publication['artifacts']['target/lib/java/jvm/' + name]
        require(producer[name]['after_sha256'] == equivalence[name]['after_sha256'] == current
                == bundle_hash(reports['probe'], name)
                and equivalence[name]['before_sha256'] == bundle_hash(reports['product'], name)
                and equivalence[name]['changed'] == [], 'BUNDLE_LINEAGE_MISMATCH: ' + name)
        # The raw/AST collector invokes runner.jar; the compiler bridge is
        # embedded there and Maven inputs were captured before collection.
        if name == 'runner.jar':
            require(any(path.endswith('/lang-java-adapotr/target/jvm/' + name)
                    and value == producer[name]['before_sha256']
                    for path, value in model['inputs'].items()), 'MODEL_PRODUCER_BUNDLE_MISMATCH')
    semantics, classified = reports['semantics'], reports['classification']
    require(len(semantics.get('domains', [])) == 14
            and len({row['name'] for row in semantics['domains']}) == 14
            and semantics.get('changed_inputs') == []
            and semantics.get('published_revision') == revision, 'FULL_SEMANTIC_SCAN_REQUIRED')
    require(classified.get('migration_semantics_accepted') is True
            and classified.get('unexplained_failures') == []
            and classified.get('revision') == revision, 'EXACT_KNOWN_ISSUE_CLASSIFICATION_REQUIRED')
    audit = reports['audit']
    require(audit.get('status') == 'passed' and audit.get('errors') == []
            and audit.get('before_sha256') == audit.get('after_sha256')
            == sha(publication['published_facts']), 'CURRENT_READ_ONLY_AUDIT_REQUIRED')
    for name, count in [('queries', 198), ('representative', 21), ('query_matrix', 19), ('lifecycle', 7)]:
        cases(reports[name], count)
        require(reports[name].get('revision') == revision, 'QUERY_REVISION_MISMATCH')
    history = reports['history']
    require(history.get('backend') == 'jvm' and history.get('status') == 'passed'
            and history.get('complete_committed_history_gate') is True
            and history.get('changed_inputs') == [], 'COMMITTED_HISTORY_REQUIRED')
    return revision


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'NEW_GATE_RECEIPT_REQUIRED')
    manifest = json.loads(args.manifest.read_text())
    proofs = {str(pathlib.Path(__file__).resolve()): sha(__file__),
              str(args.manifest.resolve()): sha(args.manifest)}
    reports = {}
    for name, filename in manifest.items():
        path = pathlib.Path(filename).resolve()
        reports[name] = json.loads(path.read_text())
        proofs[str(path)] = sha(path)
        for field in ('inputs', 'sources', 'sources_and_artifacts'):
            for filename, expected in reports[name].get(field, {}).items():
                path = input_path(filename)
                require(sha(path) == expected, 'GATE_PROOF_DRIFT: ' + filename)
                proofs[str(path)] = expected
    proofs.update(verify_producer(reports))
    revision = validate(reports)
    # Bind each domain result, including every failed strict comparison, to
    # this seal. A later edit cannot broaden a known-issue exception.
    for row in reports['semantics']['domains']:
        require(sha(row['output']) == row['output_sha256'], 'SEMANTIC_RESULT_DRIFT')
        proofs[row['output']] = row['output_sha256']
    report = dict(status='passed', kind='complete_jvm_migration_acceptance',
                  jvm_migration_accepted=True, native_accepted=False,
                  strict_reference_equal=False, unexplained_failures=[],
                  revision=revision, receipts=manifest, inputs=proofs,
                  known_upstream_issues=reports['classification']['known_upstream_issues'],
                  performance_accepted=False, coverage_inventory_closed=False)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ('status', 'jvm_migration_accepted', 'revision')}))


if __name__ == '__main__':
    main()
