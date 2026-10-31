#!/usr/bin/env python3
"""Record exact cases and source/runtime identities for shared resource gates."""
import argparse
import importlib
import json
import os
import pathlib
import subprocess
import time
import unittest
from acceptance_support import identities,summarize
import java_jvm_product_gate as evidence

ROOT=pathlib.Path(__file__).resolve().parents[1]
GROUPS={'resources':['analysis_resources_e2e'], 'mixed':['analysis_resources_mixed_e2e'],
        'history':['analysis_resources_history_e2e']}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    parser.add_argument('--backend',choices=['native','jvm'],default='native')
    parser.add_argument('--group',choices=[*GROUPS,'all'],default='all')
    parser.add_argument('--case',action='append',default=[])
    args=parser.parse_args()
    output=args.evidence.resolve();output.mkdir(parents=True,exist_ok=False)
    evidence.PROGRESS_FILE=output/'progress.json'
    os.environ['CODEANA_JAVA_BACKEND']=args.backend
    from java_product_runtime import prepare
    runtime=prepare(ROOT,output/'runtime',args.backend,stable_paths=True)
    os.environ['CODEANA_TEST_BIN']=runtime['binary_directory']
    os.environ['CODEANA_TEST_JAVA_BUNDLE']=str(output/'runtime/target/lib/java'/args.backend)
    modules=GROUPS[args.group] if args.group!='all' else sum(GROUPS.values(),[])
    tests=[test for module in modules for test in evidence.flatten(unittest.defaultTestLoader.loadTestsFromModule(importlib.import_module(module)))]
    if args.case:
        tests=[test for test in tests if any(test.id().endswith('.'+name) for name in args.case)]
        if len(tests)!=len(set(args.case)):raise ValueError('UNKNOWN_OR_DUPLICATE_CASE_SELECTION')
    expected=[test.id() for test in tests]
    (output/'expected-cases.json').write_text(json.dumps(expected,indent=2)+'\n')
    paths=[ROOT/name for name in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]
    paths += [ROOT/'target/debug'/name for name in ['codeana-cli','codeana-daemon','codeana-java-worker','codeana-rust-worker','codeana-python-worker']]
    paths += [path for path in (ROOT/'target/lib/java').rglob('*') if path.is_file()]
    paths += [pathlib.Path(path) for key in ['application_files','installed_files'] for path in runtime.get(key,{})]
    before=identities(paths);started=time.monotonic()
    with (output/'tests.log').open('w') as log:
        result=unittest.TextTestRunner(stream=log,verbosity=2,resultclass=evidence.ReceiptResult).run(unittest.TestSuite(tests))
    receipt=summarize(expected,result.cases,before,identities(paths))
    receipt.update(seconds=time.monotonic()-started,backend=args.backend,group=args.group,
                   complete_group_suite=not args.case,inputs=before,complete_plan=False,
                   native_accepted=False,release_accepted=False,performance_accepted=False)
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({key:receipt[key] for key in ['status','executed_count','expected_count','artifact_drift']}))
    return 0 if receipt['status']=='passed' else 1

if __name__=='__main__':raise SystemExit(main())
