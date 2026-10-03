#!/usr/bin/env python3
"""Verify already-built fixture artifacts without rebuilding or changing a running gate."""
import argparse
import json
import os
import pathlib
import re
import shlex
import signal
import subprocess
import sys
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference


def preflight(root):
    target = root/'lang-adaptor/target'
    required = [target/'compiler-bridge.jar', target/'native-classpath.txt',
                target/'classes/dev/codeana/analysis/JavaAnalysis.class',
                target/'test-classes/dev/codeana/analysis/AnalysisProbe.class']
    missing = [str(path) for path in required if not path.is_file()]
    classpath = target/'native-classpath.txt'
    if classpath.is_file():
        entries = classpath.read_text().strip().split(os.pathsep)
        missing += [entry for entry in entries if not entry or not pathlib.Path(entry).is_file()]
    return dict(passed=not missing, missing=missing)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', default='java-fixtures-built')
    parser.add_argument('--failfast', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch('[a-zA-Z0-9_-]+', args.label):
        parser.error('label must be a simple filename')
    root = alignment.ROOT
    evidence = root/'target/java-alignment/evidence'/(args.label+'-'+time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir()
    commands = []
    for line in (root/'scripts/test-java.sh').read_text().splitlines():
        if line.startswith('python3 "$repo_root/tests/'):
            command = shlex.split(line.replace('$repo_root/', ''))
            commands.append([sys.executable, *command[1:]])
    if not commands:
        raise ValueError('NO_FIXTURE_COMMANDS')
    preparation = preflight(root)
    receipt = evidence/'receipt.json'
    if not preparation['passed']:
        receipt.write_text(json.dumps(dict(all_ok=False, preflight=preparation, gates=[],
            full_semantic_diff_accepted=False, rebuild_performed=False), indent=2)+'\n')
        print('FIXTURE_ARTIFACTS_MISSING: '+json.dumps(preparation['missing']), flush=True)
        print(receipt, flush=True)
        return 1
    inputs, artifacts = alignment.input_files(), reference.probe_artifacts(False)
    report = dict(inputs=inputs, artifacts=artifacts, gates=[], all_ok=False,
                  full_semantic_diff_accepted=False, rebuild_performed=False, preflight=preparation)
    total_started = time.monotonic()
    for index, command in enumerate(commands):
        started = time.monotonic()
        log = evidence/(f'{index:02d}-'+pathlib.Path(command[1]).stem+'.log')
        with log.open('wb') as stream:
            child = subprocess.Popen(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT,
                                     env=os.environ.copy(), start_new_session=True)
            try:
                code = child.wait(timeout=1800)
            except (subprocess.TimeoutExpired, KeyboardInterrupt) as stopped:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
                code = 130 if isinstance(stopped, KeyboardInterrupt) else 124
        current, current_artifacts = alignment.input_files(), reference.probe_artifacts(False)
        drift = sorted(p for p in inputs.keys() | current.keys() if inputs.get(p) != current.get(p))
        artifact_drift = sorted(p for p in artifacts.keys() | current_artifacts.keys()
                                if artifacts.get(p) != current_artifacts.get(p))
        report['gates'].append(dict(command=command, exit_code=code, passed=code == 0 and not drift and not artifact_drift,
                                  elapsed_seconds=round(time.monotonic()-started, 3), changed_inputs=drift,
                                  changed_artifacts=artifact_drift, log=str(log.relative_to(root)),
                                  log_sha256=reference.digest(log)))
        report['elapsed_seconds'] = round(time.monotonic()-total_started, 3)
        receipt.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        print(command[1], 'passed' if report['gates'][-1]['passed'] else 'failed', flush=True)
        if drift or artifact_drift or code == 130 or code != 0 and args.failfast:
            break
    report['all_ok'] = len(report['gates']) == len(commands) and all(g['passed'] for g in report['gates'])
    receipt.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(receipt, flush=True)
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
