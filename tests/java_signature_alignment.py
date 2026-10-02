#!/usr/bin/env python3
"""Java 8 javac consumes complex NoCode ABIs with full/delta fact equivalence."""
import unittest
import java_model_tests as model_tests
from java_type_views import type_attributes


class JavaSignatures(unittest.TestCase):
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze
    merged = model_tests.Models.merged

    def roundtrip(self, files):
        first = self.analyze([self.context('app', files)], cache=True)['facts']
        files = dict(files)
        files['Use.java'] = files['Use.java'].replace('before', 'after')
        changed = self.context('app', files)
        delta = self.analyze([changed], first, cache=True)
        full = self.analyze([changed])
        self.assertEqual(delta['reduced_contexts'], 1)
        self.assertEqual(delta['compiler_sources'], 1)
        self.assertEqual(self.merged(first, delta), sorted(full['facts'], key=lambda f: f['id']))

    def test_generic_substitution_and_wildcards(self):
        self.roundtrip({'Api.java': '''import java.util.*;
          class Api<T extends Number & Comparable<T>> {
            T value; List<? extends T> list;
            T get(){ return value; } <U extends T> U echo(U input){ return input; }
          }''', 'Use.java': '''class Use extends Api<Integer> {
            void run(){ Integer value = get(); Integer copy = echo(value); System.out.println("before"); }
          }'''})

    def test_member_inner_constructor_and_static_nested_type(self):
        self.roundtrip({'Outer.java': '''class Outer<T> {
          class Inner<U> { U value; U get(){return value;} }
          static class Nested { int number(){return 1;} }
        }''', 'Use.java': '''class Use {
          void run(){ Outer<String> outer = new Outer<>();
            Outer<String>.Inner<Integer> inner = outer.new Inner<>();
            Integer value = inner.get(); new Outer.Nested().number(); System.out.println("before"); }
        }'''})

    def test_annotation_defaults_enum_and_constants(self):
        self.roundtrip({'Api.java': '''import java.lang.annotation.*;
          @Retention(RetentionPolicy.RUNTIME) @interface Flag {
            int value() default 3; byte small() default 1; char letter() default 'a';
            long large() default 9223372036854775806L;
          }
          enum Mode { ONE, TWO }
          @Flag class Api { static final int COUNT=7; Mode mode(){return Mode.ONE;} }
        ''', 'Use.java': '''@Flag class Use {
          void run(){ switch(Api.COUNT){case 7: break;} Mode.valueOf("ONE"); new Api().mode(); System.out.println("before"); }
        }'''})

    def test_type_annotations_in_generic_arguments(self):
        files = {'Api.java': '''import java.util.*; import java.lang.annotation.*;
          @Target({ElementType.TYPE_USE,ElementType.TYPE_PARAMETER}) @Retention(RetentionPolicy.RUNTIME)
          @interface Mark {}
          class Api<@Mark T extends @Mark Number> {
            List<@Mark ? extends @Mark T> values; @Mark T get(){return null;}
            List<@Mark ? super @Mark T> lower;
          }
        ''', 'Use.java': '''class Use extends Api<Integer> {
          void run(){ Integer value = get(); System.out.println("before"); }
        }'''}
        facts = self.analyze([self.context('app', files)])['facts']
        for signature, edge in (('Api#values', 'extends'), ('Api#lower', 'super')):
            field = next(f for f in facts if f['signature'] == signature)
            wildcard = type_attributes(field)['type_model']['arguments'][0]
            self.assertEqual(wildcard['kind'], 'WILDCARD')
            self.assertIn('Mark', wildcard['annotations'])
            self.assertIn('Mark', wildcard[edge]['annotations'])
            self.assertEqual((wildcard[edge]['name'], wildcard[edge]['owner']), ('T', 'Api'))
            self.assertNotIn('upper', wildcard)
            self.assertNotIn('lower', wildcard)
        self.roundtrip(files)

    def test_optional_cache_write_failure_preserves_source_facts(self):
        (self.root / 'signature-cache').write_text('a file cannot hold cache entries')
        context = self.context('app', {'Use.java': 'class Use { void run(){System.out.println("ok");} }'})
        cached = self.analyze([context], cache=True)
        full = self.analyze([context])
        self.assertEqual(cached['signature_cache_write_failures'], 1)
        self.assertEqual(cached['facts'], full['facts'])

    def test_missing_written_annotation_cannot_enter_nocode_cache(self):
        import json
        context = self.context('app', {
            'Api.java': 'class Api { @Missing int value; }',
            'Use.java': 'class Use { int read(Api api){ return api.value; } }',
        })
        facts = self.analyze([context], cache=True)['facts']
        field = next(f for f in facts if f['kind']=='declaration' and f['signature']=='Api#value')
        self.assertFalse(field['attributes']['written_type_complete'])
        cached = [d for p in (self.root/'signature-cache').rglob('declarations.json')
                  for d in json.loads(p.read_text())['declarations']]
        self.assertTrue(any(d['signature']=='Use' for d in cached))
        self.assertFalse(any(d['source']=='app/Api.java' for d in cached))

    def test_unchanged_source_header_drift_invalidates_derived_catalogue(self):
        files = {'Defaults.java':'class Defaults { static final int NUMBER=1; }',
                 'Api.java':'class Api { static final int NUMBER=Defaults.NUMBER; }',
                 'Use.java':'class Use { void run(){System.out.println(Api.NUMBER + "before");} }'}
        self.analyze([self.context('app', files)], cache=True)
        files['Defaults.java'] = files['Defaults.java'].replace('NUMBER=1', 'NUMBER=2')
        current = self.analyze([self.context('app', files)], cache=True)['facts']
        files['Use.java'] = files['Use.java'].replace('before', 'after')
        context = self.context('app', files)
        delta = self.analyze([context], current, cache=True)
        self.assertEqual(delta['reduced_contexts'], 0)
        self.assertEqual(self.merged(current, delta), sorted(self.analyze([context])['facts'], key=lambda f: f['id']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
