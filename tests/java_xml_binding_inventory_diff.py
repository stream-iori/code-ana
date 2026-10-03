#!/usr/bin/env python3
"""All published Spring XML callable requests and candidate metadata, including unresolved requests.

The frozen constructor/lambda defect stays an explicit difference. This auditor
never selects a runtime candidate or claims whole XML/model equivalence.
"""
import argparse
import collections
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_functional_reference_alignment import canonical as symbol_identity, compare
from java_inventory_canonical import Canonical, verify_source_inputs
from java_sofa_xml_inventory_diff import verify_xml_inputs

ROLES = {'constructor':'constructor', 'factory-method':'factory', 'init':'init',
         'destroy':'destroy', 'property-member':'setter'}
MECHANISMS = {'constructor':'spring.xml.constructor', 'factory':'spring.xml.factory-method',
              'init':'spring.xml.init-method', 'destroy':'spring.xml.destroy-method',
              'setter':'spring.xml.property'}


def select_facts(db):
    selected = {row['id']:json.loads(row['body']) for row in db.execute(
        "SELECT id,body FROM facts WHERE relation='java.spring.xml' "
        "AND json_type(body,'$.attributes.member')='text'")}
    details = list(selected)
    pending = {f['parent'] for f in selected.values() if f['parent']}
    for identity in details:
        for row in db.execute("SELECT id,body FROM facts WHERE relation='config_member' AND parent=?",(identity,)):
            fact=json.loads(row['body']);selected[row['id']]=fact;pending.add(fact['target'])
    while pending:
        identity=pending.pop()
        if identity in selected:continue
        row=db.execute('SELECT body FROM facts WHERE id=?',(identity,)).fetchone()
        if row is None:raise ValueError('XML_BINDING_ENDPOINT_MISSING: '+identity)
        fact=json.loads(row['body']);selected[identity]=fact
        if fact['parent']:pending.add(fact['parent'])
    return list(selected.values())


def product_projection(facts, normalize=None):
    by={f['id']:f for f in facts};links=collections.defaultdict(list);result=[]
    for f in facts:
        if f['relation']=='config_member':links[f['parent']].append(f)
    norm=normalize or (lambda text,kind=None,source='',origin='product':symbol_identity(text,kind))
    for f in facts:
        a=f['attributes']
        if f['relation']!='java.spring.xml' or 'member' not in a:continue
        owner=by.get(f['parent']);visited=set()
        while owner and owner['relation']!='java.bean':
            if owner['id'] in visited:raise ValueError('XML_BINDING_PARENT_CYCLE')
            visited.add(owner['id']);owner=by.get(owner['parent'])
        if owner is None:raise ValueError('XML_BINDING_BEAN_OWNER_MISSING')
        role=ROLES[a['kind']];kind='CONSTRUCTOR' if role=='constructor' else 'METHOD'
        candidates=[]
        for link in links[f['id']]:
            target=by[link['target']];target_kind=target['attributes']['element_kind']
            candidates.append((norm(target['signature'],target_kind,f['source'],'product'),target_kind))
        if len(candidates)!=a['candidate_count']:raise ValueError('XML_BINDING_CANDIDATE_COUNT_MISMATCH')
        construction=owner['attributes'].get('xml_configuration_model',{}).get('construction',{})
        model_name=construction.get('name',owner['name'])
        # The original configuration remains explicit; no static/runtime choice is inferred from target names.
        static=None if role=='constructor' else not bool(owner['attributes']['factory_bean']) if role=='factory' else False
        result.append(dict(file=f['source'],bean=model_name,position={k:a['position'][k] for k in
            ('line','column','end_line','end_column')},role=role,mechanism=MECHANISMS[role],
            runtime='jvm',language='java',symbol_kind=kind,
            owner=norm(a['type'],None,f['source'],'product'),
            member='<init>' if kind=='CONSTRUCTOR' else a['member'],arity=a['arity'],
            hints=[norm(hint,None,f['source'],'product') for hint in a['parameter_type_hints']],
            static_requirement=static,resolution='unresolved' if not candidates else 'exact' if len(candidates)==1 else 'ambiguous',
            candidates=sorted(candidates),candidate_count=len(candidates),metadata_consistent=True))
    return sorted(result,key=lambda row:json.dumps(row,sort_keys=True))


