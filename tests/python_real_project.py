#!/usr/bin/env python3
"""One reproducible product index over the frozen diorama-sdd sample."""
import argparse
import collections
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import sqlite3
import subprocess
import threading
import time
from product_support import ROOT, BIN
from python_product_audit import audit_calls

COMMIT = '31259b761a5ea77672d0e90bc9e2776e4dfe397e'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    project = args.project.resolve()
    inventory = json.loads(args.inventory.read_text())
    if inventory['commit'] != COMMIT:
        raise ValueError('FROZEN_COMMIT_MISMATCH')
    expected = {f['path']: f['sha256'] for f in inventory['files']}
    selected = json.loads(args.selected.read_text())
    if len(selected) != 615 or len(set(selected)) != 615:
        raise ValueError('FROZEN_SCOPE_MISMATCH')
    for relative in selected:
        if sha(project/relative) != expected[relative]:
            raise ValueError('FROZEN_SOURCE_CHANGED: '+relative)
    spec = importlib.util.spec_from_file_location('python_export', ROOT/'scripts/export-python-inputs.py')
    exporter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(exporter)
    inputs = exporter.export(project, args.source_root or ['skills/diorama/scripts', '.', 'scripts'], args.dependency,
                             pyproject=args.pyproject, extras=args.extra, scope=args.scope)
    control = project/'.code-ana'
    control.mkdir(exist_ok=True)
    (control/'python-inputs.json').write_text(json.dumps(inputs, indent=2)+'\n')
    config = ('schema_version=1\nkind="project"\n[scan]\nscopes='+json.dumps([args.scope])+'\ninclude='+json.dumps(selected)+
              '\n[languages.python]\nenabled=true\n[languages.python.discovery]\ninputs_manifest=".code-ana/python-inputs.json"\n'
              '[resources.index]\npython_result_bytes=536870912\npython_fact_limit=2000000\npython_scratch_bytes=2147483648\n'
              '[observability]\nmode="full"\n')
    if args.prepare_inputs:
        legacy = '[languages.python.discovery]\ninputs_manifest=".code-ana/python-inputs.json"\n'
        preparation = '[inputs]\n[[inputs.entries]]\nid="python"\nlanguage="python"\nroot="."\n'
        if args.pyproject:
            preparation += 'manifest='+json.dumps(args.pyproject)+'\n'
        preparation += '[inputs.entries.python]\nextras='+json.dumps(args.extra)+'\ndependency_roots='+json.dumps(args.dependency)+'\n'
        config = config.replace(legacy, preparation)
        config += '\n[[modules]]\nid="python"\nlanguage="python"\nroot="."\nsource_roots=['+','.join('{path='+json.dumps(p)+',scope="MAIN"}' for p in args.source_root or ['skills/diorama/scripts', '.', 'scripts'])+']\n'
    (control/'config.toml').write_text(config)
    (evidence/'python-inputs.json').write_bytes((control/'python-inputs.json').read_bytes())
    (evidence/'config.toml').write_text(config)
    home = evidence/'home'
    home.mkdir(mode=0o700)
    (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=4294967296\nmax_total_worker_memory_bytes=4294967296\n')
    binary = args.bin.resolve()
    receipt = dict(status='running', commit=COMMIT, source_files=615, measured=args.measure,
                   input_sha256=sha(evidence/'python-inputs.json'), config_sha256=sha(evidence/'config.toml'),
                   binaries={name: sha(binary/name) for name in ('codeana-cli', 'codeana-daemon', 'codeana-python-worker')},
                   os_cache='uncontrolled', monitor_interval_seconds=.2, memory_budget_bytes=4294967296,
                   result_budget_bytes=536870912, fact_limit=2000000, scratch_budget_bytes=2147483648)
    (evidence/'started.json').write_text(json.dumps(receipt, indent=2)+'\n')
    daemon = None
    stopped = threading.Event()
    peak = [0]
    samples = []

    def monitor():
        while not stopped.wait(.2):
            try:
                output = subprocess.check_output(['/bin/ps', '-axo', 'pid=,ppid=,rss='], text=True)
                processes = [tuple(map(int, line.split())) for line in output.splitlines() if line.strip()]
                owned = {daemon.pid}
                while True:
                    expanded = owned | {pid for pid, parent, _ in processes if parent in owned}
                    if expanded == owned:
                        break
                    owned = expanded
                rss = sum(rss*1024 for pid, _, rss in processes if pid in owned)
                peak[0] = max(peak[0], rss)
                samples.append([time.monotonic(), rss])
            except (OSError, subprocess.SubprocessError):
                continue

    def cli(*command, timeout=900):
        result = subprocess.run([str(binary/'codeana-cli'), '--home', str(home), '--project', str(project), *command],
                                capture_output=True, text=True, timeout=timeout)
        records = [json.loads(line[7:]) for line in result.stdout.splitlines() if line.startswith('record ')]
        terminal = [json.loads(line[9:]) for line in result.stdout.splitlines() if line.startswith('terminal ')]
        return result, records, terminal[-1] if terminal else None

    try:
        with (evidence/'daemon.log').open('w') as log:
            daemon = subprocess.Popen([str(binary/'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log, env=dict(os.environ, CODEANA_PERFORMANCE_TIMINGS_DIR=str(evidence/'timings')))
            until = time.monotonic()+15
            while not (home/'endpoint.json').exists():
                if daemon.poll() is not None or time.monotonic()>until:
                    raise RuntimeError('DAEMON_START_FAILED')
                time.sleep(.05)
            thread = threading.Thread(target=monitor, daemon=True)
            thread.start()
            started = time.monotonic()
            command = ['index', '--full', '--deadline-seconds', '900']
            if args.prepare_inputs:
                command.append('--prepare')
            result, records, terminal = cli(*command)
            receipt.update(index_seconds=time.monotonic()-started, exit_code=result.returncode, terminal=terminal, index_records=records)
            (evidence/'index.stdout').write_text(result.stdout)
            (evidence/'index.stderr').write_text(result.stderr)
            if result.returncode or not terminal or terminal['publication'] != 'published':
                raise RuntimeError('INDEX_FAILED')
            revision = terminal['revision']
            if args.prepare_inputs:
                catalog = json.loads((control/'inputs.json').read_text())
                (evidence/'inputs-catalog.json').write_text(json.dumps(catalog, indent=2)+'\n')
                unit = catalog['units'][0]
                prepared = next(a for a in unit['artifacts'] if a['path'].endswith('/inputs.json'))
                (evidence/'prepared-python-inputs.json').write_bytes((project/prepared['path']).read_bytes())
                receipt.update(prepared_inputs=True, catalog_sha256=sha(control/'inputs.json'), prepared_input_sha256=prepared['sha256'])
            database = home/'revisions'/revision/'facts.db'
            with sqlite3.connect(database) as db:
                receipt['counts'] = {table: db.execute('SELECT count(*) FROM '+table).fetchone()[0]
                                     for table in ('nodes', 'edges', 'call_sites', 'call_site_targets', 'analysis_coverage', 'index_diagnostics')}
                receipt['published_files'] = db.execute("SELECT count(DISTINCT source_file) FROM nodes WHERE language='python'").fetchone()[0]
                receipt['diagnostic_codes'] = dict(db.execute('SELECT code,count(*) FROM index_diagnostics GROUP BY code'))
                receipt['call_resolution_status'] = dict(db.execute('SELECT resolution_status,count(*) FROM call_sites GROUP BY resolution_status'))
                receipt['declaration_kinds'] = dict(db.execute('SELECT declaration_kind,count(*) FROM nodes GROUP BY declaration_kind'))
                receipt['missing_imports'] = sorted({name for (sample,) in db.execute("SELECT sample FROM index_diagnostics WHERE code='missing-import'")
                                                    for name in re.findall(r'Cannot find module `([^`]+)`', sample)})
                diagnostics = [dict(source=source, code=code, world=world, sample=sample, details=json.loads(details or '{}'))
                               for source, code, world, sample, details in db.execute('SELECT source_file,code,world,sample,sample_details FROM index_diagnostics')]
                receipt['diagnostic_origins'] = dict(collections.Counter(d['details'].get('origin', 'unknown') for d in diagnostics))
                for origin in ['source', 'auxiliary', 'backend']:
                    receipt[origin+'_missing_imports'] = sorted({name for d in diagnostics if d['code'] == 'missing-import' and d['details'].get('origin') == origin
                                                                for name in re.findall(r'Cannot find module `([^`]+)`', d['sample'])})
                coverage = [dict(source=source, status=status, codes=json.loads(codes), world=world)
                            for source, status, codes, world in db.execute('SELECT source_file,status,codes,world FROM analysis_coverage')]
                receipt['coverage_status'] = dict(collections.Counter(c['status'] for c in coverage if c['source'] != '*'))
                receipt['coverage_codes'] = dict(collections.Counter(code for c in coverage if c['source'] != '*' for code in c['codes']))
                receipt['partial_contexts'] = sum(c['source'] == '*' and c['status'] != 'complete' for c in coverage)
                impacts = {(d['world'], source, 'dependency_'+d['code']) for d in diagnostics for source in d['details'].get('affected_sources', [])}
                receipt['dependency_partial_without_evidence'] = sum(code.startswith('dependency_') and (c['world'], c['source'], code) not in impacts
                                                                    for c in coverage if c['source'] != '*' for code in c['codes'])
                receipt['local_search_paths_resolved'] = not ({'helpers', 'seed_remote_knowledge', 'update_remote_knowledge'} & set(receipt['source_missing_imports']))
                receipt['database_integrity'] = db.execute('PRAGMA integrity_check').fetchone()[0]
                receipt['foreign_key_violations'] = len(db.execute('PRAGMA foreign_key_check').fetchall())
                calls_selector = db.execute("SELECT id FROM nodes WHERE qualified_name='diorama_sdd._core.result.fail' AND declaration_kind='function'").fetchone()[0]
            cases = []
            for source in args.cases:
                for case in json.loads(source.read_text()):
                    if case['id'] in args.reference_case:
                        case['operation'] = 'references'
                    case['file'] = pathlib.Path(case['file']).relative_to(project).as_posix()
                    for target in case['expected']:
                        target['file'] = pathlib.Path(target['file']).relative_to(project).as_posix()
                    cases.append(case)
            audit = audit_calls(database, cases)
            (evidence/'call-audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2)+'\n')
            receipt.update(audit_passed=sum(r['passed'] for r in audit), audit_total=len(audit), database_bytes=database.stat().st_size,
                           database_sha256=sha(database), revision=revision)
            query_results = {}
            for operation, selector in [('resolve', 'WorkflowError'), ('structure', 'WorkflowError'), ('calls', calls_selector)]:
                response, rows, end = cli(operation, selector, timeout=30)
                (evidence/(operation+'.stdout')).write_text(response.stdout)
                (evidence/(operation+'.stderr')).write_text(response.stderr)
                query_results[operation] = dict(exit_code=response.returncode, records=len(rows), terminal=end)
            receipt['queries'] = query_results
            if terminal.get('report') and terminal['report'] != 'pending':
                response, rows, _ = cli('report', terminal['report'], '--max-output-bytes', '8388608', timeout=30)
                if response.returncode == 0 and rows:
                    (evidence/'report.json').write_text(json.dumps(rows[0], ensure_ascii=False, indent=2)+'\n')
                    receipt['timing'] = rows[0].get('summary', {}).get('timing')
            with sqlite3.connect(home/'catalog.db') as db:
                events = [json.loads(r[0]) for r in db.execute('SELECT body FROM events ORDER BY task,seq')]
                (evidence/'events.json').write_text(json.dumps(events, ensure_ascii=False, indent=2)+'\n')
            receipt['capture_timings'] = [json.loads(p.read_text()) for p in sorted((evidence/'timings').glob('capture-*.json'))]
            receipt['worker_metrics'] = [event['Progress'] for event in events if isinstance(event.get('Progress'), str) and event['Progress'].startswith('python_worker_memory ')]
            receipt['status'] = 'passed' if receipt['published_files'] == 615 and all(r['passed'] for r in audit) and all(q['exit_code'] == 0 and q['records'] > 0 for q in query_results.values()) else 'failed_semantic_audit'
            if receipt['dependency_partial_without_evidence'] or (args.scope == 'TEST' and not receipt['local_search_paths_resolved']):
                receipt['status'] = 'failed_coverage_audit'
    except Exception as error:
        receipt.update(status='failed', error=str(error))
    finally:
        stopped.set()
        if daemon:
            daemon.terminate()
            try:
                daemon.wait(timeout=10)
            except subprocess.TimeoutExpired:
                daemon.kill()
                daemon.wait(timeout=5)
        receipt['peak_daemon_tree_rss_bytes'] = peak[0]
        (evidence/'rss-samples.json').write_text(json.dumps(samples)+'\n')
        (evidence/'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(receipt, ensure_ascii=False))
    return receipt['status'] == 'passed'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=pathlib.Path, required=True)
    parser.add_argument('--inventory', type=pathlib.Path, required=True)
    parser.add_argument('--selected', type=pathlib.Path, required=True)
    parser.add_argument('--dependency', action='append', required=True)
    parser.add_argument('--source-root', action='append', default=[])
    parser.add_argument('--pyproject')
    parser.add_argument('--extra', action='append', default=[])
    parser.add_argument('--scope', choices=['MAIN', 'TEST'], default='MAIN')
    parser.add_argument('--cases', type=pathlib.Path, action='append', required=True)
    parser.add_argument('--reference-case', action='append', default=[], help='Case IDs that navigate attributes rather than calls')
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    parser.add_argument('--bin', type=pathlib.Path, default=BIN)
    parser.add_argument('--measure', action='store_true')
    parser.add_argument('--prepare-inputs', action='store_true')
    raise SystemExit(0 if run(parser.parse_args()) else 1)
