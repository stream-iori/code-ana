#!/usr/bin/env python3
"""Comment owners, immutable revisions and failure publication with the real Java backend."""
import contextlib
import json
import sqlite3
import unittest
from product_support import JavaProductHarness
import comment_search_e2e as comments


class CommentLifecycle(JavaProductHarness):
    prepare_comments = comments.CommentSearch.prepare_comments

    def source(self):
        return self.project / 'src/fixture/comments/Comments.java'

    def db(self, revision):
        return contextlib.closing(sqlite3.connect(self.home / 'revisions' / revision / 'facts.db'))

    def canonical_comments(self, revision):
        with self.db(revision) as db:
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
            self.assertFalse(db.execute('PRAGMA foreign_key_check').fetchall())
            return {table: sorted(db.execute('SELECT * FROM ' + table).fetchall(), key=repr)
                    for table in ('comments', 'comment_links')}

    def delta_equals_full(self):
        records, delta = self.cli('index', timeout=180)
        self.assertEqual(records[0]['mode'], 'delta')
        before = self.canonical_comments(delta['revision'])
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(before, self.canonical_comments(full['revision']))
        return delta['revision']

    def test_comment_code_edits_zero_rename_delete_and_full_delta_equivalence(self):
        first = self.prepare_comments()
        original = self.source().read_text()
        with self.db(first) as db:
            fingerprint = db.execute('SELECT contract_hash FROM file_cache').fetchone()[0]
        self.source().write_text(original.replace('扣减库存', '预留库存'))
        changed = self.delta_equals_full()
        with self.db(changed) as db:
            self.assertEqual(db.execute('SELECT contract_hash FROM file_cache').fetchone()[0], fingerprint)
        self.assertFalse(self.cli('search-comments', '扣减库存')[0])
        self.assertEqual(len(self.cli('search-comments', '预留库存')[0]), 1)
        self.source().write_text('\n\n' + self.source().read_text().replace('reserve(', 'reserveNew('))
        self.delta_equals_full()
        declarations = self.cli('search-code', '预留库存', '--in', 'comments')[0]
        self.assertEqual([r['signature'] for r in declarations], ['fixture.comments.Comments#reserveNew(int)'])
        self.assertGreater(declarations[0]['comment_hits'][0]['comment']['range']['begin_line'], 6)
        before_removal = self.source().read_bytes()
        current = self.cli('overview')[1]['revision']
        with self.db(current) as db:
            start, end = db.execute("SELECT l.byte_start,l.byte_end FROM comment_links l JOIN nodes n ON n.id=l.declaration_id WHERE n.symbol_id='fixture.comments.Comments#reserveNew(int)' AND l.relation='enclosing_callable' LIMIT 1").fetchone()
        self.source().write_bytes(before_removal[:start] + before_removal[end:])
        removed = self.delta_equals_full()
        with self.db(removed) as db:
            self.assertFalse(db.execute("SELECT 1 FROM comment_links WHERE declaration_id LIKE '%#reserveNew(int)'").fetchall())
        self.assertTrue(self.cli('search-comments', '预留库存')[0])
        self.source().write_bytes(before_removal)
        self.delta_equals_full()
        # Remove the actual language-produced spans, including all multiline comments.
        current = self.cli('overview')[1]['revision']
        with self.db(current) as db:
            spans = db.execute('SELECT byte_start,byte_end FROM comments ORDER BY byte_start DESC').fetchall()
        raw = self.source().read_bytes()
        for start, end in spans:
            raw = raw[:start] + raw[end:]
        self.source().write_bytes(raw)
        empty = self.delta_equals_full()
        self.assertFalse(self.canonical_comments(empty)['comments'])
        self.assertFalse(self.cli('search-comments', '库存')[0])
        self.source().write_text(original)
        self.delta_equals_full()
        renamed = self.source().with_name('Renamed.java')
        self.source().rename(renamed)
        renamed_revision = self.delta_equals_full()
        with self.db(renamed_revision) as db:
            self.assertEqual({r[0] for r in db.execute('SELECT DISTINCT source_file FROM comments')}, {'src/fixture/comments/Renamed.java'})
        renamed.with_name('Keep.java').write_text('package fixture.comments; class Keep {}')
        renamed.unlink()
        deleted = self.delta_equals_full()
        self.assertFalse(self.canonical_comments(deleted)['comments'])
        self.assertEqual(len(self.canonical_comments(first)['comments']), 8)
        historical = self.cli('search-comments', '扣减库存', '--revision', first)[0]
        self.assertEqual(len(historical), 1)
        self.assertIn('boolean reserve', historical[0]['source']['text'])

    def test_frozen_pages_survive_source_edits_publication_and_configuration_changes(self):
        first = self.prepare_comments()
        arguments = ('search-comments', '库存', '--max-records', '1')
        rows, terminal = self.cli(*arguments)
        self.assertTrue(terminal['cursor'])
        self.source().write_text('package fixture.comments; class Comments { // 新版本\n void changed() {} }')
        self.cli('index', timeout=180)
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text() + '\n[query.comments]\ncontext_before=0\ncontext_after=0\n')
        while terminal['cursor']:
            page, terminal = self.cli(*arguments, '--cursor', terminal['cursor'])
            rows.extend(page)
            self.assertEqual(terminal['revision'], first)
        self.assertGreater(len(rows), 1)
        self.assertTrue(all('新版本' not in row['source']['text'] for row in rows))
        self.assertTrue(all(row['comment']['source_hash'] == row['source']['hash'] for row in rows))
        historical = self.cli('search-comments', '库存', '--revision', first)[0]
        self.assertEqual([r['comment']['id'] for r in rows], [r['comment']['id'] for r in historical])
        self.assertFalse(self.cli('search-comments', '库存')[0])

    def test_resource_output_and_scratch_failures_preserve_comments_and_head(self):
        first = self.prepare_comments()
        original_source = self.source().read_bytes()
        config = self.project / '.code-ana/config.toml'
        original_config = config.read_text()
        before = self.canonical_comments(first)
        for policy in ('payload', 'scratch', 'rows'):
            for full in (False, True):
                with self.subTest(policy=policy, full=full):
                    self.source().write_bytes(original_source + b'\n/* ' + b'oversizedComment ' * 10000 + b' */\n')
                    extra = {'payload': '\n[resources.index.analysis]\nmax_payload_bytes=16777216\n',
                             'scratch': '\n[resources.index]\njava_scratch_bytes=16384\n',
                             'rows': '\n[resources.index]\njava_fact_limit=1\n'}[policy]
                    config.write_text(original_config + extra)
                    _, failed = self.cli('index', *(('--full',) if full else ()), code=1, timeout=180)
                    self.assertEqual(failed['publication'], 'retained')
                    self.assertEqual(self.cli('overview')[1]['revision'], first)
                    self.assertEqual(before, self.canonical_comments(first))
        config.write_text(original_config)
        self.source().write_bytes(original_source)
        _, retried = self.cli('index', '--full', timeout=180)
        self.assertEqual(before, self.canonical_comments(retried['revision']))

    def test_active_source_hash_and_presentation_changes_do_not_rebuild_semantics(self):
        first = self.prepare_comments()
        before = self.canonical_comments(first)
        profile = json.loads((self.home/'revisions'/first/'revision.json').read_text())['profile']
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text() + '\n[query.comments]\ncontext_before=0\ncontext_after=8\n')
        records, changed = self.cli('index', timeout=180)
        self.assertNotEqual(records[0]['mode'], 'full')
        self.assertEqual(json.loads((self.home/'revisions'/changed['revision']/'revision.json').read_text())['profile'], profile)
        self.assertEqual(before, self.canonical_comments(changed['revision']))
        self.source().write_text(self.source().read_text().replace('扣减库存', '更新文档'))
        _, stale = self.cli('search-comments', '扣减库存', code=1)
        self.assertIn('SOURCE_HASH_MISMATCH', stale['message'])
        self.assertTrue(self.cli('search-comments', '扣减库存', '--revision', first)[0])
        self.cli('index', timeout=180)
        self.assertFalse(self.cli('search-comments', '扣减库存')[0])
        self.assertTrue(self.cli('search-comments', '更新文档')[0])

    def test_all_distinct_hits_survive_navigation_and_lambda_has_no_outer_projection(self):
        self.prepare_comments()
        rows = self.cli('search-code', '库存', '--in', 'comments')[0]
        method = next(row for row in rows if row['signature'] == 'fixture.comments.Comments#reserve(int)')
        self.assertEqual(len(method['comment_hits']), 3)
        self.assertEqual(len({hit['comment']['id'] for hit in method['comment_hits']}), 3)
        self.assertEqual({hit['comment']['range']['begin_line'] for hit in method['comment_hits']}, {6, 9, 10})
        self.assertFalse(self.cli('search-code', 'lambda', '--in', 'comments')[0])
        self.assertFalse(self.cli('search-code', 'reserve', '--in', 'comments')[0])
        names = self.cli('search-code', 'reserve', '--in', 'names')[0]
        combined = self.cli('search-code', 'reserve', '--in', 'names,comments')[0]
        self.assertEqual([row['id'] for row in combined], [row['id'] for row in names])
        self.assertTrue(all('comment_hits' not in row for row in combined))
        hit = self.cli('search-comments', 'lambda')[0][0]
        enclosing = next(link for link in hit['links'] if link['relation'] == 'enclosing_callable')
        self.assertIsNone(enclosing['declaration'])
        self.assertEqual((enclosing['range']['begin_line'], enclosing['range']['end_line']), (14, 17))

    def test_comment_coverage_remains_complete_when_type_binding_is_partial(self):
        self.prepare_comments()
        path = self.source().with_name('Broken.java')
        path.write_text('package fixture.comments; class Broken { MissingType value; /** 类型缺失文档 */ void run() {} }')
        _, indexed = self.cli('index', timeout=180)
        self.assertFalse(indexed['evidence']['semantic'])
        rows, comments = self.cli('search-comments', '类型缺失文档', '--strict')
        self.assertEqual(len(rows), 1)
        self.assertTrue(comments['evidence']['semantic'], comments['evidence'])
        declarations, code = self.cli('search-code', '类型缺失文档', '--in', 'comments')
        self.assertTrue(declarations)
        self.assertFalse(code['evidence']['semantic'])

    def test_query_budget_keeps_completed_comment_evidence_and_discloses_limits(self):
        self.prepare_comments()
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        partial = None
        for work in (3000, 6000, 12000, 24000):
            config.write_text(original + '\n[resources.query]\nmax_work=' + str(work) + '\n')
            rows, terminal = self.cli('search-comments', '库存')
            if rows and not terminal['evidence']['enumeration']:
                partial = rows, terminal
                break
        self.assertIsNotNone(partial, 'no completed evidence survived the exhausted query')
        rows, terminal = partial
        self.assertTrue(terminal['evidence']['resource_usage']['exhausted'])
        self.assertTrue(all(row['hits'] and row['source']['text'] for row in rows))
        _, strict = self.cli('search-comments', '库存', '--strict', code=3)
        self.assertFalse(strict['evidence']['enumeration'])
        config.write_text(original)
        complete, terminal = self.cli('search-comments', '库存')
        self.assertTrue(terminal['evidence']['enumeration'])
        self.assertGreater(len(complete), len(rows))

    def test_cancellation_and_worker_crash_keep_a_comment_bearing_revision(self):
        first = self.prepare_comments()
        before = self.canonical_comments(first)
        (self.project / 'src/p').mkdir(parents=True, exist_ok=True)
        self.assert_cancel_and_crash_preserve_head()
        retained = self.cli('overview')[1]['revision']
        self.assertEqual(before, self.canonical_comments(retained))
        self.assertTrue(self.cli('search-comments', '扣减库存')[0])


if __name__ == '__main__':
    unittest.main()
