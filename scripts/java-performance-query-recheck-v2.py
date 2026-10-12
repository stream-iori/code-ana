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


def output_only_source_delta(previous, current):
    before = json.loads(previous.read_text())['files']
    after = json.loads(current.read_text())['files']
    production = lambda name: name.startswith(('crates/', 'proto/')) or name in ('Cargo.toml', 'Cargo.lock')
    if {p for p in before if production(p)} != {p for p in after if production(p)}:
        raise ValueError('PRODUCTION_SOURCE_SET_DRIFT')
    changed = {p for p in before if production(p) and before[p] != after[p]}
    if changed != {'crates/application/src/lib.rs', 'crates/storage/src/lib.rs'}:
        raise ValueError('EXACT_OUTPUT_ONLY_SOURCE_DELTA_REQUIRED')
    for name, opening, closing in [
        ('crates/application/src/lib.rs', '    fn render(', '    pub fn cancel('),
        ('crates/storage/src/lib.rs', '    pub fn event<T: Serialize>(', '    pub fn events<T: DeserializeOwned>('),
    ]:
        texts = []
        for manifest, values in [(previous, before), (current, after)]:
            source = manifest.parent / name
            if sha(source) != values[name]:
                raise ValueError('OUTPUT_ONLY_SOURCE_PROOF_DRIFT')
            text = source.read_text().split('\n#[cfg(test)]\nmod tests {')[0]
            texts.append(text)
        prefixes = [text[:text.index(opening)] for text in texts]
        suffixes = [text[text.index(closing):] for text in texts]
        if prefixes[0] != prefixes[1] or suffixes[0] != suffixes[1]:
            raise ValueError('NON_OUTPUT_PRODUCTION_CHANGE: ' + name)
    return sorted(changed)

def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'candidate', 'query-plan', 'evidence', 'comparison', 'build-receipt', 'build-manifest', 'candidate-gate', 'previous-manifest'):
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
    source_delta = output_only_source_delta(args.previous_manifest, args.build_manifest)
    measurement = json.loads(args.candidate.read_text())
    if measurement['inputs'].get(str(args.previous_manifest.resolve())) != sha(args.previous_manifest):
        raise ValueError('PREVIOUS_MEASUREMENT_SOURCE_BINDING_REQUIRED')
    comparison = json.loads(args.comparison.read_text())
    cases = [(row['backend'], row['temperature'], row['stage'])
             for row in comparison['checks'] if not row['passed'] and row['stage'] != 'full_index']
    if len(set(cases)) != len(cases) or not cases:
        raise ValueError('EXACT_FAILED_QUERY_SET_REQUIRED')
    build = json.loads(args.build_receipt.read_text())
    gate = json.loads(args.candidate_gate.read_text())
    if (build.get('status') != 'passed' or build.get('build_profile') != 'release'
            or build.get('source_manifest_sha256') != sha(args.build_manifest)
            or gate.get('status') != 'passed' or gate.get('candidate_correctness_accepted') is not True
            or gate.get('build_receipt_sha256') != sha(args.build_receipt)
            or gate.get('source_manifest_sha256') != sha(args.build_manifest)):
        raise ValueError('CURRENT_CORRECTNESS_BUILD_BINDING_REQUIRED')
    for name, digest in gate['inputs'].items():
        if sha(name) != digest:
            raise ValueError('CURRENT_CORRECTNESS_PROOF_DRIFT: ' + name)
    if comparison['inputs'].get(str(args.candidate.resolve())) != sha(args.candidate):
        raise ValueError('FAILED_MEASUREMENT_BINDING_REQUIRED')
    report = dict(status='running', diagnostic_only=True, optimization_accepted=False,
                  scope='one pair per failed query for a new source-bound output-only build; retained immutable databases, fresh daemon, same preceding queries; OS page cache uncontrolled',
                  no_index_rerun=True, output_only_changed_source_files=source_delta, expected_cases=[list(case) for case in cases],
                  original_performance_receipts_unchanged=True, cases=[], inputs={})
    for path in (args.baseline, args.candidate, args.query_plan, args.comparison, args.build_receipt, args.build_manifest, args.candidate_gate, args.previous_manifest, Path(__file__)):
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
                    if version == 'candidate':
                        if build['binaries'].get(str(app)) != sha(app):
                            raise ValueError('CURRENT_BUILD_APPLICATION_DRIFT: ' + str(app))
                    elif phase['inputs'].get(str(app)) != sha(app):
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
        if len(report['cases']) != len(cases):
            raise ValueError('COMPLETE_QUERY_RECHECK_REQUIRED')
        report['status'] = 'passed'
        report['all_diagnostic_pairs_within_limit'] = all(c['recheck_within_limit'] for c in report['cases'])
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    (args.evidence / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'inputs'}))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
