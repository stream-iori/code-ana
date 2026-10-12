#!/usr/bin/env python3
"""Verify the single frozen upstream constructor/method ID collision.

This explains exactly two failed raw/AST cases. It never turns the strict model
gate green and rejects every additional field or occurrence difference.
"""
import argparse
import collections
import hashlib
import json
import pathlib

TYPE = 'com.alipay.iopengw.biz.service.IOpengwManagerServiceImplTest'
OLD = TYPE + '#IOpengwManagerServiceImplTest()'
NEW = TYPE + '#<init>()'
SOURCE = 'app/biz/service-impl/src/test/java/com.alipay.iopengw.biz.service/IOpengwManagerServiceImplTest.java'
FIELDS = {'facts.declarations':'symbolId','facts.nodes':'id','facts.edges':'targetId'}


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def compare_rows(domain, reference, migrated):
    missing = list((collections.Counter(reference)-collections.Counter(migrated)).elements())
    extra = list((collections.Counter(migrated)-collections.Counter(reference)).elements())
    if len(missing)!=1 or len(extra)!=1:
        raise ValueError('CONSTRUCTOR_ISSUE_OCCURRENCE_COVERAGE: '+domain)
    before, after = json.loads(missing[0]), json.loads(extra[0])
    field = FIELDS[domain]
    if before.get(field)!=OLD or after.get(field)!=NEW or before.get('sourceFile')!=SOURCE or after.get('sourceFile')!=SOURCE:
        raise ValueError('CONSTRUCTOR_ISSUE_IDENTITY: '+domain)
    wanted=dict(before);wanted[field]=NEW
    if wanted!=after:
        raise ValueError('CONSTRUCTOR_ISSUE_ADDITIONAL_FIELD_DIFFERENCE: '+domain)
    if domain=='facts.edges':
        if before.get('relation')!='CONTAINS' or before.get('sourceId')!=TYPE:
            raise ValueError('CONSTRUCTOR_ISSUE_RELATION')
    elif before.get('kind')!='CONSTRUCTOR':
        raise ValueError('CONSTRUCTOR_ISSUE_KIND')
    return dict(field=field,before=before,after=after)


def main():
    parser=argparse.ArgumentParser()
    for name in ('models','collector','project','adaptation','output'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args()
    replay=json.loads(args.models.read_text());collector=json.loads(args.collector.read_text())
    adaptation=json.loads(args.adaptation.read_text())
    original=pathlib.Path(adaptation['original'])
    if sha(original)!=adaptation['original_sha256'] or adaptation['analysis_rules_changed'] is not False:
        raise ValueError('EVIDENCE_WITNESS_ADAPTATION_DRIFT')
    witness=next(pathlib.Path(path) for path in replay['identity']['inputs'] if path.endswith('/ParityWitness.java'))
    adapted=original.read_text()
    for before,after in adaptation['replacements']:
        if adapted.count(before)!=1:raise ValueError('EVIDENCE_WITNESS_ADAPTATION_AMBIGUOUS')
        adapted=adapted.replace(before,after)
    if adapted!=witness.read_text() or sha(witness)!=adaptation['adapted_sha256']:
        raise ValueError('EVIDENCE_WITNESS_ADAPTATION_DRIFT')
    case_ids={mode+':app/biz/service-impl@TEST' for mode in ('raw','ast')}
    if {row['id'] for row in replay['cases']}!=case_ids or len(replay['cases'])!=2:
        raise ValueError('CONSTRUCTOR_ISSUE_CASE_COVERAGE')
    inputs={str(path.resolve()):sha(path) for path in [args.models,args.adaptation,args.project/SOURCE,pathlib.Path(__file__),original,witness]}
    source=(args.project/SOURCE).read_text()
    if 'public void IOpengwManagerServiceImplTest() throws Exception' not in source:
        raise ValueError('LEGAL_CLASS_NAMED_METHOD_SOURCE_REQUIRED')
    report=dict(kind='known_frozen_upstream_constructor_collision',status='known_upstream_issue_verified',
        strict_model_gate_accepted=False,full_model_gate_accepted=False,native_accepted=False,
        source=SOURCE,source_sha256=sha(args.project/SOURCE),cases=[],inputs=inputs)
    selected={row['id']:row for row in collector['cases']}
    report['collector_receipt']=str(args.collector.resolve())
    report['collector_selected_cases']={identity:selected[identity] for identity in sorted(case_ids)}
    report['collector_selected_cases_sha256']=hashlib.sha256(json.dumps(report['collector_selected_cases'],sort_keys=True).encode()).hexdigest()
    for case in replay['cases']:
        if case['status']!='failed' or set(case['differences'])!=set(FIELDS):
            raise ValueError('CONSTRUCTOR_ISSUE_DOMAIN_COVERAGE')
        if case['results']!=selected[case['id']]['results']:
            raise ValueError('CONSTRUCTOR_ISSUE_REPLAY_MODEL_DRIFT')
        for path,digest in case['proofs'].items():
            if sha(path)!=digest:raise ValueError('CONSTRUCTOR_ISSUE_INPUT_DRIFT: '+path)
            inputs[path]=digest
        for execution in case['executions'].values():
            if execution['status']!='passed' or execution['unexpected_signal'] is not None:
                raise ValueError('CONSTRUCTOR_ISSUE_REFERENCE_EXECUTION')
        directory=next(pathlib.Path(path).parent for path in case['proofs'] if path.endswith('reference.json'))
        differences={}
        for domain in FIELDS:
            paths=[directory/(label+'-rows')/(domain+'.json') for label in ('reference','migrated')]
            rows=[json.loads(path.read_text()) for path in paths]
            inputs.update({str(path):sha(path) for path in paths})
            differences[domain]=compare_rows(domain,*rows)
            if domain=='facts.declarations':
                reference=[json.loads(row) for row in rows[0]];migrated=[json.loads(row) for row in rows[1]]
                if sum(row.get('symbolId')==OLD and row.get('kind')=='METHOD' for row in reference)!=1:
                    raise ValueError('REFERENCE_CLASS_NAMED_METHOD_REQUIRED')
                if sum(row.get('symbolId')==OLD for row in reference)!=2:
                    raise ValueError('REFERENCE_COLLISION_NOT_REPRODUCED')
                if sum(row.get('symbolId')==OLD and row.get('kind')=='METHOD' for row in migrated)!=1:
                    raise ValueError('PRODUCT_CLASS_NAMED_METHOD_LOST')
                if sum(row.get('symbolId')==NEW and row.get('kind')=='CONSTRUCTOR' for row in migrated)!=1:
                    raise ValueError('PRODUCT_CONSTRUCTOR_LOST')
        report['cases'].append(dict(id=case['id'],unchanged_domains=11,differences=differences,
            original_strict_status=selected[case['id']]['status']))
    report['changed_inputs']=[path for path,digest in inputs.items() if sha(path)!=digest]
    if report['changed_inputs']:raise ValueError('CONSTRUCTOR_ISSUE_INPUT_DRIFT')
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=report['status'],strict_model_gate_accepted=False,explained_cases=len(report['cases']))))


if __name__=='__main__':main()
