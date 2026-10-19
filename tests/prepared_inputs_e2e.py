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

    def test_python_isolation_explicit_dependency_reuse_and_global_report(self):
        self.assertFalse((self.project/'.code-ana/inputs.json').exists())
        doctor = self.cli('doctor')[0][0]
        self.assertEqual(doctor['preparation']['catalog_state'], 'missing')
        self.assertFalse((self.project/'.code-ana/inputs.json').exists())
        _, result = self.cli('index', '--prepare', timeout=120)
        first = result['revision']
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
        self.assertEqual(report['schema_version'], 2)
        self.assertEqual(report['diagnostics'], [])
        self.assertEqual(len(report['global']['units']), 3)
        self.assertNotIn('Full', report['effective'])
        markdown = self.cli('report', second['report'], '--format', 'markdown', raw=True)
        self.assertIn('| Execution | Quality |', markdown)
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
    def test_independent_maven_reactors_and_three_languages(self):
        self.config += '\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\nclasspath="existing"\nvm_classpath=true\n'
        for name, method in (('java-a', 'alpha'), ('java-b', 'beta')):
            path = self.project/name
            (path/'src/main/java').mkdir(parents=True)
            (path/'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>same</artifactId><version>1</version><properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties></project>')
            (path/'src/main/java/App.java').write_text('public class App { public static int '+method+'(){return 1;} }\n')
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="java"\nroot="{name}"\n[inputs.entries.java]\noffline=true\n'
        path = self.project/'native-rust'
        (path/'src').mkdir(parents=True)
        (path/'Cargo.toml').write_text('[workspace]\n[package]\nname="native-rust"\nversion="0.1.0"\nedition="2024"\n')
        (path/'src/lib.rs').write_text('pub fn native_rust() -> u32 { 1 }\n')
        subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=path, check=True, capture_output=True)
        self.config += '\n[languages.rust]\nenabled=true\n[[inputs.entries]]\nid="native-rust"\nlanguage="rust"\nroot="native-rust"\n'
        self.write_config()
        _, result = self.cli('index', '--prepare', timeout=180)
        with self.db(result['revision']) as db:
            self.assertEqual({r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')}, {'java', 'rust', 'python'})
            self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes WHERE language='java'")}, {'java-a', 'java-b'})
        self.assertTrue(self.cli('resolve', 'alpha')[0])
        self.assertTrue(self.cli('resolve', 'beta')[0])
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
