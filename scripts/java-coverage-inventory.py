#!/usr/bin/env python3
"""Inventory the complete frozen production/test/fixture surface before claiming parity."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from java_source_cases import other_annotations, test_cases
from java_coverage_scope import analyzer_test_source
PROJECT = ROOT / 'lang-java-adapotr'
ledger = json.loads((PROJECT / 'upstream-sources.json').read_text())
copied = {row['source']: row for row in ledger['files']}
mapping_file = PROJECT / 'rust-coverage-mappings.json'
mapping = json.loads(mapping_file.read_text()) if mapping_file.exists() else dict(commit=ledger['commit'], files=[])
assert mapping['commit'] == ledger['commit'], 'coverage mapping commit drift'
mapped = {row['source']: row for row in mapping['files']}
archive_file = PROJECT / 'upstream-archive.json'
archive = json.loads(archive_file.read_text()) if archive_file.exists() else dict(commit=ledger['commit'], files=[])
assert archive['commit'] == ledger['commit'], 'archive commit drift'
archived = {row['source']: row for row in archive['files']}
assert len(archived) == len(archive['files']), 'duplicate archived source'
assert not archived.keys() & (copied.keys() | mapped.keys()), 'archive overlaps retained source'
assert len(mapped) == len(mapping['files']), 'duplicate coverage mapping'
assert not copied.keys() & mapped.keys(), 'copied source also marked Rust'
tree = subprocess.check_output(['git', '-C', str(ROOT.parent / 'anatomist'), 'ls-tree', '-r', ledger['commit']], text=True).splitlines()
paths = [row.split('\t',1)[1] for row in tree if row.split()[1] == 'blob']
inventory = []
for path in paths:
    if not (re.match(r'anatomist-(core|app|framework-spring|framework-sofa)/(src/|pom.xml)', path)
            or path.startswith(('fixtures/', 'e2e/fixtures/', 'tests/scenarios/'))):
        continue
    data = subprocess.check_output(['git', '-C', str(ROOT.parent / 'anatomist'), 'show', ledger['commit'] + ':' + path])
    row = dict(source=path, sha256=hashlib.sha256(data).hexdigest())
    if path in copied:
        row.update(disposition='boundary-adaptation' if copied[path].get('adaptation') else 'copied', destination=copied[path]['destination'])
        if copied[path].get('purpose'):
            row['purpose'] = copied[path]['purpose']
    elif '/cli/' in path or '/query/' in path or '/store/' in path or '/incremental/' in path:
        row.update(disposition='rust-pending', reason='Rust owns CLI/query/storage/lifecycle; semantic rules and original expectations still require individual mapping.')
    else:
        row.update(disposition='pending', reason='Dependency or coverage review required; absence does not count as accepted.')
    if analyzer_test_source(path):
        text = data.decode()
        row['test_cases'] = test_cases(text, pathlib.Path(path).stem)
        row['other_test_annotations'] = other_annotations(text)
    elif '/src/test/' in path and path.endswith('.java'):
        row['analysis_fixture_test_declarations'] = test_cases(data.decode(), pathlib.Path(path).stem)
    if path in mapped:
        replacement = mapped[path]
        assert replacement['sha256'] == row['sha256'], 'mapped upstream source drift: ' + path
        retained = PROJECT / 'migrated-test-expectations' / path
        assert retained.is_file() and hashlib.sha256(retained.read_bytes()).hexdigest() == row['sha256'], 'mapped original test bytes required: ' + path
        assert replacement['disposition'] == 'rust' and replacement['reason'], 'invalid Rust disposition'
        cases = replacement.get('test_cases', {})
        assert set(cases) == set(row.get('test_cases', [])), 'mapped case coverage: ' + path
        for case, details in cases.items():
            assert details['expectations'] and details['targets'], 'missing mapped assertions: ' + case
            evidence = ROOT / details['evidence']
            assert evidence.resolve().is_relative_to(ROOT), 'coverage evidence escapes repository'
            receipt = json.loads(evidence.read_text())
            assert receipt.get('status') == 'passed', 'mapped test has no passing evidence: ' + case
            proofs = receipt.get('inputs')
            assert isinstance(proofs, dict) and proofs, 'mapped test proof set required: ' + case
            for filename, checksum in proofs.items():
                proof = pathlib.Path(filename)
                if not proof.is_absolute():
                    proof = ROOT / proof
                assert proof.resolve().is_relative_to(ROOT), 'mapped proof escapes repository'
                assert proof.is_file() and hashlib.sha256(proof.read_bytes()).hexdigest() == checksum, 'mapped test proof drift: ' + filename
            for target in details['targets']:
                target_path = ROOT / target['path']
                assert target_path.resolve().is_relative_to(ROOT), 'coverage target escapes repository'
                assert target['symbol'] in target_path.read_text(), 'missing coverage target: ' + case
        row.update(disposition='rust', reason=replacement['reason'], case_mappings=cases)
    if path in archived:
        record = archived[path]
        assert record['sha256'] == row['sha256'], 'archived source drift: ' + path
        assert record['disposition'] == 'archived' and record['reason'], 'archive rationale required'
        assert record['original_test_cases'] == row.get('test_cases', []), 'archive test case accounting'
        if row.get('test_cases'):
            dispositions = record.get('test_case_dispositions', {})
            assert set(dispositions) == set(row['test_cases']), 'archive individual case accounting'
            assert all(case.get('disposition') == 'archived_product_contract' and case.get('reason')
                       for case in dispositions.values()), 'archive case rationale required'
            retained = PROJECT / record['archived_destination']
            assert retained.resolve().is_relative_to(PROJECT), 'archive source escapes repository'
            assert hashlib.sha256(retained.read_bytes()).hexdigest() == row['sha256'], 'archived test bytes drift'
        replacement = ROOT / record['replacement_source']
        assert replacement.resolve().is_relative_to(ROOT) and replacement.is_file(), 'archive replacement required'
        row.update(disposition='archived', reason=record['reason'], replacement_source=record['replacement_source'])
    inventory.append(row)
assert mapped.keys() <= {row['source'] for row in inventory}, 'unknown mapped source'
assert archived.keys() <= {row['source'] for row in inventory}, 'unknown archived source'
output = dict(commit=ledger['commit'], root_license='No root LICENSE/NOTICE in the frozen Git tree; bundled third-party fixture notices retained.', gitlinks=[row for row in tree if row.split()[1]=='commit'], complete=False, files=inventory)
(PROJECT / 'coverage-inventory.json').write_text(json.dumps(output, indent=2) + '\n')
print(json.dumps({'files': len(inventory), 'copied': sum(r['disposition'] in ('copied', 'boundary-adaptation') for r in inventory), 'complete': False}))
