#!/usr/bin/env python3
"""One fixed JS/TS fixture, empty application state, one index and one query."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import struct
import subprocess
import threading
import time
import unittest
from acceptance_support import identities,sha256
from graph_product_support import canonical_graph
ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();package=args.package.resolve();output=args.evidence.resolve()
    # A repository-wide sentinel prevents accidental retries or picking a best run.
    sentinel=ROOT/'target/typescript-c-abi/performance-once.json'
    with sentinel.open('x') as stream:json.dump({'evidence':str(output),'started_utc':time.time()},stream)
    output.mkdir(parents=True,exist_ok=False);project=output/'project';project.mkdir();home=output/'home';home.mkdir(mode=0o700)
    subprocess.run(['git','init','--quiet'],cwd=project,check=True)
    (project/'.code-ana').mkdir()
    (project/'a.ts').write_text(''.join(f'/** 固定库存函数 {i} */\nexport function stock_{i}(value: number): number {{ return value + {i}; }}\n' for i in range(500)))
    (project/'b.js').write_text('import * as stock from "./a";\nexport function exercise() {\n'+''.join(f' stock.stock_{i}(1); stock.stock_{i}(2);\n' for i in range(500))+'}\nexercise();\n')
    (project/'tsconfig.json').write_text(json.dumps({'compilerOptions':{'allowJs':True,'checkJs':True,'module':'ESNext','moduleResolution':'bundler','types':[]},'include':['a.ts','b.js']}))
    (project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[resources.index]\nworker_memory_bytes=1073741824\n[resources.index.analysis]\nmax_work=10000000\nmax_nodes=1000000\nmax_payload_bytes=536870912\nmax_depth=256\n[languages.javascript]\nenabled=true\n[languages.typescript]\nenabled=true\n[inputs]\n[[inputs.entries]]\nid="fixture"\nlanguage="typescript"\nroot="."\n[[modules]]\nid="app"\nlanguage="typescript"\nroot="."\n')
    (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=1073741824\nmax_total_worker_memory_bytes=2147483648\n')
    report={'status':'failed','rounds':1,'fixture':'500 typed functions, 1000 JS-to-TS calls plus exercise entry',
            'empty_application_state':True,'inputs':identities(p for p in project.rglob('*') if p.is_file() and '.git' not in p.parts),
            'budget':{'worker_memory_bytes':1073741824,'max_work':10000000,'max_nodes':1000000,'max_payload_bytes':536870912,'max_depth':256},
            'package_files':identities(p for p in package.rglob('*') if p.is_file()),'rss_method':'20ms ps snapshots; sum of CLI/daemon descendant RSS at each same snapshot; shared mappings may be counted by multiple processes',
            'formal_native_acceptance':False,'stable_capacity_claim':False}
    environment={k:v for k,v in os.environ.items() if not k.startswith(('CODEANA_','NODE_'))};environment['PATH']=''
    cli=package/'bin/codeana-cli';roots=set();samples=[];stop=threading.Event();archive=None
    def sample():
        nonlocal archive
        while not stop.is_set():
            try:
                endpoint=home/'endpoint.json'
                if endpoint.exists():roots.add(json.loads(endpoint.read_text())['pid'])
                rows=subprocess.check_output(['/bin/ps','-axo','pid=,ppid=,rss=,comm='],text=True)
                values=[line.strip().split(None,3) for line in rows.splitlines()]
                selected=set(roots);changed=True
                while changed:
                    changed=False
                    for row in values:
                        if len(row)==4 and int(row[1]) in selected and int(row[0]) not in selected:
                            selected.add(int(row[0]));changed=True
                members=[{'pid':int(r[0]),'ppid':int(r[1]),'rss_bytes':int(r[2])*1024,'command':r[3]} for r in values if len(r)==4 and int(r[0]) in selected]
                samples.append({'seconds':time.monotonic(),'rss_bytes':sum(r['rss_bytes'] for r in members),'processes':members})
                if archive is None and list((home/'candidates').glob('*/facts.db')):
                    for path in home.glob('analysis-index-*/u*/engine/graph.rows'):
                        try:
                            data=path.read_bytes()
                            if len(data)<4 or data[:4]!=b'GRA1':continue
                            offset=4;count=0
                            while offset<len(data):
                                size=struct.unpack_from('>I',data,offset)[0];offset+=4+size;count+=1
                            if offset!=len(data):continue
                            saved=output/'graph.rows';saved.write_bytes(data)
                            archive={'bytes':len(data),'rows':count,'sha256':sha256(saved),'capture':'after candidate Begin, after Go sealed its archive'}
                            break
                        except (OSError,ValueError,struct.error):pass
            except (OSError,ValueError,KeyError):pass
            stop.wait(.02)
    monitor=threading.Thread(target=sample,daemon=True);monitor.start()
    def run(name,arguments):
        command=[str(cli),'--home',str(home),'--project',str(project),*arguments,'--format','json','--detail']
        started=time.monotonic();process=subprocess.Popen(command,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE);roots.add(process.pid)
        try:
            stdout,stderr=process.communicate(timeout=180)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout,stderr=process.communicate()
            (output/(name+'.stdout')).write_bytes(stdout)
            (output/(name+'.stderr')).write_bytes(stderr)
            raise
        report[name+'_seconds']=time.monotonic()-started
        (output/(name+'.stdout')).write_bytes(stdout);(output/(name+'.stderr')).write_bytes(stderr)
        if process.returncode:raise AssertionError(f'{name}: {process.returncode}: '+stdout.decode()+stderr.decode())
        return json.loads(stdout),time.monotonic()-started
    try:
        indexed,index_seconds=run('index',['index','--prepare','--full'])
        revision=indexed['result']['revision'];assert indexed['result']['publication']=='published'
        queried,query_seconds=run('query',['calls','exercise','--direction','out','--max-records','2000','--max-output-bytes','10485760'])
        assert queried['records']
        database=home/'revisions'/revision/'facts.db'
        with sqlite3.connect(database) as db:
            calls=db.execute('SELECT count(*) FROM call_sites').fetchone()[0]
            assert calls==1001,calls
        check=unittest.TestCase();check.home=home;canonical=canonical_graph(check,revision)
        report.update(status='passed',index_seconds=index_seconds,query_seconds=query_seconds,revision=revision,
                      query_records=len(queried['records']),call_sites=calls,graph_database_bytes=database.stat().st_size,
                      semantic_sha256=hashlib.sha256(json.dumps(canonical,sort_keys=True,ensure_ascii=False).encode()).hexdigest())
    except Exception as error:
        report.update(failure=str(error),failure_type=type(error).__name__);raise
    finally:
        stop.set();monitor.join(timeout=5)
        stopped=subprocess.run([str(cli),'--home',str(home),'daemon','stop'],env=environment,capture_output=True,timeout=20)
        (output/'daemon-stop.log').write_bytes(stopped.stdout+stopped.stderr)
        (output/'rss-samples.json').write_text(json.dumps(samples,indent=2)+'\n')
        report['same_snapshot_peak']=max(samples,key=lambda s:s['rss_bytes']) if samples else None
        report['gra1']=archive
        report['performance_complete']=report['status']=='passed' and archive is not None
        (output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('status','performance_complete','index_seconds','query_seconds','graph_database_bytes')}))

if __name__=='__main__':main()
