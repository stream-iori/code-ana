#!/usr/bin/env python3
"""One diagnostic pair per failed query, using retained revisions and unchanged paths."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'candidate', 'query-plan', 'evidence'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    locator = json.loads(args.query_plan.read_text())['declaration_cases'][0]['locator']
    commands = [
        ('resolve', ['resolve', locator['symbol_id'], '--unique']),
        ('structure', ['structure', locator['symbol_id']]),
        ('source', ['source', locator['source_file']]),
        ('search-code', ['search-code', locator['symbol_id']]),
        ('search-text', ['search-text', locator['symbol_id'].split('.')[-1], '--path', locator['source_file']]),
        ('overview', ['overview']),
    ]
    cases = [('jvm', 'cold_application_cache', 'search-text'),
             ('native', 'cold_application_cache', 'structure'),
             ('native', 'warm_application_cache', 'overview')]
    report = dict(status='running', diagnostic_only=True, optimization_accepted=False,
                  scope='one pair per originally failed query; fresh daemon, same preceding queries; OS page cache uncontrolled',
                  original_performance_receipts_unchanged=True, cases=[], inputs={})
    for path in (args.baseline, args.candidate, args.query_plan, Path(__file__)):
        report['inputs'][str(path.resolve())] = sha(path)
    try:
        for backend, temperature, query in cases:
            trials = []
            for version, receipt_path in [('baseline', args.baseline), ('candidate', args.candidate)]:
                receipt = json.loads(receipt_path.read_text())
                phase = next(r['phase'] for r in receipt['rounds'] if r['backend'] == backend and r['temperature'] == temperature)
                folder = receipt_path.resolve().parent / ('1-' + backend)
                binaries = (folder / 'runtime/target/debug').resolve(strict=True)
                home = folder / 'home'
                database = home / 'revisions' / phase['revision'] / 'facts.db'
                if sha(database) != phase['database_sha256']:
                    raise ValueError('RETAINED_DATABASE_DRIFT')
                report['inputs'][str(database)] = phase['database_sha256']
                for name in ('codeana-cli', 'codeana-daemon'):
                    app = (binaries / name).resolve()
                    if phase['inputs'].get(str(app)) != sha(app):
                        # Historical runtimes recorded their original lexical path.
                        recorded = phase['inputs'].get(str(folder / 'runtime/target/debug' / name))
                        if recorded != sha(app):
                            raise ValueError('RETAINED_APPLICATION_DRIFT: ' + str(app))
                    report['inputs'][str(app)] = sha(app)
                output = args.evidence / (backend + '-' + temperature + '-' + query + '-' + version)
                output.mkdir()
                base = [str(binaries / 'codeana-cli'), '--home', str(home), '--project', str(folder / 'project')]
                daemon = None
                try:
                    with (output / 'daemon.log').open('w') as log:
                        daemon = subprocess.Popen([str(binaries / 'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log)
                        deadline = time.monotonic() + 20
                        while subprocess.run(base + ['daemon', 'status'], capture_output=True, timeout=3).returncode:
                            if daemon.poll() is not None or time.monotonic() >= deadline:
                                raise ValueError('QUERY_RECHECK_DAEMON_FAILED: ' + str(daemon.poll()))
                            time.sleep(.05)
                        # Recreate the original query order before the affected item.
                        for name, argv in commands:
                            argv = list(argv)
                            if name != 'overview':
                                argv += ['--module', locator['module'], '--scope', locator['scope']]
                            argv += ['--revision', phase['revision'], '--max-records', '10000', '--max-output-bytes', '8388608']
                            started = time.monotonic()
                            result = subprocess.run(base + argv, text=True, capture_output=True, timeout=120)
                            elapsed = time.monotonic() - started
                            if result.returncode:
                                (output / (name + '.stderr')).write_text(result.stderr)
                                raise ValueError('QUERY_RECHECK_FAILED: ' + name)
                            if name != query:
                                continue
                            (output / 'stdout').write_text(result.stdout)
                            (output / 'stderr').write_text(result.stderr)
                            records = [json.loads(line[7:]) for line in result.stdout.splitlines() if line.startswith('record ')]
                            terminal = [json.loads(line[9:]) for line in result.stdout.splitlines() if line.startswith('terminal ')]
                            if not records or len(terminal) != 1 or terminal[0]['revision'] != phase['revision']:
                                raise ValueError('QUERY_RECHECK_RESULT_INVALID')
                            original = json.loads((folder / temperature / 'receipt.json').read_text())
                            previous = [json.loads(line[7:]) for line in (folder / temperature / (query + '.stdout')).read_text().splitlines() if line.startswith('record ')]
                            if records != previous or original['revision'] != phase['revision']:
                                raise ValueError('QUERY_RECHECK_RESULT_DRIFT')
                            trials.append(dict(version=version, seconds=elapsed, records=len(records), output_sha256=sha(output / 'stdout')))
                            break
                finally:
                    if daemon is not None and daemon.poll() is None:
                        daemon.terminate()
                        daemon.wait(timeout=30)
                if sha(database) != phase['database_sha256']:
                    raise ValueError('QUERY_RECHECK_MUTATED_DATABASE')
            ratio = trials[1]['seconds'] / trials[0]['seconds']
            report['cases'].append(dict(backend=backend, temperature=temperature, query=query, trials=trials,
                                        ratio=ratio, maximum_ratio=1.05, recheck_within_limit=ratio <= 1.05))
        report['status'] = 'passed'
        report['all_diagnostic_pairs_within_limit'] = all(c['recheck_within_limit'] for c in report['cases'])
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    (args.evidence / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'inputs'}))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
