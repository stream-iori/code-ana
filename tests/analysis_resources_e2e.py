#!/usr/bin/env python3
"""Unified analysis budget contract through real CLI/daemon/worker processes.

No expected failures or skips: until production wiring lands, this gate is red.
"""
import json
import contextlib
import sqlite3
import subprocess
import unittest
import java_graph_storage as java
import python_language_e2e as python
import rust_language_e2e as rust
import prepared_inputs_e2e as prepared


class BudgetCases:
    language = None

    def setUp(self):
        super().setUp()
        # A fixture below target/ must not inherit the enclosing repository ignore.
        subprocess.run(['git', 'init', '--quiet'], cwd=self.project,
                       check=True, capture_output=True)

    def graph(self, revision):
        with contextlib.closing(sqlite3.connect(self.home / 'revisions' / revision / 'facts.db')) as db:
            rows = db.execute('SELECT * FROM nodes ORDER BY id').fetchall()
            self.assertGreater(len(rows), 0, 'semantic fixture must not publish an empty graph')
            languages = {r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')}
            self.assertIn(self.language, languages)
            return rows

    def limited_then_recover(self, field):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        _, baseline = self.cli('index', '--full', timeout=180)
        self.assertEqual(baseline['publication'], 'published')
        revision = baseline['revision']
        graph = self.graph(revision)
        try:
            config.write_text(original + '\n[resources.index.analysis]\n' + field + '=1\n')
            for args in [('index', '--full'), ('index',)]:
                _, failed = self.cli(*args, code=1, timeout=180)
                self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
                self.assertEqual(self.cli('overview')[1]['revision'], revision)
                self.assertEqual(self.graph(revision), graph)
                report = self.cli('report', failed['report'])[0][0]
                usage = report['analysis_resources']['usage']
                self.assertIsNotNone(usage['failure'])
                self.assertEqual(usage['failure']['Limit']['resource'], {'max_work': 'work', 'max_nodes': 'nodes', 'max_payload_bytes': 'payload_bytes', 'max_depth': 'depth'}[field])
                for used, limit in [('work', 'max_work'), ('nodes', 'max_nodes'), ('payload_bytes', 'max_payload_bytes')]:
                    self.assertLessEqual(usage[used], report['analysis_resources']['limits'][limit])
        finally:
            config.write_text(original)
        _, recovered = self.cli('index', '--full', timeout=180)
        self.assertEqual(recovered['publication'], 'published')
        self.graph(recovered['revision'])
        self.assertEqual(self.graph(revision), graph)

    def test_remaining_capacity_allocations(self):
        _, result = self.cli('index', '--full', timeout=180)
        self.graph(result['revision'])
        report = self.cli('report', result['report'])[0][0]
        coordinator = report['analysis_resources']['coordinator']
        slots = coordinator['languages']
        self.assertTrue(slots)
        markdown = self.cli('report', result['report'], '--format', 'markdown', raw=True)
        self.assertIn('Analysis resources:', markdown)
        self.assertIn(report['analysis_resources']['coverage'], markdown)
        for slot in slots:
            for worker in slot['workers']:
                self.assertEqual(markdown.count(worker['grant']['id']), 1)
        if self.language in ('java', 'rust', 'python'):
            slot = next(s for s in slots if s['allocation']['language'] == self.language)
            self.assertGreater(slot['host_usage']['payload_bytes'], 0)
            self.assertGreater(slot['host_usage']['nodes'], 0)
            self.assertEqual(slot['worker']['state'], 'complete')
            self.assertGreater(slot['worker']['usage']['work'], 0)
            self.assertGreater(slot['worker']['usage']['max_depth'], 1)
        self.assertEqual(len({s['worker']['grant']['id'] for s in slots}), len(slots))
        for used, limit in [('work', 'max_work'), ('nodes', 'max_nodes'), ('payload_bytes', 'max_payload_bytes')]:
            self.assertEqual(coordinator['admission'][used] + sum(s['allocation']['total'][limit] for s in slots), coordinator['limits'][limit])
            for slot in slots:
                allocation = slot['allocation']
                self.assertEqual(allocation['host'][limit] + allocation['worker'][limit], allocation['total'][limit])
                self.assertEqual(slot['worker']['grant']['limits'][limit], allocation['worker'][limit])

    def test_unchanged_reuse_has_discovery_receipt(self):
        _, first = self.cli('index', '--full', timeout=180)
        before = self.graph(first['revision'])
        _, reused = self.cli('index', timeout=180)
        self.assertEqual(self.graph(reused['revision']), before)
        report = self.cli('report', reused['report'])[0][0]['analysis_resources']['coordinator']
        self.assertTrue(report['complete'])
        for slot in report['languages']:
            for worker in slot['workers']:
                self.assertEqual(worker['state'], 'complete')
                self.assertEqual(worker['phase'], 'reused')
                self.assertGreater(worker['usage']['work'], 0)
                self.assertGreater(worker['usage']['payload_bytes'], 0)
                self.assertIsNone(worker['usage']['failure'])
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(self.graph(full['revision']), before)

    def test_work_budget(self):
        self.limited_then_recover('max_work')

    def test_node_budget(self):
        self.limited_then_recover('max_nodes')

    def test_payload_budget(self):
        self.limited_then_recover('max_payload_bytes')

    def test_depth_budget(self):
        self.limited_then_recover('max_depth')


class JavaBudget(BudgetCases, java.JavaProductHarness):
    language = 'java'

    def test_discovery_payload_failure_has_final_receipt_and_preserves_head(self):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        _, good = self.cli('index', '--full', timeout=180)
        baseline = self.graph(good['revision'])
        report = self.cli('report', good['report'])[0][0]['analysis_resources']['coordinator']
        limit = report['admission']['payload_bytes'] + 8192
        try:
            config.write_text(original + '\n[resources.index.analysis]\nmax_payload_bytes=' + str(limit) + '\n')
            _, failed = self.cli('index', '--full', code=1, timeout=180)
            self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
            usage = self.cli('report', failed['report'])[0][0]['analysis_resources']['coordinator']
            worker = usage['languages'][0]['worker']
            self.assertEqual(worker['phase'], 'input_decode')
            self.assertEqual(worker['state'], 'failed')
            self.assertEqual(worker['usage']['failure']['Limit']['resource'], 'payload_bytes')
            self.assertGreater(worker['usage']['payload_bytes'], 0)
            failure = worker['usage']['failure']['Limit']
            self.assertGreater(failure['used'] + failure['requested'], failure['limit'])
            self.assertEqual(self.cli('overview')[1]['revision'], good['revision'])
            self.assertEqual(self.graph(good['revision']), baseline)
        finally:
            config.write_text(original)
        self.cli('index', '--full', timeout=180)

    def test_backend_work_budget_after_admission(self):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        _, baseline = self.cli('index', '--full', timeout=180)
        old = baseline['revision']
        before = self.graph(old)
        try:
            source = next(self.project.rglob('*.java'))
            source.write_text(source.read_text() + '\n// changed input for analysis budget\n')
            config.write_text(original + '\n[resources.index.analysis]\nmax_work=128\n')
            for args in [('index', '--full'), ('index',)]:
                _, failed = self.cli(*args, code=1, timeout=180)
                self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
                report = self.cli('report', failed['report'])[0][0]['analysis_resources']['coordinator']
                self.assertIsNone(report['admission']['failure'])
                worker = next(s for s in report['languages'] if s['allocation']['language'] == 'java')['worker']
                self.assertEqual(worker['state'], 'failed')
                self.assertEqual(worker['usage']['failure']['Limit']['resource'], 'work')
                self.assertEqual(self.cli('overview')[1]['revision'], old)
                self.assertEqual(self.graph(old), before)
        finally:
            config.write_text(original)
        _, recovered = self.cli('index', '--full', timeout=180)
        self.graph(recovered['revision'])


class RustBudget(BudgetCases, rust.RustHarness):
    language = 'rust'

    def test_output_nodes_after_successful_analysis(self):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        _, baseline = self.cli('index', '--full', timeout=180)
        old = baseline['revision']
        before = self.graph(old)
        try:
            vector = json.loads((java.ROOT/'tests/fixtures/analysis-resources/rust-output-budget.json').read_text())
            config.write_text(original + '\n[resources.index.analysis]\nmax_nodes=' + str(vector['max_nodes']) + '\n')
            _, failed = self.cli('index', '--full', code=1, timeout=180)
            self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
            report = self.cli('report', failed['report'])[0][0]['analysis_resources']
            self.assertIsNone(report['coordinator']['admission']['failure'])
            slot = report['coordinator']['languages'][0]
            self.assertIsNone(slot['host_usage']['failure'])
            self.assertEqual(slot['worker']['state'], 'failed')
            self.assertEqual(slot['worker']['phase'], 'records')
            self.assertEqual(slot['worker']['usage']['failure']['Limit']['resource'], 'nodes')
            self.assertEqual(self.cli('overview')[1]['revision'], old)
            self.assertEqual(self.graph(old), before)
        finally:
            config.write_text(original)
        _, recovered = self.cli('index', '--full', timeout=180)
        self.graph(recovered['revision'])

    def test_backend_work_budget_after_admission(self):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        _, baseline = self.cli('index', '--full', timeout=180)
        old = baseline['revision']
        before = self.graph(old)
        try:
            config.write_text(original + '\n[resources.index.analysis]\nmax_work=128\n')
            source = self.project / 'app/src/main.rs'
            source.write_text(source.read_text() + '\nfn budget_changed_input() -> u32 { 1 }\n')
            for args in [('index', '--full'), ('index',)]:
                _, failed = self.cli(*args, code=1, timeout=180)
                self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
                report = self.cli('report', failed['report'])[0][0]['analysis_resources']
                self.assertIsNone(report['coordinator']['admission']['failure'])
                worker = report['coordinator']['languages'][0]['worker']
                self.assertEqual(worker['state'], 'failed')
                self.assertEqual(worker['usage']['failure']['Limit']['resource'], 'work')
                self.assertLessEqual(worker['usage']['work'], worker['grant']['limits']['max_work'])
                self.assertEqual(self.cli('overview')[1]['revision'], old)
                self.assertEqual(self.graph(old), before)
        finally:
            config.write_text(original)
        _, recovered = self.cli('index', '--full', timeout=180)
        self.graph(recovered['revision'])



class PythonBudget(BudgetCases, python.PythonLanguage):
    language = 'python'

    def test_backend_work_budget_after_admission(self):
        self.backend_failure(False)

    def test_python_rust_failure_keeps_both_published_graphs(self):
        self.add_rust()
        self.backend_failure(True)

    def backend_failure(self, mixed):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text()
        _, baseline = self.cli('index', '--full', timeout=180)
        old = baseline['revision']
        before = self.graph(old)
        if mixed:
            with self.db(old) as db:
                self.assertEqual({r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')}, {'python', 'rust'})
        try:
            source = self.project / 'main.py'
            source.write_text(source.read_text() + ('\ndef added_for_budget():\n' + '    pass\n' * 10000 if mixed else '\ndef added_for_budget():\n    return factory()\n'))
            self.export()
            config.write_text(original + '\n[resources.index.analysis]\nmax_work=' + str(30000 if mixed else 128) + '\n')
            for args in [('index', '--full'), ('index',)]:
                _, failed = self.cli(*args, code=1, timeout=180)
                self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
                report = self.cli('report', failed['report'])[0][0]['analysis_resources']['coordinator']
                self.assertIsNone(report['admission']['failure'])
                self.assertFalse(report['complete'])
                slot = next(s for s in report['languages'] if s['allocation']['language'] == 'python')
                worker = slot['worker']
                self.assertEqual(worker['state'], 'failed')
                self.assertEqual(worker['usage']['failure']['Limit']['resource'], 'work')
                self.assertLessEqual(worker['usage']['work'], worker['grant']['limits']['max_work'])
                self.assertEqual(self.cli('overview')[1]['revision'], old)
                self.assertEqual(self.graph(old), before)
        finally:
            config.write_text(original)
        _, recovered = self.cli('index', '--full', timeout=180)
        self.graph(recovered['revision'])
        self.assertEqual(self.graph(old), before)


class PreparedPythonBudget(prepared.PreparedInputs):
    def test_prepared_reuse_finishes_every_instance(self):
        _, first = self.cli('index', '--prepare', '--full', timeout=180)
        _, reused = self.cli('index', timeout=180)
        report = self.cli('report', reused['report'])[0][0]['analysis_resources']['coordinator']
        self.assertTrue(report['complete'])
        workers = [w for slot in report['languages'] for w in slot['workers']]
        self.assertGreaterEqual(len(workers), 2)
        for worker in workers:
            self.assertEqual(worker['state'], 'complete')
            self.assertEqual(worker['phase'], 'reused')
            self.assertGreater(worker['usage']['work'], 0)
            self.assertGreater(worker['usage']['payload_bytes'], 0)
        with self.db(first['revision']) as db:
            before = db.execute('SELECT * FROM nodes ORDER BY id').fetchall()
        with self.db(reused['revision']) as db:
            self.assertEqual(db.execute('SELECT * FROM nodes ORDER BY id').fetchall(), before)


    def test_python_units_share_one_static_language_pool(self):
        _, result = self.cli('index', '--prepare', '--full', timeout=180)
        old = result['revision']
        with self.db(old) as db:
            before = db.execute('SELECT * FROM nodes ORDER BY id').fetchall()
            self.assertGreater(len(before), 0)
        report = self.cli('report', result['report'])[0][0]['analysis_resources']['coordinator']
        slot = next(s for s in report['languages'] if s['allocation']['language'] == 'python')
        self.assertEqual(len(slot['workers']), 3)
        self.assertTrue(all(w['state'] == 'complete' and w['usage']['work'] > 0 for w in slot['workers']))
        for field in ('max_work', 'max_nodes', 'max_payload_bytes'):
            self.assertEqual(sum(w['grant']['limits'][field] for w in slot['workers']), slot['allocation']['worker'][field])
        original = self.config
        try:
            self.config = original + '\n[resources.index.analysis]\nmax_work=128\n'
            self.write_config()
            _, failed = self.cli('index', '--prepare', '--full', code=1, timeout=180)
            self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
            report = self.cli('report', failed['report'])[0][0]['analysis_resources']['coordinator']
            self.assertIsNone(report['admission']['failure'])
            slot = next(s for s in report['languages'] if s['allocation']['language'] == 'python')
            self.assertTrue(any(w['state'] == 'failed' for w in slot['workers']))
            self.assertEqual(self.cli('overview')[1]['revision'], old)
            with self.db(old) as db:
                self.assertEqual(db.execute('SELECT * FROM nodes ORDER BY id').fetchall(), before)
        finally:
            self.config = original
            self.write_config()
        _, recovered = self.cli('index', '--prepare', '--full', timeout=180)
        self.assertEqual(recovered['publication'], 'published')


class PreparedRustBudget(prepared.PreparedInputs):
    def test_prepared_reuse_finishes_every_instance(self):
        self.config = 'schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\n[resources.index]\nworker_memory_bytes=1073741824\n[inputs]\n'
        for name in ('rust-a', 'rust-b'):
            path = self.project / name
            (path / 'src').mkdir(parents=True)
            (path / 'Cargo.toml').write_text('[workspace]\n[package]\nname="fixture"\nversion="0.1.0"\nedition="2024"\n')
            (path / 'src/lib.rs').write_text('pub fn answer() -> u32 { 42 }\n')
            subprocess.run(['cargo', 'generate-lockfile', '--offline'], cwd=path, check=True, capture_output=True)
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="rust"\nroot="{name}"\n'
        self.write_config()
        _, first = self.cli('index', '--prepare', '--full', timeout=180)
        _, reused = self.cli('index', timeout=180)
        report = self.cli('report', reused['report'])[0][0]['analysis_resources']['coordinator']
        self.assertTrue(report['complete'])
        workers = [w for slot in report['languages'] for w in slot['workers']]
        self.assertGreaterEqual(len(workers), 2)
        for worker in workers:
            self.assertEqual(worker['state'], 'complete')
            self.assertEqual(worker['phase'], 'reused')
            self.assertGreater(worker['usage']['work'], 0)
            self.assertGreater(worker['usage']['payload_bytes'], 0)
        with self.db(first['revision']) as db:
            before = db.execute('SELECT * FROM nodes ORDER BY id').fetchall()
        with self.db(reused['revision']) as db:
            self.assertEqual(db.execute('SELECT * FROM nodes ORDER BY id').fetchall(), before)


    def test_individual_units_pass_but_combined_task_exhausts(self):
        base='schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\n[resources.index]\nworker_memory_bytes=1073741824\n'
        entries={}
        for name in ('unit-a','unit-b'):
            path=self.project/name;(path/'src').mkdir(parents=True)
            (path/'Cargo.toml').write_text('[workspace]\n[package]\nname="fixture"\nversion="0.1.0"\nedition="2024"\n')
            (path/'src/lib.rs').write_text('pub fn answer() -> u32 { 1 }\n')
            subprocess.run(['cargo','generate-lockfile','--offline'],cwd=path,check=True,capture_output=True)
            entries[name]=f'\n[[inputs.entries]]\nid="{name}"\nlanguage="rust"\nroot="{name}"\n'
        def select(names,limited=True):
            self.config=base+'[inputs]\n'+''.join(entries[n] for n in names)
            if limited:
                vector=json.loads((java.ROOT/'tests/fixtures/analysis-resources/prepared-rust-budget.json').read_text())
                self.config+='\n[resources.index.analysis]\nmax_work='+str(vector['max_work'])+'\n'
            self.write_config()
        old=None
        for name in ('unit-a','unit-b'):
            select([name]);_,result=self.cli('index','--prepare','--full',timeout=180)
            old=result['revision']
            with self.db(old) as db:self.assertGreater(db.execute("SELECT count(*) FROM nodes WHERE language='rust'").fetchone()[0],0)
        select(['unit-a','unit-b'])
        _,failed=self.cli('index','--prepare','--full',code=1,timeout=180)
        self.assertEqual(failed['code'],'ANALYSIS_RESOURCE_LIMIT')
        self.assertEqual(self.cli('overview')[1]['revision'],old)
        report=self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
        self.assertIsNone(report['admission']['failure'])
        self.assertTrue(any(s['host_usage']['failure'] or any(w['state']=='failed' for w in s['workers']) for s in report['languages']))
        select(['unit-a','unit-b'],False)
        _,recovered=self.cli('index','--prepare','--full',timeout=180)
        with self.db(recovered['revision']) as db:self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes WHERE language='rust'")},{'unit-a','unit-b'})

    def test_two_cargo_workspaces_share_worker_quota_and_publish_atomically(self):
        self.config = 'schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\n[resources.index]\nworker_memory_bytes=1073741824\n[inputs]\n'
        for name in ('rust-a', 'rust-b'):
            path=self.project/name
            (path/'src').mkdir(parents=True)
            (path/'Cargo.toml').write_text('[workspace]\n[package]\nname="fixture"\nversion="0.1.0"\nedition="2024"\n')
            count=1 if name=='rust-a' else 80
            (path/'src/lib.rs').write_text(''.join(f'pub fn answer_{i}() -> u32 {{ {i} }}\n' for i in range(count)))
            subprocess.run(['cargo','generate-lockfile','--offline'],cwd=path,check=True,capture_output=True)
            self.config += f'\n[[inputs.entries]]\nid="{name}"\nlanguage="rust"\nroot="{name}"\n'
        self.write_config()
        _,result=self.cli('index','--prepare','--full',timeout=180)
        old=result['revision']
        with self.db(old) as db:
            graph=db.execute('SELECT * FROM nodes ORDER BY id').fetchall()
            self.assertGreater(len(graph),80)
            self.assertEqual({r[0] for r in db.execute("SELECT DISTINCT module FROM nodes WHERE language='rust'")},{'rust-a','rust-b'})
        report=self.cli('report',result['report'])[0][0]['analysis_resources']['coordinator']
        slot=next(s for s in report['languages'] if s['allocation']['language']=='rust')
        self.assertEqual(len(slot['workers']),2)
        self.assertTrue(all(w['state']=='complete' for w in slot['workers']))
        for field in ('max_work','max_nodes','max_payload_bytes'):
            self.assertEqual(sum(w['grant']['limits'][field] for w in slot['workers']),slot['allocation']['worker'][field])
        original=self.config
        try:
            self.config=original+'\n[resources.index.analysis]\nmax_work=256\n';self.write_config()
            _,failed=self.cli('index','--prepare','--full',code=1,timeout=180)
            self.assertEqual(failed['code'],'ANALYSIS_RESOURCE_LIMIT')
            report=self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
            self.assertIsNone(report['admission']['failure'])
            slot=next(s for s in report['languages'] if s['allocation']['language']=='rust')
            self.assertTrue(any(w['state']=='failed' and w['usage']['failure'] for w in slot['workers']))
            self.assertFalse(report['complete'])
            self.assertEqual(self.cli('overview')[1]['revision'],old)
            with self.db(old) as db:self.assertEqual(db.execute('SELECT * FROM nodes ORDER BY id').fetchall(),graph)
        finally:self.config=original;self.write_config()
        _,recovered=self.cli('index','--prepare','--full',timeout=180)
        self.assertEqual(recovered['publication'],'published')


def load_tests(loader, tests, pattern):
    # PythonLanguage has legacy test methods: run only this gate's named cases.
    suite = unittest.TestSuite()
    for case in (JavaBudget, RustBudget, PythonBudget):
        for name in ('test_work_budget', 'test_node_budget', 'test_payload_budget', 'test_depth_budget', 'test_remaining_capacity_allocations', 'test_unchanged_reuse_has_discovery_receipt'):
            suite.addTest(case(name))
    suite.addTest(PreparedPythonBudget('test_prepared_reuse_finishes_every_instance'))
    suite.addTest(PreparedRustBudget('test_prepared_reuse_finishes_every_instance'))
    suite.addTest(JavaBudget('test_discovery_payload_failure_has_final_receipt_and_preserves_head'))
    suite.addTest(JavaBudget('test_backend_work_budget_after_admission'))
    suite.addTest(PythonBudget('test_backend_work_budget_after_admission'))
    suite.addTest(PythonBudget('test_python_rust_failure_keeps_both_published_graphs'))
    suite.addTest(RustBudget('test_backend_work_budget_after_admission'))
    suite.addTest(RustBudget('test_output_nodes_after_successful_analysis'))
    suite.addTest(PreparedPythonBudget('test_python_units_share_one_static_language_pool'))
    suite.addTest(PreparedRustBudget('test_two_cargo_workspaces_share_worker_quota_and_publish_atomically'))
    suite.addTest(PreparedRustBudget('test_individual_units_pass_but_combined_task_exhausts'))
    return suite


if __name__ == '__main__':
    unittest.main(verbosity=2)
