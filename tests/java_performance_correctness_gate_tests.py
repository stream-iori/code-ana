#!/usr/bin/env python3
"""A correctness label cannot authorize expensive performance execution."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('rounds', Path(__file__).resolve().parents[1] / 'scripts/java-performance-rounds.py')
rounds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rounds)


class CorrectnessProofTests(unittest.TestCase):
    def test_incomplete_receipt_never_authorizes_performance(self):
        with tempfile.TemporaryDirectory() as folder:
            receipt = Path(folder) / 'receipt.json'
            receipt.write_text(json.dumps(dict(status='passed', native_accepted=False, unexplained_failures=[], inputs={})))
            with self.assertRaisesRegex(ValueError, 'FULL_CORRECTNESS_REQUIRED'):
                rounds.accepted(receipt, 'native_accepted')

    def test_claimed_acceptance_without_proofs_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            receipt = Path(folder) / 'receipt.json'
            for inputs in [None, {}, []]:
                receipt.write_text(json.dumps(dict(status='passed', native_accepted=True, unexplained_failures=[], inputs=inputs)))
                with self.assertRaisesRegex(ValueError, 'NONEMPTY_CORRECTNESS_PROOFS_REQUIRED'):
                    rounds.accepted(receipt, 'native_accepted')

    def test_changed_and_relative_proofs_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            proof = Path(folder) / 'proof'; proof.write_text('accepted bytes')
            digest = hashlib.sha256(proof.read_bytes()).hexdigest()
            receipt = Path(folder) / 'receipt.json'
            report = dict(status='passed', native_accepted=True, unexplained_failures=[], inputs={str(proof): digest})
            receipt.write_text(json.dumps(report))
            self.assertTrue(rounds.accepted(receipt, 'native_accepted')['native_accepted'])
            proof.write_text('changed bytes')
            with self.assertRaisesRegex(ValueError, 'CORRECTNESS_PROOF_DRIFT'):
                rounds.accepted(receipt, 'native_accepted')
            report['inputs'] = {'proof': digest}; receipt.write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'CORRECTNESS_PROOF_DRIFT'):
                rounds.accepted(receipt, 'native_accepted')


if __name__ == '__main__':
    unittest.main()
