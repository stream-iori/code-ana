#!/usr/bin/env python3
"""Real per-unit execution and one publication; no target compilation or mocks."""
import contextlib
import argparse
import itertools
import os
import pathlib
import time
import json
import sqlite3
import subprocess
import unittest

from graph_product_support import canonical_graph
from prepared_product_support import PreparedHarness
from python_product_support import PythonHarness
from product_support import ROOT, BIN
from backend_test_support import required_backends, requires_backends, select_cases, run_cases


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

    def test_native_view_ordinary_and_prepared_have_the_same_semantics(self):
        self.config = 'schema_version=1\nkind="project"\n[languages.python]\nenabled=true\n[inputs]\n[[inputs.entries]]\nid="python"\nlanguage="python"\nroot="common"\n'
        self.write_config()
        first = self.cli('index', '--prepare', '--full', timeout=180)[1]['revision']
        expected = canonical_graph(self, first)
        unit = self.catalog()['units'][0]
        native = (self.project/unit['config']['path']).read_text()
        self.config = 'schema_version=1\nkind="project"\n'+native
        self.write_config()
        (self.project/'.code-ana/inputs.json').unlink()
        result = self.cli('index', timeout=180)[1]
        ordinary = result['revision']
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
            events = [row[0] for row in db.execute('SELECT body FROM events WHERE task=? ORDER BY seq',(result['report'],))]
        self.assertTrue(any('unit_result python reuse' in event for event in events), events)
        self.assertEqual(canonical_graph(self, ordinary), expected)
        self.assertEqual(canonical_graph(self, first), expected)
        self.assertEqual({u['unit'] for u in self.ownership(ordinary)['units']}, {'python'})

    def test_host_sql_scratch_limit_keeps_ready_and_files_only_heads(self):
        ready = self.config
        unavailable = self.project/'unavailable'
        unavailable.mkdir()
        (unavailable/'README.md').write_text('files only\n')
        files_only = 'schema_version=1\nkind="project"\n[resources.index]\nworker_memory_bytes=1073741824\n[languages.python]\nenabled=true\n[inputs]\n[[inputs.entries]]\nid="unavailable"\nlanguage="python"\nroot="unavailable"\n'
        for available, config in [(True, ready), (False, files_only)]:
            with self.subTest(ready=available):
                self.config = config
                self.write_config()
                old = self.cli('index', '--prepare', '--full', timeout=180)[1]['revision']
                historical = canonical_graph(self, old)
                self.config = config.replace('[resources.index]', '[resources.index]\nhost_scratch_bytes=65536')
                self.write_config()
                _, failed = self.cli('index', '--prepare', '--full', code=1, timeout=180)
                self.assertEqual(failed['code'], 'HOST_SCRATCH_BUDGET')
                self.assertEqual(self.cli('overview')[1]['revision'], old)
                self.assertEqual(canonical_graph(self, old), historical)
                if not available:
                    self.assertTrue(all(unit['mode']=='files_only' for unit in self.catalog()['units']))
                    report = self.cli('report', failed['report'])[0][0]['analysis_resources']['coordinator']
                    self.assertTrue(all(w['state']=='complete' and w['usage']['work']==0 for slot in report['languages'] for w in slot['workers']))
                self.config = config
                self.write_config()
                self.assertEqual(self.cli('index', '--prepare', '--full', timeout=180)[1]['publication'], 'published')

    @requires_backends("rust", "python")
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
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
            before = {row[0] for row in db.execute('SELECT id FROM revisions')}
        result = self.cli('index', '--prepare', timeout=180)[1]
        mixed = result['revision']
        self.assertFalse(result['evidence']['semantic'])
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
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


