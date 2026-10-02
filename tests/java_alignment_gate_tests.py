#!/usr/bin/env python3
"""Acceptance accounting must not turn declared progress into executed evidence."""
import copy
import json
import unittest
from java_alignment import ROOT, capability_results

class AcceptanceAccounting(unittest.TestCase):
    def setUp(self):
        self.matrix = json.loads((ROOT / 'tests/java_alignment_matrix.json').read_text())
        self.gates = [dict(name=name, status='passed') for name in sorted({
            gate for item in self.matrix['capabilities'] for gate in item['required_gates']})]

    def test_status_labels_cannot_complete_missing_implementations(self):
        for item in self.matrix['capabilities']:
            item['status'] = 'passed'
        item = next(c for c in self.matrix['capabilities'] if c['id'] == 'J02')
        item['implementation_complete'] = False
        results = capability_results(self.matrix, self.gates)
        blocked = next(c for c in results if c['id'] == 'J02')
        self.assertEqual(blocked['status'], 'blocked')
        self.assertIn('implementation_not_complete', blocked['blockers'])

    def test_unverified_acceptance_blocks_an_implemented_capability(self):
        item = next(c for c in self.matrix['capabilities'] if c['id'] == 'E01')
        item['implementation_complete'] = True
        item['remaining_acceptance'] = ['independent semantic proof missing']
        result = next(c for c in capability_results(self.matrix, self.gates) if c['id'] == 'E01')
        self.assertEqual(result['status'], 'blocked')
        self.assertIn('independent semantic proof missing', result['blockers'])

    def test_implemented_capabilities_still_require_executed_gates(self):
        for gates in ([], [dict(name=g['name'], status='failed') for g in self.gates]):
            self.assertTrue(all(c['status'] == 'blocked' for c in capability_results(self.matrix, gates)))
        matrix = copy.deepcopy(self.matrix)
        item = next(c for c in matrix['capabilities'] if c['id'] == 'E01')
        item['checks'] = ['tests/does-not-exist.py']
        result = next(c for c in capability_results(matrix, self.gates) if c['id'] == 'E01')
        self.assertIn('missing_check:tests/does-not-exist.py', result['blockers'])

    def test_duplicate_or_removed_capabilities_are_rejected(self):
        matrix = copy.deepcopy(self.matrix)
        matrix['capabilities'].append(matrix['capabilities'][0])
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_CAPABILITY'):
            capability_results(matrix, self.gates)
        matrix['capabilities'] = matrix['capabilities'][:-2]
        with self.assertRaisesRegex(ValueError, 'EXPECTED_52_CAPABILITIES'):
            capability_results(matrix, self.gates)

if __name__ == '__main__':
    unittest.main(verbosity=2)
