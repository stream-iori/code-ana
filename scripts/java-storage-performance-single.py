#!/usr/bin/env python3
"""One exclusive replay of accepted facts, separating storage from javac."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    parser = argparse.ArgumentParser()
    for name in ['native-gate', 'fact-equivalence', 'revision-directory', 'executable', 'evidence']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--proof-source-root',type=Path)
    parser.add_argument('--build-manifest',type=Path,required=True)
    parser.add_argument('--build-receipt',type=Path,required=True)
    args = parser.parse_args()
    build=json.loads(args.build_receipt.read_text())
    if build.get('status')!='passed' or build.get('build_profile')!='release' or build['source_manifest_sha256']!=sha256(args.build_manifest) or build['binaries'].get(str(args.executable.resolve()))!=sha256(args.executable): raise ValueError('PROVEN_RELEASE_REPLAY_REQUIRED')
    rounds = module('rounds', 'java-performance-single.py')
    rounds.accepted(args.native_gate, 'native_accepted',args.proof_source_root)
    history = module('history', 'java-jvm-committed-history.py')
    equality = json.loads(args.fact_equivalence.read_text())
    if equality.get('status') != 'passed' or equality.get('whole_fact_ir_equal') is not True:
        raise ValueError('ACCEPTED_WHOLE_FACT_IR_REQUIRED')
    source = args.revision_directory.resolve()
    database = source / 'facts.db'
    expected = equality['graphs']['native']
    if history.graph(database) != expected:
        raise ValueError('SOURCE_REPLAY_GRAPH_DRIFT')
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    source_sha = sha256(database)
    inputs = {str(p.resolve()): sha256(p) for p in [args.native_gate, args.fact_equivalence,
              args.executable, Path(__file__), args.build_manifest, args.build_receipt,
              ROOT / 'scripts/java-performance-monitor-single.py', ROOT / 'scripts/java-performance-single.py']}
    receipt = args.evidence / 'receipt.json'
    report = dict(status='running', build_profile='release',rounds_per_version=1, performance_accepted=False, rounds=[], inputs=inputs,
                  source_database_sha256=source_sha, source_graph=expected,
                  os_page_cache='uncontrolled', cache_temperature='cold_application_cache',
                  scope='accepted blobs + CAF1 export + genuine SQLite stage/publish; no compiler')
    atomic_json(receipt, report)
    try:
        for number in range(1, 2):
            folder = args.evidence / str(number); folder.mkdir()
            home = folder / 'home'
            spec_path = folder / 'spec.json'
            atomic_json(spec_path, dict(command=[str(args.executable.resolve()), str(source), str(home)],
                        cwd=str(ROOT), env={}, backend='native', cache_temperature='cold_application_cache',
                        disk_roots=[str(folder)], timeout_seconds=86400))
            observed = folder / 'monitor'
            with (folder / 'run.log').open('wb') as log:
                execution = subprocess.run([sys.executable, str(ROOT / 'scripts/java-performance-monitor-single.py'),
                            '--spec', str(spec_path), '--evidence', str(observed)], stdout=log, stderr=subprocess.STDOUT)
            if execution.returncode:
                raise ValueError('EXCLUSIVE_STORAGE_REPLAY_FAILED: ' + str(folder))
            metrics = json.loads((observed / 'receipt.json').read_text())
            if metrics.get('status') != 'passed' or metrics.get('foreign_analyzers'):
                raise ValueError('EXCLUSIVE_STORAGE_METRICS_REQUIRED')
            # The example writes one JSON result; the monitor preserves its stdout.
            output = observed / 'stdout'
            value = json.loads(output.read_text().splitlines()[-1])
            target = home / 'revisions' / value['published_revision'] / 'facts.db'
            graph = history.graph(target)
            if graph != expected or sha256(database) != source_sha:
                raise ValueError('STORAGE_REPLAY_CHANGED_FACTS_OR_SOURCE')
            report['rounds'].append(dict(round=number, phases=value, metrics=metrics,
                                        graph=graph, database_sha256=sha256(target)))
            atomic_json(receipt, report)
            shutil.rmtree(home)  # Only this round's newly created duplicate.
        report['changed_inputs'] = [p for p, digest in inputs.items() if sha256(p) != digest]
        if report['changed_inputs']:
            raise ValueError('STORAGE_PERFORMANCE_PROOF_DRIFT')
        fields = ['blob_seconds', 'export_archive_seconds', 'stage_seconds', 'publish_seconds']
        report['summary'] = {field: dict(seconds=report['rounds'][0]['phases'][field]) for field in fields}
        report.update(status='passed', performance_accepted=True)
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    atomic_json(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
