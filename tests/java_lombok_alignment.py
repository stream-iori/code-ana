#!/usr/bin/env python3
"""Static Lombok declaration rules: modeled signatures never confirm compiler bindings."""
import sys
import unittest
import json
import hashlib
import os
import subprocess
import java_model_tests


class Lombok(java_model_tests.Models):
    def test_generated_policy_is_shared_only_within_context(self):
        cp=os.pathsep.join(str(java_model_tests.ROOT/'lang-adaptor/target'/part)
                           for part in ('classes','test-classes'))
        subprocess.run([str(java_model_tests.JDK25/'bin/java'),'-Xmx64m','-cp',cp,
                        'dev.codeana.analysis.LombokPolicyProbe'],check=True,timeout=30)

    def test_generated_signature_type_references_preserve_occurrences_and_delta(self):
        source='''class Api {} class Box<T> {}
        @lombok.Getter @lombok.Setter class Holder {
          Api api; Api[] many; Box<Box<Api>> nested; Box<? extends Api> wildcard;
        }'''
        context=self.context('app',self.files(source),lombok='model')
        facts=self.analyze([context])['facts'];by_id={f['id']:f for f in facts}
        def references(signature):
            owner=self.declaration(facts,signature)
            edges=[f for f in facts if f['kind']=='reference' and f['relation']=='type_reference' and f['parent']==owner['id']]
            for edge in edges:
                self.assertEqual(edge['status'],'possible',edge)
                self.assertFalse(edge['evidence_complete']);self.assertFalse(edge['declaration_resolved'])
                self.assertIsNotNone(edge['span']);self.assertTrue(edge['attributes']['generated'])
            return [(by_id[e['target']]['signature'],e['attributes']['type_context']) for e in edges]
        self.assertEqual(references('Holder#getApi()'),[('Api','return_type')])
        self.assertEqual(references('Holder#setApi(Api)'),[('Api','parameter_type')])
        self.assertEqual(references('Holder#getMany()'),[('Api','return_type')])
        self.assertCountEqual(references('Holder#getNested()'),[('Box','return_type'),('Box','generic_arg'),('Api','generic_arg')])
        self.assertEqual(references('Holder#getWildcard()'),[('Box','return_type')])
        # A generated signature supplements the original field occurrence; it does not move it.
        api=self.declaration(facts,'Api');field=self.declaration(facts,'Holder#api')
        self.assertTrue([f for f in facts if f['kind']=='reference' and f['parent']==field['id'] and f['target']==api['id'] and f['span'] is not None])
        changed=self.context('app',self.files(source.replace('Api api;','Box<Api> api;')),lombok='model')
        delta=self.analyze([changed],facts);full=self.analyze([changed])['facts']
        self.assertEqual(self.merged(facts,delta),sorted(full,key=lambda f:f['id']))
        off=changed|dict(lombok='off')
        self.assertEqual(self.merged(full,self.analyze([off],full)),sorted(self.analyze([off])['facts'],key=lambda f:f['id']))

    def test_annotation_copy_coverage_uses_frozen_policy_instead_of_name_suffix(self):
        source = ('@lombok.Getter class Validation {@javax.validation.constraints.NotNull String name;} '
                  '@lombok.Getter class CopyCandidate {@org.jetbrains.annotations.NotNull String name;}')
        files = self.files(source) | {
            'javax/validation/constraints/NotNull.java': 'package javax.validation.constraints; import java.lang.annotation.*; @Target(ElementType.FIELD) public @interface NotNull {}',
            'org/jetbrains/annotations/NotNull.java': 'package org.jetbrains.annotations; import java.lang.annotation.*; @Target(ElementType.FIELD) public @interface NotNull {}',
        }
        context = self.controls(self.context('app', files, lombok='model'), {
            'app/lombok.config': 'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n',
        })
        facts = self.analyze([context])['facts']
        summary = lambda name: self.declaration(facts, name)['attributes']['model_extensions']['lombok-model-v1']['summary']
        self.assertEqual(summary('Validation')['coverage'], 'complete')
        self.assertEqual(summary('Validation')['partial_capabilities'], [])
        self.assertEqual(summary('CopyCandidate')['coverage'], 'partial')
        self.assertEqual(summary('CopyCandidate')['partial_capabilities'], ['annotation_propagation'])
        for owner in ('Validation', 'CopyCandidate'):
            getter = self.declaration(facts, owner + '#getName()')
            self.assertEqual(getter['origin'], 'lombok_model')
            self.assertFalse(getter['declaration_resolved'])

    def files(self, source):
        common = {
            'lombok/AccessLevel.java': 'package lombok; public enum AccessLevel { PUBLIC, PROTECTED, PRIVATE, PACKAGE, MODULE, NONE }',
            'lombok/Getter.java': 'package lombok; public @interface Getter { AccessLevel value() default AccessLevel.PUBLIC; boolean lazy() default false; }',
            'lombok/Setter.java': 'package lombok; public @interface Setter { AccessLevel value() default AccessLevel.PUBLIC; }',
            'lombok/Data.java': 'package lombok; public @interface Data { String staticConstructor() default ""; }',
            'lombok/Value.java': 'package lombok; public @interface Value { String staticConstructor() default ""; }',
            'lombok/NonNull.java': 'package lombok; public @interface NonNull {}',
            'lombok/ToString.java': 'package lombok; public @interface ToString {}',
            'lombok/EqualsAndHashCode.java': 'package lombok; public @interface EqualsAndHashCode {}',
            'lombok/Builder.java': 'package lombok; public @interface Builder {}',
            'lombok/experimental/Accessors.java': 'package lombok.experimental; public @interface Accessors { boolean fluent() default false; }',
            'lombok/experimental/NonFinal.java': 'package lombok.experimental; public @interface NonFinal {}',
            'lombok/experimental/PackagePrivate.java': 'package lombok.experimental; public @interface PackagePrivate {}',
            'lombok/experimental/UtilityClass.java': 'package lombok.experimental; public @interface UtilityClass {}',
            'lombok/extern/slf4j/Slf4j.java': 'package lombok.extern.slf4j; public @interface Slf4j {}',
            'lombok/extern/java/Log.java': 'package lombok.extern.java; public @interface Log {}',
            'App.java': source,
        }
        for name in ['NoArgsConstructor', 'RequiredArgsConstructor', 'AllArgsConstructor']:
            common[f'lombok/{name}.java'] = 'package lombok; public @interface '+name+' { AccessLevel access() default AccessLevel.PUBLIC; String staticName() default ""; boolean force() default false; }'
        return common

    def test_basic_capitalization_unicode_parameter_names_and_recovery_delta(self):
        source='''@lombok.Data class Names {
          String oAuthToken; String eCGenName; String iOpengwClient;
          String xContentSignature; String URL; String ǆelta;
        } class Use { String read(Names value) {
          value.setOAuthToken("token"); return value.getOAuthToken();
        } }'''
        context=self.context('app',self.files(source),lombok='model')
        facts=self.analyze([context])['facts']
        for field,stem in [('oAuthToken','OAuthToken'),('eCGenName','ECGenName'),
                           ('iOpengwClient','IOpengwClient'),('xContentSignature','XContentSignature'),
                           ('URL','URL'),('ǆelta','ǅelta')]:
            self.declaration(facts,f'Names#get{stem}()')
            setter=self.declaration(facts,f'Names#set{stem}(java.lang.String)')
            self.assertEqual(setter['attributes']['parameter_names'],[field])
            if field!=stem:
                self.assertFalse([f for f in facts if f['kind']=='declaration' and f['signature']==f'Names#get{field}()'])
        for method in ('equals','canEqual'):
            declaration=self.declaration(facts,f'Names#{method}(java.lang.Object)')
            self.assertEqual(declaration['attributes']['parameter_names'],['other'])
        getter=self.declaration(facts,'Names#getOAuthToken()')
        self.assertTrue([f for f in facts if f['kind']=='call_target' and f['target']==getter['id'] and f['status']=='possible'])
        updated=self.context('app',self.files(source.replace('String eCGenName;','String eCGenName; String aBCValue;')),lombok='model')
        delta=self.analyze([updated],facts);full=self.analyze([updated])['facts']
        self.assertEqual(self.merged(facts,delta),sorted(full,key=lambda f:f['id']))
        self.declaration(full,'Names#getABCValue()')

    def test_utility_class_explicit_static_members_private_constructor_and_raw_modifiers(self):
        context = self.context('app',self.files('''@lombok.experimental.UtilityClass class Util {
          static int count; static void run(){} }
          class Use { void call(){ Util.run(); new Util(); } }'''),lombok='model')
        context = self.controls(context,{'app/lombok.config':'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})
        facts = self.analyze([context])['facts']
        util = self.declaration(facts,'Util')
        self.assertNotIn('final',util['attributes']['modifiers'])
        self.assertIn('final',util['attributes']['model_extensions']['lombok-model-v1']['effective_modifiers'])
        constructor = self.declaration(facts,'Util#<init>()')
        self.assertEqual(constructor['attributes']['modifiers'],['private'])
        self.assertFalse(constructor['declaration_resolved'])
        self.assertFalse([f for f in facts if f['kind']=='call_target' and f['target']==constructor['id']])
        summary = next(f['attributes'] for f in facts if f['relation']=='java.lombok' and f['parent']==util['id'])
        self.assertIn('utility_class',summary['modeled_capabilities'])
        self.assertEqual(summary['coverage'],'complete')
        context['lombok_strict'] = True
        self.analyze([context])

    def test_utility_class_declines_instance_nested_explicit_ctor_and_unknown_policy(self):
        for source in ('''@lombok.experimental.UtilityClass class Util { int count; }''',
                       '''class Outer { @lombok.experimental.UtilityClass static class Util { static int count; } }''',
                       '''@lombok.experimental.UtilityClass class Util { private Util(){} static int count; }''',
                       '''@lombok.experimental.UtilityClass @lombok.Getter class Util { static int count; }'''):
            with self.subTest(source=source):
                context = self.context('app',self.files(source),lombok='model')
                context = self.controls(context,{'app/lombok.config':'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})
                facts = self.analyze([context])['facts']
                summaries = [f['attributes'] for f in facts if f['relation']=='java.lombok' and 'UtilityClass' in f['attributes'].get('detected_annotations',[])]
                self.assertTrue(summaries)
                self.assertTrue(all('utility_class' in f['partial_capabilities'] for f in summaries))
        context = self.context('app',self.files('@lombok.experimental.UtilityClass class Util { static int count; }'),lombok='model')
        facts = self.analyze([context])['facts']
        self.assertFalse([f for f in facts if f['signature']=='Util#<init>()' and f['origin']=='lombok_model'])

    def result(self, source, extra=None):
        files = self.files(source) | (extra or {})
        result = self.analyze([self.context('app', files, lombok='model')])
        self.merged([], dict(facts=result['facts'], replacements=[]))
        self.assertFalse([f for f in result['facts'] if f['kind'] == 'diagnostic'
                          and f['attributes'].get('code') == 'EXTENSION_CALLBACK_FAILED'])
        return result['facts']

    def controls(self, context, layers):
        resources = []
        for index, (path, content) in enumerate(layers.items()):
            captured = self.root/'controls'/str(index)
            captured.parent.mkdir(exist_ok=True)
            captured.write_text(content)
            resources.append(dict(unit=dict(path=path, world='files', scope='JAVA_CONFIG', hash=hashlib.sha256(captured.read_bytes()).hexdigest()), input=str(captured)))
        context['resources'] = resources
        context['digest'] = hashlib.sha256((context['digest']+json.dumps(layers,sort_keys=True)).encode()).hexdigest()
        return context

    def test_configuration_ancestors_nearest_override_stop_bubbling_and_drift(self):
        context = self.context('app', self.files('@lombok.Getter class A { boolean ready; }'), lombok='model')
        context = self.controls(context, {
            '.code-ana/lombok-ancestors/000001/lombok.config': 'lombok.getter.noIsPrefix=false\nlombok.getter.unsupported=true\n',
            'lombok.config': 'lombok.getter.noIsPrefix=false\n',
            'app/lombok.config': 'config.stopBubbling=true\nlombok.getter.noIsPrefix=true\nlombok.addLombokGeneratedAnnotation=false\n',
        })
        facts = self.analyze([context])['facts']
        self.declaration(facts, 'A#getReady()')
        self.assertFalse([f for f in facts if f['signature']=='A#isReady()'])
        summary = next(f['attributes'] for f in facts if f['relation']=='java.lombok' and f['parent']==self.declaration(facts,'A')['id'])
        self.assertEqual(summary['coverage'], 'complete')
        self.assertEqual([row[0] for row in summary['configuration_inputs']], ['app/lombok.config'])
        context['lombok_strict'] = True
        self.analyze([context])
        captured = next(r['input'] for r in context['resources'] if r['unit']['path']=='app/lombok.config')
        import pathlib
        pathlib.Path(captured).write_text('changed without refreshing capture')
        with self.assertRaisesRegex(AssertionError, 'LOMBOK_CONFIG_INPUT_DRIFT'):
            self.analyze([context])

    def test_configuration_logger_names_static_and_generated_annotation_facets(self):
        context = self.context('app', self.files('@lombok.Getter @lombok.extern.java.Log class A { @Deprecated @lombok.NonNull String name; }'), lombok='model')
        context = self.controls(context, {'app/lombok.config': '''config.stopBubbling=true
          lombok.log.fieldName=LOGGER
          lombok.log.fieldIsStatic=false
          lombok.addLombokGeneratedAnnotation=true
          lombok.addSuppressWarnings=false
          lombok.addJavaxGeneratedAnnotation=true
        '''})
        facts = self.analyze([context])['facts']
        logger = self.declaration(facts, 'A#LOGGER')
        self.assertEqual(logger['attributes']['modifiers'], ['private','final'])
        getter = self.declaration(facts, 'A#getName()')
        annotations = {a['type'] for a in getter['attributes']['annotations']}
        self.assertEqual(annotations, {'lombok.Generated','javax.annotation.Generated','java.lang.Deprecated','lombok.NonNull'})
        facets = [f for f in facts if f['relation']=='java.annotation' and f['parent']==getter['id']]
        self.assertEqual({f['attributes']['resolved_type'] for f in facets}, annotations)
        self.assertTrue(all(f['status']=='modeled' and not f['declaration_resolved'] and f['span'] is None for f in facets))

    def test_unknown_configuration_degrades_affected_capability_and_delta_cleanup(self):
        files = self.files('@lombok.Data class A { boolean ready; }')
        context = self.controls(self.context('app', files, lombok='model'), {'app/lombok.config':'lombok.addLombokGeneratedAnnotation=false\n'})
        old = self.analyze([context])['facts']
        context = self.controls(self.context('app', files, lombok='model'), {'app/lombok.config':'lombok.addLombokGeneratedAnnotation=false\nlombok.setter.unsupported=true\n'})
        delta = self.analyze([context], old)
        full = self.analyze([context])['facts']
        self.assertEqual(self.merged(old,delta), sorted(full,key=lambda f:f['id']))
        self.declaration(full,'A#isReady()')
        self.assertFalse([f for f in full if f['signature']=='A#setReady(boolean)'])
        summary = next(f['attributes'] for f in full if f['relation']=='java.lombok' and f['parent']==self.declaration(full,'A')['id'])
        self.assertIn('setter', summary['partial_capabilities'])
        context['lombok_strict'] = True
        with self.assertRaisesRegex(AssertionError, 'LOMBOK_STRICT_INCOMPLETE'):
            self.analyze([context])
        deleted = self.context('app',files,lombok='model')
        delta = self.analyze([deleted], old)
        self.assertEqual(self.merged(old,delta), sorted(self.analyze([deleted])['facts'],key=lambda f:f['id']))

    @staticmethod
    def declaration(facts, signature):
        return next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == signature)

    def test_access_static_boolean_names_and_arity_conflicts(self):
        facts = self.result('''@lombok.Data class A {
          @lombok.Getter(lombok.AccessLevel.PRIVATE) String secret;
          @lombok.Setter(lombok.AccessLevel.NONE) int readOnly;
          @lombok.Getter static int count;
          static int skipped;
          boolean isReady;
          Boolean boxed;
          int $hidden;
          String x; Object getX(){return null;}
          int y; void setY(String incompatible){}
        }''')
        get = self.declaration(facts, 'A#getSecret()')
        self.assertEqual(get['attributes']['modifiers'], ['private'])
        self.assertFalse(get['declaration_resolved'])
        self.assertIsNone(get['span'])
        self.assertIn('static', self.declaration(facts, 'A#getCount()')['attributes']['modifiers'])
        self.declaration(facts, 'A#isReady()')
        self.declaration(facts, 'A#setReady(boolean)')
        self.declaration(facts, 'A#getBoxed()')
        signatures = {f['signature'] for f in facts if f['origin'] == 'lombok_model'}
        for absent in ['A#setReadOnly(int)', 'A#getSkipped()', 'A#get$hidden()', 'A#setY(int)', 'A#getX()']:
            self.assertNotIn(absent, signatures)

    def test_constructor_rules_initializer_and_explicit_policy(self):
        facts = self.result('''@lombok.RequiredArgsConstructor class Required {
          final String first; final int already=1; @lombok.NonNull Integer second;
          @lombok.NonNull String assigned="x"; static final int stat=1; int other;
        }
        @lombok.AllArgsConstructor(access=lombok.AccessLevel.PROTECTED) class All {
          final String first; final int already=1; String assigned="x"; int other;
        }
        @lombok.Data class Explicit { final int x; Explicit(int x){this.x=x;} }
        @lombok.NoArgsConstructor(force=true,access=lombok.AccessLevel.PRIVATE) class Forced { final int x; }
        class Use { Object build(){ return new Forced(); } }''')
        ctor = self.declaration(facts, 'Required#<init>(java.lang.String,java.lang.Integer)')
        self.assertEqual(ctor['origin'], 'lombok_model')
        self.assertEqual(self.declaration(facts, 'All#<init>(java.lang.String,java.lang.String,int)')['attributes']['modifiers'], ['protected'])
        self.assertEqual(self.declaration(facts, 'Explicit#<init>(int)')['origin'], 'javac')
        self.assertEqual(self.declaration(facts, 'Forced#<init>()')['attributes']['modifiers'], ['private'])
        signatures = {f['signature'] for f in facts if f['kind'] == 'declaration'}
        self.assertNotIn('Required#<init>()', signatures)
        self.assertNotIn('All#<init>()', signatures)
        self.assertNotIn('Explicit#<init>()', signatures)
        forced = self.declaration(facts, 'Forced#<init>()')
        self.assertFalse([f for f in facts if f['kind'] == 'call_target' and f['target'] == forced['id'] and f['declaration_resolved']])

    def test_common_signatures_and_value_effective_modifiers(self):
        facts = self.result('''@lombok.Data class Data { int x; }
          @lombok.Value class Value { String name; @lombok.experimental.NonFinal int counter;
            @lombok.experimental.PackagePrivate int pkg; }
          @lombok.EqualsAndHashCode @lombok.ToString class Existing {
            public int hashCode(){return 1;} int x;
          }''')
        for sig in ['Data#equals(java.lang.Object)', 'Data#hashCode()', 'Data#canEqual(java.lang.Object)',
                    'Data#toString()', 'Value#equals(java.lang.Object)', 'Value#toString()', 'Value#<init>(java.lang.String,int,int)']:
            self.declaration(facts, sig)
        for sig in ['Existing#equals(java.lang.Object)', 'Value#canEqual(java.lang.Object)', 'Value#setCounter(int)']:
            self.assertFalse([f for f in facts if f['kind'] == 'declaration' and f['signature'] == sig])
        owner = self.declaration(facts, 'Value')
        self.assertNotIn('final', owner['attributes']['modifiers'])
        self.assertIn('final', owner['attributes']['model_extensions']['lombok-model-v1']['effective_modifiers'])
        field = self.declaration(facts, 'Value#name')
        self.assertEqual(field['attributes']['model_extensions']['lombok-model-v1']['effective_modifiers'], ['final', 'private'])
        self.assertNotIn('final', self.declaration(facts, 'Value#counter')['attributes']['model_extensions']['lombok-model-v1']['effective_modifiers'])

    def test_explicit_options_are_distinct_from_annotation_defaults(self):
        annotations = {
            'lombok/EqualsAndHashCode.java': '''package lombok; public @interface EqualsAndHashCode {
              boolean callSuper() default false; boolean onlyExplicitlyIncluded() default false;
              Deprecated[] onParam() default {}; }''',
            'lombok/ToString.java': '''package lombok; public @interface ToString {
              boolean onlyExplicitlyIncluded() default false; }''',
        }
        facts = self.result('''class Base {}
          @lombok.Data @lombok.EqualsAndHashCode(callSuper=true) @lombok.ToString
          class A extends Base { int number; }''', annotations)
        for name in ('equals(java.lang.Object)', 'hashCode()', 'canEqual(java.lang.Object)', 'toString()'):
            self.declaration(facts, 'A#'+name)
        facts = self.result('''@lombok.EqualsAndHashCode(onParam={}) class A { int number; }''', annotations)
        self.assertFalse([f for f in facts if f['kind']=='declaration' and f['origin']=='lombok_model'])
        summary = next(f['attributes'] for f in facts if f['relation']=='java.lombok'
                       and f['parent']==self.declaration(facts, 'A')['id'])
        self.assertIn('equals_hash_code', summary['partial_capabilities'])

    def test_logger_availability_and_partial_options(self):
        facts = self.result('''@lombok.extern.slf4j.Slf4j class Logged {}
          @lombok.extern.java.Log class JdkLog {}
          @lombok.NoArgsConstructor class NoForce { final int x; }
          @lombok.AllArgsConstructor(staticName="create") class Factory { int x; }
          @lombok.Getter(lazy=true) class Lazy { final String text="x"; }
          @lombok.Getter @lombok.experimental.Accessors(fluent=true) class Fluent { int x; }
          @lombok.Builder class Built { int x; }''', {'org/slf4j/Logger.java': 'package org.slf4j; public interface Logger {}'})
        for name in ['Logged', 'JdkLog']:
            log = self.declaration(facts, name+'#log')
            self.assertEqual(log['attributes']['modifiers'], ['private', 'final', 'static'])
        for sig in ['Lazy#getText()', 'Fluent#getX()', 'Factory#<init>(int)']:
            self.assertFalse([f for f in facts if f['kind'] == 'declaration' and f['signature'] == sig])
        summaries = {f['parent']: f['attributes'] for f in facts if f['relation'] == 'java.lombok' and 'partial_capabilities' in f['attributes']}
        for name, capability in [('NoForce', 'no_args_constructor'), ('Factory', 'all_args_constructor'), ('Lazy', 'getter'), ('Fluent', 'getter')]:
            self.assertIn(capability, summaries[self.declaration(facts, name)['id']]['partial_capabilities'])
        missing = self.result('@lombok.extern.slf4j.Slf4j class Logged {}')
        self.assertFalse([f for f in missing if f['signature'] == 'Logged#log'])

    def test_unsupported_field_policy_does_not_suppress_other_accessors(self):
        facts = self.result('''@lombok.Data class A {
          @lombok.Getter(lazy=true) final String uncertain="x";
          String other;
          @lombok.Setter(lombok.AccessLevel.PROTECTED) static boolean shared;
        }''')
        self.declaration(facts, 'A#getOther()')
        self.declaration(facts, 'A#setOther(java.lang.String)')
        self.assertEqual(self.declaration(facts,'A#setShared(boolean)')['attributes']['modifiers'], ['protected','static'])
        self.assertFalse([f for f in facts if f['signature']=='A#getUncertain()'])

    def test_generic_erasure_incomplete_type_enum_and_constructor_collision(self):
        facts = self.result('''@lombok.Getter @lombok.Setter class Generic<T extends Number & Comparable<T>> {
            T value; java.util.List<Missing> unavailable;
          }
          @lombok.AllArgsConstructor enum Choice { A; int value; }
          @lombok.NoArgsConstructor class Collision { Collision(){} }
        ''')
        self.declaration(facts, 'Generic#getValue()')
        self.declaration(facts, 'Generic#setValue(java.lang.Number)')
        self.assertFalse([f for f in facts if f['signature']=='Generic#getUnavailable()'])
        constructor = self.declaration(facts, 'Choice#<init>(int)')
        self.assertEqual(constructor['attributes']['modifiers'], ['private'])
        source = self.declaration(facts, 'Collision#<init>()')
        self.assertTrue(source['declaration_resolved'])
        self.assertEqual(source['origin'], 'javac')
        summary = next(f['attributes'] for f in facts if f['relation']=='java.lombok'
                       and f['parent']==self.declaration(facts,'Collision')['id'])
        self.assertIn('no_args_constructor',summary['partial_capabilities'])

    def test_declaration_delta_full_and_disable(self):
        source = '@lombok.Data class A { int x; String text; String read(){return getText();} }'
        files = self.files(source)
        old = self.analyze([self.context('app', files, lombok='model')])['facts']
        files['App.java'] = source.replace('String text;', '@lombok.Getter(lombok.AccessLevel.NONE) String text;')
        changed = self.context('app', files, lombok='model')
        delta = self.analyze([changed], old)
        self.assertEqual(self.merged(old, delta), sorted(self.analyze([changed])['facts'], key=lambda f: f['id']))
        disabled = self.context('app', files, lombok='off')
        delta = self.analyze([disabled], old)
        full = self.analyze([disabled])['facts']
        self.assertEqual(self.merged(old, delta), sorted(full, key=lambda f: f['id']))
        self.assertFalse([f for f in full if f['origin'] == 'lombok_model'])


if __name__ == '__main__':
    # Do not inherit and rerun the base suite.
    names = [n for n in Lombok.__dict__ if n.startswith('test_')]
    suite = unittest.TestSuite(Lombok(n) for n in names)
    sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
