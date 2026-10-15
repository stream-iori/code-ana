#!/usr/bin/env python3
"""Close coverage only with an executed disposition for every frozen original test."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_source_cases import test_cases, other_annotations
from java_coverage_scope import analyzer_test_source


def verify_proofs(receipt, proofs):
    inputs = receipt.get('inputs')
    if not isinstance(inputs, dict) or not inputs:
        raise ValueError('COVERAGE_EXECUTION_PROOFS_REQUIRED')
    for filename, checksum in inputs.items():
        path = Path(filename)
        if not path.is_absolute():
            path = ROOT / path
        path = path.resolve()
        if sha256(path) != checksum:
            raise ValueError('COVERAGE_PROOF_DRIFT: ' + str(path))
        previous = proofs.get(str(path))
        if previous is not None and previous != checksum:
            raise ValueError('COVERAGE_PROOF_CONFLICT: ' + str(path))
        proofs[str(path)] = checksum


def junit_cases(paths):
    cases = set()
    for path in paths:
        if not path.name.startswith('TEST-') or path.suffix != '.xml':
            continue
        suite = ET.parse(path).getroot()
        for test in suite.iter('testcase'):
            if any(test.find(name) is not None for name in ['failure', 'error', 'skipped']):
                raise ValueError('UNPASSED_COVERAGE_JUNIT_CASE: ' + str(path))
            cases.add(test.attrib['classname'] + '#' + test.attrib['name'].split('(')[0])
    return cases


def main():
    parser = argparse.ArgumentParser()
    for name in ['inventory', 'jvm-gate', 'rust-gate', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--extra-java-receipt', action='append', type=Path, default=[])
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('NEW_COVERAGE_RECEIPT_REQUIRED')
    inputs = {str(path.resolve()): sha256(path) for path in [args.inventory, args.jvm_gate,
              args.rust_gate, Path(__file__), ROOT / 'scripts/java-coverage-inventory.py',
              ROOT / 'tests/java_coverage_scope.py', ROOT / 'tests/java_source_cases.py',
              ROOT / 'lang-java-adaptor/upstream-sources.json', ROOT / 'lang-java-adaptor/upstream-archive.json',
              ROOT / 'lang-java-adaptor/rust-coverage-mappings.json']}
    report = dict(status='failed', coverage_inventory_closed=False, native_accepted=False,
                  performance_accepted=False, unresolved_files=[], unexecuted_cases=[],
                  unsupported_annotations=[], inputs=inputs)
    try:
        inventory = json.loads(args.inventory.read_text())
        upstream = ROOT.parent / 'anatomist'
        tree = subprocess.check_output(['git', '-C', str(upstream), 'ls-tree', '-r',
                                        inventory['commit']], text=True).splitlines()
        paths = [line.split('\t', 1)[1] for line in tree if line.split()[1] == 'blob']
        expected = {path for path in paths if re.match(r'anatomist-(core|app|framework-spring|framework-sofa)/(src/|pom.xml)', path)
                    or path.startswith(('fixtures/', 'e2e/fixtures/', 'tests/scenarios/'))}
        rows = {row['source']: row for row in inventory['files']}
        if len(rows) != len(inventory['files']) or set(rows) != expected:
            raise ValueError('COMPLETE_FROZEN_COVERAGE_UNIVERSE_REQUIRED')
        for path, row in rows.items():
            original = subprocess.check_output(['git', '-C', str(upstream), 'show', inventory['commit'] + ':' + path])
            if hashlib.sha256(original).hexdigest() != row['sha256']:
                raise ValueError('ORIGINAL_COVERAGE_SOURCE_DRIFT: ' + path)
            if analyzer_test_source(path):
                if (row.get('test_cases') != test_cases(original.decode(), Path(path).stem)
                        or row.get('other_test_annotations') != other_annotations(original.decode())):
                    raise ValueError('ORIGINAL_CASE_OMISSION: ' + path)
        project = ROOT / 'lang-java-adaptor'
        ledger = json.loads((project / 'upstream-sources.json').read_text())
        retained = {row['source']: row for row in ledger['files']}
        archives = {row['source']: row for row in json.loads((project / 'upstream-archive.json').read_text())['files']}
        jvm = json.loads(args.jvm_gate.read_text()); rust = json.loads(args.rust_gate.read_text())
        if jvm.get('status') != 'passed' or jvm.get('jvm_migration_accepted') is not True:
            raise ValueError('FULL_JVM_REQUIRED_FOR_COVERAGE')
        if rust.get('status') != 'passed' or not rust.get('clippy') or not rust.get('fmt'):
            raise ValueError('CURRENT_RUST_REQUIRED_FOR_COVERAGE')
        verify_proofs(jvm, inputs); verify_proofs(rust, inputs)
        executed = junit_cases([Path(path) for path in inputs])
        # The accepted JVM gate seals the collector's complete case records.
        # Raw Surefire XML need not remain a mutable build output dependency.
        component_path = Path(jvm['receipts']['components']).resolve()
        if inputs.get(str(component_path)) != sha256(component_path):
            raise ValueError('SEALED_JVM_COMPONENT_CASES_REQUIRED')
        component = json.loads(component_path.read_text())
        if component.get('status') != 'passed' or any(component.get(key) for key in
                ['missing', 'duplicate', 'unexpected', 'expected_duplicate', 'artifact_drift']):
            raise ValueError('COMPLETE_JVM_COMPONENT_CASES_REQUIRED')
        if any(case.get('status') != 'passed' for case in component['cases']):
            raise ValueError('UNPASSED_JVM_COMPONENT_CASE')
        executed.update(case['id'] for case in component['cases'])
        for filename in args.extra_java_receipt:
            extra = json.loads(filename.read_text())
            if extra.get('status') != 'passed':
                raise ValueError('PASSING_ADDITIONAL_JAVA_AUDIT_REQUIRED')
            inputs[str(filename.resolve())] = sha256(filename)
            verify_proofs(extra, inputs)
            executed.update(junit_cases([Path(path) for path in extra['inputs']]))
        for path, row in rows.items():
            disposition = row['disposition']
            if disposition in ['pending', 'rust-pending']:
                report['unresolved_files'].append(path)
            elif disposition in ['copied', 'boundary-adaptation']:
                destination = ROOT / 'lang-java-adaptor' / row['destination']
                if sha256(destination) != retained[path]['migrated_sha256']:
                    raise ValueError('COPIED_ORIGINAL_SOURCE_DRIFT: ' + path)
                inputs[str(destination.resolve())] = sha256(destination)
                report['unexecuted_cases'].extend(case for case in row.get('test_cases', []) if case not in executed)
            elif disposition == 'rust':
                original = project / 'migrated-test-expectations' / path
                if sha256(original) != row['sha256']:
                    raise ValueError('MAPPED_ORIGINAL_BYTES_REQUIRED: ' + path)
                inputs[str(original.resolve())] = sha256(original)
                for case, details in row['case_mappings'].items():
                    filename = ROOT / details['evidence']; mapped = json.loads(filename.read_text())
                    if mapped.get('status') != 'passed':
                        raise ValueError('UNPASSED_MAPPED_ORIGINAL_CASE: ' + case)
                    inputs[str(filename.resolve())] = sha256(filename)
                    verify_proofs(mapped, inputs)
            elif disposition == 'archived':
                record = archives[path]
                if record['original_test_cases'] != row.get('test_cases', []):
                    raise ValueError('ARCHIVED_ORIGINAL_CASE_OMISSION: ' + path)
                if row.get('test_cases'):
                    if set(record['test_case_dispositions']) != set(row['test_cases']):
                        raise ValueError('INDIVIDUAL_ARCHIVE_RATIONALE_REQUIRED: ' + path)
                    for details in record['test_case_dispositions'].values():
                        if details.get('disposition') != 'archived_product_contract' or not details.get('reason'):
                            raise ValueError('INDIVIDUAL_ARCHIVE_RATIONALE_REQUIRED: ' + path)
                    original = project / record['archived_destination']
                    if sha256(original) != row['sha256']:
                        raise ValueError('ARCHIVED_ORIGINAL_BYTES_REQUIRED: ' + path)
                    inputs[str(original.resolve())] = sha256(original)
            else:
                raise ValueError('UNKNOWN_COVERAGE_DISPOSITION: ' + path)
            if row.get('other_test_annotations'):
                report['unsupported_annotations'].append(dict(source=path, annotations=row['other_test_annotations']))
        report.update(original_files=len(rows), original_cases=sum(len(row.get('test_cases', [])) for row in rows.values()))
        if any(report[key] for key in ['unresolved_files', 'unexecuted_cases', 'unsupported_annotations']):
            raise ValueError('ORIGINAL_COVERAGE_STILL_INCOMPLETE')
        report.update(status='passed', coverage_inventory_closed=True)
    except Exception as error:
        report['failure'] = str(error)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
