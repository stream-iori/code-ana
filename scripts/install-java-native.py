#!/usr/bin/env python3
"""Install the verified Native bundle beside the existing Cargo executables."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def replace(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.native-install-', delete=False) as stream:
        staged = Path(stream.name)
    try:
        shutil.copy2(source, staged)
        os.replace(staged, destination)
    finally:
        staged.unlink(missing_ok=True)


def install(root, archive):
    source = root / 'lang-java-adaptor/target'
    target = root / 'target/lib/java'
    binaries = (root / 'target/debug').resolve()
    manifest = json.loads((source / 'native/manifest.json').read_text())
    if manifest.get('native_gc') not in ('G1', 'serial'):
        raise ValueError('NATIVE_GC_REQUIRED')
    library = 'native/libcodeana_java.' + ('dylib' if os.uname().sysname == 'Darwin' else 'so')
    required = {library, 'compiler-bridge.jar', 'maven-input-worker.jar'}
    if not required.issubset(manifest['files']):
        raise ValueError('NATIVE_INSTALL_FILES_REQUIRED')
    for name, digest in manifest['files'].items():
        path = source / name
        if not path.resolve().is_relative_to(source.resolve()) or sha(path) != digest:
            raise ValueError('NATIVE_INSTALL_SOURCE_CHECKSUM: ' + name)
    active = subprocess.check_output(['ps', '-axo', 'pid=,comm='], text=True)
    for line in active.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) == 2 and fields[1].startswith(str(binaries) + '/codeana-'):
            raise ValueError('STOP_RUNTIME_BEFORE_NATIVE_INSTALL: ' + fields[0])
    probe = subprocess.run([str(binaries / 'codeana-java-worker'), str(source / library), '--runtime-info'],
                           capture_output=True, text=True, check=True, timeout=15)
    versions = json.loads(probe.stdout)
    expected = dict(backend='native', worker_protocol=manifest['worker_protocol'], fact_ir=manifest['fact_ir'],
                    adapter=manifest['adapter'], analysis_request=manifest['analysis_request'], abi=manifest['abi'])
    if versions != expected:
        raise ValueError('NATIVE_INSTALL_WORKER_VERSION')
    names = [*manifest['files'], 'native/manifest.json']
    archive.mkdir(parents=True, exist_ok=False)
    metadata = root / 'target/runtime/runtime-native.json'
    old = {}
    for path in [*(target / name for name in names), metadata]:
        if path.is_file():
            copy = archive / path.relative_to(root / 'target')
            copy.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, copy)
            old[path] = copy
    touched = []
    try:
        for name in names:
            path = target / name
            replace(source / name, path)
            touched.append(path)
        installed = {str((target / name).resolve()): sha(target / name) for name in names}
        metadata.parent.mkdir(parents=True, exist_ok=True)
        value = dict(binary_directory=str(binaries), versions=versions,
                     stable_runtime=str(metadata.parent.resolve()), relocated_binaries=False, installed_files=installed)
        staged_metadata = archive / 'new-runtime.json'
        staged_metadata.write_text(json.dumps(value, indent=2) + '\n')
        replace(staged_metadata, metadata)
        touched.append(metadata)
    except BaseException:
        for path in reversed(touched):
            if path in old:
                replace(old[path], path)
            else:
                path.unlink(missing_ok=True)
        raise
    result = dict(status='passed', gc=manifest['native_gc'], application_paths_preserved=True,
                  archive=str(archive), installed_files=installed, versions=versions,
                  native_accepted=False)
    (archive / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    archive = args.archive or ROOT / 'target/native-install-backups' / (time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    print(json.dumps(install(ROOT, archive.resolve())))
