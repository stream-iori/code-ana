#!/usr/bin/env python3
"""Executable gates and evidence receipts; fixture success never claims full parity."""
import argparse
import hashlib
import json
import os
import pathlib
import signal
import subprocess
import sys
import time
from java_product_runtime import inventory

ROOT = pathlib.Path(__file__).resolve().parents[1]


def capability_results(matrix, gates):
    """Declarations of progress cannot substitute for executed acceptance gates."""
    outcomes = {g['name']: g for g in gates}
    results = []
    seen = set()
    for capability in matrix['capabilities']:
        identity = capability['id']
        if identity in seen:
            raise ValueError('DUPLICATE_CAPABILITY: ' + identity)
        seen.add(identity)
        blockers = list(capability.get('remaining_acceptance', []))
        if capability.get('implementation_complete') is not True:
            blockers.append('implementation_not_complete')
        checks = capability.get('checks', [])
        if not checks:
            blockers.append('no_capability_checks')
        for check in checks:
            if not (ROOT / check).is_file():
                blockers.append('missing_check:' + check)
        required = capability.get('required_gates', [])
        if not required:
            blockers.append('no_required_gates')
        for gate in required:
            if gate not in outcomes:
                blockers.append('gate_not_executed:' + gate)
            elif outcomes[gate]['status'] != 'passed':
                blockers.append('gate_failed:' + gate)
        results.append(dict(id=identity, status='blocked' if blockers else 'passed',
                            blockers=blockers, required_gates=required, checks=checks))
    if len(seen) != 52:
        raise ValueError('EXPECTED_52_CAPABILITIES')
    return results


