import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('diagnostic', ROOT / 'scripts/java-performance-diagnostic.py')
DIAGNOSTIC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIAGNOSTIC)


class DiagnosticTests(unittest.TestCase):
    def test_missing_native_management_data_is_unavailable_not_zero(self):
        value = DIAGNOSTIC.runtime_delta({}, {})
        self.assertIsNone(value['gc_ms'])
        self.assertIsNone(value['thread_cpu_ns'])
        before = dict(gc_available=True, gc=[dict(name='serial', time_ms=10, count=2)],
                      thread_cpu_available=True, thread_cpu_ns=100)
        after = dict(gc_available=True, gc=[dict(name='serial', time_ms=25, count=3)],
                     thread_cpu_available=True, thread_cpu_ns=200)
        self.assertEqual(DIAGNOSTIC.runtime_delta(before, after)['gc_ms'], 15)
        self.assertEqual(DIAGNOSTIC.runtime_delta(before, after)['thread_cpu_ns'], 100)
        after['gc'][0]['time_ms'] = 5
        self.assertIsNone(DIAGNOSTIC.runtime_delta(before, after)['gc_ms'])
        after['thread_cpu_ns'] = -1
        self.assertIsNone(DIAGNOSTIC.runtime_delta(before, after)['thread_cpu_ns'])

    def test_current_proofs_detect_tampering_and_reject_missing_groups(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / 'proof'
            path.write_bytes(b'accepted')
            proofs = {str(path): DIAGNOSTIC.sha(path)}
            DIAGNOSTIC.verify_inputs(proofs)
            path.write_bytes(b'modified')
            with self.assertRaisesRegex(ValueError, 'INPUT_DRIFT'):
                DIAGNOSTIC.verify_inputs(proofs)
        with self.assertRaisesRegex(ValueError, 'CURRENT_FIXTURES'):
            DIAGNOSTIC.validate_fixtures([])

    def test_nested_phase_failure_cannot_be_summarized_as_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = pathlib.Path(temporary)
            DIAGNOSTIC.write(folder / 'receipt.json', dict(status='failed'))
            with self.assertRaisesRegex(ValueError, 'SUCCESSFUL_WORKER'):
                DIAGNOSTIC.phase_summary(folder, 'native', 'cold_application_cache')


if __name__ == '__main__':
    unittest.main()
