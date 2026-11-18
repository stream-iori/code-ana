#!/usr/bin/env python3
"""Real CLI preparation, capture and native product indexing gate."""
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
    (project/'tsconfig.json').write_text(json.dumps(options));(project/'jsconfig.json').write_text(json.dumps(options));(project/'second.json').write_text(json.dumps(options))
    binaries=[ROOT/'target/debug'/name for name in ['codeana-cli','codeana-daemon','codeana-typescript-worker']]
    paths=[ROOT/path for path in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]+binaries+[path for path in sdk.rglob('*') if path.is_file()]
    before=identities(paths);environment=dict(os.environ,PATH='',CODEANA_TYPESCRIPT_SDK=str(sdk))
    report=dict(stage='T6 CLI TypeScript preparation and product indexing',status='failed',commands=[],inputs=before,T3_complete=False,semantic_indexing=True,formal_native_acceptance=False,performance_acceptance=False)
    def config(js=True,ts=True,aliases=False,conflict=False,multiple=False):
        text=f"schema_version=1\nkind='project'\n[languages.javascript]\nenabled={str(js).lower()}\n[languages.typescript]\nenabled={str(ts).lower()}\n[inputs]\n"
        text += "[[inputs.entries]]\nid='primary'\nlanguage='"+('typescript' if ts else 'javascript')+"'\nroot='.'\nmanifest='tsconfig.json'\n"
        if aliases:text += "[[inputs.entries]]\nid='alias'\nlanguage='javascript'\nroot='.'\nmanifest='tsconfig.json'\n"
        if multiple:text += "[[inputs.entries]]\nid='second'\nlanguage='typescript'\nroot='.'\nmanifest='second.json'\n"
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
        failed = name in {'overlapping-module-owners','conflict','old-catalog','solution-reference-cycle','solution-stale-control','solution-missing-reference'}
        assert entry['exit_code']==int(failed),(name,entry,log.read_text())
        return log.read_text()
    def catalog():return json.loads((state/'inputs.json').read_text())
    def native(unit):
        artifact=next(artifact for artifact in unit['artifacts'] if artifact['path'].endswith('/inputs.json'))
        path=project/artifact['path'];assert sha256(path)==artifact['sha256'];return json.loads(path.read_text())
    try:
        config();run('dual','index','--prepare')
        first=catalog();assert first['schema_version']==3 and len(first['units'])==1
        unit=first['units'][0];assert unit['backend']=='typescript' and set(unit['source_languages'])=={'javascript','typescript'}
        original=native(unit)
        run('repeat','index','--prepare')
        second=catalog()['units'][0];assert second['id']==unit['id'] and native(second)==original
        config(js=True,ts=False);run('js-only','index','--prepare')
        js=catalog()['units'][0];assert js['backend']=='typescript' and js['source_languages']==['javascript']
        assert [Path(file['path']).name for file in native(js)['contexts'][0]['files'] if file['role']=='publish']==['b.js']
        config(aliases=True);run('aliases','index','--prepare')
        merged=catalog();assert len(merged['units'])==1 and set(merged['units'][0]['entries'])=={'primary','alias'}
        assert merged['units'][0]['id']==unit['id']
        config(multiple=True);run('multiple-contexts','index','--prepare')
        separate=catalog()['units'];assert len(separate)==2 and len({u['id'] for u in separate})==2
        contexts=[native(u)['contexts'][0] for u in separate]
        assert {str(project/'a.ts'),str(project/'b.js')}.issubset(set(f['path'] for f in contexts[0]['files']) & set(f['path'] for f in contexts[1]['files']))
        assert set(f['world'] for f in contexts[0]['files'] if f['role']=='publish').isdisjoint(set(f['world'] for f in contexts[1]['files'] if f['role']=='publish'))
        assert all(f['module']=='app' for c in contexts for f in c['files'] if f['role']=='publish')
        # A single compiler context may publish files from several modules.
        (project/'nested/lib').mkdir(parents=True)
        (project/'nested/main.ts').write_text('export const main = 1;\n')
        (project/'nested/lib/child.js').write_text('export const child = 2;\n')
        config(aliases=True)
        text=(state/'config.toml').read_text()+"source_roots=[{path='nested',scope='MAIN'}]\n[[modules]]\nid='child'\nlanguage='javascript'\nroot='nested/lib'\n"
        (state/'config.toml').write_text(text)
        run('file-module-owners','index','--prepare')
        owner_units=catalog()['units'];assert len(owner_units)==1 and set(owner_units[0]['modules'])=={'app','child'}
        owned=native(owner_units[0])['contexts'][0]['files']
        published={Path(f['path']).relative_to(project).as_posix():f for f in owned if f['role']=='publish'}
        assert set(published)=={'nested/main.ts','nested/lib/child.js'}
        assert published['nested/main.ts']['module']=='app' and published['nested/lib/child.js']['module']=='child'
        assert published['nested/main.ts']['world']!=published['nested/lib/child.js']['world']
        assert all(f['role']=='auxiliary' and f.get('module') is None for f in owned if Path(f['path']).name in {'a.ts','b.js'})
        retained=sha256(state/'inputs.json')
        (state/'config.toml').write_text(text+"[[modules]]\nid='overlap'\nlanguage='javascript'\nroot='.'\nsource_roots=[{path='nested',scope='MAIN'}]\n")
        assert 'TYPESCRIPT_MODULE_OWNER_CONFLICT' in run('overlapping-module-owners','index','--prepare')
        assert sha256(state/'inputs.json')==retained
        (project/'nested/lib/child.js').unlink();(project/'nested/lib').rmdir()
        (project/'nested/main.ts').unlink();(project/'nested').rmdir()
        retained=sha256(state/'inputs.json');config(aliases=True,conflict=True)
        assert 'TYPESCRIPT_CONTEXT_CONFLICT' in run('conflict','index','--prepare');assert sha256(state/'inputs.json')==retained
        config(aliases=True);old=catalog();old['schema_version']=2;(state/'inputs.json').write_text(json.dumps(old))
        assert 'INPUT_CATALOG_STALE' in run('old-catalog','index')
        (state/'legacy-a.json').write_text(json.dumps(original))
        alias=json.loads(json.dumps(original));alias['contexts'][0]['files'].reverse()
        (state/'legacy-b.json').write_text(json.dumps(alias,indent=2))
        (state/'config.toml').write_text("schema_version=1\nkind='project'\n[languages.javascript]\nenabled=true\n[languages.javascript.discovery]\ninputs_manifest='.code-ana/legacy-a.json'\n[languages.typescript]\nenabled=true\n[languages.typescript.discovery]\ninputs_manifest='.code-ana/legacy-b.json'\n[[modules]]\nid='app'\nlanguage='typescript'\nroot='.'\n")
        run('legacy-equivalent-locators','index')
        # A compiler-owned empty solution expands into distinct context units.
        for name in ['left','right']:
            (project/name).mkdir()
            (project/name/'index.ts').write_text('export const value = 1;\n')
            (project/name/'tsconfig.json').write_text(json.dumps({'compilerOptions':{'noLib':True,'composite':True},'files':['index.ts']}))
        (project/'tsconfig.json').write_text(json.dumps({'compilerOptions':{'noLib':True},'files':[],'references':[{'path':'./left'},{'path':'./right'}]}))
        config(aliases=True)
        run('rootless-solution','index','--prepare')
        solution=catalog()['units'];assert len(solution)==3 and len({u['id'] for u in solution})==3
        assert all(set(u['entries'])=={'alias','primary'} for u in solution)
        native_contexts=[native(u)['contexts'][0] for u in solution]
        empty=[c for c in native_contexts if not c['root_files']]
        assert len(empty)==1 and len(empty[0]['references'])==2
        assert not [f for f in empty[0]['files'] if f['role']=='publish']
        published=[f for c in native_contexts for f in c['files'] if f['role']=='publish']
        assert {f['path'] for f in published}=={str(project/'left/index.ts'),str(project/'right/index.ts')}
        assert len({f['world'] for f in published})==2
        saved={u['id']:native(u) for u in solution}
        run('rootless-solution-repeat','index','--prepare')
        assert {u['id']:native(u) for u in catalog()['units']}==saved
        (state/'config.toml').write_text((state/'config.toml').read_text()+"[[inputs.entries]]\nid='direct'\nlanguage='typescript'\nroot='.'\nmanifest='left/tsconfig.json'\n")
        run('solution-overlapping-entry','index','--prepare')
        merged=catalog()['units'];assert len(merged)==3
        left=next(u for u in merged if native(u)['contexts'][0]['config_path']==str(project/'left/tsconfig.json'))
        assert set(left['entries'])=={'alias','primary','direct'}
        assert len([f for u in merged for f in native(u)['contexts'][0]['files'] if f['role']=='publish'])==2
        retained=sha256(state/'inputs.json')
        right=project/'right/tsconfig.json';right_text=right.read_text()
        cycle=json.loads(right_text);cycle['references']=[{'path':'..'}];right.write_text(json.dumps(cycle))
        assert 'TYPESCRIPT_REFERENCE_CYCLE' in run('solution-reference-cycle','index','--prepare')
        assert sha256(state/'inputs.json')==retained
        right.write_text(right_text)
        solution_config=project/'tsconfig.json';solution_text=solution_config.read_text()
        changed=json.loads(solution_text);changed['references'].reverse();solution_config.write_text(json.dumps(changed))
        assert 'TYPESCRIPT_INPUT_STALE' in run('solution-stale-control','index')
        assert sha256(state/'inputs.json')==retained
        solution_config.write_text(solution_text)
        (project/'left/tsconfig.json').unlink()
        assert 'TYPESCRIPT_REFERENCE_CONFIG_MISSING' in run('solution-missing-reference','index','--prepare')
        assert sha256(state/'inputs.json')==retained
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
