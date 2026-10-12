"""Regression for process CPU accounting across ps role changes and PID reuse."""
import importlib.util
import pathlib
import hashlib
import json
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    'monitor_v2', ROOT / 'scripts/java-performance-monitor-single-v2.py')
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)
spec = importlib.util.spec_from_file_location(
    'runner_v2', ROOT / 'scripts/java-performance-single-v2.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class CpuAccounting(unittest.TestCase):
    def test_exit_shortening_does_not_double_count(self):
        cpu, roles = {}, {}
        for command, seconds in [('java -jar runner.jar', 40), ('(java)', 41)]:
            monitor.record_cpu(cpu, roles, dict(pid=10, started='birth1',
                                               command=command, cpu_seconds=seconds))
        self.assertEqual(monitor.cpu_totals(cpu, roles), {'jvm_runner': 41})

    def test_pid_reuse_is_separate_and_late_role_discovery_is_retained(self):
        cpu, roles = {}, {}
        for birth, command, seconds in [('birth1', '(java)', 3),
                                        ('birth1', 'java -jar runner.jar', 8),
                                        ('birth2', 'codeana-daemon', 2)]:
            monitor.record_cpu(cpu, roles, dict(pid=10, started=birth,
                                               command=command, cpu_seconds=seconds))
        self.assertEqual(monitor.cpu_totals(cpu, roles), {'jvm_runner': 8, 'daemon': 2})



class CandidateCorrectness(unittest.TestCase):
    def test_historical_or_different_build_cannot_approve_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = pathlib.Path(directory).resolve()
            manifest, build, gate = [folder / name for name in ('source.json', 'build.json', 'gate.json')]
            manifest.write_text('source snapshot')
            build.write_text('release build')
            digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
            value = dict(status='passed', candidate_correctness_accepted=True,
                         unexplained_failures=[], build_profile='release',
                         source_manifest_sha256=digest(manifest), build_receipt_sha256=digest(build),
                         inputs={str(manifest): digest(manifest), str(build): digest(build)})
            gate.write_text(json.dumps(value))
            runner.candidate_correctness(gate, manifest, build)
            value['build_receipt_sha256'] = 'old build'
            gate.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, 'CANDIDATE_CORRECTNESS_BUILD_DRIFT'):
                runner.candidate_correctness(gate, manifest, build)
            value.pop('candidate_correctness_accepted')
            value['native_accepted'] = True
            gate.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, 'FULL_CORRECTNESS_REQUIRED'):
                runner.candidate_correctness(gate, manifest, build)


if __name__ == '__main__':
    unittest.main()
