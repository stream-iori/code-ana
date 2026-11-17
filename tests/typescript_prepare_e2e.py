#!/usr/bin/env python3
"""The real, read-only worker preparation entry; no component request or skip."""
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
    parser.add_argument('--binary',type=Path,default=ROOT/'target/debug/codeana-typescript-worker')
    args=parser.parse_args();sdk=args.sdk.resolve();binary=args.binary.resolve();output=args.evidence.resolve()
    if not output.is_relative_to(ROOT/'target/typescript-c-abi'):parser.error('new evidence under target/typescript-c-abi required')
    output.mkdir(parents=True,exist_ok=False)
    project=output/'project';project.mkdir();state=project/'.code-ana';state.mkdir()
    dependency=output/'dependency';dependency.mkdir()
    (dependency/'package.json').write_text(json.dumps({'name':'fixture-dependency','types':'index.d.ts'}))
    (dependency/'index.d.ts').write_text('export declare function external(value: number): number;\n')
    (project/'node_modules').mkdir();(project/'node_modules/fixture-dependency').symlink_to(dependency,target_is_directory=True)
    (project/'a.ts').write_text('import { external } from "fixture-dependency";\nexport function greet(value: number) { return external(value); }\n')
    (project/'b.js').write_text('import { greet } from "./a";\n// 中文😀\ngreet(1);\n')
    (state/'private.ts').write_text('throw Error("indexer state is not a source input");\n')
    config=project/'tsconfig.json';config.write_text(json.dumps({'compilerOptions':{'noLib':True,'allowJs':True,'checkJs':True,'module':'ESNext','moduleResolution':'bundler'},'include':['**/*.ts','**/*.js']}))
    paths=[ROOT/path for path in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]
    paths += [binary,*[path for path in sdk.rglob('*') if path.is_file()],config,project/'a.ts',project/'b.js',dependency/'index.d.ts',dependency/'package.json']
    before=identities(paths)
    report=dict(stage='T3 explicit worker preparation',status='failed',commands=[],inputs=before,T3_complete=False,product_indexing=False,formal_native_acceptance=False,performance_acceptance=False)
    def run(name,languages,extra=(),expected=0):
        generation=state/name;generation.mkdir();result=generation/'native.json'
        argv=[binary,'--sdk',sdk,'--prepare-project',project,'--prepare-config',config,'--output',result,'--scratch',output/(name+'-scratch'),'--unit','typescript/fixture/main','--module','app','--world','typescript/main','--allowed-root',dependency]
        for language in languages:argv += ['--publish-language',language]
        argv += list(extra);argv=list(map(str,argv));log=output/(name+'.log');entry=dict(id=name,argv=argv,log=log.name,expected_exit=expected);report['commands'].append(entry);start=time.monotonic()
        with log.open('wb') as stream:
            process=subprocess.Popen(argv,cwd=output,env=dict(os.environ,PATH=''),stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:process.wait(timeout=45)
            finally:
                baseline.signal_group(process.pid,signal.SIGKILL)
                entry.update(exit_code=process.wait(),seconds=time.monotonic()-start,sha256=sha256(log))
        assert entry['exit_code']==expected,(name,entry,log.read_text())
        if expected:
            assert not result.exists();return None
        value=json.loads(result.read_text());assert value['schema_version']==1 and len(value['contexts'])==1
        assert value['compiler']['library_sha256']==json.loads((sdk/'manifest.json').read_text())['files']['libcodeana_typescript.dylib']
        return value
    try:
        both=run('dual',['javascript','typescript']);context=both['contexts'][0]
        assert set(context['publish_languages'])=={'javascript','typescript'}
        assert sorted(Path(file['path']).name for file in context['files'] if file['role']=='publish')==['a.ts','b.js']
        assert any(file['path']==str(dependency/'index.d.ts') and file['role']=='auxiliary' for file in context['files'])
        assert any(link['path']==str(project/'node_modules/fixture-dependency') for link in context['links'])
        assert not any(str(state) in file['path'] for file in context['files'])
        (state/'generation-added').mkdir()
        repeated=run('repeat',['javascript','typescript']);assert both==repeated,'preparation generation changed native semantic inputs'
        js=run('js-only',['javascript'])
        assert [Path(file['path']).name for file in js['contexts'][0]['files'] if file['role']=='publish']==['b.js']
        assert any(Path(file['path']).name=='a.ts' and file['role']=='auxiliary' for file in js['contexts'][0]['files'])
        run('quota',['typescript'],['--max-work','1'],1)
        run('bad-language',['java'],expected=1)
        after=identities(paths);report['artifact_drift']=[path for path in sorted(before.keys()|after.keys()) if before.get(path)!=after.get(path)]
        assert not report['artifact_drift'];report.update(status='passed',executed_count=len(report['commands']))
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__);raise
    finally:(output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:report[key] for key in ('status','executed_count','artifact_drift')}))
if __name__=='__main__':main()
