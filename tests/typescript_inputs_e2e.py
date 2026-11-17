#!/usr/bin/env python3
"""Real Go prepare -> native inventory -> public Rust capture, with no skip."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time
from acceptance_support import identities, sha256

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('baseline',ROOT/'scripts/multilang-baseline.py')
baseline=importlib.util.module_from_spec(spec);spec.loader.exec_module(baseline)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--binary',type=Path,default=ROOT/'target/debug/codeana-typescript-worker')
    args=parser.parse_args();sdk=args.sdk.resolve();binary=args.binary.resolve();output=args.evidence.resolve()
    if not output.is_relative_to(ROOT/'target/typescript-c-abi'):parser.error('evidence must be new under target/typescript-c-abi')
    output.mkdir(parents=True,exist_ok=False)
    paths=[ROOT/name for name in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]
    paths += [binary,*[path for path in sdk.rglob('*') if path.is_file()]]
    before=identities(paths)
    report=dict(stage='T3 real preparation and public capture',status='failed',commands=[],inputs=before,component_only=True,T3_complete=False,product_indexing=False,formal_release_acceptance=False,performance_acceptance=False)
    def run(name,argv,environment,timeout):
        log=output/(name+'.log');entry=dict(name=name,argv=list(map(str,argv)),log=log.name);report['commands'].append(entry)
        started=time.monotonic()
        with log.open('wb') as stream:
            process=subprocess.Popen(entry['argv'],cwd=ROOT,env=environment,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:process.wait(timeout=timeout)
            finally:
                baseline.signal_group(process.pid,signal.SIGKILL)
                entry.update(exit_code=process.wait(),seconds=time.monotonic()-started,sha256=sha256(log))
        if entry['exit_code']:raise AssertionError(f'{name} failed; see {log}')
    try:
        prepared=output/'prepared'
        run('go-prepare',[binary,'--sdk',sdk,'--component',ROOT/'lang-typescript-adaptor/probe/fixtures','--evidence',prepared,'--case','prepare'],dict(os.environ,PATH=''),45)
        run('public-capture',['cargo','test','-p','codeana-config','--test','typescript_inputs','--locked','actual_compiler_inventory_uses_public_capture_without_live_fallback','--','--ignored','--exact','--nocapture'],dict(os.environ,CODEANA_TYPESCRIPT_PREPARED_FIXTURE=str(prepared/'native-inputs.json')),300)
        proof=json.loads((prepared/'capture-proof.json').read_text())
        assert proof['real_go_inventory'] and proof['public_capture'] and proof['live_inputs_deleted']
        assert proof['captured_bodies_verified']==3 and proof['published_sources']==2 and proof['units']==1
        report['proof']=proof
        after=identities(paths);report['artifact_drift']=[name for name in sorted(before.keys()|after.keys()) if before.get(name)!=after.get(name)]
        assert not report['artifact_drift']
        report['status']='passed'
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__);raise
    finally:(output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(status=report['status'],artifact_drift=report['artifact_drift'])))

if __name__=='__main__':main()
