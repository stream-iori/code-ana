#!/usr/bin/env python3
"""Collector capture, incomplete selections, and known incompatible inputs."""
from java_type_views import type_attributes
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok


SOURCE = '''class Rule {@lombok.Getter String key;static Rule build(String text){return null;}}
class Other {} class Use {
static java.util.Map<String,Rule> build(java.util.List<String> texts){return texts.stream().map(Rule::build).collect(java.util.stream.Collectors.toMap(Rule::getKey,item -> item,(v1,v2)->v2));}
static java.util.Map<String,Rule> incompatible(java.util.List<String> texts){return texts.stream().map(Rule::build).collect(java.util.stream.Collectors.toMap(Rule::getKey,item ->new Other(),(v1,v2)->v2));}
static void wrongExplicit(java.util.List<Rule> rules){rules.stream().collect(java.util.stream.Collectors.<Rule,Integer,Rule>toMap(Rule::getKey,item ->item,(v1,v2)->v2));}}
'''


TARGET_SOURCE = '''import java.util.*;import java.util.stream.*;
@lombok.Getter class Api {String id;} class Validator {}
class Cache {static List<Validator> getValidators(String id){return null;}}
class Use {Map<String,Object> read(List<Api> values){return values.stream().collect(Collectors.toMap(Api::getId,c -> Cache.getValidators(c.getId())));}}
'''

IDENTITY_SOURCE = '''import java.util.*;import java.util.stream.*;import java.util.function.*;
@lombok.Getter class Api {String id;}
class Use {Map<String,Api> read(List<Api> values){return values.stream().collect(Collectors.toMap(Api::getId,Function.identity()));}}
'''
CONCURRENT_IDENTITY_SOURCE = IDENTITY_SOURCE.replace(
    'toMap(Api::getId,Function.identity())', 'toConcurrentMap(Api::getId,Function.identity(),(a,b)->b)')


