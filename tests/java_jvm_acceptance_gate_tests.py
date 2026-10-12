import importlib.util
import pathlib
import hashlib
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('jvm_acceptance', ROOT / 'scripts/java-jvm-acceptance-gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class FullGateAccounting(unittest.TestCase):
    def test_relative_collector_proofs_bind_to_repository_not_build_cwd(self):
        import os
        before = pathlib.Path.cwd()
        with tempfile.TemporaryDirectory() as folder:
            try:
                os.chdir(folder)
                self.assertEqual(gate.input_path('tests/java_jvm_acceptance_gate_tests.py'),
                                 ROOT / 'tests/java_jvm_acceptance_gate_tests.py')
            finally:
                os.chdir(before)

    def test_database_hash_does_not_load_the_whole_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'facts.db'
            data = b'facts' * 500000
            path.write_bytes(data)
            with mock.patch.object(pathlib.Path, 'read_bytes', side_effect=AssertionError('unbounded read')):
                self.assertEqual(gate.sha(path), hashlib.sha256(data).hexdigest())

    def test_missing_gate_is_rejected_even_with_passed_fixture(self):
        with self.assertRaisesRegex(ValueError, 'COMPLETE_GATE_MANIFEST_REQUIRED'):
            gate.validate({'components': {'status': 'passed'}})

    def test_omitted_duplicate_and_failed_cases_are_rejected(self):
        good = dict(status='passed', cases=[dict(id='a', status='passed'), dict(id='b', status='passed')])
        gate.cases(good, 2)
        for rows in [good['cases'][:1], [good['cases'][0]] * 2,
                     [good['cases'][0], dict(id='b', status='failed')]]:
            with self.assertRaisesRegex(ValueError, 'CASE_COVERAGE'):
                gate.cases(dict(good, cases=rows), 2)

    def test_drift_cannot_be_hidden_by_case_success(self):
        with self.assertRaisesRegex(ValueError, 'INPUT_DRIFT_OR_OMISSION'):
            gate.cases(dict(status='passed', cases=[dict(id='a', status='passed')],
                            changed_inputs=['runner.jar']), 1)

    def test_unrelated_or_mixed_runtime_proofs_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'RUNTIME_BUNDLE_REQUIRED'):
            gate.bundle_hash(dict(sources_and_artifacts={'/old/runner.jar': 'old'}), 'runner.jar')
        with self.assertRaisesRegex(ValueError, 'RUNTIME_BUNDLE_REQUIRED'):
            gate.bundle_hash(dict(sources_and_artifacts={
                '/a/runtime/target/lib/java/jvm/runner.jar': 'a',
                '/b/runtime/target/lib/java/jvm/runner.jar': 'b'}), 'runner.jar')


if __name__ == '__main__':
    unittest.main()
