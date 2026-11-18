#!/usr/bin/env python3
"""Isolated native crashes, stalled FFI, archive corruption and process cleanup."""
import argparse
import contextlib
import json
import os
import pathlib
import signal
import struct
import subprocess
import time
import unittest
from unittest.mock import patch
from typescript_product_support import TypeScriptHarness
from typescript_fault_support import fault_sdk, sha
from graph_product_support import canonical_graph
from backend_test_support import run_cases
from product_support import BIN


class TypeScriptFaults(TypeScriptHarness):
    def setUp(self):
        super().setUp()
        self.sdk=pathlib.Path(os.environ['CODEANA_TYPESCRIPT_SDK']).resolve()
        self.old=self.index('--prepare','--full')[1]['revision']
        self.old_graph=canonical_graph(self,self.old)
        unit=self.catalog()['units'][0]
        native=next(a for a in unit['artifacts'] if a['path'].endswith('/inputs.json'))
        self.native=json.loads((self.project/native['path']).read_text())
        self.stop()

    @contextlib.contextmanager
    def inject(self,mode,memory=None,scratch=None):
        destination=self.root/('fault-'+mode)
        proof=fault_sdk(self.sdk,destination,self.home,mode)
        native=json.loads(json.dumps(self.native))
        native['compiler']['library_sha256']=sha(destination/'libcodeana_typescript.dylib')
        (self.project/'.code-ana/fault-inputs.json').write_text(json.dumps(native))
        (self.project/'.code-ana/inputs.json').unlink(missing_ok=True)
        self.config='schema_version=1\nkind="project"\n'
        for language in ('javascript','typescript'):
            self.config+=f'[languages.{language}]\nenabled=true\n[languages.{language}.discovery]\ninputs_manifest=".code-ana/fault-inputs.json"\n'
        self.config+='[[modules]]\nid="app"\nlanguage="typescript"\nroot="nested"\n'
        if memory is not None:
            self.config+='[resources.index]\nworker_memory_bytes='+str(memory)+'\n'
        if scratch is not None:
            self.config+='[resources.index]\ntypescript_result_bytes=65536\ntypescript_scratch_bytes='+str(scratch)+'\n'
        self.write_config()
        with patch.dict(os.environ,CODEANA_TYPESCRIPT_SDK=str(destination)):
            try:
                yield destination
            finally:
                self.stop()
                print(json.dumps({'native_fault':proof}),flush=True)

    def retained(self):
        self.assertEqual(self.cli('overview')[1]['revision'],self.old)
        self.assertEqual(canonical_graph(self,self.old),self.old_graph)
        self.assertFalse(list(self.home.glob('analysis-index-*')),'task scratch leaked')

    def unknown(self,result):
        account=self.cli('report',result['report'])[0][0]['analysis_resources']['coordinator']
        workers=[w for slot in account['languages'] for w in slot['workers']]
        self.assertEqual(len(workers),1)
        self.assertEqual(workers[0]['state'],'unknown')
        self.assertGreater(workers[0]['usage']['work'],0)

    def barrier(self,sdk):
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            marker=sdk/'entered'
            if marker.exists() and marker.stat().st_size==8:
                return struct.unpack('q',marker.read_bytes())[0]
            time.sleep(.02)
        self.fail('native analyze_start barrier not reached: '+self.daemon_log())

    def reaped(self,pid):
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            try:os.kill(pid,0)
            except ProcessLookupError:return
            time.sleep(.02)
        self.fail('owned native worker leaked: '+str(pid))

    def test_native_sigsegv_and_fatal_exit_preserve_head_and_unknown_watermark(self):
        for mode in ('crash','fatal'):
            with self.subTest(mode=mode),self.inject(mode) as sdk:
                _,failed=self.index('--full',code=1)
                self.reaped(self.barrier(sdk))
                self.unknown(failed)
                self.retained()

    def test_ack_failure_keeps_the_already_committed_revision_and_complete_usage(self):
        with self.inject('ack') as sdk:
            records,published=self.index('--full')
            self.assertEqual(published['publication'],'published')
            self.assertNotEqual(published['revision'],self.old)
            self.assertTrue(any(record.get('ack_warning') for record in records),records)
            self.reaped(self.barrier(sdk))
            self.assertEqual(self.cli('overview')[1]['revision'],published['revision'])
            account=self.cli('report',published['report'])[0][0]['analysis_resources']['coordinator']
            self.assertTrue(all(w['state']=='complete' for s in account['languages'] for w in s['workers']))
            self.assertEqual(canonical_graph(self,self.old),self.old_graph)
            self.assertTrue(self.cli('search-code','greet')[0])

    def test_real_go_cancellation_returns_a_known_final_receipt(self):
        self.a.write_text(self.a.read_text()+''.join(f'export function work_{i}() {{ return {i}; }}\n' for i in range(5000)))
        context=self.native['contexts'][0]
        world=next(f['world'] for f in context['files'] if f['role']=='publish')
        output=self.root/'large-inputs.json'
        command=[str(BIN/'codeana-typescript-worker'),'--sdk',str(self.sdk),
            '--prepare-project',str(self.script),'--prepare-config',str(self.script/'tsconfig.json'),
            '--output',str(output),'--scratch',str(self.root/'prepare-large'),'--unit',context['unit'],
            '--module','app','--world',world,'--allowed-root',str(self.project)]
        for language in context['publish_languages']:command+=['--publish-language',language]
        prepared=subprocess.run(command,capture_output=True,text=True,timeout=60)
        self.assertEqual(prepared.returncode,0,prepared.stdout+prepared.stderr)
        self.native=json.loads(output.read_text())
        with self.inject('observe') as sdk:
            task=self.cli_record('index','--full','--background')['task']
            pid=self.barrier(sdk)
            self.cli('task','cancel',task,raw=True)
            _,cancelled=self.cli('task','watch',task,code=130,timeout=15)
            self.reaped(pid)
            account=self.cli('report',cancelled['report'])[0][0]['analysis_resources']['coordinator']
            workers=[w for slot in account['languages'] for w in slot['workers']]
            self.assertEqual(workers[0]['state'],'failed',workers)
            self.assertEqual(workers[0]['usage']['failure'],'Cancelled',workers)
            self.retained()

    def test_rss_supervisor_kills_native_overrun_with_unknown_consumption(self):
        with self.inject('rss',memory=128 << 20) as sdk:
            _,failed=self.index('--full','--deadline-seconds','10',code=1)
            self.assertEqual(failed['code'],'WORKER_MEMORY_BUDGET',failed)
            self.reaped(self.barrier(sdk))
            self.unknown(failed)
            self.retained()

    def test_scratch_supervisor_kills_foreign_overrun_with_backend_identity(self):
        with self.inject('scratch',scratch=128 << 10) as sdk:
            _,failed=self.index('--full','--deadline-seconds','10',code=1)
            self.assertEqual(failed['code'],'TYPESCRIPT_SCRATCH_BUDGET',failed)
            self.reaped(self.barrier(sdk))
            self.unknown(failed)
            self.retained()

    def test_controller_disconnect_reaps_the_native_worker_and_recovers_old_head(self):
        with self.inject('hang') as sdk:
            self.cli_record('index','--full','--background')
            worker=self.barrier(sdk)
            daemon=json.loads((self.home/'endpoint.json').read_text())['pid']
            os.kill(daemon,signal.SIGKILL)
            self.reaped(worker)
            self.assertEqual(self.cli('overview')[1]['revision'],self.old)
            self.assertNotEqual(json.loads((self.home/'endpoint.json').read_text())['pid'],daemon)
            self.assertEqual(canonical_graph(self,self.old),self.old_graph)

    def test_stalled_native_cancellation_reaps_worker_before_input_release(self):
        with self.inject('hang') as sdk:
            task=self.cli_record('index','--full','--background')['task']
            pid=self.barrier(sdk)
            self.cli('task','cancel',task,raw=True)
            _,cancelled=self.cli('task','watch',task,code=130,timeout=15)
            self.reaped(pid)
            self.unknown(cancelled)
            self.retained()

    def test_stalled_native_deadline_keeps_unknown_usage_and_head(self):
        with self.inject('hang') as sdk:
            _,failed=self.index('--full','--deadline-seconds','5',code=124)
            self.assertIn(failed['code'],{'DEADLINE_EXCEEDED','TIMEOUT'})
            self.reaped(self.barrier(sdk))
            self.unknown(failed)
            self.retained()

    def test_real_go_archive_truncation_append_and_checksum_corruption_never_commit(self):
        for mode in ('truncate','append','corrupt'):
            with self.subTest(mode=mode),self.inject(mode) as sdk:
                _,failed=self.index('--full',code=1)
                self.reaped(self.barrier(sdk))
                account=self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
                workers=[w for slot in account['languages'] for w in slot['workers']]
                self.assertTrue(all(w['state']=='failed' and w['usage']['work']>0 for w in workers))
                self.retained()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=pathlib.Path)
    args=parser.parse_args()
    return run_cases(list(unittest.defaultTestLoader.loadTestsFromTestCase(TypeScriptFaults)),['typescript'],{},args.evidence)

if __name__=='__main__':
    raise SystemExit(main())
