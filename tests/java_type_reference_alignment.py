#!/usr/bin/env python3
"""Source signature relationships, callback types, and retained navigation."""
import collections
from java_type_views import type_attributes
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok
from java_collector_alignment import SOURCE as COLLECTOR_SOURCE


SOURCE = '''package p;
class Leaf {} class Box<T> {} class Parent<T> {} interface Contract<T> {}
class Holder<T extends Leaf> extends Parent<Leaf> implements Contract<Leaf> {
 Box<Leaf> field; java.util.List<? extends Leaf> bounded;
 Holder(Leaf first, Leaf[] second){}
 Box<Leaf> read(Leaf first, Leaf second, Box<Box<Leaf>> nested, Leaf[][] matrix) throws java.io.IOException {
  Leaf local=new Leaf(); Class<?> type=Leaf.class; Object cast=(Leaf)local;
  java.util.function.Function<Leaf,Box<Leaf>> callback=value ->new Box<>();
  return null;
 }
}
'''
EXTENDED_SOURCE = '''package p;
import java.util.Map;
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Mark {}
class Leaf {} class Outer {static class Inner {}}
class Holder<T extends Leaf & java.io.Serializable> {
 Missing absent; @Mark Leaf field; Map.Entry<String,Outer.Inner> nested;
 java.util.List<? super Leaf[]> lower;
 Holder(Leaf... values){}
 <R extends Leaf> R generic(R first, @Mark Leaf[] second) throws java.io.IOException {return null;}
 void read(){ Missing missing=null; java.util.function.Function<Leaf,Outer.Inner> explicit=(Leaf value)->null;
 java.util.function.Supplier<Leaf[]> arrays=()->new Leaf[1]; }
}
'''
ENUM_SOURCE = '''package p;
class Leaf {} interface Root<T> {} interface Child extends Root<Leaf> {}
enum Mode { ONE, TWO; Leaf value; }
class Holder {java.util.Map<String,Leaf> field;}
'''
AMBIGUOUS_SOURCE = '''import a.*;import b.*;
class Use {Leaf ambiguous; Missing absent; a.Leaf known;}
'''


ANNOTATED_PARENT_SOURCE = '''package p;
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Mark {}
class Leaf {} class Parent<T> {} interface Contract<T> {}
class Holder extends @Mark Parent<Leaf> implements @Mark Contract<Leaf> {}
class Outer<T> {class Inner<U> {}}
class Use {Outer<Leaf>.Inner<Leaf> value;}
'''


ANONYMOUS_HIERARCHY_SOURCE = '// 类型𐐀\r\nclass Leaf {} interface Service {Leaf take(Leaf value);} class Base {} class Use{void read(){Service service=new Service(){Leaf field;public Leaf take(Leaf value){return value;}};Base base=new Base(){};}}'

OBJECT_HIERARCHY_SOURCE = 'class Base {} class Explicit extends Object {} class Plain {} class Use{void read(){Object a=new Object(){};Base b=new Base(){};}}'

