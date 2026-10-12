import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class FullReceiptEntry(unittest.TestCase):
    def test_fixture_success_cannot_authorize_full_migration(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = pathlib.Path(directory) / 'fixture.json'
            receipt.write_text(json.dumps(dict(status='passed', complete_fixture_gate=True, cases=[])))
            result = subprocess.run([sys.executable, str(ROOT / 'tests/java_alignment.py'), '--level', 'full', '--final-receipt', str(receipt)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('COMPLETE_MIGRATION_RECEIPT_REQUIRED', result.stderr)

    def test_full_claim_without_identity_proofs_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = pathlib.Path(directory) / 'full.json'
            values = {key: True for key in ['complete_capability_migration_accepted', 'jvm_migration_accepted', 'native_accepted', 'coverage_closed', 'performance_accepted']}
            receipt.write_text(json.dumps(dict(values, status='passed', unexplained_failures=[], inputs={})))
            result = subprocess.run([sys.executable, str(ROOT / 'tests/java_alignment.py'), '--level', 'full', '--final-receipt', str(receipt)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('COMPLETE_MIGRATION_INPUT_PROOFS_REQUIRED', result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
