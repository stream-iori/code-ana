#!/usr/bin/env python3
"""One G1 full index, using original application paths and a sealed logical baseline."""
import argparse
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from acceptance_support import sha256
from java_release import atomic_binary_copy


def main():
    parser=argparse.ArgumentParser()
    for name in ('project','baseline','query-plan','evidence'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    parser.add_argument('--retry-preflight',action='store_true',help='Retry only when no measured phase was started')
    args=parser.parse_args()
    base=args.evidence.resolve()
    if args.retry_preflight:
        previous=json.loads((base/'receipt.json').read_text())
        if previous.get('status')!='failed' or (base/'phase').exists() or (base/'monitor').exists():
            raise ValueError('PREFLIGHT_RETRY_REQUIRES_NO_MEASURED_PHASE')
        shutil.copy2(base/'receipt.json',base/'preflight-failure.json')
        shutil.copy2(base/'run.log',base/'preflight-failure.log')
    else:base.mkdir(parents=True,exist_ok=False)
    for name,count in (('graph',6),('lifecycle',8)):
        gate=json.loads((ROOT/('target/compact-'+name+'-gate/receipt.json')).read_text())
        if gate.get('status')!='passed' or gate.get('executed_count')!=count:
            raise ValueError('COMPACT_FIXTURE_GATE_REQUIRED: '+name)
    package_gate=json.loads((ROOT/'target/compact-release-gate/receipt.json').read_text())
    if package_gate.get('all_ok') is not True:raise ValueError('COMPACT_RELEASE_GATE_REQUIRED')
    spec=importlib.util.spec_from_file_location('logical_graph',ROOT/'scripts/java-graph-storage-acceptance.py')
    graphs=importlib.util.module_from_spec(spec);spec.loader.exec_module(graphs)
    baseline=args.baseline.resolve()
    baseline_sha=sha256(baseline)
    expected=base/'expected-graph.json'
    expected.write_text(json.dumps(dict(status='passed',graph_storage_correctness_eligible=True,
        graph_encoding='anatomist-logical-rows',graphs={'native':graphs.graph(baseline,True)},
        baseline_database=str(baseline),baseline_sha256=baseline_sha),indent=2)+'\n')
    project=args.project.resolve()
    # Existing captured source/config paths are preserved, not copied or edited.
    tracked=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    source_inputs={str(ROOT/name):sha256(ROOT/name) for name in tracked if (ROOT/name).is_file() and
        name.startswith(('crates/','proto/','lang-java-adapotr/','scripts/','tests/'))}
    source_inputs.update({str(project/'.code-ana/config.toml'):sha256(project/'.code-ana/config.toml'),
        str(project/'.code-ana/java-inputs-v2.json'):sha256(project/'.code-ana/java-inputs-v2.json')})
    revision=json.loads((baseline.parent/'revision.json').read_text())
    for source in revision['manifest']['files']:
        path=project/source['path']
        if path.is_file():
            digest=sha256(path)
            if digest!=source['hash']:raise ValueError('FROZEN_PROJECT_INPUT_DRIFT: '+source['path'])
            source_inputs[str(path)]=digest
    binaries=ROOT/'target/debug';release=ROOT/'target/release'
    for line in subprocess.check_output(['ps','-axo','pid=,comm='],text=True).splitlines():
        fields=line.strip().split(None,1)
        if len(fields)==2 and fields[1].startswith(str(binaries)+'/codeana-'):
            raise ValueError('STOP_RUNTIME_BEFORE_MEASUREMENT: '+fields[0])
    saved=base/'original-binaries';saved.mkdir(exist_ok=args.retry_preflight)
    names=('codeana-cli','codeana-daemon','codeana-java-worker')
    originals={name:sha256(binaries/name) for name in names}
    report=dict(status='running',gc='G1',rounds=1,native_accepted=False,performance_accepted=False,
        source_inputs=source_inputs,baseline_bytes=baseline.stat().st_size,baseline_sha256=baseline_sha)
    touched=[]
    try:
        for name in names:
            shutil.copy2(binaries/name,saved/name)
            touched.append(name)
            atomic_binary_copy(release/name,binaries/name)
        build=base/'release-build.json'
        build.write_text(json.dumps(dict(build_profile='release',binaries={str(binaries/name):sha256(binaries/name) for name in names}),indent=2)+'\n')
        runtime=base/'runtime'
        if not runtime.is_symlink():runtime.symlink_to(ROOT/'target/runtime',target_is_directory=True)
        home=base/'home';home.mkdir(mode=0o700,exist_ok=args.retry_preflight)
        jdk=pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu'
        (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=4294967296\nmax_total_worker_memory_bytes=4294967296\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(jdk))+'\n')
        command=[sys.executable,str(ROOT/'scripts/java-performance-phase-single-v2.py'),'--backend','native',
            '--runtime',str(runtime),'--build-receipt',str(build),'--project',str(project),'--home',str(home),
            '--evidence',str(base/'phase'),'--expected-graph',str(expected),'--query-plan',str(args.query_plan.resolve())]
        monitor_spec=base/'monitor-spec.json'
        monitor_spec.write_text(json.dumps(dict(command=command,cwd=str(ROOT),env={},backend='native',gc='G1',cache_policy='independent_empty_application_state',disk_roots=[str(base)]),indent=2)+'\n')
        with (base/'run.log').open('wb') as log:
            execution=subprocess.run([sys.executable,str(ROOT/'scripts/java-performance-monitor-single-v2.py'),
                '--spec',str(monitor_spec),'--evidence',str(base/'monitor')],stdout=log,stderr=subprocess.STDOUT)
        phase=json.loads((base/'phase/receipt.json').read_text());monitor=json.loads((base/'monitor/receipt.json').read_text())
        report.update(phase=phase,monitor=monitor)
        if execution.returncode or phase['status']!='passed' or monitor['status']!='passed' or monitor.get('foreign_analyzers'):
            raise ValueError('COMPACT_SINGLE_ROUND_FAILED')
        database=home/'revisions'/phase['revision']/'facts.db'
        report.update(database=str(database),database_bytes=database.stat().st_size,index_seconds=phase['index_seconds'],
            reduction_percent=100*(1-database.stat().st_size/baseline.stat().st_size))
        if database.stat().st_size>400_000_000:raise ValueError('COMPACT_DATABASE_SIZE_TARGET')
        if sha256(baseline)!=baseline_sha:raise ValueError('BASELINE_DRIFT')
        report['source_drift']=[p for p,digest in source_inputs.items() if sha256(p)!=digest]
        if report['source_drift']:raise ValueError('COMPACT_SOURCE_DRIFT')
        report['status']='passed'
    except BaseException as error:
        report.update(status='failed',error=str(error))
    finally:
        for name in reversed(touched):atomic_binary_copy(saved/name,binaries/name)
        report['original_binaries_restored']=all(sha256(binaries/name)==digest for name,digest in originals.items())
        if not report['original_binaries_restored']:report.update(status='failed',error='ORIGINAL_BINARY_RESTORE_FAILED')
        (base/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('source_inputs','phase','monitor')},indent=2))
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
