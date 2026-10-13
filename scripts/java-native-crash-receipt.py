#!/usr/bin/env python3
"""Record the existing genuine ABI crash assertions without changing sealed tests."""
import argparse
import contextlib
import hashlib
import importlib
import json
import os
from pathlib import Path
import shutil
import sys
import traceback


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-root', type=Path, required=True)
    parser.add_argument('--bin-root', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime_root.resolve()
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    scratch = evidence / 'scratch'
    scratch.mkdir()
    os.environ.update(CODEANA_JAVA_BACKEND='native',
                      CODEANA_TEST_BIN=str(args.bin_root.resolve()),
                      CODEANA_TEST_JAVA_BUNDLE=str(runtime / 'lang-java-adapotr/target/native'),
                      TMPDIR=str(scratch))
    sys.path.insert(0, str(runtime / 'tests'))
    fixture = importlib.import_module('java_native_crash_e2e')
    product = fixture.java_e2e.JavaProduct

    class RecordedProduct(product):
        def doCleanups(self):
            # Copy before TemporaryDirectory cleanup; retain both success and failure.
            if hasattr(self, 'root') and self.root.exists():
                shutil.copytree(self.root, evidence / 'workspace', dirs_exist_ok=True)
            return super().doCleanups()

    fixture.java_e2e.JavaProduct = RecordedProduct
    original_run = fixture.subprocess.run
    crash_results = []

    def recorded_run(command, *arguments, **options):
        result = original_run(command, *arguments, **options)
        if (isinstance(command, (list, tuple)) and command
                and Path(command[0]).name == 'codeana-cli'
                and 'index' in command and '--full' in command and result.returncode != 0):
            crash_results.append(dict(command=list(map(str, command)),
                                      returncode=result.returncode,
                                      stdout=result.stdout, stderr=result.stderr))
        return result

    fixture.subprocess.run = recorded_run
    report = dict(kind='native_actual_crash_isolation', status='failed', actual_signal=None,
                  old_head_preserved=False, daemon_alive=False, worker_reaped=False)
    log = evidence / 'run.log'
    with log.open('w') as stream, contextlib.redirect_stdout(stream), contextlib.redirect_stderr(stream):
        try:
            fixture.main()
            # These three assertions are executed in the original fixture after SIGSEGV.
            (evidence / 'crash-command.json').write_text(json.dumps(crash_results, indent=2) + '\n')
            signal_evidence = ''.join((row['stdout'] or '') + (row['stderr'] or '')
                                      for row in crash_results if row['returncode'] != 0)
            if 'signal: 11' not in signal_evidence and 'SIGSEGV' not in signal_evidence:
                raise AssertionError('ACTUAL_SIGSEGV_LOG_REQUIRED')
            report.update(status='passed', actual_signal=11, old_head_preserved=True,
                          daemon_alive=True, worker_reaped=True)
        except BaseException as error:
            report.update(failure_type=type(error).__name__, failure=str(error))
            traceback.print_exc()
        finally:
            fixture.subprocess.run = original_run
            (evidence / 'crash-command.json').write_text(json.dumps(crash_results, indent=2) + '\n')
    paths = [Path(__file__).resolve(), Path(fixture.__file__).resolve(),
             runtime / 'tests/java_e2e.py', runtime / 'tests/rust_e2e.py', log,
             evidence / 'crash-command.json']
    paths += list((evidence / 'workspace').rglob('*'))
    report['inputs'] = {str(path): sha(path) for path in paths if path.is_file()}
    report['sigkill_observed'] = 'SIGKILL' in log.read_text() or 'signal: 9' in log.read_text()
    (evidence / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
    print(evidence / 'receipt.json', flush=True)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
