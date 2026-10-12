#!/usr/bin/env python3
"""Frozen-reference trace/boundary proofs and restart of a published revision.

This gate does not claim committed-history or full/delta equivalence.
"""
import argparse
import collections
import importlib.util
import json
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_declaration_inventory_diff import connection
from java_real_acceptance import atomic_json

spec = importlib.util.spec_from_file_location('navigation', ROOT / 'scripts/java-jvm-representative-queries.py')
navigation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(navigation)


def main():
    parser = argparse.ArgumentParser()
    for name in ['publication-receipt', 'query-plan', 'reference-database', 'reference-receipt', 'project', 'query-binaries', 'evidence']:
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    pub = json.loads(args.publication_receipt.read_text())
    plan = json.loads(args.query_plan.read_text())
    if pub.get('publication_accepted') is not True or pub.get('backend') != 'jvm':
        raise ValueError('ACCEPTED_JVM_PUBLICATION_REQUIRED')
    for name in ['reference_database', 'reference_receipt']:
        if sha256(getattr(args, name)) != plan[name + '_sha256']:
            raise ValueError('REFERENCE_DRIFT')
    product = connection(pathlib.Path(pub['published_facts']))
    reference = connection(args.reference_database)
    expected = navigation.Expectations(product, reference, args.project)
    # Fix a two-hop internal path from the reference, before starting the candidate.
    edges = collections.defaultdict(set)
    for row in reference.execute('''SELECT o.caller_id,t.target_id FROM call_site_targets t
        JOIN call_sites s ON s.site_pk=t.call_site_pk
        JOIN call_site_owners o ON o.owner_pk=s.owner_pk
        WHERE t.resolution_status='exact' AND t.target_id IS NOT NULL'''):
        if all(x in expected.nodes and expected.nodes[x].get('source_file') for x in row):
            edges[row[0]].add(row[1])
    selected = None
    for start in sorted(edges):
        distances = {start: 0}
        queue = collections.deque([start])
        while queue and selected is None:
            parent = queue.popleft()
            for target in sorted(edges[parent]):
                if target in distances:
                    continue
                distances[target] = distances[parent] + 1
                if distances[target] == 2:
                    selected = (start, target)
                    break
                queue.append(target)
        if selected:
            break
    if selected is None:
        raise ValueError('REFERENCE_TWO_HOP_PATH_REQUIRED')
    start, target = selected
    anchor = expected.nodes[start]
    keys = ['id', 'symbol_id', 'kind', 'source_file', 'module', 'scope']
    selection = dict(start={k: anchor.get(k) for k in keys}, target={k: expected.nodes[target].get(k) for k in keys}, minimum_depth=2)
    atomic_json(args.evidence / 'selection.json', selection)
    paths = [args.publication_receipt, args.query_plan, args.reference_database, args.reference_receipt,
        pathlib.Path(pub['published_facts']), pathlib.Path(__file__),
        ROOT / 'scripts/java-jvm-project-queries.py', ROOT / 'scripts/java-jvm-representative-queries.py',
        ROOT / 'tests/java_inventory_canonical.py', args.evidence / 'selection.json',
        args.query_binaries / 'codeana-cli', args.query_binaries / 'codeana-daemon']
    inputs = {str(p.resolve()): sha256(p) for p in paths}
    receipt = args.evidence / 'receipt.json'
    report = dict(status='running', cases=[], revision=pub['published_revision'], inputs=inputs,
        committed_history_accepted=False, full_delta_accepted=False, native_accepted=False)
    atomic_json(receipt, report)
    home = args.publication_receipt.parent / 'home'
    command = [str(args.query_binaries / 'codeana-cli'), '--home', str(home), '--project', str(args.project)]
    options = ['--revision', report['revision'], '--max-records', '3', '--max-output-bytes', '8388608']
    scoped = ['--module', anchor['module'], '--scope', anchor['scope']]
    daemon = None

    def launch(number):
        nonlocal daemon
        with (args.evidence / ('daemon-' + str(number) + '.log')).open('wb') as log:
            daemon = subprocess.Popen([str(args.query_binaries / 'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log)
        deadline = time.monotonic() + 15
        while subprocess.run(command + ['daemon', 'status'], capture_output=True, timeout=3).returncode:
            if daemon.poll() is not None or time.monotonic() > deadline:
                raise ValueError('DAEMON_START_FAILED')
            time.sleep(.05)

    def stop():
        nonlocal daemon
        if daemon is not None and daemon.poll() is None:
            daemon.terminate()
            daemon.wait(timeout=30)
        daemon = None

    def query(name, argv, validate):
        folder = args.evidence / name
        folder.mkdir()
        row = dict(id=name, status='failed')
        try:
            records, pages = navigation.pages.query(command + argv + options, folder, 'query')
            if any(p['terminal']['revision'] != report['revision'] for p in pages):
                raise ValueError('REVISION_DRIFT')
            atomic_json(folder / 'results.json', records)
            atomic_json(folder / 'pages.json', pages)
            validate(records, pages)
            row.update(status='passed', records=len(records), pages=len(pages))
            return records, pages
        except Exception as error:
            row['failure'] = str(error)
            return None
        finally:
            report['cases'].append(row)
            atomic_json(receipt, report)
            print(name + ': ' + row['status'], flush=True)

    def require(value, message):
        if not value:
            raise ValueError(message)

    def trace(records, pages):
        require(len(records) == 1, 'UNIQUE_PATH_REQUIRED')
        path = records[0]
        symbols = [expected.product_symbol(x) for x in path['nodes']]
        require(symbols[0] == expected.reference_symbol(start) and symbols[-1] == expected.reference_symbol(target), 'PATH_ENDPOINTS')
        require(len(path['hops']) == 2 and len(symbols) == 3, 'SHORTEST_PATH_DEPTH')
        for number, hop in enumerate(path['hops']):
            a, b = expected.product_symbol(hop['from']), expected.product_symbol(hop['to'])
            require((a, b) == tuple(symbols[number:number+2]), 'PATH_CHAIN')
            reference_ids = [identity for identity, node in expected.nodes.items() if node.get('symbol_id') == a]
            count = sum(expected.calls(identity, False).get((a, b), 0) for identity in reference_ids)
            require(count == len(hop['sites']) and count > 0, 'INDEPENDENT_HOP_OCCURRENCES')
            require(all(expected.fact(site)['kind'] == 'call_site' for site in hop['sites']), 'HOP_SITE_KIND')

    try:
        launch(1)
        argv = ['trace', anchor['symbol_id'], '--target', expected.nodes[target]['symbol_id'], '--depth', '2', *scoped]
        before = query('trace-two-hop', argv, trace)
        query('trace-depth-boundary', ['trace', anchor['symbol_id'], '--target', expected.nodes[target]['symbol_id'], '--depth', '1', *scoped],
            lambda rows, pages: require(not rows and 'depth' in pages[-1]['terminal']['evidence']['limits'], 'TRACE_DEPTH_DISCLOSURE'))
        wanted = expected.calls(start, False)
        pipeline = args.evidence / 'calls-pipeline.json'
        atomic_json(pipeline, [dict(operation='resolve', selector=anchor['symbol_id']),
            dict(operation='call-sites'), dict(operation='dispatch', algorithm='exact')])
        query('callsites-dispatch-pipeline', ['pipeline', str(pipeline), *scoped],
            lambda rows, pages: require(collections.Counter(expected.product_symbol(r['target']) for r in rows) == collections.Counter({b: n for (a, b), n in wanted.items()}), 'PIPELINE_TARGET_MULTIPLICITY'))
        query('legal-empty-search', ['search-code', 'codeana_no_such_symbol_9f9a36e3'],
            lambda rows, pages: require(not rows and pages[-1]['terminal']['evidence']['enumeration'], 'EMPTY_ENUMERATION'))
        for name, argv, marker in [
            ('invalid-dispatch-option', ['resolve', anchor['symbol_id'], '--algorithm', 'exact'], 'ALGORITHM'),
            ('invalid-instantiability', ['runtime-implementations', anchor['symbol_id'], '--instantiability', 'invalid'], 'INSTANTIABILITY')]:
            folder = args.evidence / name; folder.mkdir()
            execution = subprocess.run(command + argv + ['--revision', report['revision']], capture_output=True, timeout=30)
            (folder / 'stdout').write_bytes(execution.stdout); (folder / 'stderr').write_bytes(execution.stderr)
            valid = execution.returncode != 0 and marker in (execution.stdout + execution.stderr).decode(errors='replace').upper()
            report['cases'].append(dict(id=name, status='passed' if valid else 'failed', exit_code=execution.returncode, command=command + argv))
            atomic_json(receipt, report)
        stop(); launch(2)
        after = query('trace-after-restart', ['trace', anchor['symbol_id'], '--target', expected.nodes[target]['symbol_id'], '--depth', '2', *scoped], trace)
        require(before is not None and after is not None and before[0] == after[0], 'RESTART_PATH_DRIFT')
        report['changed_inputs'] = [path for path, digest in inputs.items() if sha256(path) != digest]
        report['expected_cases'] = 7
        report['status'] = 'passed' if len(report['cases']) == 7 and all(r['status'] == 'passed' for r in report['cases']) and not report['changed_inputs'] else 'failed'
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    finally:
        stop(); product.close(); reference.close(); atomic_json(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
