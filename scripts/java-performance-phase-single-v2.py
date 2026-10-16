#!/usr/bin/env python3
"""One real-project full index and fixed public queries in an exclusive phase."""
import argparse
import importlib.util
import json
import os
import pathlib
import sqlite3
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from acceptance_support import atomic_json


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', choices=['jvm', 'native'], required=True)
    for name in ['runtime', 'project', 'home', 'evidence', 'expected-graph', 'query-plan']:
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--build-receipt',type=pathlib.Path,required=True)
    parser.add_argument('--deadline-seconds',type=int,default=3600)
    args = parser.parse_args()
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    scratch = args.evidence / 'tmp'
    scratch.mkdir(mode=0o700)
    # macOS JDK 8/25 ignore TMPDIR for java.io.tmpdir. javac creates result
    # directories with Java's default temp API, so configure that separately.
    if any(character in str(scratch) for character in ('"', '\n', '\r')):
        raise ValueError('UNSUPPORTED_JAVA_TEMP_PROPERTY_PATH')
    java_options = (os.environ.get('JAVA_TOOL_OPTIONS', '')
                    + ' -Djava.io.tmpdir="' + str(scratch) + '"').strip()
    runtime = args.runtime.resolve()
    binaries = (runtime / 'target/debug').resolve(strict=True)
    build=json.loads(args.build_receipt.read_text())
    if build.get('build_profile') != 'release' or any(build['binaries'].get(str((binaries/name).resolve())) != sha256(binaries/name) for name in ('codeana-cli','codeana-daemon','codeana-java-worker')): raise ValueError('RELEASE_BUILD_DRIFT')
    command = [str(binaries / 'codeana-cli'), '--home', str(args.home.resolve()), '--project', str(args.project.resolve())]
    expected = json.loads(args.expected_graph.read_text())
    if expected.get('status') != 'passed' or expected.get('graph_storage_correctness_eligible') is not True:
        raise ValueError('BACKEND_CORRECTNESS_REQUIRED_BEFORE_PERFORMANCE')
    row = dict(status='running', backend=args.backend, index_seconds=None,
               queries=[], performance_accepted=False, native_accepted=False,
               measurement_scope='index command measured separately; monitor includes startup, queries and post-index verification')
    inputs = {str(path): sha256(path) for path in runtime.rglob('*') if path.is_file()}
    installation=json.loads((runtime/('runtime-'+args.backend+'.json')).read_text())
    if installation['binary_directory'] != str(binaries): raise ValueError('STABLE_RUNTIME_BINARY_PATH_DRIFT')
    for name,digest in installation['installed_files'].items():
        if sha256(name) != digest: raise ValueError('STABLE_RUNTIME_BUNDLE_DRIFT')
        inputs[name]=digest
    inputs.update({str((binaries/name).resolve()):sha256(binaries/name) for name in ('codeana-cli','codeana-daemon','codeana-java-worker')})
    inputs.update({str(p.resolve()): sha256(p) for p in [args.expected_graph, args.query_plan, args.build_receipt, pathlib.Path(__file__), args.project / '.code-ana/config.toml', args.project / '.code-ana/java-inputs-v2.json']})
    row['inputs'] = inputs
    daemon = None
    try:
        with (args.evidence / 'daemon.log').open('wb') as log:
            daemon = subprocess.Popen([str(binaries / 'codeana-daemon'), '--home', str(args.home.resolve())], stdout=log, stderr=log,
                                      env=dict(os.environ, TMPDIR=str(scratch), JAVA_TOOL_OPTIONS=java_options, CODEANA_PERFORMANCE_TMPDIR=str(scratch), CODEANA_JAVA_BACKEND=args.backend, CODEANA_PERFORMANCE_TIMINGS_DIR=str(args.evidence/'worker-timings')))
        deadline = time.monotonic() + 15
        while subprocess.run(command + ['daemon', 'status'], capture_output=True, timeout=3).returncode:
            if daemon.poll() is not None or time.monotonic() > deadline:
                raise ValueError('PERFORMANCE_DAEMON_START_FAILED: ' + str(daemon.poll()))
            time.sleep(.05)
        start = time.monotonic()
        with (args.evidence / 'index.stdout').open('wb') as out, (args.evidence / 'index.stderr').open('wb') as err:
            execution = subprocess.run(command + ['index', '--full', '--deadline-seconds', str(args.deadline_seconds)], stdout=out, stderr=err, timeout=args.deadline_seconds+30)
        row['index_seconds'] = time.monotonic() - start
        lines = (args.evidence / 'index.stdout').read_text().splitlines()
        terminal = [json.loads(line[9:]) for line in lines if line.startswith('terminal ')]
        records = [json.loads(line[7:]) for line in lines if line.startswith('record ')]
        if execution.returncode or len(terminal) != 1 or terminal[0].get('publication') != 'published' or not records:
            raise ValueError('PERFORMANCE_INDEX_NOT_PUBLISHED')
        row.update(revision=terminal[0]['revision'], terminal=terminal[0], records=records)
        database = args.home / 'revisions' / row['revision'] / 'facts.db'
        start = time.monotonic()
        if expected.get('graph_encoding') != 'anatomist-logical-rows':
            raise ValueError('TYPED_LOGICAL_GRAPH_BASELINE_REQUIRED')
        spec=importlib.util.spec_from_file_location('graph_storage',ROOT/'scripts/java-graph-storage-acceptance.py')
        graph_storage=importlib.util.module_from_spec(spec);spec.loader.exec_module(graph_storage)
        row['graph']=graph_storage.graph(database,ignore_execution_provenance=True,raw_metadata=expected.get('raw_metadata',False))
        row['graph_verification_seconds'] = time.monotonic() - start
        if row['graph'] != expected['graphs'][args.backend]:
            raise ValueError('PERFORMANCE_FACT_GRAPH_DRIFT')
        row['database_sha256'] = sha256(database)
        row['stage_reports'] = {}
        row['timing_sidecars'] = {}
        for source in (args.evidence/'worker-timings').glob('*.json'):
            row['timing_sidecars'][source.name] = json.loads(source.read_text())
        row['scratch_directory'] = str(scratch)
        row['java_temp_property'] = str(scratch)
        for source in (args.home/'reports').glob('*.json'):
            value=json.loads(source.read_text())
            if value.get('revision')==row['revision']:
                row['stage_reports'][source.name]=value.get('summary',{}).get('timing',{})
                (args.evidence/source.name).write_bytes(source.read_bytes())
        plan = json.loads(args.query_plan.read_text())
        chosen = plan['declaration_cases'][0]['locator']
        queries = [('resolve', ['resolve', chosen['symbol_id'], '--unique']),
                   ('structure', ['structure', chosen['symbol_id']]),
                   ('source', ['source', chosen['source_file']]),
                   ('search-code', ['search-code', chosen['symbol_id']]),
                   ('search-text', ['search-text', chosen['symbol_id'].split('.')[-1], '--path', chosen['source_file']]),
                   ('overview', ['overview'])]
        for name, argv in queries:
            if name != 'overview':
                argv += ['--module', chosen['module'], '--scope', chosen['scope']]
            start = time.monotonic()
            result = subprocess.run(command + argv + ['--revision', row['revision'], '--max-records', '10000', '--max-output-bytes', '8388608'], capture_output=True, text=True, timeout=120)
            elapsed = time.monotonic() - start
            (args.evidence / (name + '.stdout')).write_text(result.stdout)
            (args.evidence / (name + '.stderr')).write_text(result.stderr)
            terminals = [json.loads(line[9:]) for line in result.stdout.splitlines() if line.startswith('terminal ')]
            if (result.returncode or len(terminals) != 1 or terminals[0]['revision'] != row['revision']
                    or not any(line.startswith('record ') for line in result.stdout.splitlines())):
                raise ValueError('PERFORMANCE_QUERY_FAILED: ' + name)
            row['queries'].append(dict(id=name, seconds=elapsed, records=sum(line.startswith('record ') for line in result.stdout.splitlines()), terminal=terminals[0]))
        start = time.monotonic()
        with sqlite3.connect('file:' + str(database.resolve()) + '?mode=ro', uri=True) as connection:
            row['storage'] = dict(page_size=connection.execute('PRAGMA page_size').fetchone()[0],
                                  page_count=connection.execute('PRAGMA page_count').fetchone()[0],
                                  freelist_count=connection.execute('PRAGMA freelist_count').fetchone()[0],
                                  tables_indexes=[dict(name=r[0], pages=r[1], bytes=r[2], payload=r[3], unused=r[4]) for r in connection.execute('SELECT name,count(*),sum(pgsize),sum(payload),sum(unused) FROM dbstat GROUP BY name')])
        row['storage_observation_seconds'] = time.monotonic() - start
        if sha256(database) != row['database_sha256']:
            raise ValueError('PERFORMANCE_READ_ONLY_QUERY_MUTATED_DATABASE')
        row['changed_inputs'] = [path for path, digest in inputs.items() if sha256(path) != digest]
        if row['changed_inputs']:
            raise ValueError('PERFORMANCE_INPUT_DRIFT')
        row['status'] = 'passed'
    except BaseException as error:
        row.update(status='failed', failure=str(error))
    finally:
        if daemon is not None and daemon.poll() is None:
            daemon.terminate()
            daemon.wait(timeout=30)
        atomic_json(args.evidence / 'receipt.json', row)
    return 0 if row['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
