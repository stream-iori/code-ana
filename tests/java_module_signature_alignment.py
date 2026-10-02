import os,sys,unittest,json,hashlib,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tests'));import java_model_tests as models;sha=models.sha

class ModuleProof(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged
 def reactor(self,body='"before"',value=7):
  library=self.context('lib',{'p/Api.java':'''package p;import java.lang.annotation.*;
   @Retention(RetentionPolicy.RUNTIME) @interface Mark {int value() default 3;}
   @Mark public class Api<T extends Number & Comparable<T>> {
    public static final int COUNT=VALUE;public T value;
    public T get(){int local=COUNT;System.out.println(local);return value;}
    public <U extends T> U echo(U input){return input;}
    public class Inner<V>{public V read(V value){return value;}}
    public enum Mode{ONE,TWO}
   }'''.replace('VALUE',str(value))})
  middle=self.context('mid',{'p/Middle.java':'''package p; public class Middle extends Api<Integer> {
   public Integer copy(){ Integer x=get();return echo(x);}
  }'''})
  client=self.context('app',{'p/Use.java':'''package p;@Mark class Use extends Middle {
   void run(){Integer x=copy();Api<Integer>.Inner<String> i=new Api<Integer>().new Inner<String>();
    i.read("value");Api.Mode.valueOf("ONE");System.out.println(COUNT+BODY);}
  }'''.replace('BODY',body)})
  middle['sources']=[dict(u,publish=False) for u in library['sources']]+middle['sources']
  client['sources']=[dict(u,publish=False) for u in middle['sources']]+client['sources']
  for ctx in (library,middle,client):ctx['digest']=sha(json.dumps([(u['path'],u['hash']) for u in ctx['sources']],sort_keys=True).encode())
  return [client,middle,library] # Deliberately consumers first: plan must reorder safely.
 def assert_facts(self,a,b):
  self.assertEqual({f['id']:f for f in a},{f['id']:f for f in b})
 def test_generic_source_inventory_topology_and_cache_disabled_equal(self):
  contexts=self.reactor();cold=self.analyze(contexts);selected=self.analyze(contexts,cache=True)
  self.assertEqual(cold['compiler_sources'],6);self.assertEqual(selected['compiler_sources'],3)
  self.assert_facts(cold['facts'],selected['facts']);self.assertEqual(selected['compiler_launches'],1)
  # An annotation must not force javac to complete an unrelated annotated nested type.
  import zipfile
  archives=list((self.root/'signature-cache-modules').glob('*/signatures.jar'))
  annotation_headers=[]
  for archive in archives:
   with zipfile.ZipFile(archive) as jar:
    if 'p/Mark.class' in jar.namelist():annotation_headers.append(jar.read('p/Mark.class'))
  self.assertEqual(len(annotation_headers),1)
  self.assertNotIn(b'InnerClasses',annotation_headers[0])

 def test_warm_cold_delta_corrupt_and_upstream_edit_equal(self):
  first=self.analyze(self.reactor(),cache=True)['facts']
  contexts=self.reactor('"after"');delta=self.analyze(contexts,first,cache=True)
  self.assert_facts(self.merged(first,delta),self.analyze(contexts)['facts'])
  archives=list((self.root/'signature-cache-modules').glob('*/signatures.jar'));self.assertTrue(archives)
  for archive in archives:archive.write_bytes(b'corrupt NoCode archive')
  corrupted=self.analyze(contexts,cache=True)
  self.assert_facts(corrupted['facts'],self.analyze(contexts)['facts'])
  contexts=self.reactor('"again"',8);edited=self.analyze(contexts,corrupted['facts'],cache=True)
  self.assert_facts(self.merged(corrupted['facts'],edited),self.analyze(contexts)['facts'])
 def test_main_test_module_boundary_is_identical(self):
  contexts=self.reactor();test=self.context('tests',{'p/Check.java':'package p;class Check {void run(){new Middle().copy();}}'})
  test.update(module='app',world='app-test-world',scope='TEST',context='app@TEST')
  for row in test['sources']:row.update(world=test['world'],scope='TEST')
  test['sources']=[dict(row,publish=False) for row in contexts[0]['sources']]+test['sources'];contexts.append(test)
  self.assert_facts(self.analyze(contexts)['facts'],self.analyze(contexts,cache=True)['facts'])
 def test_test_only_prerequisites_cache_and_invalidation_do_not_publish_main(self):
  def contexts(value='1'):
   main=self.context('main',{'p/Api.java':'package p;public class Api<T extends Number> {public static final int VALUE='+value+';public static int read(){return VALUE;}public static class Nested{}}'})
   for row in main['sources']:row.update(publish=False,auxiliary=True)
   main['prerequisite']=True
   test=self.context('checks',{'p/Check.java':'package p;class Check {int read(){Api.Nested n=new Api.Nested();return Api.read()+Api.VALUE;}}'})
   test.update(scope='TEST',context='checks@TEST')
   for row in test['sources']:row.update(scope='TEST')
   test['sources']=[dict(row) for row in main['sources']]+test['sources']
   test['digest']=sha(json.dumps([(s['path'],s['hash']) for s in test['sources']]).encode())
   return [test,main]
  plan=contexts();uncached=self.analyze(plan);cached=self.analyze(plan,cache=True)
  self.assert_facts(uncached['facts'],cached['facts'])
  self.assertGreater(cached['module_signature_report']['module_archives_used'],0)
  self.assertEqual(cached['compiler_sources'],2)
  self.assertFalse([f for f in cached['facts'] if f['owner']['world']==plan[1]['world'] or f['source'].startswith('main/')])
  edited=contexts('2');delta=self.analyze(edited,cached['facts'],cache=True)
  self.assert_facts(self.merged(cached['facts'],delta),self.analyze(edited)['facts'])
  self.assertNotEqual(cached['facts'],self.analyze(edited)['facts'])
  for archive in (self.root/'signature-cache-modules').glob('*/signatures.jar'):archive.write_bytes(b'corrupt prerequisite')
  self.assert_facts(self.analyze(edited,cache=True)['facts'],self.analyze(edited)['facts'])
  self.assert_facts(self.analyze(edited,parallelism=2,cache=True)['facts'],self.analyze(edited)['facts'])

 def test_auxiliary_publication_and_non_main_prerequisites_are_rejected(self):
  ctx=self.context('hidden',{'A.java':'class A {}'})
  ctx['sources'][0]['auxiliary']=True
  with self.assertRaisesRegex(AssertionError,'JAVA_AUXILIARY_PUBLICATION'):self.analyze([ctx])
  with self.assertRaisesRegex(AssertionError,'JAVA_PUBLICATION_INDEX'):self.analyze([ctx],compact=True)
  ctx['sources'][0]['publish']=False;ctx.update(prerequisite=True,scope='TEST')
  with self.assertRaisesRegex(AssertionError,'JAVA_PREREQUISITE_SCOPE'):self.analyze([ctx])

 def test_parallel_dependency_batches_preserve_fact_set(self):
  contexts=self.reactor()
  cold=self.analyze(contexts,parallelism=2)
  cached=self.analyze(contexts,parallelism=2,cache=True)
  self.assert_facts(cold['facts'],cached['facts'])
  self.assertEqual(cached['compiler_sources'],3)
  # Middle consumes lib once; app consumes both lib and middle.
  self.assertEqual(cached['module_signature_report']['module_archives_used'],3)
 def test_corrupt_catalogue_crash_cleanup_and_profile_change(self):
  contexts=self.reactor();self.analyze(contexts,cache=True)
  root=self.root/'signature-cache-modules'
  for file in root.glob('*/catalogue.bin'):file.write_bytes(b'corrupt catalogue')
  abandoned=root/'.module-crashed';abandoned.mkdir();(abandoned/'partial').write_text('unfinished')
  rebuilt=self.analyze(contexts,cache=True)
  self.assert_facts(rebuilt['facts'],self.analyze(contexts)['facts'])
  self.assertFalse(abandoned.exists())
  self.mutate_request=lambda request:request.update(profile='changed-semantic-profile')
  changed=self.analyze(contexts,cache=True)
  self.assert_facts(changed['facts'],self.analyze(contexts)['facts'])
  self.assertGreater(changed['module_signature_report']['module_signatures_published'],0)
 def test_incomplete_upstream_is_not_promoted(self):
  contexts=self.reactor();source=self.root/'lib/p/Api.java';source.write_text(source.read_text().replace('return value;','return Missing.value;'))
  for ctx in contexts:
   for row in ctx['sources']:
    if row['path']=='lib/p/Api.java':row['hash']=sha(source.read_bytes())
  cold=self.analyze(contexts)
  cached=self.analyze(contexts,cache=True)
  self.assert_facts(cold['facts'],cached['facts'])
  self.assertEqual(cached['compiler_sources'],6)
  self.assertEqual(cached['module_signature_report']['module_archives_used'],0)
 def test_catalogue_lru_inflight_lease_shared_eviction_and_cancel(self):
  import subprocess
  contexts=[self.context('lib'+str(i),{f'Api{i}.java':f'public class Api{i} {{ '+''.join(f'public int value{j}(){{return {j};}}' for j in range(50))+' }'}) for i in range(4)]
  contexts.append(self.context('client',{'Client.java':'class Client { int value(){return 1;} }'}))
  request=dict(java_home=str(models.JDK8),bridge=str(ROOT/'lang-adaptor/target/compiler-bridge.jar'),scratch=str(self.root),signature_cache=True,cache_root=str(self.root/'cache'),profile='module-lru',contexts=contexts)
  file=self.root/'request.json';file.write_text(json.dumps(request))
  cp=os.pathsep.join([str(ROOT/'lang-adaptor/target/classes'),str(ROOT/'lang-adaptor/target/test-classes'),(ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()])
  result=subprocess.run([str(models.JDK25/'bin/java'),'-cp',cp,'dev.codeana.analysis.ModuleCacheProbe',str(file)],text=True,capture_output=True,timeout=90)
  self.assertEqual(result.returncode,0,result.stderr)
  self.assertTrue(json.loads(result.stdout)['accepted'])
  self.assertFalse(list(self.root.glob('compiler-*')))
  self.assertFalse(list(self.root.rglob('*.class')))
if __name__=='__main__':unittest.main(verbosity=2)
