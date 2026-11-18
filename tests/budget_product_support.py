"""Shared real-process resource contract; no test cases without a backend harness."""
import contextlib
import sqlite3
import subprocess


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
        source = next(self.project.rglob('*.java')) if self.language == 'java' and field == 'max_depth' else None
        saved_source = source.read_text() if source else None
        try:
            # Depth limits apply to actual analysis; unchanged Java can legitimately reuse.
            if source:
                source.write_text(saved_source + '\n// changed input for depth budget\n')
            config.write_text(original + '\n[resources.index.analysis]\n' + field + '=1\n')
            for args in [('index', '--full'), ('index',)]:
                _, failed = self.cli(*args, code=1, timeout=180)
                self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT',failed)
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
            if source:
                source.write_text(saved_source)
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
        markdown = self.markdown(result['report'])
        self.assertIn('Analysis resources:', markdown)
        self.assertIn(report['analysis_resources']['coverage'], markdown)
        for slot in slots:
            for worker in slot['workers']:
                self.assertEqual(markdown.count(worker['grant']['id']), 1)
        if self.language in ('java', 'rust', 'python', 'typescript'):
            slot = next(s for s in slots if s['allocation']['language'] == self.language)
            self.assertGreater(slot['host_usage']['payload_bytes'], 0)
            self.assertGreater(slot['host_usage']['nodes'], 0)
            self.assertEqual(slot['workers'][0]['state'], 'complete')
            self.assertGreater(slot['workers'][0]['usage']['work'], 0)
            self.assertGreater(slot['workers'][0]['usage']['max_depth'], 1)
        self.assertEqual(len({s['workers'][0]['grant']['id'] for s in slots}), len(slots))
        for used, limit in [('work', 'max_work'), ('nodes', 'max_nodes'), ('payload_bytes', 'max_payload_bytes')]:
            self.assertEqual(coordinator['admission'][used] + sum(s['allocation']['total'][limit] for s in slots), coordinator['limits'][limit])
            for slot in slots:
                allocation = slot['allocation']
                self.assertEqual(allocation['host'][limit] + allocation['worker'][limit], allocation['total'][limit])
                self.assertEqual(slot['workers'][0]['grant']['limits'][limit], allocation['worker'][limit])

    def test_unchanged_reuse_has_discovery_receipt(self):
        _, first = self.cli('index', '--full', timeout=180)
        before = self.graph(first['revision'])
        config=self.project/'.code-ana/config.toml'
        original=config.read_text()
        try:
            if self.language == 'java':
                config.write_text(original+'\n[resources.index.analysis]\nmax_depth=1\n')
            _, reused = self.cli('index', timeout=180)
        finally:
            config.write_text(original)
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
