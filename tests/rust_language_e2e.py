#!/usr/bin/env python3
"""Real rust-analyzer -> isolated worker -> shared graph store -> CLI regression."""
import contextlib
import importlib.machinery
import importlib.util
import json
import pathlib
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest
from product_support import ProductHarness, ROOT, BIN, FIX

export_path = BIN/'codeana-export-rust-inputs'
if not export_path.exists():
    export_path = ROOT/'scripts/export-rust-inputs.py'
spec = importlib.util.spec_from_loader(
    'rust_export', importlib.machinery.SourceFileLoader('rust_export', str(export_path)))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)

class RustExport(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rust-export-', dir=ROOT/'target')
        self.addCleanup(self.temp.cleanup)
        self.project = pathlib.Path(self.temp.name)/'project'
        fixture = FIX/'rust/workspace'
        shutil.copytree(fixture if fixture.exists() else ROOT/'fixtures/rust/workspace', self.project)
        (self.project/'.code-ana').mkdir()
        subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=self.project,
                       check=True, capture_output=True)

    def export(self, extra='', **kwargs):
        (self.project/'.code-ana/config.toml').write_text(
            'schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\n' + extra)
        return exporter.export(self.project, self.project/'.code-ana/rust-inputs.json', **kwargs)

    def test_feature_worlds_keep_distinct_crate_instances_and_dependencies(self):
        inputs = self.export('''
[[modules]]
id="core"
language="rust"
root="core"
[[modules.worlds]]
id="default"
[[modules.worlds]]
id="extra"
features=["fixture-core/extra"]
''')
        published = [c for c in inputs['crates'] if c['publish'] and c['scope']=='MAIN']
        self.assertEqual(len(published), 2)
        self.assertEqual(len({c['world'] for c in published}), 2)
        self.assertEqual(sum('feature="extra"' in c['cfg'] for c in published), 1)
        ids = {c['id'] for c in inputs['crates']}
        self.assertTrue(all(set(c['dependencies'].values()) <= ids for c in inputs['crates']))

    def test_unsupported_toolchain_and_reserved_cfg_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'RUST_TOOLCHAIN_ALIAS_UNSUPPORTED'):
            self.export('[languages.rust.analysis]\ntoolchain="unregistered"\n')
        with self.assertRaisesRegex(ValueError, 'RUST_RESERVED_CFG'):
            self.export('''
[[modules]]
id="core"
language="rust"
root="core"
[[modules.worlds]]
id="invalid"
cfg=['target_os="invented"']
''')

    def test_filtered_roots_invalid_globs_and_test_only_scopes(self):
        with self.assertRaisesRegex(ValueError, 'RUST_NO_PROJECT_SOURCE'):
            self.export('[scan]\nexclude=["**"]\n')
        with self.assertRaisesRegex(ValueError, 'RUST_INPUT_POLICY_INVALID'):
            self.export('[scan]\ninclude=["["]\n')
        inputs = self.export('[scan]\nscopes=["TEST"]\n')
        self.assertTrue(all(c['scope']=='TEST' for c in inputs['crates'] if c['publish']))
        self.assertTrue(all(c['root'] in inputs['dependency_sources'][c['id']]
                            for c in inputs['crates'] if not c['publish']))

class RustHarness(ProductHarness):
    fixture_name = "workspace"
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rust-language-', dir=ROOT/'target')
        self.root = pathlib.Path(self.temp.name)
        self.home = self.root/'home'
        self.project = self.root/'project'
        fixture = FIX/'rust'/self.fixture_name
        if not fixture.exists():
            fixture = ROOT/'fixtures/rust'/self.fixture_name
        shutil.copytree(fixture, self.project)
        (self.project/'.code-ana').mkdir()
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\nmode="semantic"\n[scan]\nscopes=["MAIN","TEST"]\n')
        # This script would fail the test if the exporter/indexer ever compiled it.
        (self.project/'app/build.rs').write_text('fn main() { panic!("BUILD_SCRIPT_MUST_NOT_RUN"); }\n')
        subprocess.run(['cargo','generate-lockfile','--offline'],cwd=self.project,check=True,capture_output=True)
        exporter.export(self.project,self.project/'.code-ana/rust-inputs.json')
        self.daemon = None
        self.log = None
        self.addCleanup(self.cleanup)
        self.log = open(self.root/'daemon.log','a')
        self.daemon = subprocess.Popen([str(BIN/'codeana-daemon'),'--home',str(self.home)],stdout=self.log,stderr=self.log)
        until=time.monotonic()+10
        while not (self.home/'endpoint.json').exists():
            if self.daemon.poll() is not None or time.monotonic()>until:
                raise RuntimeError(self.daemon_log())
            time.sleep(.05)

    def db(self, revision):
        return contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db'))

    def canonical(self, revision):
        with self.db(revision) as db:
            result={}
            for table in ('nodes','edges','analysis_coverage','index_diagnostics','rust_source_states','rust_analysis_dependencies'):
                columns=[r[1] for r in db.execute('PRAGMA table_info('+table+')') if not (r[1]=='id' and table!='nodes')]
                result[table]=sorted(db.execute('SELECT '+','.join(columns)+' FROM '+table).fetchall(),key=repr)
            result['calls']=sorted(db.execute('SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.resolution_status,t.target_id,t.external_target_fqn FROM call_sites c JOIN call_site_owners o USING(owner_pk) LEFT JOIN call_site_targets t ON t.call_site_pk=c.site_pk').fetchall(),key=repr)
            return result

