#!/usr/bin/env python3
"""Independent all-control fixture, including callback boundaries and source line movement."""
import argparse
import json
import os
import sqlite3
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_control_inventory_diff import inventory
from java_declaration_reference_alignment import index_reference
from java_model_tests import Models

SOURCE='''package p; // 原字节😀
interface Action {int apply(int value);}
class Use {
 int value=call(1); {value=call(2);}
 int call(int next){return next;}
 void run(int next) {
  if(next>0) value=call(value); else value=call(next);
  for(int i=0;i<next;i++) {if(i==2) value=call(i);}
  for(int item:new int[]{1,2}) value=call(item);
  while(next-->0) value=call(next);
  do {value=call(next);} while(next-->0);
  try {value=call(next);} catch(RuntimeException e) {value=call(0);}
  finally {value=call(1);}
  synchronized(this) {value=call(next);}
  switch(next) {case 1:value=call(1);break;default:value=call(0);}
  value=next>0?call(value):call(next);
  if(next>0) {Runnable callback=()->{if(value>0)value=call(value);};callback.run();}
  if(next>0) {Action callback=this::call;value=callback.apply(value);}
 }
 void reflection() throws NoSuchMethodException {if(value>0)Use.class.getDeclaredMethod("call",int.class);}
}
'''


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--native',action='store_true');args=parser.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('control-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Models();case.setUp()
    if args.native:case.analyze=reference.native_analyze.__get__(case)
    proofs=[];started=time.monotonic();baseline=None
    try:
        for name,source in [('baseline',SOURCE),('line-growth',SOURCE.replace(' void run','\n\n void run').replace('\n','\r\n')),
                            ('callback-removed',SOURCE.replace('this::call','v -> call(v)'))]:
            directory=base/name;directory.mkdir()
            context=case.context('app',{'Use.java':source})
            facts=case.analyze([context],cache=True)['facts']
            if baseline is not None:
                case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),sorted(facts,key=lambda f:f['id']))
            baseline=facts;database=index_reference(directory,{'Use.java':source})
            reference_sha=reference.digest(database)
            with sqlite3.connect(':memory:') as product,sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True) as ref:
                product.row_factory=ref.row_factory=sqlite3.Row
                product.execute('CREATE TABLE facts(id,parent,source,kind,relation,body)')
                product.executemany('INSERT INTO facts VALUES(?,?,?,?,?,?)',
                    ((f['id'],f['parent'],f['source'],f['kind'],f['relation'],json.dumps(f)) for f in facts))
                proof=inventory(product,ref,directory)
                # Changing only control evidence must fail even when all targets
                # and source spans remain equal.
                mutated=next(f for f in facts if f['kind']=='call_site' and f['attributes'].get('control'))
                corrupt=json.loads(json.dumps(mutated));corrupt['attributes']['control']='incorrect@L999'
                product.execute('UPDATE facts SET body=? WHERE id=?',(json.dumps(corrupt),corrupt['id']))
                rejected=inventory(product,ref,directory)
                case.assertFalse(rejected['projections']['calls']['equal'])
            case.assertEqual(reference.digest(database),reference_sha)
            case.assertGreater(proof['projections']['type_reference']['reference'],0)
            case.assertGreater(proof['projections']['reflection_lookup']['reference'],0)
            (directory/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
            proof.update(name=name,reference_database_sha256=reference_sha,
                product_sha256=reference.digest(directory/'product.json'),control_tamper_rejected=True)
            proof['equal']=all(row['equal'] for row in proof['projections'].values())
            (directory/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof)
            print(name,{key:dict(product=row['product'],reference=row['reference'],equal=row['equal'])
                for key,row in proof['projections'].items()},flush=True)
    finally:case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    report=dict(native=args.native,proofs=proofs,inputs=inputs,artifacts=artifacts,
        changed_inputs=[p for p in inputs.keys()|current.keys() if inputs.get(p)!=current.get(p)],
        changed_artifacts=[p for p in artifacts.keys()|now.keys() if artifacts.get(p)!=now.get(p)],
        elapsed_seconds=round(time.monotonic()-started,3),full_semantic_diff_accepted=False)
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not report['changed_inputs'] and not report['changed_artifacts']
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
