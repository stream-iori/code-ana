#!/usr/bin/env python3
"""Agent CLI contract: real processes, explicit codecs, delivery and durable effects."""
import json
import pathlib
import sqlite3
import subprocess
import tempfile
import time
import unittest
from product_support import MockProductHarness, BIN, ROOT

COMMANDS = ['hello', 'daemon status', 'daemon drain', 'daemon stop', 'daemon restart',
            'task status', 'task watch', 'task cancel', 'java-inputs export',
            'index', 'doctor', 'resolve', 'search-code', 'search-comments', 'structure',
            'calls', 'call-sites', 'references', 'source', 'annotations', 'type-relations',
            'callable-relations', 'runtime-implementations', 'dispatch', 'trace', 'overview',
            'search-text', 'pipeline', 'snapshot', 'snapshot-list', 'snapshot-remove',
            'diff', 'report', 'gc', 'recover']


class CliContract(MockProductHarness):
    def invoke(self, *args, project=True, cwd=None):
        command = [str(BIN / 'codeana-cli'), '--home', str(self.home)]
        if project:
            command += ['--project', str(self.project)]
        return subprocess.run(command + list(map(str, args)), capture_output=True, cwd=cwd)

    def test_all_command_help_and_local_rejection_without_task_or_daemon(self):
        for command in COMMANDS:
            # Actions are positional; their shared command help describes action-specific options.
            result = self.invoke(*command.split(), '--help', project=False)
            self.assertEqual(result.returncode, 0, result.stderr)
        for args in [('calls', 'main', '--full'), ('diff', '--name', 'files'),
                     ('diff', '--base', 'before', '--target', 'after', '--world', 'ignored'),
                     ('snapshot', 'before', '--name', 'analysis'),
                     ('index', '--background', '--export', self.root / 'bad'),
                     ('report', 'missing', '--format', 'markdown'),
                     ('snapshot', 'before', '--kind', 'files', '--full'),
                     ('search-text', '[', '--regex'),
                     ('task', 'status', 'missing', '--after-seq', '1')]:
            result = self.invoke(*args, project=False)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertEqual(result.stdout, b'')
        with sqlite3.connect(self.home / 'catalog.db') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM tasks').fetchone()[0], 0)

    def test_text_source_grouping_closure_and_explicit_json(self):
        indexed = self.invoke('index')
        self.assertEqual(indexed.returncode, 0, indexed.stderr)
        text = indexed.stdout.decode()
        self.assertTrue(text.endswith('end\n'))
        self.assertIn('index: published\n', text)
        for field in ('codeana-text-v1', 'record {', 'revision:', 'producer:', 'manifest:', 'profile:'):
            self.assertNotIn(field, text)
        rows = self.cli('search-code', 'greet')[0]
        source = self.invoke('source', rows[0]['id'])
        self.assertEqual(source.returncode, 0, source.stderr)
        text = source.stdout.decode()
        self.assertIn('```java\n', text)
        self.assertNotIn(' | ', text)
        self.assertTrue(text.endswith('end\n'))
        rendered = self.invoke('search-code', 'greet').stdout.decode()
        self.assertEqual(rendered.count('src/App.java'), 1)
        self.assertNotIn('display_name', rendered)
        self.assertNotIn('package: \"\"', rendered)
        sites = self.invoke('call-sites', 'main').stdout.decode()
        self.assertIn('target: greet', sites)
        self.assertNotIn('stable_hash:', sites)
        self.assertEqual(sites.count('call-site:'), 2)
        structured = json.loads(self.invoke('source', rows[0]['id'], '--format', 'json').stdout)
        self.assertEqual(len(structured['records']), 1)
        self.assertEqual(structured['delivery']['state'], 'complete')
        # Reuse the public identity rather than selecting an ambiguous short name.
        self.assertIn('hello', structured['records'][0]['text'])

    def test_cursor_only_restores_format_filters_and_frozen_bytes_from_another_cwd(self):
        source = self.project / 'page.txt'
        source.write_text('hit one\nhit two\nhit three\n')
        first = json.loads(self.invoke('search-text', 'hit', '--path', 'page.txt', '--max-records', '1', '--format', 'json').stdout)
        cursor = first['delivery']['cursor']
        self.assertTrue(cursor)
        self.assertTrue(first['evidence']['enumeration'])
        source.write_text('new workspace\n')
        remaining = self.invoke('search-text', '--cursor', cursor, '--max-records', '100', project=False, cwd=self.root)
        self.assertEqual(remaining.returncode, 0, remaining.stderr)
        page = json.loads(remaining.stdout)
        self.assertEqual([r['text'] for r in page['records']], ['hit two', 'hit three'])
        mismatch = self.invoke('search-text', '--cursor', cursor, '--format', 'text')
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn(b'CURSOR_OPTIONS_MISMATCH', mismatch.stderr)
        switched = self.invoke('search-text', 'new', '--cursor', cursor)
        self.assertEqual(switched.returncode, 2)
        # Incompatible old projection is refused, never silently re-executed.
        page_id = cursor.rsplit(':', 1)[0]
        with sqlite3.connect(self.home / 'catalog.db') as db:
            body = json.loads(db.execute("SELECT body FROM objects WHERE kind='page' AND id=?", (page_id,)).fetchone()[0])
            body.pop('projection_version')
            db.execute("UPDATE objects SET body=? WHERE kind='page' AND id=?", (json.dumps(body), page_id))
        old = self.invoke('search-text', '--cursor', cursor)
        self.assertNotEqual(old.returncode, 0)
        self.assertIn(b'INVALID_CURSOR', old.stderr)

    def test_export_is_no_clobber_and_does_not_repeat_a_write_task(self):
        target = self.root / 'result.txt'
        first = self.invoke('index', '--export', target, '--request-id', 'export-once')
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn(b'exported:', first.stdout)
        self.assertTrue(target.read_bytes().endswith(b'end\n'))
        before = target.read_bytes()
        second = self.invoke('index', '--export', target, '--request-id', 'different-write')
        self.assertNotEqual(second.returncode, 0)
        self.assertEqual(target.read_bytes(), before)
        with sqlite3.connect(self.home / 'catalog.db') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM tasks').fetchone()[0], 1)
        markdown = self.root / 'report.md'
        report = self.cli('index')[1]['report']
        result = self.invoke('report', report, '--format', 'markdown', '--export', markdown)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(markdown.read_text().startswith('#'))

    def test_asset_and_readiness_commands_keep_preview_reference_and_execution_distinct(self):
        revision = self.cli('index')[1]['revision']
        self.cli('snapshot', 'retained', '--kind', 'files')
        snapshots = self.cli('snapshot-list')[0]
        self.assertEqual([row['id'] for row in snapshots], ['retained'])
        self.assertEqual(snapshots[0]['kind'], 'files')
        readiness = self.invoke('doctor')
        self.assertEqual(readiness.returncode, 0, readiness.stderr)
        self.assertIn(b'mock_fixture_only', readiness.stdout)
        self.assertNotIn(b'effective:', readiness.stdout)
        self.assertTrue(readiness.stdout.endswith(b'end\n'))
        preview = self.cli_record('gc')
        self.assertFalse(preview['execute'])
        self.assertIn(revision, preview['protected'])
        executed = self.cli_record('gc', '--execute')
        self.assertTrue(executed['execute'])
        self.assertEqual(self.cli('overview')[1]['revision'], revision)
        self.cli('snapshot-remove', 'retained')
        self.assertEqual(self.cli('snapshot-list')[0], [])
        self.assertFalse(self.cli_record('recover')['execute'])
        self.assertTrue(self.cli_record('recover', '--execute')['execute'])
        self.assertEqual(self.cli('overview')[1]['revision'], revision)

    def test_watch_has_events_once_and_observation_boundary(self):
        task = self.cli_record('index', '--background')['task']
        watched = self.invoke('task', 'watch', task, '--format', 'json', '--max-output-bytes', '2048', project=False)
        self.assertEqual(watched.returncode, 0, watched.stderr)
        value = json.loads(watched.stdout)
        self.assertNotIn('records', value)
        self.assertLessEqual(len(watched.stdout), 2048)
        self.assertEqual(value['result']['publication'], 'published')
        seqs = [event['seq'] for event in value['events']]
        self.assertEqual(seqs, sorted(set(seqs)))
        resumed = self.invoke('task', 'watch', task, '--after-seq', '1', project=False)
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assertIn(b'delivery: partial\n', resumed.stdout)
        self.assertTrue(resumed.stdout.endswith(b'end\n'))
        cancelled = self.invoke('task', 'cancel', task, project=False)
        self.assertIn(b'cancel: requested', cancelled.stdout)
        self.assertIn(b'publication: published', cancelled.stdout)

    def test_source_chunks_preserve_bytes_at_the_minimum_json_budget(self):
        body = ('````\r\nend\n\\"\t中😀' * 25).encode()
        (self.project / 'raw.txt').write_bytes(body)
        saved = self.invoke('snapshot', 'raw', '--kind', 'files')
        self.assertEqual(saved.returncode, 0, saved.stderr)
        first = self.invoke('source', 'raw.txt', '--revision', 'snapshot:raw', '--format', 'json', '--max-output-bytes', '2048')
        self.assertEqual(first.returncode, 0, first.stderr)
        text = ''
        current = first
        while True:
            self.assertLessEqual(len(current.stdout), 2048)
            value = json.loads(current.stdout)
            text += ''.join(record['text'] for record in value['records'])
            cursor = value['delivery']['cursor']
            if not cursor:
                break
            current = self.invoke('source', '--cursor', cursor)
            self.assertEqual(current.returncode, 0, current.stderr)
        self.assertEqual(text.encode(), body)

    def test_text_pipeline_can_trace_with_an_explicit_shared_target(self):
        self.cli('index')
        path = self.root / 'trace.txt'
        path.write_text('resolve main\ntrace --from entity\n')
        rows, _ = self.cli('pipeline', path, '--target', 'greet')
        self.assertEqual(rows[0]['nodes'], ['main', 'greet'])

    def test_diff_projections_and_cursor_keep_the_original_snapshot_identities(self):
        self.cli('index')
        before = self.cli('snapshot', 'before', '--kind', 'analysis')[1]['revision']
        self.state('edited')
        self.cli('index')
        after = self.cli('snapshot', 'after', '--kind', 'analysis')[1]['revision']
        for section in ('files', 'declarations', 'relations', 'impact'):
            result = self.invoke('diff', '--base', 'before', '--target', 'after', '--section', section)
            self.assertEqual(result.returncode, 0, result.stderr)
            text = result.stdout.decode()
            self.assertIn('diff: ' + section, text)
            self.assertIn('base: before (analysis ' + before + ')', text)
            self.assertIn('target: after (analysis ' + after + ')', text)
            self.assertNotIn('display_', text)
            self.assertNotIn('metadata:', text)
            self.assertNotIn('status: \n', text)
            if section == 'declarations':
                self.assertEqual(text.count('world:'), 1)
            self.assertTrue(text.endswith('end\n'))
        first = self.invoke('diff', '--base', 'before', '--target', 'after', '--section', 'declarations', '--max-records', '1')
        import re
        cursor = re.search(rb'^cursor: (\S+)', first.stdout, re.M).group(1).decode()
        # Same-named snapshots are mutable; a page must retain the compared state.
        self.cli('snapshot', 'before', '--kind', 'analysis')
        resumed = self.invoke('diff', '--cursor', cursor, '--max-records', '100')
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assertIn(('base: before (analysis ' + before + ')').encode(), resumed.stdout)
        task = self.cli_record('diff', '--base', 'before', '--target', 'after', '--section', 'files', '--background')['task']
        observed = self.invoke('task', 'watch', task, project=False)
        self.assertEqual(observed.returncode, 0, observed.stderr)
        self.assertEqual(observed.stdout.count(b'diff: files'), 1)

    def test_event_gaps_are_not_mistaken_for_a_complete_task_body(self):
        task = self.cli_record('index', '--background')['task']
        self.invoke('task', 'watch', task, project=False)
        with sqlite3.connect(self.home / 'catalog.db') as db:
            records = [(seq, body) for seq, body in db.execute('SELECT seq,body FROM events WHERE task=?', (task,)).fetchall() if 'Record' in json.loads(body)]
            self.assertTrue(records)
            db.execute('DELETE FROM events WHERE task=? AND seq=?', (task, records[0][0]))
        broken = self.invoke('task', 'watch', task, project=False)
        self.assertEqual(broken.returncode, 1)
        self.assertFalse(broken.stdout.endswith(b'end\n'))
        self.assertIn(b'EVENT_ORDER_INVALID', broken.stderr)
        self.assertEqual(self.task(task)['terminal']['publication'], 'published')

    def test_limited_computation_does_not_publish_an_invented_total(self):
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text() + '\n[resources.query]\nmax_work=1\n')
        result = self.invoke('search-text', 'hello', '--format', 'json')
        self.assertEqual(result.returncode, 0, result.stderr)
        value = json.loads(result.stdout)
        self.assertFalse(value['evidence']['enumeration'])
        self.assertIsNone(value['delivery']['total'])

    def pause_before_publish(self):
        self.require_debug_faults()
        self.cli('index')
        self.stop()
        self.state('edited')
        self.start('pause:before_publish')

    def wait_publication_pause(self):
        deadline = time.monotonic() + 10
        while not (self.home / 'test-fault-reached').exists():
            self.assertLess(time.monotonic(), deadline, self.daemon_log())
            time.sleep(.02)

    def test_stop_timeout_does_not_claim_exit(self):
        self.pause_before_publish()
        self.cli_record('index', '--background')
        try:
            self.wait_publication_pause()
            stopped = self.invoke('daemon', 'stop', '--deadline-seconds', '0', project=False)
            self.assertEqual(stopped.returncode, 124, stopped.stdout + stopped.stderr)
            self.assertNotIn(b'daemon: stopped', stopped.stdout)
            self.assertIn(b'TIMEOUT', stopped.stdout)
            self.assertIsNone(self.daemon.poll())
        finally:
            (self.home / 'test-fault-release').write_text('release')
            self.daemon.wait(timeout=15)

    def test_export_race_keeps_the_actual_publication(self):
        self.pause_before_publish()
        path = self.root / 'contended.txt'
        process = subprocess.Popen([str(BIN / 'codeana-cli'), '--home', str(self.home),
                                   '--project', str(self.project), 'index', '--export', str(path),
                                   '--format', 'json'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.wait_publication_pause()
            path.write_text('other writer')
        finally:
            (self.home / 'test-fault-release').write_text('release')
        stdout, stderr = process.communicate(timeout=20)
        self.assertEqual(process.returncode, 1, stderr)
        value = json.loads(stdout)
        self.assertEqual(value['result']['publication'], 'published')
        self.assertEqual(value['result']['execution'], 'completed')
        self.assertEqual(value['delivery']['state'], 'failed')
        self.assertEqual(value['result']['code'], 'EXPORT_WRITE_FAILED')
        self.assertEqual(path.read_text(), 'other writer')
        self.assertEqual(self.cli('overview')[1]['revision'], value['result']['revision'])

    def test_stop_waits_for_the_original_process(self):
        stopped = self.invoke('daemon', 'stop', project=False)
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        self.assertIn(b'daemon: stopped', stopped.stdout)
        self.assertIsNotNone(self.daemon.poll())


class ServiceLifecycle(unittest.TestCase):
    def test_restart_confirms_a_different_instance(self):
        with tempfile.TemporaryDirectory(prefix='codeana-cli-service-') as directory:
            home = pathlib.Path(directory) / 'home'
            command = [str(BIN / 'codeana-cli'), '--home', str(home)]
            try:
                # hello starts a normal service without analyzing a target project.
                hello = subprocess.run(command + ['hello'], capture_output=True, timeout=15)
                self.assertEqual(hello.returncode, 0, hello.stderr)
                before = json.loads((home / 'endpoint.json').read_text())
                restarted = subprocess.run(command + ['daemon', 'restart'], capture_output=True, timeout=20)
                self.assertEqual(restarted.returncode, 0, restarted.stderr)
                after = json.loads((home / 'endpoint.json').read_text())
                self.assertNotEqual(before['generation'], after['generation'])
                self.assertIn(b'daemon: restarted', restarted.stdout)
            finally:
                subprocess.run(command + ['daemon', 'stop'], capture_output=True, timeout=15)


if __name__ == '__main__':
    unittest.main(verbosity=2)
