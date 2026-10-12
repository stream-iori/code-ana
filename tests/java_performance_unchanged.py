"""Real worker/SQLite regression for retained Java revisions, including the next full run."""
import os
from pathlib import Path


def test_unchanged_manifest_preserves_warm_worker_without_java_analysis(self):
    timings=Path(os.environ['CODEANA_PERFORMANCE_TIMINGS_DIR'])
    _,first=self.cli('index','--full',timeout=180)
    before=self.facts(first['revision'])
    worker=self.java_worker_pid()
    self.assertIsNotNone(worker)
    captures=set(timings.glob('worker-*'))
    self.assertTrue(captures)
    _,unchanged=self.cli('index',timeout=180)
    self.assertEqual(unchanged['publication'],'published')
    self.assertNotEqual(unchanged['revision'],first['revision'])
    self.assertEqual(self.facts(unchanged['revision']),before)
    self.assertEqual(set(timings.glob('worker-*')),captures,'unchanged input started a Java analysis')
    self.assertEqual(self.java_worker_pid(),worker,'idle warm worker was replaced')
    _,forced=self.cli('index','--full',timeout=180)
    self.assertEqual(self.facts(forced['revision']),before)
    self.assertGreater(len(set(timings.glob('worker-*'))),len(captures))
    self.assertEqual(self.facts(first['revision']),before)
    self.assertFalse(list(self.project.rglob('*.class')))
