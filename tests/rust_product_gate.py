#!/usr/bin/env python3
"""Run every Rust CLI fixture, including debug-only publication fault points."""
import argparse
import importlib
import json
import os
import pathlib
import time
import unittest

from acceptance_support import identities, summarize
import java_jvm_product_gate as results

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    args = parser.parse_args()
    output = args.evidence.resolve()
    output.mkdir(parents=True, exist_ok=False)
    profile = json.loads((ROOT/'rust-build-profile.json').read_text())
    if profile['profile'] != 'debug':
        raise ValueError('DEBUG_BUILD_REQUIRED_FOR_FAULT_GATES')
    binaries = ROOT/'target/debug'
    from acceptance_support import sha256
    if any(sha256(binaries/name) != digest for name, digest in profile['files'].items()):
        raise ValueError('DEBUG_BINARY_DRIFT')
    os.environ['CODEANA_TEST_BIN'] = str(binaries)
    module = importlib.import_module('rust_graph_e2e')
    suite = unittest.defaultTestLoader.loadTestsFromModule(module)
    expected = [test.id() for test in results.flatten(suite)]
    results.PROGRESS_FILE = output/'progress.json'
    paths = [p for folder in ['tests','crates','proto','target/debug']
             for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    paths += [ROOT/'rust-build-profile.json', ROOT/'Cargo.toml', ROOT/'Cargo.lock']
    before = identities(paths)
    started = time.monotonic()
    (output/'expected-cases.json').write_text(json.dumps(expected, indent=2)+'\n')
    with (output/'tests.log').open('w') as log:
        result = unittest.TextTestRunner(stream=log, verbosity=2,
            resultclass=results.ReceiptResult).run(suite)
    report = summarize(expected, result.cases, before, identities(paths))
    report.update(seconds=time.monotonic()-started, profile='debug', inputs=before,
        complete_rust_cli_gate=report['status']=='passed', native_accepted=False)
    (output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['status','expected_count','executed_count','missing','artifact_drift']}))
    return 0 if report['complete_rust_cli_gate'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
