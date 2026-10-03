#!/usr/bin/env python3
"""Every published Spring XML relation, with lossless configuration endpoints.

Configuration models are product evidence: their logical edges need not use the
reference's IDs or SQL layout. Unknown relations and absent models are failures.
"""
import argparse
import collections
import functools
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_functional_reference_alignment import compare
from java_inventory_canonical import Canonical, verify_source_inputs
from java_sofa_xml_inventory_diff import verify_xml_inputs
from java_xml_binding_inventory_diff import product_projection as product_bindings, reference_projection as reference_bindings
from java_xml_wiring_reference import product_projection as product_wires, reference_projection as reference_wires

RELATIONS={'CONFIGURES','XML_CONTAINS','XML_REFERS_TO','DEFINED_BY','PARENT_BEAN','FACTORY_BEAN','BINDS_TO','WIRES'}


def encoded(value):return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'))


def bean_key(file,name,ordinal,position):
    return ['bean',file,name,ordinal,{key:position[key] for key in ('line','column','end_line','end_column')}]


def config_key(bean,metadata,position):
    return ['configuration',bean,metadata,{key:position[key] for key in ('line','column','end_line','end_column')}]


def select_facts(db):
    facts={row['id']:json.loads(row['body']) for row in db.execute(
        "SELECT id,body FROM facts WHERE source LIKE '%.xml' AND relation IN "
        "('java.bean','java.spring.xml','config','config_type','config_owner','config_member','wires_possible')")}
    pending={fact[key] for fact in facts.values() for key in ('parent','target') if fact[key]}
    pending.update(f['attributes']['configuration_evidence'] for f in facts.values()
                   if f['relation']=='wires_possible' and f['attributes'].get('via')=='xml_configuration')
    while pending:
        identity=pending.pop()
        if identity in facts:continue
        row=db.execute('SELECT body FROM facts WHERE id=?',(identity,)).fetchone()
        if row is None:raise ValueError('XML_GRAPH_ENDPOINT_MISSING: '+identity)
        fact=json.loads(row['body']);facts[identity]=fact
        if fact['parent']:pending.add(fact['parent'])
    return facts


