#!/usr/bin/env python3
"""Execute independently selected declaration/source cases through all CLI pages."""
import argparse
import collections
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json
from java_inventory_canonical import Canonical, verify_source_inputs
from java_declaration_inventory_diff import connection

CORE = {'CLASS', 'INTERFACE', 'ENUM', 'ANNOTATION', 'ANNOTATION_TYPE', 'METHOD',
        'CONSTRUCTOR', 'FIELD', 'ENUM_CONSTANT', 'ANONYMOUS_CLASS'}


def normalized_symbol(value, kind):
    value = value.replace('$', '.').replace('...', '[]')
    if kind == 'CONSTRUCTOR' and '#' in value and '(' in value:
        owner, member = value.rsplit('#', 1)
        value = owner + '#<init>' + member[member.index('('):]
    return value


def declaration_key(row, product=False, canonical=None):
    kind = row['attributes']['element_kind'] if product else row['kind']
    kind = {'ANONYMOUS_CLASS': 'CLASS', 'ANNOTATION': 'ANNOTATION_TYPE'}.get(kind, kind)
    signature = row['signature'] if product else row['symbol_id']
    if product and canonical is not None:
        signature = canonical(signature, kind=kind, source=row['source'], origin='product')
    return (kind, normalized_symbol(signature, kind))


def query(command, directory, suffix):
    rows, pages, cursors = [], [], set()
    cursor = ''
    for number in range(10000):
        argv = command + (['--cursor', cursor] if cursor else [])
        execution = subprocess.run(argv, capture_output=True, timeout=180)
        prefix = directory / (suffix + '-' + str(number))
        prefix.with_suffix('.stdout').write_bytes(execution.stdout)
        prefix.with_suffix('.stderr').write_bytes(execution.stderr)
        if execution.returncode != 0:
            raise ValueError('PUBLIC_QUERY_EXIT: ' + str(execution.returncode)
                             + ': ' + execution.stderr.decode(errors='replace')[:600])
        page, terminals = [], []
        for line in execution.stdout.decode().splitlines():
            if line.startswith('record '):
                page.append(json.loads(line[7:]))
            elif line.startswith('terminal '):
                terminals.append(json.loads(line[9:]))
        if len(terminals) != 1:
            raise ValueError('PUBLIC_QUERY_TERMINAL_COVERAGE')
        terminal = terminals[0]
        pages.append(dict(command=argv, terminal=terminal, records=len(page),
                          stdout_sha256=sha256(prefix.with_suffix('.stdout')),
                          stderr_sha256=sha256(prefix.with_suffix('.stderr'))))
        rows.extend(page)
        cursor = terminal.get('cursor', '')
        if not cursor:
            return rows, pages
        if cursor in cursors:
            raise ValueError('PUBLIC_QUERY_REPEATED_CURSOR')
        cursors.add(cursor)
    raise ValueError('PUBLIC_QUERY_PAGE_BUDGET')


