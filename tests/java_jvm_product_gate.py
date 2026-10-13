#!/usr/bin/env python3
"""Run explicit Java product/probe backends with complete case and artifact identity."""
import argparse
import importlib
import json
import os
import pathlib
import sys
import time
import unittest

from acceptance_support import identities, summarize

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROGRESS_FILE = None
REFERENCE_FILES = []


class ReceiptResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.cases = []

    def stopTest(self, test):
        super().stopTest(test)
        PROGRESS_FILE.write_text(json.dumps(dict(status='running', cases=self.cases), indent=2) + '\n')

    def addSuccess(self, test):
        super().addSuccess(test)
        self.cases.append(dict(id=test.id(), status='passed'))

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self.cases.append(dict(id=test.id(), status='failed', error=self._exc_info_to_string(err, test)))
        self.stream.writeln(self.cases[-1]['error'])
        self.stream.flush()

    def addError(self, test, err):
        super().addError(test, err)
        self.cases.append(dict(id=test.id(), status='failed', error=self._exc_info_to_string(err, test)))
        self.stream.writeln(self.cases[-1]['error'])
        self.stream.flush()

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self.cases.append(dict(id=test.id(), status='skipped', reason=reason))

    def addExpectedFailure(self, test, err):
        super().addExpectedFailure(test, err)
        self.cases.append(dict(id=test.id(), status='known_issue'))

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        self.cases.append(dict(id=test.id(), status='failed', error='unexpected_success'))


def flatten(suite):
    for test in suite:
        if isinstance(test, unittest.TestSuite):
            yield from flatten(test)
        else:
            yield test


def artifact_paths(output):
    return ([p for p in (output / 'runtime').rglob('*') if p.is_file()]
            + [p for p in (ROOT / 'tests').rglob('*') if p.is_file() and '__pycache__' not in p.parts]
            + REFERENCE_FILES)


def main():
    global PROGRESS_FILE, REFERENCE_FILES
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    parser.add_argument('--group', choices=('probe', 'product', 'graph', 'lifecycle'), required=True)
    parser.add_argument('--backend', choices=('jvm', 'native'), default='jvm')
    parser.add_argument('--case', action='append', default=[])
    args = parser.parse_args()
    if args.group == 'lifecycle' and args.backend != 'native':
        parser.error('C ABI lifecycle requires --backend native')
    output = args.evidence.resolve()
    output.mkdir(parents=True, exist_ok=False)
    PROGRESS_FILE = output / 'progress.json'
    os.environ['CODEANA_JAVA_BACKEND'] = args.backend
    # Resolve and verify the independent oracle before starting expensive cases.
    # A private source snapshot must be given an explicit manifest path.
    from java_fresh_reference import reference
    oracle, proof = reference()
    manifest = pathlib.Path(os.environ.get('ANATOMIST_REFERENCE_MANIFEST',
        ROOT / 'target/frozen-reference/manifest.json')).resolve()
    REFERENCE_FILES = [manifest, oracle] + [manifest.parent/'source'/name for name in proof['sources']]
    REFERENCE_FILES += [pathlib.Path(name) for name in proof['toolchain']]
    from java_product_runtime import prepare
    runtime = prepare(ROOT, output / 'runtime', args.backend,
                      stable_paths=args.backend == 'native' and args.group in ('graph', 'lifecycle'))
    os.environ['CODEANA_TEST_BIN'] = runtime['binary_directory']
    os.environ['CODEANA_TEST_JAVA_BUNDLE'] = str(output / 'runtime/target/lib/java' / args.backend)
    REFERENCE_FILES += [pathlib.Path(name) for name in runtime.get('application_files', {})]
    REFERENCE_FILES += [pathlib.Path(name) for name in runtime.get('installed_files', {})]
    module = importlib.import_module(dict(probe='java_anatomist_native',
        product='java_e2e', graph='java_graph_storage', lifecycle='java_native_lifecycle')[args.group])
    suite = unittest.defaultTestLoader.loadTestsFromModule(module)
    all_tests = list(flatten(suite))
    if args.case:
        selected = [test for test in all_tests if any(test.id().endswith('.' + name) for name in args.case)]
        if len(selected) != len(set(args.case)):
            raise ValueError('UNKNOWN_OR_DUPLICATE_CASE_SELECTION')
        suite = unittest.TestSuite(selected)
    expected = [test.id() for test in flatten(suite)]
    (output / 'expected-cases.json').write_text(json.dumps(expected, indent=2) + '\n')
    before = identities(artifact_paths(output))
    started = time.monotonic()
    with (output / 'tests.log').open('w') as log:
        result = unittest.TextTestRunner(stream=log, verbosity=2, resultclass=ReceiptResult).run(suite)
    receipt = summarize(expected, result.cases, before, identities(artifact_paths(output)))
    receipt.update(backend=args.backend, seconds=time.monotonic() - started, sources_and_artifacts=before,
                   native_accepted=False, group=args.group,
                   complete_group_suite=not args.case,
                   complete_group_gate=not args.case and receipt['status']=='passed',
                   complete_fixture_gate=False,
                   total_group_cases=len(all_tests))
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    PROGRESS_FILE.write_text(json.dumps(dict(status=receipt['status'], cases=result.cases), indent=2) + '\n')
    print(json.dumps({k: receipt[k] for k in ['status', 'expected_count', 'executed_count', 'missing', 'duplicate', 'artifact_drift']}))
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