def product_graph(facts,normalize):
    edges={name:collections.Counter() for name in RELATIONS-{'BINDS_TO','WIRES'}}
    beans={};schema=[]
    for identity,fact in facts.items():
        if fact['relation']!='java.bean' or fact['attributes'].get('kind')!='xml_bean':continue
        model=fact['attributes'].get('xml_configuration_model')
        if not model:schema.append(identity);continue
        construction=model['construction'];position=fact['attributes']['position']
        beans[identity]=bean_key(fact['source'],construction['name'],construction['ordinal'],position)
        edges['XML_CONTAINS'][(encoded(['artifact',fact['source']]),encoded(beans[identity]),position['line'],construction['ordinal'],False)]+=1
        nodes=model['nodes'];visited=set()
        def walk(index,parent,root,depth=0):
            if index in visited or depth>=256 or not 0<=index<len(nodes):raise ValueError('PRODUCT_XML_CONFIGURATION_GRAPH_INVALID')
            visited.add(index);node=nodes[index]
            endpoint=config_key(beans[identity],node['metadata'],node['position'])
            edges['CONFIGURES' if root else 'XML_CONTAINS'][(encoded(parent),encoded(endpoint),node['position']['line'],None,False)]+=1
            for child in node['children']:walk(child,endpoint,False,depth+1)
        for index in model['roots']:walk(index,beans[identity],True)
        if len(visited)!=len(nodes):raise ValueError('PRODUCT_XML_CONFIGURATION_UNREACHABLE_NODES')

    @functools.lru_cache(maxsize=None)
    def enclosing(identity):
        active=set()
        while identity and identity not in beans:
            if identity in active or len(active)>=256:raise ValueError('PRODUCT_XML_GRAPH_PARENT_CYCLE')
            active.add(identity);identity=facts[identity]['parent']
        return identity

    for fact in facts.values():
        if fact['relation']=='java.spring.xml' and 'member' in fact['attributes']:
            attrs=fact['attributes'];identity=enclosing(fact['id'])
            if identity and attrs['kind'] in ('constructor','factory-method','init','destroy'):
                role={'factory-method':'factory'}.get(attrs['kind'],attrs['kind'])
                edges['CONFIGURES'][(encoded(beans[identity]),encoded(['callable',beans[identity],role]),attrs['position']['line'],None,False)]+=1
    links=collections.defaultdict(list)
    for fact in facts.values():links[(fact['parent'],fact['relation'])].append(fact)
    def targets(site):
        found=links[(site['id'],'config')]
        expected=site['attributes'].get('candidate_count',0)
        if len(found)!=expected:raise ValueError('PRODUCT_XML_GRAPH_REFERENCE_COUNT_MISMATCH')
        if not found:return [['external_bean',site['attributes']['bean']]]
        result=[]
        for link in found:
            target=facts[link['target']]
            if target['id'] in beans:result.append(beans[target['id']])
            elif target['source'].endswith('.java'):result.append(['java_bean',target['source'],target['name'],
                normalize(target['attributes']['type'],source=target['source'],origin='product')])
            else:schema.append(target['id']+':missing_reference_endpoint_model')
        return result
    for identity,endpoint in beans.items():
        fact=facts[identity];model=fact['attributes']['xml_configuration_model'];construction=model['construction']
        if construction.get('className') and not construction.get('factoryMethod'):
            defined_targets=links[(identity,'config_type')]
            if len(defined_targets)!=1:schema.append(identity+':defined_by')
            for link in defined_targets:
                target=facts[link['target']]
                symbol=normalize(target['signature'],target['attributes'].get('element_kind'),fact['source'],'product')
                edges['DEFINED_BY'][(encoded(endpoint),encoded(['java',symbol]),fact['attributes']['position']['line'],None,target['kind']=='external_symbol')]+=1
        elif construction.get('factoryMethod'):
            # Definition is the selected factory METHOD; config_type describes
            # its product CLASS. These are separate edges in the reference.
            sites=[site for site in facts.values() if site['parent']==identity
                and site['relation']=='java.spring.xml' and site['attributes'].get('kind')=='factory-method']
            if len(sites)!=1:schema.append(identity+':factory_definition');continue
            site=sites[0];bound=links[(site['id'],'config_member')]
            if not bound:
                attrs=site['attributes'];hints=[hint or '?' for hint in attrs['parameter_type_hints']]
                symbol=(attrs['type'] or '?')+'#'+attrs['member']+'('+','.join(hints)+')'
                edges['DEFINED_BY'][(encoded(endpoint),encoded(['java',normalize(symbol,'METHOD',fact['source'],'product')]),fact['attributes']['position']['line'],None,True)]+=1
            for link in bound:
                target=facts[link['target']]
                symbol=normalize(target['signature'],target['attributes'].get('element_kind'),fact['source'],'product')
                edges['DEFINED_BY'][(encoded(endpoint),encoded(['java',symbol]),fact['attributes']['position']['line'],None,target['kind']=='external_symbol')]+=1
        # Logical XML refs retain each source occurrence, including synthetic
        # attribute nodes. Each possible candidate is retained; unresolved names
        # remain external rather than disappearing from the inventory.
        for index,node in enumerate(model['nodes']):
            metadata=node['metadata']
            if metadata['xmlKind'] not in ('ref','idref') or 'bean' not in metadata:continue
            matching=[site for site in facts.values() if site['relation']=='java.spring.xml'
                and site['source']==fact['source'] and site['span']==node['source_element_span']
                and site['attributes'].get('kind') in ('bean_ref','idref','parent_context_ref')
                and site['attributes'].get('bean')==metadata['bean']]
            if len(matching)!=1:
                schema.append(identity+':reference_site:'+str(index));continue
            candidates=targets(matching[0])
            source=config_key(endpoint,metadata,node['position'])
            for target in candidates:
                edges['XML_REFERS_TO'][(encoded(source),encoded(target),node['position']['line'],None,target[0]=='external_bean')]+=1
        for field,relation in (('parent','PARENT_BEAN'),('factoryBean','FACTORY_BEAN')):
            name=construction.get(field)
            if not name:continue
            kind='parent_bean' if relation=='PARENT_BEAN' else 'factory_bean'
            sites=[site for site in facts.values() if site['relation']=='java.spring.xml'
                and site['parent']==identity and site['attributes'].get('kind')==kind]
            if len(sites)!=1:schema.append(identity+':'+kind);continue
            candidates=targets(sites[0])
            for target in candidates:
                edges[relation][(encoded(endpoint),encoded(target),fact['attributes']['position']['line'],None,target[0]=='external_bean')]+=1
    return edges,schema


