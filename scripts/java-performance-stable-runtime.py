#!/usr/bin/env python3
"""Install verified bundles beside existing Cargo binaries without relocating apps."""
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tests'))
from acceptance_support import sha256


def install_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if not destination.is_file() or sha256(destination) != sha256(source):
            raise ValueError('STABLE_RUNTIME_ARTIFACT_DRIFT: '+str(destination))
    else:
        shutil.copy2(source, destination)


def link_once(path, target):
    if path.exists() or path.is_symlink():
        if not path.is_symlink() or path.resolve() != target.resolve():
            raise ValueError('STABLE_RUNTIME_PATH_DRIFT: '+str(path))
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.symlink_to(target, target_is_directory=True)


def prepare(root, destination, backend):
    binaries = (root/'target/debug').resolve(strict=True)
    stable = binaries.parent/'runtime'
    if destination.resolve() == stable:
        raise ValueError('STABLE_RUNTIME_EVIDENCE_ALIAS_REQUIRED')
    source = root/'lang-java-adapotr/target'
    if backend == 'native':
        manifest_path = source/'native/manifest.json'
        target = binaries.parent/'lib/java'
        runtime_file = 'native/libcodeana_java.'+('dylib' if sys.platform == 'darwin' else 'so')
    elif backend == 'jvm':
        source = source/'jvm'
        manifest_path = source/'manifest.json'
        target = binaries.parent/'lib/java/jvm'
        runtime_file = 'runner.jar'
    else:
        raise ValueError('JAVA_RUNTIME_BACKEND')
    manifest = json.loads(manifest_path.read_text())
    required = {runtime_file, 'compiler-bridge.jar', 'maven-input-worker.jar'}
    if not required.issubset(manifest['files']):
        raise ValueError('JAVA_RUNTIME_MANIFEST_INCOMPLETE')
    # Validate the complete source bundle before writing any installation files.
    for relative, digest in manifest['files'].items():
        path = source/relative
        if not path.resolve().is_relative_to(source.resolve()) or sha256(path) != digest:
            raise ValueError('JAVA_RUNTIME_BUILD_CHECKSUM: '+relative)
    for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
        if not (binaries/name).is_file():
            raise ValueError('STABLE_RUNTIME_BINARY_MISSING: '+name)
    for relative in manifest['files']:
        install_file(source/relative, target/relative)
    install_file(manifest_path, target/('native/manifest.json' if backend == 'native' else 'manifest.json'))
    stable.mkdir(parents=True, exist_ok=True)
    link_once(stable/'target/debug', binaries)
    link_once(stable/'target/lib', binaries.parent/'lib')
    probe = subprocess.run([str(binaries/'codeana-java-worker'),str(target/runtime_file),
                            '--backend',backend,'--runtime-info'],capture_output=True,text=True,timeout=15)
    if probe.returncode:
        raise ValueError('STABLE_RUNTIME_PROBE_FAILED: '+probe.stderr)
    versions = json.loads(probe.stdout)
    expected = dict(backend=backend, worker_protocol=manifest['worker_protocol'],
                    fact_ir=manifest['fact_ir'], adapter=manifest['adapter'], analysis_request=manifest['analysis_request'])
    expected['abi']=manifest['abi'] if backend=='native' else None
    if versions != expected:
        raise ValueError('JAVA_RUNTIME_PROTOCOL_MISMATCH')
    result = dict(binary_directory=str(binaries), versions=versions,
                  stable_runtime=str(stable), relocated_binaries=False,
                  installed_files={str(target/name):sha256(target/name) for name in manifest['files']} | {str(target/('native/manifest.json' if backend=='native' else 'manifest.json')):sha256(manifest_path)})
    metadata = stable/('runtime-'+backend+'.json')
    encoded=json.dumps(result,indent=2)+'\n'
    if metadata.exists() and metadata.read_text()!=encoded:
        raise ValueError('STABLE_RUNTIME_METADATA_DRIFT')
    if not metadata.exists(): metadata.write_text(encoded)
    link_once(destination, stable)
    return result
