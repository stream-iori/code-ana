#!/usr/bin/env python3
"""Freeze the freshly built runtime for daemon gates, independent of old installs."""
import argparse
import hashlib
import json
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root):
    return {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob('*')) if p.is_file()}


def prepare(root, destination):
    source = root / 'lang-adaptor/target'
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
    result = dict(binary_directory=str(binaries.resolve()), files=inventory(destination))
    (destination.parent / 'product-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--destination', type=pathlib.Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(ROOT, args.destination)))
