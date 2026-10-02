from java_type_views import type_attributes
import pathlib,sys,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'))
import java_model_tests as models,java_lombok_alignment as lombok,java_binding_alignment as bindings
class TargetReturns(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged;recovered=bindings.Bindings.recovered
 def fixture(self,body):
  source='''class Holder {@lombok.Getter Api api;} class Api {
    <T> T make(){return null;} <T> java.util.List<T> values(){return null;}
    <T> T[] array(){return null;} <T extends Number> T number(){return null;}
    <T> T echo(T input){return input;}
  } class Use {'''+body+'}'
  return self.context('app',lombok.Lombok.files(self,source),lombok='model')
 def target(self,result,name,element):
  found=[f for site,signature,f in self.recovered(result['facts']) if site==name]
  self.assertEqual(len(found),1,(name,[s for s,_,_ in self.recovered(result['facts'])]));self.assertEqual(type_attributes(found[0])['recovered_return_type_model'].get('element'),element)
  self.assertTrue(all(f['status']=='possible' and not f['evidence_complete'] for f in found))
 def test_variable_assignment_and_return_written_targets(self):
  result=self.analyze([self.fixture('String read(Holder h){String value=h.getApi().make();Number n;n=h.getApi().number();return (h.getApi().make());}')])
  found=[f for site,sig,f in self.recovered(result['facts']) if site=='h.getApi().make'];self.assertEqual(len(found),2)
  self.assertTrue(all(type_attributes(f)['recovered_return_type_model']['element']=='java.lang.String' for f in found))
  self.target(result,'h.getApi().number','java.lang.Number')
 def test_invariant_container_target_and_array_boundary(self):
  result=self.analyze([self.fixture('void read(Holder h){java.util.List<String> values=h.getApi().values();String[] array=h.getApi().array();}')])
  targets={site:type_attributes(f)['recovered_return_type_model'] for site,sig,f in self.recovered(result['facts'])}
  self.assertEqual(targets['h.getApi().values']['arguments'][0]['element'],'java.lang.String')
  self.assertNotIn('h.getApi().array',targets)

 def test_modeled_lambda_parameters_recover_source_and_binary_calls(self):
  source='''class Holder {@lombok.Getter java.util.List<Item> items; @lombok.Getter Api api;}
    @lombok.Data class Item {String value;}
    class Api {void accept(java.util.function.Consumer<Item> action){}
      <R> R mapped(java.util.function.Function<Item,R> fn){return null;}}
    class Use {String read(Holder h){
      h.getItems().forEach(item -> item.toString());
      h.getApi().accept(item -> {item.getValue();});
      return h.getApi().mapped(item -> item.getValue()); }}'''
  context=self.context('app',lombok.Lombok.files(self,source),lombok='model')
  result=self.analyze([context]);targets=self.recovered(result['facts'])
  expected={'java.lang.Iterable#forEach(java.util.function.Consumer)',
            'Item#toString()','Item#getValue()',
            'Api#accept(java.util.function.Consumer)','Api#mapped(java.util.function.Function)'}
  self.assertTrue(expected.issubset({sig for name,sig,f in targets}),targets)
  sites={f['id']:f for f in result['facts'] if f['kind']=='call_site'}
  to_string=next(f for name,sig,f in targets if sig=='Item#toString()')
  site=sites[to_string['parent']]
  self.assertEqual(site['status'],'unresolved')
  binding=next(f for f in result['facts'] if f['parent']==site['id'] and f['relation']=='java.binding')
  self.assertFalse(binding['attributes']['resolved'])
  self.assertFalse([f for f in result['facts'] if f['kind']=='call_target' and f['parent']==site['id'] and f['status']=='resolved'])
  mapped=next(f for name,sig,f in targets if sig=='Api#mapped(java.util.function.Function)')
  self.assertEqual(type_attributes(mapped)['recovered_return_type_model']['element'],'java.lang.String')
  updated=self.context('app',lombok.Lombok.files(self,source.replace('String value;','String value; int marker;')),lombok='model')
  delta=self.analyze([updated],result['facts']);full=self.analyze([updated])
  self.assertEqual(self.merged(result['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))

 def test_nested_function_map_context_preserves_original_javac_binding(self):
  source='''@lombok.Data class Item {String value;}
    class Api {java.util.List<Item> items(String key){return null;}}
    class Use {void read(Api api,java.util.Map<String,java.util.function.Function<String,String>> actions){
      actions.put("key", key -> {java.util.List<Item> items=api.items(key);
        items.forEach(item -> item.toString());return "value";});}}'''
  result=self.analyze([self.context('app',lombok.Lombok.files(self,source),lombok='model')])
  targets=self.recovered(result['facts'])
  self.assertTrue({'java.util.Map#put(java.lang.Object,java.lang.Object)',
                   'Api#items(java.lang.String)','Item#toString()',
                   'java.lang.Iterable#forEach(java.util.function.Consumer)'}
                  .issubset({sig for name,sig,f in targets}),targets)
  site=next(f for f in result['facts'] if f['kind']=='call_site' and f['name']=='item.toString')
  self.assertEqual(site['status'],'resolved')
  self.assertEqual(type_attributes(site)['selected_type_model']['return']['element'],'java.lang.String')
  binding=next(f for f in result['facts'] if f['parent']==site['id'] and f['relation']=='java.binding')
  self.assertTrue(binding['attributes']['resolved'])
  self.assertFalse([f for f in result['facts'] if f['kind']=='call_target' and f['parent']==site['id'] and f['status']=='resolved'])

 def test_lambda_arity_return_and_explicit_parameter_guards(self):
  source='''class Holder {@lombok.Getter Api api;} class Item {}
    class Api {void one(java.util.function.Consumer<Item> action){}
      String text(java.util.function.Function<Item,String> fn){return null;}}
    class Use {void read(Holder h){h.getApi().one((a,b) -> {});
      h.getApi().one((String value) -> {});
      h.getApi().text(value -> 42);
      h.getApi().text(value -> {if (value==null) return "value";});
      h.getApi().one(value -> {return "value";});}}'''
  result=self.analyze([self.context('app',lombok.Lombok.files(self,source),lombok='model')])
  self.assertFalse([f for name,sig,f in self.recovered(result['facts']) if sig.startswith('Api#')])

 def test_invocation_parameter_target_is_local_to_each_candidate(self):
  context=self.fixture('''static void text(String value){} static void list(java.util.List<String> values){}
    static void bounded(String value){}
    void read(Holder h){text(h.getApi().make());list(h.getApi().values());
      bounded(h.getApi().number());}''')
  result=self.analyze([context]);targets=self.recovered(result['facts'])
  self.assertTrue({'Use#text(java.lang.String)','Use#list(java.util.List)'}
                  .issubset({sig for name,sig,f in targets}),targets)
  self.assertFalse([f for name,sig,f in targets if sig=='Use#bounded(java.lang.String)'])
  for name,sig,f in targets:
   if sig=='Api#number()':
    self.assertEqual(type_attributes(f)['recovered_return_type_model']['kind'],'TYPEVAR')
    self.assertEqual(type_attributes(f)['recovered_return_type_model']['upper']['element'],'java.lang.Number')
  make=next(f for name,sig,f in targets if sig=='Api#make()')
  values=next(f for name,sig,f in targets if sig=='Api#values()')
  self.assertEqual(type_attributes(make)['recovered_return_type_model']['element'],'java.lang.String')
  self.assertEqual(type_attributes(values)['recovered_return_type_model']['arguments'][0]['element'],'java.lang.String')

 def test_modeled_enum_switch_labels_preserve_possible_source_reads(self):
  source='''enum Choice {FIRST,SECOND} class Holder {@lombok.Getter Choice choice;}
    class Use {int read(Holder value){switch ((value.getChoice())) {
      case FIRST:return 1;case SECOND:return 2;default:return 0;}}
      int ordinary(Choice value){switch(value){case FIRST:return 1;default:return 0;}}}'''
  context=self.context('app',lombok.Lombok.files(self,source),lombok='model')
  facts=self.analyze([context])['facts'];declarations={f['id']:f for f in facts if f['kind']=='declaration'}
  recovered=[f for f in facts if f['relation']=='reads' and f['origin']=='source_recovery']
  self.assertEqual({declarations[f['target']]['signature'] for f in recovered},{'Choice#FIRST','Choice#SECOND'})
  self.assertEqual(len(recovered),2)
  self.assertTrue(all(f['status']=='possible' and not f['evidence_complete'] for f in recovered))
  self.assertEqual(len([f for f in facts if f['relation']=='reads' and f['origin']=='javac' and declarations.get(f['target'],{}).get('signature')=='Choice#FIRST']),1)
  updated=self.context('app',lombok.Lombok.files(self,source.replace('return 2','return 3')),lombok='model')
  delta=self.analyze([updated],facts);full=self.analyze([updated])['facts']
  self.assertEqual(self.merged(facts,delta),sorted(full,key=lambda f:f['id']))
 def test_bounds_explicit_types_and_input_conflicts_are_not_selected(self):
  result=self.analyze([self.fixture('void read(Holder h){String bad=h.getApi().number();java.util.List<Number> wrong=h.getApi().<String>values();Number n=h.getApi().echo("bad");}')])
  self.assertFalse([f for name,sig,f in self.recovered(result['facts']) if name in ('h.getApi().number','h.getApi().values','h.getApi().echo')])
 def test_no_written_target_and_cast_are_not_guessed(self):
  result=self.analyze([self.fixture('void read(Holder h){h.getApi().make();String cast=(String)h.getApi().make();}')])
  found=[f for name,sig,f in self.recovered(result['facts']) if name=='h.getApi().make']
  self.assertEqual(len(found),2)
  self.assertTrue(all(type_attributes(f)['recovered_return_type_model']['kind']=='TYPEVAR' for f in found))
 def test_target_edit_delta_is_equal_to_full(self):
  first=self.fixture('String read(Holder h){return h.getApi().make();}');old=self.analyze([first])['facts']
  changed=self.fixture('Number read(Holder h){return h.getApi().make();}');delta=self.analyze([changed],old);full=self.analyze([changed]);self.assertEqual(self.merged(old,delta),sorted(full['facts'],key=lambda f:f['id']))
  self.target(full,'h.getApi().make','java.lang.Number')
if __name__=='__main__':unittest.main(verbosity=2)
