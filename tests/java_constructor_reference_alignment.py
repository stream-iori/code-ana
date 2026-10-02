#!/usr/bin/env python3
"""Contextual constructor references retain checked generics and recovery evidence."""
from java_type_views import type_attributes
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok
import java_binding_alignment as bindings


class ConstructorReferences(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged
    recovered = bindings.Bindings.recovered

    def fixture(self, qualifier='java.util.HashMap', extra='', value='String'):
        source = 'class Context {@lombok.Getter java.util.Map<String,VALUE> headers;} class Use {static java.util.Map<String,VALUE> read(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElseGet(QUALIFIER::new);}}'
        source = source.replace('VALUE', value).replace('QUALIFIER', qualifier) + extra
        return self.context('app', lombok.Lombok.files(self, source), lombok='model')

    def signatures(self, result):
        return [signature for name, signature, fact in self.recovered(result['facts'])]

    def constructor(self, result, value='java.lang.String'):
        recovered = [fact for name, signature, fact in self.recovered(result['facts'])
                     if signature == 'java.util.HashMap#<init>()']
        self.assertEqual(len(recovered), 1)
        fact = recovered[0]
        result_type = type_attributes(fact)['recovered_return_type_model']
        self.assertEqual(result_type['element'], 'java.util.HashMap')
        self.assertEqual([arg['element'] for arg in result_type['arguments']], ['java.lang.String', value])
        self.assertNotIn('signature', result_type)
        self.assertEqual(fact['status'], 'possible')
        self.assertFalse(fact['evidence_complete'])
        return fact

    def test_generic_binary_constructor_uses_checked_sam_output(self):
        result = self.analyze([self.fixture()])
        self.constructor(result)
        self.assertIn('java.util.Optional#orElseGet(java.util.function.Supplier)', self.signatures(result))

    def test_replaced_lombok_constructor_is_an_input_to_static_factory(self):
        source = '@lombok.NoArgsConstructor @lombok.AllArgsConstructor class Bean {String value;} class Store {static ThreadLocal<Bean> local=ThreadLocal.withInitial(Bean::new);}'
        result = self.analyze([self.context('app', lombok.Lombok.files(self, source), lombok='model')])
        self.assertIn('Bean#<init>()', self.signatures(result))
        self.assertIn('java.lang.ThreadLocal#withInitial(java.util.function.Supplier)', self.signatures(result))
        facts = result['facts']
        site = next(f for f in facts if f['kind']=='call_site' and f['attributes'].get('lookup_name')=='withInitial')
        self.assertEqual(site['attributes']['javac_selected_declaration'], 'java.lang.ThreadLocal#withInitial(java.util.function.Supplier)')

    def test_incompatible_generic_bounds_and_constructor_result_are_rejected(self):
        for qualifier, extra in (('Bounded', 'class Bounded<K extends Number,V> extends java.util.HashMap<K,V>{public Bounded(){}}'),
                                 ('java.util.ArrayList', '')):
            with self.subTest(qualifier=qualifier):
                result = self.analyze([self.fixture(qualifier, extra)])
                self.assertNotIn('java.util.Optional#orElseGet(java.util.function.Supplier)', self.signatures(result))
                self.assertNotIn(qualifier+'#<init>()', self.signatures(result))

    def test_constructor_generic_inputs_change_and_getter_removal_delta(self):
        initial = self.analyze([self.fixture()], cache=True)
        self.constructor(initial)
        changed = self.fixture(value='Integer')
        delta = self.analyze([changed], initial['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f:f['id']))
        self.constructor(full, 'java.lang.Integer')
        source = 'class Context {java.util.Map<String,Integer> headers;} class Use {static java.util.Map<String,Integer> read(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElseGet(java.util.HashMap::new);}}'
        removed = self.context('app', lombok.Lombok.files(self, source), lombok='model')
        delta = self.analyze([removed], full['facts'], cache=True)
        full_removed = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(full_removed['facts'], key=lambda f:f['id']))
        self.assertNotIn('java.util.Optional#orElseGet(java.util.function.Supplier)', self.signatures(full_removed))
        self.assertNotIn('java.util.HashMap#<init>()', self.signatures(full_removed))


if __name__ == '__main__':
    unittest.main(verbosity=2)
