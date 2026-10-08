#!/usr/bin/env python3
"""Verify the vendored source ledger without requiring the sibling checkout."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'lang-java-adapotr'
ledger = json.loads((ROOT / 'upstream-sources.json').read_text())
seen = set()
changed = []
for row in ledger['files']:
    path = ROOT / row['destination']
    assert path.resolve().is_relative_to(ROOT.resolve()), row['destination']
    assert row['destination'] not in seen, row['destination']
    seen.add(row['destination'])
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    assert actual == row['migrated_sha256'], 'migration ledger drift: ' + row['destination']
    if actual != row['sha256']:
        assert row.get('adaptation'), 'missing adaptation rationale: ' + row['destination']
        changed.append(row['destination'])
for path in ROOT.glob('*/src/**/com/anatomist/**/*.java'):
    assert str(path.relative_to(ROOT)) in seen, 'untracked upstream source: ' + str(path)
print(json.dumps({'upstream': ledger['commit'], 'files': len(seen), 'adapted': len(changed)}))