def reference_projection(db, normalize=None):
    norm=normalize or (lambda text,kind=None,source='',origin='reference':symbol_identity(text,kind))
    nodes={r['id']:dict(r) for r in db.execute("SELECT * FROM nodes WHERE producer_id='spring-xml'")}
    parents={}
    for r in db.execute("SELECT source_id,target_id FROM edges WHERE producer_id='spring-xml' "
                        "AND relation IN ('CONFIGURES','XML_CONTAINS')"):
        if r['target_id'] in parents and parents[r['target_id']]!=r['source_id']:
            raise ValueError('REFERENCE_XML_BINDING_AMBIGUOUS_PARENT')
        parents[r['target_id']]=r['source_id']
    edges=collections.defaultdict(list)
    for edge in db.execute("SELECT * FROM edges WHERE producer_id='spring-xml' AND relation='BINDS_TO'"):
        edges[edge['source_id']].append(dict(edge))
    result=[]
    for identity,bindings in edges.items():
        node=nodes[identity];metadata=json.loads(bindings[0]['metadata']);symbol=metadata['symbolRef']
        owner=node;visited=set()
        while owner['kind']!='BEAN':
            if owner['id'] in visited:raise ValueError('REFERENCE_XML_BINDING_PARENT_CYCLE')
            visited.add(owner['id']);owner=nodes[parents[owner['id']]]
        candidates=[]
        for edge in bindings:
            detail=json.loads(edge['metadata'])
            if any(detail[k]!=metadata[k] for k in ['symbolRef','candidateCount','resolutionStatus','role','mechanism']):
                raise ValueError('REFERENCE_XML_BINDING_METADATA_DRIFT')
            if edge['target_id']:
                target=db.execute('SELECT symbol_id,kind FROM nodes WHERE id=?',(edge['target_id'],)).fetchone()
                if target is None:raise ValueError('REFERENCE_XML_BINDING_TARGET_MISSING')
                candidates.append((norm(target['symbol_id'],target['kind'],node['source_file'],'reference'),target['kind']))
        source=owner if node['kind']=='XML_CALLABLE_REF' else node
        result.append(dict(file=node['source_file'],bean=owner['label'],position=dict(
            line=source['begin_line'],column=source['begin_column'],end_line=source['end_line'],end_column=source['end_column']),
            role=metadata['role'],mechanism=metadata['mechanism'],runtime=symbol['runtime'],language=symbol['languageHint'],
            symbol_kind='CONSTRUCTOR' if symbol['symbolKind']=='constructor' else 'METHOD',
            owner=norm(symbol['owner'] or '',None,node['source_file'],'reference'),
            member='<init>' if symbol['symbolKind']=='constructor' else symbol['memberName'],arity=symbol['arity'],
            hints=[norm(hint,None,node['source_file'],'reference') for hint in symbol['parameterTypeHints']],
            static_requirement=symbol['staticRequirement'],resolution=metadata['resolutionStatus'],
            candidates=sorted(candidates),candidate_count=metadata['candidateCount'],
            metadata_consistent=len(candidates)==metadata['candidateCount']))
    return sorted(result,key=lambda row:json.dumps(row,sort_keys=True))


def main():
    parser=argparse.ArgumentParser()
    for name in ['product','reference','project','output']:parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args();before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as db,connection(args.reference) as ref:
        revision=db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('PUBLISHED_REVISION_REQUIRED')
        inputs=verify_source_inputs(db,args.project);canonical=Canonical(db,ref,args.project)
        norm=lambda text,kind=None,source='',origin='product':canonical(text,kind,source,origin)
        facts=select_facts(db);left=product_projection(facts,norm);right=reference_projection(ref,norm)
        xml,captured=verify_xml_inputs(args.product,args.project,revision[0],
                                      [dict(source=r['file']) for r in left+right])
        counter=lambda rows:collections.Counter((json.dumps(row,ensure_ascii=False,sort_keys=True),) for row in rows)
        report=dict(kind='published_spring_xml_callable_request_and_candidate_metadata',revision=revision[0],
            database_sha256=before,project_source_inputs=inputs,xml_source_sha256=xml,captured_manifest=captured,
            aliases=canonical.receipt,**compare(counter(left),counter(right)),
            reference_metadata_inconsistencies=[r for r in right if not r['metadata_consistent']],
            whole_xml_graph_accepted=False,full_semantic_diff_accepted=False)
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):raise ValueError('COMPARISON_DATABASE_DRIFT')
    if digest(pathlib.Path(captured['path']))!=captured['sha256'] or any(digest(args.project/path)!=sha for path,sha in xml.items()):
        raise ValueError('COMPARISON_CAPTURE_OR_XML_SOURCE_DRIFT')
    report['changed_databases']=[];args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(dict(product=report['product'],reference=report['reference'],equal=report['equal'],
               missing=sum(row['count'] for row in report['missing']),
               extra=sum(row['count'] for row in report['extra'])))
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
