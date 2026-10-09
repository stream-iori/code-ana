#!/usr/bin/env python3
"""Complete lexical owners and control strings, including empty contexts and duplicates."""
import argparse
import collections
import functools
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_functional_reference_alignment import compare
from java_inventory_canonical import Canonical, compiler_lines, verify_source_inputs


def inventory(product, reference, project):
    normalize=Canonical(product,reference,project)
    declarations={row['id']:json.loads(row['body']) for row in product.execute(
        "SELECT id,body FROM facts WHERE kind IN ('declaration','external_symbol')")}
    nodes={row['id']:dict(row) for row in reference.execute('SELECT * FROM nodes')}
    parents={}
    for row in reference.execute("SELECT source_id,target_id FROM edges WHERE relation='CONTAINS'"):
        if row['target_id'] in parents and parents[row['target_id']]!=row['source_id']:
            raise ValueError('AMBIGUOUS_LEXICAL_PARENT')
        parents[row['target_id']]=row['source_id']

    @functools.lru_cache(maxsize=128)
    def source_lines(path):return compiler_lines((project/path).read_bytes().decode('utf8'))

    def offset(path,line,column,inclusive=False):
        if not line or not column:return None
        lines=source_lines(path)
        return sum(len(row.encode('utf8')) for row in lines[:line-1])+len(
            lines[line-1].encode('utf-16-le')[:2*(column-1+inclusive)].decode('utf-16-le').encode('utf8'))

    @functools.lru_cache(maxsize=None)
    def owner(identity, side, active=()):
        if identity in active or len(active)>=256:raise ValueError('LEXICAL_PARENT_CYCLE_OR_BUDGET')
        if side=='product':
            row=declarations.get(identity)
            if row is None:raise ValueError('PRODUCT_LEXICAL_OWNER_MISSING: '+identity)
            kind=row['attributes'].get('element_kind');span=row.get('span') or {}
            if kind in ('LAMBDA','METHOD_REF','INITIALIZER'):
                return (kind,row['source'],span.get('start'),span.get('end'),owner(row['parent'],side,active+(identity,)))
            return normalize(row['signature'],kind,row['source'],side)
        row=nodes.get(identity)
        if row is None:raise ValueError('REFERENCE_LEXICAL_OWNER_MISSING: '+identity)
        kind=row['kind']
        if kind in ('LAMBDA','METHOD_REF','SOURCE_REGION'):
            return ('INITIALIZER' if kind=='SOURCE_REGION' else kind,row['source_file'],
                offset(row['source_file'],row['begin_line'],row['begin_column']),
                offset(row['source_file'],row['end_line'],row['end_column'],True),
                owner(parents.get(identity,''),side,active+(identity,)))
        return normalize(row['symbol_id'],kind,row['source_file'],side)

    domains=('lexical_owners','calls','reads','writes','type_reference','reflection_lookup')
    left={name:collections.Counter() for name in domains};right={name:collections.Counter() for name in domains}
    for identity,row in declarations.items():
        if row['kind']=='declaration' and row['attributes']['element_kind'] in ('LAMBDA','METHOD_REF','INITIALIZER'):
            left['lexical_owners'][(owner(identity,'product'),)]+=1
    for identity,row in nodes.items():
        if row['kind'] in ('LAMBDA','METHOD_REF','SOURCE_REGION'):
            right['lexical_owners'][(owner(identity,'reference'),)]+=1
    for row in product.execute("SELECT body FROM facts WHERE kind='call_site' OR relation IN "
        "('reads','writes','type_reference','reflection_lookup')"):
        fact=json.loads(row['body']);span=fact.get('span') or {};attrs=fact['attributes']
        relation='calls' if fact['kind']=='call_site' else fact['relation']
        target='' if relation=='calls' else owner(fact['target'],'product')
        context=attrs.get('type_context') if relation in ('type_reference','reflection_lookup') else attrs.get('control_context',attrs.get('control'))
        left[relation][(fact['source'],span.get('start'),span.get('end'),owner(fact['parent'],'product'),target,context or None)]+=1
    for row in reference.execute('SELECT s.*,o.source_file,o.caller_id FROM call_sites s JOIN call_site_owners o ON s.owner_pk=o.owner_pk'):
        path=row['source_file']
        right['calls'][(path,offset(path,row['begin_line'],row['begin_column']),
            offset(path,row['end_line'],row['end_column'],True),owner(row['caller_id'],'reference'),'',row['context'] or None)]+=1
    for row in reference.execute("SELECT * FROM edges WHERE relation IN ('READS','WRITES','REFERENCES')"):
        path=row['source_file'];target=nodes.get(row['target_id'])
        signature=(target or {}).get('symbol_id') or row['external_target_symbol'] or row['external_target_fqn']
        if not signature:raise ValueError('REFERENCE_CONTROL_TARGET_MISSING')
        relation=row['relation'].lower()
        if relation=='references':
            relation='reflection_lookup' if (target or {}).get('kind') in ('METHOD','CONSTRUCTOR','FIELD','ENUM_CONSTANT') or '#' in signature else 'type_reference'
        target_identity=normalize(signature,(target or {}).get('kind'),path,'reference')
        right[relation][(path,offset(path,row['begin_line'],row['begin_column']),
            offset(path,row['end_line'],row['end_column'],True),owner(row['source_id'],'reference'),target_identity,row['context'] or None)]+=1
    return dict(projections={name:compare(left[name],right[name]) for name in domains},identity_aliases=normalize.receipt)


def main():
    parser=argparse.ArgumentParser()
    for name in ('product','reference','project','output'):parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args();before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as product,connection(args.reference) as reference:
        revision=product.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('PUBLISHED_REVISION_REQUIRED')
        sources=verify_source_inputs(product,args.project)
        report=dict(kind='published_complete_lexical_owners_and_control_contexts',revision=revision[0],
            project_source_inputs=sources,database_sha256=before,**inventory(product,reference,args.project),
            full_semantic_diff_accepted=False)
        report['equal']=all(value['equal'] for value in report['projections'].values())
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):raise ValueError('COMPARISON_DATABASE_DRIFT')
    report['changed_databases']=[];args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({name:{key:value[key] for key in ('product','reference','equal')} for name,value in report['projections'].items()},flush=True)
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
