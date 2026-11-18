#!/usr/bin/env python3
"""Run actual packaged hosts with a separately installed Java SDK."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
from acceptance_support import identities,sha256
ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package',type=Path,required=True)
    parser.add_argument('--java-sdk',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    args=parser.parse_args();package=args.package.resolve();output=args.evidence.resolve();output.mkdir(parents=True,exist_ok=False)
    before=identities(p for p in package.rglob('*') if p.is_file())
    prefix=output/'runtime';(prefix/'bin').mkdir(parents=True);(prefix/'lib').mkdir()
    for source in (package/'bin').iterdir():shutil.copy2(source,prefix/'bin'/source.name)
    (prefix/'lib/java').symlink_to(args.java_sdk.resolve(),target_is_directory=True)
    (prefix/'lib/typescript').symlink_to(package/'lib/typescript',target_is_directory=True)
    for source in (package/'bin').iterdir():assert sha256(source)==sha256(prefix/'bin'/source.name)
    os.environ.update(CODEANA_TEST_BIN=str(prefix/'bin'),CODEANA_TEST_FIXTURES=str(package/'fixtures'),
                      CODEANA_TYPESCRIPT_SDK=str(prefix/'lib/typescript/current'),CODEANA_JAVA_BACKEND='native',CODEANA_TEST_REAL_JAVA='1')
    # Import fixtures only after binding every executable and fixture directory.
    from multilang_analysis_e2e import OrdinaryMatrix,PreparedTypeScriptMatrix
    from typescript_resources_e2e import TypeScriptResources
    from typescript_faults_e2e import TypeScriptFaults
    from backend_test_support import run_cases
    import unittest
    tests=[OrdinaryMatrix(name) for name in ['test_java_typescript','test_rust_typescript','test_python_typescript','test_java_rust_python_typescript','test_four_backend_default_quota_retains_head']]
    tests.append(PreparedTypeScriptMatrix('test_all_backends_share_prepared_units_and_one_publication'))
    tests+=list(unittest.defaultTestLoader.loadTestsFromTestCase(TypeScriptResources))
    tests+=list(unittest.defaultTestLoader.loadTestsFromTestCase(TypeScriptFaults))
    status=run_cases(tests,['java','rust','python','typescript'],{},output/'gate')
    after=identities(p for p in package.rglob('*') if p.is_file())
    drift=[p for p in before.keys()|after.keys() if before.get(p)!=after.get(p)]
    receipt={'status':'passed' if status==0 and not drift else 'failed','package':str(package),
             'package_files':before,'artifact_drift':drift,'executed_count':len(tests),'separate_java_sdk':str(args.java_sdk.resolve()),
             'gate':'gate/receipt.json','formal_native_acceptance':False,'performance_acceptance':False}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    return 0 if receipt['status']=='passed' else 1

if __name__=='__main__':raise SystemExit(main())
