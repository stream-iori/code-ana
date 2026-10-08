#!/usr/bin/env python3
"""Freeze the freshly built runtime for daemon gates, independent of old installs."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root):
    return {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob('*')) if p.is_file()}


def prepare(root, destination):
    source = root / 'lang-java-adapotr/target'
    manifest = json.loads((source / 'native/manifest.json').read_text())
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    library = 'native/libcodeana_java.' + suffix
    required = {library, 'compiler-bridge.jar'}
    if not required.issubset(manifest['files']):
        raise ValueError('JAVA_RUNTIME_MANIFEST_INCOMPLETE')
    for relative, expected in manifest['files'].items():
        path = source / relative
        if not path.resolve().is_relative_to(source.resolve()) or sha(path) != expected:
            raise ValueError('JAVA_RUNTIME_BUILD_CHECKSUM: ' + relative)
    probe = subprocess.run([str(root / 'target/debug/codeana-java-worker'),
                            str(source / library), '--runtime-info'],
                           capture_output=True, text=True, timeout=15)
    if probe.returncode:
        raise ValueError('JAVA_RUNTIME_WORKER_NOT_CURRENT: rebuild Rust binaries; ' + probe.stderr.strip())
    versions = json.loads(probe.stdout)
    expected_versions = dict(worker_protocol=13, fact_ir=1,
                             adapter=manifest['adapter'], analysis_request=7, abi=2)
    if versions != expected_versions:
        raise ValueError('JAVA_RUNTIME_PROTOCOL_MISMATCH: ' + json.dumps(versions))
    # A new task-owned directory is required; never overwrite an active runtime.
    destination.mkdir(parents=True, exist_ok=False)
    binaries = destination / 'target/debug'
    bundle = destination / 'target/lib/java'
    binaries.mkdir(parents=True)
    (bundle / 'native').mkdir(parents=True)
    for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker'):
        shutil.copy2(root / 'target/debug' / name, binaries / name)
    for relative in sorted(required | {'native/manifest.json', 'maven-input-worker.jar'}):
        shutil.copy2(source / relative, bundle / relative)
    result = dict(binary_directory=str(binaries.resolve()), versions=versions,
                  files=inventory(destination))
    (destination.parent / 'product-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--destination', type=pathlib.Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(ROOT, args.destination)))
