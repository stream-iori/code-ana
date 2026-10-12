#!/usr/bin/env python3
"""Execution evidence cannot be replaced by labels or skipped JUnit cases."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('coverage', Path(__file__).resolve().parents[1] / 'scripts/java-coverage-acceptance-gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CoverageGateTests(unittest.TestCase):
    def test_target_project_tests_are_analysis_inputs(self):
        self.assertTrue(gate.analyzer_test_source('anatomist-app/src/test/java/com/anatomist/store/OriginalTest.java'))
        self.assertFalse(gate.analyzer_test_source('fixtures/demo/src/test/java/com/example/BusinessTest.java'))

    def test_empty_proof_set_is_rejected(self):
        for inputs in [None, {}, []]:
            with self.assertRaisesRegex(ValueError, 'COVERAGE_EXECUTION_PROOFS_REQUIRED'):
                gate.verify_proofs(dict(status='passed', inputs=inputs), {})

    def test_changed_proof_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            proof = Path(directory) / 'source'; proof.write_text('executed')
            receipt = dict(inputs={str(proof): gate.sha256(proof)})
            pinned = {}; gate.verify_proofs(receipt, pinned)
            self.assertEqual(pinned, {str(proof.resolve()): receipt['inputs'][str(proof)]})
            proof.write_text('changed')
            with self.assertRaisesRegex(ValueError, 'COVERAGE_PROOF_DRIFT'):
                gate.verify_proofs(receipt, {})

    def test_skipped_and_failed_cases_are_not_execution_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'TEST-original.xml'
            report.write_text('<testsuite><testcase classname="original.Test" name="retained(Path)"/></testsuite>')
            self.assertEqual(gate.junit_cases([report]), {'original.Test#retained'})
            for marker in ['skipped', 'failure', 'error']:
                report.write_text('<testsuite><testcase classname="original.Test" name="retained(Path)"><' + marker + '/></testcase></testsuite>')
                with self.assertRaisesRegex(ValueError, 'UNPASSED_COVERAGE_JUNIT_CASE'):
                    gate.junit_cases([report])


if __name__ == '__main__':
    unittest.main()
