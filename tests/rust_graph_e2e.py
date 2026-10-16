#!/usr/bin/env python3
"""JDK-free product checks for the typed Anatomist graph contract."""
import contextlib,hashlib,json,os,sqlite3,subprocess,time,unittest
from product_support import BIN,FIX
import product_support
from java_graph_storage import GraphStorage

class GraphProduct(product_support.MockProductHarness):
    canonical=GraphStorage.canonical
    def test_typed_graph_delta_history_and_pagination(self):
        first=self.cli('index')[1]['revision'];before=self.canonical(first)
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/first/'facts.db')) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM call_sites').fetchone()[0],2)
            self.assertEqual(db.execute('SELECT count(*) FROM call_site_targets').fetchone()[0],2)
        self.assertTrue(self.cli('structure','app')[0]);self.assertTrue(self.cli('annotations','app')[0])
        self.cli('snapshot','before','--name','analysis')
        self.state('edited');delta=self.cli('index')[1]['revision'];changed=self.canonical(delta)
        full=self.cli('index','--full')[1]['revision'];self.assertEqual(changed,self.canonical(full));self.assertEqual(before,self.canonical(first))
        self.cli('snapshot','after','--name','analysis');self.assertTrue(self.cli('diff','--base','before','--target','after')[0])
        self.assertTrue(self.cli('calls','main')[0])
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

    def test_historical_text_search_is_independent_of_active_workspace(self):
        revision = self.cli('index')[1]['revision']
        snapshot_revision = self.cli('snapshot', 'before', '--name', 'analysis')[1]['revision']
        args = ['search-text', 'hello', '--path', 'src/App.java']
        frozen, terminal = self.cli(*args, '--revision', revision)
        self.assertTrue(frozen)
        self.assertEqual(terminal['revision'], revision)
        config = self.project / '.code-ana/config.toml'
        original_config = config.read_bytes()
        config.write_text('[invalid TOML\n')
        (self.project / 'src/App.java').write_text('active workspace changed\n')
        for selector, expected_revision in ((revision, revision), ('snapshot:before', snapshot_revision)):
            rows, terminal = self.cli(*args, '--revision', selector)
            self.assertEqual(rows, frozen)
            self.assertEqual(terminal['revision'], expected_revision)
        self.cli(*args, code=1)
        config.write_bytes(original_config)
        rows, _ = self.cli('search-text', 'active workspace changed', '--path', 'src/App.java')
        self.assertEqual([row['text'] for row in rows], ['active workspace changed'])

    def test_old_schema_requires_full_rebuild(self):
        first=self.cli('index')[1]['revision'];path=self.home/'revisions'/first/'facts.db'
        with sqlite3.connect(path) as db:db.execute('PRAGMA user_version=5')
        self.assertIn('GRAPH_SCHEMA_REBUILD_REQUIRED',self.cli('overview','--revision',first,code=1)[1]['message'])
        self.assertIn('GRAPH_SCHEMA_REBUILD_REQUIRED',self.cli('index',code=1)[1]['message'])
        second=self.cli('index','--full')[1]['revision'];self.assertNotEqual(first,second);self.canonical(second)

    def test_regex_case_path_context_and_frozen_pages(self):
        source = self.project / 'src/Case.java'
        original = 'before\r\nHello 中文😀\r\nafter\r\nHELLO second\r\nend\r\n'.encode()
        source.write_bytes(original)
        other = self.project / 'other/Case.java'
        other.parent.mkdir()
        other.write_text('Hello outside the selected path\n')
        argv = ['search-text', r'hello\b', '--regex', '--ignore-case', '--context', '1',
                '--path', 'src/Case.java', '--max-records', '1']
        records, terminal = self.cli(*argv)
        self.assertEqual(len(records), 1)
        self.assertTrue(terminal['cursor'])
        source.write_text('active bytes changed after the first page\n')
        while terminal['cursor']:
            page, terminal = self.cli(*argv, '--cursor', terminal['cursor'])
            records.extend(page)
        self.assertEqual([r['line'] for r in records], [2, 4])
        self.assertEqual([r['text'] for r in records], ['Hello 中文😀', 'HELLO second'])
        self.assertEqual({r['path'] for r in records}, {'src/Case.java'})
        self.assertEqual({r['hash'] for r in records}, {hashlib.sha256(original).hexdigest()})
        self.assertEqual(records[0]['context_before'], [{'line': 1, 'text': 'before'}])
        self.assertEqual(records[0]['context_after'], [{'line': 3, 'text': 'after'}])
        self.assertEqual(records[1]['context_before'], [{'line': 3, 'text': 'after'}])
        self.assertEqual(records[1]['context_after'], [{'line': 5, 'text': 'end'}])
        source.write_bytes(original)
        literal, _ = self.cli('search-text', 'hello', '--path', 'src/Case.java')
        self.assertEqual(literal, [])
        folded, _ = self.cli('search-text', 'hello', '--ignore-case', '--path', 'src/Case.java')
        self.assertEqual([r['line'] for r in folded], [2, 4])
        _, invalid = self.cli('search-text', '[', '--regex', code=1)
        self.assertIn('TEXT_REGEX_INVALID', invalid['message'])
        _, rejected = self.cli('search-text', 'hello', '--context', '101', code=1)
        self.assertIn('TEXT_CONTEXT_BUDGET', rejected['message'])


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


    def assert_cancel_publication(self, point, published, timeout=False):
        self.require_debug_faults()
        old = self.cli('index')[1]['revision']
        self.stop()
        self.state('edited')
        self.start('pause:' + point)
        argv = ['index', '--background'] + (['--deadline-seconds', '1'] if timeout else [])
        task = json.loads(self.cli(*argv, raw=True))['task_id']
        until = time.monotonic() + 8
        while not (self.home / 'test-fault-reached').exists():
            self.assertLess(time.monotonic(), until, self.daemon_log())
            time.sleep(.02)
        if timeout:
            time.sleep(1.2)
        else:
            self.cli('task', 'cancel', task, raw=True)
        # An active storage operation cannot outlive its saved task terminal.
        self.assertNotEqual(self.task(task)['state'], 'terminal')
        (self.home / 'test-fault-release').write_text('release')
        status = self.wait_task(task, 'terminal')['terminal']
        head = self.cli('overview')[1]['revision']
        if published:
            self.assertNotEqual(head, old)
            self.assertEqual((status['publication'], status['revision']), ('published', head))
        else:
            self.assertEqual(head, old)
            self.assertEqual(status['code'], 'TIMEOUT' if timeout else 'CANCELLED')
        self.stop()
        self.start()
        self.assertEqual(self.task(task)['terminal'], status)
        self.assertEqual(self.cli('overview')[1]['revision'], head)

    def test_cancel_before_commit_retains_head(self):
        self.assert_cancel_publication('candidate_synced', False)

    def test_timeout_before_commit_retains_head(self):
        self.assert_cancel_publication('candidate_synced', False, timeout=True)

    def test_cancel_during_commit_reports_publication(self):
        self.assert_cancel_publication('before_catalog_commit', True)

    def test_cancel_after_commit_reports_publication(self):
        self.assert_cancel_publication('catalog_committed', True)

    def test_concurrent_idempotent_submit_registers_once(self):
        from concurrent.futures import ThreadPoolExecutor
        self.state('hang')
        def submit(_):
            return json.loads(self.cli('index', '--background', '--request-id', 'concurrent-repeat', raw=True))['task_id']
        with ThreadPoolExecutor(max_workers=6) as pool:
            tasks = list(pool.map(submit, range(6)))
        self.assertEqual(len(set(tasks)), 1)
        with sqlite3.connect(self.home / 'catalog.db') as db:
            self.assertEqual(db.execute("SELECT count(*) FROM tasks WHERE request_id='concurrent-repeat'").fetchone()[0], 1)
        self.cli('task', 'cancel', tasks[0], raw=True)
        self.wait_task(tasks[0], 'terminal')
        self.state('base')
        self.assertEqual(self.cli('index')[1]['publication'], 'published')

    def test_analysis_saturation_preserves_query_and_idempotency(self):
        old = self.cli('index')[1]['revision']
        self.stop()
        (self.home / 'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_query_tasks=1\n')
        self.start()
        self.state('hang')
        tasks = []
        try:
            for i in range(8):
                tasks.append(json.loads(self.cli('index', '--background', '--request-id', f'saturated-{i}', raw=True))['task_id'])
            self.wait_task(tasks[0])
            retry = json.loads(self.cli('index', '--background', '--request-id', 'saturated-0', raw=True))['task_id']
            self.assertEqual(retry, tasks[0])
            conflict = self.cli('index', '--background', '--request-id', 'saturated-0', '--full', code=1, raw=True)
            self.assertIn('REQUEST_ID_CONFLICT', conflict)
            self.assertEqual(self.cli('overview', '--revision', old, '--deadline-seconds', '2')[1]['revision'], old)
            self.assertIn('QUEUE_BUDGET', self.cli('index', '--background', '--request-id', 'rejected', code=1, raw=True))
            with sqlite3.connect(self.home / 'catalog.db') as db:
                self.assertEqual(db.execute("SELECT count(*) FROM tasks WHERE request_id='rejected'").fetchone()[0], 0)
        finally:
            for task in tasks:
                self.cli('task', 'cancel', task, raw=True)

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


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(GraphProduct(name) for name in loader.getTestCaseNames(GraphProduct))

if __name__=='__main__':unittest.main()
