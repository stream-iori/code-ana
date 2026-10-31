#!/usr/bin/env python3
"""Run the shared resource matrix using all five actual release executables.

Preserve authorized executable paths; archive and restore development bytes.
The matching Native SDK must already be installed and all runtimes stopped.
"""
import argparse
import json
import pathlib
import subprocess
import sys
from acceptance_support import sha256
from java_release import atomic_binary_copy

ROOT=pathlib.Path(__file__).resolve().parents[1]
NAMES=('codeana-cli','codeana-daemon','codeana-java-worker','codeana-rust-worker','codeana-python-worker')

def active(directory):
    rows=subprocess.check_output(['ps','-axo','pid=,comm='],text=True)
    return [line.strip() for line in rows.splitlines()
            if len(fields:=line.strip().split(None,1))==2 and fields[1].startswith(str(directory)+'/codeana-')]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    parser.add_argument('--case',action='append',default=[])
    args=parser.parse_args();output=args.evidence.resolve();output.mkdir(parents=True,exist_ok=False)
    binaries=(ROOT/'target/debug').resolve()
    if active(binaries):raise ValueError('STOP_RUNTIME_BEFORE_RELEASE_TEST')
    saved=output/'original-binaries';saved.mkdir()
    original={};released={}
    # Archive every executable before replacing the first one.
    import shutil
    for name in NAMES:
        source=ROOT/'target/release'/name
        released[name]=sha256(source)
        original[name]=sha256(binaries/name)
        shutil.copy2(binaries/name,saved/name)
    sdk_paths=[path for path in (ROOT/'target/lib/java').rglob('*') if path.is_file()]
    sdk_before={str(path):sha256(path) for path in sdk_paths}
    report=dict(kind='all_languages_release_resource_matrix',all_ok=False,original_binaries=original,
                release_binaries=released,sdk_before=sdk_before,native_accepted=False,
                performance_accepted=False,complete_plan=False,complete_group_suite=not args.case)
    staged=[]
    try:
        for name in NAMES:
            atomic_binary_copy(ROOT/'target/release'/name,binaries/name);staged.append(name)
        if any(sha256(binaries/name)!=released[name] for name in NAMES):raise ValueError('RELEASE_EXECUTION_BYTES_MISMATCH')
        with (output/'gate.log').open('w') as log:
            result=subprocess.run([sys.executable,str(ROOT/'tests/analysis_resources_gate.py'),
                                   '--backend','native','--group','all','--evidence',str(output/'gate'),*[part for name in args.case for part in ['--case',name]]],
                                  cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=1500)
        report['gate_exit_code']=result.returncode
        if result.returncode==0:report['all_ok']=True
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__)
    finally:
        running=active(binaries)
        report['active_at_restore']=running
        if running:
            report.update(all_ok=False,original_binaries_restored=False)
        else:
            for name in staged:atomic_binary_copy(saved/name,binaries/name)
            report['original_binaries_restored']=all(sha256(binaries/name)==original[name] for name in NAMES)
            report['all_ok'] &= report['original_binaries_restored']
        sdk_after={str(path):sha256(path) for path in sdk_paths}
        report['sdk_unchanged']=sdk_before==sdk_after
        report['all_ok'] &= report['sdk_unchanged']
        (output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:report[key] for key in ['all_ok','original_binaries_restored','sdk_unchanged']}))
    return 0 if report['all_ok'] else 1

if __name__=='__main__':raise SystemExit(main())
