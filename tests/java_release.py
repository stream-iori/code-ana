#!/usr/bin/env python3
"""Run actual release-package CLI/daemon/native smoke without rebuilding frozen artifacts.

This certifies the packaged runtime only. All Java parity and exclusive capacity
gates remain independently required by java_alignment's final accounting.
"""
import argparse
import json
import os
import pathlib
import subprocess
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_product_runtime import inventory


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=pathlib.Path);args=parser.parse_args()
    base=(args.evidence or alignment.ROOT/'target/java-alignment/evidence'/
          ('release-package-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))).resolve()
    base.mkdir(parents=True,exist_ok=False)
    frozen=alignment.input_files();artifacts=reference.probe_artifacts(True)
    package=base/'package';started=time.monotonic()
    report=dict(kind='release_package_real_runtime',all_ok=False,full_semantic_diff_accepted=False,
                final_capacity_accepted=False,inputs=frozen,artifacts=artifacts,gates=[])
    try:
        with (base/'package.log').open('wb') as log:
            subprocess.run(['./scripts/package-java.sh','--from-build','--output',str(package)],
                           cwd=alignment.ROOT,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
        before=inventory(package)
        entries=(package/'SHA256SUMS').read_text().splitlines()
        expected={}
        for entry in entries:
            checksum,path=entry.split('  ',1)
            target=package/path
            if not target.resolve().is_relative_to(package):raise ValueError('PACKAGE_CHECKSUM_PATH')
            if reference.digest(target)!=checksum:raise ValueError('PACKAGE_CHECKSUM_MISMATCH: '+path)
            if path in expected:raise ValueError('DUPLICATE_PACKAGE_CHECKSUM: '+path)
            expected[path]=checksum
        if expected!={key:value for key,value in before.items() if key!='SHA256SUMS'}:
            raise ValueError('INCOMPLETE_PACKAGE_CHECKSUMS')
        environment=dict(os.environ,CODEANA_TEST_BIN=str(package/'bin'))
        for name in ('test_core','test_maven_xml_only_main_test_contexts_publish_and_delta','test_wrong_jdk'):
            command=[os.sys.executable,'-m','unittest','discover','-s','tests','-p','java_e2e.py','-k',name,'-v']
            log=base/(name+'.log')
            with log.open('wb') as stream:
                result=subprocess.run(command,cwd=alignment.ROOT,env=environment,stdout=stream,
                                      stderr=subprocess.STDOUT,timeout=900)
            report['gates'].append(dict(name=name,exit_code=result.returncode,log_sha256=reference.digest(log)))
        current_package=inventory(package)
        current_inputs=alignment.input_files();current_artifacts=reference.probe_artifacts(True)
        report.update(package_files=before,changed_package=sorted(key for key in before.keys()|current_package.keys()
            if before.get(key)!=current_package.get(key)))
        report['changed_inputs']=sorted(key for key in frozen.keys()|current_inputs.keys()
            if frozen.get(key)!=current_inputs.get(key))
        report['changed_artifacts']=sorted(key for key in artifacts.keys()|current_artifacts.keys()
            if artifacts.get(key)!=current_artifacts.get(key))
        report['all_ok']=len(report['gates'])==3 and all(g['exit_code']==0 for g in report['gates']) and not any(
            report[key] for key in ('changed_package','changed_inputs','changed_artifacts'))
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__)
    report['elapsed_seconds']=round(time.monotonic()-started,3)
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
