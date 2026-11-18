#!/usr/bin/env python3
"""Explicit production-prefix ABI/component gate; no TS product certification."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

from acceptance_support import identities, sha256

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('baseline',ROOT/'scripts/multilang-baseline.py')
baseline=importlib.util.module_from_spec(spec);spec.loader.exec_module(baseline)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--fixture-sdk',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--binary',type=Path,default=ROOT/'target/debug/codeana-typescript-worker')
    args=parser.parse_args();sdk=args.sdk.resolve();fixture_sdk=args.fixture_sdk.resolve();binary=args.binary.resolve();output=args.evidence.resolve()
    output.mkdir(parents=True,exist_ok=False)
    fixtures=output/'fixtures';fixtures.mkdir()
    for name in ('a.ts','b.ts','expected.json'):shutil.copy2(ROOT/'lang-typescript-adaptor/probe/fixtures'/name,fixtures/name)
    paths=[ROOT/name for name in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]
    paths+=[binary,*[path for directory in (sdk,fixture_sdk) for path in directory.rglob('*') if path.is_file()]]
    before=identities(paths)
    report=dict(stage='T2b ABI and component',status='failed',cases=[],inputs=before,
                component_only=True,semantic_graph_complete=False,complete_plan=False,native_accepted=False,performance_accepted=False)
    def run(name,argv,expected=0,empty_path=True,fixture_mode=None):
        log=output/(name+'.log');entry=dict(id=name,argv=list(map(str,argv)),expected_exit=expected,log=log.name)
        report['cases'].append(entry);started=time.monotonic()
        environment=dict(os.environ)
        if empty_path:environment['PATH']=''
        if fixture_mode:environment['CODEANA_TS_ABI_FIXTURE']=fixture_mode
        with log.open('wb') as stream:
            process=subprocess.Popen(entry['argv'],cwd=output,env=environment,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:process.wait(timeout=45)
            finally:
                baseline.signal_group(process.pid,signal.SIGKILL)
                entry.update(exit_code=process.wait(),seconds=time.monotonic()-started,sha256=sha256(log))
        if entry['exit_code']!=expected:raise AssertionError(f'{name}: {entry}; see {log}')
        entry['status']='passed'
        return log
    try:
        log=run('abi',[binary,'--sdk',sdk,'--abi-check'])
        conformance=json.loads(log.read_text());assert conformance['status']=='passed'
        assert len(conformance['case_ids'])==len(set(conformance['case_ids']))==conformance['checks']==20
        report['abi_cases']=conformance['case_ids']
        for name,exit_code in [('success',0),('prepare',0),('quota',1),('invalid',1),('cancel-before-start',130)]:
            evidence=output/name
            run(name,[binary,'--sdk',sdk,'--component',fixtures,'--evidence',evidence,'--case',name],exit_code)
            receipt=json.loads((evidence/'receipt.json').read_text())
            assert receipt['component_only'] and receipt['semantic_partial'] and not receipt['complete_plan']
            assert receipt['analysis_usage']['work']>0 and receipt['analysis_usage']['payload_bytes']>0
            if name=='success':
                assert receipt['accounting_owner']=='HostOwned' and receipt['verified_graph']['count']>5
                declarations=receipt['semantic_declarations'];greet=[d['id'] for d in declarations if d['name']=='greet' and d['kind']=='function'];assert len(greet)==1
                calls=receipt['semantic_calls'];assert len(calls)==2 and all(c['target']==greet[0] and json.loads(c['metadata'])['target_kind']=='implementation' for c in calls),calls
                assert receipt['analysis_usage']['failure'] is None
            elif name=='prepare':
                assert receipt['verified_graph'] is None and receipt['accounting_owner']=='HostOwned'
                native=json.loads((evidence/'native-inputs.json').read_text())
                assert native['schema_version']==1 and len(native['contexts'])==1
                manifest=json.loads((sdk/'manifest.json').read_text())
                assert native['compiler']['library_sha256']==manifest['files']['libcodeana_typescript.dylib']
                assert 'sdk_path' not in native['compiler']
                inventory=receipt['prepared_inventory']
                assert len(inventory['files'])==3 and len(inventory['context']['root_files'])==2
                assert len(inventory['context']['absent'])>0
                assert sorted(file['path'].rsplit('/',1)[-1] for file in inventory['files'])==['a.ts','b.ts','tsconfig.json']
            else:
                assert receipt['verified_graph'] is None
                if name=='quota':
                    assert receipt['accounting_owner']=='HostOwned'
                    assert receipt['analysis_usage']['failure']['Limit']['resource']=='work'
                else:assert receipt['accounting_owner']=='Unknown'
        for name,exit_code in [('panic',1),('running-close',130)]:
            evidence=output/name
            run(name,[binary,'--sdk',fixture_sdk,'--dev-abi-fixture','--component',fixtures,'--evidence',evidence,'--case',name],exit_code,fixture_mode=name)
            receipt=json.loads((evidence/'receipt.json').read_text())
            assert receipt['development_fixture'] and receipt['verified_graph'] is None and receipt['accounting_owner']=='Unknown'
            if name=='running-close':assert receipt['running_close_pending_observed']
            else:assert receipt['outcome']['Failure']['code']==8
        log=run('fixture-sdk-rejected',[binary,'--sdk',fixture_sdk,'--runtime-info'],1)
        assert 'SDK_DEVELOPMENT_FIXTURE_REJECTED' in log.read_text()
        # SDK verification precedes runtime loading. Each negative uses a new
        # isolated SDK; the original bundle and executable remain untouched.
        for name in ('sdk-digest','sdk-abi','sdk-missing'):
            changed=output/name;shutil.copytree(sdk,changed)
            if name=='sdk-digest':
                with (changed/'libcodeana_typescript.dylib').open('ab') as file:file.write(b'corrupt')
                expected='SDK_HASH_MISMATCH'
            elif name=='sdk-abi':
                path=changed/'manifest.json';manifest=json.loads(path.read_text());manifest['backend']['abi']=99;path.write_text(json.dumps(manifest))
                expected='SDK_TYPESCRIPT_PROTOCOL_MISMATCH'
            else:
                (changed/'codeana_ts.h').unlink();expected='No such file'
            log=run(name,[binary,'--sdk',changed,'--runtime-info'],1)
            assert expected in log.read_text(),log.read_text()
        after=identities(paths);report['artifact_drift']=[name for name in sorted(before.keys()|after.keys()) if before.get(name)!=after.get(name)]
        assert not report['artifact_drift']
        report.update(status='passed',expected_count=12,executed_count=len(report['cases']),abi_check_count=20)
        assert report['executed_count']==report['expected_count']
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__)
        raise
    finally:(output/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:report[key] for key in ('status','executed_count','abi_check_count','artifact_drift')}))

if __name__=='__main__':main()
