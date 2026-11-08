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
        docs, _ = self.cli('search-comments', '扣减库存')
        self.assertNotIn('source', docs[0])
        self.assertTrue(all(link['relation'] in ('documents', 'enclosing_callable') for row in docs for link in row['links']))
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

    def test_complete_long_callable_source_is_read_separately_at_the_same_revision(self):
        source = self.project / 'src/p/Complete.java'
        source.parent.mkdir(parents=True, exist_ok=True)
        method = 'public void complete() {\n// 完整函数检索\n' + 'System.out.println("多字节😀");\n' * 800 + '}'
        source.write_text('package p; class Complete {\n' + method + '\n}')
        _, indexed = self.cli('index', '--full', timeout=180)
        revision = indexed['revision']
        declarations, searched = self.cli('search-code', '完整函数检索', '--in', 'comments', '--revision', revision)
        self.assertEqual(searched['revision'], revision)
        self.assertEqual(len(declarations), 1)
        self.assertNotIn('source', declarations[0]['comment_hits'][0])
        chunks, _ = self.cli('source', declarations[0]['id'], '--revision', revision, '--max-records', '1000', '--max-output-bytes', '1048576')
        self.assertGreater(len(chunks), 1)
        self.assertEqual(''.join(row['text'] for row in chunks), method)
        source.write_text('package p; class Complete {}')
        _, stale = self.cli('source', declarations[0]['id'], code=1)
        self.assertIn('SOURCE_HASH_MISMATCH', str(stale))
        historical, _ = self.cli('source', declarations[0]['id'], '--revision', revision, '--max-records', '1000', '--max-output-bytes', '1048576')
        self.assertEqual(''.join(row['text'] for row in historical), method)

    def test_retired_options_config_and_frozen_pages(self):
        self.prepare_comments()
        for flag in ('--context-before', '--context-after'):
            _, error = self.cli('search-comments', '库存', flag, '0', code=2)
            self.assertIn('delete', str(error))
        literal, _ = self.cli('search-comments', '--', '--context-before')
        self.assertFalse(literal)
        first, terminal = self.cli('search-comments', '库存', '--max-records', '1')
        remaining, _ = self.cli('search-comments', '库存', '--cursor', terminal['cursor'], '--max-records', '100')
        self.assertTrue(first and remaining)
        self.assertTrue(all('source' not in row for row in first + remaining))
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text() + '\n[query.comments]\ncontext_before=0\n')
        _, invalid = self.cli('search-comments', '库存', code=1)
        self.assertIn('comments', str(invalid))


if __name__ == '__main__':
    unittest.main()
