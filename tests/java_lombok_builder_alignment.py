from java_type_views import type_attributes
import pathlib,os,sys,unittest,json
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'));import java_model_tests as models;import java_lombok_alignment as lombok
class Builders(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged;controls=lombok.Lombok.controls;declaration=staticmethod(lombok.Lombok.declaration)
 def fixture(self,source,extra=None):
  files=lombok.Lombok.files(self,source)
  files.update(extra or {})
  files['lombok/Builder.java']='package lombok;public @interface Builder { String builderClassName() default ""; String builderMethodName() default "builder"; String buildMethodName() default "build"; String setterPrefix() default ""; boolean toBuilder() default false; AccessLevel access() default AccessLevel.PUBLIC; public @interface Default {} public @interface ObtainVia {} }'
  # Empty builderClassName means Lombok's conventional name; normalize in the implementation.
  return self.controls(self.context('app',files,lombok='model'),{'app/lombok.config':'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})
 def test_builder_field_annotations_retain_source_projection_and_delta(self):
  extra={'validation/NotBlank.java':'''package validation; import java.lang.annotation.*;
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface NotBlank {String message() default "required";}'''}
  source='''@lombok.Builder class A {@validation.NotBlank(message="value required") String value;
    @Deprecated int count;}'''
  context=self.fixture(source,extra);facts=self.analyze([context])['facts']
  field=self.declaration(facts,'A$ABuilder#value')
  annotation=next(f for f in facts if f['relation']=='java.annotation' and f['parent']==field['id'] and f['attributes'].get('resolved_type')=='validation.NotBlank')
  self.assertEqual(annotation['origin'],'source_projection')
  self.assertFalse(annotation['evidence_complete'])
  self.assertEqual(annotation['attributes']['source_arguments'],['message = "value required"'])
  self.assertEqual(annotation['attributes']['annotation_origin'],'source_backed_generated_declaration')
  self.assertEqual(annotation['attributes']['generated_from'],'A#value')
  self.assertIsNotNone(annotation['span'])
  self.assertFalse([a for a in field['attributes']['annotations'] if a['type']=='validation.NotBlank'])
  updated=self.fixture(source.replace('value required','changed'),extra)
  delta=self.analyze([updated],facts);full=self.analyze([updated])['facts']
  self.assertEqual(self.merged(facts,delta),sorted(full,key=lambda f:f['id']))
 def test_unattributed_conditional_argument_uses_checked_branches_and_keeps_original_none(self):
  from java_type_views import type_attributes
  source='@lombok.Builder class Api {String path;} class Use {static String text(){return "x";} Api read(boolean flag){return Api.builder().path(flag ? text() : text()).build();}}'
  result=self.analyze([self.fixture(source)],cache=True);by={f['id']:f for f in result['facts']}
  targets=[(by[f['target']]['signature'],f['status']) for f in result['facts'] if f['kind']=='call_target']
  self.assertIn(('Api$ApiBuilder#path(java.lang.String)','possible'),targets)
  self.assertIn(('Api$ApiBuilder#build()','possible'),targets)
  path=next(f for f in result['facts'] if f['kind']=='call_site' and f['name'].endswith('.path'))
  self.assertEqual(type_attributes(path)['argument_type_models'][0]['kind'],'NONE')
  changed=self.fixture(source.replace('return "x"','return "after"'))
  delta=self.analyze([changed],result['facts'],cache=True);full=self.analyze([changed])
  self.assertEqual(self.merged(result['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
  incompatible=self.fixture(source.replace('flag ? text() : text()','flag ? text() : new Object()'))
  bad=self.analyze([incompatible]);by={f['id']:f for f in bad['facts']}
  self.assertFalse([f for f in bad['facts'] if f['kind']=='call_target' and by[f['target']]['signature']=='Api$ApiBuilder#path(java.lang.String)'])
  self.assertEqual(self.merged(full['facts'],self.analyze([incompatible],full['facts'],cache=True)),sorted(bad['facts'],key=lambda f:f['id']))
 def test_fields_builder_chain_and_original_constructor_replacement(self):
  ctx=self.fixture('@lombok.Builder class A { String name; int count; } class Use { A run(){return A.builder().name("x").count(1).build();} }')
  facts=self.analyze([ctx])['facts'];declarations={f['signature']:f for f in facts if f['kind']=='declaration'}
  for signature in ('A$ABuilder','A#builder()','A$ABuilder#name(java.lang.String)','A$ABuilder#count(int)','A$ABuilder#build()','A#<init>(java.lang.String,int)'):self.assertIn(signature,list(declarations))
  summary=next(type_attributes(f) for f in facts if f['relation']=='java.lombok' and f['parent']==declarations['A']['id'])
  self.assertIn('builder',summary['modeled_capabilities']);self.assertTrue(summary['constructor_replaced'])
  targets=[f for f in facts if f['kind']=='call_target' and f['target'] in {declarations[s]['id'] for s in ('A#builder()','A$ABuilder#name(java.lang.String)','A$ABuilder#count(int)','A$ABuilder#build()')}]
  self.assertEqual(len(targets),4);self.assertTrue(all(f['status']=='possible' for f in targets))
 def test_default_custom_prefix_access_and_to_builder(self):
  ctx=self.fixture('@lombok.Builder(builderClassName="Draft",builderMethodName="draft",buildMethodName="finish",setterPrefix="with",toBuilder=true,access=lombok.AccessLevel.PROTECTED) class A { @lombok.Builder.Default final int count=7; String name; }')
  facts=self.analyze([ctx])['facts']
  for signature in ('A$Draft','A#draft()','A#toBuilder()','A#$default$count()','A$Draft#count$value','A$Draft#count$set','A$Draft#withCount(int)','A$Draft#finish()'):self.declaration(facts,signature)
  annotations=[f for f in facts if f['relation']=='java.annotation' and f['attributes'].get('resolved_type') in ('lombok.Builder$Default','lombok.Builder.Default')]
  self.assertEqual([f['parent'] for f in annotations],[self.declaration(facts,'A#count')['id']])
 def test_generic_factory_field_build_type_owners(self):
  ctx=self.fixture('@lombok.Builder class A<T extends Number> { T value; }')
  facts=self.analyze([ctx])['facts'];builder=self.declaration(facts,'A$ABuilder');factory=self.declaration(facts,'A#builder()');setter=self.declaration(facts,'A$ABuilder#value(java.lang.Number)')
  self.assertEqual(type_attributes(setter)['parameter_type_models'][0]['owner'],'A$ABuilder')
  self.assertEqual(type_attributes(factory)['type_parameter_models'][0]['owner'],'A#builder()')
  self.assertEqual(type_attributes(factory)['return_type_model']['arguments'][0]['owner'],'A#builder()')
 def test_recursive_bounds_preserve_identity_in_generated_owners(self):
  ctx=self.fixture('@lombok.Builder class A<T extends Number & Comparable<T>> { T value; }')
  facts=self.analyze([ctx])['facts'];builder=self.declaration(facts,'A$ABuilder');factory=self.declaration(facts,'A#builder()');setter=self.declaration(facts,'A$ABuilder#value(java.lang.Number)')
  self.assertEqual(type_attributes(setter)['parameter_type_models'][0]['owner'],'A$ABuilder')
  bounds=type_attributes(builder)['type_parameter_models'][0]['bounds']
  self.assertEqual([b['element'] for b in bounds],['java.lang.Number','java.lang.Comparable'])
  self.assertEqual(type_attributes(factory)['return_type_model']['arguments'][0]['owner'],'A#builder()')
 def test_custom_builder_members_are_preserved_and_source_constructor_survives(self):
  ctx=self.fixture('@lombok.Builder class A {String value; A(int ignored){} static class ABuilder {private String value; public ABuilder value(String input){value=input;return this;} }}')
  facts=self.analyze([ctx])['facts'];setter=self.declaration(facts,'A$ABuilder#value(java.lang.String)');constructor=self.declaration(facts,'A#<init>(int)')
  self.assertEqual(setter['origin'],'javac');self.assertTrue(setter['declaration_resolved'])
  self.assertEqual(constructor['origin'],'javac');self.assertTrue(constructor['declaration_resolved'])
  self.declaration(facts,'A#<init>(java.lang.String)')
 def test_instance_method_target_and_builder_removal_delta(self):
  ctx=self.fixture('class A {@lombok.Builder String create(String value){return value;} }')
  first=self.analyze([ctx])['facts'];factory=self.declaration(first,'A#builder()')
  self.assertNotIn('static',factory['attributes']['modifiers'])
  ctx=self.fixture('class A {String create(String value){return value;} }')
  delta=self.analyze([ctx],first);full=self.analyze([ctx])['facts']
  self.assertEqual(self.merged(first,delta),sorted(full,key=lambda f:f['id']))
  self.assertFalse([f for f in full if f['kind']=='declaration' and f['attributes'].get('modeled_capability')=='builder'])
 def test_method_and_constructor_targets_and_custom_conflicts(self):
  for source in ('class A {String value;@lombok.Builder A(String value){this.value=value;}}','class A {@lombok.Builder static String create(String value){return value;}}'):
   facts=self.analyze([self.fixture(source)])['facts'];self.assertTrue(any(f['kind']=='declaration' and '$' in f['signature'] and f['attributes'].get('modeled_capability')=='builder' for f in facts))
 def test_nested_return_simple_name_nonnull_copy_and_generic_method_rejection(self):
  facts=self.analyze([self.fixture('class Outer {static class Inner{}} class A {@lombok.Builder static Outer.Inner create(String value){return null;} }')])['facts']
  self.declaration(facts,'A$InnerBuilder')
  facts=self.analyze([self.fixture('@lombok.Builder class A {@lombok.NonNull String name;}')])['facts']
  setter=self.declaration(facts,'A$ABuilder#name(java.lang.String)')
  self.assertTrue(any(a['type']=='lombok.NonNull' for a in setter['attributes']['parameter_annotations'][0]))
  facts=self.analyze([self.fixture('class A {@lombok.Builder static <T> T create(T value){return value;} }')])['facts']
  self.assertFalse([f for f in facts if f['kind']=='declaration' and f['attributes'].get('modeled_capability')=='builder'])
  self.assertTrue(any('builder' in f['attributes'].get('partial_capabilities',[]) for f in facts if f['relation']=='java.lombok'))
 def test_unsupported_singular_default_without_initializer_or_options(self):
  for source in ('@lombok.Builder class A {@lombok.Builder.Default int count;}','@lombok.Builder(builderMethodName="bad-name") class A {int count;}','@lombok.Builder abstract class A {int count;}'):
   facts=self.analyze([self.fixture(source)])['facts'];self.assertFalse([f for f in facts if f['kind']=='declaration' and f['signature']=='A$ABuilder'])
   self.assertTrue(any('builder' in f['attributes'].get('partial_capabilities',[]) for f in facts if f['relation']=='java.lombok'))
 def test_modifier_annotations_do_not_block_builder_or_pollute_primitive_identity(self):
  extra={'Constraint.java':'import java.lang.annotation.*; @Target({ElementType.FIELD,ElementType.TYPE_USE}) @Retention(RetentionPolicy.RUNTIME) @interface Constraint {String message() default "required";}'}
  ctx=self.fixture('@lombok.Builder @lombok.Data class A {@Constraint int count; @Constraint boolean ready;}',extra)
  facts=self.analyze([ctx])['facts']
  for signature in ('A$ABuilder','A#<init>(int,boolean)','A#setCount(int)','A#setReady(boolean)','A$ABuilder#count(int)','A$ABuilder#ready(boolean)'):
   declaration=self.declaration(facts,signature)
   for parameter in type_attributes(declaration).get('parameter_type_models',[]):
    self.assertEqual(parameter.get('annotations',[]),[])
  self.assertIn('Constraint',type_attributes(self.declaration(facts,'A#count'))['type_model']['annotations'])
  ctx=self.fixture('@lombok.Builder @lombok.Data class A {@Constraint int count; @Constraint boolean ready; int added;}',extra)
  delta=self.analyze([ctx],facts)
  self.assertEqual(self.merged(facts,delta),sorted(self.analyze([ctx])['facts'],key=lambda f:f['id']))
 def test_written_array_and_generic_annotations_survive_generated_types(self):
  extra={'Mark.java':'import java.lang.annotation.*; @Target(ElementType.TYPE_USE) @interface Mark {}'}
  ctx=self.fixture('@lombok.Builder class A {String @Mark [] names; java.util.List<@Mark String> values;}',extra)
  facts=self.analyze([ctx])['facts']
  names=type_attributes(self.declaration(facts,'A$ABuilder#names(java.lang.String[])'))['parameter_type_models'][0]
  values=type_attributes(self.declaration(facts,'A$ABuilder#values(java.util.List)'))['parameter_type_models'][0]
  self.assertEqual(names['annotations'],['Mark'])
  self.assertEqual(values['arguments'][0]['annotations'],['Mark'])
 def test_unmodeled_copy_candidate_keeps_builder_partial(self):
  extra={'org/jetbrains/annotations/NotNull.java':'package org.jetbrains.annotations; import java.lang.annotation.*; @Target({ElementType.FIELD,ElementType.TYPE_USE}) @interface NotNull {}'}
  facts=self.analyze([self.fixture('@lombok.Builder class A {@org.jetbrains.annotations.NotNull String value;}',extra)])['facts']
  self.assertFalse([f for f in facts if f['signature']=='A$ABuilder'])
  self.assertTrue(any('builder' in f['attributes'].get('partial_capabilities',[]) for f in facts if f['relation']=='java.lombok'))
if __name__=='__main__':unittest.main(verbosity=2)
