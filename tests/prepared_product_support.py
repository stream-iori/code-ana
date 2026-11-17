"""Shared real-worker input preparation fixture; no test cases are imported."""
import contextlib
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import tempfile
from product_support import ProductHarness, ROOT, BIN, JDK


class PreparedHarness(ProductHarness):
    required_backends = frozenset({"python"})
    copy_fixture = True
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='prepared-inputs-', dir=ROOT/'target')
        self.root = pathlib.Path(self.temp.name)
        self.project, self.home = self.root/'project', self.root/'home'
        if self.copy_fixture:
            fixture = pathlib.Path(os.environ.get('CODEANA_TEST_FIXTURES', ROOT/'fixtures'))/'python/preparation'
            if not fixture.exists() and 'CODEANA_TEST_FIXTURES' not in os.environ:
                fixture = ROOT/'fixtures/python/preparation'
            shutil.copytree(fixture, self.project)
        else:
            self.project.mkdir()
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

    def add_python_comment_inputs(self):
        for relative in ('app-a/main_a.py','app-b/main_b.py'):
            source=self.project/relative
            source.write_text('"""准备库存文档"""\n'+source.read_text())

