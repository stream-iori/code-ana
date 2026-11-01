#!/usr/bin/env python3
"""Real Java producer -> GRA1 -> independent FTS -> CLI/RPC/pipeline contracts."""
import json
import shutil
import unittest
from product_support import JavaProductHarness, ROOT, FIX


class CommentSearch(JavaProductHarness):
    def prepare_comments(self):
        shutil.rmtree(self.project / 'src')
        target = self.project / 'src/fixture/comments'
        target.mkdir(parents=True)
        fixture = FIX / 'comment-indexing'
        if not fixture.exists():
            fixture = ROOT / 'lang-java-adaptor/fixtures/comment-indexing'
        shutil.copy2(fixture / 'Comments.java', target / 'Comments.java')
        self.expected = json.loads((fixture / 'expected.json').read_text())
        return self.cli('index', '--full', timeout=180)[1]['revision']

    def test_fixture_queries_highlights_declaration_projection_and_pipeline(self):
        revision = self.prepare_comments()
        for case in self.expected['queries']:
            with self.subTest(query=case['text']):
                rows, terminal = self.cli('search-comments', case['text'])
                self.assertEqual(terminal['revision'], revision)
                self.assertEqual([r['comment']['range']['begin_line'] for r in rows], case['comment_lines'])
                declarations, _ = self.cli('search-code', case['text'], '--in', 'comments')
                self.assertEqual(sorted(r['signature'] for r in declarations), sorted(case['declarations']))
                for declaration in declarations:
                    self.assertTrue(declaration['comment_hits'])
                for row in rows:
                    original = (self.project / row['comment']['source_file']).read_bytes()
                    for hit in row['hits']:
                        highlighted = original[hit['source']['byte_start']:hit['source']['byte_end']].decode('utf-8')
                        self.assertTrue(highlighted)
        docs, _ = self.cli('search-comments', '扣减库存', '--context-before', '0', '--context-after', '0')
        self.assertNotIn('库存仓库', docs[0]['source']['text'])
        stages = self.root / "pipeline.json"
        stages.write_text(json.dumps([
            {'operation': 'search-comments', 'selector': '扣减库存'},
            {'operation': 'resolve'}], ensure_ascii=False))
        pipeline, _ = self.cli('pipeline', stages)
        self.assertEqual([r['signature'] for r in pipeline], ['fixture.comments.Comments#reserve(int)'])
        names, _ = self.cli('search-code', 'reserve')
        self.assertTrue(names)
        self.assertTrue(all('comment_hits' not in r for r in names))
        empty, _ = self.cli('search-code', '文件头', '--in', 'names,comments')
        self.assertFalse(empty)
        _, error = self.cli('search-comments', '!!!', code=1)
        self.assertIn('COMMENT_QUERY_EMPTY', str(error))

    def test_context_config_overrides_explicit_zero_and_frozen_pages(self):
        self.prepare_comments()
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text() + '\n[query.comments]\ncontext_before=0\ncontext_after=0\n')
        rows, _ = self.cli('search-comments', '扣减库存')
        self.assertNotIn('库存仓库', rows[0]['source']['text'])
        rows, _ = self.cli('search-comments', '扣减库存', '--context-before', '3')
        self.assertIn('库存仓库', rows[0]['source']['text'])
        first, terminal = self.cli('search-comments', '库存', '--max-records', '1')
        self.assertEqual(len(first), 1)
        cursor = terminal['cursor']
        self.assertTrue(cursor)
        config.write_text(config.read_text().replace('context_before=0', 'context_before=100'))
        remaining, _ = self.cli('search-comments', '库存', '--cursor', cursor, '--max-records', '100')
        self.assertTrue(remaining)
        for row in remaining:
            self.assertNotIn('// 文件头', row['source']['text'])
        config.write_text(config.read_text().replace('context_before=100', 'context_before=101'))
        _, invalid = self.cli('search-comments', '库存', code=1)
        self.assertIn('COMMENT_CONTEXT_RANGE', str(invalid))


if __name__ == '__main__':
    unittest.main()
