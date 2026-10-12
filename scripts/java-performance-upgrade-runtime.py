#!/usr/bin/env python3
"""Explicitly replace a verified Java bundle, preserving all application paths."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256


def atomic_copy(source, destination):
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.runtime-upgrade-', delete=False) as stream:
        temporary = Path(stream.name)
    try:
        shutil.copy2(source, temporary)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def upgrade(binaries, bundle, backend, archive, previous_manifest_sha256):
    binaries = binaries.resolve(strict=True)
    stable = binaries.parent / 'runtime'
    source = bundle.resolve() / 'lang-java-adapotr/target'
    target = binaries.parent / 'lib/java'
    if backend == 'jvm':
        source /= 'jvm'
        target /= 'jvm'
    manifest_relative = Path('native/manifest.json' if backend == 'native' else 'manifest.json')
    manifest_source = source / manifest_relative
    manifest_target = target / manifest_relative
    metadata = stable / ('runtime-' + backend + '.json')
    old = json.loads(metadata.read_text())
    manifest = json.loads(manifest_source.read_text())
    current = json.loads(manifest_target.read_text())
    if sha256(manifest_target) != previous_manifest_sha256:
        raise ValueError('EXPLICIT_PREVIOUS_RUNTIME_IDENTITY_REQUIRED')
    if set(current['files']) != set(manifest['files']):
        raise ValueError('RUNTIME_FILE_SET_CHANGED')
    for name, digest in manifest['files'].items():
        path = source / name
        if not path.resolve().is_relative_to(source.resolve()) or sha256(path) != digest:
            raise ValueError('NEW_RUNTIME_CHECKSUM: ' + name)
    expected_old_files = {str(target / name): digest for name, digest in current['files'].items()}
    expected_old_files[str(manifest_target)] = previous_manifest_sha256
    if old['installed_files'] != expected_old_files or old['binary_directory'] != str(binaries):
        raise ValueError('OLD_RUNTIME_METADATA_DRIFT')
    for filename, digest in old['installed_files'].items():
        if sha256(filename) != digest:
            raise ValueError('OLD_RUNTIME_CHECKSUM: ' + filename)
    processes = subprocess.run(['ps', '-axo', 'pid=,comm='], text=True, capture_output=True, check=True)
    for line in processes.stdout.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) == 2 and fields[1].startswith(str(binaries) + '/codeana-'):
            raise ValueError('STOP_RUNTIME_BEFORE_UPGRADE: ' + fields[0])
    runtime_file = ('native/libcodeana_java.' + ('dylib' if sys.platform == 'darwin' else 'so')) if backend == 'native' else 'runner.jar'
    probe = subprocess.run([str(binaries / 'codeana-java-worker'), str(source / runtime_file), '--backend', backend, '--runtime-info'], text=True, capture_output=True, timeout=15)
    if probe.returncode:
        raise ValueError('NEW_RUNTIME_PROBE_FAILED: ' + probe.stderr)
    versions = json.loads(probe.stdout)
    expected = dict(backend=backend, worker_protocol=manifest['worker_protocol'], fact_ir=1,
                    adapter=manifest['adapter'], analysis_request=8, abi=2 if backend == 'native' else None)
    if versions != expected:
        raise ValueError('NEW_RUNTIME_PROTOCOL_MISMATCH')
    # Archive old bytes before touching any live path. No application is copied.
    archive.mkdir(parents=True, exist_ok=False)
    saved = {}
    for path in [*(Path(p) for p in old['installed_files']), metadata]:
        relative = path.relative_to(binaries.parent)
        copy = archive / relative
        copy.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, copy)
        if sha256(path) != sha256(copy):
            raise ValueError('RUNTIME_ARCHIVE_COPY_FAILED')
        saved[str(path)] = dict(archive=str(copy), sha256=sha256(copy))
    report = dict(status='archived', backend=backend, binary_directory=str(binaries), relocated_binaries=False,
                  old_files=saved, old_manifest_sha256=previous_manifest_sha256,
                  new_manifest_sha256=sha256(manifest_source), application_sha256={})
    for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker'):
        report['application_sha256'][str(binaries / name)] = sha256(binaries / name)
    receipt = archive / 'receipt.json'
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    replacements = [(source / name, target / name) for name in manifest['files']]
    replacements.append((manifest_source, manifest_target))
    touched = []
    try:
        for origin, destination in replacements:
            touched.append(destination)
            atomic_copy(origin, destination)
        result = dict(binary_directory=str(binaries), versions=versions, stable_runtime=str(stable), relocated_binaries=False,
                      installed_files={str(target / name): digest for name, digest in manifest['files'].items()} | {str(manifest_target): sha256(manifest_source)})
        generated = archive / 'new-runtime-metadata.json'
        generated.write_text(json.dumps(result, indent=2) + '\n')
        touched.append(metadata)
        atomic_copy(generated, metadata)
        for filename, digest in result['installed_files'].items():
            if sha256(filename) != digest:
                raise ValueError('UPGRADED_RUNTIME_CHECKSUM')
        for filename, digest in report['application_sha256'].items():
            if sha256(filename) != digest:
                raise ValueError('APPLICATION_CHANGED_DURING_UPGRADE')
        report.update(status='passed', new_files=result['installed_files'])
    except BaseException as failure:
        for destination in reversed(touched):
            atomic_copy(Path(saved[str(destination)]['archive']), destination)
        report.update(status='failed', failure=str(failure), rolled_back=True)
        receipt.write_text(json.dumps(report, indent=2) + '\n')
        raise
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser()
    for name in ('binaries', 'bundle', 'archive'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--backend', choices=('native', 'jvm'), required=True)
    parser.add_argument('--previous-manifest-sha256', required=True)
    args = parser.parse_args()
    print(json.dumps(upgrade(args.binaries, args.bundle, args.backend, args.archive, args.previous_manifest_sha256)))


if __name__ == '__main__':
    main()