def reference_graph(db,normalize):
    nodes={row['id']:dict(row) for row in db.execute("SELECT * FROM nodes WHERE producer_id='spring-xml'")}
    metadata={identity:json.loads(node['metadata'] or '{}') for identity,node in nodes.items()}
    parents={}
    for row in db.execute("SELECT source_id,target_id FROM edges WHERE producer_id='spring-xml' AND relation IN ('CONFIGURES','XML_CONTAINS')"):
        if metadata.get(row['target_id'],{}).get('xmlKind') or nodes.get(row['target_id'],{}).get('kind')=='XML_CALLABLE_REF':
            if row['target_id'] in parents and parents[row['target_id']]!=row['source_id']:raise ValueError('REFERENCE_XML_GRAPH_AMBIGUOUS_PARENT')
            parents[row['target_id']]=row['source_id']
    def bean(identity):
        active=set()
        while metadata.get(identity,{}).get('source')!='xml':
            if identity in active or len(active)>=256:raise ValueError('REFERENCE_XML_GRAPH_PARENT_CYCLE')
            active.add(identity);identity=parents[identity]
        return identity
    @functools.lru_cache(maxsize=None)
    def endpoint(identity):
        node=nodes.get(identity)
        if node is None:
            target=db.execute('SELECT kind,symbol_id,label,source_file,metadata FROM nodes WHERE id=?',(identity,)).fetchone()
            if target is None:raise ValueError('REFERENCE_XML_GRAPH_TARGET_MISSING')
            if target['kind']=='BEAN':return ['java_bean',target['source_file'],target['label'],
                normalize(json.loads(target['metadata'] or '{}').get('className',''),source=target['source_file'],origin='reference')]
            return ['java',normalize(target['symbol_id'],target['kind'],target['source_file'],'reference')]
        position=dict(line=node['begin_line'],column=node['begin_column'],end_line=node['end_line'],end_column=node['end_column'])
        attrs=metadata[identity]
        if attrs.get('xmlKind'):return config_key(endpoint(bean(identity)),attrs,position)
        if node['kind']=='BEAN':return bean_key(node['source_file'],node['label'],node['source_ordinal'],position)
        if node['kind']=='ARTIFACT':return ['artifact',node['source_file']]
        if node['kind']=='XML_CALLABLE_REF':
            role=attrs.get('role') or attrs['symbolRef']['role']
            return ['callable',endpoint(bean(identity)),role]
        raise ValueError('REFERENCE_XML_GRAPH_NODE_KIND: '+node['kind'])
    edges={name:collections.Counter() for name in RELATIONS-{'BINDS_TO','WIRES'}}
    unknown=[];counts=collections.Counter()
    for row in db.execute("SELECT * FROM edges WHERE producer_id='spring-xml'"):
        relation=row['relation'];counts[relation]+=1
        if relation not in RELATIONS:unknown.append(dict(row));continue
        if relation not in edges:continue
        source=endpoint(row['source_id'])
        target=endpoint(row['target_id']) if row['target_id'] else ['external_bean' if relation in ('XML_REFERS_TO','PARENT_BEAN','FACTORY_BEAN') else 'java',row['external_target_fqn']]
        edges[relation][(encoded(source),encoded(target),row['begin_line'],row['source_ordinal'],bool(row['is_external']))]+=1
    return edges,counts,unknown


def main():
    parser=argparse.ArgumentParser()
    for name in ('product','reference','project','output'):parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args();before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as product,connection(args.reference) as reference:
        revision=product.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('PUBLISHED_REVISION_REQUIRED')
        sources=verify_source_inputs(product,args.project);normalize=Canonical(product,reference,args.project)
        facts=select_facts(product);left,gaps=product_graph(facts,normalize);right,counts,unknown=reference_graph(reference,normalize)
        projections={name:compare(left[name],right[name]) for name in sorted(left)}
        bindings=lambda rows:collections.Counter((encoded(row),) for row in rows)
        projections['BINDS_TO']=compare(bindings(product_bindings(list(facts.values()),normalize)),bindings(reference_bindings(reference,normalize)))
        wires=lambda rows,side:collections.Counter((row['file'],normalize(row['source'],source=row['file'],origin=side),normalize(row['target'],source=row['file'],origin=side),row['external'],row['line']) for row in rows)
        projections['WIRES']=compare(wires(product_wires(list(facts.values()),include_file=True),'product'),wires(reference_wires(reference),'reference'))
        files={fact['source'] for fact in facts.values() if fact['source'].endswith('.xml')}
        files.update(row[0] for row in reference.execute("SELECT DISTINCT source_file FROM nodes WHERE producer_id='spring-xml'"))
        xml,captured=verify_xml_inputs(args.product,args.project,revision[0],[dict(source=file) for file in files])
        report=dict(kind='published_complete_spring_xml_logical_graph',revision=revision[0],database_sha256=before,
            project_source_inputs=sources,xml_source_sha256=xml,captured_manifest=captured,
            projections=projections,reference_raw_relation_counts=dict(counts),unknown_reference_edges=unknown,
            schema_gaps=gaps,identity_aliases=normalize.receipt,full_semantic_diff_accepted=False)
        report['equal']=not gaps and not unknown and all(row['equal'] for row in projections.values())
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):raise ValueError('COMPARISON_DATABASE_DRIFT')
    if digest(pathlib.Path(captured['path']))!=captured['sha256'] or any(digest(args.project/path)!=sha for path,sha in xml.items()):raise ValueError('COMPARISON_XML_SOURCE_DRIFT')
    report['changed_databases']=[];args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({name:{key:row[key] for key in ('product','reference','equal')} for name,row in projections.items()},flush=True)
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
