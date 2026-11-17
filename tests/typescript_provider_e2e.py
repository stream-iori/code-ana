#!/usr/bin/env python3
"""Real CLI preparation/capture gate. Semantic indexing is pending T4--T6."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time
from acceptance_support import identities,sha256
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('baseline',ROOT/'scripts/multilang-baseline.py')
baseline=importlib.util.module_from_spec(spec);spec.loader.exec_module(baseline)
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();sdk=args.sdk.resolve();output=args.evidence.resolve()
    if not output.is_relative_to(ROOT/'target/typescript-c-abi'):parser.error('new evidence under target/typescript-c-abi required')
    output.mkdir(parents=True,exist_ok=False);home=output/'home';home.mkdir(mode=0o700);project=output/'project';project.mkdir();state=project/'.code-ana';state.mkdir()
    (project/'a.ts').write_text('export function greet(value: number) {return value + 1;}\n')
    (project/'b.js').write_text('import {greet} from "./a"; greet(1);\n')
    options={'compilerOptions':{'noLib':True,'allowJs':True,'module':'ESNext','moduleResolution':'bundler'},'include':['**/*.ts','**/*.js']}
    (project/'tsconfig.json').write_text(json.dumps(options));(project/'jsconfig.json').write_text(json.dumps(options))
    binaries=[ROOT/'target/debug'/name for name in ['codeana-cli','codeana-daemon','codeana-typescript-worker']]
    paths=[ROOT/path for path in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]+binaries+[path for path in sdk.rglob('*') if path.is_file()]
    before=identities(paths);environment=dict(os.environ,PATH='',CODEANA_TYPESCRIPT_SDK=str(sdk))
    report=dict(stage='T3 CLI TypeScript preparation',status='failed',commands=[],inputs=before,T3_complete=False,semantic_indexing=False,formal_native_acceptance=False,performance_acceptance=False)
    def config(js=True,ts=True,aliases=False,conflict=False):
        text=f"schema_version=1\nkind='project'\n[languages.javascript]\nenabled={str(js).lower()}\n[languages.typescript]\nenabled={str(ts).lower()}\n[inputs]\n"
        text += "[[inputs.entries]]\nid='primary'\nlanguage='"+('typescript' if ts else 'javascript')+"'\nroot='.'\nmanifest='tsconfig.json'\n"
        if aliases:text += "[[inputs.entries]]\nid='alias'\nlanguage='javascript'\nroot='.'\nmanifest='tsconfig.json'\n"
        if not conflict:text += "[[modules]]\nid='app'\nlanguage='"+('typescript' if ts else 'javascript')+"'\nroot='.'\n"
        (state/'config.toml').write_text(text)
    def run(name,*args):
        argv=list(map(str,[binaries[0],'--home',home,'--project',project,*args,'--format','json','--detail']))
        log=output/(name+'.log');start=time.monotonic();entry=dict(id=name,argv=argv,log=log.name);report['commands'].append(entry)
        with log.open('wb') as stream:
            process=subprocess.Popen(argv,cwd=output,env=environment,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:process.wait(timeout=90)
            finally:
                baseline.signal_group(process.pid,signal.SIGKILL);entry.update(exit_code=process.wait(),seconds=time.monotonic()-start,sha256=sha256(log))
        assert entry['exit_code']==1,(name,entry,log.read_text())
        return log.read_text()
    def catalog():return json.loads((state/'inputs.json').read_text())
    def native(unit):
        artifact=next(artifact for artifact in unit['artifacts'] if artifact['path'].endswith('/inputs.json'))
        path=project/artifact['path'];assert sha256(path)==artifact['sha256'];return json.loads(path.read_text())
    try:
        config();assert 'TYPESCRIPT_INPUT_NOT_READY' in run('dual','index','--prepare')
        first=catalog();assert first['schema_version']==3 and len(first['units'])==1
        unit=first['units'][0];assert unit['backend']=='typescript' and set(unit['source_languages'])=={'javascript','typescript'}
        original=native(unit)
        assert 'TYPESCRIPT_INPUT_NOT_READY' in run('repeat','index','--prepare')
        second=catalog()['units'][0];assert second['id']==unit['id'] and native(second)==original
        config(js=True,ts=False);assert 'TYPESCRIPT_INPUT_NOT_READY' in run('js-only','index','--prepare')
        js=catalog()['units'][0];assert js['backend']=='typescript' and js['source_languages']==['javascript']
        assert [Path(file['path']).name for file in native(js)['contexts'][0]['files'] if file['role']=='publish']==['b.js']
        config(aliases=True);assert 'TYPESCRIPT_INPUT_NOT_READY' in run('aliases','index','--prepare')
        merged=catalog();assert len(merged['units'])==1 and set(merged['units'][0]['entries'])=={'primary','alias'}
        assert merged['units'][0]['id']==unit['id']
        retained=sha256(state/'inputs.json');config(aliases=True,conflict=True)
        assert 'TYPESCRIPT_CONTEXT_CONFLICT' in run('conflict','index','--prepare');assert sha256(state/'inputs.json')==retained
        config(aliases=True);old=catalog();old['schema_version']=2;(state/'inputs.json').write_text(json.dumps(old))
        assert 'INPUT_CATALOG_STALE' in run('old-catalog','index')
        after=identities(paths);report['artifact_drift']=[path for path in sorted(before.keys()|after.keys()) if before.get(path)!=after.get(path)]
        assert not report['artifact_drift'];report.update(status='passed',executed_count=len(report['commands']),one_shared_backend=True,source_tags=['javascript','typescript'])
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__);raise
    finally:
        stop=subprocess.run([str(binaries[0]),'--home',str(home),'daemon','stop'],env=environment,capture_output=True,timeout=20)
        (output/'daemon-stop.log').write_bytes(stop.stdout+stop.stderr)
        (output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:report[key] for key in ('status','executed_count','artifact_drift')}))
if __name__=='__main__':main()
