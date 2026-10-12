"""Small standard-library gate helpers; no product parser or normalizer oracle."""
import collections
import hashlib
import os
import pathlib
import re
import signal
import subprocess
import time
import xml.etree.ElementTree as ET


def sha256(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def identities(paths):
    return {str(path): sha256(path) if pathlib.Path(path).is_file() else None for path in paths}


def summarize(expected, cases, before, after):
    expected = list(expected)
    counts = collections.Counter(case['id'] for case in cases)
    expected_duplicate = sorted(key for key,count in collections.Counter(expected).items() if count != 1)
    missing = sorted(set(expected) - counts.keys())
    duplicate = sorted(key for key, count in counts.items() if count != 1)
    unexpected = sorted(counts.keys() - set(expected))
    drift = sorted(key for key in before.keys() | after.keys() if before.get(key) != after.get(key))
    passed = bool(expected) and not (expected_duplicate or missing or duplicate or unexpected or drift)
    passed = passed and all(case['status'] == 'passed' for case in cases)
    return dict(status='passed' if passed else 'failed', expected_count=len(expected),
                executed_count=len(cases), missing=missing, duplicate=duplicate,
                unexpected=unexpected, expected_duplicate=expected_duplicate, artifact_drift=drift, cases=cases)


def read_surefire(paths):
    cases = []
    for path in paths:
        for node in ET.parse(path).getroot().iter('testcase'):
            status = 'passed'
            if node.find('failure') is not None or node.find('error') is not None:
                status = 'failed'
            elif node.find('skipped') is not None:
                status = 'skipped'
            reported_id = node.attrib['classname'] + '#' + node.attrib['name']
            # JUnit adds injected argument types (e.g. @TempDir Path) to @Test names.
            # Invocation suffixes remain distinct; duplicates still fail the gate.
            name = re.sub(r'\([\w\s.,?<>\[\]]*\)$', '', node.attrib['name'])
            cases.append(dict(id=node.attrib['classname'] + '#' + name, reported_id=reported_id,
                              status=status, report=str(path)))
    return cases


def run_command(command, log, timeout, *, cwd=None, env=None):
    log = pathlib.Path(log)
    log.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    result = dict(command=list(map(str, command)), status='failed', exit_code=None)
    with log.open('wb') as output:
        try:
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as error:
            result.update(status='blocked', error=str(error))
        else:
            try:
                result['exit_code'] = process.wait(timeout=timeout)
                result['status'] = 'passed' if result['exit_code'] == 0 else 'failed'
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                result.update(exit_code=process.wait(), error='TIMEOUT')
    result.update(seconds=time.monotonic() - started, log=str(log), log_sha256=sha256(log))
    return result
