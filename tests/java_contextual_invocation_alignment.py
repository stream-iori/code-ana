#!/usr/bin/env python3
"""Real-project nested lambda, target-neutral constructor, and poly factory regressions."""
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok
NESTED='''import java.util.*;import java.util.function.*;import java.util.stream.*;
@lombok.Getter class Trigger {Object value;}
class Use {Map<String,Map<String,Object>> registry=new HashMap<>();
Function<Object,Function<String,String>> prop=value->key->String.valueOf(value);
void read(Map<String,Map<String,Map<String,Object>>> config){
 config.forEach(((tenant,map)->{Map<String,Object> table=new HashMap<>();registry.put(tenant,table);
 map.forEach((name,data)->{List<String> fields=Arrays.asList("a","b");List<?> values=(List<?>)data.get("values");
 values.forEach(value->{String key=fields.stream().map(prop.apply(value)).collect(Collectors.joining("-"));table.put(name,key);});});}));}}
'''
CONSTRUCTOR='''@lombok.Data class Config {}
interface Stub<T> {Stub<T> thenReturn(T value);}
class Stubber {static <T> Stub<T> when(T value){return null;}}
class Api {static Config current(){return null;}}
class Use {void read(){Stubber.when(Api.current()).thenReturn(new Config());}}
'''
GENERIC='''class Base {} class Child extends Base {} @lombok.Setter class Holder {java.util.List<Base> values;}
class Factory {static <T> java.util.ArrayList<T> list(T... values){return null;}}
class Use {void read(Holder holder){holder.setValues(Factory.list(new Child()));}}
'''
METHOD_INPUT='''@lombok.ToString class Bean {} class Use {
 void read(Bean value){System.out.println(value.toString());}
}'''
class ContextualInvocations(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze
 def result(self,source):return self.analyze([self.context('app',lombok.Lombok.files(self,source),lombok='model')])['facts']
 def test_attributed_captured_lambda_parameters_keep_confirmed_targets(self):
  facts=self.result(NESTED);targets={f['parent'] for f in facts if f['kind']=='call_target'}
  missing=[(f['name'],f['attributes'].get('recovery_failure')) for f in facts if f['kind']=='call_site' and f['status']=='resolved' and f['id'] not in targets]
  self.assertEqual(missing,[])
 def test_stable_constructor_type_keeps_confirmed_outer_call(self):
  facts=self.result(CONSTRUCTOR);by={f['id']:f for f in facts};f=next(f for f in facts if f['kind']=='call_target' and by[f['target']]['signature']=='Stub#thenReturn(java.lang.Object)');self.assertEqual(f['status'],'resolved')
 def test_generic_factory_accepts_setter_target_type(self):
  facts=self.result(GENERIC);by={f['id']:f for f in facts};targets=[by[f['target']]['signature'] for f in facts if f['kind']=='call_target'];self.assertIn('Holder#setValues(java.util.List)',targets)

 def test_same_return_generated_method_keeps_outer_possible_target(self):
  self.assertIn(('java.io.PrintStream#println(java.lang.String)','possible'),self.targets(METHOD_INPUT))
  self.assertIn(('Bean#toString()','possible'),self.targets(METHOD_INPUT))
 def test_same_return_method_full_delta_and_removal(self):
  context=self.context('app',lombok.Lombok.files(self,METHOD_INPUT),lombok='model')
  base=self.analyze([context],cache=True)
  changed=METHOD_INPUT.replace('println(value.toString())','print(value.toString())')
  context=self.context('app',lombok.Lombok.files(self,changed),lombok='model')
  delta=self.analyze([context],base['facts'],cache=True);full=self.analyze([context],cache=True)
  self.assertEqual(self.merged(base['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
  context=self.context('app',lombok.Lombok.files(self,changed.replace('@lombok.ToString ','')),lombok='model')
  delta=self.analyze([context],full['facts'],cache=True);plain=self.analyze([context],cache=True)
  self.assertEqual(self.merged(full['facts'],delta),sorted(plain['facts'],key=lambda f:f['id']))
  by={f['id']:f for f in plain['facts']}
  targets=[(by[f['target']]['signature'],f['status']) for f in plain['facts'] if f['kind']=='call_target']
  self.assertIn(('java.io.PrintStream#print(java.lang.String)','resolved'),targets)
  self.assertNotIn(('Bean#toString()','possible'),targets)
 merged=models.Models.merged
 def targets(self,source):
  facts=self.result(source);by={f['id']:f for f in facts}
  return [(by[f['target']]['signature'],f['status']) for f in facts if f['kind']=='call_target']
 def test_unrelated_constructor_keeps_outer_invalid(self):
  source=CONSTRUCTOR.replace('static Config current()','static String current()')
  self.assertNotIn('Stub#thenReturn(java.lang.Object)',[s for s,_ in self.targets(source)])
 def test_constructor_itself_keeps_modeled_confidence(self):
  self.assertIn(('Config#<init>()','possible'),self.targets(CONSTRUCTOR))
 def test_generic_explicit_arguments_cannot_be_reinferred(self):
  source=GENERIC.replace('Factory.list(', 'Factory.<Child>list(')
  self.assertNotIn('Holder#setValues(java.util.List)',[s for s,_ in self.targets(source)])
 def test_generic_unrelated_argument_stays_inapplicable(self):
  source=GENERIC.replace('new Child()', '"text"')
  self.assertNotIn('Holder#setValues(java.util.List)',[s for s,_ in self.targets(source)])
 def test_generic_bound_stays_checked(self):
  source=GENERIC.replace('static <T>', 'static <T extends Number>')
  self.assertNotIn('Holder#setValues(java.util.List)',[s for s,_ in self.targets(source)])
 def test_contextual_binding_full_delta_and_removal(self):
  old=self.context('app',lombok.Lombok.files(self,GENERIC),lombok='model')
  base=self.analyze([old],cache=True)
  source=GENERIC.replace('new Child()', 'new Base()')
  ctx=self.context('app',lombok.Lombok.files(self,source),lombok='model')
  delta=self.analyze([ctx],base['facts'],cache=True);full=self.analyze([ctx],cache=True)
  self.assertEqual(self.merged(base['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
  source=source.replace('List<Base>', 'List<Child>')
  ctx=self.context('app',lombok.Lombok.files(self,source),lombok='model')
  delta=self.analyze([ctx],full['facts'],cache=True);invalid=self.analyze([ctx],cache=True)
  self.assertEqual(self.merged(full['facts'],delta),sorted(invalid['facts'],key=lambda f:f['id']))
  by={f['id']:f for f in invalid['facts']}
  self.assertNotIn('Holder#setValues(java.util.List)',[by[f['target']]['signature'] for f in invalid['facts'] if f['kind']=='call_target'])

if __name__=='__main__':unittest.main(verbosity=2)
