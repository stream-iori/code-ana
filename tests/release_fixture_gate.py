#!/usr/bin/env python3
"""Verify shipped bytes and execute the supported Rust/Python release scopes."""
import argparse
import importlib
import json
import os
import pathlib
import time
import sys
import unittest
from acceptance_support import identities,summarize
import java_jvm_product_gate as evidence

DEBUG_ONLY={
 'test_cancel_before_commit_retains_head','test_timeout_before_commit_retains_head',
 'test_cancel_during_commit_reports_publication','test_cancel_after_commit_reports_publication',
 'test_duplicate_can_cancel_a_capture_before_first_submit_returns','test_candidate_sync_crash',
 'test_revision_rename_crash','test_catalog_commit_crash','test_report_write_failure_after_publication'}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--package',type=pathlib.Path,required=True)
    parser.add_argument('--kind',choices=['rust','python'],required=True)
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    args=parser.parse_args();package=args.package.resolve();output=args.evidence.resolve()
    sys.dont_write_bytecode=True
    os.environ['PYTHONDONTWRITEBYTECODE']='1'
    output.mkdir(parents=True,exist_ok=False);evidence.PROGRESS_FILE=output/'progress.json'
    expected_files={}
    for line in (package/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1);path=package/name
        if name in expected_files or not path.resolve().is_relative_to(package):raise ValueError('PACKAGE_CHECKSUM_PATH')
        expected_files[str(path)]=digest
    actual=identities(path for path in package.rglob('*') if path.is_file() and path.name!='SHA256SUMS')
    if actual!=expected_files:raise ValueError('PACKAGE_CHECKSUMS_INCOMPLETE_OR_CHANGED')
    os.environ['CODEANA_TEST_BIN']=str(package/'bin')
    os.environ['CODEANA_TEST_FIXTURES']=str(package/'fixtures')
    tests=[];exclusions={}
    if args.kind=='rust':
        module=importlib.import_module('rust_graph_e2e')
        for test in evidence.flatten(unittest.defaultTestLoader.loadTestsFromModule(module)):
            if test._testMethodName in DEBUG_ONLY:exclusions[test.id()]='debug fault hook; covered by debug product gate'
            else:tests.append(test)
        tests.extend(evidence.flatten(unittest.defaultTestLoader.loadTestsFromModule(importlib.import_module('rust_language_e2e'))))
    else:
        module=importlib.import_module('python_language_e2e')
        tests.extend(evidence.flatten(unittest.defaultTestLoader.loadTestsFromTestCase(module.PythonLanguage)))
        module=importlib.import_module('prepared_inputs_e2e')
        for test in evidence.flatten(unittest.defaultTestLoader.loadTestsFromTestCase(module.PreparedInputs)):
            if test._testMethodName=='test_independent_maven_reactors_and_three_languages':
                exclusions[test.id()]='Java SDK distributed and tested separately'
            else:tests.append(test)
    resources=importlib.import_module('analysis_resources_e2e')
    # Honor the resource module's explicit gate; inherited legacy test methods
    # are outside this matrix and can otherwise pull in an unbundled Java SDK.
    for test in evidence.flatten(unittest.defaultTestLoader.loadTestsFromModule(resources)):
        if isinstance(test,resources.JavaBudget):continue
        if args.kind=='rust' and not isinstance(test,resources.RustBudget):continue
        tests.append(test)
    expected=[test.id() for test in tests]
    (output/'expected-cases.json').write_text(json.dumps(expected,indent=2)+'\n')
    before=identities(path for path in package.rglob('*') if path.is_file())
    execution_files={key:value for key,value in before.items() if pathlib.Path(key).name!='SHA256SUMS'}
    if execution_files!=expected_files:raise ValueError('PACKAGE_CHANGED_DURING_GATE_SETUP')
    started=time.monotonic()
    with (output/'tests.log').open('w') as log:
        result=unittest.TextTestRunner(stream=log,verbosity=2,resultclass=evidence.ReceiptResult).run(unittest.TestSuite(tests))
    receipt=summarize(expected,result.cases,before,identities(path for path in package.rglob('*') if path.is_file()))
    receipt.update(kind=args.kind,seconds=time.monotonic()-started,package=str(package),package_files=before,
                   declared_scope_exclusions=exclusions,complete_plan=False,native_accepted=False,performance_accepted=False)
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({key:receipt[key] for key in ['status','expected_count','executed_count','artifact_drift']}))
    return 0 if receipt['status']=='passed' else 1

if __name__=='__main__':raise SystemExit(main())