class Collectors(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def fixture(self, source=SOURCE):
        return self.context('app', lombok.Lombok.files(self, source), lombok='model')

    def targets(self, result, method):
        facts = result['facts']
        by = {f['id']: f for f in facts}
        return [(by[f['target']]['signature'], f) for f in facts if f['kind'] == 'call_target'
                and by[by[f['parent']]['parent']]['signature'].startswith('Use#' + method + '(')]

    def test_captured_collector_recovers_whole_chain(self):
        result = self.analyze([self.fixture()])
        targets = dict(self.targets(result, 'build'))
        for signature in ('java.util.stream.Stream#collect(java.util.stream.Collector)',
                          'java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function,java.util.function.BinaryOperator)',
                          'Rule#getKey()'):
            self.assertIn(signature, targets)
            self.assertEqual(targets[signature]['status'], 'possible')
            self.assertFalse(targets[signature]['evidence_complete'])
        collect = targets['java.util.stream.Stream#collect(java.util.stream.Collector)']
        returned = type_attributes(collect)['recovered_return_type_model']
        self.assertEqual(returned['element'], 'java.util.Map')
        self.assertEqual([v['element'] for v in returned['arguments']], ['java.lang.String', 'Rule'])
        self.assertFalse([f for f in result['facts'] if f['attributes'].get('recovery_failure') == 'recovery_budget'])

    def test_known_bad_result_is_blocked_but_unique_outer_error_selection_is_retained(self):
        result = self.analyze([self.fixture()])
        signature = 'java.util.stream.Stream#collect(java.util.stream.Collector)'
        self.assertNotIn(signature, dict(self.targets(result, 'incompatible')))
        wrong = dict(self.targets(result, 'wrongExplicit'))
        self.assertEqual(wrong[signature]['status'], 'possible')
        self.assertFalse(wrong[signature]['evidence_complete'])
        self.assertFalse([s for s in wrong if s.startswith('java.util.stream.Collectors#toMap') or s == 'Rule#getKey()'])
        self.assertTrue([f for f in result['facts'] if f['kind'] == 'diagnostic'
                         and 'invalid.mref' in f['name']])

    def test_unrelated_generated_member_name_does_not_change_confirmed_source_reference(self):
        source = '''class Generated {@lombok.Getter String key;}
class Explicit {String getKey(){return "key";}}
class Use {static String read(Explicit value){return java.util.Optional.ofNullable(value).map(Explicit::getKey).orElse("empty");}}'''
        result = self.analyze([self.fixture(source)])
        targets = dict(self.targets(result, 'read'))
        for signature in ('Explicit#getKey()', 'java.util.Optional#map(java.util.function.Function)',
                          'java.util.Optional#orElse(java.lang.Object)'):
            self.assertEqual(targets[signature]['status'], 'resolved')

    def test_key_type_change_and_getter_removal_preserve_full_delta_equivalence(self):
        initial = self.analyze([self.fixture()], cache=True)
        changed = self.fixture(SOURCE.replace('String key;', 'Integer key;').replace('Map<String,Rule>', 'Map<Integer,Rule>'))
        delta = self.analyze([changed], initial['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        collect = dict(self.targets(full, 'build'))['java.util.stream.Stream#collect(java.util.stream.Collector)']
        self.assertEqual(type_attributes(collect)['recovered_return_type_model']['arguments'][0]['element'], 'java.lang.Integer')
        removed = self.fixture(SOURCE.replace('@lombok.Getter ', ''))
        delta = self.analyze([removed], full['facts'], cache=True)
        full_removed = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(full_removed['facts'], key=lambda f: f['id']))
        self.assertFalse([s for s, _ in self.targets(full_removed, 'build')
                          if s == 'Rule#getKey()' or s.startswith('java.util.stream.Collectors#toMap')])


    def test_nested_factory_uses_invariant_map_target_and_captures_accumulator(self):
        result = self.analyze([self.fixture(TARGET_SOURCE)])
        targets = dict(self.targets(result, 'read'))
        for signature in ('java.util.stream.Stream#collect(java.util.stream.Collector)',
                          'java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)',
                          'Api#getId()', 'Cache#getValidators(java.lang.String)'):
            self.assertEqual(targets[signature]['status'], 'possible')
            self.assertFalse(targets[signature]['evidence_complete'])
        returned = type_attributes(targets['java.util.stream.Stream#collect(java.util.stream.Collector)'])['recovered_return_type_model']
        self.assertEqual([v['element'] for v in returned['arguments']], ['java.lang.String', 'java.lang.Object'])

    def test_nested_factory_rejects_incompatible_invariant_key(self):
        source = TARGET_SOURCE.replace('Map<String,Object> read', 'Map<Integer,Object> read')
        targets = dict(self.targets(self.analyze([self.fixture(source)]), 'read'))
        self.assertNotIn('java.util.stream.Stream#collect(java.util.stream.Collector)', targets)

    def test_nested_factory_rejects_wrong_stream_element(self):
        source = TARGET_SOURCE.replace('List<Api> values', 'List<Validator> values')
        targets = dict(self.targets(self.analyze([self.fixture(source)]), 'read'))
        self.assertNotIn('java.util.stream.Stream#collect(java.util.stream.Collector)', targets)

    def test_nested_factory_target_and_getter_changes_keep_full_delta_equivalence(self):
        initial = self.analyze([self.fixture(TARGET_SOURCE)], cache=True)
        changed = TARGET_SOURCE.replace('String id;', 'Integer id;').replace('Map<String,Object>', 'Map<Integer,Object>').replace('getValidators(String id)', 'getValidators(Integer id)')
        for source in (changed, changed.replace('@lombok.Getter ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], initial['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            targets = dict(self.targets(full, 'read'))
            if '@lombok.Getter' in source:
                returned = type_attributes(targets['java.util.stream.Stream#collect(java.util.stream.Collector)'])['recovered_return_type_model']
                self.assertEqual(returned['arguments'][0]['element'], 'java.lang.Integer')
            else:
                self.assertNotIn('Api#getId()', targets)
                self.assertNotIn('java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)', targets)
            initial = full

    def test_identity_factory_infers_outer_wildcard_constraint(self):
        targets = dict(self.targets(self.analyze([self.fixture(IDENTITY_SOURCE)]), 'read'))
        self.assertEqual(targets['Api#getId()']['status'], 'possible')
        self.assertEqual(targets['java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)']['status'], 'possible')
        collect = targets['java.util.stream.Stream#collect(java.util.stream.Collector)']
        self.assertEqual(collect['status'], 'possible')
        self.assertEqual([t['element'] for t in type_attributes(collect)['recovered_return_type_model']['arguments']], ['java.lang.String', 'Api'])
        self.assertEqual(targets['java.util.function.Function#identity()']['status'], 'resolved')

    def test_identity_factory_keeps_valid_nested_call_and_rejects_incompatible_constraints(self):
        targets = dict(self.targets(self.analyze([self.fixture(
            IDENTITY_SOURCE.replace('Map<String,Api>', 'Map<Integer,Api>'))]), 'read'))
        self.assertNotIn('java.util.stream.Stream#collect(java.util.stream.Collector)', targets)
        self.assertIn('java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)', targets)
        targets = dict(self.targets(self.analyze([self.fixture(
            IDENTITY_SOURCE.replace('Function.identity()', 'Function.<String>identity()'))]), 'read'))
        self.assertNotIn('java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)', targets)
        self.assertNotIn('Api#getId()', targets)
        self.assertEqual(targets['java.util.function.Function#identity()']['status'], 'resolved')

    def test_identity_factory_type_and_getter_removal_keep_full_delta_equivalence(self):
        base = self.analyze([self.fixture(IDENTITY_SOURCE)], cache=True)
        changed = IDENTITY_SOURCE.replace('String id;', 'Integer id;').replace('Map<String,Api>', 'Map<Integer,Api>')
        for source in (changed, changed.replace('@lombok.Getter ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], base['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            targets = dict(self.targets(full, 'read'))
            if '@lombok.Getter' in source:
                self.assertIn('java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)', targets)
            else:
                self.assertNotIn('Api#getId()', targets)
            base = full

    def test_concurrent_factory_infers_return_subtype_before_map_target(self):
        targets = dict(self.targets(self.analyze([self.fixture(CONCURRENT_IDENTITY_SOURCE)]), 'read'))
        self.assertEqual(len(targets), 5)
        collect = targets['java.util.stream.Stream#collect(java.util.stream.Collector)']
        returned = type_attributes(collect)['recovered_return_type_model']
        self.assertEqual(returned['element'], 'java.util.concurrent.ConcurrentMap')
        self.assertEqual([v['element'] for v in returned['arguments']], ['java.lang.String', 'Api'])
        self.assertEqual(collect['status'], 'possible')

    def test_concurrent_factory_rejects_wrong_target_key_and_explicit_value(self):
        targets = dict(self.targets(self.analyze([self.fixture(
            CONCURRENT_IDENTITY_SOURCE.replace('Map<String,Api>', 'Map<Integer,Api>'))]), 'read'))
        self.assertNotIn('java.util.stream.Stream#collect(java.util.stream.Collector)', targets)
        targets = dict(self.targets(self.analyze([self.fixture(
            CONCURRENT_IDENTITY_SOURCE.replace('Function.identity()', 'Function.<String>identity()'))]), 'read'))
        self.assertNotIn('java.util.stream.Collectors#toConcurrentMap(java.util.function.Function,java.util.function.Function,java.util.function.BinaryOperator)', targets)
        self.assertNotIn('Api#getId()', targets)

    def test_concurrent_factory_key_change_and_getter_removal_match_cached_full_delta(self):
        base = self.analyze([self.fixture(CONCURRENT_IDENTITY_SOURCE)], cache=True)
        changed = CONCURRENT_IDENTITY_SOURCE.replace('String id;', 'Integer id;').replace('Map<String,Api>', 'Map<Integer,Api>')
        for source in (changed, changed.replace('@lombok.Getter ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], base['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            targets = dict(self.targets(full, 'read'))
            if '@lombok.Getter' in source:
                returned = type_attributes(targets['java.util.stream.Stream#collect(java.util.stream.Collector)'])['recovered_return_type_model']
                self.assertEqual(returned['arguments'][0]['element'], 'java.lang.Integer')
            else:
                self.assertNotIn('Api#getId()', targets)
            base = full


if __name__ == '__main__':
    unittest.main(verbosity=2)
