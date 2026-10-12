#!/usr/bin/env python3
"""Require the complete shared Java producer bytecode/resources to match JVM."""
import argparse
import importlib.util
import json
import pathlib
import hashlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('bundle_entries', ROOT / 'scripts/java-model-runtime-equivalence.py')
entries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entries)


def main():
    parser = argparse.ArgumentParser()
    for name in ['jvm-jar', 'native-jar', 'output']:
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('NEW_PRODUCER_RECEIPT_REQUIRED')
    a = entries.entries(args.jvm_jar.read_bytes())
    b = entries.entries(args.native_jar.read_bytes())
    selected = {name for name in a if name.startswith(('com/anatomist/', 'dev/codeana/adapter/'))
                or name.startswith('META-INF/anatomist/') and name.endswith('.jar')}
    extra = sorted(name for name in b.keys() - a.keys() if name.startswith(('com/anatomist/', 'dev/codeana/adapter/')))
    changed = sorted(name for name in selected if a[name] != b.get(name))
    paths = [args.jvm_jar, args.native_jar, pathlib.Path(__file__), ROOT / 'scripts/java-model-runtime-equivalence.py']
    report = dict(kind='shared_native_jvm_producer_bytecode', status='passed' if selected and not changed and not extra else 'failed',
                  shared_entries=len(selected), changed_entries=changed, unexpected_shared_entries=extra, native_accepted=False,
                  inputs={str(path.resolve()): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths})
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
