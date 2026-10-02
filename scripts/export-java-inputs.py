#!/usr/bin/env python3
"""Compatibility entry point for the explicit Rust Maven input exporter."""
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def binary():
    choices = [os.environ.get('CODEANA_CLI'), ROOT / 'codeana-cli',
               ROOT / 'bin/codeana-cli', ROOT / 'target/debug/codeana-cli']
    for candidate in choices:
        if candidate and pathlib.Path(candidate).is_file():
            return str(candidate)
    raise ValueError('Rust CLI missing; run cargo build --workspace')


def export(project, output, maven='mvn', settings=None, profiles=(), modules=(),
           scopes=('MAIN', 'TEST'), offline=False, source_only=False, timeout=300,
           extension=None, java_home=None):
    argv = [binary(), '--project', str(project), 'java-inputs', 'export',
            '--output', str(output), '--maven', str(maven), '--timeout', str(timeout)]
    for option, value in (('settings', settings), ('extension', extension), ('java-home', java_home)):
        if value is not None:
            argv.extend(['--' + option, str(value)])
    for option, values in (('profile', profiles), ('module', modules), ('scope', scopes)):
        for value in values:
            argv.extend(['--' + option, str(value)])
    if offline:
        argv.append('--offline')
    if source_only:
        argv.append('--source-only')
    process = subprocess.run(argv, capture_output=True, text=True)
    if process.returncode:
        message = process.stderr.strip()
        if 'MAVEN_INPUTS_TIMEOUT' in message:
            raise TimeoutError(message)
        raise ValueError(message)
    return json.loads(pathlib.Path(output).read_text())


def main():
    argv = [binary(), 'java-inputs', 'export', *sys.argv[1:]]
    return subprocess.call(argv)


if __name__ == '__main__':
    sys.exit(main())
