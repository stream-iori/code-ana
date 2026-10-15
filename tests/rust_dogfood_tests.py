#!/usr/bin/env python3
"""Dogfood evidence guards; no parser/performance runs in these tests."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('rust_dogfood',ROOT/'scripts/rust-source-dogfood.py')
dogfood=importlib.util.module_from_spec(spec)
spec.loader.exec_module(dogfood)

class EvidenceGuards(unittest.TestCase):
    def test_existing_marker_or_receipt_prevents_preparation_and_budget_changes(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'target') as temporary:
            root=pathlib.Path(temporary)
            project=root/'project'
            config=project/'.code-ana/config.toml'
            config.parent.mkdir(parents=True)
            config.write_text('[resources.index]\nworker_memory_bytes=268435456\n')
            for name in ('measurement-started.json','receipt.json'):
                evidence=root/name.replace('.json','')
                evidence.mkdir()
                (evidence/name).write_text('{}')
                with self.assertRaisesRegex(ValueError,'ALREADY_MEASURED'):
                    dogfood.measure(project,evidence,reuse_inputs=True,worker_memory_bytes=536870912)
                self.assertIn('268435456',config.read_text())

    def test_same_timestamp_memory_total_does_not_sum_independent_peaks(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'target') as temporary:
            monitor=dogfood.MemoryMonitor(0)
            monitor.thread.start=lambda:None
            monitor.thread.join=lambda **kwargs:None
            monitor.samples=[dict(daemon_rss_bytes=100,worker_tree_rss_bytes=10,total_rss_bytes=110),dict(daemon_rss_bytes=10,worker_tree_rss_bytes=200,total_rss_bytes=210)]
            summary=monitor.finish(pathlib.Path(temporary))
            self.assertEqual(summary['simultaneous_peak_rss_bytes'],210)
            self.assertEqual(summary['daemon_peak_rss_bytes'],100)
            self.assertEqual(summary['worker_tree_peak_rss_bytes'],200)
            self.assertEqual(len(json.loads((pathlib.Path(temporary)/'memory-samples.json').read_text())),2)

if __name__=='__main__':
    unittest.main()