def input_files():
    paths = [ROOT / name for name in ('Cargo.toml','Cargo.lock','lang-adaptor/pom.xml','lang-adaptor/.sdkmanrc','justfile')]
    extensions = {'.rs','.java','.py','.sh','.proto','.toml','.xml','.json'}
    for directory in ('crates','lang-adaptor/src','scripts','tests','proto'):
        paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in extensions
                  and not {'target','__pycache__'}.intersection(p.relative_to(ROOT).parts)]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--level', choices=('fixtures', 'native', 'full'), default='full')
    args = parser.parse_args()
    evidence = ROOT / 'target/java-alignment/evidence' / (time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    evidence.mkdir(parents=True)
    frozen = input_files()
    inputs = evidence / 'input-files.json'
    inputs.write_text(json.dumps(frozen, sort_keys=True, indent=2) + '\n')
    gates = [
        ('acceptance-harness', [sys.executable, 'tests/java_alignment_gate_tests.py'], 30),
        ('inventory-diff-harness', [sys.executable, 'tests/java_inventory_diff_tests.py'], 30),
        ('acceptance-recovery', [sys.executable, 'tests/java_real_acceptance_tests.py'], 30),
        ('java-fixtures', ['./scripts/test-java.sh'], 14400),
        ('type-reference', [sys.executable, 'tests/java_type_reference_reference.py'], 360),
        ('functional-reference', [sys.executable, 'tests/java_functional_reference_alignment.py'], 360),
        ('supplemental-reference', [sys.executable, 'tests/java_supplemental_reference_alignment.py'], 360),
        ('module-abi-reference', [sys.executable, 'tests/java_module_abi_reference_alignment.py'], 360),
        ('written-annotation-reference', [sys.executable, 'tests/java_written_annotation_reference_alignment.py'], 360),
        ('module-annotation-reference', [sys.executable, 'tests/java_module_annotation_reference_alignment.py'], 360),
        ('framework-reference', [sys.executable, 'tests/java_framework_reference_alignment.py'], 360),
        ('declaration-reference', [sys.executable, 'tests/java_declaration_reference_alignment.py'], 360),
        ('registration-reference', [sys.executable, 'tests/java_registration_reference_alignment.py'], 360),
        ('annotation-reference', [sys.executable, 'tests/java_annotation_reference_alignment.py'], 360),
        ('override-reference', [sys.executable, 'tests/java_override_reference_alignment.py'], 360),
        ('field-reference', [sys.executable, 'tests/java_field_reference_alignment.py'], 360),
        ('xml-reference', [sys.executable, 'tests/java_xml_reference_alignment.py'], 360),
        ('xml-reference-defect', [sys.executable, 'tests/java_xml_reference_alignment.py', '--reference-defect'], 360),
        ('sofa-xml-reference', [sys.executable, 'tests/java_sofa_xml_reference_alignment.py'], 360),
        ('maven-export', [sys.executable, 'tests/java_maven_export.py'], 180),
        ('maven-mediation', [sys.executable, 'tests/java_maven_alignment.py'], 240),
        ('rust-format', ['cargo', 'fmt', '--all', '--', '--check'], 60),
        ('rust-unit', ['cargo', 'test', '--workspace', '--locked'], 240),
        ('rust-clippy', ['cargo', 'clippy', '--workspace', '--all-targets', '--locked', '--', '-D', 'warnings'], 240),
    ]
    if args.level in ('native', 'full'):
        gates += [
            ('rust-build', ['cargo', 'build', '--workspace', '--locked'], 240),
            ('rust-release-build', ['cargo', 'build', '--workspace', '--release', '--locked'], 600),
            ('native-build', ['./scripts/build-java-native.sh'], 600),
            ('native-fixtures', [sys.executable, 'tests/java_native_fixture_alignment.py'], 14400),
            ('type-graph-native', [sys.executable, 'tests/java_graph_fact_alignment.py', '--native'], 600),
            ('type-graph-consumers-native', [sys.executable, 'tests/java_graph_consumer_alignment.py', '--native'], 7200),
            ('type-graph-bindings-native', [sys.executable, 'tests/java_graph_consumer_alignment.py', '--bindings', '--native'], 7200),
            ('default-type-graph-native', [sys.executable, 'tests/java_default_graph_alignment.py', '--native'], 600),
            ('native-probe', [sys.executable, 'tests/java_native_probe.py'], 300),
            ('type-reference-native', [sys.executable, 'tests/java_type_reference_reference.py', '--native'], 360),
            ('functional-reference-native', [sys.executable, 'tests/java_functional_reference_alignment.py', '--native'], 360),
            ('supplemental-reference-native', [sys.executable, 'tests/java_supplemental_reference_alignment.py', '--native'], 360),
            ('module-abi-reference-native', [sys.executable, 'tests/java_module_abi_reference_alignment.py', '--native'], 360),
            ('written-annotation-reference-native', [sys.executable, 'tests/java_written_annotation_reference_alignment.py', '--native'], 360),
            ('module-annotation-reference-native', [sys.executable, 'tests/java_module_annotation_reference_alignment.py', '--native'], 360),
            ('framework-reference-native', [sys.executable, 'tests/java_framework_reference_alignment.py', '--native'], 360),
            ('declaration-reference-native', [sys.executable, 'tests/java_declaration_reference_alignment.py', '--native'], 360),
            ('registration-reference-native', [sys.executable, 'tests/java_registration_reference_alignment.py', '--native'], 360),
            ('annotation-reference-native', [sys.executable, 'tests/java_annotation_reference_alignment.py', '--native'], 360),
            ('override-reference-native', [sys.executable, 'tests/java_override_reference_alignment.py', '--native'], 360),
            ('field-reference-native', [sys.executable, 'tests/java_field_reference_alignment.py', '--native'], 360),
            ('xml-reference-native', [sys.executable, 'tests/java_xml_reference_alignment.py', '--native'], 360),
            ('xml-reference-defect-native', [sys.executable, 'tests/java_xml_reference_alignment.py', '--native', '--reference-defect'], 360),
            ('sofa-xml-reference-native', [sys.executable, 'tests/java_sofa_xml_reference_alignment.py', '--native'], 360),
            ('native-product-ipc', ['cargo', 'run', '-p', 'codeana-application', '--example', 'java_worker_ipc', '--locked'], 240),
        ]
    if args.level == 'full':
        gates += [
            ('java-product-runtime', [sys.executable, 'tests/java_product_runtime.py',
                                      '--destination', str(evidence / 'product-runtime')], 60),
            ('rust-daemon', [sys.executable, 'tests/rust_e2e.py'], 600),
            ('java-daemon', [sys.executable, 'tests/java_e2e.py'], 14400),
            ('native-daemon-crash', [sys.executable, 'tests/java_native_crash_e2e.py'], 300),
            ('java-release', [sys.executable, 'tests/java_release.py'], 3000),
        ]
    matrix = json.loads((ROOT / 'tests/java_alignment_matrix.json').read_text())
    report = dict(level=args.level, reference_commit=matrix['reference_commit'], gates=[],
                  inputs=dict(path=str(inputs.relative_to(ROOT)), count=len(frozen),
                              sha256=hashlib.sha256(inputs.read_bytes()).hexdigest()),
                  acceptance_profile=matrix['acceptance_profile'],
                  parity_pending=[c['id'] for c in matrix['capabilities']],
                  alignment_complete=False)
    receipt = evidence / 'receipt.json'
    product_artifacts = None
    for name, command, timeout in gates:
        started = time.monotonic()
        log = evidence / (name + '.log')
        with log.open('wb') as output:
            env = os.environ.copy()
            if name in ('java-daemon', 'native-daemon-crash'):
                env['CODEANA_TEST_BIN'] = str(evidence / 'product-runtime/target/debug')
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            try:
                code = process.wait(timeout=timeout)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                code = 124
        current = input_files()
        drifted = sorted(p for p in frozen.keys() | current.keys() if frozen.get(p) != current.get(p))
        runtime = evidence / 'product-runtime'
        if name == 'java-product-runtime' and code == 0:
            product_artifacts = inventory(runtime)
            report['product_runtime'] = json.loads((evidence / 'product-runtime.json').read_text())
        artifact_drift = []
        if product_artifacts is not None:
            actual = inventory(runtime)
            artifact_drift = sorted(p for p in product_artifacts.keys() | actual.keys()
                                    if product_artifacts.get(p) != actual.get(p))
        status = 'passed' if code == 0 and not drifted and not artifact_drift else 'failed'
        report['gates'].append(dict(name=name, command=command, status=status, exit_code=code,
                                   elapsed_seconds=round(time.monotonic() - started, 3),
                                   log=str(log.relative_to(ROOT)),
                                   log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),
                                   changed_inputs=drifted, changed_runtime_artifacts=artifact_drift))
        receipt.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(f'{name}: {status}', flush=True)
        if drifted or artifact_drift:
            print('Input changed during verification: ' + ', '.join(drifted), flush=True)
            if artifact_drift:
                print('Frozen runtime changed during verification: ' + ', '.join(artifact_drift), flush=True)
            break
    report['capability_results'] = capability_results(matrix, report['gates'])
    report['parity_pending'] = [c['id'] for c in report['capability_results'] if c['status'] != 'passed']
    report['missing_final_gates'] = [name for name in matrix['required_final_gates']
                                   if not any(g['name'] == name and g['status'] == 'passed'
                                              for g in report['gates'])]
    report['gates_passed'] = all(g['status'] == 'passed' for g in report['gates'])
    report['alignment_complete'] = (args.level == 'full' and report['gates_passed']
                                    and not report['parity_pending'] and not report['missing_final_gates'])
    report['artifacts'] = {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in [ROOT/'lang-adaptor/target/compiler-bridge.jar',
                                     ROOT/'lang-adaptor/target/native/manifest.json',
                                     ROOT/'lang-adaptor/target/native/libcodeana_java.dylib',
                                     ROOT/'target/debug/codeana-java-worker'] if p.is_file()}
    receipt.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Evidence: ' + str(receipt.relative_to(ROOT)))
    if not report['gates_passed']:
        return 1
    if args.level == 'full' and (report['parity_pending'] or report['missing_final_gates']):
        print('Remaining capability acceptance: ' + ', '.join(report['parity_pending']))
        print('Remaining final gates: ' + ', '.join(report['missing_final_gates']))
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
