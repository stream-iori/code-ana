#!/usr/bin/env python3
"""Exclusive full-index comparisons with independent empty application state per run."""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import shutil
import statistics
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tests"))


def sha(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def atomic(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(path)


def run(args):
    if os.environ.get('CODEANA_PROFILE_ALLOCATIONS') == '1':
        raise ValueError('DIAGNOSTIC_RECORDING_MUST_BE_DISABLED_FOR_PERFORMANCE')
    if args.evidence.is_relative_to(args.frozen_project):
        raise ValueError('PERFORMANCE_OUTPUT_INSIDE_INPUT')
    variants = json.loads(args.variants.read_text())
    if (not variants or len({v['name'] for v in variants}) != len(variants)
            or any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]*', v['name'])
                   or v['backend'] not in ('native', 'jvm') for v in variants)):
        raise ValueError('UNIQUE_NONEMPTY_VARIANTS_REQUIRED')
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = dict(status='running', cache_policy='independent_empty_application_state',
                  os_page_cache='uncontrolled', native_warmup=False, rounds=[],
                  performance_accepted=False, optimization_accepted=False,
                  inputs={str(p.resolve()): sha(p) for p in
                          (args.variants, args.frozen_receipt, args.expected_graph,
                           args.query_plan, pathlib.Path(__file__))})
    receipt = args.evidence / 'receipt.json'
    spec = importlib.util.spec_from_file_location('acceptance', ROOT / 'tests/java_real_acceptance.py')
    acceptance = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(acceptance)
    try:
        report['frozen_project'] = acceptance.verify_frozen(args.frozen_project, args.frozen_receipt)
        for variant in variants:
            runtime = pathlib.Path(variant['runtime']).resolve(strict=True)
            build_receipt = pathlib.Path(variant['build_receipt']).resolve(strict=True)
            report['inputs'][str(build_receipt)] = sha(build_receipt)
            for path in runtime.rglob('*'):
                if path.is_file():
                    report['inputs'][str(path.resolve())] = sha(path)
        atomic(receipt, report)
        for number in range(args.rounds):
            order = variants if number % 2 == 0 else list(reversed(variants))
            for variant in order:
                folder = args.evidence / f'{number + 1}-{variant["name"]}'
                folder.mkdir()
                project = folder / 'project'
                shutil.copytree(args.frozen_project, project)
                acceptance.verify_frozen(project, args.frozen_receipt)
                configuration = acceptance.project_config(['MAIN', 'TEST'], True,
                    '.code-ana/java-inputs-v2.json', 2 << 30, 4 << 30, 8 << 30, 32 << 30, 1800)
                configuration = configuration.replace(b'[resources.index]\n',
                    b'[resources.index]\njava_model_decode_bytes=2147483648\n')
                (project / '.code-ana/config.toml').write_bytes(configuration)
                home = folder / 'home'
                home.mkdir(mode=0o700)
                jdk = pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'
                (home / 'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\n'
                    'max_workers=1\nmax_worker_memory_bytes=4294967296\n'
                    'max_total_worker_memory_bytes=4294967296\n[toolchains.java8]\n'
                    'language="java"\nhome=' + json.dumps(str(jdk)) + '\n')
                command = [sys.executable, str(ROOT / 'scripts/java-performance-phase-single-v2.py'),
                    '--backend', variant['backend'], '--runtime', variant['runtime'],
                    '--build-receipt', variant['build_receipt'], '--project', str(project),
                    '--home', str(home), '--evidence', str(folder / 'phase'),
                    '--expected-graph', str(variant.get('expected_graph',args.expected_graph)), '--query-plan', str(args.query_plan)]
                monitor = dict(command=command, cwd=str(ROOT), env={},
                    backend=variant['backend'], cache_policy=report['cache_policy'],
                    disk_roots=[str(folder)], timeout_seconds=86400)
                monitor_path = folder / 'monitor-spec.json'
                atomic(monitor_path, monitor)
                with (folder / 'run.log').open('wb') as log:
                    execution = subprocess.run([sys.executable,
                        str(ROOT / 'scripts/java-performance-monitor-single-v2.py'),
                        '--spec', str(monitor_path), '--evidence', str(folder / 'monitor')],
                        stdout=log, stderr=subprocess.STDOUT)
                phase = json.loads((folder / 'phase/receipt.json').read_text())
                observed = json.loads((folder / 'monitor/receipt.json').read_text())
                if execution.returncode or phase['status'] != 'passed' or observed['status'] != 'passed':
                    raise ValueError('FRESH_PHASE_FAILED: ' + str(folder))
                row = dict(round=number + 1, variant=variant['name'], phase=phase, monitor=observed)
                report['rounds'].append(row)
                atomic(receipt, report)
                # Verification and public queries finished before disposable outputs are released.
                # Runtime/config/input proofs and telemetry stay at their original paths.
                if not args.retain_databases:
                    removed = []
                    for name in ('revisions', 'blobs', 'cache', 'candidates'):
                        path = home / name
                        if path.is_dir() and not path.is_symlink():
                            shutil.rmtree(path)
                            removed.append(str(path))
                    row['released_outputs'] = removed
                    atomic(receipt, report)
        report['summary'] = {}
        for variant in variants:
            values = [r['phase']['index_seconds'] for r in report['rounds']
                      if r['variant'] == variant['name']]
            report['summary'][variant['name']] = (
                dict(index_seconds=values[0], observations=1) if len(values) == 1 else
                dict(values=values, observations=len(values),
                     median=statistics.median(values), minimum=min(values), maximum=max(values)))
        report['changed_inputs'] = [p for p, h in report['inputs'].items() if sha(p) != h]
        if report['changed_inputs']:
            raise ValueError('PERFORMANCE_INPUT_DRIFT')
        # This runner certifies measurements, never an automatic optimization/default switch.
        report['status'] = 'passed'
        report['measurement_complete'] = True
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    atomic(receipt, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('variants', 'frozen-project', 'frozen-receipt', 'expected-graph', 'query-plan', 'evidence'):
        parser.add_argument('--' + name, required=True, type=pathlib.Path)
    parser.add_argument('--rounds', type=int, choices=(1, 3), default=1)
    parser.add_argument('--retain-databases', action='store_true')
    arguments = parser.parse_args()
    for name in ('variants', 'frozen_project', 'frozen_receipt', 'expected_graph', 'query_plan', 'evidence'):
        setattr(arguments, name, getattr(arguments, name).resolve())
    sys.exit(run(arguments))
