#!/usr/bin/env python3
"""Prove unchanged model producers across bundles; this never accepts a model case."""
import argparse
import hashlib
import io
import json
import pathlib
import zipfile

BRIDGE_CLASSES = {
    'dev/codeana/adapter/AnalysisSession.class',
    'dev/codeana/adapter/AnalysisSession$ProjectView.class',
    'dev/codeana/adapter/FactProjection.class',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def entries(data, depth=0):
    if depth > 4:
        raise ValueError('ARCHIVE_DEPTH')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = [row.filename for row in archive.infolist() if not row.is_dir()]
        if len(names) != len(set(names)) or len(names) > 10000:
            raise ValueError('ARCHIVE_ENTRY_IDENTITY')
        if sum(row.file_size for row in archive.infolist()) > 512 << 20:
            raise ValueError('ARCHIVE_BYTES')
        result = {}
        for name in sorted(names):
            content = archive.read(name)
            if name.startswith('com/anatomist/') and name.endswith('.class') \
                    and b'dev/codeana/adapter' in content:
                raise ValueError('MODEL_PRODUCER_DEPENDS_ON_CHANGED_BRIDGE: ' + name)
            if name.endswith('.jar'):
                content = json.dumps(entries(content, depth + 1), sort_keys=True).encode()
            result[name] = sha(content)
        return result


def compare(before, after, witness):
    before, after, witness = map(pathlib.Path, (before, after, witness))
    witness_bytes = witness.read_bytes()
    if b'dev/codeana/adapter' in witness_bytes:
        raise ValueError('WITNESS_DEPENDS_ON_CHANGED_BRIDGE')
    report = dict(kind='unchanged_model_producer_bytes', status='passed',
                  witness=str(witness.resolve()), witness_sha256=sha(witness_bytes),
                  tool_sha256=sha(pathlib.Path(__file__).read_bytes()),
                  bundles=[], complete_model_gate=False, native_accepted=False)
    for name in ('runner.jar', 'compiler-bridge.jar', 'maven-input-worker.jar'):
        old, new = (before / name).read_bytes(), (after / name).read_bytes()
        prior, current = entries(old), entries(new)
        changed = sorted(n for n in prior.keys() | current.keys()
                         if prior.get(n) != current.get(n))
        allowed = BRIDGE_CLASSES if name == 'runner.jar' else set()
        unexpected = [n for n in changed if n not in allowed
                      or n not in prior or n not in current]
        if unexpected:
            raise ValueError('MODEL_PRODUCER_CHANGED: ' + name + ': ' + ', '.join(unexpected))
        report['bundles'].append(dict(name=name, before_sha256=sha(old), after_sha256=sha(new),
                                     normalized_entries=len(prior), changed_bridge_classes=changed))
    return report


def main():
    parser = argparse.ArgumentParser()
    for name in ('before', 'after', 'witness', 'output'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        result = compare(args.before, args.after, args.witness)
    except Exception as failure:
        result = dict(kind='unchanged_model_producer_bytes', status='failed', failure=str(failure),
                      complete_model_gate=False, native_accepted=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(status=result['status'], failure=result.get('failure'))))
    return 0 if result['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
