from java_type_views import type_attributes
import pathlib,os,sys,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'));import java_lombok_builder_alignment as base
class SuperBuilders(unittest.TestCase):
 setUp=base.Builders.setUp;context=base.Builders.context;analyze=base.Builders.analyze;merged=base.Builders.merged;controls=base.Builders.controls;declaration=staticmethod(base.Builders.declaration)
 def fixture(self,source):
  ctx=base.Builders.fixture(self,source)
  # SOURCE annotation mirrors supported experimental options/defaults.
  path=self.root/'app/lombok/experimental/SuperBuilder.java';path.parent.mkdir(parents=True,exist_ok=True)
  path.write_text('package lombok.experimental;public @interface SuperBuilder {String builderMethodName() default "builder";String buildMethodName() default "build";boolean toBuilder() default false;String setterPrefix() default "";}')
  from java_model_tests import sha
  ctx['sources'].append(dict(path='app/lombok/experimental/SuperBuilder.java',input=str(path),hash=sha(path.read_bytes()),world=ctx['world'],module='app',scope='MAIN',publish=True))
  ctx['digest']=sha((ctx['digest']+sha(path.read_bytes())).encode())
  return ctx
 def test_super_concrete_generic_defaults_and_finite_self_bounds(self):
  facts=self.analyze([self.fixture('@lombok.experimental.SuperBuilder class A<T extends Number> { T value; @lombok.Builder.Default int count=7; }')])['facts']
  b=self.declaration(facts,'A$ABuilder');impl=self.declaration(facts,'A$ABuilderImpl')
  implicit=self.declaration(facts,'A$ABuilder#<init>()')
  self.assertEqual(implicit['origin'],'lombok_model')
  self.assertFalse(implicit['declaration_resolved']);self.assertFalse(implicit['evidence_complete'])
  self.assertIsNone(implicit['span']);self.assertTrue(implicit['attributes']['modeled_implicit'])
  self.assertEqual(b['attributes']['modifiers'],['public','static','abstract'])
  self.assertEqual(impl['attributes']['modifiers'],['private','static','final'])
  self.assertEqual([f['name'] for f in type_attributes(b)['type_parameter_models']],['T','C','B'])
  self.assertEqual(type_attributes(self.declaration(facts,'A$ABuilder#value(java.lang.Number)'))['return_type_model']['name'],'B')
  for s in ('A$ABuilder#count$value','A$ABuilder#count$set','A#$default$count()','A#builder()','A#<init>(A$ABuilder)','A$ABuilderImpl#build()'):self.declaration(facts,s)
  self.assertEqual(type_attributes(self.declaration(facts,'A#builder()'))['return_type_model']['arguments'][0]['owner'],'A#builder()')
 def test_super_abstract_parent_and_reversed_source_order(self):
  source='@lombok.experimental.SuperBuilder class Child extends Parent<String> { int age; } @lombok.experimental.SuperBuilder abstract class Parent<T> { T name; }'
  facts=self.analyze([self.fixture(source)])['facts']
  child=self.declaration(facts,'Child$ChildBuilder');parent=self.declaration(facts,'Parent$ParentBuilder')
  self.assertEqual(type_attributes(child)['superclass_type_model']['element'],'Parent$ParentBuilder')
  self.assertEqual(type_attributes(child)['superclass_type_model']['arguments'][0]['element'],'java.lang.String')
  self.assertFalse([f for f in facts if f['signature'] in ('Parent#builder()','Parent$ParentBuilderImpl')])
 def test_super_parent_generated_first_does_not_become_custom_source(self):
  facts=self.analyze([self.fixture('@lombok.experimental.SuperBuilder class Parent {String name;} @lombok.experimental.SuperBuilder class Child extends Parent {int age;}')])['facts']
  self.declaration(facts,'Child$ChildBuilder');self.declaration(facts,'Child#builder()')
 def test_super_fresh_formals_and_static_nested_owner(self):
  facts=self.analyze([self.fixture('class Outer {@lombok.experimental.SuperBuilder static class A<C,B> {C first;B second;}}')])['facts']
  builder=self.declaration(facts,'Outer$A$ABuilder')
  self.assertEqual([f['name'] for f in type_attributes(builder)['type_parameter_models']],['C','B','C2','B2'])
 def test_super_reject_options_raw_missing_parent_and_custom_builder(self):
  sources=['@lombok.experimental.SuperBuilder(toBuilder=false) class A{int n;}',
   '@lombok.experimental.SuperBuilder class A{static class ABuilder {}}',
   'class Parent {} @lombok.experimental.SuperBuilder class A extends Parent {int n;}',
   '@lombok.experimental.SuperBuilder class Parent<T> {} @lombok.experimental.SuperBuilder class A extends Parent {}',
   '@lombok.experimental.SuperBuilder class A {@lombok.Builder.Default int n;}',
   'class Outer {@lombok.experimental.SuperBuilder class A{int n;}}',
   '@lombok.experimental.SuperBuilder class A {static Object builder(){return null;}}']
  for source in sources:
   with self.subTest(source=source):
    facts=self.analyze([self.fixture(source)])['facts']
    self.assertFalse([f for f in facts if f['kind']=='declaration' and f['signature']=='A$ABuilder' and f['origin']=='lombok_model'])
    self.assertTrue(any('super_builder' in f['attributes'].get('partial_capabilities',[]) for f in facts if f['relation']=='java.lombok'))
 def test_super_default_unchanged_delta_and_removal(self):
  ctx=self.fixture('@lombok.experimental.SuperBuilder class A { @lombok.Builder.Default int n=1; }')
  old=self.analyze([ctx])['facts'];unchanged=self.analyze([ctx],old)
  self.assertEqual(unchanged['facts'],[])
  ctx=self.fixture('class A { int n=2; }');delta=self.analyze([ctx],old);full=self.analyze([ctx])['facts']
  self.assertEqual(self.merged(old,delta),sorted(full,key=lambda f:f['id']))
 def test_super_inherited_chain_recovery(self):
  facts=self.analyze([self.fixture('@lombok.experimental.SuperBuilder abstract class Parent<T> {T name;} @lombok.experimental.SuperBuilder class Child extends Parent<String> {int age;} class Use {Child run(){return Child.builder().name("x").age(1).build();}}')])['facts']
  declarations={f['signature']:f for f in facts if f['kind']=='declaration'}
  expected=('Child#builder()','Parent$ParentBuilder#name(java.lang.Object)','Child$ChildBuilder#age(int)','Child$ChildBuilder#build()')
  targets={f['target'] for f in facts if f['kind']=='call_target'}
  for s in expected:self.assertIn(declarations[s]['id'],targets,s)
 def test_super_generic_inherited_chain_keeps_actual_formals(self):
  facts=self.analyze([self.fixture('@lombok.experimental.SuperBuilder abstract class Parent<T> {T name;} @lombok.experimental.SuperBuilder class Child<N extends Number> extends Parent<String> {N value;} class Use {Child<Integer> run(){return Child.<Integer>builder().name("x").value(1).build();}}')])['facts']
  declarations={f['signature']:f for f in facts if f['kind']=='declaration'}
  targets={f['target'] for f in facts if f['kind']=='call_target'}
  for s in ('Child#builder()','Parent$ParentBuilder#name(java.lang.Object)','Child$ChildBuilder#value(java.lang.Number)','Child$ChildBuilder#build()'):self.assertIn(declarations[s]['id'],targets,s)
  calls=[f for f in facts if f['kind']=='call_site' and f['attributes'].get('lookup_name')=='value']
  self.assertEqual(type_attributes(calls[0])['recovered_parameter_type_models'][0]['element'],'java.lang.Integer')
  facts=self.analyze([self.fixture('@lombok.experimental.SuperBuilder abstract class Parent<T> {T name;} @lombok.experimental.SuperBuilder class Child<N extends Number> extends Parent<String> {N value;} class Use {Object run(){return Child.<Integer>builder().name("x").value("wrong").build();}}')])['facts']
  invalid=[f for f in facts if f['kind']=='call_site' and f['attributes'].get('lookup_name')=='value']
  self.assertTrue(invalid);self.assertTrue(all(f['attributes'].get('recovery_failure')=='no_applicable_candidate' for f in invalid))
if __name__=='__main__':unittest.main(verbosity=2)
