#!/usr/bin/env python3
"""Read-only source annotation owner/path/span/name and written-value projection.

Additional local/type-use/generated facts remain visible differences. This
projection does not certify the annotation meta graph or the complete model.
"""
import argparse
import collections
import functools
import json
import pathlib
import sqlite3

from java_declaration_inventory_diff import connection, digest
from java_inventory_canonical import Canonical, verify_source_inputs, compiler_lines


def meta_projection(facts, reference):
    """The reference has a UNIQUE metadata-edge identity; discoveries remain separate facts."""
    left, right = set(), set()
    discoveries = 0
    for f in facts:
        if f['relation']!='java.annotation.meta': continue
        a=f['attributes'];origin=a.get('source_origin',{'path':f['source']});discoveries+=1
        left.add((origin['path'],a.get('annotation_fqn',a.get('annotation')).replace('$','.'),
                  a.get('meta_annotation_fqn',a.get('meta_annotation')).replace('$','.'),a['raw_name'].replace('$','.'),a.get('resolution_status',a.get('resolution'))))
    for r in reference.execute('SELECT * FROM annotation_meta_relations'):
        key=(r['source_file'],r['annotation_fqn'].replace('$','.'),
             (r['meta_annotation_fqn'] or '').replace('$','.'),r['raw_name'].replace('$','.'),r['resolution_status'])
        if key in right: raise ValueError('REFERENCE_META_IDENTITY_DUPLICATE')
        right.add(key)
    rows=lambda values: [list(k) for k in sorted(values,key=str)[:100]]
    return dict(discovery_facts=discoveries,product=len(left),reference=len(right),equal=left==right,
        identity='reference_unique_metadata_edge_without_discovery_owner',
        missing=len(right-left),extra=len(left-right),missing_sample=rows(right-left),extra_sample=rows(left-right))


def projection(facts, declarations, product, reference, project):
    canonical = Canonical(product, reference, project)
    left, right = collections.Counter(), collections.Counter()
    incomplete = []
    for f in facts:
        if f['relation'] != 'java.annotation':
            continue
        a = f['attributes']
        owner = declarations.get(f['parent'])
        if owner is None:
            raise ValueError('ANNOTATION_OWNER_MISSING: '+f['id'])
        kind = owner['attributes'].get('element_kind')
        target_path = None
        values = a.get('values',a.get('written_values'))
        if values is None or ('values' not in a and a.get('written_values_complete') is not True):
            incomplete.append(f['id'])
        values = dict(values or {})
        if kind == 'PARAMETER' and 'target_kind' not in a:
            parent = declarations[owner['parent']]
            names = parent['attributes'].get('parameter_names', [])
            if names.count(owner['name']) != 1:
                raise ValueError('ANNOTATION_PARAMETER_IDENTITY_AMBIGUOUS')
            index = names.index(owner['name'])
            values.update(_param=index, _name=owner['name'])
            target_path = 'parameter['+str(index)+']'
            owner = parent
            kind = owner['attributes']['element_kind']
            target_kind = 'callable'
        else:
            target_kind = 'type' if kind in ('CLASS','INTERFACE','ENUM','ANNOTATION_TYPE') else 'callable' if kind in ('METHOD','CONSTRUCTOR') else 'value'
        if 'target_kind' in a:
            target_kind=a['target_kind'];target_path=a['target_path']
        span = f.get('span') or {}
        key = (f['source'], canonical(owner['signature'],kind,f['source'],'product'),target_kind,target_path,
               a.get('name',a.get('resolved_type','')).replace('$','.'),a.get('raw_name',a.get('written_name',a.get('source_name'))),
               span.get('start'),span.get('end'),json.dumps(values,sort_keys=True,ensure_ascii=False))
        left[key] += 1

    @functools.lru_cache(maxsize=128)
    def lines(path):
        return compiler_lines((project/path).read_bytes().decode('utf8'))

    def offset(path,line,column,end=False):
        if not line or not column:
            return None
        rows = lines(path)
        return sum(len(s.encode('utf8')) for s in rows[:line-1])+len(
            rows[line-1].encode('utf-16-le')[:2*(column-1+end)].decode('utf-16-le').encode('utf8'))

    for r in reference.execute('SELECT n.symbol_id,n.kind,a.* FROM annotations a JOIN nodes n ON n.id=a.node_id'):
        values = json.loads(r['attributes'] or '{}')
        key = (r['source_file'],canonical(r['symbol_id'],r['kind']),r['target_kind'],r['target_path'],
               (r['annotation_fqn'] or '').replace('$','.'),r['raw_name'],
               offset(r['source_file'],r['begin_line'],r['begin_column']),
               offset(r['source_file'],r['end_line'],r['end_column'],True),json.dumps(values,sort_keys=True,ensure_ascii=False))
        right[key] += 1
    rows = lambda values: [dict(key=k,count=v) for k,v in sorted(values.items(),key=str)[:100]]
    return dict(product=left.total(),reference=right.total(),equal=left==right and not incomplete,
                missing=(right-left).total(),extra=(left-right).total(),missing_sample=rows(right-left),
                extra_sample=rows(left-right),incomplete_written_values=len(incomplete),
                incomplete_sample=incomplete[:100],identity_aliases=canonical.receipt)


