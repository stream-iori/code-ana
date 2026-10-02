import pathlib,sys,json,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'))
import java_model_tests as models,java_lombok_builder_alignment as builders,java_lombok_super_builder_alignment as supers,java_module_signature_alignment as base
class OverlayModules(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged
 controls=builders.Builders.controls;declaration=staticmethod(builders.Builders.declaration);assert_facts=base.ModuleProof.assert_facts
 def plan(self,source,client='package p;class Use {int read(A value){return value.n;}}',super_builder=False):
  library=(supers.SuperBuilders.fixture if super_builder else builders.Builders.fixture)(self,source)
  consumer=self.context('client',{'p/Use.java':client},lombok='model',resources=library['resources'])
  consumer['sources']=[dict(row,publish=False) for row in library['sources']]+consumer['sources']
  consumer['digest']=models.sha(json.dumps([(r['path'],r['hash']) for r in consumer['sources']]).encode())
  return [consumer,library]
 def prove(self,plan,expected_cache=True,parallelism=1):
  full=self.analyze(plan);cache=self.analyze(plan,cache=True,parallelism=parallelism)
  self.assert_facts(full['facts'],cache['facts'])
  if expected_cache:
   self.assertEqual(cache['compiler_sources'],len(plan[1]['sources'])+1)
   self.assertGreater(cache['module_signature_report']['module_archives_used'],0)
  return cache
 def test_getter_original_abi_and_overlay_restoration(self):
  result=self.prove(self.plan('package p;@lombok.Getter class A {int n;}'))
  getter=self.declaration(result['facts'],'p.A#getN()');self.assertFalse(getter['declaration_resolved'])
 def test_modeled_overrides_recover_restored_source_and_remove_stale_edges(self):
  client='package p;class Use extends A {public int getN(){return 2;}}'
  plan=self.plan('package p;@lombok.Getter class A {int n;}',client)
  result=self.prove(plan);old=result['facts'];by_id={f['id']:f for f in old}
  edges=[f for f in old if f['relation']=='overrides']
  self.assertEqual([(by_id[f['parent']]['signature'],by_id[f['target']]['signature']) for f in edges],
                   [('p.Use#getN()','p.A#getN()')])
  self.assertTrue(all(f['origin']=='modeled_override' and f['status']=='possible'
                     and not f['declaration_resolved'] and not f['evidence_complete'] for f in edges))
  removed=self.plan('package p;class A {int n;}',client)
  delta=self.analyze(removed,old,cache=True);full=self.analyze(removed)
  self.assert_facts(self.merged(old,delta),full['facts'])
  self.assertFalse([f for f in full['facts'] if f['relation']=='overrides'])
 def test_builder_original_constructor_and_possible_target(self):
  self.prove(self.plan('package p;@lombok.Builder class A {int n;}'),parallelism=2)
 def test_data_value_and_utility_ctor_modifications_are_reapplied(self):
  for source,client in [('package p;@lombok.Data class A {int n;}','package p;class Use {int read(A value){return value.n;}}'),('package p;@lombok.Value class A {int n;}','package p;class Use {int read(A value){return value.n;}}'),('package p;@lombok.experimental.UtilityClass class A {static int n;}','package p;class Use {int read(){return A.n;}}')]:
   with self.subTest(source=source):self.prove(self.plan(source,client))
 def test_generated_calls_remain_possible_and_preserve_diagnostics(self):
  plan=self.plan('package p;@lombok.Getter class A {int n;}','package p;class Use {int read(A value){return value.getN();}}')
  result=self.prove(plan,False)
  getter_ids={r['id'] for r in result['facts'] if r['signature']=='p.A#getN()'}
  targets=[r for r in result['facts'] if r['kind']=='call_target' and r['target'] in getter_ids]
  self.assertTrue(targets);self.assertTrue(all(r['status']=='possible' for r in targets))
  self.assertEqual(result['compiler_sources'],len(plan[1]['sources'])+len(plan[0]['sources']))
 def test_super_builder_original_abi_and_reapplied_hierarchy(self):
  self.prove(self.plan('package p;@lombok.experimental.SuperBuilder abstract class Parent<T> {T name;} @lombok.experimental.SuperBuilder class A<N extends Number> extends Parent<String> {int n;N value;}',super_builder=True),parallelism=2)
 def test_delta_annotation_removal_corruption_and_profile(self):
  plan=self.plan('package p;@lombok.Getter class A {int n;}');old=self.prove(plan)['facts']
  changed=self.plan('package p;@lombok.Getter class A {int n=2;}')
  delta=self.analyze(changed,old,cache=True,parallelism=2);self.assert_facts(self.merged(old,delta),self.analyze(changed)['facts'])
  changed_facts=self.merged(old,delta)
  removed=self.plan('package p;class A {int n=3;}');delta=self.analyze(removed,changed_facts,cache=True)
  self.assert_facts(self.merged(changed_facts,delta),self.analyze(removed)['facts'])
  plan=self.plan('package p;@lombok.Getter class A {int n;}')
  for archive in (self.root/'signature-cache-modules').glob('*/catalogue.bin'):archive.write_bytes(b'bad')
  self.prove(plan)
  self.mutate_request=lambda request:request.update(profile='new-overlay-profile')
  self.prove(plan)
 def test_configuration_changes_reapply_upstream_and_consumer_overlay(self):
  plan=self.plan('package p;@lombok.Getter class A {boolean n;}','package p;class Use {boolean read(A value){return value.n;}}')
  old=self.prove(plan)['facts']
  self.declaration(old,'p.A#isN()')
  for context in plan:
   for resource in context['resources']:
    file=pathlib.Path(resource['input'])
    text=file.read_text()
    if 'lombok.getter.noIsPrefix=true' not in text:file.write_text(text+'lombok.getter.noIsPrefix=true\n')
    resource['unit']['hash']=models.sha(file.read_bytes())
   context['digest']=models.sha((context['digest']+'config-changed').encode())
  delta=self.analyze(plan,old,cache=True,parallelism=2);full=self.analyze(plan)
  self.assert_facts(self.merged(old,delta),full['facts'])
  self.declaration(full['facts'],'p.A#getN()')
  self.assertFalse([f for f in full['facts'] if f['signature']=='p.A#isN()'])
 def test_hidden_main_rebuilds_overlay_without_publishing_source(self):
  plan=self.plan('package p;@lombok.Builder class A {int n;}')
  plan[1]['prerequisite']=True
  for row in plan[1]['sources']:row.update(publish=False,auxiliary=True)
  for row in plan[0]['sources']:
   if not row['publish']:row['auxiliary']=True
  result=self.prove(plan,parallelism=2)
  self.assertFalse([f for f in result['facts'] if f['source'].startswith('app/')])
if __name__=='__main__':unittest.main(verbosity=2)
