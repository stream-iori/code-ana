#!/usr/bin/env python3
"""Generic receivers and contextual method references preserve compiler evidence."""
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok
import java_binding_alignment as bindings


class GenericReferences(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged
    recovered = bindings.Bindings.recovered

    def fixture(self, body, extra=''):
        source = '''class Holder {@lombok.Getter Factory factory;}
class Factory {<T,R> R convert(T input,java.util.function.Function<T,R> fn){return null;}}
class Api {void run(){}} class Other {void run(){}}
class Box<T> {@lombok.Getter T value; EXTRA} class Child extends Box<Api> {}
class Use {void read(Holder h,Box<Api> box,Child child,java.util.Map.Entry<String,Api> entry){BODY}}
'''.replace('EXTRA', extra).replace('BODY', body)
        return self.context('app', lombok.Lombok.files(self, source), lombok='model'), source

    def signatures(self, result):
        return [signature for name, signature, fact in self.recovered(result['facts'])]

    def test_source_inherited_and_binary_unbound_generic_outputs(self):
        context, _ = self.fixture('''h.getFactory().convert(box,Box::getValue).run();
h.getFactory().convert(child,Child::getValue).run();
h.getFactory().convert(entry,java.util.Map.Entry::getValue).run();''')
        result = self.analyze([context])
        self.assertEqual(self.signatures(result).count('Api#run()'), 3)
        self.assertEqual(self.signatures(result).count('Factory#convert(java.lang.Object,java.util.function.Function)'), 3)
        facts = result['facts']
        reference = next(f for f in facts if f['kind'] == 'call_site' and f['name'] == 'java.util.Map.Entry::getValue')
        self.assertEqual(reference['status'], 'unresolved')
        self.assertEqual(reference['attributes']['binding_failure'], 'inapplicable')
        self.assertIn('compiler.err.invalid.mref', reference['attributes']['diagnostic_codes'])
        self.assertEqual(reference['attributes']['javac_selected_declaration'], 'java.util.Map$Entry#getValue()')
        recovered = next(f for f in facts if f['kind'] == 'call_target' and f['parent'] == reference['id'])
        self.assertEqual(recovered['status'], 'possible')
        self.assertFalse(recovered['evidence_complete'])
        self.assertEqual(recovered['attributes']['recovered_return_type_model']['element'], 'Api')

    def test_wrong_receiver_and_explicit_output_remain_unresolved(self):
        for body in ('h.getFactory().<Box<Api>,String>convert(box,Box::getValue);',
                     'h.getFactory().convert(new Object(),Box::getValue);'):
            with self.subTest(body=body):
                context, _ = self.fixture(body)
                self.assertNotIn('Factory#convert(java.lang.Object,java.util.function.Function)',
                                 self.signatures(self.analyze([context])))

    def test_static_and_instance_ambiguity_with_concrete_generic_receiver(self):
        context, _ = self.fixture('h.getFactory().convert(box,Box::getValue).run();',
                                  'static Api getValue(Box<Api> box){return null;}')
        signatures = self.signatures(self.analyze([context]))
        self.assertNotIn('Factory#convert(java.lang.Object,java.util.function.Function)', signatures)
        self.assertNotIn('Api#run()', signatures)

    def test_generic_input_change_and_getter_removal_invalidate_cache_delta(self):
        context, source = self.fixture('h.getFactory().convert(box,Box::getValue).run();')
        initial = self.analyze([context], cache=True)
        self.assertIn('Api#run()', self.signatures(initial))
        changed_source = source.replace('Box<Api> box', 'Box<Other> box')
        changed = self.context('app', lombok.Lombok.files(self, changed_source), lombok='model')
        delta = self.analyze([changed], initial['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        self.assertIn('Other#run()', self.signatures(full))
        self.assertNotIn('Api#run()', self.signatures(full))
        removed = self.context('app', lombok.Lombok.files(self, changed_source.replace('@lombok.Getter T value;', 'T value;')), lombok='model')
        delta = self.analyze([removed], full['facts'], cache=True)
        full_removed = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(full_removed['facts'], key=lambda f: f['id']))
        self.assertNotIn('Other#run()', self.signatures(full_removed))


    def test_static_source_inherited_and_binary_unbound_references(self):
        _, source = self.fixture('h.getFactory().convert(box,Box::getValue).run();h.getFactory().convert(child,Child::getValue).run();h.getFactory().convert(entry,java.util.Map.Entry::getValue).run();')
        source = source.replace('void read(Holder', 'static void read(Holder')
        context = self.context('app', lombok.Lombok.files(self, source), lombok='model')
        self.assertEqual(self.signatures(self.analyze([context])).count('Api#run()'), 3)

    def test_static_this_reference_and_direct_instance_call_remain_rejected(self):
        source = 'class Context {@lombok.Getter String name; static void bad(Context c){Context.getName();java.util.function.Supplier<String> s=this::getName;java.util.Optional.ofNullable(c).<Number>map(Context::getName);}}'
        context = self.context('app', lombok.Lombok.files(self, source), lombok='model')
        signatures = self.signatures(self.analyze([context]))
        self.assertNotIn('Context#getName()', signatures)
        self.assertNotIn('java.util.Optional#map(java.util.function.Function)', signatures)

    def test_static_optional_reference_chain_and_getter_removal_delta(self):
        source = 'class Context {@lombok.Getter String name;} class Use {static String read(Context c){return java.util.Optional.ofNullable(c).map(Context::getName).orElse(null);}}'
        context = self.context('app', lombok.Lombok.files(self, source), lombok='model')
        initial = self.analyze([context], cache=True)
        for signature in ('Context#getName()', 'java.util.Optional#map(java.util.function.Function)', 'java.util.Optional#orElse(java.lang.Object)'):
            self.assertIn(signature, self.signatures(initial))
        removed = self.context('app', lombok.Lombok.files(self, source.replace('@lombok.Getter ', '')), lombok='model')
        delta = self.analyze([removed], initial['facts'], cache=True)
        full = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        self.assertNotIn('Context#getName()', self.signatures(full))


if __name__ == '__main__':
    unittest.main(verbosity=2)
