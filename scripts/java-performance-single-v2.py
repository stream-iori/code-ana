#!/usr/bin/env python3
"""One serial release cold/warm pair per accepted backend."""
import argparse
import importlib.util
import json
import os
import pathlib
import shutil
import tempfile
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json, project_config
spec=importlib.util.spec_from_file_location('stable_runtime',ROOT/'scripts/java-performance-stable-runtime.py')
stable=importlib.util.module_from_spec(spec);spec.loader.exec_module(stable)
prepare=stable.prepare


def accepted(path, flag, source_root=None):
    value = json.loads(path.read_text())
    if value.get('status') != 'passed' or value.get(flag) is not True or value.get('unexplained_failures') != []:
        raise ValueError('FULL_CORRECTNESS_REQUIRED: ' + flag)
    proofs = value.get('inputs')
    if not isinstance(proofs, dict) or not proofs:
        raise ValueError('NONEMPTY_CORRECTNESS_PROOFS_REQUIRED')
    for filename, digest in proofs.items():
        proof = pathlib.Path(filename)
        if source_root is not None and proof.is_absolute() and proof.is_relative_to(ROOT):
            archived = source_root / proof.relative_to(ROOT)
            if archived.is_file(): proof = archived
        if not pathlib.Path(filename).is_absolute() or sha256(proof) != digest:
            raise ValueError('CORRECTNESS_PROOF_DRIFT: ' + filename)
    return value


def candidate_correctness(path, build_manifest, build_receipt):
    # Historical Java acceptance establishes the unchanged Java bundle only.
    # Candidate Rust must carry its own complete source-bound correctness gate.
    value = accepted(path, 'candidate_correctness_accepted')
    if (value.get('source_manifest_sha256') != sha256(build_manifest)
            or value.get('build_receipt_sha256') != sha256(build_receipt)
            or value.get('build_profile') != 'release'):
        raise ValueError('CANDIDATE_CORRECTNESS_BUILD_DRIFT')
    for proof in (build_manifest, build_receipt):
        if value['inputs'].get(str(proof.resolve())) != sha256(proof):
            raise ValueError('CANDIDATE_CORRECTNESS_PROVENANCE_MISSING')
    return value


