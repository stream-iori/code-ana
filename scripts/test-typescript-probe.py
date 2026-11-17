#!/usr/bin/env python3
"""Build and verify the diagnostic Rust/Go ABI from locked sources on macOS ARM64."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / 'lang-typescript-adaptor/probe'
LOCK = ROOT / 'lang-typescript-adaptor/upstream.lock.json'
spec = importlib.util.spec_from_file_location('baseline', ROOT / 'scripts/multilang-baseline.py')
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--go-home', type=Path)
    parser.add_argument('--java-baseline', type=Path)
    parser.add_argument('--direct-network', action='store_true')
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        parser.error('Python >= 3.12 is required; select it explicitly in PATH')
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('this probe gate validates macOS ARM64 only')
    evidence = (args.evidence or ROOT / 'target/typescript-c-abi' /
                time.strftime('probe-%Y%m%d-%H%M%S')).resolve()
    if not evidence.is_relative_to(ROOT / 'target'):
        parser.error('evidence must be a new directory under target')
    evidence.mkdir(parents=True, exist_ok=False)
    lock = json.loads(LOCK.read_text())
    env = dict(os.environ)
    env.update(RUSTUP_TOOLCHAIN='1.96.0', GOTOOLCHAIN='local', CGO_ENABLED='1',
               GOWORK='off', GOENV='off', GOFLAGS='')
    env['PATH'] = str(Path(sys.executable).parent) + os.pathsep + env['PATH']
    if args.direct_network:
        env = {key: value for key, value in env.items()
               if key.lower() not in ('http_proxy', 'https_proxy', 'all_proxy', 'no_proxy')}
    receipt = {'stage': 'T0', 'status': 'failed', 'commands': [],
               'lock_sha256': baseline.sha(LOCK), 'direct_network': args.direct_network}

    def output(*argv: str, cwd: Path = ROOT) -> str:
        return subprocess.check_output(argv, cwd=cwd, env=env, text=True,
                                       stderr=subprocess.STDOUT, timeout=30).strip()

    def run(name: str, argv: list[str], cwd: Path = ROOT, timeout: float = 300,
            process_env: dict | None = None, after_ready: int | None = None) -> None:
        log = evidence / (name + '.log')
        entry = {'name': name, 'argv': argv, 'cwd': str(cwd), 'log': log.name}
        receipt['commands'].append(entry)
        started = time.monotonic()
        with log.open('wb') as stream:
            process = subprocess.Popen(argv, cwd=cwd, env=process_env or env,
                                       stdout=stream, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            try:
                ready = False
                while os.waitid(os.P_PID, process.pid,
                                os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
                    if time.monotonic() - started > timeout:
                        raise subprocess.TimeoutExpired(argv, timeout)
                    if after_ready and not ready and b'probe semantic and ABI checks passed' in log.read_bytes():
                        ready = True
                        baseline.signal_group(process.pid, after_ready)
                    time.sleep(0.05)
                entry['ready'] = ready
            finally:
                baseline.signal_group(process.pid, signal.SIGTERM)
                until = time.monotonic() + 5
                while (os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                       is None and time.monotonic() < until):
                    time.sleep(0.05)
                baseline.signal_group(process.pid, signal.SIGKILL)
                entry['exit_code'] = process.wait()
                entry['seconds'] = time.monotonic() - started
                entry['sha256'] = baseline.sha(log)
        expected = -after_ready if after_ready else 0
        if entry['exit_code'] != expected or (after_ready and not entry['ready']):
            raise RuntimeError(f'{name} failed: {entry}; see {log}')

    try:
        receipt['head'] = output('git', 'rev-parse', 'HEAD')
        receipt['working_tree'] = output('git', 'status', '--porcelain')
        for name, expected in lock['overlay']['files'].items():
            if baseline.sha(ROOT / 'lang-typescript-adaptor' / name) != expected:
                raise RuntimeError(f'overlay changed without lock update: {name}')
        if args.java_baseline:
            path = args.java_baseline.resolve()
            prior = json.loads(path.read_text())
            if prior['status'] != 'passed' or prior['head'] != receipt['head']:
                raise RuntimeError('baseline status/HEAD mismatch')
            if prior['stage'] != 'R0' or len(prior['commands']) != 1 or prior['commands'][0]['argv'] != ['just', 'test-java']:
                raise RuntimeError('not an R0 Java baseline receipt')
            for name, expected in prior['inputs'].items():
                if baseline.sha(ROOT / name) != expected:
                    raise RuntimeError(f'baseline input changed: {name}')
            files = output('git', 'ls-files', 'lang-java-adaptor').splitlines()
            digest = hashlib.sha256(json.dumps({name: baseline.sha(ROOT / name)
                for name in files if (ROOT / name).is_file()}, sort_keys=True).encode()).hexdigest()
            if digest != prior['java_source_sha256']:
                raise RuntimeError('Java baseline source mismatch')
            for item in prior['commands']:
                if item['exit_code'] != 0 or baseline.sha(path.parent / item['log']) != item['sha256']:
                    raise RuntimeError('baseline command/log mismatch')
            receipt['java_baseline'] = {'reused': True, 'path': str(path), 'sha256': baseline.sha(path)}
        else:
            run('java-baseline', [sys.executable, str(ROOT / 'scripts/multilang-baseline.py'),
                                 '--evidence', str(evidence / 'java-baseline')], timeout=1800)
            receipt['java_baseline'] = {'reused': False, 'path': 'java-baseline/receipt.json'}
        source = args.source.resolve() if args.source else evidence / 'upstream'
        if not source.is_relative_to(ROOT / 'target'):
            raise RuntimeError('source checkout must be under target')
        if not args.source:
            run('git-init', ['git', 'init', str(source)])
            run('git-fetch', ['git', 'fetch', '--depth=1', lock['repository'], lock['commit']], source)
            run('git-checkout', ['git', 'checkout', '--detach', 'FETCH_HEAD'], source)
            run('git-submodules', ['git', 'submodule', 'update', '--init', '--depth=1'], source)
        if output('git', 'rev-parse', 'HEAD', cwd=source) != lock['commit']:
            raise RuntimeError('upstream commit mismatch')
        submodule = source / '_submodules/TypeScript'
        if output('git', 'rev-parse', 'HEAD', cwd=submodule) != lock['typescript']['commit']:
            raise RuntimeError('TypeScript submodule mismatch')
        if lock['typescript']['commit'] not in output('git', 'ls-tree', 'HEAD', '_submodules/TypeScript', cwd=source):
            raise RuntimeError('submodule gitlink mismatch')
        for tree in (source, submodule):
            if output('git', 'status', '--porcelain', '--untracked-files=no', cwd=tree):
                raise RuntimeError(f'modified tracked upstream files: {tree}')
            untracked = output('git', 'ls-files', '--others', '--exclude-standard', cwd=tree).splitlines()
            allowed = {'codeana_probe/main.go'} if tree == source else set()
            if set(untracked) - allowed:
                raise RuntimeError(f'unrecognized upstream inputs: {untracked}')
        for name, expected in lock['source_sha256'].items():
            if baseline.sha(source / name) != expected:
                raise RuntimeError(f'upstream digest mismatch: {name}')
        if baseline.sha(submodule / 'LICENSE.txt') != lock['typescript']['license_sha256']:
            raise RuntimeError('TypeScript license mismatch')
        go_home = args.go_home.resolve() if args.go_home else evidence / 'go'
        if not args.go_home:
            distribution = lock['go']['distribution']
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if args.direct_network else urllib.request.build_opener()
            archive = evidence / distribution['filename']
            with opener.open('https://go.dev/dl/' + distribution['filename'], timeout=60) as response, archive.open('wb') as destination:
                shutil.copyfileobj(response, destination)
            if baseline.sha(archive) != distribution['sha256']:
                raise RuntimeError('Go distribution SHA mismatch')
            with tarfile.open(archive) as bundle:
                bundle.extractall(evidence, filter='data')
        go = str(go_home / 'bin/go')
        if output(go, 'version') != lock['go']['version']:
            raise RuntimeError('Go toolchain mismatch')
        for name, expected in lock['go']['tools_sha256'].items():
            if baseline.sha(go_home / name) != expected:
                raise RuntimeError(f'Go tool digest mismatch: {name}')
        if not output('rustc', '--version').startswith('rustc 1.96.0 '):
            raise RuntimeError('Rust toolchain mismatch')
        env['CC'] = output('xcrun', '--find', 'clang')
        env['SDKROOT'] = output('xcrun', '--show-sdk-path')
        if output(env['CC'], '--version') != lock['c_compiler']['version']:
            raise RuntimeError('C compiler mismatch')
        if (output('xcrun', '--show-sdk-version') != lock['macos_sdk']['version'] or
                baseline.sha(Path(env['SDKROOT']) / 'SDKSettings.json') != lock['macos_sdk']['settings_sha256']):
            raise RuntimeError('macOS SDK mismatch')
        run('generate-libs', [go, 'generate', './internal/bundled'], source)
        actual_files = {str(p.relative_to(source)) for p in (source / 'internal/bundled').rglob('*') if p.is_file()}
        if actual_files != set(lock['standard_library']['files']):
            raise RuntimeError('standard library file set mismatch')
        for name, expected in lock['standard_library']['files'].items():
            if baseline.sha(source / name) != expected:
                raise RuntimeError(f'generated library mismatch: {name}')
        overlay = source / 'codeana_probe'
        if overlay.exists():
            if set(overlay.iterdir()) != {overlay / 'main.go'} or (overlay / 'main.go').read_bytes() != (PROBE / 'bridge/main.go').read_bytes():
                raise RuntimeError('unrecognized existing probe overlay; use a new checkout')
        else:
            shutil.copytree(PROBE / 'bridge', overlay)
        receipt['overlay_sha256'] = baseline.sha(overlay / 'main.go')
        run('gofmt', [str(go_home / 'bin/gofmt'), '-d', str(overlay / 'main.go')])
        if (evidence / 'gofmt.log').stat().st_size:
            raise RuntimeError('Go format mismatch')
        library = evidence / 'probe.dylib'
        run('go-build', [go, 'build', '-mod=readonly', '-trimpath', '-buildmode=c-shared',
                         '-o', str(library), './codeana_probe'], source)
        header_check = evidence / 'header-check.c'
        header_check.write_text(f'#include "{PROBE / "include/probe.h"}"\n#include "{evidence / "probe.h"}"\n')
        run('header', [env['CC'], '-isysroot', env['SDKROOT'], '-Werror', '-fsyntax-only', str(header_check)])
        run('symbols', ['nm', '-gU', str(library)])
        run('dependencies', ['otool', '-L', str(library)])
        manifest = str(PROBE / 'host/Cargo.toml')
        env['CARGO_TARGET_DIR'] = str(evidence / 'rust-target')
        run('rust-fmt', ['cargo', 'fmt', '--manifest-path', manifest, '--check'])
        run('rust-build', ['cargo', 'build', '--manifest-path', manifest, '--locked'])
        run('rust-clippy', ['cargo', 'clippy', '--manifest-path', manifest, '--locked', '--', '-D', 'warnings'])
        host = evidence / 'rust-target/debug/codeana-typescript-probe'
        runtime_env = dict(env, PATH='')
        for mode in ('main', 'thread'):
            run(mode, [str(host), str(library), str(PROBE / 'fixtures'), str(evidence / ('capture-' + mode)), mode], process_env=runtime_env)
        for number in (signal.SIGINT, signal.SIGTERM):
            run('signal-' + str(number), [str(host), str(library), str(PROBE / 'fixtures'),
                str(evidence / ('capture-signal-' + str(number))), 'wait-signal'],
                process_env=runtime_env, after_ready=number)
        receipt['artifacts'] = {str(p.relative_to(evidence)): baseline.sha(p) for p in (library, host, evidence / 'probe.h')}
        receipt['fixtures'] = {p.name: baseline.sha(p) for p in (PROBE / 'fixtures').iterdir() if p.is_file()}
        receipt['status'] = 'passed'
    except (Exception, KeyboardInterrupt) as error:
        receipt['error'] = f'{type(error).__name__}: {error}'
    finally:
        (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(evidence / 'receipt.json')
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
