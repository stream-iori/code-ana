#!/usr/bin/env python3
"""Frozen committed project and production input-capture acceptance, with process RSS."""
import argparse
import hashlib
import json
import pathlib
import platform
import resource
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=pathlib.Path, default=ROOT / 'target/java-alignment/iopengw')
    parser.add_argument('--receipt', type=pathlib.Path, default=ROOT / 'target/java-alignment/iopengw-input-receipt.json')
    args = parser.parse_args()
    freeze = json.loads((args.project.parent / 'iopengw-freeze.json').read_text())
    matrix = json.loads((ROOT / 'tests/java_alignment_matrix.json').read_text())
    expected = matrix['real_project']
    assert freeze['commit'] == expected['commit'] and not freeze['include_uncommitted']
    assert freeze['source'] == expected['path']
    entries = subprocess.check_output(['git', '-C', freeze['source'], 'ls-tree', '-rz', freeze['commit']]).split(b'\0')
    verified = 0
    java_files = 0
    for entry in entries:
        if not entry:
            continue
        head, path = entry.split(b'\t', 1)
        mode, kind, expected_hash = head.split()
        if kind != b'blob':
            continue
        assert mode != b'120000', 'frozen symlink needs explicit acceptance'
        frozen = args.project / path.decode()
        size = frozen.stat().st_size
        digest = hashlib.sha1() if len(expected_hash) == 40 else hashlib.sha256()
        digest.update(b'blob ' + str(size).encode() + b'\0')
        with frozen.open('rb') as source:
            for chunk in iter(lambda: source.read(65536), b''):
                digest.update(chunk)
        assert digest.hexdigest().encode() == expected_hash, 'committed input changed: ' + str(frozen)
        verified += 1
        java_files += path.endswith(b'.java')
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    started = time.monotonic()
    result = subprocess.run([str(ROOT / 'target/debug/examples/capture_java_inputs'), str(args.project)],
                            capture_output=True, text=True, timeout=240)
    assert result.returncode == 0, result.stderr
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    stats = json.loads(result.stdout)
    assert stats['dependency_bytes'] > 64 * 1024 * 1024
    assert stats['verified_blobs'] > 0
    stats.update(commit=freeze['commit'], include_uncommitted=False, verified_committed_files=verified,
                 committed_java_files=java_files, capture_seconds=round(time.monotonic() - started, 3),
                 child_user_seconds=round(usage.ru_utime - before.ru_utime, 3),
                 child_peak_rss_bytes=usage.ru_maxrss * (1 if platform.system() == 'Darwin' else 1024),
                 semantic_index_accepted=False)
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(stats, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == '__main__':
    main()
