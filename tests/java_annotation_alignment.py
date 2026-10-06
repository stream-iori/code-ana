#!/usr/bin/env python3
"""Independent source/binary effective annotation and composed Spring checks."""
import subprocess
import unittest
import java_model_tests as model_tests

COMPONENT = 'package org.springframework.stereotype; import java.lang.annotation.*; @Target({ElementType.TYPE,ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME) public @interface Component { String value() default ""; }'
ALIAS = 'package org.springframework.core.annotation; import java.lang.annotation.*; @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) public @interface AliasFor { String value() default ""; String attribute() default ""; Class<? extends Annotation> annotation() default Annotation.class; }'
BASE = {'org/springframework/stereotype/Component.java': COMPONENT,
        'org/springframework/core/annotation/AliasFor.java': ALIAS}
FANCY = '''import java.lang.annotation.*;
@org.springframework.stereotype.Component @Retention(RetentionPolicy.RUNTIME) @interface Fancy {
 @org.springframework.core.annotation.AliasFor(annotation=org.springframework.stereotype.Component.class,attribute="value")
 String name() default "";
}'''


class Annotations(unittest.TestCase):
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze
    merged = model_tests.Models.merged

    def test_meta_source_spans_survive_file_offset_cache_eviction(self):
        files={}
        for i in range(6):
            files['Meta'+str(i)+'.java']='// 原字节😀\r\npackage p; import java.lang.annotation.*; @Deprecated @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @interface Ann'+str(i)+' {}'
            files['Use'+str(i)+'.java']='package p; @Ann'+str(i)+' class Use'+str(i)+' {}'
        facts=self.analyze([self.context('app',files)])['facts']
        origins={f['attributes']['source_origin']['path']:f['attributes']['source_origin']
                 for f in facts if f['relation']=='java.annotation.meta'
                 and f['attributes']['annotation_fqn'].startswith('p.Ann')}
        for i in range(6):
            path='app/Meta'+str(i)+'.java';origin=origins[path]
            self.assertEqual(origin['hash'],model_tests.sha(files['Meta'+str(i)+'.java'].encode()))
            span=origin['span'];text=files['Meta'+str(i)+'.java'].encode()[span['start']:span['end']].decode()
            self.assertIn('@interface Ann'+str(i),text)

    def beans(self, facts):
        return [f for f in facts if f['relation'] == 'java.bean']

    def test_meta_graph_cycles_source_and_binary_evidence_survive_delta_and_removal(self):
        source='''import java.lang.annotation.*;
          @Target({ElementType.TYPE,ElementType.ANNOTATION_TYPE}) @B @interface A {}
          @Target({ElementType.TYPE,ElementType.ANNOTATION_TYPE}) @A @interface B {}
          @A class One {int body=1;} @A class Two {} @Deprecated class Old {}'''
        def verify(facts):
            meta=[f for f in facts if f['relation']=='java.annotation.meta']
            self.assertTrue(meta)
            keys=[(f['source'],f['attributes']['annotation_fqn'],f['attributes']['meta_annotation_fqn'],
                   f['attributes']['source_origin']['path']) for f in meta]
            self.assertEqual(len(keys),len(set(keys)))
            self.assertIn(('app/Use.java','A','B','app/Use.java'),keys)
            self.assertIn(('app/Use.java','B','A','app/Use.java'),keys)
            self.assertIn(('app/Use.java','java.lang.Deprecated','java.lang.annotation.Documented','app/Use.java'),keys)
            origin=next(f['attributes']['source_origin'] for f in meta if f['attributes']['annotation_fqn']=='A')
            self.assertEqual(origin['hash'],model_tests.sha(source.encode()))
            self.assertEqual(source.encode()[origin['span']['start']:origin['span']['end']].decode(),
                '@Target({ElementType.TYPE,ElementType.ANNOTATION_TYPE}) @B @interface A {}')
            self.assertFalse([f for f in facts if f['name']=='ANNOTATION_META_INCOMPLETE'])
            return meta
        context=self.context('app',{'Use.java':source})
        first=self.analyze([context],cache=True)['facts'];verify(first)
        changed=self.context('app',{'Use.java':source.replace('body=1','body=2')})
        full=self.analyze([changed],cache=True)['facts']
        self.assertEqual(self.merged(first,self.analyze([changed],first,cache=True)),sorted(full,key=lambda f:f['id']))
        changed=self.context('app',{'Use.java':'class One {}'})
        full=self.analyze([changed],cache=True)['facts']
        self.assertFalse([f for f in full if f['relation']=='java.annotation.meta'])
        self.assertEqual(self.merged(first,self.analyze([changed],first,cache=True)),sorted(full,key=lambda f:f['id']))

    def test_meta_depth_budget_rolls_back_root_marks_partial_and_keeps_healthy_file(self):
        source='\n'.join(('@A'+str(i+1)+' ' if i<18 else '')+'@interface A'+str(i)+' {}' for i in range(19))
        files={'Meta.java':source,'Broken.java':'@A0 class Broken {}','Healthy.java':'@Deprecated class Healthy {}'}
        facts=self.analyze([self.context('app',files)])['facts']
        gaps=[f for f in facts if f['name']=='ANNOTATION_META_INCOMPLETE']
        self.assertTrue(gaps)
        self.assertTrue(any(f['source']=='app/Broken.java' for f in gaps))
        coverage={f['source']:f['status'] for f in facts if f['kind']=='coverage'}
        self.assertEqual(coverage['app/Broken.java'],'partial')
        self.assertEqual(coverage['app/Healthy.java'],'complete')
        self.assertFalse([f for f in facts if f['relation']=='java.annotation.meta' and f['source']=='app/Broken.java'])
        self.assertTrue([f for f in facts if f['relation']=='java.annotation.meta' and f['source']=='app/Healthy.java'])

    def test_every_annotation_occurrence_keeps_original_arguments_including_nested_type_use(self):
        source = '''import java.lang.annotation.*;
          @Target({ElementType.TYPE,ElementType.TYPE_USE}) @interface Note {
            String[] value(); String name() default "default";
          }
          @Note(name="中文😀",value={ "one", /* 原字节 */ "two" }) class Use {
            void read(java.util.List<@Note(value = { "inner" }) String> values){}
          }'''
        context=self.context('app',{'Use.java':source})
        first=self.analyze([context],cache=True)['facts']
        annotations=[f['attributes'] for f in first if f['relation']=='java.annotation'
                     and f['attributes']['resolved_type']=='Note']
        self.assertEqual(len(annotations),2)
        self.assertTrue(all(a['written_attributes_complete'] for a in annotations))
        self.assertTrue(all(a['written_values_complete'] for a in annotations))
        self.assertCountEqual([a['written_attributes'] for a in annotations],
            [dict(name='"中文😀"',value='{ "one", /* 原字节 */ "two" }'),dict(value='{ "inner" }')])
        self.assertCountEqual([a['written_values'] for a in annotations],
            [dict(name='中文😀',value=['one','two']),dict(value=['inner'])])
        changed=self.context('app',{'Use.java':source.replace('"inner"','"changed"')})
        full=self.analyze([changed])['facts']
        self.assertEqual(self.merged(first,self.analyze([changed],first,cache=True)),sorted(full,key=lambda f:f['id']))

    def test_duplicate_written_annotation_attribute_discloses_incomplete_source_values(self):
        source='@interface Note {String value();} @Note(value="first",value="second") class Broken {} class Healthy {}'
        facts=self.analyze([self.context('app',{'Use.java':source})])['facts']
        annotation=next(f for f in facts if f['relation']=='java.annotation' and f['attributes']['resolved_type']=='Note')
        self.assertFalse(annotation['attributes']['written_attributes_complete'])
        self.assertFalse(annotation['attributes']['written_values_complete'])
        self.assertTrue(any(f['kind']=='diagnostic' for f in facts))
        self.assertTrue(any(f['signature']=='Healthy' and f['kind']=='declaration' for f in facts))

    def test_written_annotation_values_keep_nonliteral_expressions_and_literal_precision(self):
        source='''@interface Note {String text();char ch();float fraction();boolean enabled();int count();Class<?> type();}
          class Constants {static final int COUNT=10;}
          @\\u004eote(text="\\u4e2d\\n",ch='x',fraction=0.1F,enabled=true,count=Constants.COUNT,type=String.class) class Use {}'''
        facts=self.analyze([self.context('app',{'Use.java':source})])['facts']
        annotation=next(f for f in facts if f['relation']=='java.annotation' and f['attributes']['resolved_type']=='Note')
        a=annotation['attributes']
        self.assertEqual(a['written_values'],dict(text='中\n',ch='x',fraction=0.1,enabled=True,count='Constants.COUNT',type='String.class'))
        self.assertEqual(a['written_attributes']['fraction'],'0.1F')
        self.assertEqual(a['written_name'],'\\u004eote')
        self.assertEqual(a['source_name'],'Note')
        self.assertEqual(a['explicit']['count'],10)

    def test_repeated_irrelevant_meta_queries_preserve_late_injection_and_alias_values(self):
        definitions = '\n'.join(
            ('@Noise'+str(i+1)+' ' if i < 11 else '')
            + '@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,java.lang.annotation.ElementType.FIELD,java.lang.annotation.ElementType.ANNOTATION_TYPE}) '
            + '@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) '
            + '@interface Noise'+str(i)+' {}' for i in range(12))
        pads = '\n'.join('@Noise0 class Pad'+str(i)+' { @Noise0 int a,b,c,d,e; }' for i in range(500))
        autowired = 'package org.springframework.beans.factory.annotation; public @interface Autowired { boolean required() default true; }'
        files = BASE | {'Noise.java':definitions, 'Pads.java':pads, 'Fancy.java':FANCY,
            'org/springframework/beans/factory/annotation/Autowired.java':autowired,
            'Late.java':'class Dependency {} @Fancy(name="one") class One {} @Fancy(name="two") class Two {} @org.springframework.stereotype.Component("late") class Late { @org.springframework.beans.factory.annotation.Autowired Late(Dependency dep){} }'}
        context = self.context('repeated', files, spring=True, sofa=True,
                               model_record_limit=1000000, model_archive_bytes=536870912)
        facts = self.analyze([context])['facts']
        self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
        self.assertFalse([f for f in facts if f['kind']=='coverage'
                          and f['attributes'].get('reason')=='ANNOTATION_QUERY_BUDGET'])
        self.assertTrue({'one','two','late'}.issubset({f['name'] for f in self.beans(facts)}))
        self.assertTrue(any(f['relation']=='java.spring.injection'
                            and f['source']=='repeated/Late.java'
                            and f['attributes'].get('role')=='constructor'
                            and model_tests.type_node(f['attributes'],f['attributes']['injected_type']).get('element')=='Dependency'
                            for f in facts))

    def test_confirmed_constant_component_name(self):
        files = BASE | {'Use.java': 'class Names { static final String NAME="confirmed"; } @org.springframework.stereotype.Component(Names.NAME) class Use {}'}
        beans = self.beans(self.analyze([self.context('app', files, spring=True)])['facts'])
        self.assertEqual([b['name'] for b in beans], ['confirmed'])
        self.assertEqual(beans[0]['attributes']['annotation_evidence']['attribute_status'], 'static')

    def test_composed_cross_annotation_alias_and_cache_equality(self):
        files = BASE | {'Fancy.java': FANCY, 'Use.java': '@Fancy(name="aliased") class Use { void run(){System.out.println("before");} }'}
        first = self.analyze([self.context('app', files, spring=True)], cache=True)['facts']
        bean = self.beans(first)
        self.assertEqual([b['name'] for b in bean], ['aliased'])
        self.assertEqual(bean[0]['attributes']['annotation_evidence']['meta_path'], ['Fancy','org.springframework.stereotype.Component'])
        files['Use.java'] = files['Use.java'].replace('before', 'after')
        context = self.context('app', files, spring=True)
        delta = self.analyze([context], first, cache=True)
        full = self.analyze([context])
        self.assertEqual(self.merged(first, delta), sorted(full['facts'], key=lambda f: f['id']))

    def test_binary_meta_annotation_alias(self):
        definitions = self.context('definitions', BASE | {'Fancy.java': FANCY})
        classes = self.root / 'binary-classes'; classes.mkdir()
        subprocess.run([str(model_tests.JDK8 / 'bin/javac'), '-proc:none', '-d', str(classes)] +
                       [s['input'] for s in definitions['sources']], check=True, capture_output=True)
        archive = self.root / 'annotations.jar'
        subprocess.run([str(model_tests.JDK8 / 'bin/jar'), 'cf', str(archive), '-C', str(classes), '.'], check=True)
        # Classfile generation is explicitly test-owned; analysis must generate no classes.
        import shutil
        shutil.rmtree(classes)
        context = self.context('app', {'Use.java':'@Fancy(name="binaryName") class Use {}'}, spring=True, classpath=[str(archive)])
        beans = self.beans(self.analyze([context])['facts'])
        self.assertEqual([b['name'] for b in beans], ['binaryName'])
        self.assertFalse(beans[0]['attributes']['runtime_confirmed'])

    def test_conflicting_aliases_remain_partial(self):
        bean = '''package org.springframework.context.annotation;
          import org.springframework.core.annotation.AliasFor; public @interface Bean {
           @AliasFor("value") String[] name() default {};
           @AliasFor("name") String[] value() default {};
          }'''
        files = BASE | {'org/springframework/context/annotation/Bean.java':bean,
                        'Use.java':'class Use { @org.springframework.context.annotation.Bean(name="one",value="two") String make(){return "";} }'}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        self.assertEqual(self.beans(facts), [])
        self.assertTrue(any(f['kind']=='coverage' and f['status']=='partial' and f['owner']['producer']=='spring-model-v1' for f in facts))

    def test_meta_cycle_and_runtime_placeholder(self):
        files = BASE | {'Use.java': '@B @interface A {} @A @interface B {} @A class Loop {} @org.springframework.stereotype.Component("${dynamic.name}") class Use {}'}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        self.assertEqual(self.beans(facts), [])
        self.assertTrue(any(f['kind']=='coverage' and f['status']=='partial' and f['owner']['producer']=='spring-model-v1' for f in facts))

    def test_alias_to_missing_member_is_not_a_confirmed_name(self):
        files = BASE | {'Fancy.java': FANCY.replace('attribute="value"', 'attribute="missing"'),
                        'Use.java': '@Fancy(name="unproven") class Use {}'}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        self.assertEqual(self.beans(facts), [])
        self.assertTrue(any(f['kind']=='coverage' and f['status']=='partial'
                            and f['owner']['producer']=='spring-model-v1' for f in facts))

    def test_nonconstant_annotation_value_does_not_publish_error_text_as_name(self):
        files = BASE | {'Use.java': 'class Names { static final String NAME=new String("runtime"); } @org.springframework.stereotype.Component(Names.NAME) class Use {}'}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        self.assertTrue(any(f['kind']=='diagnostic' for f in facts))
        self.assertEqual(self.beans(facts), [])

    def test_literal_error_text_is_a_valid_constant_name(self):
        files = BASE | {'Use.java': '@org.springframework.stereotype.Component("<error>") class Use {}'}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        self.assertEqual([b['name'] for b in self.beans(facts)], ['<error>'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
