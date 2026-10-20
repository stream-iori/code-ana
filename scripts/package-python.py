#!/usr/bin/env python3
"""Stage a self-contained Rust/Python package from locked release builds."""
import argparse
import hashlib
import json
import pathlib
import platform
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def package(output):
    output = output.resolve()
    if output.exists():
        raise ValueError(f'PACKAGE_ALREADY_EXISTS: {output}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.python-package-', dir=output.parent) as temporary:
        stage = pathlib.Path(temporary)/'package'
        (stage/'bin').mkdir(parents=True)
        for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker', 'codeana-rust-worker', 'codeana-python-worker'):
            shutil.copy2(ROOT/'target/release'/name, stage/'bin'/name)
        for language in ('rust', 'python'):
            shutil.copy2(ROOT/'scripts'/f'export-{language}-inputs.py', stage/'bin'/f'codeana-export-{language}-inputs')
            shutil.copytree(ROOT/'fixtures'/language, stage/'fixtures'/language)
        shutil.copytree(ROOT/'docs', stage/'docs')
        (stage/'README.md').write_text(
            '# codeana Python package\n\n'
            'CLI, daemon, Rust/Python workers and static-analysis fixtures. Java SDK is installed separately.\n\n'
            'See [Python configuration and limits](docs/modules/python-worker.md) and '
            '[validation scope](docs/current-state.md).\n\n'
            'Verify `shasum -a 256 -c SHA256SUMS` before use. '
            'Run `bin/codeana-cli --help`; use `doctor` to inspect inputs, '
            '`index --prepare` to prepare configured entries and index, '
            'or `python3 bin/codeana-export-python-inputs --help` for independent exports.\n'
        )
        shutil.copy2(ROOT/'Cargo.lock', stage/'rust-Cargo.lock')
        shutil.copy2(ROOT/'lang-python-adaptor/Cargo.lock', stage/'python-Cargo.lock')
        shutil.copy2(ROOT/'lang-python-adaptor/upstream-source.json', stage/'upstream-source.json')
        shutil.copytree(ROOT/'lang-python-adaptor/licenses', stage/'licenses/backend')
        dependencies = {}
        for manifest in ('Cargo.toml', 'lang-python-adaptor/Cargo.toml'):
            metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--locked', '--format-version', '1', '--manifest-path', str(ROOT/manifest)]))
            for item in metadata['packages']:
                if item['source'] is None:
                    continue
                key = f"{item['name']}-{item['version']}"
                directory = pathlib.Path(item['manifest_path']).parent
                destination = stage/'licenses'/key
                destination.mkdir(parents=True, exist_ok=True)
                texts = []
                if item['source'].startswith('git+https://github.com/facebook/pyrefly'):
                    ancestor = next((p for p in (directory, *list(directory.parents)[:4]) if (p/'LICENSE').is_file()), None)
                    if ancestor is None:
                        raise ValueError('PYREFLY_LICENSE_MISSING')
                    shutil.copy2(ancestor/'LICENSE', destination/'LICENSE-pyrefly')
                    texts.append('LICENSE-pyrefly')
                for path in directory.iterdir():
                    if path.is_file() and path.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING', 'NOTICE', 'COPYRIGHT')):
                        shutil.copy2(path, destination/path.name)
                        texts.append(path.name)
                if item.get('license_file'):
                    path = directory/item['license_file']
                    shutil.copy2(path, destination/'declared-license')
                    texts.append('declared-license')
                dependencies[key] = dict(name=item['name'], version=item['version'], source=item['source'], license=item['license'], included_texts=sorted(set(texts)))
        (stage/'dependencies.json').write_text(json.dumps(dependencies, indent=2)+'\n')
        constants = (ROOT/'crates/model/src/lib.rs').read_text()
        version = lambda name: int(re.search(r'pub const '+name+r': u32 = (\d+)', constants)[1])
        manifest = dict(package='codeana-python', platform=platform.system(), architecture=platform.machine(),
                        worker_protocol=version('WORKER_PROTOCOL_VERSION'), daemon_protocol=version('DAEMON_PROTOCOL_VERSION'),
                        graph_model=version('VERSION'), physical_schema=11,
                        backend=json.loads((stage/'upstream-source.json').read_text()), java='sdk_not_bundled', java_host='bundled', acceptance='not_asserted')
        python_model = (ROOT/'crates/model/src/python.rs').read_text()
        manifest['python_adapter'] = re.search(r'pub const ADAPTER_VERSION: &str = "([^"]+)"', python_model)[1]
        manifest['python_input_schema'] = int(re.search(r'pub const INPUT_SCHEMA: u32 = (\d+)', python_model)[1])
        (stage/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        sums = [f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(stage).as_posix()}\n'
                for path in sorted(stage.rglob('*')) if path.is_file()]
        (stage/'SHA256SUMS').write_text(''.join(sums))
        stage.rename(output)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    print(package(parser.parse_args().output))
