#!/usr/bin/env python3
"""Real Pyrefly worker, frozen inputs, publication and query regressions."""
import contextlib
import json
import pathlib
import os
import signal
import sys
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest
from python_product_audit import audit_calls
from product_support import ProductHarness, ROOT, BIN, FIX, JDK


class PythonLanguage(ProductHarness):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='python-language-', dir=ROOT/'target')
        self.root = pathlib.Path(self.temp.name)
        self.home, self.project = self.root/'home', self.root/'project'
        fixture = FIX/'python/inference'
        shutil.copytree(fixture if fixture.exists() else ROOT/'fixtures/python/inference', self.project)
        # Isolate the fixture from the parent repository's ignored target/ tree.
        subprocess.run(['git', 'init', '--quiet'], cwd=self.project, check=True, capture_output=True)
        (self.project/'.code-ana').mkdir()
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[languages.python]\nenabled=true\n[languages.python.discovery]\ninputs_manifest=".code-ana/python-inputs.json"\n')
        self.home.mkdir(mode=0o700)
        (self.home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=1073741824\nmax_total_worker_memory_bytes=2147483648\n')
        self.export()
        self.daemon, self.log = None, None
        self.addCleanup(self.cleanup)
        self.log = open(self.root/'daemon.log', 'a')
        self.daemon = subprocess.Popen([str(BIN/'codeana-daemon'), '--home', str(self.home)], stdout=self.log, stderr=self.log)
        until = time.monotonic()+10
        while not (self.home/'endpoint.json').exists():
            if self.daemon.poll() is not None or time.monotonic()>until:
                raise RuntimeError(self.daemon_log())
            time.sleep(.05)

    def export(self):
        script = BIN/'codeana-export-python-inputs'
        if not script.exists():
            script = ROOT/'scripts/export-python-inputs.py'
        subprocess.run(['python3', str(script), '--project', str(self.project), '--source-root', '.', '--python-version', '3.12', '--platform', 'darwin'], check=True, capture_output=True)

    def db(self, revision):
        return contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db'))

    def canonical(self, revision):
        with self.db(revision) as db:
            result = {}
            for table in ('nodes', 'edges', 'analysis_coverage', 'index_diagnostics'):
                columns = [r[1] for r in db.execute('PRAGMA table_info('+table+')')
                           if not (r[1] == 'id' and table != 'nodes')]
                result[table] = sorted(db.execute('SELECT '+','.join(columns)+' FROM '+table).fetchall(), key=repr)
            result['calls'] = sorted(db.execute('SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.resolution_status,t.target_id,t.external_target_fqn FROM call_sites c JOIN call_site_owners o USING(owner_pk) LEFT JOIN call_site_targets t ON t.call_site_pk=c.site_pk').fetchall(), key=repr)
            return result

    def add_rust(self):
        fixture = FIX/'rust/workspace'
        shutil.copytree(fixture if fixture.exists() else ROOT/'fixtures/rust/workspace', self.project, dirs_exist_ok=True)
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.rust]\nenabled=true\n')
        subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=self.project, check=True, capture_output=True)
        script = BIN/'codeana-export-rust-inputs'
        subprocess.run(['python3', str(script if script.exists() else ROOT/'scripts/export-rust-inputs.py'), '--project', str(self.project)], check=True, capture_output=True)

    def add_java(self):
        config = self.project/'.code-ana/config.toml'
        fixture = FIX/'java-native/base'
        if not fixture.exists():
            fixture = ROOT/'tests/fixtures/java-native/base'
        java_config = (fixture/'.code-ana/config.toml').read_text()
        config.write_text(config.read_text()+'\n'+java_config[java_config.index('[languages.java]'):])
        shutil.copytree(fixture/'src', self.project/'src', dirs_exist_ok=True)
        user = self.home/'config.toml'
        user.write_text(user.read_text().replace('max_worker_memory_bytes=1073741824', 'max_worker_memory_bytes=3221225472').replace('max_total_worker_memory_bytes=2147483648', 'max_total_worker_memory_bytes=6442450944')+'\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(JDK))+'\n')
        # User toolchains are read at daemon startup.
        self.stop()
        self.log = open(self.root/'daemon.log', 'a')
        self.daemon = subprocess.Popen([str(BIN/'codeana-daemon'), '--home', str(self.home)], stdout=self.log, stderr=self.log)
        until = time.monotonic()+10
        while time.monotonic() < until:
            check = subprocess.run([str(BIN/'codeana-cli'), '--home', str(self.home), 'daemon', 'status'], capture_output=True)
            if check.returncode == 0:
                return
            time.sleep(.05)
        self.fail(self.daemon_log())

    def test_inference_queries_reuse_strict_and_stale_inputs(self):
        _, terminal = self.cli('index', timeout=90)
        first = terminal['revision']
        with self.db(first) as db:
            self.assertGreater(db.execute("SELECT count(*) FROM nodes WHERE language='python'").fetchone()[0], 5)
            self.assertGreater(db.execute("SELECT count(*) FROM call_sites WHERE language='python'").fetchone()[0], 5)
            self.assertEqual(db.execute("SELECT count(*) FROM call_sites WHERE language='java'").fetchone()[0], 0)
        self.assertTrue(self.cli('resolve', 'factory')[0])
        self.assertTrue(self.cli('calls', 'exercise')[0])
        rows, reused = self.cli('index', timeout=90)
        self.assertEqual(rows[0]['mode'], 'delta')
        self.assertEqual(self.canonical(first), self.canonical(reused['revision']))
        first = reused['revision']
        self.cli('index', '--strict', code=3, timeout=90)
        self.assertEqual(self.cli('overview')[1]['revision'], first)
        source = self.project/'main.py'
        source.write_text(source.read_text()+'\ndef added():\n    return factory().run()\n')
        self.cli('index', code=1)
        self.assertEqual(self.cli('overview')[1]['revision'], first)
        self.export()
        rows, changed = self.cli('index', timeout=90)
        self.assertEqual(rows[0]['mode'], 'full')
        full = self.cli('index', '--full', timeout=90)[1]['revision']
        self.assertEqual(self.canonical(changed['revision']), self.canonical(full))


    def test_nine_frozen_probes_and_python_dispatch(self):
        first = self.cli('index', timeout=90)[1]['revision']
        cases = json.loads((self.project/'probe-expected.json').read_text())
        results = audit_calls(self.home/'revisions'/first/'facts.db', cases)
        self.assertEqual(len(results), 9)
        self.assertTrue(all(r['passed'] for r in results), results)
        direct = next(r for r in results if r['id'] == 'direct')
        candidates = self.cli('dispatch', direct['call_site'])[0]
        self.assertTrue(candidates)
        self.assertTrue(all(r.get('algorithm') == 'pyrefly-static' for r in candidates))
        self.cli('dispatch', direct['call_site'], '--algorithm', 'cha', code=1)

    def test_output_budget_failure_retains_head(self):
        first = self.cli('index', timeout=90)[1]['revision']
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\npython_fact_limit=1\n')
        self.cli('index', '--full', code=1, timeout=90)
        self.assertEqual(self.cli('overview')[1]['revision'], first)

    def test_optional_pyproject_controls_and_legacy_inventory(self):
        pyproject = self.project/'pyproject.toml'
        fixture = FIX/'python/metadata/pyproject.toml'
        pyproject.write_bytes((fixture if fixture.exists() else ROOT/'fixtures/python/metadata/pyproject.toml').read_bytes())
        script = BIN/'codeana-export-python-inputs'
        if not script.exists():
            script = ROOT/'scripts/export-python-inputs.py'
        def metadata_export():
            subprocess.run(['python3', str(script), '--project', str(self.project), '--source-root', '.',
                            '--python-version', '3.12', '--platform', 'darwin', '--pyproject', 'pyproject.toml',
                            '--extra', 'dev'], check=True, capture_output=True)
        metadata_export()
        first = self.cli('index', timeout=90)[1]['revision']
        doctor = self.cli('doctor')[0][0]['python_worker']
        self.assertEqual(doctor['project_metadata']['selected_extras'], ['dev'])
        pyproject.write_text(pyproject.read_text()+'\n# metadata changed\n')
        self.cli('index', code=1)
        self.assertEqual(self.cli('overview')[1]['revision'], first)
        metadata_export()
        self.assertEqual(self.cli('index', timeout=90)[0][0]['mode'], 'full')
        self.export()
        path = self.project/'.code-ana/python-inputs.json'
        legacy = json.loads(path.read_text())
        legacy['schema_version'] = 1
        del legacy['controls'], legacy['metadata']
        path.write_text(json.dumps(legacy))
        self.cli('index', timeout=90)
        self.assertEqual(self.cli('index', timeout=90)[0][0]['mode'], 'delta')

    def test_rust_python_publish_and_failure_are_atomic(self):
        self.add_rust()
        config = self.project/'.code-ana/config.toml'
        self.export()
        first = self.cli('index', timeout=90)[1]['revision']
        with self.db(first) as db:
            languages = {row[0] for row in db.execute('SELECT DISTINCT language FROM nodes')}
            self.assertTrue({'rust', 'python'} <= languages)
        config.write_text(config.read_text()+'\n[resources.index]\nrust_fact_limit=1\n')
        self.cli('index', '--full', code=1, timeout=90)
        self.assertEqual(self.cli('overview')[1]['revision'], first)

    def test_deleted_empty_and_missing_import_recovery(self):
        source = self.project/'main.py'
        source.write_text('from missing import make\ndef invoke():\n    return make().run()\n')
        self.export()
        first = self.cli('index', timeout=90)[1]
        self.assertFalse(first['evidence']['semantic'])
        report = self.cli('report', first['report'])[0][0]
        with self.db(first['revision']) as db:
            groups = dict(db.execute('SELECT code,sum(occurrence_count) FROM index_diagnostics GROUP BY code'))
        self.assertEqual(report['summary']['diagnostics']['groups'], groups)
        self.assertGreater(groups.get('missing-import', 0), 0)
        markdown = self.cli('report', first['report'], '--format', 'markdown', raw=True)
        self.assertIn('missing-import', markdown)
        self.assertIn('Coverage:', markdown)
        (self.project/'missing.py').write_text('class Service:\n    def run(self):\n        return 1\ndef make():\n    return Service()\n')
        self.export()
        recovered = self.cli('index', timeout=90)[1]['revision']
        self.assertTrue(self.cli('resolve', 'Service')[0])
        with self.db(recovered) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM index_diagnostics WHERE code='missing-import'").fetchone()[0], 0)
        (self.project/'missing.py').unlink()
        source.write_text('')
        (self.project/'probe.py').unlink()
        self.export()
        deleted = self.cli('index', timeout=90)[1]['revision']
        with self.db(deleted) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM call_sites').fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT count(*) FROM nodes WHERE source_file='missing.py'").fetchone()[0], 0)
        full = self.cli('index', '--full', timeout=90)[1]['revision']
        self.assertEqual(self.canonical(deleted), self.canonical(full))
        source.unlink()
        self.export()
        empty = self.cli('index', timeout=90)[1]['revision']
        with self.db(empty) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM nodes').fetchone()[0], 0)
            self.assertEqual(db.execute('SELECT count(*) FROM call_sites').fetchone()[0], 0)

    def test_cancel_crash_and_recovery_preserve_head(self):
        good = self.cli('index', timeout=90)[1]['revision']
        source = self.project/'main.py'
        source.write_text(source.read_text()+'\n'+''.join(f'def generated_{i}():\n    return factory().run()\n' for i in range(2500)))
        self.export()
        for action in ('cancel', 'crash'):
            with self.subTest(action=action):
                task = json.loads(self.cli('index', '--full', '--background', raw=True))['task_id']
                until = time.monotonic()+20
                worker = None
                while time.monotonic() < until:
                    rows = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,comm='], text=True)
                    workers = [int(f[0]) for line in rows.splitlines() if len(f := line.split(None, 2)) == 3 and int(f[1]) == self.daemon.pid and 'codeana-python-worker' in f[2]]
                    if workers:
                        worker = workers[0]
                        break
                    time.sleep(.02)
                self.assertIsNotNone(worker, self.daemon_log())
                # A private candidate is created only after the validated Begin.
                until = time.monotonic()+20
                while time.monotonic() < until and not list((self.home/'candidates').glob('*/facts.db')):
                    time.sleep(.02)
                self.assertTrue(list((self.home/'candidates').glob('*/facts.db')), 'Begin was not observed')
                if action == 'cancel':
                    self.cli('task', 'cancel', task, raw=True)
                else:
                    os.kill(worker, signal.SIGKILL)
                self.cli('task', 'watch', task, code=130 if action == 'cancel' else 1, timeout=30)
                self.assertEqual(self.cli('overview')[1]['revision'], good)
                until = time.monotonic()+5
                while time.monotonic() < until:
                    try:
                        os.kill(worker, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(.05)
                else:
                    self.fail('Python worker leaked after '+action)
        source.write_text('def recovered():\n    return 1\n')
        self.export()
        self.cli('index', timeout=90)
        self.assertTrue(self.cli('resolve', 'recovered')[0])


class PythonJava(PythonLanguage):
    """Native Java combinations run separately after the Java fixture gate."""
    def test_java_python_and_three_language_publication(self):
        self.add_java()
        self.export()
        first = self.cli('index', timeout=180)[1]['revision']
        with self.db(first) as db:
            self.assertTrue({'java', 'python'} <= {r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')})
        self.add_rust()
        self.export()
        combined = self.cli('index', timeout=180)[1]['revision']
        with self.db(combined) as db:
            self.assertTrue({'java', 'rust', 'python'} <= {r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')})
        reused = self.cli('index', timeout=180)[1]['revision']
        self.assertEqual(self.canonical(combined), self.canonical(reused))
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\npython_fact_limit=1\n')
        self.cli('index', '--full', code=1, timeout=180)
        self.assertEqual(self.cli('overview')[1]['revision'], reused)


if __name__ == '__main__':
    # The default Rust/Python package intentionally has no Java SDK.
    if len(sys.argv) == 1:
        unittest.main(defaultTest='PythonLanguage')
    else:
        unittest.main()
