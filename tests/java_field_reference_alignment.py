#!/usr/bin/env python3
"""Independent field access/lexical owner/control-flow and full/delta evidence."""
import argparse
import json
import os
import sqlite3
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_declaration_reference_alignment import index_reference
from java_field_inventory_diff import compare
from java_model_tests import Models

SOURCE='''package p; // 原字节😀
class Base { static int count=1; int inherited; }
enum Mode { ONE }
class Use extends Base {
 int value=Base.count; Mode mode=Mode.ONE;
 int sourceField=java.io.Written.flag;
 Runnable callback=()->{value=inherited;};
 { value=Base.count; }
 void write(int next) {
  this.value=next; (value)=inherited; value+=next;
  value++; --this.value;
  System.out.println(Boolean.TRUE);
  if(next>0) value=inherited; else value=Base.count;
  try {value=inherited;} finally {value=Base.count;}
  int[] array=new int[1]; array[value]=next; int n=array.length;
  Class<?> type=Use.class;
 }
}
'''


def main():
    p=argparse.ArgumentParser();p.add_argument('--native',action='store_true');args=p.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('field-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Models();case.setUp()
    if args.native: case.analyze=reference.native_analyze.__get__(case)
    proofs=[];started=time.monotonic()
    try:
        baseline=None
        for name,source in [('baseline',SOURCE),('body',SOURCE.replace('count=1','count=2')),
                            ('declaration',SOURCE.replace('inherited','renamed'))]:
            folder=base/name;folder.mkdir()
            files={'Use.java':source,'java/io/Written.java':
                   'package java.io; public class Written {public static int flag=1;}'}
            context=case.context('app',files)
            facts=case.analyze([context],cache=True)['facts']
            raw=[f for f in facts if f['relation']=='' and f['attributes'].get('field_mutation')]
            case.assertEqual(len(raw),2)
            case.assertTrue(all(f['attributes']['field_access']=='read_write' for f in raw))
            by={f['id']:f for f in facts}
            case.assertTrue(any(f['relation']=='' and by.get(f['target'],{}).get('signature')=='java.lang.Boolean#TRUE'
                                for f in facts))
            case.assertFalse(any(f['relation'] in ('reads','writes') and
                                 by.get(f['target'],{}).get('signature','').startswith(('java.lang.Boolean#','java.lang.System#'))
                                 for f in facts))
            case.assertTrue(any(f['relation']=='reads' and by.get(f['target'],{}).get('signature')=='java.io.Written#flag'
                                for f in facts))
            if baseline is not None: case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),sorted(facts,key=lambda f:f['id']))
            baseline=facts
            database=index_reference(folder,files)
            with sqlite3.connect(':memory:') as product,sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True) as ref:
                product.row_factory=ref.row_factory=sqlite3.Row
                product.execute('CREATE TABLE facts(id,parent,source,kind,relation,body)')
                product.executemany('INSERT INTO facts VALUES(?,?,?,?,?,?)',
                    ((f['id'],f['parent'],f['source'],f['kind'],f['relation'],json.dumps(f)) for f in facts))
                proof=compare(product,ref,folder)
            case.assertGreater(proof['reference'],10)
            (folder/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
            proof.update(name=name,product_sha256=reference.digest(folder/'product.json'),reference_sha256=reference.digest(database))
            (folder/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof);print(name,proof['product'],proof['reference'],proof['equal'],flush=True)
    finally: case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    drift=sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed=sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report=dict(kind='frozen_field_access_and_owner_projection',native=args.native,proofs=proofs,
        inputs=inputs,artifacts=artifacts,changed_inputs=drift,changed_artifacts=changed,
        reference_commit=reference.REFERENCE_COMMIT,reference_sha256=reference.REFERENCE_SHA,
        elapsed_seconds=round(time.monotonic()-started,3),full_semantic_diff_accepted=False)
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not drift and not changed
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
