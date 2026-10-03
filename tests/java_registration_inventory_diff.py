#!/usr/bin/env python3
"""Read-only full-project annotation registrations and HANDLES, including attributes.

XML registrations, Bean/DI and other framework edges are separate required domains.
"""
import argparse
import json
import pathlib

from java_declaration_inventory_diff import connection, digest
from java_inventory_canonical import verify_source_inputs
from java_registration_reference_alignment import product_projection, reference_projection
from java_functional_reference_alignment import compare


def facts_for_registrations(db):
    selected = {}
    for row in db.execute("SELECT id,body FROM facts WHERE relation IN "
            "('java.spring.mvc','java.spring.handler','java.sofa','handles_possible')"):
        fact = json.loads(row['body'])
        selected[row['id']] = fact
    # Retain every selected facet/link and its actual owner/target; never synthesize a declaration.
    wanted = {f[key] for f in selected.values() for key in ('parent','target') if f[key]}
    for identity in wanted - selected.keys():
        row = db.execute('SELECT body FROM facts WHERE id=?', (identity,)).fetchone()
        if row is None:
            raise ValueError('REGISTRATION_EVIDENCE_ENDPOINT_MISSING: '+identity)
        selected[identity] = json.loads(row['body'])
    return list(selected.values())


def schema_gaps(facts):
    required = {
        'java.spring.mvc': ('parameters','path','http_method','consumes','produces','params'),
        'java.spring.handler': ('role','mechanism','written_attributes','source_parameter_types','condition_status'),
        'java.sofa': ('role','mechanism','contract','unique_id','written_attributes','api_members',
                      'contract_supported','transport','contract_baseline'),
    }
    gaps = []
    for fact in facts:
        missing = [key for key in required.get(fact['relation'], ()) if key not in fact['attributes']]
        if missing:
            gaps.append(dict(id=fact['id'],relation=fact['relation'],missing_attributes=missing))
    return gaps


def main():
    parser = argparse.ArgumentParser()
    for name in ('product','reference','project','output'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args = parser.parse_args()
    before = dict(product=digest(args.product),reference=digest(args.reference))
    with connection(args.product) as db:
        revision = db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:
            raise ValueError('PUBLISHED_REVISION_REQUIRED')
        sources = verify_source_inputs(db,args.project)
        facts = facts_for_registrations(db)
        gaps = schema_gaps(facts)
        right = reference_projection(args.reference)
        result = (dict(equal=False,comparison_error='REGISTRATION_SCHEMA_INCOMPLETE',
                       schema_gap_count=len(gaps),schema_gap_sample=gaps[:100],reference=right.total())
                  if gaps else compare(product_projection(facts),right))
        report = dict(kind='published_annotation_registrations_and_handles',revision=revision[0],
            database_sha256=before,project_source_inputs=sources,**result,
            full_semantic_diff_accepted=False,whole_framework_graph_accepted=False)
    if before != dict(product=digest(args.product),reference=digest(args.reference)):
        raise ValueError('COMPARISON_DATABASE_DRIFT')
    report['changed_databases'] = []
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print({key:report[key] for key in ('product','reference','equal','comparison_error','schema_gap_count') if key in report})
    return 0 if report['equal'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
