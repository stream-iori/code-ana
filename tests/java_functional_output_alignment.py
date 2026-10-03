from java_type_views import type_attributes
import pathlib,sys,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'))
import java_model_tests as models,java_lombok_alignment as lombok,java_binding_alignment as bindings

VOID_OVERLOAD_SOURCE = '@lombok.Getter class Holder{String id;} class Utility{static void run(String key,Runnable work){} static <T> T run(String key,java.util.function.Supplier<T> work){return null;}} class Use{static void execute(String key){} void read(Holder h){Utility.run("prefix"+h.getId(),() -> {execute(h.getId());});}}'
TRY_OUTPUT_SOURCE = '@lombok.Getter class Holder{Api api;} class Api{void run(){}} class Factory{static <T> T create(java.util.function.Supplier<T> work){return null;}} class Use{void read(Holder h){Factory.create(() -> {try{return h.getApi();}catch(RuntimeException e){return h.getApi();}}).run();}}'

class FunctionalOutputs(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged;recovered=bindings.Bindings.recovered
 def test_void_block_excludes_supplier_overload_and_delta_changes_selected_sam(self):
  for graph in (False,True):
   with self.subTest(graph=graph):
    def context(source):
     result=self.context('app',lombok.Lombok.files(self,source),lombok='model')
     if graph:result['type_graph']=True
     return result
    initial=self.analyze([context(VOID_OVERLOAD_SOURCE)],cache=True)
    signatures=[f['signature'] for f in initial['facts'] if f['id'] in {t['target'] for t in initial['facts'] if t['kind']=='call_target'}]
    self.assertIn('Utility#run(java.lang.String,java.lang.Runnable)',signatures)
    self.assertNotIn('Utility#run(java.lang.String,java.util.function.Supplier)',signatures)
    changed=context(VOID_OVERLOAD_SOURCE.replace('execute(h.getId());','return h.getId();'))
    delta=self.analyze([changed],initial['facts'],cache=True);full=self.analyze([changed],cache=True)
    self.assertEqual(self.merged(initial['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
    signatures=[f['signature'] for f in full['facts'] if f['id'] in {t['target'] for t in full['facts'] if t['kind']=='call_target'}]
    self.assertIn('Utility#run(java.lang.String,java.util.function.Supplier)',signatures)
    self.assertNotIn('Utility#run(java.lang.String,java.lang.Runnable)',signatures)
 def test_try_catch_finally_completion_and_incomplete_return_delta(self):
  for graph in (False,True):
   with self.subTest(graph=graph):
    def context(source):
     result=self.context('app',lombok.Lombok.files(self,source),lombok='model')
     if graph:result['type_graph']=True
     return result
    initial=self.analyze([context(TRY_OUTPUT_SOURCE)],cache=True)
    self.assertIn('Factory#create(java.util.function.Supplier)',self.signatures(initial))
    self.assertIn('Api#run()',self.signatures(initial))
    changed=context(TRY_OUTPUT_SOURCE.replace('catch(RuntimeException e){return h.getApi();}','finally{int count=1;}'))
    delta=self.analyze([changed],initial['facts'],cache=True);full=self.analyze([changed],cache=True)
    self.assertEqual(self.merged(initial['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
    self.assertIn('Api#run()',self.signatures(full))
    for body in ('try{return h.getApi();}catch(RuntimeException e){h.getApi();}',
                 'while(System.nanoTime()>0){return h.getApi();}'):
     source=TRY_OUTPUT_SOURCE.replace('try{return h.getApi();}catch(RuntimeException e){return h.getApi();}',body)
     changed=context(source);delta=self.analyze([changed],initial['facts'],cache=True);full=self.analyze([changed],cache=True)
     self.assertEqual(self.merged(initial['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
     self.assertNotIn('Factory#create(java.util.function.Supplier)',self.signatures(full))
     self.assertNotIn('Api#run()',self.signatures(full))
 def fixture(self,body,extra='',declarations=''):
  source='''class Holder {@lombok.Getter Api api; @lombok.Getter Factory factory;}
    class Api {@lombok.Getter String value; void run(){} }
    class Other {} interface SourceSupplier<T> {T get();}
    class Factory {
      static <T> T create(java.util.function.Supplier<T> supplier){return null;}
      static <T> T source(SourceSupplier<T> supplier){return null;}
      static <T extends Number> T bounded(java.util.function.Supplier<T> supplier){return null;}
      <T,R> R convert(java.util.function.Function<T,R> function){return null;}
      '''+declarations+'''} class Use {void read(Holder h, boolean flag){'''+body+'''}}'''+extra
  return self.context('app',lombok.Lombok.files(self,source),lombok='model'),source
 def signatures(self,result):
  return [signature for name,signature,fact in self.recovered(result['facts'])]
 def test_supplier_lambda_reference_source_sam_and_agreeing_block_outputs(self):
  context,source=self.fixture('''Factory.create(() -> h.getApi()).run();
    Factory.create(h::getApi).run(); Factory.source(() -> h.getApi()).run();
    Factory.create(() -> {if(flag) return h.getApi(); else return h.getApi();}).run();''')
  result=self.analyze([context]);targets=self.recovered(result['facts'])
  outer=[f for name,signature,f in targets if signature.startswith(('Factory#create','Factory#source'))]
  self.assertEqual(len(outer),4,targets)
  self.assertTrue(all(type_attributes(f)['recovered_return_type_model']['element']=='Api' for f in outer))
  self.assertEqual(self.signatures(result).count('Api#run()'),4)
  references=[f for name,signature,f in targets if name=='h::getApi']
  self.assertEqual(len(references),1);self.assertEqual(type_attributes(references[0])['recovered_sam']['return']['element'],'Api')
  self.assertTrue(all(f['status']=='possible' and not f['evidence_complete'] for _,_,f in targets))
 def test_unbound_reference_infers_receiver_and_output_without_written_target(self):
  context,_=self.fixture('h.getFactory().convert(Api::getValue).length();')
  result=self.analyze([context]);targets=self.recovered(result['facts'])
  self.assertIn('Api#getValue()',self.signatures(result));self.assertIn('java.lang.String#length()',self.signatures(result))
  target=next(f for name,sig,f in targets if sig=='Factory#convert(java.util.function.Function)')
  self.assertEqual(type_attributes(target)['recovered_return_type_model']['element'],'java.lang.String')
  args=type_attributes(target)['recovered_parameter_type_models'][0]['arguments']
  self.assertEqual([t['element'] for t in args],['Api','java.lang.String'])
 def test_conflicting_outputs_bounds_void_and_wrong_explicit_types_remain_unresolved(self):
  for body in ('Factory.create(() -> {if(flag) return h.getApi(); else return new Other();}).run();',
               'Factory.bounded(() -> h.getApi()).intValue();',
               'Factory.create(() -> {h.getApi();}).run();',
               'Factory.<Other>create(() -> h.getApi()).run();'):
   with self.subTest(body=body):
    context,_=self.fixture(body);result=self.analyze([context])
    self.assertFalse([s for s in self.signatures(result) if s.startswith(('Factory#create','Factory#bounded'))],self.signatures(result))
 def test_failed_candidate_cannot_leak_inference_into_other_overload(self):
  first='<T extends Number> T pick(java.util.function.Supplier<T> s,int tag){return null;}'
  second='<T> T pick(SourceSupplier<T> s,Object tag){return null;}'
  for declarations in (first+second,second+first):
   with self.subTest(declarations=declarations):
    context,_=self.fixture('h.getFactory().pick(() -> h.getApi(),0).run();',declarations=declarations)
    result=self.analyze([context]);signatures=self.signatures(result)
    self.assertIn('Factory#pick(SourceSupplier,java.lang.Object)',signatures)
    self.assertNotIn('Factory#pick(java.util.function.Supplier,int)',signatures)
    self.assertIn('Api#run()',signatures)
 def test_full_delta_module_cache_and_removing_getter(self):
  body='Factory.create(() -> h.getApi()).run(); Factory.create(h::getApi).run();'
  context,source=self.fixture(body);initial=self.analyze([context],cache=True)
  self.assertEqual(self.signatures(initial).count('Api#run()'),2)
  unchanged=self.analyze([context],initial['facts'],cache=True)
  self.assertEqual(self.merged(initial['facts'],unchanged),sorted(initial['facts'],key=lambda f:f['id']))
  changed=self.context('app',lombok.Lombok.files(self,source.replace('String value;','String value; int marker;')),lombok='model')
  delta=self.analyze([changed],initial['facts'],cache=True);full=self.analyze([changed],cache=True)
  self.assertEqual(self.merged(initial['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
  removed=self.context('app',lombok.Lombok.files(self,source.replace('@lombok.Getter Api api;','Api api;')),lombok='model')
  delta=self.analyze([removed],full['facts'],cache=True);full_removed=self.analyze([removed],cache=True)
  self.assertEqual(self.merged(full['facts'],delta),sorted(full_removed['facts'],key=lambda f:f['id']))
  self.assertNotIn('Api#run()',self.signatures(full_removed))
 def test_generated_overload_survives_old_javac_arity_and_preserves_evidence(self):
  source='@lombok.Getter class Envelope {java.util.Map<String,Object> extraContent;\n   <T> T getExtraContent(String key){return null;}}\n  class Use {void read(Envelope e){e.getExtraContent().get("name");}}'
  context=self.context('app',lombok.Lombok.files(self,source),lombok='model')
  result=self.analyze([context],cache=True);targets=self.recovered(result['facts'])
  getter=next(f for name,sig,f in targets if sig=='Envelope#getExtraContent()')
  self.assertEqual(getter['attributes']['binding_failure'],'inapplicable')
  self.assertTrue(getter['attributes']['diagnostic_codes'])
  self.assertEqual(getter['status'],'possible');self.assertFalse(getter['evidence_complete'])
  self.assertIn('java.util.Map#get(java.lang.Object)',self.signatures(result))
  changed=self.context('app',lombok.Lombok.files(self,source.replace('"name"','"other"')),lombok='model')
  delta=self.analyze([changed],result['facts'],cache=True);full=self.analyze([changed],cache=True)
  self.assertEqual(self.merged(result['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
 def test_generated_overload_wrong_arity_and_poly_upper_bound_remain_unresolved(self):
  source='@lombok.Getter class Envelope {java.util.Map<String,Object> extraContent;\n   <T> T getExtraContent(String key){return null;}}\n  class Item {@lombok.Getter String name;} class Use {void read(Envelope e,java.util.Optional<Item> value){\n   e.getExtraContent(1,2);value.<Number>map(item -> item.getName());}}'
  context=self.context('app',lombok.Lombok.files(self,source),lombok='model')
  signatures=self.signatures(self.analyze([context]))
  self.assertFalse([s for s in signatures if s.startswith(('Envelope#getExtraContent','java.util.Optional#map'))])
 def test_static_instance_reference_ambiguity_does_not_seed_receiver(self):
  context,source=self.fixture('h.getFactory().convert(Api::getValue).length();')
  source=source.replace('void run(){}','void run(){} static String getValue(Api input){return "";}')
  context=self.context('app',lombok.Lombok.files(self,source),lombok='model');result=self.analyze([context])
  self.assertNotIn('Factory#convert(java.util.function.Function)',self.signatures(result))

class SourceParameters(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged
 def test_source_signature_type_owners_multiplicity_unicode_and_delta(self):
  source='''// 中文类型区间
class Leaf {} class Box<T> {} class Holder {
 Holder(Leaf one, Leaf[] two){}
 Box<Leaf> signature(Leaf first, Leaf second, Box<Box<Leaf>> nested, Leaf[][] matrix){return null;}}
'''
  context=self.context('app',{'App.java':source});initial=self.analyze([context],cache=True)
  def verify(result):
   by_id={f['id']:f for f in result['facts']}
   method=next(f for f in result['facts'] if f['signature']=='Holder#signature(Leaf,Leaf,Box,Leaf[][])')
   constructor=next(f for f in result['facts'] if f['signature']=='Holder#<init>(Leaf,Leaf[])')
   refs=[f for f in result['facts'] if f['relation']=='type_reference' and f['parent'] in (method['id'],constructor['id'])]
   self.assertEqual(len(refs),10,refs)
   self.assertEqual(sum(f['parent']==method['id'] for f in refs),8)
   self.assertTrue(all(f['status']=='complete' and f['evidence_complete'] for f in refs))
   raw=source.encode('utf8')
   self.assertTrue(all(raw[f['span']['start']:f['span']['end']].decode('utf8') in ('Leaf','Box') for f in refs))
   parameters=[f for f in result['facts'] if f['attributes'].get('element_kind')=='PARAMETER']
   self.assertEqual(len(parameters),6)
   self.assertFalse([f for f in result['facts'] if f['relation']=='type_reference' and f['parent'] in {p['id'] for p in parameters}])
  verify(initial)
  unchanged=self.analyze([context],initial['facts'],cache=True)
  self.assertEqual(self.merged(initial['facts'],unchanged),sorted(initial['facts'],key=lambda f:f['id']))
  changed=self.context('app',{'App.java':source.replace('return null;','return new Box<Leaf>();')})
  delta=self.analyze([changed],initial['facts'],cache=True);full=self.analyze([changed],cache=True)
  self.assertEqual(self.merged(initial['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))

if __name__=='__main__':unittest.main(verbosity=2)