def main():
    parser = argparse.ArgumentParser()
    for name in ('publication-receipt', 'query-plan', 'reference-database', 'reference-receipt', 'project', 'evidence'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    publication = json.loads(args.publication_receipt.read_text())
    if publication.get('backend') != 'native' or publication.get('publication_accepted') is not True:
        raise ValueError('CURRENT_NATIVE_PUBLICATION_REQUIRED')
    plan = json.loads(args.query_plan.read_text())
    if plan['unconfirmed_contexts'] or plan['inputs_manifest_sha256'] != publication['inputs_manifest_sha256']:
        raise ValueError('INDEPENDENT_QUERY_SELECTION_REQUIRED')
    if sha256(args.reference_database) != plan['reference_database_sha256'] or sha256(args.reference_receipt) != plan['reference_receipt_sha256']:
        raise ValueError('INDEPENDENT_QUERY_REFERENCE_DRIFT')
    original = json.loads(args.reference_receipt.read_text())
    if original.get('state') != 'finished' or original.get('execution', {}).get('status') != 'passed' or original.get('changed_inputs') != []:
        raise ValueError('INDEPENDENT_QUERY_REFERENCE_NOT_ACCEPTED')
    product_db = connection(pathlib.Path(publication['published_facts']))
    reference_db = connection(args.reference_database)
    canonical = Canonical(product_db, reference_db, args.project)
    source_proof = verify_source_inputs(product_db, args.project)
    product_db.close(); reference_db.close()
    args.evidence.mkdir(parents=True, exist_ok=False)
    runtime = pathlib.Path(publication['runtime']['binary_directory'])
    home = args.publication_receipt.parent / 'home'
    command = [str(runtime / 'codeana-cli'), '--home', str(home), '--project', str(args.project)]
    proofs = {str(p.resolve()): sha256(p) for p in
              (args.publication_receipt, args.query_plan, args.reference_database, args.reference_receipt, pathlib.Path(__file__), ROOT / 'tests/java_inventory_canonical.py')}
    proofs.update({str(runtime / name): sha256(runtime / name)
                   for name in ('codeana-cli', 'codeana-daemon')})
    report = dict(backend='native', kind='frozen_native_declaration_source_public_queries', status='running',
                  cases=[], revision=publication['published_revision'], expected_cases=len(plan['declaration_cases']),
                  inputs=proofs, identity_aliases=canonical.receipt, source_proof=source_proof, complete_query_matrix=False, native_accepted=False)
    receipt = args.evidence / 'receipt.json'
    atomic_json(receipt, report)
    process = None
    try:
        with (args.evidence / 'daemon.log').open('wb') as log:
            process = subprocess.Popen([str(runtime / 'codeana-daemon'), '--home', str(home)],
                                       stdout=log, stderr=log, start_new_session=True)
            deadline = time.monotonic() + 15
            while subprocess.run(command + ['daemon', 'status'], capture_output=True, timeout=3).returncode:
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise ValueError('QUERY_DAEMON_START_FAILED')
                time.sleep(.05)
            for number, case in enumerate(plan['declaration_cases']):
                directory = args.evidence / str(number)
                directory.mkdir()
                row = dict(id=case['id'], query_class=case['query_class'], status='failed')
                locator = case['locator']
                options = ['--revision', report['revision'], '--module', locator['module'],
                           '--scope', locator['scope'], '--max-records', '7',
                           '--max-output-bytes', '8388608']
                try:
                    resolved, pages = query(command + ['resolve', '--signature', locator['symbol_id'],
                                                      '--unique', *options], directory, 'resolve')
                    if len(resolved) != 1 or resolved[0]['source'] != locator['source_file'] \
                            or declaration_key(resolved[0], True, canonical) != declaration_key(locator):
                        raise ValueError('INDEPENDENT_DECLARATION_MISMATCH')
                    identity = resolved[0]['id']
                    operation = case['operation']
                    if operation == 'resolve':
                        rows = resolved
                    else:
                        selector = locator['source_file'] if operation == 'source' else identity
                        argv = command + [operation, selector, *options]
                        if operation == 'structure':
                            argv += ['--depth', str(case['depth'])]
                        rows, operation_pages = query(argv, directory, operation)
                        pages += operation_pages
                        if operation == 'source':
                            expected = (args.project / locator['source_file']).read_bytes()
                            if hashlib.sha256(expected).hexdigest() != case['source_sha256']:
                                raise ValueError('FROZEN_QUERY_SOURCE_DRIFT')
                            offset = 0
                            for item in rows:
                                text = item['text'].encode()
                                if item['path'] != locator['source_file'] or item['span'] != dict(start=offset, end=offset + len(text)):
                                    raise ValueError('SOURCE_CHUNK_GAP_OR_DUPLICATE')
                                offset += len(text)
                            if b''.join(r['text'].encode() for r in rows) != expected:
                                raise ValueError('PUBLIC_SOURCE_BYTES_DIFFER')
                        else:
                            observed = collections.Counter(declaration_key(r, True, canonical) for r in rows
                                if r['attributes'].get('element_kind') in CORE)
                            expected = collections.Counter(declaration_key(r) for r in case['expected_declarations'])
                            if observed != expected:
                                row['missing'] = list((expected - observed).elements())
                                row['extra'] = list((observed - expected).elements())
                                raise ValueError('PUBLIC_STRUCTURE_DECLARATIONS_DIFFER')
                    if any(p['terminal'].get('revision') != report['revision'] for p in pages):
                        raise ValueError('QUERY_REVISION_DRIFT')
                    row.update(status='passed', records=len(rows), pages=len(pages),
                               result_sha256=hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest())
                    atomic_json(directory / 'results.json', rows)
                    atomic_json(directory / 'pages.json', pages)
                except Exception as failure:
                    row['failure'] = str(failure)
                report['cases'].append(row)
                atomic_json(receipt, report)
                print(case['id'] + ': ' + row['status'], flush=True)
        report['changed_inputs'] = [p for p, h in proofs.items() if sha256(p) != h]
        report['status'] = 'passed' if len(report['cases']) == report['expected_cases'] \
            and all(c['status'] == 'passed' for c in report['cases']) \
            and not report['changed_inputs'] else 'failed'
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
        atomic_json(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
