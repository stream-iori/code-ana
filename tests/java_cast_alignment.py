#!/usr/bin/env python3
"""Attributed cast types in incomplete generated-getter chains, including graph delivery."""
import unittest

from java_collector_alignment import Collectors, MAP_CAST_CHAIN_SOURCE
from java_graph_fact_alignment import GraphFacts
from java_type_views import type_attributes


class CastBindings(unittest.TestCase):
    setUp = Collectors.setUp
    context = Collectors.context
    analyze = Collectors.analyze
    merged = Collectors.merged
    fixture = Collectors.fixture
    targets = Collectors.targets

    def test_cast_callback_keeps_known_leaf_and_cached_full_delta(self):
        context = self.fixture(MAP_CAST_CHAIN_SOURCE)
        before = self.analyze([context], cache=True)
        targets = dict(self.targets(before, 'read'))
        self.assertIn('Leaf#getName()', targets)
        self.assertIn('java.util.Map#get(java.lang.Object)', targets)
        self.assertEqual(targets['Leaf#getName()']['status'], 'possible')
        self.assertFalse(targets['Leaf#getName()']['evidence_complete'])
        changed = self.fixture(MAP_CAST_CHAIN_SOURCE.replace('m.get("x")', 'm.get("y")'))
        delta = self.analyze([changed], before['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(before['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        for source in (
                MAP_CAST_CHAIN_SOURCE.replace('(Leaf)m.get', '(MissingLeaf)m.get'),
                MAP_CAST_CHAIN_SOURCE.replace('(Leaf)m.get', '(Integer)m.get'),
                MAP_CAST_CHAIN_SOURCE.replace('@lombok.Getter class Api', 'class Api')):
            with self.subTest(source=source):
                failed = self.analyze([self.fixture(source)])
                self.assertNotIn('Leaf#getName()', dict(self.targets(failed, 'read')))

    def test_graph_cast_preserves_eighty_levels_and_rust_validation(self):
        nested = 'String'
        for _ in range(80):
            nested = 'Box<' + nested + '>'
        source = ('@lombok.Getter class Box<T>{T value;} class Use{void read(Object x){Object y=('
                  + nested + ')x;}}')
        context = self.fixture(source)
        context['type_graph'] = True
        result = self.analyze([context])
        GraphFacts.validate_rust(self, [context], result)
        casts = [node for fact in result['facts'] if fact['relation'] == 'java.syntax'
                 for node in type_attributes(fact)['nodes'] if node['kind'] == 'TYPE_CAST']
        self.assertEqual(len(casts), 1)
        model = casts[0]['expression_type_model']
        for _ in range(80):
            self.assertEqual(model['element'], 'Box')
            model = model['arguments'][0]
        self.assertEqual(model['element'], 'java.lang.String')


if __name__ == '__main__':
    unittest.main(verbosity=2)
