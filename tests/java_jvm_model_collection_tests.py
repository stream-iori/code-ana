#!/usr/bin/env python3
"""Collector exclusions and independent oracle resource policy remain explicit."""
import importlib.util
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('jvm_collector', ROOT / 'scripts/java-jvm-project-models.py')
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
import java_decode_budget_reference as budget


class CollectionPolicy(unittest.TestCase):
    def test_app_test_defers_four_of_160_cases(self):
        expected = [f'{mode}:{module}@{scope}'
                    for mode in ('raw', 'ast')
                    for module in ['app/test', *[f'm{i}' for i in range(39)]]
                    for scope in ('MAIN', 'TEST')]
        selected = collector.selected_cases(expected, None, {'app/test'})
        self.assertEqual(len(expected), 160)
        self.assertEqual(len(selected), 156)
        self.assertEqual(set(expected) - set(selected),
                         {f'{mode}:app/test@{scope}' for mode in ('raw', 'ast') for scope in ('MAIN', 'TEST')})

    def test_context_subset_preserves_both_modes(self):
        self.assertEqual(collector.selected_cases(['raw:x@MAIN', 'ast:x@MAIN', 'raw:y@TEST'], 'x@MAIN', set()),
                         ['raw:x@MAIN', 'ast:x@MAIN'])

    def test_excluded_context_cannot_claim_an_empty_pass(self):
        with self.assertRaisesRegex(ValueError, 'PROJECT_MODEL_EMPTY_SELECTION'):
            collector.selected_cases(['raw:app/test@MAIN'], 'app/test@MAIN', {'app/test'})

    def test_two_gib_is_valid_and_out_of_range_values_fail(self):
        for value in (65536, 512 << 20, 2 << 30):
            self.assertEqual(budget.validate_budget(value), value)
        for value in (0, 65535, (2 << 30) + 1, True):
            with self.assertRaisesRegex(ValueError, 'JAVA_MODEL_DECODE_BYTES_RANGE'):
                budget.validate_budget(value)

    def test_oracle_overlay_changes_only_resource_admission(self):
        text = 'class JavacProtocol {\n    static final int MAGIC = 1;\nvar budget = new JavacDecodeBudget(JavacDecodeBudget.DEFAULT_BYTES);\n}'
        adapted = budget.adapted_source(text)
        self.assertIn('new JavacDecodeBudget(resourceDecodeBudget())', adapted)
        self.assertIn('codeana.javac.decode.bytes', adapted)
        self.assertIn('bytes > (2L << 30)', adapted)
        self.assertEqual(adapted[adapted.index('    static final int MAGIC'):],
                         text[text.index('    static final int MAGIC'):].replace(
                             'new JavacDecodeBudget(JavacDecodeBudget.DEFAULT_BYTES)',
                             'new JavacDecodeBudget(resourceDecodeBudget())'))

    def test_changed_frozen_admission_point_is_rejected(self):
        for text in ('', 'new JavacDecodeBudget(JavacDecodeBudget.DEFAULT_BYTES)'):
            with self.assertRaisesRegex(ValueError, 'FROZEN_DECODE_POLICY_ADMISSION_POINT'):
                budget.adapted_source(text)


if __name__ == '__main__':
    unittest.main(verbosity=2)
