#!/usr/bin/env python3
"""Actual native product semantics, history and conservative unit updates."""
import argparse
import json
import pathlib
import sqlite3
import unittest
from prepared_product_support import PreparedHarness
from graph_product_support import canonical_graph
from backend_test_support import run_cases, select_cases


class TypeScriptProduct(PreparedHarness):
    copy_fixture = False
    required_backends = frozenset({'typescript'})

    def setUp(self):
        super().setUp()
        self.script = self.project/'nested'
        self.script.mkdir()
        self.a = self.script/'a.ts'
        self.b = self.script/'b.js'
        self.a.write_bytes('\ufeff/** 库存文档 中文😀 */\r\nexport function greet(value: number) { return value + 1; }\r\n'.encode())
        self.b.write_text('/** 库存调用说明 */\nimport {greet} from "./a";\nexport function exercise() { greet(1); greet(2); }\nexercise();')
        (self.script/'tsconfig.json').write_text(json.dumps({
            'compilerOptions': {'noLib':True,'allowJs':True,'checkJs':True,
                                'module':'ESNext','moduleResolution':'bundler'},
            'include':['*.ts','*.js']}))
        self.config = '''schema_version=1
kind="project"
[resources.index]
worker_memory_bytes=3221225472
[languages.javascript]
enabled=true
[languages.typescript]
enabled=true
[inputs]
[[inputs.entries]]
id="script"
language="typescript"
root="nested"
manifest="tsconfig.json"
[[modules]]
id="app"
language="typescript"
root="nested"
'''
        self.write_config()

    def index(self, *args, **kwargs):
        return self.cli('index', *args, timeout=180, **kwargs)

    def test_nested_program_cross_tag_calls_source_comments_and_history(self):
        first = self.index('--prepare','--full')[1]['revision']
        self.assertEqual(len(self.catalog()['units']),1)
        ownership = self.ownership(first)['units']
        self.assertEqual(len(ownership),1)
        self.assertEqual(set(ownership[0]['source_languages']),{'javascript','typescript'})
        declarations = self.cli('search-code','greet')[0]
        target = next(d for d in declarations if d['name']=='greet' and d['source']=='nested/a.ts')
        source = self.cli('source',target['id'],'--revision',first)[0]
        self.assertTrue(source)
        self.assertIn('nested/a.ts',json.dumps(source))
        self.assertTrue(self.cli('calls',target['id'],'--direction','in')[0])
        self.assertTrue(self.cli('calls','exercise','--direction','out')[0])
        self.assertTrue(self.cli('call-sites','exercise')[0])
        with self.db(first) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM call_site_targets WHERE target_id=?",(target['id'],)).fetchone()[0],2)
            self.assertEqual({row[0] for row in db.execute("SELECT DISTINCT source_file FROM nodes WHERE source_file IS NOT NULL")},{'nested/a.ts','nested/b.js'})
        comments = self.cli('search-comments','库存','--max-records','1')
        self.assertEqual(len(comments[0]),1)
        self.assertTrue(comments[1]['cursor'])
        original = canonical_graph(self,first)
        self.cli('snapshot','before','--kind','analysis')
        retained = self.cli('overview')[1]['revision']
        self.a.write_text('/** 更新库存文档 */\nexport function greet(value: number) { return value + 2; }\n')
        self.index(code=1)
        self.assertEqual(self.cli('overview')[1]['revision'],retained)
        changed = self.index('--prepare')[1]['revision']
        self.assertEqual(next(d for d in self.cli('search-code','greet')[0] if d['name']=='greet' and d['source']=='nested/a.ts')['id'],target['id'])
        self.cli('snapshot','after','--kind','analysis')
        self.assertTrue(self.cli('diff','--base','before','--target','after')[0])
        remaining = self.cli('search-comments','--cursor',comments[1]['cursor'],'--max-records','100')[0]
        self.assertTrue(remaining)
        self.assertEqual(canonical_graph(self,first),original)
        self.a.unlink()
        self.assertTrue(self.cli('source',target['id'],'--revision',first)[0])
        self.assertTrue(self.cli('search-comments','库存文档','--revision',first)[0])
        self.assertNotEqual(changed,first)

    def test_reprepare_and_ordinary_input_reuse_equal_full_and_delete_to_zero(self):
        first = self.index('--prepare','--full')[1]['revision']
        expected = canonical_graph(self,first)
        repeated = self.index('--prepare')[1]
        self.assertEqual(canonical_graph(self,repeated['revision']),expected)
        unit = self.catalog()['units'][0]
        self.config = 'schema_version=1\nkind="project"\n'+(self.project/unit['config']['path']).read_text()
        self.write_config()
        (self.project/'.code-ana/inputs.json').unlink()
        ordinary = self.index()[1]['revision']
        self.assertEqual(canonical_graph(self,ordinary),expected)
        with sqlite3.connect(self.home/'catalog.db') as db:
            events = '\n'.join(row[0] for row in db.execute('SELECT body FROM events'))
        self.assertIn('unit_result '+unit['id']+' reuse',events)
        full = self.index('--full')[1]['revision']
        self.assertEqual(canonical_graph(self,full),expected)
        self.config = 'schema_version=1\nkind="project"\n'
        self.write_config()
        empty = self.index()[1]['revision']
        self.assertFalse(self.cli('search-code','greet')[0])
        self.assertFalse(self.cli('search-comments','库存')[0])
        self.assertTrue(self.cli('search-code','greet','--revision',first)[0])
        self.assertTrue(all(not u['sources'] for u in self.ownership(empty)['units']))

    def test_strict_failure_retains_head_and_reports_known_account(self):
        old = self.index('--prepare','--full')[1]['revision']
        _, failed = self.index('--full','--strict',code=3)
        self.assertEqual(failed['publication'],'retained')
        self.assertEqual(self.cli('overview')[1]['revision'],old)
        report = self.cli('report',failed['report'])[0][0]
        coordinator = report['analysis_resources']['coordinator']
        self.assertTrue(coordinator['complete'])
        self.assertEqual([s['allocation']['language'] for s in coordinator['languages']],['typescript'])
        self.assertTrue(self.cli('doctor')[0][0]['typescript_worker']['binary_present'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=pathlib.Path)
    args=parser.parse_args()
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(TypeScriptProduct)
    tests,excluded=select_cases(list(suite),['typescript'])
    return run_cases(tests,['typescript'],excluded,args.evidence)

if __name__=='__main__':
    raise SystemExit(main())