class SourceTypeReferences(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    @staticmethod
    def references(facts, owner=None):
        by = {f['id']: f for f in facts}
        return [(by[f['parent']]['signature'], by[f['target']]['signature'], f)
                for f in facts if f['relation'] == 'type_reference'
                and (owner is None or by[f['parent']]['signature'] == owner)]

    def test_anonymous_object_has_no_implicit_hierarchy_edge(self):
        facts = self.analyze([self.context('app', {'App.java': OBJECT_HIERARCHY_SOURCE})])['facts']
        by = {f['id']: f for f in facts}
        edges = [f for f in facts if f['relation'] in ('extends', 'implements')]
        self.assertEqual(len(edges), 2)
        named = [f for f in edges if by[f['parent']]['signature'] == 'Explicit']
        self.assertEqual(len(named), 1)
        self.assertEqual(by[named[0]['target']]['signature'], 'java.lang.Object')
        anonymous = [f for f in edges if by[f['parent']]['attributes'].get('nesting_kind') == 'ANONYMOUS']
        self.assertEqual(len(anonymous), 1)
        self.assertEqual(by[anonymous[0]['target']]['signature'], 'Base')
        self.assertEqual(OBJECT_HIERARCHY_SOURCE.encode()[anonymous[0]['span']['start']:anonymous[0]['span']['end']], b'{}')

    def test_declaration_signatures_keep_multiplicity_and_body_navigation(self):
        facts = self.analyze([self.context('app', {'App.java': SOURCE})])['facts']
        refs = self.references(facts)
        self.assertEqual(len(refs), 23)
        method = 'p.Holder#read(p.Leaf,p.Leaf,p.Box,p.Leaf[][])'
        edges = self.references(facts, method)
        self.assertEqual(collections.Counter(target for _, target, _ in edges)['p.Leaf'], 5)
        self.assertCountEqual([f['attributes']['type_context'] for _, _, f in edges],
                             ['return_type', 'generic_arg', 'parameter_type', 'parameter_type',
                              'parameter_type', 'generic_arg', 'generic_arg', 'parameter_type', None])
        encoded = SOURCE.encode()
        for _, _, edge in edges:
            self.assertIsNotNone(edge['span'])
            self.assertNotIn(b'local', encoded[edge['span']['start']:edge['span']['end']])
        by = {f['id']: f for f in facts}
        navigation = [f for f in facts if f['relation'] == 'type_navigation'
                      and by[f['parent']]['signature'] == method]
        self.assertTrue(any(encoded[f['span']['start']:f['span']['end']] == b'Leaf' for f in navigation))
        hierarchy = [f for f in facts if f['relation'] in ('extends', 'implements')
                     and by[f['parent']]['signature'] == 'p.Holder']
        self.assertCountEqual([encoded[f['span']['start']:f['span']['end']] for f in hierarchy],
                             [b'Parent', b'Contract'])

    def test_implicit_callbacks_include_captured_parameter_types(self):
        facts = self.analyze([self.context('app', {'App.java': SOURCE})])['facts']
        callbacks = [(target, f['attributes']['type_context']) for owner, target, f in self.references(facts)
                     if '#<lambda@' in owner]
        self.assertCountEqual(callbacks, [('java.util.function.Function', 'functional_interface'),
                                         ('p.Leaf', 'generic_arg'), ('p.Box', 'generic_arg'),
                                         ('p.Leaf', 'generic_arg'), ('p.Leaf', 'parameter_type')])

    def test_unknown_types_are_not_complete_relationships_and_unicode_spans_match(self):
        source = '// 类型𐐀\r\n' + EXTENDED_SOURCE.replace('\n', '\r\n')
        facts = self.analyze([self.context('app', {'App.java': source})])['facts']
        self.assertEqual(len(self.references(facts)), 18)
        self.assertFalse([target for _, target, _ in self.references(facts) if target.endswith('Missing')])
        self.assertTrue([f for f in facts if f['kind'] == 'coverage' and f['status'] == 'partial'])
        self.assertTrue([f for f in facts if f['kind'] == 'diagnostic' and f['attributes'].get('severity') == 'ERROR'])
        written = [source.encode()[f['span']['start']:f['span']['end']]
                   for _, _, f in self.references(facts) if f['span'] is not None]
        self.assertIn(b'java.io.IOException', written)
        self.assertIn(b'Outer.Inner', written)

    def test_recovered_callbacks_keep_original_evidence_and_deduplicate_parameter_types(self):
        context = self.context('app', lombok.Lombok.files(self, COLLECTOR_SOURCE), lombok='model')
        facts = self.analyze([context])['facts']
        by = {f['id']: f for f in facts}
        recovered = [f for f in facts if f['relation'] == 'type_reference' and f['origin'] == 'source_recovery']
        self.assertEqual(len(recovered), 8)
        for f in recovered:
            self.assertEqual(f['status'], 'possible')
            self.assertFalse(f['evidence_complete'])
            self.assertTrue(f['attributes']['modeled_input'])
            original = type_attributes(by[f['parent']])['functional_type_model']
            self.assertNotEqual(original['kind'], 'DECLARED')
        self.assertEqual(len({(f['parent'], f['target'], f['attributes']['type_context']) for f in recovered}), 8)
        self.assertFalse([f for f in recovered if '#wrongExplicit(' in by[f['parent']]['signature']])

    def test_ambiguous_source_types_never_create_confirmed_relationships(self):
        files = {'App.java': AMBIGUOUS_SOURCE, 'a/Leaf.java': 'package a;public class Leaf {}',
                 'b/Leaf.java': 'package b;public class Leaf {}'}
        facts = self.analyze([self.context('app', files)])['facts']
        refs = self.references(facts)
        self.assertCountEqual([(owner, target) for owner, target, _ in refs], [('Use#known', 'a.Leaf')])
        self.assertTrue([f for f in facts if f['kind'] == 'coverage' and f['status'] == 'partial'])

    def test_generated_default_external_policy_preserves_written_source_types(self):
        source = 'class Leaf {} @lombok.Data class Holder {String name; java.util.Map<String,Leaf> map; Leaf leaf;} class Explicit {Object echo(Object value){return value;}}'
        context = self.context('app', lombok.Lombok.files(self, source), lombok='model')
        facts = self.analyze([context])['facts']
        refs = self.references(facts)
        generated = [(target, edge) for _, target, edge in refs if edge['origin'] == 'lombok_model']
        self.assertTrue(generated)
        self.assertFalse([target for target, _ in generated if target.startswith('java.lang.')])
        self.assertTrue([edge for owner, target, edge in refs if owner == 'Holder#name' and target == 'java.lang.String'])
        self.assertTrue([edge for owner, target, edge in refs if owner == 'Explicit#echo(java.lang.Object)' and target == 'java.lang.Object'])

    def test_anonymous_synthetic_headers_do_not_add_signature_relationships(self):
        source = 'class Leaf {} class Box<T> {} class Use {void read(){Box<java.util.Map<String,Leaf>> box=new Box<java.util.Map<String,Leaf>>(){Leaf field; Leaf take(Leaf value){return value;}};}}'
        facts = self.analyze([self.context('app', {'App.java': source})])['facts']
        refs = self.references(facts)
        self.assertEqual(len(refs), 3)
        self.assertCountEqual([edge['attributes']['type_context'] for _, _, edge in refs],
                             ['field_type', 'return_type', 'parameter_type'])
        self.assertTrue(all(target == 'Leaf' for _, target, _ in refs))
        by = {f['id']: f for f in facts}
        self.assertTrue([f for f in facts if f['relation'] == 'type_navigation'
                         and by[f['target']]['signature'] == 'java.util.Map'])

    def test_anonymous_hierarchy_uses_body_span_and_preserves_members(self):
        source = ANONYMOUS_HIERARCHY_SOURCE
        facts = self.analyze([self.context('app', {'App.java': source})])['facts']
        by = {f['id']: f for f in facts}
        owners = {f['id'] for f in facts if f['kind'] == 'declaration'
                  and f['attributes'].get('nesting_kind') == 'ANONYMOUS'}
        edges = [f for f in facts if f['parent'] in owners and f['relation'] in ('extends', 'implements')]
        self.assertEqual(len(edges), 2)
        self.assertEqual({f['relation'] for f in edges}, {'extends', 'implements'})
        for edge in edges:
            self.assertEqual(edge['span'], by[edge['parent']]['span'])
            self.assertTrue(source.encode()[edge['span']['start']:edge['span']['end']].startswith(b'{'))
        self.assertFalse([f for f in facts if f['relation'] == 'type_reference' and f['parent'] in owners])
        self.assertEqual(len(self.references(facts)), 5)
        self.assertTrue(any(f['relation'] == 'type_navigation'
                            and by[f['target']]['signature'] in ('Base', 'Service') for f in facts))

    def test_callback_and_signature_edits_and_removal_match_full_delta(self):
        old = self.analyze([self.context('app', {'App.java': SOURCE})], cache=True)['facts']
        changed = SOURCE.replace('Box<Leaf> field;', 'Leaf field;').replace('Function<Leaf,Box<Leaf>>', 'Function<Box<Leaf>,Leaf>').replace('value ->new Box<>()', 'value ->null')
        context = self.context('app', {'App.java': changed})
        delta = self.analyze([context], old, cache=True)
        full = self.analyze([context], cache=True)['facts']
        self.assertEqual(self.merged(old, delta), sorted(full, key=lambda f: f['id']))
        empty = self.context('app', {'App.java': 'package p; class Holder {}'})
        self.assertEqual(self.merged(full, self.analyze([empty], full, cache=True)),
                         sorted(self.analyze([empty], cache=True)['facts'], key=lambda f: f['id']))


class SourceTypeBudgets(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    probe = 'dev.codeana.analysis.SourceTypeBudgetProbe'

    def test_budget_limits_one_owner_and_publishes_partial_coverage(self):
        facts = self.analyze([self.context('app', {'App.java': 'class Leaf {}'})])['facts']
        by = {f['id']: f for f in facts}
        references = [f for f in facts if f['relation'] == 'type_reference']
        self.assertEqual([by[f['parent']]['signature'] for f in references], ['Leaf#healthy'])
        diagnostics = [f for f in facts if f['kind'] == 'diagnostic']
        self.assertEqual([f['name'] for f in diagnostics], ['SOURCE_TYPE_REFERENCE_BUDGET'])
        self.assertEqual(diagnostics[0]['attributes']['owner'], 'Leaf#limited')
        self.assertTrue([f for f in facts if f['kind'] == 'coverage' and f['status'] == 'partial'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
