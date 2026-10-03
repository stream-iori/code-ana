#!/usr/bin/env python3
"""Strict XML WIRES endpoint/location multiset over an immutable published revision.

Runtime certainty is deliberately not equated to Anatomist's EXTRACTED label.
Distinct source references are retained, including same-line duplicates; no
missing endpoints, aliases or reference collisions are silently removed.
"""
import argparse
import collections
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_inventory_canonical import Canonical, verify_source_inputs
from java_functional_reference_alignment import compare
from java_xml_wiring_reference import product_projection, reference_projection


def select_facts(db):
    selected = {}
    for row in db.execute("SELECT id,body FROM facts WHERE relation='wires_possible' "
                          "AND json_extract(body,'$.attributes.via')='xml_configuration'"):
        selected[row['id']] = json.loads(row['body'])
    wanted = {f[key] for f in selected.values() for key in ('parent','target')}
    wanted.update(f['attributes']['configuration_evidence'] for f in selected.values())
    for identity in wanted-selected.keys():
        row = db.execute('SELECT body FROM facts WHERE id=?', (identity,)).fetchone()
        if row is None:
            raise ValueError('XML_WIRING_EVIDENCE_ENDPOINT_MISSING: '+identity)
        selected[identity] = json.loads(row['body'])
    return list(selected.values())


def main():
    parser = argparse.ArgumentParser()
    for name in ('product','reference','project','output'):
        parser.add_argument('--'+name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    before = dict(product=digest(args.product), reference=digest(args.reference))
    metadata = args.product.parent/'revision.json'
    captured_sha = digest(metadata)
    captured = json.loads(metadata.read_text())
    expected_hashes = {}
    for unit in captured['manifest']['files']:
        if unit['path'] in expected_hashes and expected_hashes[unit['path']] != unit['hash']:
            raise ValueError('AMBIGUOUS_CAPTURE_HASH: '+unit['path'])
        expected_hashes[unit['path']] = unit['hash']
    with connection(args.product) as product, connection(args.reference) as reference:
        revision = product.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision or revision[0] != captured['id']:
            raise ValueError('CAPTURE_REVISION_MISMATCH')
        sources = verify_source_inputs(product, args.project)
        canonical = Canonical(product, reference, args.project)
        left = product_projection(select_facts(product), include_file=True)
        right = reference_projection(reference)
        paths = {row['file'] for row in left+right}
        xml_hashes = {}
        for path in paths:
            target = args.project/path
            if not target.resolve().is_relative_to(args.project.resolve()):
                raise ValueError('XML_SOURCE_OUTSIDE_PROJECT: '+path)
            if path not in expected_hashes or digest(target) != expected_hashes[path]:
                raise ValueError('CAPTURED_XML_SOURCE_DRIFT: '+path)
            xml_hashes[path] = expected_hashes[path]

        def rows(values, origin):
            return collections.Counter((row['file'], canonical(row['source'], source=row['file'], origin=origin),
                canonical(row['target'], source=row['file'], origin=origin), row['external'], row['line'])
                for row in values)

        result = compare(rows(left,'product'), rows(right,'reference'))
        report = dict(kind='published_spring_xml_wiring_endpoints_and_lines', revision=revision[0],
                      database_sha256=before, project_source_inputs=sources, xml_source_sha256=xml_hashes,
                      captured_manifest=dict(path=str(metadata),sha256=captured_sha),
                      aliases=canonical.receipt, **result, full_semantic_diff_accepted=False,
                      whole_xml_graph_accepted=False)
    if before != dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    if digest(metadata) != captured_sha or any(digest(args.project/path) != checksum
                                             for path,checksum in xml_hashes.items()):
        raise ValueError('COMPARISON_CAPTURE_OR_XML_SOURCE_DRIFT')
    report['changed_databases'] = []
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({key:report[key] for key in ('product','reference','equal')})
    return 0 if report['equal'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
