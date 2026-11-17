#!/usr/bin/env python3
"""Real per-unit execution and one publication; no target compilation or mocks."""
import json
import sqlite3
import subprocess
import unittest

from graph_product_support import canonical_graph
from prepared_product_support import PreparedHarness


class MultilangAnalysis(PreparedHarness):
    def canonical(self, revision):
        graph = canonical_graph(self, revision)
        with self.db(revision) as db:
            for table in ('rust_source_states', 'rust_analysis_dependencies'):
                graph[table] = sorted(db.execute('SELECT * FROM '+table).fetchall(), key=repr)
        comments, _ = self.cli('search-comments', '准备库存文档', '--revision', revision,
                               '--max-output-bytes', '1048576')
        graph['comments'] = sorted(json.dumps(row['comment'], sort_keys=True, ensure_ascii=False)
                                   for row in comments)
        return graph

    def test_five_update_modes_share_one_candidate_and_match_full(self):
        self.add_python_comment_inputs()
        self.config += '\n[languages.rust]\nenabled=true\n'
        for name in ('rust-a', 'rust-b'):
            path = self.project/name
            (path/'src').mkdir(parents=True)
            (path/'Cargo.toml').write_text('[workspace]\n[package]\nname="same"\nversion="0.1.0"\nedition="2024"\n')
            (path/'src/lib.rs').write_text('/// 准备库存文档\npub fn '+name.replace('-', '_')+'() -> u32 { 1 }\n')
            subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=path,
                           check=True, capture_output=True)
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="rust"\nroot="{name}"\n'
        self.write_config()
        first = self.cli('index', '--prepare', timeout=180)[1]['revision']
        historical = self.canonical(first)
        self.cli('snapshot', 'five-modes-before', '--kind', 'analysis')

        source = self.project/'app-a/main_a.py'
        source.write_text(source.read_text()+'\n# changed Python input\n')
        source = self.project/'rust-a/src/lib.rs'
        source.write_text(source.read_text().replace('{ 1 }', '{ 2 }'))
        self.config = self.config.replace('[[inputs.entries]]\nid="app-b"\nlanguage="python"\nroot="app-b"\n', '')
        unavailable = self.project/'unavailable'
        unavailable.mkdir()
        (unavailable/'pyproject.toml').write_text('[broken')
        (unavailable/'missing.py').write_text('"""准备库存文档"""\nvalue = 1\n')
        self.config += '\n[[inputs.entries]]\nid="unavailable"\nlanguage="python"\nroot="unavailable"\n'
        self.write_config()
        with sqlite3.connect(self.home/'catalog.db') as db:
            before = {row[0] for row in db.execute('SELECT id FROM revisions')}
        result = self.cli('index', '--prepare', timeout=180)[1]
        mixed = result['revision']
        self.assertFalse(result['evidence']['semantic'])
        with sqlite3.connect(self.home/'catalog.db') as db:
            after = {row[0] for row in db.execute('SELECT id FROM revisions')}
            events = [row[0] for row in db.execute('SELECT body FROM events WHERE task=? ORDER BY seq',
                                                  (result['report'],))]
        self.assertEqual(after-before, {mixed}, 'all units publish exactly one revision')
        for unit, mode in [('app-a', 'full'), ('rust-a', 'delta'), ('rust-b', 'reuse'),
                           ('common', 'reuse'), ('app-b', 'removed'), ('unavailable', 'files_only')]:
            self.assertTrue(any(f'unit_update {unit} {mode}' in event for event in events),
                            (unit, mode, events))
            self.assertTrue(any(f'unit_result {unit} {mode}' in event for event in events),
                            ('verified outcome', unit, mode, events))
        report = self.cli('report', result['report'])[0][0]
        workers = {w['grant']['id'].rsplit('/', 1)[-1]: w
                   for slot in report['analysis_resources']['coordinator']['languages']
                   for w in slot['workers']}
        for unit in ('rust-b', 'common'):
            self.assertEqual(workers[unit]['phase'], 'reused')
        for unit in ('app-b', 'unavailable'):
            self.assertEqual(workers[unit]['state'], 'complete')
            self.assertEqual(workers[unit]['usage']['work'], 0)
        self.assertEqual({unit['unit'] for unit in self.ownership(mixed)['units']},
                         {'app-a', 'common', 'rust-a', 'rust-b', 'unavailable'})
        with self.db(mixed) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM nodes WHERE module='app-b'").fetchone()[0], 0)
            removed = db.execute("SELECT unit_pk FROM unit_catalog WHERE unit_id='app-b'").fetchone()[0]
            for (table,) in db.execute("SELECT name FROM sqlite_schema WHERE type='table'").fetchall():
                if any(row[2] == 'unit_catalog' and row[3] == 'unit_pk' for row in db.execute(f'PRAGMA foreign_key_list("{table}")')):
                    self.assertEqual(db.execute(f'SELECT count(*) FROM "{table}" WHERE unit_pk=?', (removed,)).fetchone()[0], 0, table)
        expected = self.canonical(mixed)
        full = self.cli('index', '--full', timeout=180)[1]['revision']
        self.assertEqual(self.canonical(full), expected)
        self.assertEqual(self.canonical(first), historical)
        self.assertFalse(list(self.project.rglob('target')), 'analysis must not build target crates')


if __name__ == '__main__':
    unittest.main()
