import os,pathlib,sys,unittest,json
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'));import java_model_tests as model;import java_module_signature_alignment as base
class ParallelModules(unittest.TestCase):
 setUp=model.Models.setUp;context=model.Models.context;analyze=model.Models.analyze;merged=model.Models.merged;assert_facts=base.ModuleProof.assert_facts
 def reactor(self,value=1):
  left=self.context('left',{'p/Left.java':'package p;public class Left<T extends Number> {public static final int N=VALUE;public T field;public T get(){return field;}}'.replace('VALUE',str(value))})
  right=self.context('right',{'p/Right.java':'package p;public class Right {public static int read(){return 2;}public static class Nested{}}'})
  client=self.context('client',{'p/Use.java':'package p;class Use {int read(Left<Integer> left){return left.get()+Left.N+Right.read();}}'})
  client['sources']=[dict(x,publish=False) for x in left['sources']+right['sources']]+client['sources']
  for ctx in (left,right,client):ctx['digest']=model.sha(json.dumps([(x['path'],x['hash']) for x in ctx['sources']]).encode())
  return [client,right,left]
 def test_independent_parents_overlap_and_consumer_uses_nocode(self):
  plan=self.reactor();cold=self.analyze(plan);parallel=self.analyze(plan,cache=True,parallelism=2)
  self.assert_facts(cold['facts'],parallel['facts']);self.assertEqual(parallel['compiler_peak'],2)
  self.assertEqual(cold['compiler_sources'],5);self.assertEqual(parallel['compiler_sources'],3)
  self.assertEqual(parallel['module_signature_report']['module_archives_used'],2)
  self.assertEqual(parallel['module_signature_report']['module_signature_fallbacks'],{})
 def test_parallel_delta_corruption_and_upstream_invalidate(self):
  first=self.analyze(self.reactor(),cache=True,parallelism=2)['facts'];plan=self.reactor(3)
  delta=self.analyze(plan,first,cache=True,parallelism=2);self.assert_facts(self.merged(first,delta),self.analyze(plan)['facts'])
  for path in (self.root/'signature-cache-modules').glob('*/signatures.jar'):path.write_bytes(b'bad')
  self.assert_facts(self.analyze(plan,cache=True,parallelism=2)['facts'],self.analyze(plan)['facts'])
 def test_parallel_three_level_generic_fixture_equals_source(self):
  plan=base.ModuleProof.reactor(self)
  cold=self.analyze(plan);parallel=self.analyze(plan,cache=True,parallelism=4)
  self.assert_facts(cold['facts'],parallel['facts']);self.assertEqual(parallel['compiler_sources'],3)
 def test_hidden_main_parallel_still_does_not_publish_prerequisites(self):
  plan=self.reactor()
  for ctx in plan[1:]:
   ctx['prerequisite']=True
   for row in ctx['sources']:row.update(publish=False,auxiliary=True)
  for row in plan[0]['sources']:
   if not row['publish']:row['auxiliary']=True
  cold=self.analyze(plan);parallel=self.analyze(plan,cache=True,parallelism=2)
  self.assert_facts(cold['facts'],parallel['facts']);self.assertEqual(parallel['compiler_sources'],3)
  self.assertFalse([f for f in parallel['facts'] if f['owner']['world'] in {x['world'] for x in plan[1:]}])
 def test_duplicate_binary_names_stay_isolated_across_parallel_modules(self):
  left=self.context('left',{'p/Shared.java':'package p;public class Shared {public int left(){return 1;}}'})
  right=self.context('right',{'p/Shared.java':'package p;public class Shared {public String right(){return "x";}}'})
  useleft=self.context('useleft',{'p/UseLeft.java':'package p;class UseLeft {int read(){return new Shared().left();}}'})
  useright=self.context('useright',{'p/UseRight.java':'package p;class UseRight {String read(){return new Shared().right();}}'})
  useleft['sources']=[dict(x,publish=False) for x in left['sources']]+useleft['sources'];useright['sources']=[dict(x,publish=False) for x in right['sources']]+useright['sources']
  plan=[useright,useleft,right,left];cold=self.analyze(plan);parallel=self.analyze(plan,cache=True,parallelism=4)
  self.assert_facts(cold['facts'],parallel['facts']);self.assertEqual(parallel['compiler_sources'],4)
  self.assertFalse([f for f in parallel['facts'] if f['kind']=='diagnostic'])
 def test_cyclic_worlds_use_source_without_deadlock(self):
  left=self.context('left',{'p/Left.java':'package p;public class Left {Right other;public int read(){return 1;}}'})
  right=self.context('right',{'p/Right.java':'package p;public class Right {Left other;public int read(){return 2;}}'})
  a=list(left['sources']);b=list(right['sources']);left['sources']=[dict(x,publish=False) for x in b]+a;right['sources']=[dict(x,publish=False) for x in a]+b
  plan=[right,left];cold=self.analyze(plan);parallel=self.analyze(plan,cache=True,parallelism=2)
  self.assert_facts(cold['facts'],parallel['facts']);self.assertEqual(parallel['module_signature_report']['module_archives_used'],0)
  self.assertEqual(parallel['module_signature_report']['module_signatures_published'],0)
  self.assertEqual(len(parallel['module_signature_report']['module_signature_fallbacks']),2)
 def test_consumer_only_delta_with_corrupt_parent_uses_full_source(self):
  plan=self.reactor();first=self.analyze(plan,cache=True,parallelism=2)['facts']
  file=self.root/'client/p/Use.java';file.write_text(file.read_text().replace('Left.N+Right.read()','Left.N+Right.read()+3'))
  plan[0]['sources'][-1]['hash']=model.sha(file.read_bytes());plan[0]['digest']=model.sha(json.dumps([(x['path'],x['hash']) for x in plan[0]['sources']]).encode())
  for path in (self.root/'signature-cache-modules').glob('*/signatures.jar'):path.write_bytes(b'bad')
  delta=self.analyze(plan,first,cache=True,parallelism=2);self.assert_facts(self.merged(first,delta),self.analyze(plan)['facts'])
  self.assertEqual(delta['compiler_sources'],3);self.assertEqual(delta['module_signature_report']['module_archives_used'],0)
 def test_mediated_classpath_uses_producer_abi_like_frozen_anatomist(self):
  import subprocess,shutil,tempfile,hashlib
  jars=[]
  for value in (1,2):
   folder=self.root/('constant'+str(value));folder.mkdir();source=folder/'Constants.java';source.write_text('package ext;public class Constants {public static final int VALUE='+str(value)+';}')
   classes=folder/'classes';classes.mkdir();subprocess.run([str(model.JDK8/'bin/javac'),'-proc:none','-d',str(classes),str(source)],check=True,capture_output=True)
   jar=folder/'constants.jar';subprocess.run([str(model.JDK8/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True);shutil.rmtree(classes);jars.append(str(jar))
  library=self.context('lib',{'p/Lib.java':'package p;public class Lib {public static final int VALUE=ext.Constants.VALUE;}'},classpath=[jars[0]])
  client=self.context('client',{'p/Use.java':'package p;class Use {static final int VALUE=Lib.VALUE;}'},classpath=[jars[1]])
  client['sources']=[dict(x,publish=False) for x in library['sources']]+client['sources'];plan=[client,library]
  cold=self.analyze(plan);cached=self.analyze(plan,cache=True,parallelism=2)
  field=next(f for f in cold['facts'] if f['signature']=='p.Use#VALUE');self.assertEqual(field['attributes']['constant_value'],2)
  print('constant proof:',json.dumps({mode:[(f['signature'],f['attributes'].get('constant_value')) for f in result['facts'] if f['signature'] in ('p.Use#VALUE','p.Lib#VALUE')] for mode,result in [('full_source',cold),('module_cache',cached)]}),flush=True)
  self.assertEqual(next(f['attributes']['constant_value'] for f in cached['facts'] if f['signature']=='p.Use#VALUE'),1)
  self.assertEqual(cached['module_signature_report']['module_archives_used'],1)
  self.assertEqual(cached['compiler_sources'],2)
  # The oracle runs the frozen backend's real Maven module/signature plan, not a
  # downstream full-SOURCE recompile of the producer on a different dependency version.
  jar=ROOT.parent/'anatomist/target/anatomist.jar'
  self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(),'457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e')
  with tempfile.TemporaryDirectory() as folder:
   reference=pathlib.Path(folder)
   subprocess.run([str(model.JDK25/'bin/javac'),'-cp',str(jar),'-d',str(reference),
       str(ROOT/'lang-adaptor/src/frozen-test/java/com/anatomist/core/javac/FrozenModuleClasspathReferenceProbe.java')],check=True,capture_output=True,timeout=30)
   result=subprocess.run([str(model.JDK25/'bin/java'),'-cp',str(reference)+os.pathsep+str(jar),
       'com.anatomist.core.javac.FrozenModuleClasspathReferenceProbe',str(reference/'project'),str(model.JDK8),*jars],check=True,capture_output=True,text=True,timeout=60)
   self.assertIn('seen=1',result.stdout);self.assertIn('direct=2',result.stdout)
   self.assertIn('archivesUsed=1',result.stdout);self.assertIn('sourceAppearances=2',result.stdout)
if __name__=='__main__':unittest.main(verbosity=2)
