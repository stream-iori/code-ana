#!/usr/bin/env python3
"""Stage a complete JS/TS package, preserving the shared Rust/Python distribution."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
from typescript_sdk import install,sha
ROOT=Path(__file__).resolve().parents[1]


def package(output,sdk):
    output=Path(output)
    if output.exists():raise ValueError('PACKAGE_ALREADY_EXISTS')
    if not output.is_absolute():raise ValueError('ABSOLUTE_PACKAGE_PATH_REQUIRED')
    output.parent.mkdir(parents=True,exist_ok=True)
    spec=importlib.util.spec_from_file_location('python_package',ROOT/'scripts/package-python.py')
    shared=importlib.util.module_from_spec(spec);spec.loader.exec_module(shared)
    with tempfile.TemporaryDirectory(prefix='.typescript-package-',dir=output.parent) as temporary:
        stage=Path(temporary)/'package'
        shared.package(stage)
        shutil.copytree(ROOT/'tests/fixtures/java-native/base',stage/'fixtures/java-native/base')
        shutil.copy2(ROOT/'target/release/codeana-typescript-worker',stage/'bin/codeana-typescript-worker')
        installed=install(sdk,stage,stage/'bin/codeana-typescript-worker')
        data=json.loads((stage/'manifest.json').read_text())
        data.update(package='codeana-typescript',typescript={'version':installed['version'],'runtime':installed['runtime']},acceptance='not_asserted')
        (stage/'manifest.json').write_text(json.dumps(data,indent=2)+'\n')
        (stage/'README.md').write_text('# codeana JS/TS package\n\n'
            'CLI, daemon and Java/Rust/Python/TypeScript hosts. The pinned Go compiler and standard library are bundled. Java SDK is installed separately.\n\n'
            'Verify SHA256SUMS before use. Enable javascript/typescript explicitly and run bin/codeana-cli index --prepare. No Node, package installation, target build or global types are required.\n\n'
            'See docs/typescript-package.md, docs/modules/typescript-worker.md and docs/current-state.md for installation, supported semantics and validation boundaries.\n')
        sums=[f'{sha(path)}  {path.relative_to(stage).as_posix()}\n' for path in sorted(stage.rglob('*')) if path.is_file() and path.name!='SHA256SUMS' and not path.is_symlink() and not any((stage/parent).is_symlink() for parent in path.relative_to(stage).parents)]
        # Record each file through the immutable version tree. The relative
        # current symlink is separately validated by the release gate.
        (stage/'SHA256SUMS').write_text(''.join(sums))
        stage.rename(output)
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('CODEANA_TYPESCRIPT_SDK') or ROOT/'target/lib/typescript/current'))
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    print(package(args.output,args.sdk))

if __name__=='__main__':main()
