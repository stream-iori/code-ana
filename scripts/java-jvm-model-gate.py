#!/usr/bin/env python3
"""Join deferred model runs without relabeling upstream differences as equal."""
import argparse
import collections
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tests'))
sys.path.insert(0, str(ROOT/'scripts'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json, verify_frozen
import importlib.util

spec = importlib.util.spec_from_file_location('model_collection',ROOT/'scripts/java-jvm-project-models.py')
models = importlib.util.module_from_spec(spec); spec.loader.exec_module(models)


def check_cases(reports, expected, issue):
    cases = [row for report in reports for row in report['cases']]
    counts = collections.Counter(row['id'] for row in cases)
    if set(counts) != set(expected) or any(count != 1 for count in counts.values()):
        raise ValueError('MODEL_CASE_COVERAGE')
    explained = issue['collector_selected_cases']
    failed = {row['id'] for row in cases if row['status'] != 'passed'}
    if failed != set(explained): raise ValueError('UNEXPLAINED_MODEL_FAILURE')
    for row in cases:
        if set(row['executions']) != {'reference','migrated'}:
            raise ValueError('MODEL_EXECUTION_COVERAGE')
        if any(value['status']!='passed' or value['exit_code']!=0 or value['unexpected_signal'] is not None
               for value in row['executions'].values()):
            raise ValueError('MODEL_EXECUTION_NOT_ACCEPTED')
        reference = models.verified_categories(row['results']['reference'])
        migrated = models.verified_categories(row['results']['migrated'])
        differences = sorted(name for name in reference if reference[name] != migrated[name])
        if differences != sorted(row['differences']): raise ValueError('MODEL_DIFFERENCE_DISCLOSURE')
        if row['status']=='passed' and differences: raise ValueError('MODEL_FALSE_GREEN')
        if row['id'] in explained and row != explained[row['id']]:
            raise ValueError('KNOWN_ISSUE_CASE_DRIFT')
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--models',type=pathlib.Path,action='append',required=True)
    for name in ['known-issue','inputs-manifest','project','freeze-receipt','output']:
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args = parser.parse_args()
    manifest = json.loads(args.inputs_manifest.read_text())
    expected = [mode+':'+module['id']+'@'+scope for mode in ['raw','ast']
                for module in manifest['modules'] if module.get('selected',True) for scope in ['MAIN','TEST']]
    if len(expected)!=160: raise ValueError('FROZEN_REACTOR_COVERAGE_REQUIRED')
    issue = json.loads(args.known_issue.read_text())
    if issue.get('status')!='known_upstream_issue_verified' or issue.get('changed_inputs')!=[]:
        raise ValueError('EXACT_UPSTREAM_ISSUE_PROOF_REQUIRED')
    if set(issue['collector_selected_cases']) != {'raw:app/biz/service-impl@TEST','ast:app/biz/service-impl@TEST'}:
        raise ValueError('UPSTREAM_ISSUE_CASE_COVERAGE')
    import hashlib
    if hashlib.sha256(json.dumps(issue['collector_selected_cases'],sort_keys=True).encode()).hexdigest() != issue['collector_selected_cases_sha256']:
        raise ValueError('UPSTREAM_ISSUE_CASE_PROOF_DRIFT')
    inputs = dict(issue['inputs'])
    def add_inputs(values):
        for path, digest in values.items():
            if path in inputs and inputs[path] != digest:
                raise ValueError('MODEL_INPUT_IDENTITY_CONFLICT: '+path)
            inputs[path] = digest
    for path in [*args.models,args.known_issue,args.inputs_manifest,args.freeze_receipt,pathlib.Path(__file__),ROOT/'scripts/java-jvm-project-models.py']:
        inputs[str(path.resolve())]=sha256(path)
    reports = [json.loads(path.read_text()) for path in args.models]
    common = ['frozen','oracle','committed_sources','configuration_inputs','expected','decode_budget_bytes']
    for number, report in enumerate(reports):
        if report['kind']!='fresh_oracle_frozen_project_jvm_models' or report['status']=='running' \
                or report.get('changed_inputs')!=[] or report.get('source_verified_after') is not True \
                or report.get('configuration_unchanged') is not True:
            raise ValueError('MODEL_RUN_NOT_FINISHED_OR_DRIFTED')
        if any(report['identity'][key]!=reports[0]['identity'][key] for key in common):
            raise ValueError('MODEL_RUN_IDENTITY_MISMATCH')
        if report['identity']['expected']!=expected: raise ValueError('MODEL_RUN_EXPECTED_COVERAGE')
        add_inputs(report['identity']['inputs'])
        for row in report['cases']: add_inputs(row['proofs'])
        class_manifest = args.models[number].parent/'witness-classes.json'
        if sha256(class_manifest)!=report['witness_classes_manifest_sha256']:
            raise ValueError('MODEL_WITNESS_MANIFEST_DRIFT')
        inputs[str(class_manifest)]=sha256(class_manifest)
        add_inputs(json.loads(class_manifest.read_text()))
    cases = check_cases(reports,expected,issue)
    frozen = verify_frozen(args.project,args.freeze_receipt)
    for relative,digest in reports[0]['identity']['committed_sources'].items():
        if sha256(args.project/relative)!=digest: raise ValueError('MODEL_SOURCE_DRIFT: '+relative)
    changed = [path for path,digest in inputs.items() if sha256(path)!=digest]
    if changed: raise ValueError('MODEL_INPUT_DRIFT: '+str(changed))
    result = dict(kind='joined_frozen_jvm_model_inventory',status='accepted_with_known_upstream_issue',
        expected_count=160,executed_count=len(cases),equal_count=sum(row['status']=='passed' for row in cases),
        known_upstream_cases=sorted(issue['collector_selected_cases']),unexplained_failures=[],
        all_contexts_executed=True,scope_model_inventory_accepted=True,
        strict_model_gate_accepted=False,complete_model_gate=False,native_accepted=False,
        publication_accepted=False,public_queries_accepted=False,
        frozen=frozen,inputs=inputs,changed_inputs=changed,cases=cases)
    atomic_json(args.output,result)
    print(json.dumps({key:result[key] for key in ['status','executed_count','equal_count','known_upstream_cases','unexplained_failures','strict_model_gate_accepted']}))


if __name__=='__main__': main()
