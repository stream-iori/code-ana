#!/usr/bin/env python3
"""Real frozen RA comment rows, language scopes, worlds and delta cleanup."""
import unittest
from product_support import ROOT, FIX
from rust_language_e2e import RustHarness

class RustComments(RustHarness):
    def prepare(self):
        source = self.project / 'core/src/api.rs'
        source.write_bytes(source.read_bytes() + b'\n' + ((FIX/'rust/comment-indexing/comments.rs') if (FIX/'rust/comment-indexing/comments.rs').exists() else (ROOT/'fixtures/rust/comment-indexing/comments.rs')).read_bytes().replace(b'//!', b'//', 1))
        return source, self.cli('index', '--full', timeout=90)[1]['revision']

    def comments(self, revision):
        with self.db(revision) as db:
            return {table: sorted(db.execute('SELECT * FROM '+table).fetchall(),key=repr) for table in ('comments','comment_links')}

    def test_real_comments_scopes_worlds_highlights_and_projection(self):
        source, revision = self.prepare()
        rows, terminal = self.cli('search-comments', '扣减库存', '--strict')
        self.assertTrue(terminal['evidence']['semantic'])
        worlds = {r['comment']['world'] for r in rows}
        self.assertEqual(len(worlds), 2)
        self.assertEqual(len(rows), 6)
        declarations, _ = self.cli('search-code', '扣减库存', '--in', 'comments')
        self.assertEqual(len(declarations), 2)
        self.assertTrue(all(r['name'] == 'comment_reserve' and len(r['comment_hits']) == 3 for r in declarations))
        self.assertFalse(self.cli('search-comments', '字符串伪注释')[0])
        self.assertEqual(len(self.cli('search-comments','续行库存检查','--strict')[0]),2)
        self.assertEqual(len(self.cli('search-comments', '嵌套库存')[0]), 2)
        self.assertEqual(len(self.cli('search-comments', '库存转义库存')[0]), 2)
        self.assertFalse(self.cli('search-code', '闭包隔离', '--in', 'comments')[0])
        inner, _ = self.cli('search-code', '内层方法隔离', '--in', 'comments')
        self.assertTrue(inner)
        self.assertTrue(all(r['name'] == 'nested' for r in inner))
        for world in worlds:
            selected, _ = self.cli('search-comments', '扣减库存', '--world', world)
            self.assertEqual(len(selected), 3)
            self.assertEqual({r['comment']['world'] for r in selected}, {world})
        for row in rows:
            original=(self.project / row['comment']['source_file']).read_bytes()
            for hit in row['hits']:
                self.assertTrue(original[hit['source']['byte_start']:hit['source']['byte_end']].decode())
        self.assertTrue(self.comments(revision)['comments'])

    def test_comment_only_delta_full_and_zero_output_keep_history(self):
        source, first = self.prepare()
        before=self.comments(first)
        source.write_text(source.read_text().replace('扣减库存', '冻结余额'))
        delta=self.cli('index', timeout=90)[1]['revision']
        self.assertFalse(self.cli('search-comments', '扣减库存')[0])
        self.assertTrue(self.cli('search-comments', '冻结余额')[0])
        full=self.cli('index', '--full', timeout=90)[1]['revision']
        self.assertEqual(self.comments(delta), self.comments(full))
        self.assertEqual(before, self.comments(first))
        source.write_text('pub fn answer() -> u64 { 1 }\n')
        self.cli('index', timeout=90)
        self.assertFalse(self.cli('search-comments', '冻结余额')[0])
        self.assertTrue(self.cli('search-comments', '扣减库存', '--revision', first)[0])

if __name__=='__main__':
    unittest.main()
