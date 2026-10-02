import sys,json,pathlib,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
import java_model_tests as models,java_lombok_builder_alignment as builders,java_lombok_alignment as lombok
from java_type_views import type_attributes
class ModuleBoundary(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged;controls=lombok.Lombok.controls
 def plan(self,extra='',consumer=None):
  seed=builders.Builders.fixture(self,'package p;@lombok.Builder public class Api {String path;int id;'+extra+'}')
  files={pathlib.Path(s['input']).relative_to(self.root/'app').as_posix():pathlib.Path(s['input']).read_text() for s in seed['sources']};files['Api.java']=files.pop('App.java')
  files['lombok/experimental/Delegate.java']='package lombok.experimental;public @interface Delegate {}'
  lib=self.context('lib',files,lombok='model')
  use=self.context('use',{'Use.java':consumer or 'package p;import p.Api.ApiBuilder;class Use {Api read(){ApiBuilder builder=Api.builder();return builder.path("x").id(1).build();}}'},lombok='model')
  use['sources']=[dict(s,publish=False) for s in lib['sources']]+use['sources']
  for ctx in [lib,use]:lombok.Lombok.controls(self,ctx,{'lombok.config':'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})
  return [use,lib]
 def targets(self,facts):
  by={f['id']:f for f in facts};return [(by[f['target']]['signature'],f) for f in facts if f['kind']=='call_target' and f['source']=='use/Use.java']
 def variable(self,facts):return next(f for f in facts if f['kind']=='declaration' and f['source']=='use/Use.java' and f['name']=='builder')
 def test_generated_imported_local_keeps_original_error_and_adds_auditable_proof(self):
  facts=self.analyze(self.plan())['facts'];v=self.variable(facts);a=type_attributes(v)
  self.assertEqual(a['type_model']['kind'],'ERROR');self.assertEqual(a['supplemental_variable']['type_model']['element'],'p.Api$ApiBuilder')
  self.assertTrue(a['supplemental_variable']['original_evidence_retained']);self.assertEqual(len(a['supplemental_variable']['signature_sha256']),64)
  self.assertEqual(len(self.targets(facts)),4);self.assertTrue(all(f['status']=='possible' and not f['evidence_complete'] for _,f in self.targets(facts)))
  self.assertTrue([f for f in facts if f['kind']=='diagnostic' and f['status']=='ERROR'])
 def test_cache_and_upstream_type_changes_and_annotation_removal_keep_full_delta(self):
  plan=self.plan();base=self.analyze(plan)['facts'];self.assertEqual({f['id']:f for f in base},{f['id']:f for f in self.analyze(plan,cache=True)['facts']})
  changed=self.plan(consumer='package p;import p.Api.ApiBuilder;class Use {Api read(){ApiBuilder builder=Api.builder();return builder.path("changed").id(2).build();}}')
  delta=self.analyze(changed,base,cache=True);full=self.analyze(changed)['facts'];self.assertEqual(self.merged(base,delta),sorted(full,key=lambda f:f['id']))
  removed=self.plan()
  for ctx in removed:
   for s in ctx['sources']:
    if s['path']=='lib/Api.java':
     p=pathlib.Path(s['input']);p.write_text(p.read_text().replace('@lombok.Builder ',''));s['hash']=models.sha(p.read_bytes())
   ctx['digest']=models.sha(json.dumps([(s['path'],s['hash']) for s in ctx['sources']]).encode())
  cold=self.analyze(removed)['facts'];self.assertEqual(self.merged(full,self.analyze(removed,full,cache=True)),sorted(cold,key=lambda f:f['id']))
  self.assertFalse(self.targets(cold));self.assertNotIn('supplemental_variable',self.variable(cold)['attributes'])
 def test_partial_upstream_declaration_overlay_remains_source(self):
  facts=self.analyze(self.plan('@lombok.experimental.Delegate String delegate;'))['facts']
  self.assertEqual(len(self.targets(facts)),1);self.assertNotIn('supplemental_variable',self.variable(facts)['attributes'])
 def test_same_world_unpublished_sources_do_not_become_generated_headers(self):
  use,lib=self.plan()
  for s in use['sources']:s.update(world=use['world'],module=use['module'])
  facts=self.analyze([use])['facts'];self.assertEqual(len(self.targets(facts)),1);self.assertNotIn('supplemental_variable',self.variable(facts)['attributes'])
 def test_incompatible_argument_does_not_get_recovered_target(self):
  plan=self.plan(consumer='package p;import p.Api.ApiBuilder;class Use {Api read(){ApiBuilder builder=Api.builder();return builder.path(42).id(1).build();}}')
  facts=self.analyze(plan)['facts'];self.assertEqual(len(self.targets(facts)),1)
 def test_broken_neighbor_does_not_discard_healthy_generated_module_proof(self):
  plan=self.plan();bad=self.context('broken',{'Broken.java':'package bad;class Broken {Missing value;}'},lombok='model')
  plan[0]['sources']=[dict(s,publish=False) for s in bad['sources']]+plan[0]['sources'];plan.append(bad)
  facts=self.analyze(plan)['facts'];self.assertEqual(len(self.targets(facts)),4);self.assertIn('supplemental_variable',self.variable(facts)['attributes'])
 def test_parse_failed_source_prevents_headers_for_its_entire_world(self):
  use,lib=self.plan()
  bad=self.context('malformed',{'Broken.java':'package p;class Broken {int value;'})['sources'][0]
  bad.update(world=lib['world'],module=lib['module'],path='lib/Broken.java')
  lib['sources'].append(bad);use['sources'].append(dict(bad,publish=False))
  facts=self.analyze([use,lib])['facts']
  self.assertEqual(len(self.targets(facts)),1)
  self.assertNotIn('supplemental_variable',self.variable(facts)['attributes'])
if __name__=='__main__':unittest.main(verbosity=2)
