#!/usr/bin/env python3
"""Strict field reads/writes: endpoints, lexical owners, byte spans and control context.

Read/write differences remain failures, including the reference's ++/-- behavior.
No synthetic class-literal field, local-variable access or runtime inference is added.
"""
import argparse
import collections
import functools
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_inventory_canonical import Canonical, verify_source_inputs, compiler_lines


def compare(product, reference, project):
    canonical=Canonical(product,reference,project)
    nodes={r['id']:dict(r) for r in reference.execute(
        'SELECT id,symbol_id,kind,source_file,begin_line,begin_column,end_line,end_column FROM nodes')}
    declarations={}
    for r in product.execute("SELECT id,parent,source,json_extract(body,'$.signature') AS signature,"
        "json_extract(body,'$.attributes.element_kind') AS kind,json_extract(body,'$.span.start') AS start,"
        "json_extract(body,'$.span.end') AS end FROM facts WHERE kind IN ('declaration','external_symbol')"):
        declarations[r['id']]=dict(r)
    parents={}
    for r in reference.execute("SELECT source_id,target_id FROM edges WHERE relation='CONTAINS'"):
        if r['target_id'] in parents and parents[r['target_id']]!=r['source_id']:
            raise ValueError('AMBIGUOUS_LEXICAL_PARENT')
        parents[r['target_id']]=r['source_id']
    @functools.lru_cache(maxsize=128)
    def lines(path): return compiler_lines((project/path).read_bytes().decode('utf8'))
    def offset(path,line,column,end=False):
        if not line or not column: return None
        rows=lines(path)
        return sum(len(r.encode('utf8')) for r in rows[:line-1])+len(
            rows[line-1].encode('utf-16-le')[:2*(column-1+end)].decode('utf-16-le').encode('utf8'))
    @functools.lru_cache(maxsize=None)
    def owner(identity, side):
        if side=='product':
            r=declarations.get(identity)
            if r is None: raise ValueError('FIELD_OWNER_MISSING: '+identity)
            if r['kind'] in ('LAMBDA','METHOD_REF','INITIALIZER'):
                return (r['kind'],r['source'],r['start'],r['end'],owner(r['parent'],side))
            return canonical(r['signature'],r['kind'],r['source'],side)
        r=nodes.get(identity)
        if r is None: raise ValueError('REFERENCE_FIELD_OWNER_MISSING: '+identity)
        if r['kind'] in ('LAMBDA','METHOD_REF','SOURCE_REGION'):
            return ('INITIALIZER' if r['kind']=='SOURCE_REGION' else r['kind'],r['source_file'],
                offset(r['source_file'],r['begin_line'],r['begin_column']),
                offset(r['source_file'],r['end_line'],r['end_column'],True),owner(parents[identity],side))
        return canonical(r['symbol_id'],r['kind'])
    left,right=collections.Counter(),collections.Counter()
    for r in product.execute("SELECT body FROM facts WHERE kind='reference' AND relation IN ('reads','writes')"):
        f=json.loads(r['body']);target=declarations[f['target']]
        if target['kind'] not in ('FIELD','ENUM_CONSTANT'): continue
        span=f.get('span') or {}
        left[(f['source'],f['relation'],owner(f['parent'],'product'),
            canonical(target['signature'],target['kind'],f['source'],'product'),
            span.get('start'),span.get('end'),f['attributes'].get('control') or None,f['status'])]+=1
    for r in reference.execute("SELECT * FROM edges WHERE relation IN ('READS','WRITES')"):
        target=nodes.get(r['target_id'],{})
        signature=target.get('symbol_id') or r['external_target_symbol'] or r['external_target_fqn']
        if not signature: raise ValueError('REFERENCE_FIELD_TARGET_MISSING')
        right[(r['source_file'],r['relation'].lower(),owner(r['source_id'],'reference'),
            canonical(signature,target.get('kind')),offset(r['source_file'],r['begin_line'],r['begin_column']),
            offset(r['source_file'],r['end_line'],r['end_column'],True),r['context'],
            'possible' if r['confidence']=='INFERRED' else 'complete')]+=1
    rows=lambda values: [dict(key=k,count=v) for k,v in sorted(values.items(),key=str)[:100]]
    return dict(product=left.total(),reference=right.total(),equal=left==right,
        missing=(right-left).total(),extra=(left-right).total(),missing_sample=rows(right-left),extra_sample=rows(left-right),
        identity_aliases=canonical.receipt)


def main():
    p=argparse.ArgumentParser()
    for key in ('product','reference','project','output'): p.add_argument('--'+key,type=pathlib.Path,required=True)
    args=p.parse_args();before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as product,connection(args.reference) as reference:
        revision=product.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision: raise ValueError('PUBLISHED_REVISION_REQUIRED')
        report=dict(kind='published_field_access_and_owner_projection',revision=revision[0],database_sha256=before,
            project_source_inputs=verify_source_inputs(product,args.project),**compare(product,reference,args.project),
            full_semantic_diff_accepted=False)
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    report['changed_databases']=[]
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({k:report[k] for k in ('product','reference','missing','extra','equal')})
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
