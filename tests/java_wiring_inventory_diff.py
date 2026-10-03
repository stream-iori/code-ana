#!/usr/bin/env python3
"""Published static DI WIRES, with complete candidate metadata and verified source aliases."""
import argparse
import collections
import json
import pathlib

from java_declaration_inventory_diff import connection,digest
from java_functional_reference_alignment import compare
from java_inventory_canonical import Canonical,verify_source_inputs


def select_facts(db):
    facts={r['id']:json.loads(r['body']) for r in db.execute(
        "SELECT id,body FROM facts WHERE relation='wires_possible' "
        "AND json_extract(body,'$.attributes.via')='injection'")}
    pending=set()
    for f in facts.values():
        pending.update([f['parent'],f['target'],f['attributes']['source'],f['attributes']['injection_evidence']])
        pending.update(f['attributes']['candidates'])
    while pending:
        identity=pending.pop()
        if identity in facts:continue
        row=db.execute('SELECT body FROM facts WHERE id=?',(identity,)).fetchone()
        if row is None:raise ValueError('WIRING_EVIDENCE_ENDPOINT_MISSING: '+identity)
        fact=json.loads(row['body']);facts[identity]=fact
    return list(facts.values())


def product_projection(facts,canonical):
    by={f['id']:f for f in facts};rows=[]
    for f in facts:
        if f['relation']!='wires_possible' or f['attributes'].get('via')!='injection':continue
        a=f['attributes'];evidence=by[a['injection_evidence']]
        if evidence['relation']!='injects_possible' or (f['source'],f['span'])!=(evidence['source'],evidence['span']):
            raise ValueError('WIRING_INJECTION_EVIDENCE_DRIFT')
        if f['status']!='possible' or f['evidence_complete'] or f['declaration_resolved'] or a['runtime_confirmed']:
            raise ValueError('WIRING_RUNTIME_SELECTION_FABRICATED')
        def identity(value):
            target=by[value]
            path=target['source'] or target['attributes'].get('source_origin',{}).get('path',f['source'])
            return canonical(target['signature'],target['attributes'].get('element_kind'),path,'product')
        rows.append(dict(file=f['source'],owner=identity(f['parent']),target=identity(f['target']),
            confidence=a['confidence'].upper(),via=a['via'],interface=identity(a['source']),
            candidates=sorted(identity(candidate) for candidate in a['candidates'])))
    return rows


def reference_projection(db,canonical):
    rows=[]
    for edge in db.execute("SELECT * FROM edges WHERE relation='WIRES' AND producer_id='derived-wiring'"):
        metadata=json.loads(edge['metadata'])
        def identity(value):
            node=db.execute('SELECT symbol_id,kind FROM nodes WHERE id=?',(value,)).fetchone()
            if node is None:raise ValueError('REFERENCE_WIRING_ENDPOINT_MISSING: '+value)
            return canonical(node['symbol_id'],node['kind'],edge['source_file'],'reference')
        rows.append(dict(file=edge['source_file'],owner=identity(edge['source_id']),target=identity(edge['target_id']),
            confidence=edge['confidence'],via=metadata['via'],interface=identity(metadata['source']),
            candidates=sorted(identity(candidate) for candidate in metadata['candidates'])))
    return rows


def main():
    parser=argparse.ArgumentParser()
    for name in ['product','reference','project','output']:parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args();before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as db,connection(args.reference) as ref:
        revision=db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('PUBLISHED_REVISION_REQUIRED')
        inputs=verify_source_inputs(db,args.project);canonical=Canonical(db,ref,args.project)
        left=product_projection(select_facts(db),canonical);right=reference_projection(ref,canonical)
        counter=lambda values:collections.Counter((json.dumps(value,ensure_ascii=False,sort_keys=True),) for value in values)
        report=dict(kind='published_static_di_wiring_candidates',revision=revision[0],database_sha256=before,
            project_source_inputs=inputs,aliases=canonical.receipt,**compare(counter(left),counter(right)),
            whole_framework_graph_accepted=False,full_semantic_diff_accepted=False)
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):raise ValueError('COMPARISON_DATABASE_DRIFT')
    report['changed_databases']=[];args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(dict(product=report['product'],reference=report['reference'],equal=report['equal'],
               missing=sum(row['count'] for row in report['missing']),
               extra=sum(row['count'] for row in report['extra'])))
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
