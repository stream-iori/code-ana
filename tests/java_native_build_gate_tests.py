import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_build_gate', ROOT / 'scripts/check-java-jvm-acceptance.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class NativeBuildPrerequisites(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.source = self.root / 'lang-java-adapotr/java-bridge/src/main/java/A.java'
        self.source.parent.mkdir(parents=True); self.source.write_text('class A {}')
        self.components = self.root / 'components.json'
        self.components.write_text(json.dumps(dict(sources={str(self.source): gate.sha(self.source)})))
        self.receipt = self.root / 'gate.json'
        self.value = dict(kind='complete_jvm_migration_acceptance', status='passed',
            jvm_migration_accepted=True, unexplained_failures=[],
            inputs={str(self.components): gate.sha(self.components)},
            receipts={name: str(self.components) for name in gate.REQUIRED})

    def check(self):
        self.receipt.write_text(json.dumps(self.value))
        return gate.check(self.receipt, self.root)

    def test_incomplete_gate_cannot_start_native_build(self):
        self.value['jvm_migration_accepted'] = False
        with self.assertRaisesRegex(ValueError, 'COMPLETE_JVM_GATE_REQUIRED'):
            self.check()

    def test_gate_input_drift_is_rejected(self):
        self.components.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'PROOF_DRIFT'):
            self.check()

    def test_build_source_must_be_the_verified_jvm_production_source(self):
        self.assertEqual(self.check(), 1)
        self.source.write_text('class A { int changed; }')
        with self.assertRaisesRegex(ValueError, 'VERIFIED_PRODUCTION_SOURCE_REQUIRED'):
            self.check()

    def test_same_count_with_missing_history_is_rejected(self):
        self.value['receipts']['unrelated'] = self.value['receipts'].pop('history')
        with self.assertRaisesRegex(ValueError, 'COMPLETE_JVM_GATE_REQUIRED'):
            self.check()

    def test_removed_verified_source_is_rejected(self):
        second = self.source.with_name('B.java')
        second.write_text('class B {}')
        self.components.write_text(json.dumps(dict(sources={
            str(path): gate.sha(path) for path in (self.source, second)})))
        self.value['inputs'][str(self.components)] = gate.sha(self.components)
        self.assertEqual(self.check(), 2)
        second.unlink()
        with self.assertRaisesRegex(ValueError, 'SOURCE_SET_CHANGED'):
            self.check()


if __name__ == '__main__':
    unittest.main()
