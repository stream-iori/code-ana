#!/usr/bin/env python3
"""Attributed cast types in incomplete generated-getter chains, including graph delivery."""
import unittest

import java_collector_alignment as collectors
import java_graph_fact_alignment as graph

from java_type_views import type_attributes

MAP_CAST_CHAIN_SOURCE = collectors.MAP_CAST_CHAIN_SOURCE


class CastBindings(unittest.TestCase):
    setUp = collectors.Collectors.setUp
    context = collectors.Collectors.context
    analyze = collectors.Collectors.analyze
    merged = collectors.Collectors.merged
    fixture = collectors.Collectors.fixture
    targets = collectors.Collectors.targets

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

    def test_instanceof_cast_chain_and_arithmetic_concatenation(self):
        for source, count, target in (
                (collectors.INSTANCEOF_CAST_CHAIN_SOURCE, 8, 'Leaf#getName()'),
                (collectors.ARITHMETIC_CONCAT_SOURCE, 3, 'Key#getKey(java.lang.String)')):
            with self.subTest(target=target):
                result = self.analyze([self.fixture(source)], cache=True)
                targets = self.targets(result, 'read')
                self.assertEqual(len(targets), count)
                selected = dict(targets)[target]
                self.assertEqual(selected['status'], 'possible')
                self.assertFalse(selected['evidence_complete'])
                changed = self.fixture(source.replace('String name;', 'String name; int marker;')
                                       .replace('String id;', 'String id; int marker;'))
                delta = self.analyze([changed], result['facts'], cache=True)
                full = self.analyze([changed], cache=True)
                self.assertEqual(self.merged(result['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))

    def test_original_selected_with_incomplete_model_input_is_possible_and_delta_stable(self):
        source = collectors.ARITHMETIC_MISSING_SOURCE
        result = self.analyze([self.fixture(source)], cache=True)
        target = dict(self.targets(result, 'read'))['Key#getKey(java.lang.String)']
        self.assertEqual(target['status'], 'possible')
        self.assertTrue(target['declaration_resolved'])
        self.assertFalse(target['evidence_complete'])
        self.assertTrue(target['attributes']['modeled_input'])
        site = next(f for f in result['facts'] if f['id'] == target['parent'])
        self.assertEqual(site['attributes']['javac_selected_declaration'], 'Key#getKey(java.lang.String)')
        original = type_attributes(site)['argument_type_models'][0]
        self.assertEqual(original['kind'], 'ERROR')
        missing = next(f for f in result['facts'] if f['kind'] == 'call_site' and f['name'] == 'missing')
        self.assertEqual(missing['status'], 'unresolved')
        self.assertFalse([f for f in result['facts'] if f['kind'] == 'call_target' and f['parent'] == missing['id']])
        changed = self.fixture(source.replace('String id;', 'String id; int marker;'))
        delta = self.analyze([changed], result['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(result['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        invalid = collectors.ARITHMETIC_CONCAT_SOURCE.replace('String id;', 'Integer id;').replace(
            'h.getId()+"_"+System.currentTimeMillis()/2', 'h.getId()')
        failed = self.analyze([self.fixture(invalid)])
        self.assertNotIn('Key#getKey(java.lang.String)', dict(self.targets(failed, 'read')))

    def test_graph_cast_preserves_eighty_levels_and_rust_validation(self):
        nested = 'String'
        for _ in range(80):
            nested = 'Box<' + nested + '>'
        source = ('@lombok.Getter class Box<T>{T value;} class Use{void read(Object x){Object y=('
                  + nested + ')x;}}')
        context = self.fixture(source)
        context['type_graph'] = True
        result = self.analyze([context])
        graph.GraphFacts.validate_rust(self, [context], result)
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
