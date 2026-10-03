#!/usr/bin/env python3
"""Isolated CLI -> gRPC -> mock process -> SQLite acceptance scenarios."""
import json
import contextlib
import os
import pathlib
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
BIN = pathlib.Path(os.environ.get('CODEANA_TEST_BIN', ROOT / 'target/debug'))
FIX = pathlib.Path(os.environ.get('CODEANA_TEST_FIXTURES', ROOT / 'tests/fixtures'))
EXPECTED = json.loads((FIX / 'expected.json').read_text())


class Product(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='codeana-e2e-')
        self.root = pathlib.Path(self.temp.name)
        self.home = self.root / 'home'
        self.project = self.root / 'project'
        shutil.copytree(FIX / 'java/base', self.project)
        self.daemon = None
        self.log = None
        self.addCleanup(self.cleanup)
        self.start()

    def cleanup(self):
        self.stop()
        self.temp.cleanup()

    def start(self, fault=None):
        self.log = open(self.root / 'daemon.log', 'a')
        env = os.environ.copy()
        env.pop('CODEANA_TEST_FAULT', None)
        if fault:
            env['CODEANA_TEST_FAULT'] = fault
        self.daemon = subprocess.Popen(
            [str(BIN / 'codeana-daemon'), '--home', str(self.home),
             '--dev-mock-fixtures', str(FIX / 'mock.json')],
            stdout=self.log, stderr=self.log, env=env)
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if self.daemon.poll() is not None:
                raise RuntimeError(self.daemon_log())
            if (self.home / 'endpoint.json').exists():
                p = subprocess.run(
                    [str(BIN / 'codeana-cli'), '--home', str(self.home), 'daemon', 'status'],
                    capture_output=True, timeout=3)
                if p.returncode == 0:
                    return
            time.sleep(.03)
        raise RuntimeError('daemon start timeout\n' + self.daemon_log())

    def stop(self, kill=False):
        if self.daemon is not None:
            if self.daemon.poll() is None:
                self.daemon.kill() if kill else self.daemon.terminate()
            try:
                self.daemon.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.daemon.kill()
                self.daemon.wait(timeout=5)
            self.daemon = None
        if self.log is not None:
            self.log.close()
            self.log = None

    def daemon_log(self):
        path = self.root / 'daemon.log'
        return path.read_text() if path.exists() else ''

    def cli(self, *args, code=0, raw=False, timeout=30):
        p = subprocess.run(
            [str(BIN / 'codeana-cli'), '--home', str(self.home),
             '--project', str(self.project), *map(str, args)],
            capture_output=True, text=True, timeout=timeout)
        self.assertEqual(p.returncode, code,
                         f'args={args}\nstdout={p.stdout}\nstderr={p.stderr}\n{self.daemon_log()}')
        if raw:
            return p.stdout if code == 0 else p.stdout + p.stderr
        records, terminal = [], None
        for line in p.stdout.splitlines():
            if line.startswith('record '):
                records.append(json.loads(line[7:]))
            if line.startswith('terminal '):
                terminal = json.loads(line[9:])
        if code != 0 and terminal is None:
            return [], {'message': p.stderr, 'code': p.returncode}
        self.assertIsNotNone(terminal, p.stdout + p.stderr)
        return records, terminal

    def state(self, name):
        origin = FIX / 'java' / name
        for p in origin.rglob('*'):
            if p.is_file():
                dest = self.project / p.relative_to(origin)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(p, dest)

    def facts(self, revision):
        with contextlib.closing(sqlite3.connect(self.home / 'revisions' / revision / 'facts.db')) as db:
            return [json.loads(row[0]) for row in db.execute('SELECT body FROM facts ORDER BY id')]

    def task(self, task_id):
        return json.loads(self.cli('task', 'status', task_id, raw=True))

    def wait_task(self, task_id, state='running'):
        until = time.monotonic() + 10
        while time.monotonic() < until:
            status = self.task(task_id)
            if status['state'] == state:
                return status
            time.sleep(.03)
        self.fail(f'task {task_id} did not reach {state}: {self.task(task_id)}')

    def require_debug_faults(self):
        if BIN.resolve() != (ROOT / 'target/debug').resolve():
            self.skipTest('fault points exist only in debug mock binary')

    def test_explicit_long_deadline_indexes_and_rejects_over_ceiling(self):
        rows, terminal = self.cli('index', '--deadline-seconds', 7200)
        self.assertEqual((rows[0]['mode'], terminal['publication']), ('full', 'published'))
        self.assertEqual(self.cli('calls', 'main')[0], EXPECTED['base']['calls'])
        _, rejected = self.cli('index', '--deadline-seconds', 86401, code=1)
        self.assertIn('DEADLINE_BUDGET', rejected['message'])
        self.assertEqual(self.cli('calls', 'main')[0], EXPECTED['base']['calls'])

    def test_query_oracles_and_boundaries(self):
        rows, terminal = self.cli('index')
        self.assertEqual((rows[0]['mode'], terminal['publication'], terminal['producer']),
                         ('full', 'published', 'mock-fixture-v1'))
        self.assertEqual(self.cli('calls', 'main')[0], EXPECTED['base']['calls'])
        self.assertEqual([r['id'] for r in self.cli('call-sites', 'main')[0]],
                         EXPECTED['base']['call_sites'])
        self.assertEqual([r['id'] for r in self.cli('references', 'greet')[0]], ['ref1'])
        self.assertEqual(self.cli('source', 'greet')[0][0]['text'],
                         'String greet() { return "hello"; }')
        self.assertEqual(self.cli('resolve', 'greet', '--unique')[0][0]['id'], 'greet')
        self.assertEqual(self.cli('overview')[0][0]['counts'], EXPECTED['base']['counts'])
        dispatch, evidence = self.cli('dispatch', 'site1')
        self.assertEqual([(r['target'], r['proof'], r['possible']) for r in dispatch],
                         [('greet', ['target1'], True)])
        self.assertFalse(evidence['evidence']['semantic'])
        self.assertIn('open_world_dispatch', evidence['evidence']['reasons'])
        for command, selector in [('structure', 'app'), ('annotations', 'app'),
                                  ('type-relations', 'service'),
                                  ('callable-relations', 'service_method'),
                                  ('runtime-implementations', 'greeting'),
                                  ('trace', 'main'), ('search-text', 'hello')]:
            self.assertTrue(self.cli(command, selector)[0], command)
        self.cli('source', 'generated', code=1)
        self.assertFalse(self.cli('overview', '--scope', 'TEST')[1]['evidence']['semantic'])
        all_rows = self.cli('search-code')[0]
        page, terminal = self.cli('search-code', '--max-records', '2')
        collected = list(page)
        while terminal['cursor']:
            page, terminal = self.cli('search-code', '--max-records', '2', '--cursor', terminal['cursor'])
            collected.extend(page)
        self.assertEqual(collected, all_rows)
        self.assertEqual(len({r['id'] for r in collected}), len(collected))
        self.cli('search-code', '--max-records', '10001', code=1)
        self.cli('structure', 'app', '--depth', '33', code=1)
        self.cli('search-code', '--max-output-bytes', '1024', code=1)
        bounded = self.cli('search-code', '--max-output-bytes', '2048', raw=True)
        self.assertLessEqual(len(bounded.encode()), 2048)
        self.assertIn('terminal ', bounded)
        self.cli('search-code', '--cursor', 'invalid', code=1)

    def test_incremental_history_and_diff_regression(self):
        base = self.cli('index')[1]['revision']
        self.cli('snapshot', 'before', '--name', 'analysis')
        _, page = self.cli('search-code', '--max-records', '2')
        frozen_revision = page['revision']
        self.state('edited')
        self.cli('source', 'greet', code=1)
        self.assertIn('hello', self.cli('source', 'greet', '--revision', base)[0][0]['text'])
        delta_rows, delta_term = self.cli('index')
        self.assertEqual(delta_rows[0]['mode'], 'delta')
        self.assertEqual([r['id'] for r in self.cli('call-sites', 'main')[0]], ['site1'])
        self.assertEqual(self.cli('calls', 'main')[0], EXPECTED['edited']['calls'])
        frozen, frozen_term = self.cli('search-code', '--max-records', '2', '--cursor', page['cursor'])
        self.assertEqual(frozen_term['revision'], frozen_revision)
        self.assertTrue(frozen)
        self.cli('search-code', 'other', '--cursor', page['cursor'], code=1)
        full = self.cli('index', '--full')[1]['revision']
        self.assertEqual(self.facts(delta_term['revision']), self.facts(full))
        self.cli('snapshot', 'after', '--name', 'analysis')
        self.assertTrue(self.cli('diff', '--base', 'before', '--target', 'after')[0])
        self.assertTrue(self.cli('diff', '--base', 'before', '--target', 'after', '--name', 'relations')[0])
        self.cli('diff', '--base', 'before', '--target', 'after', '--name', 'declarations')
        self.cli('diff', '--base', 'before', '--target', 'after', '--name', 'impact')

    def test_pipeline_annotations_and_reports(self):
        first = self.cli('index')[1]
        pipeline = self.root / 'pipeline.json'
        pipeline.write_text(json.dumps([{'operation': 'resolve', 'selector': 'main'},
                                        {'operation': 'call-sites'}]))
        self.assertEqual([r['id'] for r in self.cli('pipeline', pipeline)[0]], ['site1', 'site2'])
        pipeline.write_text(json.dumps([{'operation': 'source', 'selector': 'main'},
                                        {'operation': 'calls'}]))
        self.cli('pipeline', pipeline, code=1)
        pipeline.write_text(json.dumps([{'operation': 'resolve', 'selector': 'main'}] * 17))
        self.cli('pipeline', pipeline, code=1)
        note = self.cli('annotate', 'app', '--name', 'add', '--text', 'reviewed')[0][0]
        self.cli('annotate', '--name', 'update', '--base', note['id'],
                 '--expected-revision', '1', '--text', 'updated')
        self.cli('annotate', '--name', 'update', '--base', note['id'],
                 '--expected-revision', '1', '--text', 'stale', code=1)
        listed = self.cli('annotate', '--name', 'list')[0][0][0]
        self.assertEqual((listed['binding'], listed['text']), ('bound', 'updated'))
        self.cli('annotate', '--name', 'remove', '--base', note['id'], '--expected-revision', '2')
        self.state('edited')
        second = self.cli('index')[1]
        self.assertNotEqual(first['report'], second['report'])
        self.assertEqual(self.cli('report', second['report'])[0][0]['revision'], second['revision'])
        comparison = self.cli('report', second['report'], '--base', first['report'])[0][0]
        self.assertEqual(comparison['delta']['facts'], -2)
        exported = self.root / 'report.txt'
        self.cli('report', second['report'], '--export', exported, raw=True)
        contents = exported.read_bytes()
        self.assertTrue(contents.startswith(b'codeana-text-v1'))
        self.cli('report', second['report'], '--export', exported, code=1, raw=True)
        self.assertEqual(exported.read_bytes(), contents)

    def test_worker_errors_cancel_and_idempotency(self):
        good = self.cli('index')[1]['revision']
        for state in ['crash', 'wrong-sequence', 'bad-checksum', 'missing-end', 'oversize']:
            with self.subTest(state=state):
                self.state(state)
                self.cli('index', code=1)
                self.assertEqual(self.cli('overview')[1]['revision'], good)
        self.state('hang')
        self.cli('index', '--deadline-seconds', '1', code=124)
        task_id = json.loads(self.cli('index', '--background', raw=True))['task_id']
        self.wait_task(task_id)
        self.cli('task', 'cancel', task_id, raw=True)
        self.cli('task', 'watch', task_id, code=130)
        self.state('partial')
        self.cli('index', '--strict', code=3)
        self.assertEqual(self.cli('overview')[1]['revision'], good)
        self.state('advice')
        advice = self.cli('index', code=1, raw=True)
        self.assertIn('BUILD_INPUT_REQUIRED', advice)
        self.assertEqual(self.cli('overview')[1]['revision'], good)
        self.state('replan')
        self.assertEqual(self.cli('index')[0][0]['mode'], 'full')
        self.state('base')
        first = self.cli('index', '--request-id', 'repeat')[1]
        second = self.cli('index', '--request-id', 'repeat')[1]
        self.assertEqual(first['revision'], second['revision'])
        self.cli('index', '--request-id', 'repeat', '--full', code=1)

    def test_queue_freeze_restart_and_auth(self):
        self.cli('index')
        self.state('hang')
        blocking = json.loads(self.cli('index', '--background', raw=True))['task_id']
        self.wait_task(blocking)
        self.state('base')
        queued = json.loads(self.cli('index', '--background', raw=True))['task_id']
        (self.project / 'src/App.java').write_text('changed while queued')
        self.cli('task', 'cancel', blocking, raw=True)
        published = self.cli('task', 'watch', queued)[1]
        self.assertEqual(published['publication'], 'published')
        self.assertIn('hello', self.cli('source', 'greet', '--revision', published['revision'])[0][0]['text'])
        endpoint = self.home / 'endpoint.json'
        saved = endpoint.read_bytes()
        bad = json.loads(saved)
        bad['token'] = 'invalid'
        endpoint.write_text(json.dumps(bad))
        try:
            self.cli('daemon', 'status', code=1, raw=True)
        finally:
            endpoint.write_bytes(saved)
        second = subprocess.run([str(BIN / 'codeana-daemon'), '--home', str(self.home),
                                 '--dev-mock-fixtures', str(FIX / 'mock.json')],
                                capture_output=True, text=True, timeout=5)
        self.assertIn('DAEMON_ALREADY_RUNNING', second.stderr)
        self.state('hang')
        interrupted = json.loads(self.cli('index', '--background', raw=True))['task_id']
        self.wait_task(interrupted)
        self.stop(kill=True)
        self.start()
        self.assertEqual(self.task(interrupted)['terminal']['code'], 'DAEMON_RESTARTED')
        self.assertEqual(self.cli('overview')[1]['revision'], published['revision'])

    def test_queue_limit(self):
        self.state('hang')
        tasks = []
        try:
            for i in range(8):
                tasks.append(json.loads(self.cli('index', '--background',
                                                 '--request-id', f'queue-{i}', raw=True))['task_id'])
            self.wait_task(tasks[0])
            self.assertIn('QUEUE_BUDGET', self.cli('index', '--background',
                                                    '--request-id', 'queue-overflow', code=1, raw=True))
        finally:
            for task_id in tasks:
                self.cli('task', 'cancel', task_id, raw=True)

    def test_watch_reconnect_after_client_disconnect(self):
        self.state('hang')
        task_id = json.loads(self.cli('index', '--background', raw=True))['task_id']
        self.wait_task(task_id)
        watcher = subprocess.Popen([str(BIN / 'codeana-cli'), '--home', str(self.home),
                                    '--project', str(self.project), 'task', 'watch', task_id],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertIsNone(watcher.poll())
            resume_after = self.task(task_id)['last_seq']
        finally:
            watcher.terminate()
            watcher.communicate(timeout=5)
        self.cli('task', 'cancel', task_id, raw=True)
        _, terminal = self.cli('task', 'watch', task_id, '--after-seq', resume_after, code=130)
        self.assertEqual(terminal['code'], 'CANCELLED')
        self.assertEqual(self.task(task_id)['state'], 'terminal')
        self.assertIn('EVENT_CURSOR_AHEAD', self.cli('task', 'watch', task_id,
                         '--after-seq', self.task(task_id)['last_seq'] + 1, code=1, raw=True))

    def test_pipeline_and_annotation_while_publication_waits(self):
        self.require_debug_faults()
        old = self.cli('index')[1]['revision']
        self.stop()
        self.state('edited')
        self.start('pause:before_publish')
        task_id = json.loads(self.cli('index', '--background', raw=True))['task_id']
        marker = self.home / 'test-fault-reached'
        until = time.monotonic() + 10
        while not marker.exists() and time.monotonic() < until:
            time.sleep(.02)
        self.assertEqual(marker.read_text(), 'before_publish')
        pipeline = self.root / 'pipeline.json'
        pipeline.write_text(json.dumps([{'operation': 'resolve', 'selector': 'main'},
                                        {'operation': 'call-sites'}]))
        try:
            rows, terminal = self.cli('pipeline', pipeline)
            self.assertEqual(terminal['revision'], old)
            self.assertEqual([r['id'] for r in rows], ['site1', 'site2'])
            note = self.cli('annotate', 'app', '--name', 'add', '--text', 'during-index')[0][0]
            self.assertEqual(note['anchor']['revision'], old)
            self.assertEqual(self.cli('overview')[1]['revision'], old)
        finally:
            (self.home / 'test-fault-release').write_text('continue')
        published = self.cli('task', 'watch', task_id)[1]
        self.assertEqual(published['publication'], 'published')
        self.assertNotEqual(published['revision'], old)
        rows, terminal = self.cli('pipeline', pipeline)
        self.assertEqual(terminal['revision'], published['revision'])
        self.assertEqual([r['id'] for r in rows], ['site1'])
        self.assertEqual(self.cli('annotate', '--name', 'list')[0][0][0]['text'], 'during-index')

    def test_files_git_gc_and_production_mode(self):
        self.cli('index')
        self.cli('snapshot', 'before', '--name', 'analysis')
        self.cli('gc')
        self.cli('gc', '--execute')
        self.assertIn('hello', self.cli('source', 'greet', '--revision', 'snapshot:before')[0][0]['text'])
        self.cli('snapshot', 'files', '--name', 'files')
        self.assertIn('hello', self.cli('source', 'src/App.java', '--revision', 'snapshot:files')[0][0]['text'])
        self.cli('calls', 'main', '--revision', 'snapshot:files', code=1)
        original = self.project
        self.project = self.root / 'git-project'
        try:
            shutil.copytree(FIX / 'java/base', self.project)
            large = '😀\n\n' * 3000
            (self.project / 'src/Large.txt').write_text(large)
            def git(*args):
                subprocess.run(['git', '-C', str(self.project), '-c', 'core.hooksPath=/dev/null',
                                '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                *args], check=True, capture_output=True)
            git('init')
            git('add', '.')
            git('commit', '-m', 'fixture')
            self.cli('snapshot', 'commit', '--name', 'files', '--target', 'HEAD')
            chunks = self.cli('source', 'src/Large.txt', '--revision', 'snapshot:commit')[0]
            self.assertEqual(''.join(r['text'] for r in chunks), large)
            (self.project / 'src/App.java').write_text('changed after capture')
            shutil.rmtree(self.project)
            self.assertIn('hello', self.cli('source', 'src/App.java',
                                            '--revision', 'snapshot:commit')[0][0]['text'])
        finally:
            self.project = original
        self.project = self.root / 'files-only'
        try:
            self.project.mkdir()
            (self.project / '.code-ana').mkdir()
            (self.project / '.code-ana/config.toml').write_text(
                'schema_version=1\nkind="project"\n[scan]\ninclude=["**"]\n'
                'text_only=["**/*.txt"]\n[search.text]\ninclude=["**/*.txt"]\n')
            (self.project / 'note.txt').write_text('needle in text_only\n')
            (self.project / 'readme.md').write_text('captured file\n')
            self.assertEqual(self.cli('doctor')[0][0]['capabilities']['java_index'], 'unavailable')
            self.cli('snapshot', 'plainfiles', '--name', 'files')
            self.assertEqual([r['path'] for r in self.cli('search-text', 'needle')[0]], ['note.txt'])
            self.assertNotIn('note.txt', [r['path'] for r in self.cli('snapshot-list')[0][0]['manifest']['files']])
            self.cli('index', code=1)
        finally:
            self.project = original
        self.home = self.root / 'normal-home'
        try:
            self.assertEqual(self.cli('hello', raw=True).strip(), 'Hello, world!')
            self.cli('index', code=1)
        finally:
            subprocess.run([str(BIN / 'codeana-cli'), '--home', str(self.home), 'daemon', 'stop'],
                           capture_output=True, timeout=5)
            self.home = self.root / 'home'

    def assert_crash_boundary(self, point, committed):
        self.require_debug_faults()
        old = self.cli('index')[1]['revision']
        self.stop()
        self.state('edited')
        self.start(f'crash:{point}')
        task_id = json.loads(self.cli('index', '--background', raw=True))['task_id']
        self.assertEqual(self.daemon.wait(timeout=10), 75, self.daemon_log())
        self.stop()
        self.start()
        head = self.cli('overview')[1]['revision']
        status = self.task(task_id)
        if committed:
            self.assertNotEqual(head, old)
            self.assertEqual(status['terminal']['publication'], 'published')
            self.assertEqual(status['terminal']['revision'], head)
        else:
            self.assertEqual(head, old)
            self.assertEqual(status['terminal']['code'], 'DAEMON_RESTARTED')
        self.assertFalse(list((self.home / 'candidates').iterdir()))
        self.assertEqual(len(list((self.home / 'revisions').iterdir())), 2 if committed else 1)

    def test_candidate_sync_crash(self):
        self.assert_crash_boundary('candidate_synced', False)

    def test_revision_rename_crash(self):
        self.assert_crash_boundary('revision_renamed', False)

    def test_catalog_commit_crash(self):
        self.assert_crash_boundary('catalog_committed', True)

    def test_report_write_failure_after_publication(self):
        self.require_debug_faults()
        old = self.cli('index')[1]['revision']
        self.stop()
        self.state('edited')
        self.start('error:report_write')
        _, terminal = self.cli('index', code=1)
        self.assertEqual((terminal['publication'], terminal['code'], terminal['report']),
                         ('published', 'REPORT_WRITE_FAILED', 'pending'))
        self.assertNotEqual(terminal['revision'], old)
        self.assertEqual(self.cli('overview')[1]['revision'], terminal['revision'])

    def test_migration_failure_rolls_back(self):
        self.require_debug_faults()
        self.stop()
        db_path = self.home / 'annotations.db'
        db_path.unlink()
        with sqlite3.connect(db_path) as db:
            db.executescript("CREATE TABLE annotations(id TEXT PRIMARY KEY,project TEXT NOT NULL,"
                             "symbol TEXT NOT NULL,body TEXT NOT NULL,version INTEGER NOT NULL);"
                             "INSERT INTO annotations VALUES('note_1','p','symbol','saved',3);"
                             "PRAGMA user_version=1;")
        env = os.environ.copy()
        env['CODEANA_TEST_FAULT'] = 'error:migration'
        failed = subprocess.run([str(BIN / 'codeana-daemon'), '--home', str(self.home),
                                 '--dev-mock-fixtures', str(FIX / 'mock.json')],
                                env=env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn('TEST_FAULT:migration', failed.stderr)
        with sqlite3.connect(db_path) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 1)
            self.assertEqual(db.execute('SELECT body FROM annotations').fetchone()[0], 'saved')
            self.assertNotIn('anchor', [r[1] for r in db.execute('PRAGMA table_info(annotations)')])
        self.start()
        with sqlite3.connect(db_path) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT body FROM annotations').fetchone()[0], 'saved')


if __name__ == '__main__':
    unittest.main(verbosity=2)
