#!/usr/bin/env python3
"""Build an isolated diagnostic Native variant from already accepted bytecode."""
import argparse
import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json


def main():
    parser = argparse.ArgumentParser()
    for name in ('native-gate', 'proof-source-root', 'bundle-root', 'java-home', 'evidence'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--cpu-target', choices=('baseline', 'native'), default='baseline')
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('sealed_single', ROOT / 'scripts/java-performance-single.py')
    sealed = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sealed)
    gate = sealed.accepted(args.native_gate, 'native_accepted', args.proof_source_root)
    original = args.bundle_root.resolve() / 'lang-java-adapotr'
    jar = original / 'native-bridge/target/native-bridge-0.1.0.jar'
    manifest_path = original / 'target/native/manifest.json'
    manifest = json.loads(manifest_path.read_text())
    sources = [jar, manifest_path] + [original / 'target' / name for name in manifest['files']]
    bound_sources = []
    for path in sources:
        digest = sha256(path)
        # Prepared installations relocate identical bundle files. Bind the exact
        # accepted counterpart as well as the byte-identical builder input.
        matches = [pathlib.Path(name) for name, checksum in gate['inputs'].items()
                   if checksum == digest and pathlib.Path(name).name == path.name]
        if not matches:
            raise ValueError('ACCEPTED_NATIVE_BYTECODE_OR_BUNDLE_REQUIRED: ' + str(path))
        bound_sources.append(matches[0])
    image = args.java_home.resolve() / 'bin/native-image'
    if not image.is_file():
        raise ValueError('NATIVE_IMAGE_REQUIRED')
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    target = args.evidence / 'bundle/lang-java-adapotr/target'
    native = target / 'native'
    native.mkdir(parents=True)
    scratch = args.evidence / 'build-tmp'
    scratch.mkdir(mode=0o700)
    for name in ('compiler-bridge.jar', 'maven-input-worker.jar'):
        shutil.copy2(original / 'target' / name, target / name)
    command = [str(image), '--shared', '-R:MaxHeapSize=256m',
               '-H:-DeleteLocalSymbols', '-H:IncludeResources=META-INF/anatomist/javac-worker.jar',
               '--native-compiler-options=-save-temps=obj',
               '-J-Djava.io.tmpdir=' + str(scratch), '-H:TempDirectory=' + str(scratch),
               '-cp', str(jar), '-o', str(native / 'libcodeana_java')]
    if args.cpu_target == 'native':
        command.insert(1, '-march=native')
    inputs = {str(path): sha256(path) for path in sources + bound_sources +
              [args.native_gate.resolve(), pathlib.Path(__file__).resolve(),
               ROOT / 'scripts/java-performance-single.py', image]}
    report = dict(status='running', command=command, cpu_target=args.cpu_target,
                  inputs=inputs, native_accepted=False, performance_accepted=False,
                  diagnostic_only=True, compatibility='same host CPU' if args.cpu_target == 'native' else 'baseline CPU target',
                  native_image_version=subprocess.check_output([str(image), '--version'], text=True))
    receipt = args.evidence / 'receipt.json'
    atomic_json(receipt, report)
    start = time.monotonic()
    try:
        with (args.evidence / 'build.log').open('wb') as log:
            execution = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, cwd=args.evidence)
        report['exit_code'] = execution.returncode
        if execution.returncode:
            raise ValueError('NATIVE_VARIANT_BUILD_FAILED: ' + str(execution.returncode))
        library = native / ('libcodeana_java.dylib' if sys.platform == 'darwin' else 'libcodeana_java.so')
        if not library.is_file():
            raise ValueError('NATIVE_VARIANT_LIBRARY_MISSING')
        manifest['files'] = {name: sha256(target / name) for name in manifest['files']}
        atomic_json(native / 'manifest.json', manifest)
        report['changed_inputs'] = [path for path, digest in inputs.items() if sha256(path) != digest]
        if report['changed_inputs']:
            raise ValueError('NATIVE_VARIANT_INPUT_DRIFT')
        report.update(status='passed', library=str(library), library_sha256=sha256(library),
                      symbols_retained=True, bundle_root=str(args.evidence / 'bundle'))
    except Exception as error:
        report.update(status='failed', failure=str(error))
    report['seconds'] = time.monotonic() - start
    atomic_json(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
