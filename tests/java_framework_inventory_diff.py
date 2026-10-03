#!/usr/bin/env python3
"""Strict published annotation Bean/DI projection; XML is a separate required domain."""
import argparse
import collections
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_framework_reference_alignment import product_projection, reference_projection
from java_inventory_canonical import verify_source_inputs
from java_functional_reference_alignment import compare


def select_facts(db):
    facts={}
    for row in db.execute("SELECT id,body FROM facts WHERE relation='java.spring.injection' OR "
            "(relation='java.bean' AND json_type(body,'$.attributes.annotation_evidence')='object')"):
        facts[row['id']]=json.loads(row['body'])
    pending={f['parent'] for f in facts.values() if f['parent']}
    while pending:
        identity=pending.pop()
        if identity in facts:continue
        row=db.execute('SELECT body FROM facts WHERE id=?',(identity,)).fetchone()
        if row is None:raise ValueError('FRAMEWORK_OWNER_MISSING: '+identity)
        fact=json.loads(row['body']);facts[identity]=fact
        if fact['parent']:pending.add(fact['parent'])
    return list(facts.values())


def counter(values):
    return collections.Counter(json.dumps(value,ensure_ascii=False,sort_keys=True) for value in values)


def main():
    parser=argparse.ArgumentParser()
    for name in ('product','reference','project','output'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args();before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as db:
        revision=db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('PUBLISHED_REVISION_REQUIRED')
        sources=verify_source_inputs(db,args.project)
        left=product_projection(select_facts(db));right=reference_projection(args.reference,annotation_only=True)
        comparisons={kind:compare(counter(left[kind]),counter(right[kind])) for kind in ('beans','injections')}
        report=dict(kind='published_annotation_beans_and_injection_constraints',revision=revision[0],
            database_sha256=before,project_source_inputs=sources,comparisons=comparisons,
            equal=all(value['equal'] for value in comparisons.values()),
            full_semantic_diff_accepted=False,whole_framework_graph_accepted=False)
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    report['changed_databases']=[]
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({kind:{key:value[key] for key in ('product','reference','equal')} for kind,value in comparisons.items()})
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
