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
    paths = [ROOT / name for name in ('Cargo.toml','Cargo.lock','lang-java-adapotr/pom.xml','lang-java-adapotr/.sdkmanrc','justfile')]
    extensions = {'.rs','.java','.py','.sh','.proto','.toml','.xml','.json','.sql'}
    for directory in ('crates','lang-java-adapotr','scripts','tests','proto'):
        paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in extensions
                  and not {'target','__pycache__'}.intersection(p.relative_to(ROOT).parts)]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}


def main():
    """Retained recipe entry; the old DetachedModel gates are no longer executable."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--level', choices=('fixtures', 'native', 'full'), default='fixtures')
    parser.add_argument('--evidence', type=pathlib.Path)
    parser.add_argument('--runtime-root', type=pathlib.Path, default=ROOT)
    parser.add_argument('--final-receipt', type=pathlib.Path)
    args = parser.parse_args()
    if args.level == 'full':
        if args.final_receipt is None:
            parser.error('--level full requires a sealed --final-receipt; fixture success cannot close migration')
        receipt = json.loads(args.final_receipt.read_text())
        required = ('complete_capability_migration_accepted', 'jvm_migration_accepted',
                    'native_accepted', 'coverage_closed', 'performance_accepted')
        if receipt.get('status') != 'passed' or not all(receipt.get(key) is True for key in required):
            raise ValueError('COMPLETE_MIGRATION_RECEIPT_REQUIRED')
        if receipt.get('unexplained_failures') != [] or not receipt.get('inputs'):
            raise ValueError('COMPLETE_MIGRATION_INPUT_PROOFS_REQUIRED')
        for name, expected in receipt['inputs'].items():
            path = pathlib.Path(name)
            if not path.is_absolute():
                raise ValueError('ABSOLUTE_MIGRATION_PROOF_REQUIRED')
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1 << 20), b''):
                    digest.update(block)
            if digest.hexdigest() != expected:
                raise ValueError('MIGRATION_PROOF_DRIFT: ' + name)
        print('Sealed migration receipt verified: ' + str(args.final_receipt.resolve()))
        return 0
    evidence = (args.evidence or ROOT / 'target/java-alignment' /
                (args.level + '-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))).resolve()
    runtime = args.runtime_root.resolve()
    if args.level == 'fixtures':
        commands = [['just', 'test-java-jvm-fixtures', str(evidence)]]
    else:
        commands = [['just', 'test-java-native-' + group, str(evidence / group)]
                    for group in ('probe', 'product', 'lifecycle')]
    for command in commands:
        result = subprocess.run(command, cwd=runtime)
        if result.returncode:
            return result.returncode
    print('Fixture gates passed; full migration/performance remain separately sealed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
