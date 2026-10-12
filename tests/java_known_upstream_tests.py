import copy
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('known_issues', ROOT / 'scripts/java-known-upstream-differences.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ExactUpstreamExceptions(unittest.TestCase):
    def setUp(self):
        self.first = ['bean', 'beans.xml', 'holder', 0,
                      dict(line=1, column=1, end_line=2, end_column=1)]
        self.last = ['bean', 'beans.xml', 'holder', 1,
                     dict(line=3, column=1, end_line=4, end_column=1)]
        self.aliases = {gate.encoded(self.first): self.last}

    def test_changed_span_or_ordinal_is_not_admitted_as_known_collision(self):
        self.assertEqual(gate.collapsed_endpoint(self.first, self.aliases), self.last)
        changed = copy.deepcopy(self.first)
        changed[-1]['end_column'] = 99
        self.assertNotEqual(gate.collapsed_endpoint(changed, self.aliases), self.last)
        changed = copy.deepcopy(self.first); changed[3] = 77
        self.assertNotEqual(gate.collapsed_endpoint(changed, self.aliases), self.last)

    def test_ambiguity_requires_the_same_two_proven_overwritten_endpoints(self):
        self.assertEqual(gate.collapsed_endpoint(['ambiguous_beans', [self.first, self.last]], self.aliases), self.last)
        unrelated = copy.deepcopy(self.last); unrelated[2] = 'other'
        with self.assertRaisesRegex(ValueError, 'NOT_CAUSED_BY_PROVEN_COLLISION'):
            gate.collapsed_endpoint(['ambiguous_beans', [self.first, unrelated]], self.aliases)
        with self.assertRaisesRegex(ValueError, 'UNPROVEN_BEAN_AMBIGUITY'):
            gate.collapsed_endpoint(['ambiguous_beans', [self.first, self.last, unrelated]], self.aliases)

    def test_equal_keys_do_not_hide_changed_occurrence_counts(self):
        one = [dict(key=['a', 'b', 1], count=1)]
        two = [dict(key=['a', 'b', 1], count=2)]
        self.assertNotEqual(gate.counters(one), gate.counters(two))
        with self.assertRaisesRegex(ValueError, 'INVALID_DIFFERENCE_MULTIPLICITY'):
            gate.counters([dict(key=['a'], count=0)])


if __name__ == '__main__':
    unittest.main()