def main():
    parser = argparse.ArgumentParser()
    for name in ['native-gate', 'jvm-gate', 'fact-equivalence', 'query-plan', 'frozen-project', 'native-runtime-root', 'jvm-runtime-root', 'evidence']:
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--release-binaries', type=pathlib.Path, required=True)
    parser.add_argument('--build-receipt', type=pathlib.Path, required=True)
    parser.add_argument('--proof-source-root', type=pathlib.Path)
    parser.add_argument('--build-manifest', type=pathlib.Path, required=True)
    parser.add_argument('--candidate-gate', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.build_manifest.is_file(): raise ValueError('RELEASE_BUILD_PROVENANCE_REQUIRED')
    for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
        if not (args.release_binaries/name).is_file(): raise ValueError('RELEASE_BINARY_MISSING: '+name)
    accepted(args.native_gate, 'native_accepted', args.proof_source_root)
    accepted(args.jvm_gate, 'jvm_migration_accepted', args.proof_source_root)
    candidate_correctness(args.candidate_gate, args.build_manifest, args.build_receipt)
    build=json.loads(args.build_receipt.read_text())
    if build.get('status')!='passed' or build.get('build_profile')!='release' or build.get('source_manifest_sha256')!=sha256(args.build_manifest): raise ValueError('RELEASE_BUILD_PROVENANCE_REQUIRED')
    for filename,digest in build['binaries'].items():
        if sha256(filename)!=digest: raise ValueError('RELEASE_BUILD_BINARY_DRIFT')
    for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
        if str((args.release_binaries/name).resolve()) not in build['binaries']: raise ValueError('UNPROVEN_RELEASE_BINARY')
    manifest=json.loads(args.build_manifest.read_text())
    for relative,digest in manifest['files'].items():
        source=args.build_manifest.parent/relative
        if not source.resolve().is_relative_to(args.build_manifest.parent.resolve()) or sha256(source)!=digest: raise ValueError('RELEASE_SOURCE_DRIFT: '+relative)
    args.evidence = args.evidence.resolve()
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = dict(status='running', rounds=[], performance_accepted=False, build_profile='release', rounds_per_backend=1,
                  original_anatomist_comparison='not comparable to full Fact IR storage; no speedup multiplier claimed',
                  os_page_cache='uncontrolled', source='frozen committed HEAD', budget=dict(model_decode_bytes=2<<30,host_heap_bytes=2<<30,worker_memory_bytes=4<<30), inputs={})
    for path in [args.native_gate, args.jvm_gate, args.fact_equivalence, args.query_plan, pathlib.Path(__file__), ROOT/'scripts/java-performance-phase-single-v2.py', ROOT/'scripts/java-performance-monitor-single-v2.py', ROOT/'scripts/java-performance-stable-runtime.py', args.build_manifest, args.build_receipt]:
        report['inputs'][str(path.resolve())] = sha256(path)
    receipt = args.evidence / 'receipt.json'
    report['inputs'][str(args.candidate_gate.resolve())] = sha256(args.candidate_gate)
    atomic_json(receipt, report)
    try:
        for number in range(1):
            order = ['jvm', 'native'] if number % 2 == 0 else ['native', 'jvm']
            for backend in order:
                folder = args.evidence / (str(number + 1) + '-' + backend)
                folder.mkdir()
                project = folder / 'project'
                shutil.copytree(args.frozen_project, project)
                (project / '.code-ana/config.toml').write_bytes(project_config(['MAIN','TEST'], True, '.code-ana/java-inputs-v2.json', 2<<30,4<<30,8<<30,32<<30,1800).replace(b'[resources.index]\n',b'[resources.index]\njava_model_decode_bytes=2147483648\n'))
                runtime_root = getattr(args, backend + '_runtime_root').resolve()
                # Existing installation layout is independent of Cargo build profile.
                with tempfile.TemporaryDirectory(prefix='release-runtime-') as temporary:
                    facade=pathlib.Path(temporary)
                    (facade/'target').mkdir()
                    (facade/'target/debug').symlink_to(args.release_binaries.resolve(), target_is_directory=True)
                    (facade/'lang-java-adapotr').symlink_to(runtime_root/'lang-java-adapotr', target_is_directory=True)
                    prepare(facade, folder/'runtime', backend)
                home = folder / 'home'; home.mkdir(mode=0o700)
                jdk = pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'
                (home / 'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=4294967296\nmax_total_worker_memory_bytes=4294967296\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(jdk))+'\n')
                for temperature in ['cold_application_cache','warm_application_cache']:
                    phase = folder / temperature
                    spec_path = folder / (temperature + '-spec.json')
                    command = [sys.executable, str(ROOT/'scripts/java-performance-phase-single-v2.py'), '--backend', backend, '--runtime', str(folder/'runtime'), '--build-receipt', str(args.build_receipt.resolve()), '--project', str(project), '--home', str(home), '--evidence', str(phase), '--expected-graph', str(args.fact_equivalence.resolve()), '--query-plan', str(args.query_plan.resolve())]
                    spec = dict(command=command, cwd=str(ROOT), env={}, backend=backend, cache_temperature=temperature, disk_roots=[str(folder)], timeout_seconds=86400)
                    atomic_json(spec_path, spec)
                    observed = folder / (temperature + '-monitor')
                    with (folder / (temperature+'.log')).open('wb') as log:
                        execution = subprocess.run([sys.executable,str(ROOT/'scripts/java-performance-monitor-single-v2.py'),'--spec',str(spec_path),'--evidence',str(observed)],stdout=log,stderr=subprocess.STDOUT)
                    if execution.returncode:
                        raise ValueError('EXCLUSIVE_PERFORMANCE_PHASE_FAILED: '+str(phase))
                    value=json.loads((phase/'receipt.json').read_text()); metrics=json.loads((observed/'receipt.json').read_text())
                    if value['status']!='passed' or metrics['status']!='passed' or metrics.get('foreign_analyzers'):
                        raise ValueError('PERFORMANCE_PHASE_NOT_ACCEPTED')
                    report['rounds'].append(dict(round=number+1, backend=backend, temperature=temperature,index_seconds=value['index_seconds'], monitor=metrics,phase=value))
                    atomic_json(receipt,report)
                # Keep reports and observations; remove only this newly owned duplicate home/project after both phases.
                report.setdefault('retained_projects',[]).append(dict(backend=backend,home=str(home),project=str(project),runtime=str(folder/'runtime')))
                atomic_json(receipt,report)
        groups={}
        for backend in ['jvm','native']:
            for temperature in ['cold_application_cache','warm_application_cache']:
                rows=[r for r in report['rounds'] if r['backend']==backend and r['temperature']==temperature]
                if len(rows)!=1:raise ValueError('ONE_EXCLUSIVE_ROUND_REQUIRED')
                groups[backend+':'+temperature]=dict(seconds=rows[0]['index_seconds'])
        report['changed_inputs']=[p for p,digest in report['inputs'].items() if sha256(p)!=digest]
        if report['changed_inputs']:raise ValueError('PERFORMANCE_PROOF_DRIFT')
        report.update(status='passed',performance_accepted=True,summary=groups)
    except BaseException as error:
        report.update(status='failed',failure=str(error))
    atomic_json(receipt,report)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