def fixture_projection(facts, database, project):
    with sqlite3.connect(':memory:') as product, connection(database) as reference:
        product.row_factory = sqlite3.Row
        product.execute('CREATE TABLE facts(kind,source,body)')
        product.executemany('INSERT INTO facts VALUES(?,?,?)',
            ((f['kind'],f['source'],json.dumps(f)) for f in facts))
        inputs = verify_source_inputs(product,project)
        declarations = {f['id']:f for f in facts if f['kind'] in ('declaration','external_symbol')}
        return dict(project_source_inputs=inputs,**projection(facts,declarations,product,reference,project),
                    meta=meta_projection(facts,reference))


def main():
    p=argparse.ArgumentParser()
    for key in ('product','reference','project','output'):
        p.add_argument('--'+key,type=pathlib.Path,required=True)
    args=p.parse_args()
    before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as product,connection(args.reference) as reference:
        revision=product.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:
            raise ValueError('PUBLISHED_REVISION_REQUIRED')
        inputs=verify_source_inputs(product,args.project)
        declarations={}
        for r in product.execute("SELECT id,json_extract(body,'$.signature') AS signature,"
            "json_extract(body,'$.name') AS name,parent,json_extract(body,'$.attributes.element_kind') AS element_kind,"
            "json_extract(body,'$.attributes.parameter_names') AS parameters FROM facts WHERE kind IN ('declaration','external_symbol')"):
            declarations[r['id']]=dict(signature=r['signature'],name=r['name'],parent=r['parent'],
                attributes=dict(element_kind=r['element_kind'],parameter_names=json.loads(r['parameters'] or '[]')))
        facts=(json.loads(r['body']) for r in product.execute("SELECT body FROM facts WHERE kind='facet' AND relation='java.annotation'"))
        report=dict(kind='published_source_annotation_attribute_projection',revision=revision[0],database_sha256=before,
            project_source_inputs=inputs,**projection(facts,declarations,product,reference,args.project),
            annotation_meta_graph_accepted=False,full_semantic_diff_accepted=False)
        meta=(json.loads(r['body']) for r in product.execute("SELECT body FROM facts WHERE kind='facet' AND relation='java.annotation.meta'"))
        report['meta']=meta_projection(meta,reference)
        report['annotation_meta_graph_accepted']=report['meta']['equal']
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    report['changed_databases']=[]
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({k:report[k] for k in ('product','reference','missing','extra','incomplete_written_values','equal')})
    return 0 if report['equal'] and report['meta']['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
