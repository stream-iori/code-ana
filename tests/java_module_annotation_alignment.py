import json,pathlib,sys,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
import java_model_tests as models
import java_lombok_alignment as lombok
LOMBOK=pathlib.Path.home()/'.m2/repository/org/projectlombok/lombok/1.18.6/lombok-1.18.6.jar'
class ModuleAnnotations(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged
 def plan(self,body='return api.getName();',annotation='@Getter'):
  lib=self.context('lib',{'Api.java':'package p;import lombok.Getter;'+annotation+' public class Api {String name;}'},lombok='model',classpath=[str(LOMBOK)])
  use=self.context('use',{'Use.java':'package p;class Use {String read(Api api){'+body+'}}'},lombok='model')
  use['sources']=[dict(s,publish=False) for s in lib['sources']]+use['sources']
  for ctx in (lib,use):lombok.Lombok.controls(self,ctx,{'lombok.config':'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})
  return [use,lib]
 def targets(self,result):
  by={f['id']:f for f in result['facts']}
  return [(by[f['target']]['signature'],f) for f in result['facts'] if f['kind']=='call_target' and f['source']=='use/Use.java']
 def test_provider_classpath_proof_recovers_only_possible_calls(self):
  result=self.analyze(self.plan());(self.root/'diagnostic.json').write_text(json.dumps(result))
  targets=self.targets(result)
  self.assertEqual([s for s,f in targets],['p.Api#getName()'])
  self.assertEqual(targets[0][1]['status'],'possible');self.assertFalse(targets[0][1]['evidence_complete'])
  proof=targets[0][1]['attributes']['prerequisite_annotation_proof']
  self.assertEqual(proof['context'],'lib@MAIN');self.assertEqual(proof['source'],'lib/Api.java')
  self.assertEqual(len(proof['source_hash']),64);self.assertEqual(len(proof['classpath_sha256']),64)
  self.assertTrue(proof['original_evidence_retained']);self.assertEqual(targets[0][1]['attributes']['binding_failure'],'missing_symbol')
  self.assertTrue(any(f['kind']=='diagnostic' and f['status']=='ERROR' and f['source']=='use/Use.java' for f in result['facts']))
 def test_parallel_and_cache_are_semantically_equal(self):
  plan=self.plan();base=self.analyze(plan)['facts']
  for parallelism,cache in ((2,False),(1,True),(2,True)):
   self.assertEqual(base,self.analyze(plan,parallelism=parallelism,cache=cache)['facts'])
 def test_consumer_delta_reanalyzes_unchanged_provider_without_publishing_it(self):
  base=self.analyze(self.plan())['facts'];changed=self.plan('return api.getName()+"x";')
  delta=self.analyze(changed,base);full=self.analyze(changed)['facts']
  self.assertEqual(self.merged(base,delta),sorted(full,key=lambda f:f['id']))
  self.assertEqual([s for s,f in self.targets(delta)],['p.Api#getName()'])
 def test_removing_provider_annotation_removes_recovered_target(self):
  base=self.analyze(self.plan())['facts'];changed=self.plan(annotation='')
  delta=self.analyze(changed,base);full=self.analyze(changed)
  self.assertEqual(self.merged(base,delta),sorted(full['facts'],key=lambda f:f['id']))
  self.assertFalse(self.targets(full))
 def test_provider_without_annotation_jar_cannot_prove_imported_getter(self):
  plan=self.plan();plan[1]['classpath']=[]
  self.assertFalse(self.targets(self.analyze(plan)))
 def test_missing_provider_configuration_cannot_prove_consumer_generated_api(self):
  plan=self.plan();plan[0]['resources']=[]
  self.assertFalse(self.targets(self.analyze(plan)))
 def test_partial_provider_inventory_cannot_prove_generated_api(self):
  plan=self.plan();extra=self.context('extra',{'Other.java':'package p;class Other {}'})['sources'][0]
  extra.update(path='lib/Other.java',world=plan[1]['world'],module='lib')
  plan[1]['sources'].append(extra)
  self.assertFalse(self.targets(self.analyze(plan)))
 def test_malformed_provider_world_cannot_prove_generated_api(self):
  plan=self.plan();extra=self.context('extra',{'Other.java':'package p;class Other {'})['sources'][0]
  extra.update(path='lib/Other.java',world=plan[1]['world'],module='lib')
  plan[1]['sources'].append(extra);plan[0]['sources'].append(dict(extra,publish=False))
  self.assertFalse(self.targets(self.analyze(plan)))
 def test_cyclic_worlds_are_order_independent_and_keep_source(self):
  plan=self.plan();plan[1]['sources'] += [dict(s,publish=False) for s in plan[0]['sources'] if s['module']=='use']
  serial=self.analyze(plan)['facts'];parallel=self.analyze(plan,parallelism=2)['facts']
  self.assertEqual(serial,parallel);self.assertFalse(self.targets(dict(facts=serial)))
 def test_imported_builder_type_recovers_chain_and_keeps_original_error(self):
  plan=self.plan('return Api.builder().name("x").build().getName();',annotation='@Getter @lombok.Builder')
  result=self.analyze(plan);self.assertCountEqual([s for s,f in self.targets(result)],['p.Api#builder()','p.Api$ApiBuilder#name(java.lang.String)','p.Api$ApiBuilder#build()','p.Api#getName()'])
  self.assertTrue(all(f['status']=='possible' for s,f in self.targets(result)))
if __name__=='__main__':unittest.main(verbosity=2)
