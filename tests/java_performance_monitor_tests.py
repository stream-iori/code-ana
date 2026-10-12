import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('monitor', ROOT / 'scripts/java-performance-monitor.py')
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class PerformanceObservations(unittest.TestCase):
    def test_cpu_clock_handles_days_and_fractional_seconds(self):
        self.assertEqual(monitor.duration('1-02:03:04.50'), 93784.5)
        self.assertEqual(monitor.duration('01:02.25'), 62.25)

    def test_tree_excludes_unrelated_worker_and_tracks_grandchildren(self):
        rows = [dict(pid=2, parent=1), dict(pid=3, parent=2), dict(pid=4, parent=3), dict(pid=5, parent=1)]
        self.assertEqual([row['pid'] for row in monitor.owned_processes(rows, 2)], [2, 3, 4])

    def test_java_compiler_is_separate_from_jvm_host(self):
        self.assertEqual(monitor.process_role('java -jar runner.jar'), 'jvm_runner')
        self.assertEqual(monitor.process_role('java com.anatomist.worker.JavacWorker'), 'javac')

    def test_hardlinks_and_overlapping_roots_are_not_double_counted(self):
        with tempfile.TemporaryDirectory() as folder:
            import os
            root = pathlib.Path(folder); source = root / 'a'; source.write_bytes(b'123')
            os.link(source, root / 'b')
            self.assertEqual(monitor.disk_usage([root, source])['logical_bytes'], 3)

    def test_foreign_daemon_prevents_exclusive_measurement(self):
        rows = [dict(pid=2, parent=1, command='/runtime/codeana-daemon --home /home')]
        self.assertEqual(monitor.foreign_analyzers(rows, []), rows)
        self.assertEqual(monitor.foreign_analyzers(rows, rows), [])


if __name__ == '__main__':
    unittest.main()
