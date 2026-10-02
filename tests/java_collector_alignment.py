#!/usr/bin/env python3
"""Collector capture, incomplete selections, and known incompatible inputs."""
from java_type_views import type_attributes
import unittest
import collections
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
LAMBDA_MAP_SOURCE = 'import java.util.*;import java.util.stream.*;import java.util.function.*;@lombok.Getter class Api {String id;} class Use {Map<String,Api> read(List<Api> values){return values.stream().filter(c ->c.getId()!=null).collect(Collectors.toMap(c -> c.getId(),c -> c,(a,b)->a));}}'

ENTRY_CHAIN_SOURCE = 'import java.util.*;import java.util.stream.*;import java.util.function.*;@lombok.Getter class Api {String id;} class Use {Map.Entry<String,Api> read(List<Api> values){return values.stream().filter(c ->c.getId()!=null).collect(Collectors.toMap(c -> c.getId(),c -> c,(a,b)->a)).entrySet().stream().filter(e -> e.getKey().startsWith("x")).max(Comparator.comparingInt(c -> c.getKey().length())).orElse(null);}}'

STABLE_LAMBDA_CONSTRUCTOR_SOURCE = 'import java.util.*;import java.util.stream.*;class Other {@lombok.Getter String inputContentValue;} class InputContent {String getInputContentValue(){return "";}} class FieldSource {static FieldSource safeValueOf(String value){return null;}} @lombok.Data @lombok.AllArgsConstructor class FieldPath {FieldSource fieldSource;} class Use {List<FieldPath> read(List<InputContent> values){return values.stream().map(item -> {FieldSource value=Optional.of(item).map(InputContent::getInputContentValue).map(FieldSource::safeValueOf).orElse(null);return new FieldPath(value);}).collect(Collectors.toList());}}'

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

    def assert_selected_lambda_factory(self, result, source=LAMBDA_MAP_SOURCE):
        facts = result['facts']; by = {f['id']: f for f in facts}
        targets = [(by[f['target']]['signature'], f) for f in facts if f['kind'] == 'call_target']
        self.assertEqual(collections.Counter(signature for signature, _ in targets), collections.Counter({
            'java.util.Collection#stream()': 1,
            'java.util.stream.Stream#filter(java.util.function.Predicate)': 1,
            'java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function,java.util.function.BinaryOperator)': 1,
            'Api#getId()': 1}))
        for signature, target in targets:
            self.assertEqual(target['status'], 'resolved' if signature == 'java.util.Collection#stream()' else 'possible')
        getter = next(target for signature, target in targets if signature == 'Api#getId()')
        site = by[getter['parent']]
        self.assertEqual(site['span']['start'], source.index('c.getId()'))
        self.assertFalse(getter['evidence_complete'])

    def test_selected_generic_lambda_factory_and_delta(self):
        initial = self.analyze([self.fixture(LAMBDA_MAP_SOURCE)], cache=True)
        self.assert_selected_lambda_factory(initial)
        changed = LAMBDA_MAP_SOURCE.replace('String id;', 'Integer id;').replace('Map<String,Api>', 'Map<Integer,Api>')
        for source in (changed, changed.replace('@lombok.Getter ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], initial['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            if '@lombok.Getter' in source:
                self.assert_selected_lambda_factory(full, source)
            initial = full

    def assert_transitive_entry_chain(self, result):
        facts = result['facts']; by = {f['id']: f for f in facts}
        pairs = [(by[f['target']]['signature'], f['status']) for f in facts if f['kind'] == 'call_target']
        self.assertEqual(collections.Counter(pairs), collections.Counter({
            ('java.util.Collection#stream()', 'resolved'): 1,
            ('java.util.Collection#stream()', 'possible'): 1,
            ('java.util.stream.Stream#filter(java.util.function.Predicate)', 'possible'): 1,
            ('java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function,java.util.function.BinaryOperator)', 'possible'): 1,
            ('Api#getId()', 'possible'): 1,
            ('java.util.Map$Entry#getKey()', 'resolved'): 2,
            ('java.util.Comparator#comparingInt(java.util.function.ToIntFunction)', 'resolved'): 1,
            ('java.util.Optional#orElse(java.lang.Object)', 'possible'): 1}))

    def test_transitive_receiver_factory_and_delta(self):
        base = self.analyze([self.fixture(ENTRY_CHAIN_SOURCE)], cache=True)
        self.assert_transitive_entry_chain(base)
        changed = ENTRY_CHAIN_SOURCE.replace('String id;', 'Integer id;').replace(
            'Map.Entry<String,Api>', 'Map.Entry<Integer,Api>')
        for source in (changed, changed.replace('@lombok.Getter ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], base['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            if '@lombok.Getter' in source:
                self.assert_transitive_entry_chain(full)
            else:
                by = {f['id']: f for f in full['facts']}
                self.assertNotIn('Api#getId()', [by[f['target']]['signature']
                    for f in full['facts'] if f['kind'] == 'call_target'])
            base = full

    def assert_stable_lambda_constructor_confidence(self, result, parameter='FieldSource'):
        facts = result['facts']; by = {f['id']: f for f in facts}
        pairs = [(by[f['target']]['signature'], f['status']) for f in facts if f['kind'] == 'call_target']
        self.assertEqual(collections.Counter(pairs), collections.Counter({
            ('java.util.Collection#stream()', 'resolved'): 1,
            ('java.util.stream.Stream#map(java.util.function.Function)', 'resolved'): 1,
            ('java.util.Optional#of(java.lang.Object)', 'resolved'): 1,
            ('java.util.Optional#map(java.util.function.Function)', 'resolved'): 2,
            ('java.util.Optional#orElse(java.lang.Object)', 'resolved'): 1,
            ('InputContent#getInputContentValue()', 'resolved'): 1,
            ('FieldSource#safeValueOf(java.lang.String)', 'resolved'): 1,
            ('java.util.stream.Collectors#toList()', 'resolved'): 1,
            ('FieldPath#<init>(' + parameter + ')', 'possible'): 1}))

    def test_stable_constructor_preserves_jdk_and_member_confidence(self):
        self.assert_stable_lambda_constructor_confidence(
            self.analyze([self.fixture(STABLE_LAMBDA_CONSTRUCTOR_SOURCE)]))

    def test_constructor_signature_and_removal_preserve_full_delta(self):
        base = self.analyze([self.fixture(STABLE_LAMBDA_CONSTRUCTOR_SOURCE)], cache=True)
        changed = STABLE_LAMBDA_CONSTRUCTOR_SOURCE.replace('FieldSource fieldSource;', 'Object fieldSource;')
        for source in (changed, changed.replace('@lombok.AllArgsConstructor ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], base['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            if '@lombok.AllArgsConstructor' in source:
                self.assert_stable_lambda_constructor_confidence(full, 'java.lang.Object')
            else:
                by = {f['id']: f for f in full['facts']}
                self.assertFalse([f for f in full['facts'] if f['kind'] == 'call_target'
                    and by[f['target']]['signature'] == 'FieldPath#<init>(java.lang.Object)'])
            base = full

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
