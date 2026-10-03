#!/usr/bin/env python3
"""Strict override endpoints, original evidence spans, confidence and multiplicity."""
import argparse
import collections
import json
import os
import sqlite3
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_declaration_reference_alignment import index_reference
from java_inventory_canonical import Canonical, verify_source_inputs
from java_lombok_alignment import Lombok

SOURCE = '''// 原字节😀
interface Contract<T> { T getValue(); void setValue(T value); }
@lombok.Getter @lombok.Setter class Bean implements Contract<String> { String value; }
interface Wide { CharSequence getValue(); }
class Explicit implements Wide { public String getValue(){return "body";} }
@lombok.Getter class Parent<T> { T value; }
class Child extends Parent<String> { public String getValue(){return "child";} }
@lombok.Data class Data { int value; }
'''


def compare(facts, database, project):
    with sqlite3.connect(':memory:') as product, sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True) as ref:
        product.row_factory = ref.row_factory = sqlite3.Row
        product.execute('CREATE TABLE facts(kind,source,body)')
        product.executemany('INSERT INTO facts VALUES(?,?,?)', ((f['kind'],f['source'],json.dumps(f)) for f in facts))
        inputs = verify_source_inputs(product,project)
        canonical = Canonical(product,ref,project)
        by = {f['id']:f for f in facts}
        nodes = {r['id']:dict(r) for r in ref.execute('SELECT * FROM nodes')}
        left, right = collections.Counter(), collections.Counter()
        for f in facts:
            if f['relation']!='overrides': continue
            owner, target = by[f['parent']], by[f['target']]
            span = f.get('span') or {}
            left[(f['source'], canonical(owner['signature'],'METHOD',f['source'],'product'),
                canonical(target['signature'],'METHOD',f['source'],'product'),
                span.get('start'),span.get('end'),f['status'])] += 1
        def offset(path,line,column,inclusive=False):
            if not line or not column: return None
            rows = (project/path).read_bytes().decode('utf8').splitlines(keepends=True)
            return sum(len(r.encode('utf8')) for r in rows[:line-1])+len(
                rows[line-1].encode('utf-16-le')[:2*(column-1+inclusive)].decode('utf-16-le').encode('utf8'))
        for r in ref.execute("SELECT * FROM edges WHERE relation='OVERRIDES'"):
            owner = nodes[r['source_id']]
            target = nodes.get(r['target_id'],{})
            right[(r['source_file'],canonical(owner['symbol_id'],'METHOD'),
                canonical(target.get('symbol_id') or r['external_target_symbol'] or r['external_target_fqn'],'METHOD'),
                offset(r['source_file'],r['begin_line'],r['begin_column']),
                offset(r['source_file'],r['end_line'],r['end_column'],True),
                'possible' if r['confidence']=='INFERRED' else 'complete')] += 1
        return dict(project_source_inputs=inputs,identity_aliases=canonical.receipt,**reference.compare(left,right))


def main():
    p=argparse.ArgumentParser();p.add_argument('--native',action='store_true');args=p.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('override-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Lombok();case.setUp()
    if args.native: case.analyze=reference.native_analyze.__get__(case)
    proofs=[];started=time.monotonic()
    try:
        baseline=None
        for name,source in [('baseline',SOURCE),('body',SOURCE.replace('"body"','"changed"')),
                            ('declaration',SOURCE.replace('@lombok.Data class Data','class Data'))]:
            folder=base/name;folder.mkdir()
            files=case.files(source)
            context=case.controls(case.context('app',files,lombok='model'),{'app/lombok.config':reference.CONTROL})
            facts=case.analyze([context],cache=True)['facts']
            if baseline is not None: case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),sorted(facts,key=lambda f:f['id']))
            baseline=facts
            database=index_reference(folder,files)
            proof=compare(facts,database,folder)
            case.assertEqual(proof['reference'],4 if name=='declaration' else 7)
            (folder/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
            proof.update(name=name,product_sha256=reference.digest(folder/'product.json'),reference_sha256=reference.digest(database))
            (folder/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof);print(name,proof['product'],proof['reference'],proof['equal'],flush=True)
    finally: case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    drift=sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed=sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report=dict(kind='frozen_override_source_evidence_projection',native=args.native,proofs=proofs,
        inputs=inputs,artifacts=artifacts,changed_inputs=drift,changed_artifacts=changed,
        reference_commit=reference.REFERENCE_COMMIT,reference_sha256=reference.REFERENCE_SHA,
        elapsed_seconds=round(time.monotonic()-started,3),full_semantic_diff_accepted=False)
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not drift and not changed
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
