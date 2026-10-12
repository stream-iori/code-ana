#!/usr/bin/env python3
"""Missing gates and mixed native bundles never count as complete acceptance."""
import importlib.util
from pathlib import Path
import unittest
import tempfile

spec = importlib.util.spec_from_file_location('gate', Path(__file__).resolve().parents[1] / 'scripts/java-native-acceptance-gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class NativeGateTests(unittest.TestCase):
    def test_private_release_relative_proofs_use_its_recorded_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            receipt, base = gate.receipt_location(dict(receipt=str(root / 'receipt.json'), input_root=str(root)))
            self.assertEqual(receipt, root / 'receipt.json')
            self.assertEqual(gate.proof_location('scripts/tool.py', base), root / 'scripts/tool.py')
            self.assertEqual(gate.proof_location('/absolute/tool.py', base), Path('/absolute/tool.py'))
            with self.assertRaisesRegex(ValueError, 'ABSOLUTE_RECEIPT_INPUT_ROOT_REQUIRED'):
                gate.receipt_location(dict(receipt=str(receipt), input_root='relative'))

    def test_incomplete_gate_set_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'COMPLETE_NATIVE_RECEIPT_SET_REQUIRED'):
            gate.validate({})

    def test_fixture_only_jvm_receipt_is_rejected(self):
        reports = {name: {} for name in gate.REQUIRED}
        reports['jvm'] = dict(status='passed', kind='fixtures', jvm_migration_accepted=True)
        with self.assertRaisesRegex(ValueError, 'COMPLETE_JVM_REQUIRED'):
            gate.validate(reports)

    def test_missing_shared_library_and_mixed_runtime_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'NATIVE_BUNDLE_LINEAGE_REQUIRED'):
            gate.runtime_lineage({'publication': dict(artifacts={})})
        artifacts = {f'target/lib/java/{name}': 'accepted' for name in ['native/libcodeana_java.dylib', 'compiler-bridge.jar', 'maven-input-worker.jar', 'native/manifest.json']}
        reports = dict(publication=dict(artifacts=artifacts))
        for name in ['lifecycle', 'probe', 'product']:
            reports[name] = dict(sources_and_artifacts={f'/fixture/runtime/{path}': checksum for path, checksum in artifacts.items()})
        gate.runtime_lineage(reports)
        reports['product']['sources_and_artifacts']['/fixture/runtime/target/lib/java/native/libcodeana_java.dylib'] = 'different'
        with self.assertRaisesRegex(ValueError, 'MIXED_NATIVE_RUNTIME'):
            gate.runtime_lineage(reports)


if __name__ == '__main__':
    unittest.main()
