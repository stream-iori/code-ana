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
 def test_upstream_body_errors_survive_header_reuse_without_changing_facts(self):
  contexts=self.reactor();source=self.root/'lib/p/Api.java';source.write_text(source.read_text().replace('return value;','return Missing.value;'))
  for ctx in contexts:
   for row in ctx['sources']:
    if row['path']=='lib/p/Api.java':row['hash']=sha(source.read_bytes())
  cold=self.analyze(contexts)
  cached=self.analyze(contexts,cache=True)
  self.assert_facts(cold['facts'],cached['facts'])
  self.assertEqual(cached['compiler_sources'],3)
  self.assertGreater(cached['module_signature_report']['module_archives_used'],0)
  self.assertTrue([f for f in cached['facts'] if f['kind']=='diagnostic' and f['status']=='ERROR'])

 def test_consumer_classpath_keeps_producer_constants_and_own_binary_resolution(self):
  import tempfile,subprocess,zipfile
  with tempfile.TemporaryDirectory() as folder:
   root=pathlib.Path(folder);jars=[]
   for value in (1,2):
    source=root/f'v{value}/dep/Version.java';source.parent.mkdir(parents=True)
    source.write_text(f'package dep;public class Version {{public static final int VALUE={value};}}')
    classes=root/f'classes{value}';classes.mkdir()
    subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-d',str(classes),str(source)],check=True,capture_output=True,timeout=30)
    jar=root/f'v{value}.jar'
    with zipfile.ZipFile(jar,'w') as out:out.write(classes/'dep/Version.class','dep/Version.class')
    jars.append(str(jar))
   lib=self.context('lib',{'api/Api.java':'package api;public class Api {public static final int VALUE=dep.Version.VALUE;}'},classpath=[jars[0]])
   use=self.context('use',{'p/Use.java':'package p;public class Use {public static final int seen=api.Api.VALUE;public static final int direct=dep.Version.VALUE;}'},classpath=[jars[1]])
   use['sources']=[dict(s,publish=False) for s in lib['sources']]+use['sources']
   contexts=[use,lib];result=self.analyze(contexts,cache=True)
   constants={f['name']:f['attributes']['constant_value'] for f in result['facts'] if f['kind']=='declaration' and f['source']=='use/p/Use.java' and 'constant_value' in f['attributes']}
   self.assertEqual(constants,{'seen':1,'direct':2})
   self.assertEqual(result['compiler_sources'],2)
   self.assert_facts(result['facts'],self.analyze(contexts,cache=True)['facts'])
   # Independent SDK8 module compilation, restricted to this generated fixture.
   classes=root/'module-lib';classes.mkdir()
   subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-cp',jars[0],'-d',str(classes),lib['sources'][0]['input']],check=True,capture_output=True,timeout=30)
   archive=root/'api.jar'
   with zipfile.ZipFile(archive,'w') as out:out.write(classes/'api/Api.class','api/Api.class')
   output=root/'module-use';output.mkdir()
   subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-cp',str(archive)+os.pathsep+jars[1],'-d',str(output),use['sources'][-1]['input']],check=True,capture_output=True,timeout=30)
   text=subprocess.run([str(models.JDK8/'bin/javap'),'-constants',str(output/'p/Use.class')],check=True,capture_output=True,text=True,timeout=30).stdout
   self.assertIn('seen = 1',text);self.assertIn('direct = 2',text)
   # A producer classpath change invalidates the catalogue even with unchanged sources.
   lib['classpath']=[jars[1]]
   changed=self.analyze(contexts,cache=True)
   self.assertEqual(next(f['attributes']['constant_value'] for f in changed['facts'] if f['signature']=='p.Use#seen'),2)
   self.assert_facts(changed['facts'],self.analyze(contexts)['facts'])

 def test_incomplete_declaration_header_keeps_source_and_diagnostics(self):
  contexts=self.reactor();source=self.root/'lib/p/Api.java'
  source.write_text(source.read_text().replace('public T value;','public Missing value;'))
  for ctx in contexts:
   for row in ctx['sources']:
    if row['path']=='lib/p/Api.java':row['hash']=sha(source.read_bytes())
  cold=self.analyze(contexts);cached=self.analyze(contexts,cache=True)
  self.assert_facts(cold['facts'],cached['facts'])
  self.assertGreater(cached['compiler_sources'],3)
  reasons=str(cached['module_signature_report']['module_signature_fallbacks'])
  self.assertTrue('SIGNATURE_WRITTEN_TYPE_INCOMPLETE' in reasons or 'INCOMPLETE_SIGNATURE_TYPE' in reasons,reasons)

 def test_compact_catalogue_omits_leaf_body_variables_but_preserves_full_facts(self):
  from java_model_api_inventory_diff import archive_rows
  lib=self.context('lib',{'Api.java':'public class Api {public int read(){'+''.join(f'int local{i}={i};' for i in range(200))+'return local199;}}'})
  use=self.context('use',{'Use.java':'class Use {int read(){return new Api().read();}}'})
  use['sources']=[dict(s,publish=False) for s in lib['sources']]+use['sources']
  result=self.analyze([use,lib],cache=True)
  self.assert_facts(result['facts'],self.analyze([use,lib])['facts'])
  rows=[row['value'] for file in (self.root/'signature-cache-modules').glob('*/catalogue.bin')
        for row in archive_rows(file) if row.get('kind')=='row' and row.get('section')=='declarations']
  self.assertTrue(rows)
  self.assertFalse([d for d in rows if d['attributes']['element_kind']=='LOCAL_VARIABLE'])
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
