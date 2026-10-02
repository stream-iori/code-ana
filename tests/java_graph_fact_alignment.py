#!/usr/bin/env python3
"""Actual SDK8 compiler/model/extractor graph wire and cold delta equality (JVM, not native acceptance)."""
import json
import unittest

import java_model_tests as models


class GraphFacts(models.Models):
    def graph_context(self, source):
        context = self.context('graph', {'App.java': source})
        context['type_graph'] = True
        return context

    def graph(self, fact, key):
        attributes = fact['attributes']
        reference = attributes[key]['$java_type']
        payload = attributes['java_type_graphs'][reference[0]]
        return payload, payload['roots'][reference[1]]

    def test_graph_real_extraction_preserves_80_generic_levels_without_nested_json(self):
        nested = 'Leaf'
        for _ in range(80):
            nested = 'Box<' + nested + '>'
        facts = self.analyze([self.graph_context('class Leaf{} class Box<T>{} class Use {' + nested + ' deep;}')])['facts']
        field = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#deep')
        payload, root = self.graph(field, 'type_model')
        for level in range(80):
            node = payload['nodes'][root]
            self.assertEqual(node['element'], 'Box', level)
            self.assertTrue(node['complete'], level)
            root = node['arguments'][0]
        self.assertEqual(payload['nodes'][root]['element'], 'Leaf')
        self.assertFalse([f for f in facts if f['kind'] == 'diagnostic' and f['attributes'].get('code', '').startswith('TYPE_MODEL_')])
        facet = next(f for f in facts if f['kind'] == 'facet' and f['relation'] == 'java.type' and f['parent'] == field['id'])
        self.assertEqual(facet['attributes']['schema_version'], 3)
        # Physical JSON nesting is bounded independently of semantic graph depth.
        def depth(value):
            if isinstance(value, dict):
                return 1 + max((depth(v) for v in value.values()), default=0)
            if isinstance(value, list):
                return 1 + max((depth(v) for v in value), default=0)
            return 0
        self.assertLess(depth(facts), 32)

    def test_graph_recursive_bounds_error_neighbor_and_binding_owner(self):
        source = 'class Use<T extends Comparable<T>> { T recursive; Missing broken; String read(String x){return x.trim();} }'
        facts = self.analyze([self.graph_context(source)])['facts']
        field = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#recursive')
        payload, root = self.graph(field, 'type_model')
        node = payload['nodes'][root]
        self.assertEqual(node['kind'], 'TYPEVAR')
        self.assertEqual(payload['nodes'][node['upper']]['arguments'][0], root)
        broken = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#broken')
        payload, root = self.graph(broken, 'type_model')
        self.assertFalse(payload['nodes'][root]['complete'])
        site = next(f for f in facts if f['kind'] == 'call_site' and f['name'] == 'x.trim')
        payload, root = self.graph(site, 'selected_type_model')
        self.assertEqual(payload['nodes'][root]['kind'], 'EXECUTABLE')
        binding = next(f for f in facts if f['relation'] == 'java.binding' and f['parent'] == site['id'])
        self.assertEqual(binding['attributes']['schema_version'], 4)
        self.assertEqual(binding['attributes']['evidence_owner'], site['id'])

    def test_graph_cold_delta_deletion_and_visit_order_keep_public_identity(self):
        original = 'class Use<T extends Comparable<T>> { T value; String read(String x){return x.trim();} }'
        baseline = self.analyze([self.graph_context(original)])['facts']
        changed = original.replace('x.trim()', 'x.toLowerCase()')
        delta = self.analyze([self.graph_context(changed)], baseline)
        full = self.analyze([self.graph_context(changed)])['facts']
        self.assertEqual(self.merged(baseline, delta), sorted(full, key=lambda f: f['id']))
        deleted = self.analyze([self.graph_context('class Use {}')], full)
        self.assertEqual(self.merged(full, deleted), sorted(self.analyze([self.graph_context('class Use {}')])['facts'], key=lambda f: f['id']))


if __name__ == '__main__':
    suite = unittest.TestSuite(GraphFacts(name) for name in unittest.defaultTestLoader.getTestCaseNames(GraphFacts) if name.startswith('test_graph_'))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
