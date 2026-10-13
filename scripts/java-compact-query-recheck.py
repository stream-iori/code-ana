#!/usr/bin/env python3
"""Recheck fixed public queries on the single measured immutable compact graph."""
import argparse
import json
import pathlib
import subprocess
import sys
import time

ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from acceptance_support import sha256


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--measurement',type=pathlib.Path,required=True)
    parser.add_argument('--query-plan',type=pathlib.Path,required=True)
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    args=parser.parse_args()
    measurement=args.measurement.resolve();report=json.loads((measurement/'receipt.json').read_text())
    phase=report['phase'];database=measurement/'home/revisions'/phase['revision']/'facts.db'
    if phase.get('failure')!='PERFORMANCE_QUERY_FAILED: search-code':raise ValueError('EXACT_QUERY_RECHECK_REQUIRED')
    expected=json.loads((measurement/'expected-graph.json').read_text())
    if phase['graph']!=expected['graphs']['native']:raise ValueError('MEASURED_LOGICAL_GRAPH_DRIFT')
    old=measurement/'graph_query-measured.rs';source=ROOT/'crates/storage/src/graph_query.rs'
    if sha256(old)!=report['source_inputs'][str(source)]:raise ValueError('MEASURED_QUERY_SOURCE_DRIFT')
    fixed=old.read_text().replace(' AND n.rowid IN(SELECT rowid FROM node_names WHERE node_names MATCH ?)',
        ' AND n.id IN(SELECT id FROM graph_nodes WHERE node_pk IN(SELECT rowid FROM node_names WHERE node_names MATCH ?))')
    if fixed!=source.read_text():raise ValueError('NON_QUERY_PRODUCTION_CHANGE')
    allowed={str(source),str(ROOT/'tests/java_graph_storage.py')}
    if any(sha256(path)!=digest for path,digest in report['source_inputs'].items() if path not in allowed):
        raise ValueError('OTHER_MEASUREMENT_INPUT_DRIFT')
    args.evidence.mkdir(parents=True,exist_ok=False)
    before=sha256(database)
    binaries=ROOT/'target/release';home=measurement/'home'
    monitor_spec=json.loads((measurement/'monitor-spec.json').read_text())
    project=pathlib.Path(monitor_spec['command'][monitor_spec['command'].index('--project')+1])
    base=[str(binaries/'codeana-cli'),'--home',str(home),'--project',str(project)]
    locator=json.loads(args.query_plan.read_text())['declaration_cases'][0]['locator']
    commands=[('resolve',['resolve',locator['symbol_id'],'--unique']),('structure',['structure',locator['symbol_id']]),
        ('source',['source',locator['source_file']]),('search-code',['search-code',locator['symbol_id']]),
        ('search-text',['search-text',locator['symbol_id'].split('.')[-1],'--path',locator['source_file']]),('overview',['overview'])]
    result=dict(status='running',no_index_rerun=True,database=str(database),database_before_sha256=before,
        production_delta=['crates/storage/src/graph_query.rs'],queries=[],
        binaries={str(binaries/name):sha256(binaries/name) for name in ('codeana-cli','codeana-daemon','codeana-java-worker')})
    daemon=None
    try:
        with (args.evidence/'daemon.log').open('wb') as log:
            daemon=subprocess.Popen([str(binaries/'codeana-daemon'),'--home',str(home)],stdout=log,stderr=log)
        deadline=time.monotonic()+15
        while subprocess.run(base+['daemon','status'],capture_output=True,timeout=3).returncode:
            if daemon.poll() is not None or time.monotonic()>deadline:raise ValueError('QUERY_DAEMON_START_FAILED')
            time.sleep(.05)
        for name,argv in commands:
            if name!='overview':argv+=['--module',locator['module'],'--scope',locator['scope']]
            started=time.monotonic()
            query=subprocess.run(base+argv+['--revision',phase['revision'],'--max-records','10000','--max-output-bytes','8388608'],capture_output=True,text=True,timeout=120)
            (args.evidence/(name+'.stdout')).write_text(query.stdout)
            (args.evidence/(name+'.stderr')).write_text(query.stderr)
            terminals=[json.loads(line[9:]) for line in query.stdout.splitlines() if line.startswith('terminal ')]
            records=[json.loads(line[7:]) for line in query.stdout.splitlines() if line.startswith('record ')]
            if query.returncode or not records or len(terminals)!=1 or terminals[0]['revision']!=phase['revision'] or terminals[0]['execution']!='completed':
                raise ValueError('QUERY_RECHECK_FAILED: '+name)
            result['queries'].append(dict(name=name,seconds=time.monotonic()-started,records=len(records)))
        result['database_after_sha256']=sha256(database)
        if before!=result['database_after_sha256']:raise ValueError('QUERY_MUTATED_GRAPH')
        result['status']='passed'
    except BaseException as error:result.update(status='failed',error=str(error))
    finally:
        if daemon is not None and daemon.poll() is None:daemon.terminate();daemon.wait(timeout=30)
        (args.evidence/'receipt.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    return 0 if result['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
