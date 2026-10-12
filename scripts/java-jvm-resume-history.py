#!/usr/bin/env python3
"""Resume a sealed history prefix after an external worker kill.

Published revisions and runtime bytes are reused without rewriting receipts.
The private product project/home keep their identity; every unfinished stage
is actually executed again and has new evidence.
"""
import argparse
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json, project_config

spec = importlib.util.spec_from_file_location('original_history', ROOT / 'scripts/java-jvm-committed-history.py')
history = importlib.util.module_from_spec(spec); spec.loader.exec_module(history)


def require(condition, reason):
    if not condition: raise ValueError(reason)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--previous', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    args = parser.parse_args(); previous = args.previous.resolve()
    prior = json.loads((previous / 'receipt.json').read_text())
    require(prior['status'] == 'failed' and prior.get('failure') == 'INDEX_FAILED: target-incremental'
            and [phase['id'] for phase in prior['phases']] == ['baseline-full', 'baseline-cold-repeat']
            and prior['phases'][0]['graph'] == prior['phases'][1]['graph'], 'SEALED_HISTORY_PREFIX_REQUIRED')
    args.evidence.mkdir(parents=True, exist_ok=False)
    proofs = dict(prior['inputs'])
    for path in (previous / 'receipt.json', pathlib.Path(__file__), previous / 'runtime/runtime.json'):
        proofs[str(path.resolve())] = sha256(path)
    runtime = json.loads((previous / 'runtime/runtime.json').read_text())
    require(runtime['backend'] == 'jvm', 'JVM_RUNTIME_REQUIRED')
    for relative, checksum in runtime['files'].items(): proofs[str(previous / 'runtime' / relative)] = checksum
    for phase in prior['phases']:
        database = previous / 'home/revisions' / phase['revision'] / 'facts.db'
        require(sha256(database) == phase['database_sha256'], 'HISTORY_PREFIX_DATABASE_DRIFT')
        proofs[str(database)] = phase['database_sha256']
    for path, checksum in proofs.items(): require(sha256(path) == checksum, 'HISTORY_PROOF_DRIFT: ' + path)
    committed = json.loads((previous / 'committed-inputs.json').read_text())
    project = previous / 'project'; home = previous / 'home'
    for relative, checksum in committed['target']['files'].items():
        require(sha256(project / relative) == checksum, 'TARGET_COMMITTED_SOURCE_DRIFT: ' + relative)
    require(sha256(project / '.code-ana/history-inputs.json') == committed['target']['manifest_sha256'], 'TARGET_MANIFEST_DRIFT')
    configuration = project_config(['MAIN', 'TEST'], True, '.code-ana/history-inputs.json',
        2147483648, 4294967296, 8589934592, 34359738368, 1800).replace(
        b'[resources.index]\n', b'[resources.index]\njava_model_decode_bytes=2147483648\n')
    require((project / '.code-ana/config.toml').read_bytes() == configuration, 'HISTORY_CONFIG_DRIFT')
    proofs[str(project / '.code-ana/config.toml')] = sha256(project / '.code-ana/config.toml')
    # Query outputs of the passed prefix must remain bound to the original
    # revision and exact source bytes. Read them again below through the CLI.
    old = {}
    for family in ('resolve', 'method-resolve', 'source', 'structure', 'calls', 'sites',
                   'references', 'annotations', 'type-relations', 'text'):
        path = previous / ('baseline-' + family) / 'results.json'
        old[family] = json.loads(path.read_text()); proofs[str(path)] = sha256(path)
    selection = json.loads(pathlib.Path(next(path for path in prior['inputs'] if path.endswith('/selection.json'))).read_text())
    changed = [row.split('\t')[-1] for row in selection['changed_paths'] if row.endswith('.java')]
    require(len(changed) == 1, 'SINGLE_COMMITTED_CHANGE_REQUIRED')
    source = changed[0]
    baseline_bytes = (pathlib.Path(prior['endpoints']['baseline']['project']) / source).read_bytes()
    target_bytes = (project / source).read_bytes()
    require(baseline_bytes != target_bytes, 'REAL_COMMITTED_CHANGE_REQUIRED')
    binary = previous / 'runtime/target/debug'
    command = [str(binary / 'codeana-cli'), '--home', str(home), '--project', str(project)]
    report = dict(status='running', backend='jvm', phases=prior['phases'].copy(), cases=[],
                  inputs=proofs, endpoints=prior['endpoints'], resumed_from=str(previous / 'receipt.json'),
                  verified_prefix_phases=2, native_accepted=False,
                  complete_committed_history_gate=False, time_is_performance_gate=False)
    receipt = args.evidence / 'receipt.json'; atomic_json(receipt, report)
    daemon = None

    def start(number):
        nonlocal daemon
        with (args.evidence / ('daemon-' + str(number) + '.log')).open('wb') as log:
            daemon = subprocess.Popen([str(binary / 'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log,
                                      env=dict(os.environ, CODEANA_JAVA_BACKEND='jvm'))
        deadline = time.monotonic() + 15
        while subprocess.run(command + ['daemon', 'status'], capture_output=True, timeout=3).returncode:
            require(daemon.poll() is None and time.monotonic() < deadline, 'DAEMON_START_FAILED'); time.sleep(.05)

    def stop():
        nonlocal daemon
        if daemon is not None and daemon.poll() is None: daemon.terminate(); daemon.wait(timeout=30)
        daemon = None

    def index(name, full=False):
        folder = args.evidence / name; folder.mkdir(); argv = command + ['index', '--deadline-seconds', '86400'] + (['--full'] if full else [])
        started = time.monotonic()
        with (folder / 'stdout').open('wb') as out, (folder / 'stderr').open('wb') as err:
            execution = subprocess.run(argv, stdout=out, stderr=err, timeout=86430)
        lines = (folder / 'stdout').read_text().splitlines()
        terminal = [json.loads(line[9:]) for line in lines if line.startswith('terminal ')]
        records = [json.loads(line[7:]) for line in lines if line.startswith('record ')]
        require(execution.returncode == 0 and len(terminal) == 1 and terminal[0].get('revision'), 'INDEX_FAILED: ' + name)
        revision = terminal[0]['revision']; database = home / 'revisions' / revision / 'facts.db'
        phase = dict(id=name, command=argv, exit_code=execution.returncode, revision=revision, records=records,
                     terminal=terminal[0], seconds=time.monotonic() - started, database_sha256=sha256(database), graph=history.graph(database))
        report['phases'].append(phase); atomic_json(receipt, report); print(name + ': published ' + revision, flush=True)
        return phase

    symbol = 'com.alipay.iopengw.core.service.filter.route.IntegrationAuthVerifyFilter'
    method = symbol + '#supportsSandboxRestriction(com.alipay.iopengw.service.integration.iintegration.oauth.clientid.ClientInfo)'
    queries = [('resolve', ['resolve', symbol, '--unique']), ('method-resolve', ['resolve', method, '--unique']),
        ('source', ['source', source]), ('structure', ['structure', symbol]), ('calls', ['calls', method, '--depth', '2']),
        ('sites', ['call-sites', method]), ('references', ['references', symbol]), ('annotations', ['annotations', symbol]),
        ('type-relations', ['type-relations', symbol]), ('text', ['search-text', 'IntegrationAuthVerifyFilter', '--path', source])]

    def read(name, revision, source_bytes):
        result = {}
        for family, argv in queries:
            folder = args.evidence / (name + '-' + family); folder.mkdir()
            rows, pages = history.pages.query(command + argv + ['--revision', revision, '--max-records', '3', '--max-output-bytes', '8388608'], folder, 'query')
            require(all(page['terminal']['revision'] == revision for page in pages), 'QUERY_REVISION_DRIFT')
            if family == 'source':
                import hashlib
                require(rows and all(row['hash'] == hashlib.sha256(source_bytes).hexdigest() for row in rows)
                        and ''.join(row['text'] for row in rows).encode() == source_bytes, 'HISTORY_SOURCE_BYTES')
            atomic_json(folder / 'results.json', rows); atomic_json(folder / 'pages.json', pages); result[family] = rows
        return result

    try:
        start(1)
        require(read('prefix-before-resume', prior['phases'][0]['revision'], baseline_bytes) == old, 'RESUMED_PREFIX_QUERY_DRIFT')
        delta = index('target-incremental')
        current = read('target-delta', delta['revision'], target_bytes)
        require(current['sites'] != old['sites'], 'CHANGED_METHOD_QUERY_NOT_OBSERVED')
        require(read('history-after-delta', prior['phases'][0]['revision'], baseline_bytes) == old, 'OLD_REVISION_DRIFT')
        full = index('target-full', True)
        require(full['graph'] == delta['graph'], 'FULL_DELTA_GRAPH_MISMATCH')
        require(read('target-full', full['revision'], target_bytes) == current, 'FULL_DELTA_QUERY_MISMATCH')
        stop(); start(2)
        require(read('history-after-restart', prior['phases'][0]['revision'], baseline_bytes) == old, 'RESTART_HISTORY_DRIFT')
        require(read('current-after-restart', delta['revision'], target_bytes) == current, 'RESTART_CURRENT_DRIFT')
        report['changed_inputs'] = [path for path, checksum in proofs.items() if sha256(path) != checksum]
        report.update(status='passed' if not report['changed_inputs'] else 'failed', full_delta_equal=True,
                      historical_source_equal=True, restart_equal=True, complete_committed_history_gate=not report['changed_inputs'])
    except BaseException as error: report.update(status='failed', failure=str(error))
    finally: stop(); atomic_json(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__': raise SystemExit(main())
