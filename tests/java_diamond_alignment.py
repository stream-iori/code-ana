#!/usr/bin/env python3
"""Candidate-local diamond typing must not bypass bounds or access checks."""
from java_type_views import type_attributes
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok


SOURCE = '''class Item {} @lombok.Setter class Context {java.util.List<Item> items;}
class Bounded<T extends Number> extends java.util.ArrayList<T> {public Bounded(){}}
class Hidden<T> extends java.util.ArrayList<T> {private Hidden(){}}
class Use {static void read(Context c){c.setItems(new java.util.ArrayList<>());
c.setItems(new java.util.ArrayList<Item>(3));c.setItems(new java.util.ArrayList<String>());
c.setItems(new Bounded<>());c.setItems(new Hidden<>());}}
'''


class Diamonds(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def fixture(self, source=SOURCE):
        return self.context('app', lombok.Lombok.files(self, source), lombok='model')

    def setters(self, result):
        facts = result['facts']
        by = {f['id']: f for f in facts}
        return [(by[f['parent']], f) for f in facts if f['kind'] == 'call_target'
                and by[f['target']]['signature'] == 'Context#setItems(java.util.List)']

    def test_diamond_and_explicit_compatible_types_without_access_or_bound_guessing(self):
        result = self.analyze([self.fixture()])
        targets = self.setters(result)
        self.assertEqual(len(targets), 2, [(s['span'], f) for s, f in targets])
        for site, target in targets:
            self.assertIn(SOURCE[site['span']['start']:site['span']['end']],
                          ('c.setItems(new java.util.ArrayList<>())', 'c.setItems(new java.util.ArrayList<Item>(3))'))
            self.assertEqual(target['status'], 'possible')
            self.assertFalse(target['evidence_complete'])
            self.assertEqual(type_attributes(target)['recovered_parameter_type_models'][0]['arguments'][0]['element'], 'Item')
        self.assertTrue([f for f in result['facts'] if f['kind'] == 'diagnostic' and 'access' in f['name']])

    def test_incomparable_overloads_cannot_share_a_diamond_probe(self):
        source = '''class Holder {@lombok.Getter Api api;} class Item {}
class Api {void take(java.util.List<Item> value){} void take(java.util.RandomAccess value){}}
class Use {void read(Holder h){h.getApi().take(new java.util.ArrayList<>());}}'''
        result = self.analyze([self.fixture(source)])
        by = {f['id']: f for f in result['facts']}
        self.assertFalse([f for f in result['facts'] if f['kind'] == 'call_target'
                          and by[f['target']]['signature'].startswith('Api#take(')])

    def test_parameter_change_and_setter_removal_preserve_full_delta_equivalence(self):
        initial = self.analyze([self.fixture()], cache=True)
        changed = self.fixture(SOURCE.replace('List<Item> items;', 'List<String> items;'))
        delta = self.analyze([changed], initial['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        self.assertEqual(len(self.setters(full)), 2)
        self.assertTrue(all(type_attributes(t)['recovered_parameter_type_models'][0]['arguments'][0]['element'] == 'java.lang.String'
                            for _, t in self.setters(full)))
        removed = self.fixture(SOURCE.replace('@lombok.Setter ', ''))
        delta = self.analyze([removed], full['facts'], cache=True)
        full_removed = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(full_removed['facts'], key=lambda f: f['id']))
        self.assertFalse(self.setters(full_removed))


if __name__ == '__main__':
    unittest.main(verbosity=2)
