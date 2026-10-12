#!/usr/bin/env python3
"""One bounded, warmed real-fact control/observed pair; no acceptance index reruns."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from acceptance_support import sha256


def workload(fixture,output,cycles):
    started=time.monotonic();digest=hashlib.sha256();count=0
    # A fixed, bounded input remains warm for both trials. Include parse,
    # canonical encoding, hashing and SQLite insertion/lookup in each cycle.
    rows=fixture.read_text().splitlines()
    connection=sqlite3.connect(output.parent/'working.db')
    connection.execute('CREATE TABLE facts(id TEXT PRIMARY KEY, body TEXT)')
    for index in range(cycles):
        for raw in rows:
            value=json.loads(raw);body=json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
            digest.update(body.encode());count+=1
            if index==0:connection.execute('INSERT INTO facts VALUES(?,?)',(value['id'],body))
        connection.commit()
        # Read deterministic rows through the same SQLite path in both trials.
        for body, in connection.execute('SELECT body FROM facts ORDER BY id'):
            digest.update(body.encode())
    connection.close()
    result=dict(status='passed',seconds=time.monotonic()-started,records=count,checksum=digest.hexdigest(),fixture_sha256=sha256(fixture))
    output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))


def main():
    p=argparse.ArgumentParser();p.add_argument('--workload',action='store_true')
    p.add_argument('--fixture',type=Path);p.add_argument('--output',type=Path);p.add_argument('--cycles',type=int,default=200)
    p.add_argument('--database',type=Path);p.add_argument('--evidence',type=Path)
    a=p.parse_args()
    if a.workload:
        workload(a.fixture,a.output,a.cycles);return 0
    if not a.database or not a.evidence:p.error('--database and --evidence required')
    if not 1<=a.cycles<=1000:p.error('bounded cycles required')
    a.evidence=a.evidence.resolve();a.evidence.mkdir(parents=True,exist_ok=False)
    # Snapshot only 5,000 real facts, at most 64 MiB; original DB stays read-only.
    fixture=a.evidence/'facts.jsonl';connection=sqlite3.connect(a.database.resolve().as_uri()+'?mode=ro',uri=True);size=0;count=0
    with fixture.open('w') as f:
        for body, in connection.execute('SELECT body FROM facts ORDER BY id LIMIT 5000'):
            size+=len(body.encode())+1
            if size>64<<20:raise ValueError('CALIBRATION_FIXTURE_BUDGET')
            f.write(body+'\n');count+=1
    connection.close()
    if count<1000:raise ValueError('REPRESENTATIVE_FACT_FIXTURE_REQUIRED')
    fixture.read_bytes()  # Explicitly warm only this bounded fixture before the pair.
    before=sha256(fixture);rows=[]
    for observed in (False,True):
        trial=a.evidence/('observed' if observed else 'control');trial.mkdir()
        cmd=[sys.executable,str(Path(__file__).resolve()),'--workload','--fixture',str(fixture),'--output',str(trial/'result.json'),'--cycles',str(a.cycles)]
        if observed:
            spec=trial/'spec.json';spec.write_text(json.dumps(dict(command=cmd,cwd=str(ROOT),env={},backend='native',cache_temperature='warm_application_cache',disk_roots=[str(trial)],timeout_seconds=1800)))
            cmd=[sys.executable,str(ROOT/'scripts/java-performance-monitor-single-v2.py'),'--spec',str(spec),'--evidence',str(trial/'monitor')]
        with (trial/'run.log').open('w') as f:result=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT)
        if result.returncode:raise ValueError('CALIBRATION_TRIAL_FAILED: '+str(trial))
        rows.append(json.loads((trial/'result.json').read_text()))
    if rows[0]['records']!=rows[1]['records'] or rows[0]['checksum']!=rows[1]['checksum'] or sha256(fixture)!=before:
        raise ValueError('CALIBRATION_WORKLOAD_DRIFT')
    overhead=rows[1]['seconds']/rows[0]['seconds']-1
    report=dict(status='passed' if overhead<=.03 else 'failed',observer_overhead_calibrated=overhead<=.03,
        scope='one warmed real-fact CPU/SQLite fixture pair; OS page cache uncontrolled; not a full-project speed claim',
        overhead_fraction=overhead,control=rows[0],observed=rows[1],maximum_overhead_fraction=.03,
        measurement='workload own elapsed time, excludes monitor shutdown polling',full_project_overhead_proven=False,
        inputs={str(Path(__file__).resolve()):sha256(__file__),str(fixture):before,str(ROOT/'scripts/java-performance-monitor-single-v2.py'):sha256(ROOT/'scripts/java-performance-monitor-single-v2.py')})
    (a.evidence/'receipt.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report));return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
