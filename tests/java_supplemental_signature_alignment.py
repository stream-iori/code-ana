#!/usr/bin/env python3
"""Generated auxiliary NoCode signatures provide separate, auditable javac type evidence."""
import collections
import json
from java_type_views import type_attributes
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok
import java_lombok_builder_alignment as builders
import java_lombok_super_builder_alignment as super_builders

LIBRARY = 'package p;@lombok.Getter class Api {java.util.List<Leaf> values;} class Leaf {void run(){}} class Other {void run(){}}'
CONSUMER = 'package p;class Use {void read(Api api){api.getValues().forEach(leaf -> leaf.run());}}'
MAP_LIBRARY = 'package p;@lombok.Getter class Api {java.util.Map<String,String> values;}'
MAP_CONSUMER = 'package p;class Use {String read(java.util.List<Api> apis){return apis.stream().map(api -> api.getValues()).filter(map -> map!=null).map(map -> map.get("id")).findFirst().orElse(null);}}'


class SupplementalSignatures(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def fixture(self, library=LIBRARY, consumer=CONSUMER, shape='getter'):
        if shape == 'getter':
            library_context = self.context('lib', lombok.Lombok.files(self, library), lombok='model')
        else:
            helper = super_builders.SuperBuilders if shape == 'super-builder' else builders.Builders
            library_context = helper.fixture(self, library)
        context = self.context('use', {'Use.java': consumer}, lombok='model')
        context['sources'] = [dict(unit, publish=False) for unit in library_context['sources']] + context['sources']
        context['digest'] = models.sha(json.dumps([(s['path'], s['hash']) for s in context['sources']]).encode())
        return context

    def callback(self, facts):
        callbacks = [f for f in facts if f['kind'] == 'declaration'
                     and f['source'] == 'use/Use.java' and f['attributes'].get('element_kind') == 'LAMBDA']
        self.assertEqual(len(callbacks), 1)
        return callbacks[0]

    def references(self, facts, callback):
        by = {f['id']: f for f in facts}
        return [(by[f['target']]['signature'], f) for f in facts
                if f['relation'] == 'type_reference' and f['parent'] == callback['id']]

    def assert_leaf(self, facts, leaf='p.Leaf'):
        callback = self.callback(facts)
        proof = callback['attributes']['supplemental_callback']
        self.assertTrue(proof['original_evidence_retained'])
        self.assertTrue(proof['sam']['complete'])
        self.assertEqual(len(proof['signature_sha256']), 64)
        self.assertEqual(len(proof['auxiliary_inputs_sha256']), 64)
        refs = self.references(facts, callback)
        self.assertCountEqual([(target, f['attributes']['type_context']) for target, f in refs],
                              [('java.util.function.Consumer', 'functional_interface'),
                               (leaf, 'generic_arg'), (leaf, 'parameter_type')])
        for _, fact in refs:
            self.assertEqual(fact['status'], 'complete')
            self.assertTrue(fact['evidence_complete'])
            self.assertEqual(fact['origin'], 'javac_generated_signatures')
            self.assertEqual(fact['attributes']['signature_attribution']['evidence_owner'], callback['id'])
            self.assertEqual(fact['attributes']['signature_attribution']['signature_sha256'], proof['signature_sha256'])
        return proof

    def test_auxiliary_getter_preserves_original_evidence_and_diagnostics(self):
        facts = self.analyze([self.fixture()])['facts']
        self.assert_leaf(facts)
        original = type_attributes(self.callback(facts))
        self.assertFalse(original['sam']['complete'])
        self.assertIn('recovered_callback', original)
        self.assertTrue([f for f in facts if f['kind'] == 'diagnostic' and f['status'] == 'ERROR'])
        by = {f['id']: f for f in facts}
        getter = [f for f in facts if f['kind'] == 'call_target' and by[f['target']]['signature'] == 'p.Api#getValues()']
        self.assertEqual(len(getter), 1)
        self.assertEqual(getter[0]['status'], 'possible')
        self.assertFalse(getter[0]['evidence_complete'])

    def map_callbacks(self, facts):
        by = {f['id']: f for f in facts}
        callbacks = [f for f in facts if f['kind'] == 'declaration' and f['source'] == 'use/Use.java'
                     and f['attributes'].get('element_kind') == 'LAMBDA']
        self.assertEqual(len(callbacks), 3)
        self.assertTrue(all(f['attributes']['sam']['complete'] for f in callbacks))
        self.assertTrue(all(f['attributes']['supplemental_callback']['original_evidence_retained'] for f in callbacks))
        refs = [f for f in facts if f['relation'] == 'type_reference' and f['source'] == 'use/Use.java'
                and by[f['target']]['signature'] == 'java.util.Map']
        self.assertEqual(len(refs), 5)
        self.assertTrue(all(f['status'] == 'complete' and f['origin'] == 'javac_generated_signatures' for f in refs))
        return callbacks

    def test_complete_original_object_callback_is_reproven_without_overwriting_original(self):
        facts = self.analyze([self.fixture(MAP_LIBRARY, MAP_CONSUMER)])['facts']
        callbacks = self.map_callbacks(facts)
        predicate = next(f for f in callbacks if type_attributes(f)['functional_type_model']['element'] == 'java.util.function.Predicate')
        self.assertEqual(type_attributes(predicate)['sam']['instantiated_type']['parameters'][0]['element'], 'java.lang.Object')
        self.assertEqual(type_attributes(predicate)['supplemental_callback']['sam']['instantiated_type']['parameters'][0]['element'], 'java.util.Map')
        self.assertTrue([f for f in facts if f['kind'] == 'diagnostic' and f['status'] == 'ERROR'])

    def test_complete_object_reproof_primary_body_change_and_removal_keep_full_delta(self):
        base = self.analyze([self.fixture(MAP_LIBRARY, MAP_CONSUMER)], cache=True)
        self.map_callbacks(base['facts'])
        changed = self.fixture(MAP_LIBRARY, '// 中文👋\n' + MAP_CONSUMER.replace('map!=null', '!map.isEmpty()'))
        delta = self.analyze([changed], base['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        self.map_callbacks(full['facts'])
        removed = self.fixture(MAP_LIBRARY.replace('@lombok.Getter ', ''), MAP_CONSUMER)
        delta = self.analyze([removed], full['facts'], cache=True)
        cold = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(cold['facts'], key=lambda f: f['id']))
        self.assertFalse([f for f in cold['facts'] if f['origin'] == 'javac_generated_signatures'])

    def test_auxiliary_type_change_and_getter_removal_invalidate_full_delta_and_cache(self):
        base = self.analyze([self.fixture()], cache=True)
        before = self.assert_leaf(base['facts'])
        changed = self.fixture(LIBRARY.replace('List<Leaf>', 'List<Other>'))
        delta = self.analyze([changed], base['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        after = self.assert_leaf(full['facts'], 'p.Other')
        self.assertNotEqual(before['signature_sha256'], after['signature_sha256'])
        self.assertNotEqual(before['auxiliary_inputs_sha256'], after['auxiliary_inputs_sha256'])
        removed = self.fixture(LIBRARY.replace('@lombok.Getter ', ''))
        delta = self.analyze([removed], full['facts'], cache=True)
        cold = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(cold['facts'], key=lambda f: f['id']))
        self.assertNotIn('supplemental_callback', type_attributes(self.callback(cold['facts'])))

    def test_unknown_auxiliary_abi_stays_source_without_fabricated_proof(self):
        facts = self.analyze([self.fixture(LIBRARY.replace('class Api {', 'class Api {Missing unknown;'))])['facts']
        callback = self.callback(facts)
        self.assertNotIn('supplemental_callback', type_attributes(callback))
        self.assertFalse([f for _, f in self.references(facts, callback) if f['origin'] == 'javac_generated_signatures'])
        self.assertTrue([f for f in facts if f['kind'] == 'diagnostic' and f['status'] == 'ERROR'])

    def test_confirmed_source_callback_keeps_original_javac_evidence(self):
        library = LIBRARY.replace('@lombok.Getter ', '').replace('class Api {', 'class Api {java.util.List<Leaf> getValues(){return null;}')
        facts = self.analyze([self.fixture(library)])['facts']
        callback = self.callback(facts)
        self.assertNotIn('supplemental_callback', type_attributes(callback))
        self.assertTrue(callback['attributes']['sam']['complete'])
        self.assertFalse([f for _, f in self.references(facts, callback) if f['origin'] == 'javac_generated_signatures'])

    # Builders' fixture helper binds the same context/analysis methods on this object.
    controls = builders.Builders.controls

    def test_generic_builder_abi_provides_callback_parameter(self):
        library = '@lombok.Builder @lombok.Getter class Api<T extends Leaf> {java.util.List<T> values;} class Leaf {void run(){}}'
        consumer = 'class Use {void read(){Api.<Leaf>builder().values(java.util.Arrays.asList(new Leaf())).build().getValues().forEach(leaf -> leaf.run());}}'
        self.assert_leaf(self.analyze([self.fixture(library, consumer, 'builder')])['facts'], 'Leaf')

    def test_super_builder_recursive_abi_provides_inherited_callback_parameter(self):
        library = '@lombok.experimental.SuperBuilder @lombok.Getter class Parent<T> {java.util.List<T> values;} @lombok.experimental.SuperBuilder class Child extends Parent<Leaf> {int age;} class Leaf {void run(){}}'
        consumer = 'class Use {void read(){Child.builder().values(java.util.Arrays.asList(new Leaf())).age(1).build().getValues().forEach(leaf -> leaf.run());}}'
        self.assert_leaf(self.analyze([self.fixture(library, consumer, 'super-builder')])['facts'], 'Leaf')


if __name__ == '__main__':
    unittest.main(verbosity=2)
