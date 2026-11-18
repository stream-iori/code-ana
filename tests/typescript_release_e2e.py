#!/usr/bin/env python3
"""Actual packaged JS/TS runtime with hidden development paths and empty PATH."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest
from acceptance_support import identities,sha256
from graph_product_support import canonical_graph

ROOT=Path(__file__).resolve().parents[1]
NAMES=('codeana-cli','codeana-daemon','codeana-java-worker','codeana-rust-worker','codeana-python-worker','codeana-typescript-worker')


@contextlib.contextmanager
def hide_development(output,report):
    prefixes=[ROOT/'target/debug',ROOT/'target/release',ROOT/'lang-python-adaptor/target/debug',ROOT/'lang-python-adaptor/target/release']
    rows=subprocess.check_output(['ps','-axo','pid=,comm='],text=True)
    active=[line for line in rows.splitlines() if len(parts:=line.strip().split(None,1))==2
            and any(parts[1].startswith(str(prefix)+'/codeana-') for prefix in prefixes)]
    if active:raise ValueError('STOP_RUNTIME_BEFORE_RELEASE_TEST: '+repr(active))
    saved=output/'development-paths';saved.mkdir()
    moves=[];before={}
    try:
        for index,prefix in enumerate(prefixes):
            for name in NAMES:
                path=prefix/name
                if path.is_file():
                    before[str(path)]=sha256(path)
                    target=saved/f'{index}-{name}';path.rename(target);moves.append((path,target))
        source=ROOT/'lang-typescript-adaptor'
        if source.exists():
            target=saved/'lang-typescript-adaptor';source.rename(target);moves.append((source,target))
        report['hidden_paths']=[str(path) for path,_ in moves]
        yield
    finally:
        errors=[]
        for path,target in reversed(moves):
            if path.exists():errors.append('path recreated: '+str(path));continue
            target.rename(path)
        report['development_restored']=not errors and all(sha256(Path(p))==digest for p,digest in before.items())
        if errors:raise RuntimeError('RELEASE_RESTORE_FAILED: '+repr(errors))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();package=args.package.resolve();output=args.evidence.resolve();output.mkdir(parents=True,exist_ok=False)
    manifest=json.loads((package/'manifest.json').read_text())
    if manifest['package']!='codeana-typescript':raise ValueError('TYPESCRIPT_PACKAGE_REQUIRED')
    declared={}
    for line in (package/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1)
        if name in declared or not (package/name).resolve().is_relative_to(package):raise ValueError('PACKAGE_CHECKSUM_PATH')
        declared[name]=digest
    actual={p.relative_to(package).as_posix():sha256(p) for p in package.rglob('*') if p.is_file() and p.name!='SHA256SUMS'}
    if actual!=declared:raise ValueError('PACKAGE_CHECKSUMS_INCOMPLETE_OR_CHANGED')
    current=package/'lib/typescript/current'
    if not current.is_symlink() or current.readlink()!=Path('versions')/manifest['typescript']['version']:
        raise ValueError('PACKAGE_SDK_SELECTION')
    if not current.resolve().is_relative_to(package):raise ValueError('PACKAGE_SDK_OUTSIDE')
    before=identities(p for p in package.rglob('*') if p.is_file())
    report={'status':'failed','package':str(package),'package_files':before,'commands':[],'formal_native_acceptance':False,'performance_acceptance':False}
    environment={k:v for k,v in os.environ.items() if not k.startswith(('CODEANA_','NODE_'))}
    environment['PATH']=''
    daemon_stops=[]
    started=time.monotonic()
    try:
        with tempfile.TemporaryDirectory(prefix='codeana-ts-release-') as temporary:
            workspace=Path(temporary);project=workspace/'project';home=workspace/'home'
            project.mkdir();home.mkdir(mode=0o700);(project/'.code-ana').mkdir()
            (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=1073741824\nmax_total_worker_memory_bytes=2147483648\n')
            a=project/'a.ts';b=project/'b.js'
            a.write_text('/** 发布库存文档 中文😀 */\nexport function sale(value: number) { return value + 1; }\n')
            b.write_text('/** 发布调用说明 */\nimport {sale} from "./a";\nexport function sell() { sale(1); sale(2); }\nsell();\n')
            (project/'tsconfig.json').write_text(json.dumps({'compilerOptions':{'noLib':True,'allowJs':True,'checkJs':True,'module':'ESNext','moduleResolution':'bundler'},'include':['*.ts','*.js']}))
            (project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[languages.javascript]\nenabled=true\n[languages.typescript]\nenabled=true\n[inputs]\n[[inputs.entries]]\nid="script"\nlanguage="typescript"\nroot="."\n[[modules]]\nid="app"\nlanguage="typescript"\nroot="."\n')
            check=unittest.TestCase();check.home=home
            def run(name,*args,code=0,binary=None,app_home=None):
                binary=binary or package/'bin/codeana-cli';app_home=app_home or home
                argv=[str(binary),'--home',str(app_home),'--project',str(project),*map(str,args),'--format','json','--detail']
                result=subprocess.run(argv,capture_output=True,text=True,env=environment,timeout=90)
                log=output/(name+'.stdout');log.write_text(result.stdout)
                (output/(name+'.stderr')).write_text(result.stderr)
                entry={'id':name,'argv':argv,'exit_code':result.returncode,'stdout_sha256':sha256(log)};report['commands'].append(entry)
                if result.returncode!=code:raise AssertionError(str(entry)+'\n'+result.stdout+'\n'+result.stderr)
                response=json.loads(result.stdout)
                records=response.get('records',[e['record'] for e in response.get('events',[]) if 'record' in e])
                terminal=dict(response['result'],cursor=response['delivery']['cursor'])
                return records,terminal
            def stop(binary,app_home):
                result=subprocess.run([str(binary),'--home',str(app_home),'daemon','stop'],env=environment,capture_output=True,timeout=20)
                daemon_stops.append({'binary':str(binary),'exit_code':result.returncode})
            with hide_development(output,report):
                cli=package/'bin/codeana-cli'
                try:
                    first=run('prepare-full','index','--prepare','--full')[1]['revision']
                    initial=canonical_graph(check,first)
                    target=next(d for d in run('search','search-code','sale')[0] if d['name']=='sale' and d['source']=='a.ts')
                    js_target=next(d for d in run('search-js','search-code','sell')[0] if d['name']=='sell' and d['source']=='b.js')
                    assert run('source','source',target['id'],'--revision',first)[0]
                    assert run('incoming-calls','calls',target['id'],'--direction','in')[0]
                    assert run('outgoing-calls','calls','sell','--direction','out')[0]
                    assert run('call-sites','call-sites','sell')[0]
                    with sqlite3.connect(home/'revisions'/first/'facts.db') as db:
                        assert db.execute('SELECT count(*) FROM call_site_targets WHERE target_id=?',(target['id'],)).fetchone()[0]==2
                        assert {r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')}=={'javascript','typescript'}
                    page=run('comment-page','search-comments','发布','--max-records','1')
                    assert len(page[0])==1 and page[1]['cursor']
                    repeated=run('prepare-reuse','index','--prepare')[1]['revision']
                    assert canonical_graph(check,repeated)==initial
                    run('snapshot-before','snapshot','before','--kind','analysis')
                    retained=run('head-before-edit','overview')[1]['revision']
                    a.write_text('/** 更新发布库存 */\nexport function sale(value: number) { return value + 2; }\n')
                    stale=run('stale-input','index',code=1)[1]
                    assert 'TYPESCRIPT_INPUT_STALE' in stale['message']
                    assert run('head-after-stale','overview')[1]['revision']==retained
                    changed=run('changed-full','index','--prepare')[1]['revision']
                    run('snapshot-after','snapshot','after','--kind','analysis')
                    assert run('diff','diff','--base','before','--target','after')[0]
                    assert run('frozen-comments','search-comments','--cursor',page[1]['cursor'],'--max-records','100')[0]
                    assert canonical_graph(check,first)==initial
                    full=run('forced-full','index','--full')[1]['revision']
                    assert canonical_graph(check,full)==canonical_graph(check,changed)
                    run('strict-reject','index','--full','--strict',code=3)
                    assert run('head-after-strict','overview')[1]['revision']==full
                    b.unlink()
                    deleted=run('deleted-js','index','--prepare')[1]['revision']
                    with sqlite3.connect(home/'revisions'/deleted/'facts.db') as db:
                        assert db.execute('SELECT count(*) FROM nodes WHERE source_file="b.js"').fetchone()[0]==0
                    assert run('historical-js-source','source',js_target['id'],'--revision',first)[0]
                    assert run('doctor','doctor')[0][0]['typescript_worker']['binary_present']
                    stop(cli,home)
                    for mode in ('missing-worker','missing-sdk','digest','analysis','platform'):
                        runtime=output/('reject-'+mode);(runtime/'bin').mkdir(parents=True)
                        for name in ('codeana-cli','codeana-daemon','codeana-typescript-worker'):
                            if mode=='missing-worker' and name.endswith('typescript-worker'):continue
                            shutil.copy2(package/'bin'/name,runtime/'bin'/name)
                        if mode!='missing-sdk':
                            sdk=runtime/'lib/typescript/current';shutil.copytree(current.resolve(),sdk)
                            path=sdk/'manifest.json';data=json.loads(path.read_text())
                            if mode=='digest':
                                with (sdk/'libcodeana_typescript.dylib').open('ab') as stream:stream.write(b'broken')
                            if mode=='analysis':data['backend']['analysis_request']=1
                            if mode=='platform':data.update(os='Linux',arch='x86_64')
                            path.write_text(json.dumps(data))
                        damaged=runtime/'bin/codeana-cli'
                        try:
                            run('reject-'+mode,'index','--full',code=1,binary=damaged)
                            assert run('retained-'+mode,'overview',binary=damaged)[1]['revision']==deleted
                        finally:stop(damaged,home)
                    run('recovery','index','--full')
                    assert run('recovered-query','search-code','sale')[0]
                finally:stop(cli,home)
            report['artifact_drift']=[p for p,d in before.items() if sha256(Path(p))!=d]
            assert not report['artifact_drift'] and report['development_restored']
            report.update(status='passed',empty_path=True,sdk_from_package=True,executed_count=len(report['commands']),seconds=time.monotonic()-started)
    except Exception as error:
        report.update(failure=str(error),failure_type=type(error).__name__);raise
    finally:
        report['daemon_stops']=daemon_stops
        (output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('status','executed_count','artifact_drift','development_restored')}))

if __name__=='__main__':main()