class RustLanguage(RustHarness):
    def test_real_parser_worlds_queries_incremental_and_strict(self):
        rows,terminal=self.cli('index',timeout=90)
        first=terminal['revision']
        self.assertFalse(terminal['evidence']['semantic'])
        with self.db(first) as db:
            self.assertGreater(db.execute("SELECT count(*) FROM nodes WHERE language='rust'").fetchone()[0],10)
            self.assertEqual(db.execute("SELECT count(DISTINCT world) FROM nodes WHERE label='answer'").fetchone()[0],2)
            self.assertGreater(db.execute("SELECT count(*) FROM call_sites WHERE resolution_status='unresolved'").fetchone()[0],0)
            self.assertGreater(db.execute("SELECT count(*) FROM rust_analysis_dependencies WHERE kind='lookup' AND target_key='missing'").fetchone()[0],0)
        # Unchanged input uses normal publication, retaining the complete graph.
        _, unchanged=self.cli('index',timeout=90)
        self.assertEqual(self.canonical(first),self.canonical(unchanged['revision']))
        with sqlite3.connect(self.home/'catalog.db') as catalog:
            self.assertGreater(catalog.execute("SELECT count(*) FROM events WHERE body LIKE '%rust_analyze_unchanged%'").fetchone()[0],0)
        first=unchanged['revision']
        with self.db(first) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0],9)
            self.assertEqual(db.execute("SELECT count(*) FROM rust_source_states WHERE json_type(state,'$.dependencies') IS NOT NULL").fetchone()[0],0)
            self.assertGreater(db.execute('SELECT count(*) FROM graph_rust_analysis_dependencies').fetchone()[0],0)
        with self.db(first) as db:
            world=db.execute("SELECT world FROM nodes WHERE label='answer' AND scope='MAIN'").fetchone()[0]
        self.assertEqual(len(self.cli('resolve','answer','--world',world)[0]),1)
        self.assertTrue(self.cli('resolve','answer')[0])
        self.assertTrue(self.cli('structure','Counter')[0])
        self.assertTrue(self.cli('structure','api')[0])
        self.assertTrue(self.cli('calls','main')[0])
        self.cli('index','--strict',code=3,timeout=90)
        self.assertEqual(self.cli('overview')[1]['revision'],first)
        source=self.project/'app/src/main.rs'
        source.write_text(source.read_text()+'\nfn missing() {}\n')
        rows,terminal=self.cli('index',timeout=90);delta=terminal['revision']
        self.assertEqual(rows[0]['mode'],'delta')
        changed=self.canonical(delta)
        full=self.cli('index','--full',timeout=90)[1]['revision']
        self.assertEqual(changed,self.canonical(full))
        # A captured build control changed: require an explicit export, retain the head.
        manifest=self.project/'app/Cargo.toml';manifest.write_text(manifest.read_text()+'\n# changed\n')
        self.cli('index',code=1)
        self.assertEqual(self.cli('overview')[1]['revision'],full)

class RustDependencies(RustHarness):
    fixture_name = 'dependency-depth'

    def test_direct_dependencies_filters_stale_copies_and_memory_policy(self):
        doctor = self.cli('doctor')[0][0]
        self.assertEqual(doctor['rust_worker_memory_bytes'], 268435456)
        self.assertEqual(doctor['worker_memory_cap_bytes'], 268435456)
        config = self.project/'.code-ana/config.toml'
        config.write_text('schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\n[scan]\nscopes=["MAIN"]\nexclude=["app/src/hidden/**","app/build.rs"]\n')
        initial = exporter.export(self.project, self.project/'.code-ana/rust-inputs.json')
        self.assertEqual({c['name'] for c in initial['crates']}, {'depth_app', 'depth_a'})
        self.assertEqual(initial['schema_version'], 2)
        self.assertTrue(initial['omitted_dependencies'])
        aux = next(c for c in initial['crates'] if not c['publish'])
        old = next(p for p in initial['dependency_sources'][aux['id']] if 'ignored' in p)
        self.assertTrue((self.project/old).exists())
        config.write_text(config.read_text()+'\n[languages.rust.dependencies]\ninclude=["src/**"]\nexclude=["src/ignored/**"]\n')
        inputs = exporter.export(self.project, self.project/'.code-ana/rust-inputs.json')
        self.assertTrue((self.project/old).exists())  # stale copied file is deliberately retained
        self.assertTrue(all('/ignored/' not in p and '/tests/' not in p
                            for paths in inputs['dependency_sources'].values() for p in paths))
        rows, terminal = self.cli('index', timeout=90)
        first = terminal['revision']
        with self.db(first) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM nodes WHERE label IN ('excluded_project_marker','excluded_dependency_marker','transitive','deep')").fetchone()[0], 0)
            self.assertGreater(db.execute("SELECT count(*) FROM call_site_targets WHERE external_target_fqn LIKE '%direct'").fetchone()[0], 0)
        revision = json.loads((self.home/'revisions'/first/'revision.json').read_text())
        sources = [f['path'] for f in revision['manifest']['files'] if f['language']=='rust']
        self.assertFalse(any('ignored/' in p or 'hidden/' in p or '/tests/' in p for p in sources))
        self.assertEqual(len(sources), 2)
        # Changing only execution budgets needs no export and preserves the semantic profile.
        config.write_text(config.read_text()+'\n[resources.index]\nworker_memory_bytes=134217728\n')
        rows, terminal = self.cli('index', timeout=90)
        self.assertEqual(rows[0]['mode'], 'delta')
        head = terminal['revision']
        config.write_text(config.read_text().replace('134217728', '536870912'))
        self.cli('index', code=1, timeout=90)
        self.assertEqual(self.cli('overview')[1]['revision'], head)
        config.write_text(config.read_text().replace('536870912', '268435456').replace('src/ignored/**', 'src/**'))
        self.cli('index', code=1)
        self.assertEqual(self.cli('overview')[1]['revision'], head)

if __name__=='__main__':
    unittest.main()
