#!/usr/bin/env python3
"""Actual case-insensitive source/link spelling -> public frozen capture, no skips."""
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
    args=parser.parse_args();sdk=args.sdk.resolve();output=args.evidence.resolve()
    if not output.is_relative_to(ROOT/'target/typescript-c-abi'):parser.error('new evidence under target/typescript-c-abi required')
    output.mkdir(parents=True,exist_ok=False);root=output/'live';root.mkdir()
    (root/'Actual.ts').write_text('export function greet(value: number) {return value + 1;}\n')
    (root/'Link.ts').symlink_to('Actual.ts')
    (root/'entry.ts').write_text('import {greet as one} from "./actual"; import {greet as two} from "./link"; one(1); two(2);\n')
    (root/'tsconfig.json').write_text(json.dumps({'compilerOptions':{'noLib':True,'types':[],'module':'ESNext','moduleResolution':'bundler'},'files':['entry.ts']}))
    binary=ROOT/'target/debug/codeana-typescript-worker'
    paths=[ROOT/name for name in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]+[binary,*[p for p in sdk.rglob('*') if p.is_file()]]
    before=identities(paths);report=dict(status='failed',stage='T3 actual physical spelling capture',commands=[],inputs=before,T3_complete=False,public_semantics=False,native_accepted=False,release_accepted=False,performance_accepted=False)
    def run(name,argv,environment,timeout=45):
        log=output/(name+'.log');entry=dict(id=name,argv=list(map(str,argv)),log=log.name);report['commands'].append(entry);start=time.monotonic()
        with log.open('wb') as stream:
            process=subprocess.Popen(entry['argv'],cwd=ROOT,env=environment,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:process.wait(timeout=timeout)
            finally:
                baseline.signal_group(process.pid,signal.SIGKILL);entry.update(exit_code=process.wait(),seconds=time.monotonic()-start,sha256=sha256(log))
        assert entry['exit_code']==0,(name,entry,log.read_text())
    try:
        assert (root/'actual.ts').exists(),'this explicit gate requires a case-insensitive target volume'
        run('prepare',[binary,'--sdk',sdk,'--prepare-project',root,'--prepare-config',root/'tsconfig.json','--output',output/'native.json','--scratch',output/'scratch','--unit','spelling/fixture','--module','app','--world','spelling/main','--publish-language','typescript'],dict(os.environ,PATH=''))
        context=json.loads((output/'native.json').read_text())['contexts'][0]
        assert context['case_sensitive'] is False
        assert [Path(file['path']).name for file in context['files'] if file['role']=='publish']==['Actual.ts','entry.ts']
        assert any(link['path']==str(root/'Link.ts') for link in context['links'])
        run('public-capture',['cargo','test','-p','codeana-config','--test','typescript_inputs','--locked','actual_physical_spelling_inventory_is_captured_and_sealed','--','--ignored','--exact','--nocapture'],dict(os.environ,CODEANA_TYPESCRIPT_SPELLING_FIXTURE=str(output)),300)
        proof=json.loads((output/'capture-proof.json').read_text());assert proof['captured_bodies']==3 and proof['case_only_rename_rejected'] and proof['live_files_deleted']
        after=identities(paths);report['artifact_drift']=[name for name in sorted(before.keys()|after.keys()) if before.get(name)!=after.get(name)];assert not report['artifact_drift']
        report.update(status='passed',proof=proof)
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__);raise
    finally:(output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:report[key] for key in ('status','artifact_drift')}))
if __name__=='__main__':main()
