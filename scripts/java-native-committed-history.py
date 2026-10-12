#!/usr/bin/env python3
"""Actual committed-source full/delta, cold repeat and historical CLI proof.

Only private checkouts are changed. Each endpoint has its own exported Maven
inputs. A conservative full fallback is reported, never labelled delta.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json, project_config
from java_product_runtime import prepare

spec = importlib.util.spec_from_file_location('pages', ROOT / 'scripts/java-jvm-project-queries.py')
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


def graph(database):
    connection = sqlite3.connect('file:' + str(database) + '?mode=ro', uri=True)
    digest = hashlib.sha256(); count = 0
    # Canonical whole facts, including multiplicity. No sampling or counts-only.
    for (body,) in connection.execute('SELECT body FROM facts ORDER BY id'):
        value = json.dumps(json.loads(body), sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
        digest.update(len(value).to_bytes(8, 'big')); digest.update(value); count += 1
    connection.close()
    return dict(count=count, sha256=digest.hexdigest())


def main():
    parser = argparse.ArgumentParser()
    for name in ['selection', 'runtime-root', 'evidence']:
        parser.add_argument('--' + name, required=True, type=pathlib.Path)
    args = parser.parse_args(); args.evidence.mkdir(parents=True, exist_ok=False)
    selection = json.loads(args.selection.read_text())
    endpoints = {row['role']: row for row in selection['pairs']}
    original = pathlib.Path(selection['repository'])
    before = {}
    for role, row in endpoints.items():
        project = pathlib.Path(row['project'])
        committed = subprocess.check_output(['git', '-C', str(original), 'ls-tree', '-r', '--name-only', row['commit']], text=True).splitlines()
        hashes = {}
        for relative in committed:
            path = project / relative
            expected = subprocess.check_output(['git', '-C', str(original), 'show', row['commit'] + ':' + relative])
            if not path.is_file() or path.read_bytes() != expected:
                raise ValueError('COMMITTED_INPUT_DRIFT: ' + role + ':' + relative)
            hashes[relative] = hashlib.sha256(expected).hexdigest()
        manifest = project / '.code-ana/history-inputs.json'
        inputs = json.loads(manifest.read_text())
        if len(inputs['modules']) != 40:
            raise ValueError('FULL_PROJECT_MAVEN_INPUTS_REQUIRED')
        for relative, digest in inputs['controls'].items():
            if sha256(project / relative) != digest:
                raise ValueError('HISTORICAL_CONTROL_DRIFT')
        before[role] = dict(files=hashes, manifest=str(manifest), manifest_sha256=sha256(manifest))
    atomic_json(args.evidence / 'committed-inputs.json', before)
    project = args.evidence / 'project'
    shutil.copytree(pathlib.Path(endpoints['baseline']['project']), project)
    config = project / '.code-ana/config.toml'; config.parent.mkdir(exist_ok=True)
    configuration = project_config(['MAIN', 'TEST'], True, '.code-ana/history-inputs.json',
        2147483648, 4294967296, 8589934592, 34359738368, 1800)
    configuration = configuration.replace(b'[resources.index]\n', b'[resources.index]\njava_model_decode_bytes=2147483648\n')
    config.write_bytes(configuration)
    runtime = prepare(args.runtime_root, args.evidence / 'runtime', 'native')
    binary = pathlib.Path(runtime['binary_directory']); home = args.evidence / 'home'
    home.mkdir(mode=0o700)
    jdk = pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'
    (home / 'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\n'
        'max_worker_memory_bytes=4294967296\nmax_total_worker_memory_bytes=4294967296\n'
        '[toolchains.java8]\nlanguage="java"\nhome=' + json.dumps(str(jdk)) + '\n')
    command = [str(binary / 'codeana-cli'), '--home', str(home), '--project', str(project)]
    proof_paths = [args.selection, pathlib.Path(__file__), ROOT / 'scripts/java-jvm-project-queries.py',
        ROOT / 'tests/java_real_acceptance.py', ROOT / 'tests/java_product_runtime.py',
        args.evidence / 'committed-inputs.json', home / 'config.toml', jdk / 'release',
        *[pathlib.Path(row['manifest']) for row in before.values()]]
    proofs = {str(path.resolve()): sha256(path) for path in proof_paths}
    report = dict(status='running', phases=[], cases=[], endpoints=endpoints, inputs=proofs,
        backend='native', complete_committed_history_gate=False, native_accepted=False)
    receipt = args.evidence / 'receipt.json'; atomic_json(receipt, report)
    daemon = None

    def start(number):
        nonlocal daemon
        with (args.evidence / ('daemon-' + str(number) + '.log')).open('wb') as log:
            daemon = subprocess.Popen([str(binary / 'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log,
                env=dict(os.environ, CODEANA_JAVA_BACKEND='native'))
        deadline = time.monotonic() + 15
        while subprocess.run(command + ['daemon', 'status'], capture_output=True, timeout=3).returncode:
            if daemon.poll() is not None or time.monotonic() > deadline: raise ValueError('DAEMON_START_FAILED')
            time.sleep(.05)

    def stop():
        nonlocal daemon
        if daemon is not None and daemon.poll() is None:
            daemon.terminate(); daemon.wait(timeout=30)
        daemon = None

    def index(name, full=False):
        folder = args.evidence / name; folder.mkdir()
        argv = command + ['index', '--deadline-seconds', '86400'] + (['--full'] if full else [])
        started = time.monotonic()
        with (folder / 'stdout').open('wb') as out, (folder / 'stderr').open('wb') as err:
            execution = subprocess.run(argv, stdout=out, stderr=err, timeout=86430)
        lines = (folder / 'stdout').read_text().splitlines()
        terminal = [json.loads(line[9:]) for line in lines if line.startswith('terminal ')]
        records = [json.loads(line[7:]) for line in lines if line.startswith('record ')]
        if execution.returncode or len(terminal) != 1 or not terminal[0].get('revision'):
            raise ValueError('INDEX_FAILED: ' + name)
        revision = terminal[0]['revision']; database = home / 'revisions' / revision / 'facts.db'
        row = dict(id=name, command=argv, exit_code=execution.returncode, revision=revision,
            records=records, terminal=terminal[0], seconds=time.monotonic() - started,
            database_sha256=sha256(database), graph=graph(database))
        report['phases'].append(row); atomic_json(receipt, report)
        print(name + ': published ' + revision, flush=True)
        return row

    changed = [row.split('\t')[-1] for row in selection['changed_paths'] if row.endswith('.java')]
    if len(changed) != 1: raise ValueError('SINGLE_CHANGED_SOURCE_SELECTION_REQUIRED')
    source = changed[0]
    symbol = 'com.alipay.iopengw.core.service.filter.route.IntegrationAuthVerifyFilter'
    method = symbol + '#supportsSandboxRestriction(com.alipay.iopengw.service.integration.iintegration.oauth.clientid.ClientInfo)'
    queries = [('resolve', ['resolve', symbol, '--unique']), ('method-resolve', ['resolve', method, '--unique']), ('source', ['source', source]),
        ('structure', ['structure', symbol]), ('calls', ['calls', method, '--depth', '2']),
        ('sites', ['call-sites', method]), ('references', ['references', symbol]),
        ('annotations', ['annotations', symbol]), ('type-relations', ['type-relations', symbol]),
        ('text', ['search-text', 'IntegrationAuthVerifyFilter', '--path', source])]

    def read(name, revision, source_bytes):
        result = {}
        for family, argv in queries:
            folder = args.evidence / (name + '-' + family); folder.mkdir()
            rows, proof = pages.query(command + argv + ['--revision', revision, '--max-records', '3', '--max-output-bytes', '8388608'], folder, 'query')
            if any(page['terminal']['revision'] != revision for page in proof): raise ValueError('QUERY_REVISION_DRIFT')
            if family == 'source':
                if not rows or any(r['hash'] != hashlib.sha256(source_bytes).hexdigest() for r in rows): raise ValueError('HISTORY_SOURCE_HASH')
                if ''.join(row['text'] for row in rows).encode() != source_bytes: raise ValueError('HISTORY_SOURCE_BYTES')
            atomic_json(folder / 'results.json', rows); atomic_json(folder / 'pages.json', proof)
            result[family] = rows
        return result

    try:
        start(1)
        baseline = index('baseline-full', True)
        baseline_bytes = (project / source).read_bytes()
        old = read('baseline', baseline['revision'], baseline_bytes)
        stop(); start(2)
        cold = index('baseline-cold-repeat')
        if cold['graph'] != baseline['graph']: raise ValueError('COLD_GRAPH_MISMATCH')
        # Replace exactly the committed endpoints, preserving product control config.
        for relative in set(before['baseline']['files']) - set(before['target']['files']):
            (project / relative).unlink()
        for relative in before['target']['files']:
            target_file = pathlib.Path(endpoints['target']['project']) / relative
            destination = project / relative; destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target_file, destination)
        shutil.copy2(before['target']['manifest'], project / '.code-ana/history-inputs.json')
        target_bytes = (project / source).read_bytes()
        if target_bytes == baseline_bytes: raise ValueError('REAL_SOURCE_CHANGE_REQUIRED')
        delta = index('target-incremental')
        current = read('target-delta', delta['revision'], target_bytes)
        if current['sites'] == old['sites']: raise ValueError('CHANGED_METHOD_QUERY_NOT_OBSERVED')
        if read('history-after-delta', baseline['revision'], baseline_bytes) != old: raise ValueError('OLD_REVISION_DRIFT')
        full = index('target-full', True)
        if delta['graph'] != full['graph']: raise ValueError('FULL_DELTA_GRAPH_MISMATCH')
        if read('target-full', full['revision'], target_bytes) != current: raise ValueError('FULL_DELTA_QUERY_MISMATCH')
        stop(); start(3)
        if read('history-after-restart', baseline['revision'], baseline_bytes) != old: raise ValueError('RESTART_HISTORY_DRIFT')
        if read('current-after-restart', delta['revision'], target_bytes) != current: raise ValueError('RESTART_CURRENT_DRIFT')
        report['changed_inputs'] = [p for p, digest in proofs.items() if sha256(p) != digest]
        report.update(status='passed' if not report['changed_inputs'] else 'failed',
            full_delta_equal=True, historical_source_equal=True, restart_equal=True,
            time_is_performance_gate=False, complete_committed_history_gate=not report['changed_inputs'])
    except BaseException as error: report.update(status='failed', failure=str(error))
    finally:
        stop(); atomic_json(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__': raise SystemExit(main())
