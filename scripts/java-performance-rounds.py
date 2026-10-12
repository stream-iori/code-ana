#!/usr/bin/env python3
"""Three serial cold/warm rounds for each already accepted backend."""
import argparse
import json
import os
import pathlib
import shutil
import statistics
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json, project_config
from java_product_runtime import prepare


def accepted(path, flag):
    value = json.loads(path.read_text())
    if value.get('status') != 'passed' or value.get(flag) is not True or value.get('unexplained_failures') != []:
        raise ValueError('FULL_CORRECTNESS_REQUIRED: ' + flag)
    proofs = value.get('inputs')
    if not isinstance(proofs, dict) or not proofs:
        raise ValueError('NONEMPTY_CORRECTNESS_PROOFS_REQUIRED')
    for filename, digest in proofs.items():
        if not pathlib.Path(filename).is_absolute() or sha256(filename) != digest:
            raise ValueError('CORRECTNESS_PROOF_DRIFT: ' + filename)
    return value


def main():
    parser = argparse.ArgumentParser()
    for name in ['native-gate', 'jvm-gate', 'fact-equivalence', 'query-plan', 'frozen-project', 'native-runtime-root', 'jvm-runtime-root', 'evidence']:
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    accepted(args.native_gate, 'native_accepted')
    accepted(args.jvm_gate, 'jvm_migration_accepted')
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = dict(status='running', rounds=[], performance_accepted=False,
                  original_anatomist_comparison='not comparable to full Fact IR storage; no speedup multiplier claimed',
                  os_page_cache='uncontrolled', source='frozen committed HEAD', budget=dict(model_decode_bytes=2<<30,host_heap_bytes=2<<30,worker_memory_bytes=4<<30), inputs={})
    for path in [args.native_gate, args.jvm_gate, args.fact_equivalence, args.query_plan, pathlib.Path(__file__), ROOT/'scripts/java-performance-phase.py', ROOT/'scripts/java-performance-monitor.py']:
        report['inputs'][str(path.resolve())] = sha256(path)
    receipt = args.evidence / 'receipt.json'
    atomic_json(receipt, report)
    try:
        for number in range(3):
            order = ['jvm', 'native'] if number % 2 == 0 else ['native', 'jvm']
            for backend in order:
                folder = args.evidence / (str(number + 1) + '-' + backend)
                folder.mkdir()
                project = folder / 'project'
                shutil.copytree(args.frozen_project, project)
                (project / '.code-ana/config.toml').write_bytes(project_config(['MAIN','TEST'], True, '.code-ana/java-inputs-v2.json', 2<<30,4<<30,8<<30,32<<30,1800).replace(b'[resources.index]\n',b'[resources.index]\njava_model_decode_bytes=2147483648\n'))
                runtime_root = getattr(args, backend + '_runtime_root').resolve()
                prepare(runtime_root, folder / 'runtime', backend)
                home = folder / 'home'; home.mkdir(mode=0o700)
                jdk = pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'
                (home / 'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=4294967296\nmax_total_worker_memory_bytes=4294967296\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(jdk))+'\n')
                for temperature in ['cold_application_cache','warm_application_cache']:
                    phase = folder / temperature
                    spec_path = folder / (temperature + '-spec.json')
                    command = [sys.executable, str(ROOT/'scripts/java-performance-phase.py'), '--backend', backend, '--runtime', str(folder/'runtime'), '--project', str(project), '--home', str(home), '--evidence', str(phase), '--expected-graph', str(args.fact_equivalence.resolve()), '--query-plan', str(args.query_plan.resolve())]
                    spec = dict(command=command, cwd=str(ROOT), env={}, backend=backend, cache_temperature=temperature, disk_roots=[str(folder)], timeout_seconds=86400)
                    atomic_json(spec_path, spec)
                    observed = folder / (temperature + '-monitor')
                    with (folder / (temperature+'.log')).open('wb') as log:
                        execution = subprocess.run([sys.executable,str(ROOT/'scripts/java-performance-monitor.py'),'--spec',str(spec_path),'--evidence',str(observed)],stdout=log,stderr=subprocess.STDOUT)
                    if execution.returncode:
                        raise ValueError('EXCLUSIVE_PERFORMANCE_PHASE_FAILED: '+str(phase))
                    value=json.loads((phase/'receipt.json').read_text()); metrics=json.loads((observed/'receipt.json').read_text())
                    if value['status']!='passed' or metrics['status']!='passed' or metrics.get('foreign_analyzers'):
                        raise ValueError('PERFORMANCE_PHASE_NOT_ACCEPTED')
                    report['rounds'].append(dict(round=number+1, backend=backend, temperature=temperature,index_seconds=value['index_seconds'], monitor=metrics,phase=value))
                    atomic_json(receipt,report)
                # Keep reports and observations; remove only this newly owned duplicate home/project after both phases.
                shutil.rmtree(home); shutil.rmtree(project)
        groups={}
        for backend in ['jvm','native']:
            for temperature in ['cold_application_cache','warm_application_cache']:
                rows=[r for r in report['rounds'] if r['backend']==backend and r['temperature']==temperature]
                if len(rows)!=3:raise ValueError('THREE_EXCLUSIVE_ROUNDS_REQUIRED')
                groups[backend+':'+temperature]=dict(values=[r['index_seconds'] for r in rows],median=statistics.median(r['index_seconds'] for r in rows))
        report['changed_inputs']=[p for p,digest in report['inputs'].items() if sha256(p)!=digest]
        if report['changed_inputs']:raise ValueError('PERFORMANCE_PROOF_DRIFT')
        report.update(status='passed',performance_accepted=True,summary=groups)
    except BaseException as error:
        report.update(status='failed',failure=str(error))
    atomic_json(receipt,report)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
