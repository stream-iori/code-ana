#!/usr/bin/env python3
"""All language pairs and one three-language comment publication use the same planner."""
import subprocess
import unittest
import python_comment_e2e as python_comments
import python_language_e2e as python
import rust_language_e2e as rust
from product_support import ProductHarness, ROOT, FIX


def rust_comments(project):
    source=project/'core/src/api.rs'
    fixture=FIX/'rust/comment-indexing/comments.rs'
    if not fixture.exists(): fixture=ROOT/'fixtures/rust/comment-indexing/comments.rs'
    source.write_bytes(source.read_bytes()+b'\n'+fixture.read_bytes().replace(b'//!',b'//',1))


def java_comments(project):
    source=project/'src/p/CommentInventory.java'
    source.write_text('package p;\n/** 混合库存文档 */\nclass CommentInventory {\n/** 混合库存方法 */\nvoid reserve() { // 混合库存检查\n}\n}\n')


def assert_languages(test,expected):
    rows,terminal=test.cli('search-comments','库存','--max-records','100','--max-output-bytes','1048576','--strict')
    test.assertTrue(terminal['evidence']['semantic'],terminal['evidence'])
    test.assertEqual({r['comment']['language'] for r in rows},set(expected))
    for row in rows:
        test.assertTrue(row['comment']['source_hash'])
        test.assertNotIn('source', row)
        for link in row['links']:
            test.assertEqual(link['comment'],row['comment']['id'])


class PythonMixedComments(ProductHarness):
    setUp=python.PythonLanguage.setUp
    export=python.PythonLanguage.export
    add_rust=python.PythonLanguage.add_rust
    add_java=python.PythonLanguage.add_java
    prepare=python_comments.PythonComments.prepare

    def test_python_rust_comments(self):
        self.prepare(); self.add_rust(); rust_comments(self.project); self.export()
        self.cli('index','--full',timeout=180)
        assert_languages(self,{'python','rust'})

    def test_python_java_then_three_languages_and_failed_candidate(self):
        self.prepare(); self.add_java(); java_comments(self.project)
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\nworker_memory_bytes=1073741824\n')
        self.export(); self.cli('index','--full',timeout=180)
        assert_languages(self,{'python','java'})
        self.add_rust();rust_comments(self.project);self.export()
        first=self.cli('index','--full',timeout=180)[1]['revision']
        assert_languages(self,{'python','java','rust'})
        before=self.cli('search-comments','库存','--max-records','100','--max-output-bytes','1048576')[0]
        config.write_text(config.read_text().replace('worker_memory_bytes=1073741824','worker_memory_bytes=1073741824\nrust_fact_limit=1'))
        self.cli('index','--full',code=1,timeout=180)
        self.assertEqual(self.cli('overview')[1]['revision'],first)
        self.assertEqual(before,self.cli('search-comments','库存','--max-records','100','--max-output-bytes','1048576')[0])


class JavaRustComments(rust.RustHarness):
    def test_java_rust_comments(self):
        user=self.home/'config.toml'
        if not user.exists():
            user.write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=3221225472\nmax_total_worker_memory_bytes=6442450944\n')
        subprocess.run(['git','init','--quiet'],cwd=self.project,check=True,capture_output=True)
        python.PythonLanguage.add_java(self);java_comments(self.project);rust_comments(self.project)
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\nworker_memory_bytes=1073741824\n')
        rust.exporter.export(self.project,self.project/'.code-ana/rust-inputs.json')
        self.cli('index','--full',timeout=180)
        assert_languages(self,{'java','rust'})

if __name__=='__main__':
    unittest.main()
