#!/usr/bin/env python3
"""Comment scope/world removal and language disable use the existing index planner."""
import contextlib
import json
import sqlite3
import subprocess
import unittest
import comment_search_e2e as comments
import python_language_e2e as python
import rust_language_e2e as rust
from product_support import JavaProductHarness


class JavaWorldRemoval(JavaProductHarness):
    prepare_comments = comments.CommentSearch.prepare_comments

    def test_scopes_modules_world_filters_and_removed_source_world(self):
        self.prepare_comments()
        config = self.project / '.code-ana/config.toml'
        base = config.read_text().replace('scopes = ["MAIN"]', 'scopes = ["MAIN", "TEST"]')
        config.write_text(base + '\n[[modules.source_roots]]\npath="testsrc"\nscope="TEST"\n')
        path = self.project / 'testsrc/p/TestScope.java'
        path.parent.mkdir(parents=True)
        path.write_text('package p;\n/** 测试世界文档 */\nclass TestScope {}\n')
        _, first = self.cli('index', '--full', timeout=180)
        revision = first['revision']
        test = self.cli('search-comments', '测试世界文档', '--scope', 'TEST')[0]
        self.assertEqual(len(test), 1)
        world = test[0]['comment']['world']
        self.assertFalse(self.cli('search-comments', '测试世界文档', '--scope', 'MAIN')[0])
        self.assertEqual(len(self.cli('search-comments', '测试世界文档', '--world', world)[0]), 1)
        self.assertFalse(self.cli('search-comments', '库存', '--world', world)[0])
        self.assertFalse(self.cli('search-comments', '测试世界文档', '--module', 'absent')[0])
        config.write_text(base)
        _, removed = self.cli('index', timeout=180)
        self.assertNotEqual(removed['revision'], revision)
        self.assertFalse(self.cli('search-comments', '测试世界文档')[0])
        remaining, evidence = self.cli('search-comments', '库存')
        self.assertTrue(remaining)
        self.assertTrue(evidence['evidence']['semantic'], evidence['evidence'])
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/removed['revision']/'facts.db')) as db:
            self.assertFalse(db.execute('SELECT 1 FROM comments WHERE world=?', (world,)).fetchall())
            self.assertFalse(db.execute('PRAGMA foreign_key_check').fetchall())
        self.assertEqual(len(self.cli('search-comments', '测试世界文档', '--revision', revision, '--scope', 'TEST')[0]), 1)


class DisabledJava(rust.RustHarness):
    def test_disabled_java_removes_comments_while_rust_remains_and_can_restore(self):
        user = self.home / 'config.toml'
        if not user.exists():
            user.write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=3221225472\nmax_total_worker_memory_bytes=6442450944\n')
        # Explicit Rust inputs bypass ignore discovery; isolate the newly added Java sources too.
        subprocess.run(['git', 'init', '--quiet'], cwd=self.project, check=True, capture_output=True)
        python.PythonLanguage.add_java(self)
        (self.project/'src/p/CommentOwner.java').write_text('package p;\n/** 语言停用文档 */\nclass CommentOwner {}\n')
        config = self.project / '.code-ana/config.toml'
        # Match the pre-existing mixed-language lifecycle matrix's fixed total request.
        config.write_text(config.read_text() + '\n[resources.index]\nworker_memory_bytes=1073741824\n')
        rust.exporter.export(self.project, self.project/'.code-ana/rust-inputs.json')
        _, first = self.cli('index', '--full', timeout=180)
        self.assertEqual(len(self.cli('search-comments', '语言停用文档')[0]), 1)
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        disabled = original.replace('[languages.java]\nenabled = true', '[languages.java]\nenabled = false')
        self.assertNotEqual(disabled, original)
        config.write_text(disabled)
        _, rejected = self.cli('index', code=1, timeout=180)
        self.assertIn('MODULE_LANGUAGE_NOT_ENABLED', rejected['code'])
        target = json.loads((self.home/'revisions'/first['revision']/'revision.json').read_text())['target']
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as catalog:
            self.assertEqual(catalog.execute('SELECT revision FROM heads WHERE target=?', (target,)).fetchone()[0], first['revision'])
        # Existing strict configuration requires disabled languages' explicit modules to be removed.
        config.write_text(disabled.split('\n[[modules]]', 1)[0] + '\n[resources.index]\nworker_memory_bytes=1073741824\n')
        _, current = self.cli('index', timeout=180)
        self.assertFalse(self.cli('search-comments', '语言停用文档')[0])
        with self.db(current['revision']) as db:
            self.assertGreater(db.execute("SELECT count(*) FROM nodes WHERE language='rust'").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT count(*) FROM comments WHERE language='java'").fetchone()[0], 0)
        self.assertTrue(self.cli('search-comments', '语言停用文档', '--revision', first['revision'])[0])
        config.write_text(original)
        self.cli('index', timeout=180)
        self.assertEqual(len(self.cli('search-comments', '语言停用文档')[0]), 1)


if __name__ == '__main__':
    unittest.main()
