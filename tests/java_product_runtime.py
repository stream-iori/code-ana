#!/usr/bin/env python3
"""Freeze the freshly built runtime for daemon gates, independent of old installs."""
import argparse
import os
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


def install_jvm(root, source, archive):
    """Replace a verified JVM bundle, preserving the old SDK and executable paths."""
    manifest = json.loads((source/'manifest.json').read_text())
    names = [*manifest['files'], 'manifest.json']
    for name, digest in manifest['files'].items():
        path = source/name
        if not path.resolve().is_relative_to(source.resolve()) or sha(path) != digest:
            raise ValueError('JAVA_RUNTIME_BUILD_CHECKSUM: '+name)
    target = root/'target/lib/java/jvm'
    if all((target/name).is_file() and sha(target/name) == sha(source/name) for name in names):
        return
    binaries = (root/'target/debug').resolve()
    active = subprocess.check_output(['ps', '-axo', 'pid=,comm='], text=True)
    if any(len(fields := line.strip().split(None, 1)) == 2
           and fields[1].startswith(str(binaries)+'/codeana-') for line in active.splitlines()):
        raise ValueError('STOP_RUNTIME_BEFORE_JVM_INSTALL')
    archive.mkdir(parents=True, exist_ok=False)
    staged = archive/'new-jvm'
    staged.mkdir()
    for name in names:
        path = staged/name
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source/name, path)
    target.parent.mkdir(parents=True, exist_ok=True)
    old = archive/'previous-jvm'
    if target.exists():
        target.rename(old)
    try:
        staged.rename(target)
    except BaseException:
        if old.exists():
            old.rename(target)
        raise


def prepare(root, destination, backend=None, stable_paths=False):
    backend=backend or os.environ.get('CODEANA_JAVA_BACKEND','native')
    if backend=='jvm':
        source=root/'lang-java-adapotr/target/jvm'
        manifest=json.loads((source/'manifest.json').read_text())
        if manifest.get('backend')!='jvm': raise ValueError('JVM_RUNTIME_MANIFEST_REQUIRED')
        for relative,expected in manifest['files'].items():
            path=source/relative
            if not path.resolve().is_relative_to(source.resolve()) or sha(path)!=expected: raise ValueError('JAVA_RUNTIME_BUILD_CHECKSUM: '+relative)
        probe=subprocess.run([str(root/'target/debug/codeana-java-worker'),str(source/'runner.jar'),'--backend','jvm','--runtime-info'],capture_output=True,text=True,timeout=15)
        if probe.returncode:raise ValueError('JVM_RUNTIME_WORKER_NOT_CURRENT: '+probe.stderr)
        versions=json.loads(probe.stdout)
        if versions['backend']!='jvm' or versions['worker_protocol']!=manifest['worker_protocol'] or versions['adapter']!=manifest['adapter'] or versions['analysis_request']!=9 or versions['fact_ir']!=2:raise ValueError('JVM_RUNTIME_PROTOCOL_MISMATCH')
        installed_files = {}
        if stable_paths:
            install_jvm(root, source, destination.parent/'jvm-install')
            installed = root/'target/lib/java/jvm'
            for relative in [*manifest['files'], 'manifest.json']:
                path = installed/relative
                if not path.is_file() or sha(path) != sha(source/relative):
                    raise ValueError('JAVA_RUNTIME_INSTALLED_BUILD_MISMATCH: '+relative)
                installed_files[str(path.resolve())] = sha(path)
        destination.mkdir(parents=True,exist_ok=False)
        binaries=destination/'target/debug'
        if stable_paths:
            binaries.parent.mkdir(parents=True)
            binaries.symlink_to((root/'target/debug').resolve(),target_is_directory=True)
        else:
            binaries.mkdir(parents=True)
            for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):shutil.copy2(root/'target/debug'/name,binaries/name)
        bundle=destination/'target/lib/java/jvm';bundle.mkdir(parents=True)
        for relative in [*manifest['files'],'manifest.json']:shutil.copy2(source/relative,bundle/relative)
        result=dict(backend='jvm',binary_directory=str(binaries.resolve()),versions=versions,
            files=inventory(destination),relocated_binaries=not stable_paths,installed_files=installed_files,
            application_files={str((binaries/name).resolve()):sha(binaries/name)
                for name in ('codeana-cli','codeana-daemon','codeana-java-worker')})
        (destination/'runtime.json').write_text(json.dumps(result,indent=2)+'\n')
        return result
    if backend!='native':raise ValueError('JAVA_RUNTIME_BACKEND')
    source = root / 'lang-java-adapotr/target'
    manifest = json.loads((source / 'native/manifest.json').read_text())
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    library = 'native/libcodeana_java.' + suffix
    required = {library, 'compiler-bridge.jar', 'maven-input-worker.jar'}
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
    expected_versions = dict(worker_protocol=manifest['worker_protocol'], fact_ir=2,
                             adapter=manifest['adapter'], analysis_request=9, abi=4, backend='native')
    if versions != expected_versions:
        raise ValueError('JAVA_RUNTIME_PROTOCOL_MISMATCH: ' + json.dumps(versions))
    installed = root / 'target/lib/java'
    installed_files = {}
    if stable_paths:
        for relative in sorted(required | {'native/manifest.json'}):
            path = installed / relative
            if not path.is_file() or sha(path) != sha(source / relative):
                raise ValueError('JAVA_RUNTIME_INSTALLED_BUILD_MISMATCH: ' + relative)
            installed_files[str(path.resolve())] = sha(path)
    # A new task-owned directory is required; never overwrite an active runtime.
    destination.mkdir(parents=True, exist_ok=False)
    binaries = destination / 'target/debug'
    bundle = destination / 'target/lib/java'
    if stable_paths:
        binaries.parent.mkdir(parents=True)
        binaries.symlink_to((root / 'target/debug').resolve(), target_is_directory=True)
    else:
        binaries.mkdir(parents=True)
        for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker'):
            shutil.copy2(root / 'target/debug' / name, binaries / name)
    (bundle / 'native').mkdir(parents=True)
    for relative in sorted(required | {'native/manifest.json', 'maven-input-worker.jar'}):
        shutil.copy2(source / relative, bundle / relative)
    result = dict(binary_directory=str(binaries.resolve()), versions=versions,
                  files=inventory(destination), relocated_binaries=not stable_paths,
                  installed_files=installed_files,
                  application_files={str((binaries / name).resolve()): sha(binaries / name)
                      for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker')})
    (destination.parent / 'product-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def input_files():
    paths = [ROOT / name for name in ('Cargo.toml','Cargo.lock','lang-java-adapotr/pom.xml','lang-java-adapotr/.sdkmanrc','justfile')]
    extensions = {'.rs','.java','.py','.sh','.proto','.toml','.xml','.json','.sql'}
    for directory in ('crates','lang-java-adapotr','scripts','tests','proto'):
        paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in extensions
                  and not {'target','__pycache__'}.intersection(p.relative_to(ROOT).parts)]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--destination', type=pathlib.Path, required=True)
    parser.add_argument('--backend',choices=('jvm','native'))
    args = parser.parse_args()
    print(json.dumps(prepare(ROOT, args.destination,args.backend)))
