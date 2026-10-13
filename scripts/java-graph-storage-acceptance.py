#!/usr/bin/env python3
"""Index frozen captured sources with the existing runtime; compare Anatomist tables."""
import argparse, hashlib, json, os, pathlib, sqlite3, subprocess, time
ROOT=pathlib.Path(__file__).resolve().parents[1]
TABLES=('nodes','edges','annotations','annotation_meta_relations','semantic_annotations',
        'file_dependencies','symbol_dependencies','analysis_coverage','index_diagnostics','index_providers')

def graph(path,ignore_execution_provenance=False):
    result={}
    with sqlite3.connect('file:'+str(path.resolve())+'?mode=ro',uri=True) as db:
        for table in TABLES:
            cols=[r[1] for r in db.execute('PRAGMA table_info('+table+')')]
            cols=[c for c in cols if c not in ('fact_hash','created_at') and not(c=='id' and table!='nodes')]
            rows=db.execute('SELECT '+','.join(cols)+' FROM '+table+' ORDER BY '+','.join(cols))
            digest=hashlib.sha256();count=0;records=[]
            for row in rows:
                values=[]
                for c,v in zip(cols,row):
                    if isinstance(v,bytes):v={'hex':v.hex()}
                    elif isinstance(v,str) and c in ('metadata','attributes','modifiers','declared_modifiers','implicit_modifiers','codes','code_counts','operations','limitations','sample_details'):
                        try:v=json.loads(v)
                        except ValueError:pass
                    if ignore_execution_provenance and c=='sample_details' and isinstance(v,dict):v.pop('classpath_order_sha256',None)
                    values.append(v)
                data=json.dumps(values,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
                records.append(hashlib.sha256(data).digest());count+=1
            for value in sorted(records):digest.update(value)
            result[table]={'count':count,'sha256':digest.hexdigest()}
        for table,sql in {
          'call_sites':'''SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.begin_line,c.begin_column,c.end_line,c.end_column,c.ordinal,c.context,c.syntax_target,c.receiver_static_type,c.dispatch_kind,c.metadata,c.origin,c.resolution_status,c.producer_id,c.language,c.provider_id FROM call_sites c JOIN call_site_owners o USING(owner_pk) ORDER BY c.stable_hash''',
          'call_site_targets':'''SELECT hex(c.stable_hash),t.target_id,t.external_target_fqn,t.external_target_symbol,t.external_target_language,t.external_target_provider_id,t.resolution_status,t.confidence,t.producer_id FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk ORDER BY c.stable_hash,t.target_id,t.external_target_provider_id,t.external_target_fqn'''
        }.items():
            digest=hashlib.sha256();count=0;records=[]
            for row in db.execute(sql):
                values=list(row)
                if table=='call_sites' and values[12] is not None:
                    values[12]=json.loads(values[12])
                data=json.dumps(values,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode();records.append(hashlib.sha256(data).digest());count+=1
            for value in sorted(records):digest.update(value)
            result[table]={'count':count,'sha256':digest.hexdigest()}
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--backend',choices=('jvm','native'),required=True)
    for name in ('project','evidence','reference'):p.add_argument('--'+name,type=pathlib.Path,required=True)
    a=p.parse_args();a.evidence.mkdir(parents=True,exist_ok=True);home=a.evidence/'home';home.mkdir(exist_ok=True)
    jdk='/Users/stream/.sdkman/candidates/java/8.0.492-zulu'
    (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=4294967296\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(jdk)+'\n')
    cmd=[str(ROOT/'target/debug/codeana-cli'),'--home',str(home.resolve()),'--project',str(a.project.resolve())]
    daemon=None;receipt={'status':'running','backend':a.backend,'native_accepted':False}
    try:
        with (a.evidence/'daemon.log').open('w') as log:
            daemon=subprocess.Popen([str(ROOT/'target/debug/codeana-daemon'),'--home',str(home.resolve())],stdout=log,stderr=log,env=dict(os.environ,CODEANA_JAVA_BACKEND=a.backend))
        until=time.monotonic()+15
        while subprocess.run(cmd+['daemon','status'],capture_output=True,timeout=3).returncode:
            if daemon.poll() is not None or time.monotonic()>until:raise RuntimeError('DAEMON_START_FAILED')
            time.sleep(.05)
        with (a.evidence/'index.stdout').open('w') as out,(a.evidence/'index.stderr').open('w') as err:
            result=subprocess.run(cmd+['index','--full','--deadline-seconds','86400'],stdout=out,stderr=err,timeout=86430)
        terminals=[json.loads(l[9:]) for l in (a.evidence/'index.stdout').read_text().splitlines() if l.startswith('terminal ')]
        if result.returncode or len(terminals)!=1 or terminals[0]['publication']!='published':raise RuntimeError('GRAPH_INDEX_NOT_PUBLISHED: '+str(terminals))
        db=home/'revisions'/terminals[0]['revision']/'facts.db'
        receipt.update(database=str(db),database_bytes=db.stat().st_size,terminal=terminals[0],graph=graph(db),reference_graph=graph(a.reference))
        receipt['differences']=[t for t in receipt['graph'] if receipt['graph'][t]!=receipt['reference_graph'][t]]
        receipt['status']='passed' if not receipt['differences'] else 'different'
    except Exception as e:receipt.update(status='failed',error=str(e));raise
    finally:
        if daemon is not None:
            daemon.terminate()
            try:daemon.wait(timeout=10)
            except subprocess.TimeoutExpired:daemon.kill();daemon.wait()
        (a.evidence/'receipt.json').write_text(json.dumps(receipt,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(receipt,ensure_ascii=False))

if __name__=='__main__':main()
