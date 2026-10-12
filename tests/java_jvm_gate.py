#!/usr/bin/env python3
"""Bind the JVM gate to source-derived case IDs and fresh Surefire reports."""
import argparse
import json
import os
import pathlib
import re
import sys

from acceptance_support import identities, read_surefire, run_command, summarize
from java_source_cases import test_cases

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'lang-java-adapotr'


def source_cases():
    expected = []
    for path in sorted(PROJECT.glob('*/src/test/java/**/*.java')):
        if path.relative_to(PROJECT).parts[0] == 'native-bridge':
            continue
        text = path.read_text()
        expected.extend(test_cases(text, path.stem))
    return expected


def source_paths():
    return ([p for p in PROJECT.rglob('*') if p.is_file() and 'target' not in p.relative_to(PROJECT).parts]
            + [ROOT / 'scripts/build-java-jvm.sh', ROOT / 'scripts/java-jvm-manifest.py',
               ROOT / 'tests/java_source_cases.py', pathlib.Path(__file__)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    expected = source_cases()
    (evidence / 'expected-cases.json').write_text(json.dumps(expected, indent=2) + '\n')
    before = identities(source_paths())
    for report in PROJECT.glob('*/target/surefire-reports/TEST-*.xml'):
        report.unlink()
    execution = run_command([str(ROOT / 'scripts/build-java-jvm.sh')], evidence / 'build.log',
                            1800, cwd=ROOT, env=os.environ.copy())
    cases = read_surefire(sorted(PROJECT.glob('*/target/surefire-reports/TEST-*.xml')))
    receipt = summarize(expected, cases, before, identities(source_paths()))
    receipt.update(backend='jvm', execution=execution, sources=before, native_accepted=False,
                   complete_capability_migration_accepted=False)
    if execution['status'] != 'passed':
        receipt['status'] = 'failed'
    bundle = PROJECT / 'target/jvm/manifest.json'
    if bundle.is_file():
        receipt['artifacts'] = json.loads(bundle.read_text())
    (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({k: receipt[k] for k in ['status', 'expected_count', 'executed_count', 'missing', 'duplicate', 'unexpected', 'artifact_drift']}))
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
