#!/usr/bin/env python3
"""Crash recovery and publication accounting must preserve the user's fixture config."""
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from java_real_acceptance import (ConfigLease, atomic_bytes, atomic_json, process_identity,
                                  still_alive, publication_accepted, project_config, CompilerDiagnostics)


class AcceptanceRecovery(unittest.TestCase):
    def test_compiler_protection_is_explicit_and_not_the_task_deadline(self):
        import tomllib
        default = tomllib.loads(project_config(['TEST'], True, 'inputs.json').decode())
        self.assertEqual(default['resources']['index']['java_compiler_timeout_seconds'], 600)
        extended = tomllib.loads(project_config(['TEST'], True, 'inputs.json', compiler_timeout_seconds=3600).decode())
        self.assertEqual(extended['resources']['index']['java_compiler_timeout_seconds'], 3600)
        for value in (0, 3601, True, 1.5):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'JAVA_COMPILER_TIMEOUT_RANGE'):
                project_config(['TEST'], True, 'inputs.json', compiler_timeout_seconds=value)

    def test_thread_dump_never_attaches_to_unrelated_or_reused_process(self):
        diagnostics = CompilerDiagnostics(self.evidence)
        phase = dict(context='service@TEST', phase='compiler_model')
        with mock.patch('java_real_acceptance.time.monotonic', side_effect=[0, 0, 130]), \
             mock.patch('java_real_acceptance.still_alive', return_value=False), \
             mock.patch('java_real_acceptance.subprocess.run') as run:
            diagnostics.sample(phase, {123: ('/sdk/bin/java', dict(pid=123, identity='old'))})
            diagnostics.sample(phase, {123: ('/sdk/bin/java', dict(pid=123, identity='old'))})
            run.assert_not_called()
        diagnostics = CompilerDiagnostics(self.evidence)
        with mock.patch('java_real_acceptance.time.monotonic', side_effect=[0, 0, 130]), \
             mock.patch('java_real_acceptance.still_alive', return_value=True), \
             mock.patch('java_real_acceptance.subprocess.run', return_value=mock.Mock(stdout='java BusinessApp')) as run:
            diagnostics.sample(phase, {123: ('/sdk/bin/java', dict(pid=123, identity='new'))})
            diagnostics.sample(phase, {123: ('/sdk/bin/java', dict(pid=123, identity='new'))})
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0][0], 'ps')

    def test_explicit_disk_budgets_preserve_defaults_and_reject_out_of_range(self):
        import tomllib
        default = tomllib.loads(project_config(['MAIN'], False, 'inputs.json').decode())
        self.assertEqual(default['resources']['index']['java_result_bytes'], 4294967296)
        self.assertEqual(default['resources']['index']['java_scratch_bytes'], 17179869184)
        large = tomllib.loads(project_config(['MAIN', 'TEST'], True, 'inputs.json',
                                            result_bytes=8589934592, scratch_bytes=34359738368).decode())
        self.assertEqual(large['resources']['index']['java_result_bytes'], 8589934592)
        self.assertEqual(large['resources']['index']['java_scratch_bytes'], 34359738368)
        self.assertEqual(large['resources']['index']['worker_memory_bytes'], 1610612736)
        for kwargs, reason in ((dict(result_bytes=8589934593), 'JAVA_RESULT_BYTES_RANGE'),
                               (dict(result_bytes=65535), 'JAVA_RESULT_BYTES_RANGE'),
                               (dict(scratch_bytes=34359738369), 'JAVA_SCRATCH_BYTES_RANGE')):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(ValueError, reason):
                project_config(['MAIN'], False, 'inputs.json', **kwargs)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.project = self.root / 'project'
        (self.project / '.code-ana').mkdir(parents=True)
        self.config = self.project / '.code-ana/config.toml'
        self.config.write_bytes(b'original\n')
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.lease = ConfigLease(self.project, self.evidence)
        self.addCleanup(self.release_lock)

    def release_lock(self):
        if self.lease.lock is not None:
            self.lease.lock.close()
            self.lease.lock = None

    def abandon(self):
        # Simulate a dead harness without running its finally block.
        self.lease.state['processes']['harness'] = None
        atomic_json(self.lease.journal, self.lease.state)
        self.release_lock()
        self.lease.state = None
        next_evidence = self.root / 'next'
        next_evidence.mkdir()
        return ConfigLease(self.project, next_evidence)

    def test_normal_cleanup_restores_exact_original(self):
        self.lease.acquire()
        self.lease.apply(b'applied\n')
        self.assertEqual(self.config.read_bytes(), b'applied\n')
        self.lease.close()
        self.assertEqual(self.config.read_bytes(), b'original\n')
        self.assertFalse(self.lease.journal.exists())

    def test_native_heap_is_explicit_and_within_product_bounds(self):
        import tomllib
        default = tomllib.loads(project_config(['MAIN', 'TEST'], True, 'inputs.json').decode())
        larger = tomllib.loads(project_config(['MAIN', 'TEST'], True, 'inputs.json', 1024 * 1024 * 1024, 2147483648).decode())
        self.assertEqual(default['resources']['index']['java_native_heap_bytes'], 536870912)
        self.assertEqual(larger['resources']['index']['java_native_heap_bytes'], 1073741824)
        self.assertEqual(larger['resources']['index']['worker_memory_bytes'], 2147483648)
        for heap in (0, 7 * 1024 * 1024, 1024 * 1024 * 1024 + 1, 536870913):
            with self.subTest(heap=heap), self.assertRaisesRegex(ValueError, 'JAVA_NATIVE_HEAP_RANGE'):
                project_config(['MAIN'], False, 'inputs.json', heap)
        for worker in (1610612736, 2147483649):
            with self.subTest(worker=worker), self.assertRaisesRegex(ValueError, 'JAVA_PARALLELISM_MEMORY_BUDGET'):
                project_config(['MAIN'], False, 'inputs.json', 1073741824, worker)

    def test_partial_publication_does_not_require_complete_semantics(self):
        report = dict(published=True, exit_code=0, query_exit_code=0,
                      catalog_commits=[['task', 'revision']], catalog_heads=[['target', 'revision']],
                      semantic_index_accepted=False)
        self.assertTrue(publication_accepted(report))
        self.assertFalse(report['semantic_index_accepted'])
        for field, failure in [('published', False), ('exit_code', 1), ('query_exit_code', 1),
                               ('catalog_commits', []), ('catalog_heads', [])]:
            with self.subTest(field=field):
                self.assertFalse(publication_accepted(report | {field: failure}))

    def test_aborted_harness_recovers_backup_and_preserves_evidence(self):
        self.lease.acquire()
        self.lease.apply(b'applied\n')
        recovery = self.abandon()
        recovery.acquire()
        try:
            self.assertEqual(self.config.read_bytes(), b'original\n')
            self.assertTrue((recovery.evidence / 'recovered-run.json').is_file())
            self.assertEqual((self.evidence / 'project-config.applied.toml').read_bytes(), b'applied\n')
        finally:
            recovery.close()

    def test_write_ahead_recovery_before_config_replacement(self):
        self.lease.acquire()
        self.lease.apply(b'applied\n')
        atomic_bytes(self.config, b'original\n')
        recovery = self.abandon()
        recovery.acquire()
        recovery.close()
        self.assertEqual(self.config.read_bytes(), b'original\n')

    def test_user_change_is_never_overwritten(self):
        self.lease.acquire()
        self.lease.apply(b'applied\n')
        atomic_bytes(self.config, b'user change\n')
        recovery = self.abandon()
        with self.assertRaisesRegex(ValueError, 'RESTORE_CONFLICT'):
            recovery.acquire()
        self.assertEqual(self.config.read_bytes(), b'user change\n')
        self.assertTrue(recovery.journal.exists())

    def test_changed_backup_is_rejected(self):
        self.lease.acquire()
        self.lease.apply(b'applied\n')
        recovery = self.abandon()
        (self.evidence / 'project-config.original.toml').write_bytes(b'changed backup\n')
        with self.assertRaisesRegex(ValueError, 'BACKUP_CHANGED'):
            recovery.acquire()
        self.assertEqual(self.config.read_bytes(), b'applied\n')

    def test_parallel_harness_cannot_change_config(self):
        self.lease.acquire()
        second = ConfigLease(self.project, self.evidence)
        with self.assertRaises(BlockingIOError):
            second.acquire()
        self.assertEqual(self.config.read_bytes(), b'original\n')

    def test_live_owned_process_blocks_recovery_without_killing_it(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        try:
            self.lease.acquire()
            self.lease.apply(b'applied\n')
            self.lease.process('daemon', child.pid)
            recovery = self.abandon()
            with self.assertRaisesRegex(RuntimeError, 'PROCESS_ALIVE'):
                recovery.acquire()
            self.assertIsNone(child.poll())
            self.assertEqual(self.config.read_bytes(), b'applied\n')
            child.terminate()
            child.wait(timeout=5)
            recovery.acquire()
            recovery.close()
            self.assertEqual(self.config.read_bytes(), b'original\n')
        finally:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)

    def test_pid_reuse_does_not_look_like_live_owned_process(self):
        identity = process_identity(os.getpid())
        self.assertTrue(still_alive(identity))
        identity['identity'] = 'another process start time'
        self.assertFalse(still_alive(identity))

    def test_process_lookup_failure_cannot_authorize_recovery(self):
        self.lease.acquire()
        self.lease.apply(b'applied\n')
        self.lease.state['processes']['daemon'] = process_identity(os.getpid())
        recovery = self.abandon()
        denied = subprocess.CompletedProcess(['ps'], 1, '', 'ps: Operation not permitted')
        with mock.patch('java_real_acceptance.subprocess.run', return_value=denied):
            with self.assertRaisesRegex(RuntimeError, 'PROCESS_IDENTITY_UNAVAILABLE'):
                recovery.acquire()
        self.assertEqual(self.config.read_bytes(), b'applied\n')
        self.assertTrue(recovery.journal.exists())

    def test_absent_process_and_empty_lookup_are_distinct(self):
        absent = subprocess.CompletedProcess(['ps'], 1, '', '')
        with mock.patch('java_real_acceptance.subprocess.run', return_value=absent):
            self.assertIsNone(process_identity(12345))
        empty = subprocess.CompletedProcess(['ps'], 0, '', '')
        with mock.patch('java_real_acceptance.subprocess.run', return_value=empty):
            with self.assertRaisesRegex(RuntimeError, 'PROCESS_IDENTITY_UNAVAILABLE'):
                process_identity(12345)

    def test_real_sigkill_leaves_durable_receipt_and_recoverable_journal(self):
        script = '''import pathlib,sys,time
from java_real_acceptance import ConfigLease,atomic_json
project,evidence=map(pathlib.Path,sys.argv[1:])
lease=ConfigLease(project,evidence);lease.acquire();lease.apply(b"applied\\n")
atomic_json(evidence/"receipt.json",dict(state="running",semantic_index_accepted=False))
print("ready",flush=True)
time.sleep(60)
'''
        child = subprocess.Popen([sys.executable, '-c', script, str(self.project), str(self.evidence)],
                                 cwd=pathlib.Path(__file__).parent, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(), 'ready')
            child.send_signal(signal.SIGKILL)
            self.assertEqual(child.wait(timeout=5), -signal.SIGKILL)
            receipt = json.loads((self.evidence / 'receipt.json').read_text())
            self.assertEqual(receipt['state'], 'running')
            self.assertFalse(receipt['semantic_index_accepted'])
            self.lease.acquire()
            self.assertEqual(self.config.read_bytes(), b'original\n')
            self.lease.close()
        finally:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)
            child.stdout.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
