#!/usr/bin/env python3
"""Replay every sealed JVM model context through the native AnalysisSession witness.

Reference expectations come from the independently verified 160-case inventory.
This gate proves pre-projection models; it does not accept the shared-library
publication, queries, lifecycle, or performance gates.
"""
import argparse
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
sys.path.insert(0, str(ROOT / 'scripts'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json

spec = importlib.util.spec_from_file_location('jvm_models', ROOT / 'scripts/java-jvm-project-models.py')
models = importlib.util.module_from_spec(spec)
spec.loader.exec_module(models)


def check_inventory(inventory):
    if (inventory.get('scope_model_inventory_accepted') is not True
            or inventory.get('all_contexts_executed') is not True
            or inventory.get('unexplained_failures') != []
            or inventory.get('expected_count') != 160
            or inventory.get('executed_count') != 160
            or inventory.get('changed_inputs') != []):
        raise ValueError('COMPLETE_VERIFIED_JVM_MODELS_REQUIRED')
    cases = inventory['cases']
    if len(cases) != 160 or len({row['id'] for row in cases}) != 160:
        raise ValueError('NATIVE_MODEL_CASE_COVERAGE')
    for row in cases:
        for label in ('reference', 'migrated'):
            models.verified_categories(row['results'][label])
            execution = row['executions'][label]
            if execution['status'] != 'passed' or execution['unexpected_signal'] is not None:
                raise ValueError('JVM_MODEL_EXECUTION_NOT_ACCEPTED')
        if len([path for path in row['proofs'] if path.endswith('/context.json')]) != 1:
            raise ValueError('SEALED_MODEL_CONTEXT_REQUIRED')
    return cases


def check_jvm_gate(gate):
    if (gate.get('kind') != 'complete_jvm_migration_acceptance'
            or gate.get('status') != 'passed'
            or gate.get('jvm_migration_accepted') is not True
            or gate.get('unexplained_failures') != [] or not gate.get('inputs')):
        raise ValueError('COMPLETE_JVM_PROJECT_GATE_REQUIRED_BEFORE_NATIVE')


def main():
    parser = argparse.ArgumentParser()
    for name in ('models', 'jvm-gate', 'inputs-manifest', 'runtime-root', 'evidence'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=3600)
    args = parser.parse_args()
    args.evidence = args.evidence.resolve()
    if not 1 <= args.timeout_seconds <= 86400:
        parser.error('timeout must be within 1..86400 seconds')
    gate = json.loads(args.jvm_gate.read_text())
    check_jvm_gate(gate)
    inventory = json.loads(args.models.read_text())
    cases = check_inventory(inventory)
    manifest = args.inputs_manifest.resolve()
    if inventory['inputs'].get(str(manifest)) != sha256(manifest):
        raise ValueError('SEALED_JVM_MANIFEST_REQUIRED')
    reactor = json.loads(manifest.read_text())
    contexts = [(module['id'], scope) for module in reactor['modules']
                if module.get('selected', True)
                for scope in ('MAIN', 'TEST')]
    if len(reactor['modules']) != 40 or len(set(contexts)) != 80:
        raise ValueError('NATIVE_MODEL_FULL_REACTOR_REQUIRED')
    expected = {mode + ':' + module + '@' + scope
                for mode in ('raw', 'ast') for module, scope in contexts}
    if {row['id'] for row in cases} != expected:
        raise ValueError('NATIVE_MODEL_FULL_REACTOR_CASE_COVERAGE')
    runtime = args.runtime_root.resolve()
    probe = runtime / 'target/java-migration-probe'
    executable = probe / 'native-model-witness'
    provenance = json.loads((probe / 'build-provenance.json').read_text())
    required = {str((runtime / 'lang-java-adapotr/native-bridge/target/native-bridge-0.1.0.jar').resolve()),
                str(executable.resolve()), str((probe / 'reflection.json').resolve()),
                str((runtime / 'scripts/build-java-migration-probe.sh').resolve())}
    required.update(str((runtime / 'tests/java-migration' / name).resolve()) for name in
                    ('ParityWitness.java', 'NativeModelWitness.java', 'ReflectionInventory.java'))
    required.update(str(path.resolve()) for path in (probe / 'classes').rglob('*.class'))
    if set(provenance) != required:
        raise ValueError('NATIVE_MODEL_PROVENANCE_COVERAGE')
    proofs = {str((pathlib.Path(path) if pathlib.Path(path).is_absolute()
                   else ROOT / path).resolve()): checksum
              for path, checksum in inventory['inputs'].items()}
    for path, checksum in gate['inputs'].items():
        if path in proofs and proofs[path] != checksum:
            raise ValueError('JVM_GATE_MODEL_PROOF_CONFLICT: ' + path)
        proofs[path] = checksum
    proofs.update(provenance)
    for path in (args.models, args.jvm_gate, probe / 'build-provenance.json', pathlib.Path(__file__)):
        proofs[str(path.resolve())] = sha256(path)
    if sha256(runtime / 'tests/java-migration/ParityWitness.java') != sha256(ROOT / 'tests/java-migration/ParityWitness.java'):
        raise ValueError('NATIVE_MODEL_WITNESS_RULES_DRIFT')
    for path, expected in proofs.items():
        if sha256(path) != expected:
            raise ValueError('NATIVE_MODEL_INPUT_DRIFT: ' + path)
    args.evidence.mkdir(parents=True, exist_ok=False)
    receipt = args.evidence / 'receipt.json'
    report = dict(status='running', kind='native_preprojection_model_replay',
        expected_count=160, cases=[], inputs=proofs, backend='native',
        complete_native_model_gate=False, native_accepted=False,
        strict_reference_equal=False, time_is_performance_gate=False,
        signature_cache_policy='shared_per_mode_native_only')
    atomic_json(receipt, report)
    started = time.monotonic()
    try:
        for number, row in enumerate(cases):
            directory = args.evidence / str(number)
            directory.mkdir()
            context_path = next(path for path in row['proofs'] if path.endswith('/context.json'))
            captured = json.loads(pathlib.Path(context_path).read_text())
            consumer = next(c for c in captured['contexts'] if not c.get('prerequisite'))
            if (row['id'].split(':', 1)[1] != consumer['module'] + '@' + consumer['scope']
                    or row['source_count'] != len(consumer['sources'])
                    or row['published_source_count'] != sum(s['publish'] for s in consumer['sources'])):
                raise ValueError('NATIVE_MODEL_CAPTURE_CASE_IDENTITY')
            for context in captured['contexts']:
                for source in context['sources']:
                    if sha256(source['input']) != source['hash']:
                        raise ValueError('NATIVE_MODEL_CAPTURE_DRIFT')
                for resource in context.get('resources', []):
                    if sha256(resource['input']) != resource['unit']['hash']:
                        raise ValueError('NATIVE_MODEL_RESOURCE_DRIFT')
            scratch = directory / 'scratch'
            scratch.mkdir()
            mode = row['id'].split(':', 1)[0]
            cache = args.evidence / ('cache-' + mode)
            cache.mkdir(exist_ok=True)
            request = dict(version=8, full=True, facts=True, java_home=str(models.JDK),
                scratch=str(scratch), contexts=captured['contexts'],
                defer_fact_dedup=True, signature_cache=True, cache_root=str(cache),
                compiler_timeout_seconds=1800, compiler_heap_bytes=512 << 20,
                result_bytes=8 << 30, fact_limit=5000000)
            path = directory / 'request.json'
            atomic_json(path, request)
            execution = models.execute([str(executable), str(path)], directory, 'native',
                args.timeout_seconds, dict(os.environ, CODEANA_MODEL_ROW_EVIDENCE=str(directory / 'native-rows')))
            result = dict(id=row['id'], status='failed', execution=execution)
            if execution['status'] == 'passed':
                value = models.verified_categories(json.loads(
                    (directory / 'native.stdout').read_text().splitlines()[-1]))
                result['models'] = value
                result['jvm_differences'] = sorted(name for name in models.CATEGORIES
                    if value[name] != row['results']['migrated'][name])
                result['reference_differences'] = sorted(name for name in models.CATEGORIES
                    if value[name] != row['results']['reference'][name])
                # JVM known issues are exact reference observations. A new
                # native difference cannot be hidden by their presence.
                result['status'] = 'passed' if not result['jvm_differences'] else 'failed'
            result['proofs'] = {str(p.resolve()): sha256(p) for p in directory.rglob('*')
                if p.is_file() and 'scratch' not in p.relative_to(directory).parts}
            report['cases'].append(result)
            atomic_json(receipt, report)
            print(json.dumps(dict(id=result['id'], status=result['status'])), flush=True)
        report['changed_inputs'] = [path for path, expected in proofs.items() if sha256(path) != expected]
        report['executed_count'] = len(report['cases'])
        report['status'] = 'passed' if (len(report['cases']) == 160
            and all(row['status'] == 'passed' for row in report['cases'])
            and not report['changed_inputs']) else 'failed'
        report['complete_native_model_gate'] = report['status'] == 'passed'
        report['strict_reference_equal'] = report['status'] == 'passed' and all(
            not row.get('reference_differences') for row in report['cases'])
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    finally:
        report['seconds'] = time.monotonic() - started
        atomic_json(receipt, report)
    return 0 if report['complete_native_model_gate'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
