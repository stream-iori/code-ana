#!/usr/bin/env python3
"""Actual SDK installation, immutable versions and rejection before selection."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from typescript_sdk import install,verify,sha
from acceptance_support import identities
ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--fixture-sdk',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--worker',type=Path,default=ROOT/'target/debug/codeana-typescript-worker')
    args=parser.parse_args();output=args.evidence.resolve();output.mkdir(parents=True,exist_ok=False)
    sdk=args.sdk.resolve();worker=args.worker.resolve()
    paths=[p for root in (sdk,args.fixture_sdk.resolve()) for p in root.rglob('*') if p.is_file()]+[worker]
    before=identities(paths);report={'status':'failed','cases':[],'inputs':before,'formal_release':False}
    try:
        first=install(sdk,output/'prefix',worker)
        original=Path(first['sdk']);selected=Path(first['current'])
        assert selected.is_symlink() and selected.resolve()==original
        old_files=identities(p for p in original.rglob('*') if p.is_file())
        assert install(sdk,output/'prefix',worker)['sdk']==str(original)
        report['cases'].append('install-and-reuse-immutable-version')
        moved=output/'equivalent-sdk';shutil.copytree(sdk,moved)
        manifest=moved/'manifest.json';data=json.loads(manifest.read_text());manifest.write_text(json.dumps(data,separators=(',',':'))+'\n')
        library=original/'libcodeana_typescript.dylib'
        with library.open('rb') as active:
            inode=os.fstat(active.fileno()).st_ino
            second=install(moved,output/'prefix',worker)
            assert second['version']!=first['version']
            assert selected.resolve()==Path(second['sdk']) and original.is_dir()
            assert os.fstat(active.fileno()).st_ino==inode==library.stat().st_ino
        assert identities(p for p in original.rglob('*') if p.is_file())==old_files
        assert first['runtime']==second['runtime']
        report['cases'].append('atomic-selection-keeps-open-old-inode-and-semantic-identity')
        valid=selected.resolve()
        for mode in ('digest','missing','abi','analysis','adapter','platform','fixture'):
            damaged=output/('reject-'+mode)
            shutil.copytree(args.fixture_sdk.resolve() if mode=='fixture' else sdk,damaged)
            path=damaged/'manifest.json';metadata=json.loads(path.read_text())
            if mode=='digest':
                with (damaged/'libcodeana_typescript.dylib').open('ab') as stream:stream.write(b'corrupt')
            elif mode=='missing':(damaged/'codeana_ts.h').unlink()
            elif mode=='abi':metadata['backend']['abi']=999
            elif mode=='analysis':metadata['backend']['analysis_request']=1
            elif mode=='adapter':metadata['adapter']=metadata['adapter'].replace('inputs7','inputs6')
            elif mode=='platform':metadata.update(os='Linux',arch='x86_64')
            path.write_text(json.dumps(metadata))
            try:install(damaged,output/'prefix',worker)
            except (ValueError,OSError) as error:report['cases'].append({'id':'reject-'+mode,'error':str(error)})
            else:raise AssertionError('invalid SDK installed: '+mode)
            assert selected.resolve()==valid
        assert identities(p for p in original.rglob('*') if p.is_file())==old_files
        report['artifact_drift']=[p for p in before if identities(paths).get(p)!=before[p]]
        assert not report['artifact_drift']
        report.update(status='passed',executed_count=len(report['cases']))
    except Exception as error:
        report.update(failure=str(error),failure_type=type(error).__name__);raise
    finally:(output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('status','executed_count','artifact_drift')}))

if __name__=='__main__':main()
