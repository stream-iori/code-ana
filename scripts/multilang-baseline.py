#!/usr/bin/env python3
"""Record the R0 Java baseline without accepting historical receipts."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def capture(command: list[str], env: dict[str, str]) -> str:
    return subprocess.check_output(command, cwd=ROOT, env=env, text=True,
                                   stderr=subprocess.STDOUT, timeout=30).strip()


def signal_group(pid: int, number: int) -> None:
    try:
        os.killpg(pid, number)
    except ProcessLookupError:
        pass
    except PermissionError:
        # Darwin reports EPERM for a group whose only member is a zombie.
        if os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--timeout', type=float, default=1800)
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    if not evidence.is_relative_to(ROOT / 'target') or args.timeout <= 0:
        parser.error('evidence must be under target; timeout must be positive')
    if sys.version_info < (3, 10):
        parser.error('Python >= 3.10 is required; select it explicitly in PATH')
    evidence.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    env['PATH'] = str(Path(sys.executable).parent) + os.pathsep + env['PATH']
    env['RUSTUP_TOOLCHAIN'] = '1.96.0'
    receipt: dict = {'stage': 'R0', 'status': 'failed', 'commands': []}
    try:
        receipt['head'] = capture(['git', 'rev-parse', 'HEAD'], env)
        receipt['working_tree'] = capture(['git', 'status', '--porcelain'], env)
        receipt['python'] = sys.version
        receipt['rust'] = capture(['rustc', '--version'], env)
        if not receipt['rust'].startswith('rustc 1.96.0 '):
            raise RuntimeError('Rust 1.96.0 required')
        receipt['inputs'] = {name: sha(ROOT / name) for name in (
            'AGENTS.md', 'scripts/test-java.sh', 'scripts/build-java-jvm.sh',
            'lang-java-adaptor/.sdkmanrc')}
        java_files = capture(['git', 'ls-files', 'lang-java-adaptor'], env).splitlines()
        receipt['java_source_sha256'] = hashlib.sha256(json.dumps(
            {name: sha(ROOT / name) for name in java_files if (ROOT / name).is_file()},
            sort_keys=True).encode()).hexdigest()
        receipt['jdk_selection'] = {key: value for key, value in env.items()
                                    if key.startswith(('CODEANA_JAVA', 'ANATOMIST_TEST_JDK_'))}
        command = ['just', 'test-java']
        log = evidence / 'java.log'
        entry = {'argv': command, 'cwd': str(ROOT), 'log': log.name}
        receipt['commands'].append(entry)
        started = time.monotonic()
        with log.open('wb') as output:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            try:
                while os.waitid(os.P_PID, process.pid,
                                os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
                    if time.monotonic() - started >= args.timeout:
                        raise subprocess.TimeoutExpired(command, args.timeout)
                    time.sleep(0.1)
            finally:
                # Keep the leader unreaped until group cleanup, preventing PID reuse.
                signal_group(process.pid, signal.SIGTERM)
                deadline = time.monotonic() + 5
                while (os.waitid(os.P_PID, process.pid,
                                os.WEXITED | os.WNOHANG | os.WNOWAIT) is None
                       and time.monotonic() < deadline):
                    time.sleep(0.1)
                signal_group(process.pid, signal.SIGKILL)
                entry['exit_code'] = process.wait()
                entry['seconds'] = time.monotonic() - started
                entry['sha256'] = sha(log)
        if entry['exit_code'] != 0:
            raise RuntimeError(f"Java baseline failed: {entry['exit_code']}")
        receipt['status'] = 'passed'
    except (Exception, KeyboardInterrupt) as error:
        receipt['error'] = f'{type(error).__name__}: {error}'
    finally:
        (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(evidence / 'receipt.json')
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
