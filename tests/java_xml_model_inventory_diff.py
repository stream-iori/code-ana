#!/usr/bin/env python3
"""Lossless Spring Bean construction/config-tree metadata, from published facts and XML graph."""
import argparse
import collections
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_inventory_canonical import verify_source_inputs
from java_sofa_xml_inventory_diff import verify_xml_inputs
from java_xml_model_reference_alignment import projection
from java_functional_reference_alignment import compare


def reference_models(db):
    nodes = {row['id']:dict(row) for row in db.execute("SELECT * FROM nodes WHERE producer_id='spring-xml'")}
    metadata = {identity:json.loads(node['metadata'] or '{}') for identity,node in nodes.items()}
    children = collections.defaultdict(list)
    for edge in db.execute("SELECT * FROM edges WHERE producer_id='spring-xml' "
                           "AND relation IN ('CONFIGURES','XML_CONTAINS')"):
        if metadata.get(edge['target_id'],{}).get('xmlKind'):
            children[edge['source_id']].append(edge['target_id'])
    result = []
    for bean in nodes.values():
        attributes = metadata[bean['id']]
        if bean['kind'] != 'BEAN' or attributes.get('source') != 'xml':
            continue
        values, roots, visited = [], [], set()

        def append(identity):
            if identity in visited or len(visited) >= 30000:
                raise ValueError('REFERENCE_XML_CONFIGURATION_CYCLE_OR_BUDGET')
            visited.add(identity)
            node = nodes[identity]
            index = len(values)
            nested = []
            values.append(dict(metadata=metadata[identity], children=nested,
                position=dict(line=node['begin_line'],column=node['begin_column'],
                              end_line=node['end_line'],end_column=node['end_column'])))
            ordered = sorted(children[identity],key=lambda child:(nodes[child]['source_ordinal'],child))
            for child in ordered:nested.append(append(child))
            return index

        ordered = sorted(children[bean['id']],key=lambda child:(nodes[child]['source_ordinal'],child))
        for identity in ordered:roots.append(append(identity))
        construction = dict(name=bean['label'],className=attributes.get('className'),
            abstract=attributes['abstract'],parent=attributes.get('parent'),factoryBean=attributes.get('factoryBean'),
            factoryMethod=attributes.get('factoryMethod'),initMethod=attributes.get('initMethod'),
            destroyMethod=attributes.get('destroyMethod'),nestedIn=attributes.get('nestedIn'),
            ordinal=bean['source_ordinal'],attributes={key:value for key,value in attributes.items()
                if key in ('primary','autowireCandidate','aliases') or key.startswith('unresolved:')})
        result.append(dict(file=bean['source_file'],model=dict(version=1,construction=construction,roots=roots,nodes=values)))
    return result


def main():
    parser=argparse.ArgumentParser()
    for name in ('product','reference','project','output'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args()
    before=dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as db,connection(args.reference) as ref:
        revision=db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('PUBLISHED_REVISION_REQUIRED')
        sources=verify_source_inputs(db,args.project)
        facts=[json.loads(row['body']) for row in db.execute("SELECT body FROM facts WHERE relation='java.bean' "
                "AND json_extract(body,'$.attributes.kind')='xml_bean'")]
        missing=[f['id'] for f in facts if 'xml_configuration_model' not in f['attributes']]
        right=reference_models(ref)
        # Missing product facts must not let a reference XML input escape the
        # same captured-byte check used for the successful product projection.
        xml,captured=verify_xml_inputs(args.product,args.project,revision[0],
            facts+[dict(source=row['file']) for row in right])
        counter=lambda rows:collections.Counter((row['file'],json.dumps(row['model'],sort_keys=True,ensure_ascii=False)) for row in rows)
        left=[dict(file=f['source'],model=projection([f])[0]) for f in facts if 'xml_configuration_model' in f['attributes']]
        result=compare(counter(left),counter(right))
        report=dict(kind='published_spring_xml_construction_and_configuration_models',revision=revision[0],
            project_source_inputs=sources,xml_source_sha256=xml,captured_manifest=captured,database_sha256=before,
            **result,schema_gap_count=len(missing),schema_gap_sample=missing[:100],
            whole_xml_graph_accepted=False,full_semantic_diff_accepted=False)
        report['equal']=result['equal'] and not missing
    if before!=dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    if digest(pathlib.Path(captured['path']))!=captured['sha256'] or any(
            digest(args.project/path)!=checksum for path,checksum in xml.items()):
        raise ValueError('COMPARISON_CAPTURE_OR_XML_SOURCE_DRIFT')
    report['changed_databases']=[]
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({key:report[key] for key in ('product','reference','equal','schema_gap_count')})
    return 0 if report['equal'] else 1


if __name__=='__main__':raise SystemExit(main())