class OrdinaryMatrix(PythonHarness):
    def setUp(self):
        self.include_python = 'python' in required_backends(self)
        super().setUp()

    def run_backends(self, backends, expect_shared_limit=False):
        if 'java' in backends:
            self.add_java()
        if 'rust' in backends:
            self.add_rust()
        if 'typescript' in backends:
            self.add_typescript()
        config = self.project/'.code-ana/config.toml'
        if 'python' not in backends:
            config.write_text(config.read_text().replace('[languages.python]\nenabled=true', '[languages.python]\nenabled=false'))
        if len(backends) == 4 and not expect_shared_limit:
            # The matrix uses an explicit 1 GiB logical budget. Default 512 MiB
            # shared-host exhaustion has its own retained-head regression below.
            config.write_text(config.read_text()+'\n[resources.index.analysis]\nmax_payload_bytes=1073741824\n')
        if self.include_python:
            self.export()
        first_result = self.publish_once('index', '--full', timeout=180)[1]
        first = first_result['revision']
        historical = canonical_graph(self, first)
        with self.db(first) as db:
            self.assertEqual({r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')}, set(backends))
        ownership = json.loads((self.home/'revisions'/first/'revision.json').read_text())['unit_ownership']
        self.assertEqual({u['unit'] for u in ownership['units']}, set(backends))
        reused = self.publish_once('index', timeout=180)[1]
        self.assertEqual(canonical_graph(self, reused['revision']), historical)
        self.assert_modes(reused, {backend:'reuse' for backend in backends})

        if 'python' in backends:
            source = self.project/'main.py'
            source.write_text(source.read_text()+'\n# matrix changed Python input\n')
        if 'rust' in backends:
            source = self.project/next(s['path'] for unit in ownership['units'] for s in unit['sources'] if s['language']=='rust')
            source.write_text(source.read_text()+'\n// matrix changed Rust input\n')
            script = BIN/'codeana-export-rust-inputs'
            if not script.exists() and 'CODEANA_TEST_BIN' not in os.environ:
                script = ROOT/'scripts/export-rust-inputs.py'
            subprocess.run(['python3', str(script), '--project', str(self.project)], check=True, capture_output=True)
        if 'java' in backends:
            source = self.project/next(s['path'] for unit in ownership['units'] for s in unit['sources'] if s['language']=='java')
            source.write_text(source.read_text()+'\n// matrix changed Java input\n')
        if 'typescript' in backends:
            source = self.project/'matrix.ts'
            source.write_text(source.read_text()+'\n// matrix changed TypeScript input\n')
            self.prepare_typescript()
        if self.include_python:
            self.export()
        if expect_shared_limit:
            with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
                before = {row[0] for row in db.execute('SELECT id FROM revisions')}
            _, failed = self.cli('index', code=1, timeout=180)
            self.assertEqual(failed['code'],'ANALYSIS_RESOURCE_LIMIT')
            self.assertEqual(self.cli('overview')[1]['revision'],reused['revision'])
            with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
                self.assertEqual({row[0] for row in db.execute('SELECT id FROM revisions')},before)
            account = self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
            java = next(slot for slot in account['languages'] if slot['allocation']['language']=='java')
            self.assertEqual(java['host_usage']['failure']['Limit']['resource'],'payload_bytes')
            self.assertTrue(all(w['state']=='complete' and w['usage']['failure'] is None
                                for slot in account['languages'] for w in slot['workers']))
            self.assertEqual(canonical_graph(self,first),historical)
            return
        changed = self.publish_once('index', timeout=180)[1]
        self.assert_modes(changed, {backend:('full' if backend in ('python','typescript') else 'delta') for backend in backends})
        expected = canonical_graph(self, changed['revision'])
        full = self.publish_once('index', '--full', timeout=180)[1]['revision']
        self.assertEqual(canonical_graph(self, full), expected)
        self.assertEqual(canonical_graph(self, first), historical)
        if 'python' in backends:
            (self.project/'main.py').rename(self.project/'matrix_renamed.py')
            self.export()
            renamed = self.publish_once('index', timeout=180)[1]['revision']
            with self.db(renamed) as db:
                self.assertEqual(db.execute("SELECT count(*) FROM nodes WHERE language='python' AND source_file='main.py'").fetchone()[0],0)
                self.assertGreater(db.execute("SELECT count(*) FROM nodes WHERE language='python' AND source_file='matrix_renamed.py'").fetchone()[0],0)
            expected = canonical_graph(self, renamed)
            full = self.publish_once('index', '--full', timeout=180)[1]['revision']
            self.assertEqual(canonical_graph(self, full), expected)
        self.assertFalse(list(self.project.rglob('target')), 'index cannot build the target crate')
        self.assertFalse(list(self.project.rglob('*.class')), 'index cannot emit target Java bytecode')

    @requires_backends('java','rust','python','typescript')
    def test_four_backend_default_quota_retains_head(self):
        self.run_backends(('java','rust','python','typescript'),expect_shared_limit=True)

    def add_typescript(self):
        (self.project/'matrix.ts').write_text('/** 库存 TypeScript */\nexport function matrixTypescript(value: number) { return value + 1; }\nmatrixTypescript(1);\n')
        (self.project/'tsconfig.json').write_text(json.dumps({'compilerOptions':{'noLib':True,'module':'ESNext','moduleResolution':'bundler'},'files':['matrix.ts']}))
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.typescript]\nenabled=true\n[languages.typescript.discovery]\ninputs_manifest=".code-ana/typescript-inputs.json"\n')
        self.prepare_typescript()

    def prepare_typescript(self):
        scratch = self.root/('typescript-prepare-'+str(time.monotonic_ns()))
        output = scratch.with_suffix('.json')
        result = subprocess.run([str(BIN/'codeana-typescript-worker'), '--sdk',os.environ['CODEANA_TYPESCRIPT_SDK'],
            '--prepare-project',str(self.project),'--prepare-config',str(self.project/'tsconfig.json'),
            '--output',str(output),'--scratch',str(scratch),
            '--unit','typescript','--module','ts-app','--world','typescript/main','--publish-language','typescript'],
            capture_output=True,text=True,timeout=60)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        os.replace(output,self.project/'.code-ana/typescript-inputs.json')

    def publish_once(self, *args, **kwargs):
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
            before = {row[0] for row in db.execute('SELECT id FROM revisions')}
        response = self.cli(*args, **kwargs)
        result = response[1]
        self.assertEqual(result['publication'], 'published')
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
            after = {row[0] for row in db.execute('SELECT id FROM revisions')}
        self.assertEqual(after-before, {result['revision']}, 'one task must publish exactly one candidate')
        return response

    def assert_modes(self, result, modes):
        with contextlib.closing(sqlite3.connect(self.home/'catalog.db')) as db:
            events = [row[0] for row in db.execute('SELECT body FROM events WHERE task=? ORDER BY seq', (result['report'],))]
        for unit, mode in modes.items():
            self.assertTrue(any(f'unit_result {unit} {mode}' in event for event in events), (unit,mode,events))


class PreparedTypeScriptMatrix(PreparedHarness):
    copy_fixture = False
    required_backends = frozenset({'java','rust','python','typescript'})

    def test_all_backends_share_prepared_units_and_one_publication(self):
        for name in ('java','rust','python','script'):
            (self.project/name).mkdir()
        (self.project/'java/src/main/java').mkdir(parents=True)
        (self.project/'java/src/main/java/Stock.java').write_text('public class Stock { public static int stock() { return 1; } }\n')
        (self.project/'java/pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>stock</artifactId><version>1</version><properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties></project>')
        (self.project/'rust/src').mkdir()
        (self.project/'rust/Cargo.toml').write_text('[workspace]\n[package]\nname="stock"\nversion="0.1.0"\nedition="2024"\n')
        (self.project/'rust/src/lib.rs').write_text('pub fn stock() -> i32 { 1 }\n')
        subprocess.run(['cargo','generate-lockfile','--offline'],cwd=self.project/'rust',check=True,capture_output=True)
        (self.project/'python/pyproject.toml').write_text('[project]\nname="stock"\nversion="1"\nrequires-python=">=3.12"\n[tool.setuptools.packages.find]\nwhere=["."]\n')
        (self.project/'python/stock.py').write_text('def stock():\n    return 1\n')
        (self.project/'script/a.ts').write_text('export function stock(value: number) { return value + 1; }\n')
        js = self.project/'script/b.js'
        js.write_text('import {stock} from "./a"; stock(1);\n')
        (self.project/'script/tsconfig.json').write_text(json.dumps({'compilerOptions':{'noLib':True,'allowJs':True,'module':'ESNext','moduleResolution':'bundler'},'include':['*.ts','*.js']}))
        self.config = ('schema_version=1\nkind="project"\n[resources.index]\nworker_memory_bytes=3221225472\n'
            '[resources.index.analysis]\nmax_payload_bytes=1073741824\n'
            '[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\nclasspath="existing"\nvm_classpath=true\n'
            + ''.join('[languages.'+name+']\nenabled=true\n' for name in ('rust','python','javascript','typescript'))+'[inputs]\n')
        for name,language in [('java','java'),('rust','rust'),('python','python'),('script','typescript')]:
            self.config += f'[[inputs.entries]]\nid="{name}"\nlanguage="{language}"\nroot="{name}"\n'
            if name == 'java': self.config += '[inputs.entries.java]\noffline=true\n'
        self.write_config()
        def publish(*flags):
            with self.db_catalog() as db:
                before = {r[0] for r in db.execute('SELECT id FROM revisions')}
            response = self.cli('index',*flags,timeout=180)
            with self.db_catalog() as db:
                self.assertEqual({r[0] for r in db.execute('SELECT id FROM revisions')}-before,{response[1]['revision']})
            return response[1]
        # Start the isolated daemon and catalog before counting publications.
        self.cli('hello')
        first = publish('--prepare','--full')
        self.assertEqual({u['backend'] for u in self.catalog()['units']},{'java','rust','python','typescript'})
        self.assertEqual(len(self.catalog()['units']),4)
        self.assertTrue(all(u['mode']=='semantic' for u in self.catalog()['units']),self.catalog())
        expected = canonical_graph(self,first['revision'])
        repeated = publish('--prepare')
        ordinary = publish()
        self.assertEqual(canonical_graph(self,repeated['revision']),expected)
        self.assertEqual(canonical_graph(self,ordinary['revision']),expected)
        with self.db_catalog() as db:
            events = '\n'.join(r[0] for r in db.execute('SELECT body FROM events WHERE task=?',(ordinary['report'],)))
        for unit in self.catalog()['units']:
            self.assertIn('unit_result '+unit['id']+' reuse',events)
        js.write_text('import {stock} from "./a"; stock(2); stock(3);\n')
        self.cli('index',code=1,timeout=180)
        self.assertEqual(self.cli('overview')[1]['revision'],ordinary['revision'])
        changed = publish('--prepare')
        full = publish('--full')
        self.assertEqual(canonical_graph(self,changed['revision']),canonical_graph(self,full['revision']))
        self.assertEqual(canonical_graph(self,first['revision']),expected)
        self.assertFalse(list(self.project.rglob('target')))
        self.assertFalse(list(self.project.rglob('*.class')))

    def db_catalog(self):
        return contextlib.closing(sqlite3.connect(self.home/'catalog.db'))


def ordinary_case(backends):
    @requires_backends(*backends)
    def test(self):
        self.run_backends(backends)
    return test


for size in (1,2,3):
    for combination in itertools.combinations(('java','rust','python'), size):
        setattr(OrdinaryMatrix, 'test_'+'_'.join(combination), ordinary_case(combination))


for combination in [('java','typescript'),('rust','typescript'),('python','typescript'),('java','rust','python','typescript')]:
    setattr(OrdinaryMatrix, 'test_'+'_'.join(combination), ordinary_case(combination))

def cases(backends):
    from java_jvm_product_gate import flatten
    tests = []
    for cls in (MultilangAnalysis, OrdinaryMatrix, PreparedTypeScriptMatrix):
        tests.extend(flatten(unittest.defaultTestLoader.loadTestsFromTestCase(cls)))
    return select_cases(tests, backends)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backends', default='java,rust,python', help='explicit supported scope; Java requires its real installed SDK')
    parser.add_argument('--evidence', type=pathlib.Path)
    args = parser.parse_args()
    backends = args.backends.split(',')
    if not backends or len(set(backends))!=len(backends) or set(backends)-{'java','rust','python','typescript'}:
        parser.error('declare distinct java,rust,python,typescript backends')
    tests, excluded = cases(backends)
    if not tests:
        parser.error('empty declared product scope')
    return run_cases(tests, backends, excluded, args.evidence)


if __name__ == '__main__':
    raise SystemExit(main())
