#!/usr/bin/env python3
"""Require the sealed complete JVM gate before building the migrated native core."""
import argparse
import hashlib
import json
import os
import pathlib

REQUIRED = {'components', 'probe', 'product', 'models', 'model_producer',
            'runtime_equivalence', 'publication', 'semantics', 'classification',
            'audit', 'queries', 'representative', 'query_matrix', 'lifecycle', 'history'}


def sha(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def check(receipt, root):
    gate = json.loads(receipt.read_text())
    if (gate.get('kind') != 'complete_jvm_migration_acceptance'
            or gate.get('jvm_migration_accepted') is not True
            or gate.get('status') != 'passed' or gate.get('unexplained_failures') != []
            or not gate.get('inputs') or set(gate.get('receipts', {})) != REQUIRED):
        raise ValueError('COMPLETE_JVM_GATE_REQUIRED_BEFORE_NATIVE')
    for filename, expected in gate['inputs'].items():
        if sha(filename) != expected:
            raise ValueError('JVM_ACCEPTANCE_PROOF_DRIFT: ' + filename)
    components = json.loads(pathlib.Path(gate['receipts']['components']).read_text())
    accepted = {name.split('/lang-java-adapotr/', 1)[1]: checksum
                for name, checksum in components['sources'].items() if '/lang-java-adapotr/' in name}
    count = 0
    found = set()
    for module in ('analysis-core', 'framework-spring', 'framework-sofa', 'java-bridge'):
        for path in (root / 'lang-java-adapotr' / module / 'src/main').rglob('*'):
            if not path.is_file(): continue
            relative = str(path.relative_to(root / 'lang-java-adapotr'))
            found.add(relative)
            if accepted.get(relative) != sha(path):
                raise ValueError('JVM_VERIFIED_PRODUCTION_SOURCE_REQUIRED: ' + relative)
            count += 1
    if count == 0:
        raise ValueError('JVM_VERIFIED_PRODUCTION_SOURCE_MISSING')
    expected = {name for name in accepted if any(name.startswith(module + '/src/main/')
                for module in ('analysis-core', 'framework-spring', 'framework-sofa', 'java-bridge'))}
    if found != expected:
        raise ValueError('JVM_VERIFIED_PRODUCTION_SOURCE_SET_CHANGED')
    return count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--receipt', type=pathlib.Path,
                        default=os.environ.get('CODEANA_JVM_ACCEPTANCE_RECEIPT'))
    parser.add_argument('--source-root', type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    if args.receipt is None:
        parser.error('CODEANA_JVM_ACCEPTANCE_RECEIPT must identify the complete verified JVM gate')
    print(json.dumps(dict(jvm_gate_verified=True, verified_production_files=check(args.receipt.resolve(), args.source_root.resolve()))))


if __name__ == '__main__':
    main()
