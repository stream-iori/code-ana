#!/usr/bin/env python3
"""Unified input preparation through real workers; no target compilation."""
import contextlib
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest
from product_support import ProductHarness, ROOT, BIN, JDK


class PreparedInputs(ProductHarness):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='prepared-inputs-', dir=ROOT/'target')
        self.root = pathlib.Path(self.temp.name)
        self.project, self.home = self.root/'project', self.root/'home'
        fixture = pathlib.Path(os.environ.get('CODEANA_TEST_FIXTURES', ROOT/'fixtures'))/'python/preparation'
        if not fixture.exists():
            fixture = ROOT/'fixtures/python/preparation'
        shutil.copytree(fixture, self.project)
        subprocess.run(['git', 'init', '--quiet'], cwd=self.project, check=True)
        (self.project/'.code-ana').mkdir()
        self.home.mkdir(mode=0o700)
        (self.home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=3221225472\nmax_total_worker_memory_bytes=6442450944\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(JDK))+'\n')
        self.config = '''schema_version=1
kind="project"
[resources.index]
worker_memory_bytes=3221225472
[languages.python]
enabled=true
[inputs]
[[inputs.entries]]
id="app-a"
language="python"
root="app-a"
[inputs.entries.python]
dependencies=["common"]
[[inputs.entries]]
id="app-b"
language="python"
root="app-b"
[[inputs.entries]]
id="common"
language="python"
root="common"
'''
        self.write_config()
        self.daemon, self.log = None, None
        self.addCleanup(self.cleanup)

    def write_config(self):
        (self.project/'.code-ana/config.toml').write_text(self.config)

    def stop(self, kill=False):
        if self.home.exists() and (self.home/'endpoint.json').exists():
            subprocess.run([str(BIN/'codeana-cli'), '--home', str(self.home), 'daemon', 'stop'], capture_output=True, timeout=15)
        super().stop(kill)

    def daemon_log(self):
        path = self.home/'daemon.log'
        return path.read_text(errors='replace')[-12000:] if path.exists() else ''

    def db(self, revision):
        return contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db'))

    def catalog(self):
        return json.loads((self.project/'.code-ana/inputs.json').read_text())

    def ownership(self, revision):
        return json.loads((self.home/'revisions'/revision/'revision.json').read_text())['unit_ownership']

    def test_new_semantic_index_still_requires_an_explicit_language(self):
        self.config = 'schema_version=1\nkind="project"\n'
        self.write_config()
        _, failed = self.cli('index', code=1)
        self.assertEqual(failed['code'], 'NO_ENABLED_LANGUAGE')
        with sqlite3.connect(self.home/'catalog.db') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM heads').fetchone()[0], 0)

    def test_legacy_ownership_requires_full_and_retains_head_until_rebuilt(self):
        _, initial = self.cli('index', '--prepare', timeout=120)
        revision = initial['revision']
        self.assertEqual(self.ownership(revision)['version'], 2)
        self.stop()
        with sqlite3.connect(self.home/'catalog.db') as db:
            body = json.loads(db.execute('SELECT body FROM revisions WHERE id=?', (revision,)).fetchone()[0])
            body.pop('unit_ownership')
            db.execute('UPDATE revisions SET body=? WHERE id=?', (json.dumps(body), revision))
        (self.home/'revisions'/revision/'revision.json').write_text(json.dumps(body))
        _, failed = self.cli('index', code=1)
        self.assertEqual(failed['code'], 'UNIT_OWNERSHIP_REBUILD_REQUIRED')
        with sqlite3.connect(self.home/'catalog.db') as db:
            self.assertEqual(db.execute('SELECT revision FROM heads').fetchall(), [(revision,)])
        _, rebuilt = self.cli('index', '--full', timeout=120)
        self.assertNotEqual(rebuilt['revision'], revision)
        self.assertEqual({u['unit'] for u in self.ownership(rebuilt['revision'])['units']}, {'app-a','app-b','common'})

    def test_v1_ownership_requires_explicit_rebuild_and_preserves_historical_queries(self):
        _, initial = self.cli('index', '--prepare', timeout=120)
        revision = initial['revision']
        self.stop()
        with sqlite3.connect(self.home/'catalog.db') as db:
            body = json.loads(db.execute('SELECT body FROM revisions WHERE id=?', (revision,)).fetchone()[0])
            body['unit_ownership']['version'] = 1
            for unit in body['unit_ownership']['units']:
                unit.pop('inputs')
                unit.pop('scopes')
            db.execute('UPDATE revisions SET body=? WHERE id=?', (json.dumps(body), revision))
        (self.home/'revisions'/revision/'revision.json').write_text(json.dumps(body))
        _, failed = self.cli('index', code=1)
        self.assertEqual(failed['code'], 'UNIT_OWNERSHIP_REBUILD_REQUIRED')
        with sqlite3.connect(self.home/'catalog.db') as db:
            self.assertEqual(db.execute('SELECT revision FROM heads').fetchall(), [(revision,)])
        results, terminal = self.cli('resolve', 'from_a', '--revision', revision)
        self.assertTrue(results)
        self.assertEqual(terminal['revision'], revision)
        _, rebuilt = self.cli('index', '--full', timeout=120)
        self.assertNotEqual(rebuilt['revision'], revision)
        self.assertEqual(self.ownership(rebuilt['revision'])['version'], 2)

    def test_old_graph_schema_requires_full_rebuild_and_retains_the_old_files(self):
        _, initial = self.cli('index', '--prepare', timeout=120)
        revision = initial['revision']
        old_graph = self.home/'revisions'/revision/'facts.db'
        self.stop()
        with sqlite3.connect(old_graph) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 15)
            db.execute('PRAGMA user_version=14')
        _, failed = self.cli('index', code=1)
        self.assertEqual(failed['code'], 'GRAPH_SCHEMA_REBUILD_REQUIRED')
        with sqlite3.connect(self.home/'catalog.db') as db:
            self.assertEqual(db.execute('SELECT revision FROM heads').fetchall(), [(revision,)])
        _, unavailable = self.cli('resolve', 'from_a', '--revision', revision, code=1)
        self.assertEqual(unavailable['code'], 'GRAPH_SCHEMA_REBUILD_REQUIRED')
        _, rebuilt = self.cli('index', '--full', timeout=120)
        self.assertNotEqual(rebuilt['revision'], revision)
        with sqlite3.connect(self.home/'revisions'/rebuilt['revision']/'facts.db') as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 15)
        with sqlite3.connect(old_graph) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 14)
        self.assertTrue(self.cli('resolve', 'from_a')[0])

    def test_disabling_all_units_removes_semantics_without_a_fallback_worker(self):
        _, initial = self.cli('index', '--prepare', timeout=120)
        self.config = 'schema_version=1\nkind="project"\n'
        self.write_config()
        _, removed = self.cli('index', timeout=120)
        self.assertNotEqual(initial['revision'], removed['revision'])
        self.assertEqual(self.ownership(removed['revision'])['units'], [])
        with self.db(removed['revision']) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM nodes').fetchone()[0], 0)
        report = self.cli('report', removed['report'])[0][0]
        self.assertEqual(report['contexts'], [])
        slots = report['analysis_resources']['coordinator']['languages']
        self.assertEqual([s['allocation']['language'] for s in slots], ['python'])
        self.assertEqual(len(slots[0]['workers']), 3)
        self.assertTrue(all(w['state']=='complete' and w['usage']['work']==0 for w in slots[0]['workers']))

    def add_python_comment_inputs(self):
        for relative in ('app-a/main_a.py','app-b/main_b.py'):
            source=self.project/relative
            source.write_text('"""准备库存文档"""\n'+source.read_text())

    def test_native_unit_views_and_old_catalog_rejection(self):
        import tomllib
        revision = self.cli('index', '--prepare', timeout=120)[1]['revision']
        catalog = self.catalog()
        self.assertEqual(catalog['schema_version'], 2)
        for unit in catalog['units']:
            native = tomllib.loads((self.project / unit['config']['path']).read_text())
            self.assertEqual(set(native), {'discovery', 'languages', 'modules'})
            self.assertEqual(set(native['languages']), {unit['language']})
        catalog['schema_version'] = 1
        (self.project / '.code-ana/inputs.json').write_text(json.dumps(catalog))
        _, terminal = self.cli('index', code=1, timeout=120)
        self.assertIn('INPUT_CATALOG_STALE', json.dumps(terminal))
        self.assertEqual(self.cli('overview')[1]['revision'], revision)

    def test_unavailable_comment_inputs_are_scoped_and_frozen(self):
        self.add_python_comment_inputs()
        self.cli('index','--prepare',timeout=120)
        pyproject=self.project/'app-b/pyproject.toml'
        original=pyproject.read_text()
        pyproject.write_text('[broken')
        revision=self.cli('index','--prepare',timeout=120)[1]['revision']
        manifest=json.loads((self.home/'revisions'/revision/'revision.json').read_text())['manifest']['files']
        healthy_world=next(f['world'] for f in manifest if f['path']=='app-a/main_a.py' and f['language']=='python')
        files_world=next(f['world'] for f in manifest if f['path']=='app-b/main_b.py')
        results={}
        def check(name,options,hits,complete,enumeration=True):
            rows,terminal=self.cli('search-comments','准备库存文档',*options)
            self.assertEqual(len(rows),hits,(name,terminal))
            self.assertEqual(terminal['evidence']['semantic'],complete,(name,terminal))
            self.assertEqual(terminal['evidence']['enumeration'],enumeration,(name,terminal))
            results[name]={'hits':len(rows),'evidence':terminal['evidence']}
        check('all',[],1,False)
        check('healthy-path',['--path','app-a/**'],1,True)
        check('missing-path',['--path','app-b/**'],0,False)
        check('healthy-module',['--module','app-a'],1,True)
        check('missing-module',['--module','app-b'],0,False,False)
        check('healthy-world',['--world',healthy_world],1,True)
        check('files-world',['--world',files_world,'--path','app-b/**'],0,False)
        check('missing-main',['--scope','MAIN','--path','app-b/**'],0,False)
        _,code=self.cli('search-code','准备库存文档','--in','comments')
        self.assertFalse(code['evidence']['semantic'])
        # Recovery changes the current revision, never the frozen gap's meaning.
        pyproject.write_text(original)
        self.cli('index','--prepare',timeout=120)
        check('recovered',[],2,True)
        check('historical',['--revision',revision],1,False)
        check('historical-healthy',['--revision',revision,'--path','app-a/**'],1,True)
        destination=os.environ.get('CODEANA_COMMENT_GAP_EVIDENCE')
        if destination:
            pathlib.Path(destination).write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')

    def test_prepared_config_bytes_remain_strict_and_comments_have_no_window(self):
        self.add_python_comment_inputs()
        first=self.cli('index','--prepare',timeout=120)[1]['revision']
        catalog_bytes=(self.project/'.code-ana/inputs.json').read_bytes()
        profile=json.loads((self.home/'revisions'/first/'revision.json').read_text())['profile']
        before=self.cli('search-comments','准备库存文档','--module','app-a')[0]
        self.assertTrue(before)
        self.assertTrue(all('source' not in row for row in before))
        self.assertEqual((self.project/'.code-ana/inputs.json').read_bytes(),catalog_bytes)
        # Genuine input-policy changes still require explicit preparation.
        self.config=self.config.replace('dependencies=["common"]','dependencies=[]')
        self.write_config()
        _,failure=self.cli('index',code=1,timeout=120)
        self.assertIn('INPUT_CATALOG_STALE',str(failure))

    def test_repeat_preparation_keeps_semantics_and_reuses_across_generations(self):
        self.add_python_comment_inputs()
        _, first = self.cli('index', '--prepare', timeout=120)
        profile = json.loads((self.home/'revisions'/first['revision']/'revision.json').read_text())['profile']
        catalog_before = self.catalog()
        before = self.cli('search-comments', '准备库存文档', '--strict')[0]
        _, second = self.cli('index', '--prepare', timeout=120)
        revision = json.loads((self.home/'revisions'/second['revision']/'revision.json').read_text())
        self.assertEqual(profile, revision['profile'])
        self.assertNotEqual(catalog_before, self.catalog())
        self.assertNotEqual(first['revision'], second['revision'])
        self.assertEqual(self.cli('search-comments', '准备库存文档', '--strict')[0], before)
        report = self.cli('report', second['report'])[0][0]
        self.assertTrue(all(w['phase']=='reused' for slot in report['analysis_resources']['coordinator']['languages'] for w in slot['workers']))
        (self.project/'app-a/main_a.py').write_text((self.project/'app-a/main_a.py').read_text()+'\n# changed source\n')
        self.cli('index', code=1, timeout=120)
        self.assertEqual(self.cli('overview')[1]['revision'], second['revision'])
        _, third = self.cli('index', '--prepare', timeout=120)
        report = self.cli('report', third['report'])[0][0]
        self.assertTrue(any(w['phase']!='reused' for slot in report['analysis_resources']['coordinator']['languages'] for w in slot['workers']))

    def test_python_isolation_explicit_dependency_reuse_and_global_report(self):
        self.add_python_comment_inputs()
        self.assertFalse((self.project/'.code-ana/inputs.json').exists())
        doctor = self.cli('doctor')[0][0]
        self.assertEqual(doctor['preparation']['catalog_state'], 'missing')
        self.assertFalse((self.project/'.code-ana/inputs.json').exists())
        _, result = self.cli('index', '--prepare', timeout=120)
        first = result['revision']
        comments,_=self.cli('search-comments','准备库存文档','--strict')
        self.assertEqual({r['comment']['module'] for r in comments},{'app-a','app-b'})
        self.assertTrue(all(not r['comment']['source_file'].startswith('/') for r in comments))
        with self.db(first) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM index_diagnostics WHERE code='missing-import'").fetchone()[0], 0)
            rows = db.execute("SELECT DISTINCT module FROM nodes WHERE language='python'").fetchall()
            self.assertEqual({r[0] for r in rows}, {'app-a', 'app-b', 'common'})
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
        self.assertTrue(self.cli('resolve', 'from_a')[0])
        self.assertTrue(self.cli('resolve', 'from_b')[0])
        reused, second = self.cli('index', timeout=120)
        self.assertEqual(reused[0]['mode'], 'delta')
        report = self.cli('report', second['report'])[0][0]
        self.assertEqual(report['schema_version'], 4)
        self.assertEqual(report['diagnostics'], [])
        self.assertEqual({c['unit'] for c in report['contexts']}, {'app-a','app-b','common'})
        self.assertTrue(all(c['details']['backend']=='python' and c['source_languages']==['python'] and c['semantic_digest'] for c in report['contexts']))
        self.assertNotIn('context', report)
        self.assertEqual(len(report['global']['units']), 3)
        self.assertNotIn('Full', report['effective'])
        markdown = self.markdown(second['report'])
        self.assertIn('| Execution | Quality |', markdown)
        self.assertIn('| Instance | Backend | Source languages | Worlds |', markdown)
        (self.project/'app-a/pyproject.toml').write_text('[broken')
        self.cli('index', code=1)
        self.assertEqual(self.cli('overview')[1]['revision'], second['revision'])

    def test_single_unit_stream_with_manual_roots_and_no_pyproject(self):
        (self.project/'app-b/pyproject.toml').unlink()
        self.config = '''schema_version=1
kind="project"
[resources.index]
worker_memory_bytes=1073741824
[languages.python]
enabled=true
[inputs]
[[inputs.entries]]
id="solo"
language="python"
root="app-b"
[[modules]]
id="custom-owner"
language="python"
root="app-b"
source_roots=[{path=".",scope="MAIN"}]
'''
        self.write_config()
        _, result = self.cli('index', '--prepare', timeout=120)
        report=self.cli('report', result['report'])[0][0]
        self.assertEqual({c['unit'] for c in report['contexts']}, {u['id'] for u in self.catalog()['units']})
        self.assertEqual({c['details']['backend'] for c in report['contexts']}, {'python'})
        self.assertEqual(len({c['unit'] for c in report['contexts']}), len(report['contexts']))
        with self.db(result['revision']) as db:
            self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes")}, {'custom-owner'})
        self.assertTrue(self.cli('resolve', 'from_b')[0])
        reused, _ = self.cli('index', timeout=120)
        self.assertEqual(reused[0]['mode'], 'delta')

    def test_shared_environment_keeps_file_ownership_and_rejects_conflicts(self):
        self.config = self.config.replace('dependencies=["common"]', 'dependencies=["common"]\nenvironment="shared"')
        self.config += '\n[inputs.entries.python]\nenvironment="shared"\n'
        self.write_config()
        _, result = self.cli('index', '--prepare', timeout=120)
        self.assertEqual(len(self.catalog()['units']), 2)
        with self.db(result['revision']) as db:
            self.assertEqual(db.execute("SELECT count(DISTINCT module) FROM nodes WHERE source_file LIKE 'common/%'").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT DISTINCT module FROM nodes WHERE source_file='common/shared.py'").fetchone()[0], 'common')
        previous = (self.project/'.code-ana/inputs.json').read_bytes()
        self.config += 'dependency_roots=["different-environment"]\n'
        self.write_config()
        self.cli('index', '--prepare', code=1, timeout=120)
        self.assertEqual(previous, (self.project/'.code-ana/inputs.json').read_bytes())

    def test_unavailable_context_publishes_partial_strict_rejects_and_recovery_clears_old_facts(self):
        self.cli('index', '--prepare', timeout=120)
        pyproject = self.project/'app-b/pyproject.toml'
        original = pyproject.read_bytes()
        pyproject.write_text('[broken')
        _, partial = self.cli('index', '--prepare', timeout=120)
        self.assertFalse(partial['evidence']['semantic'])
        self.assertEqual(next(u for u in self.catalog()['units'] if u['id']=='app-b')['mode'], 'files_only')
        missing, unavailable = self.cli('resolve', 'from_b', code=1)
        self.assertFalse(missing)
        self.assertEqual(unavailable['code'], 'SEMANTIC_INPUTS_UNAVAILABLE')
        self.assertTrue(self.cli('resolve', 'from_a')[0])
        _, rejected = self.cli('index', '--strict', code=3, timeout=120)
        self.assertEqual(self.cli('overview')[1]['revision'], partial['revision'])
        report = self.cli('report', rejected['report'])[0][0]
        self.assertFalse(report['global']['measurements_available'])
        self.assertEqual(report['result']['gate'], 'rejected')
        pyproject.write_bytes(original)
        self.cli('index', '--prepare', timeout=120)
        self.assertTrue(self.cli('resolve', 'from_b')[0])

    def test_multiple_cargo_workspaces_and_unavailable_dependency_graph(self):
        for name in ('rust-a', 'rust-b'):
            path = self.project/name
            (path/'src').mkdir(parents=True)
            (path/'Cargo.toml').write_text('[workspace]\n[package]\nname="same"\nversion="0.1.0"\nedition="2024"\n')
            (path/'src/lib.rs').write_text('pub fn '+name.replace('-', '_')+'() -> u32 { 1 }\n')
            subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=path, check=True, capture_output=True)
        self.config += '\n[languages.rust]\nenabled=true\n'
        for name in ('rust-a', 'rust-b'):
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="rust"\nroot="{name}"\n'
        self.write_config()
        _, result = self.cli('index', '--prepare', timeout=180)
        with self.db(result['revision']) as db:
            self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes WHERE language='rust'")}, {'rust-a', 'rust-b'})
        self.assertTrue(self.cli('resolve', 'rust_a')[0])
        (self.project/'rust-a/Cargo.toml').write_text((self.project/'rust-a/Cargo.toml').read_text()+'[dependencies]\nmissing_fixture_crate="=99.99.99"\n')
        _, partial = self.cli('index', '--prepare', timeout=180)
        self.assertFalse(partial['evidence']['semantic'])
        missing, unavailable = self.cli('resolve', 'rust_a', code=1)
        self.assertFalse(missing)
        self.assertEqual(unavailable['code'], 'SEMANTIC_INPUTS_UNAVAILABLE')
        self.assertTrue(self.cli('resolve', 'rust_b')[0])

    def test_all_unavailable_keeps_text_and_reports_capture_failure(self):
        for name in ('app-a', 'app-b', 'common'):
            (self.project/name/'pyproject.toml').write_text('[broken')
        _, result = self.cli('index', '--prepare', timeout=120)
        self.assertFalse(result['evidence']['semantic'])
        self.assertTrue(self.cli('search-text', 'from_a')[0])
        _, unavailable = self.cli('search-code', 'from_a')
        self.assertFalse(unavailable['evidence']['semantic'])
        unit = self.catalog()['units'][0]
        self.assertEqual(unit['mode'], 'files_only')
        catalog = self.project/'.code-ana/inputs.json'
        value = self.catalog()
        value['config_sha256'] = '0'*64
        catalog.write_text(json.dumps(value))
        _, failed = self.cli('index', code=1)
        report = self.cli('report', failed['report'])[0][0]
        self.assertEqual(report['result']['execution'], 'failed')
        self.assertFalse(report['global']['measurements_available'])

    @unittest.skipUnless(os.environ.get('CODEANA_TEST_REAL_JAVA') == '1', 'explicit Native Java SDK gate')
    def test_late_preparation_failure_reaps_started_units_and_retains_head(self):
        self.config += '\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\nclasspath="existing"\nvm_classpath=true\n'
        for name, kind, method in [('java-first','First','first'), ('java-last','Last','last')]:
            path = self.project/name
            (path/'src/main/java').mkdir(parents=True)
            (path/'pom.xml').write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>{name}</artifactId><version>1</version><properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties></project>')
            (path/f'src/main/java/{kind}.java').write_text(f'public class {kind} {{ public static int {method}(){{return 1;}} }}\n')
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="java"\nroot="{name}"\n[inputs.entries.java]\noffline=true\n'
        self.config += '\n[[modules]]\nid="java-last"\nlanguage="java"\nroot="java-last"\n[modules.analysis]\ntoolchain="java8"\n'
        self.write_config()
        _, initial = self.cli('index', '--prepare', timeout=180)
        self.config = self.config.replace('[modules.analysis]\ntoolchain="java8"', '[modules.analysis]\ntoolchain="missing-toolchain-fixture"')
        self.write_config()
        _, failed = self.cli('index', '--prepare', code=1, timeout=180)
        self.assertEqual(failed['code'], 'TARGET_JDK_REQUIRED')
        self.assertEqual(failed['publication'], 'retained')
        self.assertEqual(self.cli('overview')[1]['revision'], initial['revision'])
        report = self.cli('report', failed['report'])[0][0]
        java = next(slot for slot in report['analysis_resources']['coordinator']['languages'] if slot['allocation']['language']=='java')
        self.assertEqual(len(java['workers']), 2)
        self.assertTrue(any(w.get('phase')=='discover' and w['usage']['work'] > 0 for w in java['workers']))
        pid = json.loads((self.home/'endpoint.json').read_text())['pid']
        children = []
        for line in subprocess.check_output(['ps','-axo','ppid=,command='], text=True).splitlines():
            parts = line.strip().split(None, 1)
            if len(parts)==2 and parts[0]==str(pid) and any(name in parts[1] for name in ('codeana-python-worker','codeana-rust-worker','codeana-java-worker')):
                children.append(parts[1])
        self.assertFalse(children)
        self.config = self.config.replace('toolchain="missing-toolchain-fixture"', 'toolchain="java8"')
        self.write_config()
        _, recovered = self.cli('index', '--prepare', timeout=180)
        self.assertNotEqual(recovered['revision'], initial['revision'])
        self.assertTrue(self.cli('resolve', 'last')[0])

    @unittest.skipUnless(os.environ.get('CODEANA_TEST_REAL_JAVA') == '1', 'explicit Native Java SDK gate')
    def test_same_maven_root_in_independent_contexts_keeps_both_owners(self):
        self.config += '\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\nclasspath="existing"\nvm_classpath=true\n'
        path = self.project/'java-shared'
        (path/'src/main/java').mkdir(parents=True)
        (path/'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>shared</artifactId><version>1</version><properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties></project>')
        (path/'src/main/java/Shared.java').write_text('/** 多上下文共享源码 */ public class Shared { public static int shared(){return 1;} }\n')
        for name, source_only in [('first', False), ('second', True)]:
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="java"\nroot="java-shared"\n[inputs.entries.java]\noffline=true\nsource_only={str(source_only).lower()}\n'
        self.write_config()
        _, initial = self.cli('index', '--prepare', timeout=180)
        units = [u for u in self.catalog()['units'] if u['language']=='java']
        self.assertEqual({u['id'] for u in units}, {'first','second'})
        self.assertEqual({u['mode'] for u in units}, {'semantic','source_only'})
        with self.db(initial['revision']) as db:
            rows = db.execute("SELECT module,id FROM nodes WHERE source_file='java-shared/src/main/java/Shared.java' AND kind='METHOD' AND label='shared'").fetchall()
            self.assertEqual({r[0] for r in rows}, {'first','second'})
            self.assertEqual(len({r[1] for r in rows}), 2)
            self.assertEqual(db.execute("SELECT count(DISTINCT world) FROM comments WHERE source_file='java-shared/src/main/java/Shared.java'").fetchone()[0], 2)
        _, repeated = self.cli('index', '--prepare', timeout=180)
        report = self.cli('report', repeated['report'])[0][0]
        java = [c for c in report['contexts'] if c['details']['backend']=='java']
        self.assertEqual({c['unit'] for c in java}, {'first','second'})
        self.assertTrue(all(w['phase']=='reused' for slot in report['analysis_resources']['coordinator']['languages'] for w in slot['workers']))
        # Removing one instance must keep the shared physical source in the other.
        self.config = self.config[:self.config.index('\n[[inputs.entries]]\nid="second"')]
        self.write_config()
        _, removed = self.cli('index', '--prepare', timeout=180)
        owners = self.ownership(removed['revision'])['units']
        self.assertNotIn('second', {u['unit'] for u in owners})
        original = {u['unit']:u for u in self.ownership(initial['revision'])['units']}
        self.assertTrue(original['second']['sources'])
        report = self.cli('report', removed['report'])[0][0]
        java_slot = next(slot for slot in report['analysis_resources']['coordinator']['languages'] if slot['allocation']['language']=='java')
        deleted = next(w for w in java_slot['workers'] if w['grant']['id'].endswith('/instance/second'))
        self.assertEqual(deleted['state'], 'complete')
        self.assertEqual(deleted['usage']['work'], 0)
        with self.db(removed['revision']) as db:
            self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes WHERE source_file='java-shared/src/main/java/Shared.java'")}, {'first'})

    @unittest.skipUnless(os.environ.get('CODEANA_TEST_REAL_JAVA') == '1', 'explicit Native Java SDK gate')
    def test_independent_maven_reactors_and_three_languages(self):
        self.add_python_comment_inputs()
        self.config += '\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\nclasspath="existing"\nvm_classpath=true\n'
        for name, method in (('java-a', 'alpha'), ('java-b', 'beta')):
            path = self.project/name
            (path/'src/main/java').mkdir(parents=True)
            (path/'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>same</artifactId><version>1</version><properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties></project>')
            (path/'src/main/java/App.java').write_text('/** 准备库存文档 */ public class App { public static int '+method+'(){return 1;} }\n')
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="java"\nroot="{name}"\n[inputs.entries.java]\noffline=true\n'
        path = self.project/'native-rust'
        (path/'src').mkdir(parents=True)
        (path/'Cargo.toml').write_text('[workspace]\n[package]\nname="native-rust"\nversion="0.1.0"\nedition="2024"\n')
        (path/'src/lib.rs').write_text('/// 准备库存文档\npub fn native_rust() -> u32 { 1 }\n')
        subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=path, check=True, capture_output=True)
        self.config += '\n[languages.rust]\nenabled=true\n[[inputs.entries]]\nid="native-rust"\nlanguage="rust"\nroot="native-rust"\n'
        self.write_config()
        _, result = self.cli('index', '--prepare', timeout=180)
        report=self.cli('report', result['report'])[0][0]
        self.assertEqual({c['unit'] for c in report['contexts']}, {u['id'] for u in self.catalog()['units']})
        self.assertEqual({c['details']['backend'] for c in report['contexts']}, {'java','rust','python'})
        self.assertEqual(len({c['unit'] for c in report['contexts']}), len(report['contexts']))
        with self.db(result['revision']) as db:
            self.assertEqual({r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')}, {'java', 'rust', 'python'})
            self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes WHERE language='java'")}, {'java-a', 'java-b'})
        comments,_=self.cli('search-comments','准备库存文档','--max-output-bytes','1048576','--strict')
        self.assertEqual({r['comment']['language'] for r in comments},{'java','rust','python'})
        self.assertTrue(self.cli('resolve', 'alpha')[0])
        self.assertTrue(self.cli('resolve', 'beta')[0])
        profile = json.loads((self.home/'revisions'/result['revision']/'revision.json').read_text())['profile']
        _, repeated = self.cli('index', '--prepare', timeout=180)
        self.assertEqual(profile, json.loads((self.home/'revisions'/repeated['revision']/'revision.json').read_text())['profile'])
        repeated_report = self.cli('report', repeated['report'])[0][0]
        self.assertTrue(all(w['phase']=='reused' for slot in repeated_report['analysis_resources']['coordinator']['languages'] for w in slot['workers']))
        pom = self.project/'java-a/pom.xml'
        original = pom.read_text()
        pom.write_text(original.replace('</project>', '<dependencies><dependency><groupId>fixture.missing</groupId><artifactId>unavailable</artifactId><version>1</version></dependency></dependencies></project>'))
        _, partial = self.cli('index', '--prepare', timeout=180)
        self.assertFalse(partial['evidence']['semantic'])
        self.assertEqual(next(u for u in self.catalog()['units'] if u['id']=='java-a')['mode'], 'source_only')
        self.assertTrue(self.cli('resolve', 'alpha')[0])
        pom.write_text(original.replace('<groupId>fixture</groupId>', '<parent><groupId>fixture.missing</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent><groupId>fixture</groupId>'))
        _, unavailable = self.cli('index', '--prepare', timeout=180)
        self.assertFalse(unavailable['evidence']['semantic'])
        self.assertEqual(next(u for u in self.catalog()['units'] if u['id']=='java-a')['mode'], 'files_only')
        self.assertFalse(self.cli('resolve', 'alpha', code=1)[0])
        self.assertTrue(self.cli('resolve', 'beta')[0])


if __name__ == '__main__':
    unittest.main()
