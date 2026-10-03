#!/usr/bin/env python3
"""Independent expected facts for the Java alignment work."""
import unittest
import pathlib
import subprocess
import shutil
import java_model_tests as model_tests
from java_type_views import type_attributes


class JavaSemanticAlignment(unittest.TestCase):
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze
    merged = model_tests.Models.merged

    def test_deep_finite_types_annotations_and_partial_budget_survive_delta(self):
        def nested(depth, terminal='String'):
            return 'java.util.List<' * depth + terminal + '> ' * depth
        source = '''import java.lang.annotation.*;
          @Target(ElementType.TYPE_USE) @interface Mark {}
          class Deep<T extends Comparable<T>> {''' + ''.join(
            nested(depth) + ' value' + str(depth) + ';' for depth in (5,20,40,60)) + (
            nested(40,'@Mark String') + ' annotated;' + nested(20,'Missing') +
            ' missing; T cyclic; int marker=1; }')
        def verify(facts):
            fields = {f['name']:type_attributes(f)['type_model'] for f in facts
                      if f['kind']=='declaration' and f['signature'].startswith('Deep#')
                      and f['attributes'].get('element_kind')=='FIELD'}
            for depth in (5,20,40):
                value=fields['value'+str(depth)]
                self.assertIs(value['complete'],True)
                for _ in range(depth):
                    self.assertEqual(value['element'],'java.util.List')
                    self.assertEqual(len(value['arguments']),1)
                    value=value['arguments'][0]
                self.assertEqual(value['element'],'java.lang.String')
                self.assertIs(value['complete'],True)
            annotated=fields['annotated']
            for _ in range(40): annotated=annotated['arguments'][0]
            self.assertIn('Mark',annotated['annotations'])
            self.assertIs(fields['annotated']['complete'],True)
            self.assertIs(fields['missing']['complete'],False)
            value=fields['value60'];self.assertIs(value['complete'],False)
            while value.get('arguments'): value=value['arguments'][0]
            self.assertEqual(value['reason'],'TYPE_MODEL_DEPTH_BUDGET')
            self.assertNotIn('recursive',value)
            value=fields['cyclic'];self.assertIs(value['complete'],True)
            recursive=value['upper']['arguments'][0]
            self.assertIs(recursive['recursive'],True)
            self.assertIs(recursive['complete'],True)
            self.assertEqual((recursive['name'],recursive['owner']),('T','Deep'))
            self.assertNotIn('reason',recursive)
            budgets=[f for f in facts if f['kind']=='diagnostic' and f['name']=='TYPE_MODEL_DEPTH_BUDGET']
            self.assertEqual(len(budgets),1)
            coverage={f['source']:f['status'] for f in facts if f['kind']=='coverage'}
            self.assertEqual(coverage,{'deep/Deep.java':'partial','deep/Healthy.java':'complete'})
        context=self.context('deep',{'Deep.java':source,'Healthy.java':'class Healthy {int ok(){return 1;}}'},type_graph=False)
        initial=self.analyze([context],cache=True)['facts'];verify(initial)
        changed=self.context('deep',{'Deep.java':source.replace('marker=1','marker=2'),'Healthy.java':'class Healthy {int ok(){return 1;}}'},type_graph=False)
        delta=self.analyze([changed],initial,cache=True)
        full=self.analyze([changed],cache=True)['facts'];verify(full)
        self.assertEqual(self.merged(initial,delta),sorted(full,key=lambda f:f['id']))

    def test_class_literals_are_not_synthetic_field_reads(self):
        context=self.context('literals',{'Use.java':'''class Use {Class<?> own=Use.class;
          Class<?> array=String[].class;Class<?> primitive=int.class;
          Class<?> other(){return String.class;} }'''})
        facts=self.analyze([context])['facts']
        declarations={f['id']:f for f in facts if f['kind'] in ('declaration','external_symbol')}
        self.assertFalse([f for f in declarations.values() if f['signature'].endswith('#class')])
        self.assertFalse([f for f in facts if f['relation']=='reads' and declarations[f['target']]['signature'].endswith('#class')])
        self.assertTrue([f for f in facts if f['relation']=='type_navigation' and declarations[f['target']]['signature']=='java.lang.String'])
        self.assertFalse([f for f in facts if f['relation']=='type_reference' and declarations[f['target']]['signature']=='java.lang.String'])

    def test_annotation_constants_preserve_bound_fields_and_defaults(self):
        context=self.context('metadata',{'Flag.java':'''import java.lang.annotation.*;
          @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
          @interface Flag {RetentionPolicy value() default RetentionPolicy.CLASS;}
          class Use {@Flag(RetentionPolicy.SOURCE) String value;}'''})
        facts=self.analyze([context])['facts'];declarations={f['id']:f for f in facts if f['kind'] in ('declaration','external_symbol')}
        reads={declarations[f['target']]['signature'] for f in facts if f['relation']=='reads'}
        self.assertIn('java.lang.annotation.RetentionPolicy#RUNTIME',reads)
        self.assertIn('java.lang.annotation.ElementType#FIELD',reads)
        self.assertIn('java.lang.annotation.RetentionPolicy#CLASS',reads)
        constants={declarations[f['target']]['signature'] for f in facts if f['kind']=='reference' and f['target']}
        self.assertIn('java.lang.annotation.RetentionPolicy#RUNTIME',constants)

    def test_field_enum_and_callback_initializers_keep_their_lexical_owner(self):
        source = '''import java.util.function.*; class Use {
          static String build(String text){return text;}
          static String produce(){return "value";}
          static final String STATIC = build("static");
          String instance = build("instance");
          Supplier<String> lazy = () -> build("lambda");
          Supplier<String> reference = Use::produce;
          static { build("block"); }
        } enum Choice { FIRST(Use.build("enum")); Choice(String text){} }'''
        def verify(facts):
            declarations = {f['id']:f for f in facts if f['kind']=='declaration'}
            calls = [f for f in facts if f['kind']=='call_site']
            self.assertFalse([f for f in declarations.values() if '<field-init:' in f['signature']])
            for name in ('STATIC','instance','FIRST'):
                owner = next(f for f in declarations.values() if f['name']==name and f['attributes']['element_kind'] in ('FIELD','ENUM_CONSTANT'))
                owned = [f for f in calls if f['parent']==owner['id'] and f['attributes'].get('lookup_name')=='build']
                self.assertEqual(len(owned),1,(name,owned))
            for name,kind in (('lazy','LAMBDA'),('reference','METHOD_REF')):
                field = next(f for f in declarations.values() if f['name']==name and f['attributes']['element_kind']=='FIELD')
                callback = next(f for f in declarations.values() if f['parent']==field['id'] and f['attributes']['element_kind']==kind)
                self.assertEqual(len([f for f in calls if f['parent']==callback['id']]),1)
            self.assertTrue(any(declarations[f['parent']]['attributes']['element_kind']=='INITIALIZER' for f in calls))
            self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['status']=='ERROR'])
        context = self.context('owners',{'Use.java':source})
        initial = self.analyze([context],cache=True)['facts'];verify(initial)
        changed = self.context('owners',{'Use.java':source.replace('"static"','"changed"')})
        delta = self.analyze([changed],initial,cache=True)
        full = self.analyze([changed],cache=True)['facts'];verify(full)
        self.assertEqual(self.merged(initial,delta),sorted(full,key=lambda f:f['id']))

    def test_interface_inheritance_explicit_object_and_enum_body_relations(self):
        context=self.context('inheritance',{'Types.java': '''interface Root {}
          interface Child extends Root {} class Base {}
          class Impl extends Base implements Child {} class Explicit extends Object {}
          enum Choice { FIRST { void run(){} }; abstract void run(); } @interface Flag {}'''})
        def verify(facts):
            declarations={f['id']:f for f in facts if f['kind'] in ('declaration','external_symbol')}
            edges={(declarations[f['parent']]['signature'],f['relation'],declarations[f['target']]['signature'])
                   for f in facts if f['kind']=='relation' and f['relation'] in ('extends','implements')}
            self.assertEqual(edges,{('Child','extends','Root'),('Impl','extends','Base'),
                                    ('Impl','implements','Child'),('Explicit','extends','java.lang.Object'),
                                    ('Choice$1','extends','Choice'),('Flag','extends','java.lang.annotation.Annotation')})
        facts=self.analyze([context],cache=True)['facts'];verify(facts)
        updated=self.context('inheritance',{'Types.java': '''interface Root {}
          interface Child extends Root {} class Base { int marker; }
          class Impl extends Base implements Child {} class Explicit extends Object {}
          enum Choice { FIRST { void run(){} }; abstract void run(); } @interface Flag {}'''})
        delta=self.analyze([updated],facts,cache=True);full=self.analyze([updated],cache=True)['facts']
        self.assertEqual(self.merged(facts,delta),sorted(full,key=lambda f:f['id']));verify(full)

    def test_binding_evidence_has_one_canonical_type_graph(self):
        context = self.context('bindings', {'Use.java': '''class Use {
            static <T> T identity(T value){return value;}
            String read(){return identity("value");}
        }'''})
        facts = self.analyze([context])['facts']
        site = next(f for f in facts if f['kind']=='call_site' and f['name']=='identity')
        binding = next(f for f in facts if f['relation']=='java.binding' and f['parent']==site['id'])
        target = next(f for f in facts if f['kind']=='call_target' and f['parent']==site['id'])
        self.assertEqual(binding['attributes'],dict(schema_version=4,evidence_owner=site['id'],resolved=True,complete=True))
        self.assertEqual(target['attributes'],dict(binding_evidence=site['id']))
        self.assertEqual(type_attributes(site)['selected_type_model']['return']['element'],'java.lang.String')
        self.assertEqual(type_attributes(site)['declared_type_model']['return']['kind'],'TYPEVAR')
        self.assertEqual(type_attributes(site)['expression_type_model']['element'],'java.lang.String')

    def test_method_reference_callbacks_and_intrinsic_array_constructors(self):
        source = '''import java.util.function.*;
          class Use { static String identity(String value){return value;}
            Use receiver(){return this;} String read(){return "value";}
            void run(){Function<String,String> method=Use::identity;
              Supplier<String> bound=receiver()::read;
              Supplier<Use> constructor=Use::new;
              IntFunction<String[]> array=String[]::new;
              IntFunction<int[][]> multi=int[][]::new;}
          }'''
        context = self.context('callbacks', {'Use.java':source})
        facts = self.analyze([context],cache=True)['facts']
        declarations = {f['id']:f for f in facts if f['kind']=='declaration'}
        callbacks = [f for f in declarations.values() if f['attributes']['element_kind']=='METHOD_REF']
        self.assertEqual(len(callbacks),5)
        intrinsic = [f for f in callbacks if f['attributes'].get('navigation_status')=='intrinsic']
        self.assertEqual(sorted(f['attributes']['array_type'] for f in intrinsic),['String[]','int[][]'])
        self.assertTrue(all(f['attributes']['sam'] for f in callbacks))
        references = [f for f in facts if f['kind']=='call_site' and f['attributes']['mechanism']=='method_reference']
        self.assertEqual(len(references),3)
        self.assertTrue(all(declarations[f['parent']]['attributes']['element_kind']=='METHOD_REF' for f in references))
        self.assertFalse([f for f in facts if f['kind']=='call_site' and f['parent'] in {d['id'] for d in intrinsic}])
        receiver = next(f for f in facts if f['kind']=='call_site' and f['name']=='receiver')
        self.assertEqual(declarations[receiver['parent']]['attributes']['element_kind'],'METHOD_REF')
        self.assertEqual(receiver['attributes']['evaluation_phase'],'callback_creation')
        changed = self.context('callbacks', {'Use.java':source.replace('"value"','"changed"')})
        self.assertEqual(self.merged(facts,self.analyze([changed],facts,cache=True)),
                         sorted(self.analyze([changed])['facts'],key=lambda f:f['id']))

    def test_nested_receiver_lambda_keeps_deferred_evaluation_separate(self):
        source = '''import java.util.function.*; class Use {
            static Use make(){return new Use();} String read(){return "value";}
            void run(){Supplier<String> bound=((Supplier<Use>)(() -> make())).get()::read;}
        }'''
        facts = self.analyze([self.context('nested',{'Use.java':source})])['facts']
        declarations = {f['id']:f for f in facts if f['kind']=='declaration'}
        make = next(f for f in facts if f['kind']=='call_site' and f['name']=='make')
        self.assertEqual(declarations[make['parent']]['attributes']['element_kind'],'LAMBDA')
        self.assertNotIn('evaluation_phase',make['attributes'])
        get = next(f for f in facts if f['kind']=='call_site' and f['attributes'].get('lookup_name')=='get')
        self.assertEqual(declarations[get['parent']]['attributes']['element_kind'],'METHOD_REF')
        self.assertEqual(get['attributes']['evaluation_phase'],'callback_creation')
        deferred = next(f for f in facts if f['kind']=='call_site' and f['attributes']['mechanism']=='method_reference')
        self.assertNotIn('evaluation_phase',deferred['attributes'])

    def test_invalid_array_reference_keeps_intrinsic_navigation_without_confirmed_binding(self):
        source = 'import java.util.function.*; class Use { IntFunction<Use> wrong=String[]::new; }'
        facts = self.analyze([self.context('arrays',{'Use.java':source})])['facts']
        callback = next(f for f in facts if f['kind']=='declaration' and f['attributes']['element_kind']=='METHOD_REF')
        self.assertEqual(callback['attributes']['navigation_status'],'intrinsic')
        self.assertFalse(callback['attributes']['binding_resolved'])
        self.assertFalse([f for f in facts if f['kind']=='call_site'])
        self.assertTrue(any(f['kind']=='diagnostic' and f['status']=='ERROR' for f in facts))

    def test_explicit_constructor_calls_cover_the_source_statement(self):
        source = '''class Base { Base(int number){} }
          class Use extends Base { Use(){super(1);} Use(int number){this();}
            void run(){this.helper();} void helper(){} }
          class Outer { class Inner {} }
          class Child extends Outer.Inner { Child(Outer outer){outer.super();} }'''
        context = self.context('constructors', {'Use.java': source})
        facts = self.analyze([context])['facts']
        calls = [f for f in facts if f['kind']=='call_site']
        snippets = sorted(source.encode()[f['span']['start']:f['span']['end']].decode() for f in calls)
        self.assertEqual(snippets, sorted(['outer.super();', 'super(1);', 'this.helper()', 'this();']))

    def test_enum_synthetic_initializer_is_not_a_source_call_site(self):
        source = '''enum E { ONE(helper()), TWO;
          E(){this(0);} E(int value){}
          static int helper(){return 1;} static E[] read(){return values();} }'''
        context = self.context('enums', {'E.java': source})
        facts = self.analyze([context], cache=True)['facts']
        calls = [f for f in facts if f['kind']=='call_site']
        self.assertEqual(len(calls), 3)
        self.assertEqual(sorted(f['name'] for f in calls), ['helper', 'this', 'values'])
        changed = self.context('enums', {'E.java': source.replace('return 1;', 'return 2;')})
        self.assertEqual(self.merged(facts, self.analyze([changed], facts, cache=True)),
                         sorted(self.analyze([changed])['facts'], key=lambda f:f['id']))

    def test_implicit_enum_members_are_source_owned_without_invented_bodies(self):
        ctx = self.context('enums', {'E.java': 'enum E { ONE, TWO; static E choose(String name){return valueOf(name);} }'})
        facts = self.analyze([ctx])['facts']
        declarations = {f['signature']:f for f in facts if f['kind']=='declaration'}
        for signature in ('E#values()', 'E#valueOf(java.lang.String)'):
            member = declarations[signature]
            self.assertEqual(member['origin'], 'javac')
            self.assertTrue(member['declaration_resolved'])
            self.assertTrue(member['attributes']['compiler_implicit'])
            self.assertFalse(member['attributes']['body_available'])
            self.assertEqual(member['source'], 'enums/E.java')
        target = declarations['E#valueOf(java.lang.String)']['id']
        self.assertTrue(any(f['kind']=='call_target' and f['target']==target and f['declaration_resolved'] for f in facts))
        ctx = self.context('enums', {'E.java': 'enum E { ONE; static E choose(String name){return valueOf(name);} }'})
        self.assertEqual(self.merged(facts,self.analyze([ctx],facts)),sorted(self.analyze([ctx])['facts'],key=lambda f:f['id']))

    def test_binary_generic_models_and_complete_large_member_headers(self):
        source = self.root / 'library/lib/Api.java'
        source.parent.mkdir(parents=True)
        source.write_text('package lib; public class Api<T extends Number & Comparable<T>> {'
                          + ''.join(f'public int field{i};' for i in range(2100))
                          + ' public java.util.List<? extends T> values(){ return null; } }')
        classes = self.root / 'compiled'
        classes.mkdir()
        subprocess.run([str(model_tests.JDK8/'bin/javac'), '-proc:none', '-d', str(classes), str(source)],
                       check=True, capture_output=True, timeout=30)
        jar = self.root / 'api.jar'
        subprocess.run([str(model_tests.JDK8/'bin/jar'), 'cf', str(jar), '-C', str(classes), '.'],
                       check=True, capture_output=True, timeout=30)
        shutil.rmtree(classes)
        ctx = self.context('binary', {'Use.java': 'class Use { int read(lib.Api<?> api){ return api.field2099; } java.util.List<?> values(lib.Api<?> api){return api.values();} }'},
                           classpath=[str(jar)])
        facts = self.analyze([ctx])['facts']
        external = next(f for f in facts if f['signature'] == 'lib.Api#field2099')
        attrs = type_attributes(external)
        self.assertNotIn('binary_members_partial',attrs)
        self.assertEqual(attrs['binary_class']['members_omitted'], 0)
        self.assertTrue(attrs['binary_class']['members_complete'])
        method = next(f for f in facts if f['signature']=='lib.Api#values()' and f['kind']=='external_symbol')
        self.assertEqual(method['attributes']['binary_member']['kind'],'METHOD')
        formal = attrs['binary_class']['type_model']['type_parameters'][0]
        self.assertEqual(formal['name'], 'T')
        self.assertEqual([b['element'] for b in formal['bounds']], ['java.lang.Number', 'java.lang.Comparable'])
        self.assertEqual(attrs['binary_member']['type_model']['kind'], 'INT')

    def test_external_provenance_uses_exact_jar_or_auxiliary_source(self):
        import hashlib
        source = self.root/'libsrc/p/Api.java'; source.parent.mkdir(parents=True)
        source.write_text('package p; public class Api {public static int call(){return 1;}}')
        classes = self.root/'compiled'; classes.mkdir()
        subprocess.run([str(model_tests.JDK8/'bin/javac'), '-proc:none', '-d', str(classes), str(source)], check=True, capture_output=True)
        jar = self.root/'api.jar'
        subprocess.run([str(model_tests.JDK8/'bin/jar'), 'cf', str(jar), '-C', str(classes), '.'], check=True, capture_output=True)
        shutil.rmtree(classes)
        context = self.context('binary-origin', {'Use.java':'class Use {int run(){return p.Api.call();}}'}, classpath=[str(jar)])
        facts = self.analyze([context])['facts']
        target = next(f for f in facts if f['signature']=='p.Api#call()')
        self.assertEqual(target['attributes']['artifact_inputs'], ['api.jar'])
        self.assertEqual(target['attributes']['artifact_origin'], dict(kind='classpath',index=0,name='api.jar',sha256=hashlib.sha256(jar.read_bytes()).hexdigest()))
        context_provenance = next(f for f in facts if f['kind']=='provenance' and not f['source'])
        self.assertEqual(context_provenance['attributes']['artifact_inputs'], ['api.jar'])
        # SOURCE shadows a same-named frozen binary; its origin must remain SOURCE.
        library = self.context('library', {'p/Api.java':'package p; public class Api {public static int call(){return 2;}}'})
        context['sources'] = [dict(row, publish=False) for row in library['sources']] + context['sources']
        context['digest'] = model_tests.sha(model_tests.json.dumps(context['sources'],sort_keys=True).encode())
        facts = self.analyze([context])['facts']
        target = next(f for f in facts if f['signature']=='p.Api#call()')
        self.assertEqual(target['attributes']['artifact_inputs'], [])
        self.assertNotIn('artifact_origin', target['attributes'])
        unit = library['sources'][0]
        self.assertEqual(target['attributes']['source_origin'], {key:unit[key] for key in ('path','hash','world','module','scope')})
        library = self.context('library', {'p/Api.java':'package p; public class Api {public static int call(){return 3;}}'})
        context['sources'] = [dict(row,publish=False) for row in library['sources']] + [row for row in context['sources'] if row['publish']]
        context['digest'] = model_tests.sha(model_tests.json.dumps(context['sources'],sort_keys=True).encode())
        delta = self.analyze([context],facts)
        self.assertEqual(self.merged(facts,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))

    def test_field_reads_writes_and_control_context(self):
        ctx = self.context('access', {'Use.java': '''class Use {
          static int count; int value;
          void run(boolean flag) { value = 1; value += 2; ++value;
            int copy = value; if (flag) { count++; } else { count = 0; }
          }
        }'''})
        facts = self.analyze([ctx])['facts']
        field = next(f for f in facts if f['signature'] == 'Use#value')
        reads = [f for f in facts if f['relation'] == 'reads' and f['target'] == field['id']]
        writes = [f for f in facts if f['relation'] == 'writes' and f['target'] == field['id']]
        self.assertEqual(len(reads), 3)
        self.assertEqual(len(writes), 3)
        branches = [f['attributes']['control'] for f in facts if f['relation'] == 'writes' and 'count' in f['name']]
        self.assertTrue(any('if-then' in c for c in branches))
        self.assertTrue(any('if-else' in c for c in branches))
        self.merged([], dict(facts=facts, replacements=[]))

    def test_parenthesized_field_lvalues_and_array_elements_preserve_accesses(self):
        source = '''class Fields { static int count; int value; int[] array; }
          class Use { void run(Fields fields) {
            ((Fields.count))=1; (Fields.count)+=2; ++(Fields.count); ((Fields.count))--;
            (fields.value)=3; ((fields.value))+=4; ++((fields.value)); (fields.value)--;
            ((fields.array[Fields.count]))=5;
            (fields.array[Fields.count])+=6;
            ++((fields.array[0]));
          }}'''
        def check(facts):
            self.assertFalse([f for f in facts if f['kind']=='diagnostic'
                              and f['attributes'].get('severity')=='ERROR'])
            ids = {f['id']: f for f in facts}
            counts = {}
            for fact in facts:
                if fact['relation'] in ('reads', 'writes'):
                    signature = ids[fact['target']]['signature']
                    key = (fact['relation'], signature)
                    counts[key] = counts.get(key, 0) + 1
            self.assertEqual(counts, {('reads', 'Fields#count'): 5,
                                     ('writes', 'Fields#count'): 4,
                                     ('reads', 'Fields#value'): 3,
                                     ('writes', 'Fields#value'): 4,
                                     ('reads', 'Fields#array'): 3})
        first = self.analyze([self.context('fields', {'Use.java': source})], cache=True)['facts']
        check(first)
        changed = self.context('fields', {'Use.java': source.replace('=1;', '=7;')})
        delta = self.analyze([changed], first, cache=True)
        full = self.analyze([changed])['facts']
        self.assertEqual(self.merged(first, delta), sorted(full, key=lambda f: f['id']))
        check(full)

    def test_real_source_and_binary_fields_exclude_this_super_and_array_length(self):
        library = {'p/Base.java': 'package p; public class Base { public int value; public static int count; }',
                   'p/Kind.java': 'package p; public enum Kind { ONE }'}
        use = '''package p; import static p.Base.count;
          class Use extends Base { int[] array; int length;
            int read() { this.value=1; super.value+=2; (this.value)++;
              count=1; count+=2; ++count; count--;
              int n=array.length+((int[])null).length+length;
              Kind k=Kind.ONE; return this.value+super.value+n;
            }
            Object self() { return this; }
          }'''
        expected = {('writes', 'p.Base#value'): 3, ('reads', 'p.Base#value'): 4,
                    ('writes', 'p.Base#count'): 4, ('reads', 'p.Base#count'): 3,
                    ('reads', 'p.Use#array'): 1, ('reads', 'p.Use#length'): 1,
                    ('reads', 'p.Kind#ONE'): 1}

        def check(facts):
            self.assertFalse([f for f in facts if f['kind']=='diagnostic'
                              and f['attributes'].get('severity')=='ERROR'])
            ids = {f['id']: f for f in facts}
            counts = {}
            for fact in facts:
                if fact['relation'] in ('reads', 'writes'):
                    key = (fact['relation'], ids[fact['target']]['signature'])
                    counts[key] = counts.get(key, 0) + 1
            self.assertEqual(counts, expected)
            self.assertFalse([f for f in facts if f['signature'] in
                              ('p.Use#this', 'p.Use#super', 'Array#length')])

        source = self.context('source', library | {'p/Use.java': use})
        check(self.analyze([source])['facts'])
        classes = self.root/'compiled'
        classes.mkdir()
        paths = [s['input'] for s in source['sources'] if not s['path'].endswith('Use.java')]
        subprocess.run([str(model_tests.JDK8/'bin/javac'), '-proc:none', '-d', str(classes), *paths],
                       check=True, capture_output=True, timeout=30)
        jar = self.root/'fields.jar'
        subprocess.run([str(model_tests.JDK8/'bin/jar'), 'cf', str(jar), '-C', str(classes), '.'],
                       check=True, capture_output=True, timeout=30)
        shutil.rmtree(classes)
        binary = self.context('binary', {'p/Use.java': use}, classpath=[str(jar)])
        first = self.analyze([binary], cache=True)['facts']
        check(first)
        changed = self.context('binary', {'p/Use.java': use.replace('count=1;', 'count=7;')},
                               classpath=[str(jar)])
        delta = self.analyze([changed], first, cache=True)
        full = self.analyze([changed])['facts']
        check(full)
        self.assertEqual(self.merged(first, delta), sorted(full, key=lambda f: f['id']))

    def test_nested_parameter_identity_matches_source_binary_and_nocode(self):
        library = {'lib/Outer.java': 'package lib; public class Outer { public static class Inner {} }',
                   'lib/Api.java': '''package lib; public class Api {
                     public static void take(Outer.Inner value, Outer.Inner[][] array){}
                     public static <T extends Outer.Inner> T echo(T value){return value;}
                   }'''}
        use = 'class Use { void run(lib.Outer.Inner value, lib.Outer.Inner[][] array){ lib.Api.take(value,array); lib.Api.echo(value); } }'
        source = self.context('source', library | {'Use.java': use})
        first = self.analyze([source], cache=True)['facts']
        keys = {'lib.Api#take(lib.Outer$Inner,lib.Outer$Inner[][])',
                'lib.Api#echo(lib.Outer$Inner)'}
        self.assertTrue(keys <= {f['signature'] for f in first if f['kind']=='declaration'})
        for f in first:
            if f['kind']=='call_site' and f['name'].startswith('lib.Api.'):
                self.assertIn(f['attributes']['javac_selected_declaration'], keys)
                self.assertTrue(f['evidence_complete'])
        changed = self.context('source', library | {'Use.java': use.replace('lib.Api.echo(value);','lib.Api.echo(value); lib.Api.echo(value);')})
        delta = self.analyze([changed],first,cache=True)
        self.assertEqual(delta['reduced_contexts'],1)
        self.assertEqual(self.merged(first,delta), sorted(self.analyze([changed])['facts'],key=lambda f:f['id']))
        classes = self.root/'compiled'; classes.mkdir()
        paths = [s['input'] for s in source['sources'] if s['path'].endswith(('Outer.java','Api.java'))]
        subprocess.run([str(model_tests.JDK8/'bin/javac'),'-proc:none','-d',str(classes),*paths],check=True,capture_output=True,timeout=30)
        jar = self.root/'api.jar'
        subprocess.run([str(model_tests.JDK8/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True,timeout=30)
        shutil.rmtree(classes)
        facts = self.analyze([self.context('binary',{'Use.java':use},classpath=[str(jar)])])['facts']
        for key in keys:
            external = next(f for f in facts if f['kind']=='external_symbol' and f['signature']==key)
            self.assertEqual(external['attributes']['binary_member']['kind'],'METHOD')
            self.assertEqual(type_attributes(external)['binary_member']['type_model']['parameters'][0].get('owner',key),key)
        self.merged([],dict(facts=facts,replacements=[]))

    def test_annotation_constants_defaults_and_class_values(self):
        ctx = self.context('annotations', {'Use.java': '''import java.lang.annotation.*;
          @Retention(RetentionPolicy.RUNTIME) @interface Flag {
            String value() default "default"; Class<?> type() default String.class;
            int[] codes() default {1,2};
          }
          @Flag(value=Names.NAME) class Use {} class Names { static final String NAME="chosen"; }
        '''})
        facts = self.analyze([ctx])['facts']
        annotation = next(f for f in facts if f['relation'] == 'java.annotation'
                          and f['attributes']['resolved_type'] == 'Flag')
        self.assertEqual(annotation['attributes']['explicit']['value'], 'chosen')
        self.assertEqual(annotation['attributes']['effective']['type']['name'], 'java.lang.String')
        self.assertEqual(annotation['attributes']['effective']['codes']['values'], [1, 2])
        self.assertEqual(annotation['attributes']['retention'], 'RUNTIME')
        method = next(f for f in facts if f['signature'] == 'Flag#value()')
        self.assertEqual(method['attributes']['annotation_default'], 'default')

    def test_generic_and_inner_binary_signatures(self):
        ctx = self.context('types', {'Outer.java': '''import java.util.*;
          class Outer<T extends Number & Comparable<T>> {
            class Inner<U> { List<? extends T> values;
              <V extends U> V echo(V value) { return value; }
            }
          }
        '''})
        facts = self.analyze([ctx])['facts']
        outer = next(f for f in facts if f['signature'] == 'Outer')
        inner = next(f for f in facts if f['signature'] == 'Outer$Inner')
        self.assertEqual(outer['attributes']['jvm_signature'],
                         '<T:Ljava/lang/Number;:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;')
        self.assertEqual(inner['attributes']['nesting_kind'], 'MEMBER')
        field = next(f for f in facts if f['signature'] == 'Outer$Inner#values')
        self.assertEqual(field['attributes']['jvm_descriptor'], 'Ljava/util/List;')
        self.assertEqual(field['attributes']['jvm_signature'], 'Ljava/util/List<+TT;>;')
        echo = next(f for f in facts if f['name'] == 'echo' and f['kind'] == 'declaration')
        self.assertEqual(echo['attributes']['jvm_signature'], '<V:TU;>(TV;)TV;')

    def test_syntax_roles_and_symbols_preserve_dataflow_shapes(self):
        ctx = self.context('roles', {'Use.java': '\n'.join([
            'class Use { int value; void run(int input) {',
            'String text="before"; if (input>0) { text="yes"; } else { text="no"; }',
            'for (int i=0; i<input; i++) { value+=i; }',
            'try { System.out.println(text); } catch (RuntimeException problem) { throw problem; }',
            'finally { value=0; } int[] array=new int[]{1,2}; } }'])})
        facts = self.analyze([ctx])['facts']
        nodes = next(f for f in facts if f['relation'] == 'java.syntax')['attributes']['nodes']
        by_id = {n['id']: n for n in nodes}
        for node in nodes:
            for role in node['roles'].values():
                children = role if isinstance(role, list) else [role]
                self.assertTrue(all(child in by_id for child in children), node)
        method = next(n for n in nodes if n['kind'] == 'METHOD' and n.get('name') == 'run')
        self.assertEqual(method['symbol'], 'Use#run(int)')
        body = by_id[method['roles']['body']]
        self.assertEqual(body['kind'], 'BLOCK')
        text = next(n for n in nodes if n['kind'] == 'VARIABLE' and n.get('name') == 'text')
        self.assertEqual(by_id[text['roles']['initializer']]['value'], 'before')
        branch = next(n for n in nodes if n['kind'] == 'IF')
        self.assertEqual({by_id[branch['roles'][r]]['kind'] for r in ('then', 'else')}, {'BLOCK'})
        call = next(n for n in nodes if n['kind'] == 'METHOD_INVOCATION'
                    and n.get('symbol') == 'java.io.PrintStream#println(java.lang.String)')
        selected = by_id[call['roles']['selection']]
        self.assertEqual(selected['symbol'], call['symbol'])
        self.assertEqual(by_id[call['roles']['arguments'][0]]['symbol'], text['symbol'])
        attempt = next(n for n in nodes if n['kind'] == 'TRY')
        self.assertEqual(by_id[attempt['roles']['finally']]['kind'], 'BLOCK')
        self.assertEqual(by_id[attempt['roles']['catches'][0]]['kind'], 'CATCH')
        array = next(n for n in nodes if n['kind'] == 'NEW_ARRAY')
        self.assertEqual([by_id[i]['value'] for i in array['roles']['initializers']], [1,2])

    def test_syntax_parent_graph_and_incremental_equivalence(self):
        ctx = self.context('syntax', {'Use.java': 'class Use { int value; void run(){ value++; } }'})
        first = self.analyze([ctx])['facts']
        syntax = next(f for f in first if f['relation'] == 'java.syntax')['attributes']['nodes']
        by_id = {n['id']: n for n in syntax}
        self.assertEqual(len(by_id), len(syntax))
        self.assertTrue(all(n['parent'] == 0 or n['parent'] in by_id for n in syntax))
        self.assertTrue(any(n['kind'] == 'POSTFIX_INCREMENT' for n in syntax))
        changed = self.context('syntax', {'Use.java': 'class Use { int value; void run(){ value += 2; } }'})
        delta = self.analyze([changed], first)
        full = self.analyze([changed])
        self.assertEqual(self.merged(first, delta), sorted(full['facts'], key=lambda f: f['id']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
