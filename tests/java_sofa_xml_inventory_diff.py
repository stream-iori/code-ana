#!/usr/bin/env python3
"""Strict published SOFA/MsgBroker XML registrations, including all child configuration.

Positions compare the start/end lines exposed by both producers. The reference
does not expose columns or a child graph; those remain product evidence, not
invented reference attributes. Duplicate registrations and children are retained.
"""
import argparse
import collections
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_inventory_canonical import verify_source_inputs
from java_functional_reference_alignment import compare


def row(source, name, first, last, attributes):
    return source, name, first, last, json.dumps(attributes, ensure_ascii=False, sort_keys=True)


def product_projection(facts):
    result = collections.Counter()
    for fact in facts:
        a = fact['attributes']
        if (fact['relation'] != 'java.sofa' or not fact['source'].endswith('.xml')
                or a.get('kind') not in ('service','reference','publisher','consumer')):
            continue
        children = []
        for child in a['configuration']:
            value = dict(element=child['element'], attributes=child['attributes'], line=child['position']['line'])
            if 'channel' in child:
                value['channel'] = child['channel']
            children.append(value)
        fields = dict(framework=a['framework'], role=a['role'], mechanism=a['mechanism'],
                      attributes=a['xml_attributes'], configuration=children, namespace=a['namespace'],
                      baseline=a['contract_baseline'], contract=a['contract'], unique=a['unique_id'],
                      group=a['group'], bindings=a['bindings'], transport=a['transport'],
                      supported=a['contract_supported'], target=a['target_bean'])
        position = a['position']
        result[row(fact['source'],fact['name'],position['line'],position['end_line'],fields)] += 1
    return result


def reference_projection(db):
    result = collections.Counter()
    for node in db.execute("SELECT label,source_file,begin_line,end_line,metadata FROM nodes "
                           "WHERE kind='FRAMEWORK_REGISTRATION' AND source_file LIKE '%.xml'"):
        a = json.loads(node['metadata'] or '{}')
        if not a.get('mechanism','').startswith(('sofa.xml.','msgbroker.xml.')):
            continue
        fields = dict(framework=a['framework'],role=a['role'],mechanism=a['mechanism'],
                      attributes=a['attributes'],configuration=a['configuration'],namespace=a['namespace'],
                      baseline=a['contractBaseline'],contract=a['contract'],unique=a['uniqueId'],
                      group=a['group'],bindings=a['bindings'],transport=a['transport'],
                      supported=a['contractSupported'],target=a.get('targetBean',''))
        result[row(node['source_file'],node['label'],node['begin_line'],node['end_line'],fields)] += 1
    return result


def select_facts(db):
    return [json.loads(row['body']) for row in db.execute(
        "SELECT body FROM facts WHERE relation='java.sofa' AND json_extract(body,'$.source') LIKE '%.xml' "
        "AND json_extract(body,'$.attributes.kind') IN ('service','reference','publisher','consumer')")]


def verify_xml_inputs(database, project, revision, facts):
    metadata = database.parent/'revision.json'
    captured = json.loads(metadata.read_text())
    if captured['id'] != revision:
        raise ValueError('CAPTURE_REVISION_MISMATCH')
    expected = {}
    for item in captured['manifest']['files']:
        path = item['path']
        if path in expected and expected[path] != item['hash']:
            raise ValueError('AMBIGUOUS_CAPTURE_HASH: '+path)
        expected[path] = item['hash']
    actual = {}
    for fact in facts:
        path = fact['source']
        if not (project/path).resolve().is_relative_to(project.resolve()):
            raise ValueError('XML_SOURCE_OUTSIDE_PROJECT: '+path)
        if path not in expected or digest(project/path) != expected[path]:
            raise ValueError('CAPTURED_XML_SOURCE_DRIFT: '+path)
        actual[path] = expected[path]
    return actual, dict(path=str(metadata),sha256=digest(metadata))


def main():
    parser = argparse.ArgumentParser()
    for name in ('product','reference','project','output'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args = parser.parse_args()
    before = dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as product, connection(args.reference) as reference:
        revision = product.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:
            raise ValueError('PUBLISHED_REVISION_REQUIRED')
        sources = verify_source_inputs(product,args.project)
        facts = select_facts(product)
        xml, captured = verify_xml_inputs(args.product,args.project,revision[0],facts)
        result = compare(product_projection(facts),reference_projection(reference))
        report = dict(kind='published_sofa_msgbroker_xml_registration_attributes',revision=revision[0],
                      database_sha256=before,project_source_inputs=sources,xml_source_sha256=xml,captured_manifest=captured,
                      **result,full_semantic_diff_accepted=False,whole_xml_graph_accepted=False)
    if before != dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    if any(digest(args.project/path) != checksum for path,checksum in xml.items()):
        raise ValueError('COMPARISON_XML_SOURCE_DRIFT')
    if digest(pathlib.Path(captured['path'])) != captured['sha256']:
        raise ValueError('COMPARISON_CAPTURE_MANIFEST_DRIFT')
    report['changed_databases'] = []
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({key:report[key] for key in ('product','reference','equal')})
    return 0 if report['equal'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
