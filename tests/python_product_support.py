"""Shared real Python fixture and optional Rust/Java setup; no test cases."""
import contextlib
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import tempfile
import time
from product_support import ProductHarness, ROOT, BIN, FIX, JDK


class PythonHarness(ProductHarness):
    required_backends = frozenset({"python"})
    include_python = True
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='python-language-', dir=ROOT/'target')
        self.root = pathlib.Path(self.temp.name)
        self.home, self.project = self.root/'home', self.root/'project'
        if self.include_python:
            fixture = FIX/'python/inference'
            if not fixture.exists() and 'CODEANA_TEST_FIXTURES' not in os.environ:
                fixture = ROOT/'fixtures/python/inference'
            shutil.copytree(fixture, self.project)
        else:
            self.project.mkdir()
        # Isolate the fixture from the parent repository's ignored target/ tree.
        subprocess.run(['git', 'init', '--quiet'], cwd=self.project, check=True, capture_output=True)
        (self.project/'.code-ana').mkdir()
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[languages.python]\nenabled=true\n[languages.python.discovery]\ninputs_manifest=".code-ana/python-inputs.json"\n')
        self.home.mkdir(mode=0o700)
        (self.home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_worker_memory_bytes=1073741824\nmax_total_worker_memory_bytes=2147483648\n')
        if self.include_python:
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
        if not script.exists() and 'CODEANA_TEST_BIN' not in os.environ:
            script = ROOT/'scripts/export-python-inputs.py'
        subprocess.run(['python3', str(script), '--project', str(self.project), '--source-root', '.', '--python-version', '3.12', '--platform', 'darwin'], check=True, capture_output=True)

    def db(self, revision):
        return contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db'))

    def prepare_comment_fixture(self):
        source = self.project/'comments.py'
        fixture = FIX/'python/comment-indexing/comments.py'
        if not fixture.exists() and 'CODEANA_TEST_FIXTURES' not in os.environ:
            fixture = ROOT/'fixtures/python/comment-indexing/comments.py'
        source.write_bytes(fixture.read_bytes())
        self.export()
        return source, self.cli('index', '--full', timeout=120)[1]['revision']

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
        if not fixture.exists() and 'CODEANA_TEST_FIXTURES' not in os.environ:
            fixture = ROOT/'fixtures/rust/workspace'
        shutil.copytree(fixture, self.project, dirs_exist_ok=True)
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.rust]\nenabled=true\n')
        subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=self.project, check=True, capture_output=True)
        script = BIN/'codeana-export-rust-inputs'
        if not script.exists() and 'CODEANA_TEST_BIN' not in os.environ:
            script = ROOT/'scripts/export-rust-inputs.py'
        subprocess.run(['python3', str(script), '--project', str(self.project)], check=True, capture_output=True)

    def add_java(self):
        config = self.project/'.code-ana/config.toml'
        fixture = FIX/'java-native/base'
        if not fixture.exists() and 'CODEANA_TEST_FIXTURES' not in os.environ:
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

