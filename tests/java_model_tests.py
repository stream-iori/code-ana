#!/usr/bin/env python3
"""JVM tests of the same detached model/extraction code used by the native library.
These tests are not a replacement for the native or daemon acceptance gates.
"""
import hashlib,json,os,pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
JDK8=pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME',pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu')).resolve()
JDK25=pathlib.Path(os.environ.get('JAVA_HOME',pathlib.Path.home()/'.sdkman/candidates/java/25.3.4+1.r25-graalce'))
sha=lambda b:hashlib.sha256(b).hexdigest()
key=lambda n,*p:n+'_'+sha(json.dumps(p,ensure_ascii=False,separators=(',',':')).encode())
def type_node(attributes,value):
 """Borrow a public root node; graph child roles remain numeric references."""
 if isinstance(value,dict) and '$java_type' in value:
  graph,slot=value['$java_type'];payload=attributes['java_type_graphs'][graph]
  return payload['nodes'][payload['roots'][slot]]
 return value
def initializer_sources(value='1'):
 text='''import java.util.stream.Stream; import java.util.function.Supplier;
 class A {
  static { int same=VALUE; Stream.of(same).forEach(v -> { int local=v; System.out.println(local); }); }
  { int other=VALUE; Stream.of(other).forEach(v -> System.out.println(v)); }
  Supplier<Integer> supplier=() -> { int local=VALUE; return local; };
 }'''.replace('VALUE',value)
 return {'A.java':text,'B.java':text.replace('class A','class B'),
         'E.java':'enum E { ONE; int index; static { java.util.Arrays.stream(values()).collect(java.util.stream.Collectors.toMap(v -> v.index,v -> v,(a,b) -> {throw new RuntimeException(a.name());})); } }'}
def assert_initializer_facts(test, facts):
 test.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
 declarations={f['id']:f for f in facts if f['kind']=='declaration'}
 locals=[f for f in declarations.values() if '#local:' in f['signature']]
 test.assertGreaterEqual(len(locals),16)
 for local in locals:
  owner=declarations[local['parent']]
  test.assertIn(owner['attributes']['element_kind'],('INITIALIZER','LAMBDA'))
  test.assertTrue(local['signature'].startswith(owner['signature'].split('#')[0]+'#'),(local,owner))
  if '#<clinit@' in local['signature']:
   test.assertTrue(local['signature'].startswith(owner['signature']+'#local:'),(local,owner))
 ids={f['id'] for f in locals}
 test.assertTrue(any(f['kind']=='reference' and f['target'] in ids for f in facts))
 test.assertEqual(len(ids),len(locals))
def compact_request(request):
 tables=[];paths={};classpath=[];jars={};contexts=[]
 def input_slot(row):
  path=row['unit']['path']
  if path in paths:
   index=paths[path]
   if tables[index]!=row:raise ValueError('conflicting fixture input')
   return index
  index=len(tables);paths[path]=index;tables.append(row);return index
 for old in request['contexts']:
  context=dict(old);source_indices=[];publish_indices=[];resource_indices=[];classpath_indices=[]
  for source in old.get('sources',[]):
   unit={k:v for k,v in source.items() if k not in ('input','publish')}
   unit.update(language='java',size=pathlib.Path(source['input']).stat().st_size)
   index=input_slot(dict(unit=unit,input=source['input'],publish=False))
   source_indices.append(index)
   if source.get('publish'):publish_indices.append(index)
  for resource in old.get('resources',[]):
   unit=dict(resource['unit']);unit.setdefault('language','');unit.setdefault('scope','RESOURCE');unit.setdefault('module','');unit.setdefault('size',pathlib.Path(resource['input']).stat().st_size)
   resource_indices.append(input_slot(dict(unit=unit,input=resource['input'],publish=False)))
  for jar in old.get('classpath',[]):
   if jar not in jars:jars[jar]=len(classpath);classpath.append(jar)
   classpath_indices.append(jars[jar])
  context.update(sources=[],resources=[],classpath=[],source_indices=source_indices,publish_indices=publish_indices,resource_indices=resource_indices,classpath_indices=classpath_indices)
  contexts.append(context)
 return request|dict(compact_inputs=True,input_table=tables,classpath_table=classpath,contexts=contexts)

class Models(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(prefix='java-model-test-');self.addCleanup(self.temp.cleanup);self.root=pathlib.Path(self.temp.name)
 def context(self,name,files,**options):
  world=key('world',name,'MAIN');sources=[]
  for path,text in files.items():
   source=self.root/name/path;source.parent.mkdir(parents=True,exist_ok=True);source.write_text(text,encoding='utf8')
   sources.append(dict(path=name+'/'+path,input=str(source),hash=sha(source.read_bytes()),world=world,module=name,scope='MAIN',publish=True))
  digest=sha(json.dumps([(s['path'],s['hash']) for s in sources],sort_keys=True).encode())
  return dict(version=7,context=name+'@MAIN',module=name,world=world,scope='MAIN',sources=sources,classpath=[],digest=digest,lombok='off',spring=False,spring_xml=False,sofa=False,resources=[])|options
 def analyze(self,contexts,baseline=None,parallelism=1,cache=False,compact=False):
  request=dict(version=1,facts=True,full=baseline is None,baseline=baseline or [],java_home=str(JDK8),bridge=os.environ.get('CODEANA_TEST_BRIDGE',str(ROOT/'lang-adaptor/target/compiler-bridge.jar')),scratch=str(self.root),contexts=contexts,parallelism=parallelism)
  if cache:request.update(signature_cache=True,cache_root=str(self.root/'signature-cache'),profile='fixture-profile')
  if compact:request=compact_request(request)
  if hasattr(self,'mutate_request'):self.mutate_request(request)
  path=self.root/'request.json';path.write_text(json.dumps(request,sort_keys=True))
  dependencies=(ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
  classpath=os.pathsep.join([str(ROOT/'lang-adaptor/target'/d) for d in ['classes','test-classes']]+[dependencies])
  if os.environ.get('CODEANA_TEST_CLASSES'):classpath=os.environ['CODEANA_TEST_CLASSES']+os.pathsep+classpath
  process=subprocess.run([str(JDK25/'bin/java'),'-cp',classpath,getattr(self,'probe','dev.codeana.analysis.AnalysisProbe'),str(path)],capture_output=True,text=True,timeout=140)
  self.assertEqual(process.returncode,0,process.stderr)
  result=json.loads(process.stdout);self.assertFalse(list(self.root.glob('compiler-*')))
  self.assertFalse(list(self.root.rglob('*.class')))
  return result
 def merged(self,baseline,result):
  owners={json.dumps(o,sort_keys=True) for o in result['replacements']}
  result=[f for f in baseline if json.dumps(f['owner'],sort_keys=True) not in owners]+result['facts']
  ids={f['id'] for f in result};self.assertEqual(len(ids),len(result))
  for f in result:
   for field in ['parent','target']:
    if f[field]:self.assertIn(f[field],ids,f)
  return sorted(result,key=lambda f:f['id'])
 def labeled_contexts(self, value='"a"'):
  physical=self.context('physical',{
   'src/p/Api.java':'package p; public class Api { public static String echo(String s){return s;} public static String read(){return Bridge.value();} }',
   'bridge/p/Bridge.java':'package p; public class Bridge { public static String value(){return '+value+';} public static String read(){return Api.echo(value());} }',
   'tests/p/Checks.java':'package p; class Checks { String read(){return Api.read()+Bridge.read();} }'})
  for unit in physical['sources']:
   label={'src':'api','bridge':'bridge','tests':'tests'}[unit['path'].split('/')[1]]
   unit.update(module=label,scope='TEST' if label=='tests' else 'MAIN')
   unit['world']=key('world','physical',unit['scope'])
  contexts=[]
  for scope in ('MAIN','TEST'):
   units=[dict(u,publish=u['scope']==scope) for u in physical['sources'] if scope=='TEST' or u['scope']=='MAIN']
   contexts.append(physical|dict(context='physical@'+scope,scope=scope,world=key('world','physical',scope),sources=units,digest=sha(json.dumps(units,sort_keys=True).encode())))
  return contexts
 def assert_labeled_facts(self, facts):
  self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
  for signature,label,scope in [('p.Api','api','MAIN'),('p.Bridge','bridge','MAIN'),('p.Checks','tests','TEST')]:
   declarations=[f for f in facts if f['kind']=='declaration' and f['signature']==signature]
   self.assertEqual(len(declarations),1,(signature,declarations))
   self.assertEqual(declarations[0]['attributes']['logical_key'],key('logical',label,signature))
   self.assertEqual(declarations[0]['owner']['world'],key('world','physical',scope))
  by_id={f['id']:f for f in facts}
  pairs={(by_id[f['parent']]['name'],by_id[f['target']]['signature'],f['owner']['world']) for f in facts if f['kind']=='call_target' and f['status']=='resolved'}
  for site,target,scope in [('Bridge.value','p.Bridge#value()','MAIN'),('Api.echo','p.Api#echo(java.lang.String)','MAIN'),('Api.read','p.Api#read()','TEST'),('Bridge.read','p.Bridge#read()','TEST')]:
   self.assertIn((site,target,key('world','physical',scope)),pairs)
 def test_publication_labels_share_physical_context_and_delta(self):
  contexts=self.labeled_contexts()
  result=self.analyze(contexts)
  self.assertEqual(result['compiler_sources'],5)
  self.assert_labeled_facts(result['facts'])
  changed=self.labeled_contexts('"b"')
  delta=self.analyze(changed,result['facts'])
  full=self.analyze(changed)
  self.assertEqual(self.merged(result['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
  self.assert_labeled_facts(full['facts'])

 def test_shared_input_table_preserves_facts_parallelism_and_delta(self):
  contexts=self.labeled_contexts()
  ordinary=self.analyze(contexts)['facts']
  packed=self.analyze(contexts,compact=True,parallelism=2)['facts']
  self.assertEqual(ordinary,packed)
  changed=self.labeled_contexts('"b"')
  delta=self.analyze(changed,ordinary,compact=True)
  self.assertEqual(self.merged(ordinary,delta),sorted(self.analyze(changed)['facts'],key=lambda f:f['id']))
 def test_numeric_constants_survive_model_fact_archives_and_delta(self):
  def context(value):
   return self.context('numeric',{'Numbers.java':f'class Numbers {{ static final double FRACTION={value}; static final float TINY=1.25e-20f; static final double NAN=0.0/0.0; static final float INFINITY=1.0f/0.0f; double read(){{return FRACTION+TINY;}} }}'})
  first=context('0.125'); baseline=self.analyze([first])['facts']
  declarations=[f for f in baseline if f['kind']=='declaration']
  self.assertTrue(declarations)
  self.assertFalse([f for f in baseline if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
  second=context('0.375'); delta=self.analyze([second],baseline)
  self.assertEqual(self.merged(baseline,delta),sorted(self.analyze([second])['facts'],key=lambda f:f['id']))
 def test_model_record_budget_is_explicit_and_reports_context_phase_source(self):
  context=self.context('budget',{'A.java':'class A { int value; int read(){return value;} }'})
  original=self.analyze([context])['facts']
  self.assertEqual(original,self.analyze([context|dict(model_record_limit=5000000)])['facts'])
  with self.assertRaisesRegex(AssertionError,'MODEL_RECORD_BUDGET context=budget@MAIN phase=parse source=budget/A.java count=2 limit=1'):
   self.analyze([context|dict(model_record_limit=1)])
  for limit in (0,5000001,-1,1.5):
   with self.assertRaisesRegex(AssertionError,'JAVA_MODEL_RECORD_LIMIT_RANGE|JSON_OBJECT_END'):
    self.analyze([context|dict(model_record_limit=limit)])
 def test_archive_byte_limit_covers_compiler_spools_final_decode_and_delta(self):
  context=self.context('archive',{f'C{i}.java':f'class C{i} {{ int x; int read(){{return x+{i};}} }}' for i in range(80)})
  ordinary=self.analyze([context])['facts']
  larger=context|dict(model_archive_bytes=8*1024*1024)
  self.assertEqual(ordinary,self.analyze([larger])['facts'])
  self.assertEqual(self.analyze([larger],ordinary)['facts'],[])
  with self.assertRaisesRegex(AssertionError,'RECORD_ARCHIVE_BUDGET.*byte_limit=65536'):
   self.analyze([context|dict(model_archive_bytes=65536)])
  self.assertFalse(list(self.root.glob('compiler-*')))
  for cap in (0,65535,1024*1024*1024+1,-1,65536.5):
   with self.subTest(cap=cap),self.assertRaisesRegex(AssertionError,'JAVA_MODEL_ARCHIVE_BYTES_RANGE|JSON_OBJECT_END'):
    self.analyze([context|dict(model_archive_bytes=cap)])
  context=self.context('archive',{f'C{i}.java':f'class C{i} {{ int x; int read(){{return x+{i}+1;}} }}' for i in range(80)},model_archive_bytes=8*1024*1024)
  delta=self.analyze([context],ordinary)
  self.assertEqual(self.merged(ordinary,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))

 def test_output_fact_and_byte_budgets_reject_without_leaking_and_preserve_semantics(self):
  context=self.context('limits',{'A.java':'class A { int x; int read(){return x;} }'})
  old=self.analyze([context])['facts']
  self.mutate_request=lambda request:request.update(result_bytes=4*1024*1024*1024,fact_limit=5000000)
  self.assertEqual(old,self.analyze([context])['facts'])
  self.assertEqual(self.analyze([context],old)['facts'],[])
  self.mutate_request=lambda request:request.update(fact_limit=1)
  with self.assertRaisesRegex(AssertionError,'FACT_BUDGET.*count=1 limit=1|RECORD_ARCHIVE_BUDGET.*count_limit=1'):
   self.analyze([context])
  for field,values,reason in [('result_bytes',(0,65535,4*1024*1024*1024+1),'JAVA_RESULT_BYTES_RANGE'),
                              ('fact_limit',(0,5000001),'JAVA_FACT_LIMIT_RANGE')]:
   for value in values:
    self.mutate_request=lambda request,key=field,cap=value:request.update({key:cap})
    with self.subTest(field=field,value=value),self.assertRaisesRegex(AssertionError,reason):
     self.analyze([context])
  many=self.context('limits',{f'C{i}.java':f'class C{i} {{ int x; int read(){{return x+{i};}} }}' for i in range(80)})
  self.mutate_request=lambda request:request.update(result_bytes=65536)
  with self.assertRaisesRegex(AssertionError,'RECORD_ARCHIVE_BUDGET.*byte_limit=65536'):
   self.analyze([many])
  self.assertFalse(list(self.root.glob('compiler-*')))

 def test_invalid_shared_indices_and_publication_reject_before_compiler(self):
  contexts=self.labeled_contexts()
  def change(kind):
   def apply(request):
    context=request['contexts'][1]
    if kind=='range':context['source_indices'].append(999999)
    if kind=='duplicate':context['source_indices'].append(context['source_indices'][0])
    if kind=='publication':context['publish_indices'].append(context['source_indices'][0])
    if kind=='fractional':context['source_indices'].append(.5)
    if kind=='mixed':context['sources']=[{}]
   return apply
  for kind in ('range','duplicate','publication','fractional','mixed'):
   self.mutate_request=change(kind)
   with self.subTest(kind=kind),self.assertRaisesRegex(AssertionError,'JAVA_|JSON_ARRAY_END'):
    self.analyze(contexts,compact=True)
  del self.mutate_request

 def test_core_unicode_and_partial(self):
  ctx=self.context('app',{'App.java':(ROOT/'tests/fixtures/java-native/base/src/p/App.java').read_text()})
  facts=self.analyze([ctx])['facts'];self.merged([],dict(facts=facts,replacements=[]))
  main=next(f for f in facts if f['signature']=='p.App#main(java.lang.String[])')
  sites=[f for f in facts if f['kind']=='call_site' and f['parent']==main['id']]
  self.assertGreaterEqual(len(sites),5)
  data=pathlib.Path(ctx['sources'][0]['input']).read_bytes()
  for site in sites:self.assertTrue(data[site['span']['start']:site['span']['end']].decode('utf8'))
  greet=next(f for f in facts if f['signature']=='p.Service#greet(java.lang.String)')
  self.assertEqual(len([f for f in facts if f['kind']=='call_target' and f['target']==greet['id']]),3)
  ctx=self.context('app',{'App.java':'package p; class App { void bad() { missing(); } }'})
  facts=self.analyze([ctx])['facts'];self.assertTrue(any(f['kind']=='diagnostic' for f in facts))
  self.assertTrue(any(f['kind']=='call_site' and f['status']=='unresolved' for f in facts))
 def test_cold_delta_and_deletion(self):
  a=self.context('a',{'A.java':'class A { void call(){ missing(); } }'})
  b=self.context('b',{'B.java':'class B {}'})
  old=self.analyze([a,b])['facts']
  unchanged=self.analyze([a,b],old);self.assertEqual(unchanged['facts'],[])
  a=self.context('a',{'A.java':'class A { void call(){ missing(); missing(); } void missing(){} }'})
  delta=self.analyze([a,b],old);full=self.analyze([a,b])
  self.assertEqual(self.merged(old,delta),sorted(full['facts'],key=lambda f:f['id']))
  self.assertFalse(any(o['world']==b['world'] for o in delta['replacements']))
  deleted=self.analyze([b],full['facts']);self.assertEqual(self.merged(full['facts'],deleted),sorted(self.analyze([b])['facts'],key=lambda f:f['id']))
 def test_parallel_contexts_are_deterministic(self):
  contexts=[self.context('a',{'A.java':'class A { void a() { System.out.println("a"); } }'}),
            self.context('b',{'B.java':'class B { void b() { System.out.println("b"); } }'})]
  serial=self.analyze(contexts,parallelism=1)
  parallel=self.analyze(contexts,parallelism=2)
  self.assertEqual(serial['facts'],parallel['facts'])
  self.assertEqual(serial['compiler_peak'],1)
  self.assertEqual(parallel['compiler_peak'],2)
 def test_delta_replaces_only_changed_file_owner(self):
  files={'A.java':'class A { void run() { System.out.println("old"); } }',
         'B.java':'class B { void run() { System.out.println("same"); } }'}
  old=self.analyze([self.context('app',files)])['facts']
  files['A.java']=files['A.java'].replace('old','new')
  context=self.context('app',files)
  delta=self.analyze([context],old)
  full=self.analyze([context])
  self.assertEqual(self.merged(old,delta),sorted(full['facts'],key=lambda f:f['id']))
  replaced={o['id'] for o in delta['replacements'] if o['kind']=='source'}
  self.assertEqual(replaced,{'app/A.java'})
 def test_signature_cache_reduces_javac_sources(self):
  files={'A.java':'class A { String text(){ return "a"; } }',
         'B.java':'class B { void call() { System.out.println(new A().text()+"old"); } }'}
  first=self.analyze([self.context('app',files)],cache=True)
  self.assertEqual(len(list((self.root/'signature-cache').glob('*/signatures.jar'))),2)
  files['B.java']=files['B.java'].replace('"old"','"new"')
  context=self.context('app',files)
  delta=self.analyze([context],first['facts'],cache=True)
  full=self.analyze([context])
  self.assertEqual(delta['compiler_sources'],1)
  self.assertEqual(delta['reduced_contexts'],1)
  self.assertEqual(self.merged(first['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
 def test_signature_cache_falls_back_on_declaration_change_or_corruption(self):
  files={'A.java':'class A { void use(){ new B().call(); } }',
         'B.java':'class B { void call(){} }'}
  first=self.analyze([self.context('app',files)],cache=True)['facts']
  files['B.java']='class B { void call(){} void added(){} }'
  changed=self.context('app',files)
  delta=self.analyze([changed],first,cache=True)
  second_full=self.analyze([changed])['facts']
  self.assertEqual(delta['reduced_contexts'],0)
  self.assertEqual(self.merged(first,delta),sorted(second_full,key=lambda f:f['id']))
  for archive in (self.root/'signature-cache').glob('*/signatures.jar'):
   if 'A.class' in subprocess.check_output([str(JDK8/'bin/jar'),'tf',str(archive)],text=True):
    archive.write_bytes(b'corrupt')
  files['B.java']='class B { void call(){ System.out.println("body"); } void added(){} }'
  changed=self.context('app',files)
  corrupted=self.analyze([changed],second_full,cache=True)
  self.assertEqual(corrupted['reduced_contexts'],0)
  self.assertEqual(self.merged(second_full,corrupted),sorted(self.analyze([changed])['facts'],key=lambda f:f['id']))
 def test_signature_cache_supports_generic_and_annotated_sources(self):
  for shape in ['class A<T> { T value; }',
                '@Deprecated class A { void value(){} }']:
   with self.subTest(shape=shape):
    files={'A.java':shape,'B.java':'class B { void run(){ System.out.println("before"); } }'}
    first=self.analyze([self.context('app',files)],cache=True)['facts']
    files['B.java']=files['B.java'].replace('before','after')
    changed=self.context('app',files)
    delta=self.analyze([changed],first,cache=True)
    self.assertEqual(delta['reduced_contexts'],1)
    self.assertEqual(self.merged(first,delta),sorted(self.analyze([changed])['facts'],key=lambda f:f['id']))
 def test_signature_cache_disable_and_spring_context(self):
  files={'A.java':'class A { void call(){} }',
         'B.java':'class B { void run(){ new A().call(); System.out.println("before"); } }'}
  old=self.analyze([self.context('app',files,spring=True)],cache=True)['facts']
  files['B.java']=files['B.java'].replace('before','after')
  changed=self.context('app',files,spring=True)
  disabled=self.analyze([changed],old,cache=False)
  self.assertEqual(disabled['reduced_contexts'],0)
  reduced=self.analyze([changed],old,cache=True)
  self.assertEqual(reduced['reduced_contexts'],1)
  full=self.analyze([changed])['facts']
  self.assertEqual(self.merged(old,disabled),sorted(full,key=lambda f:f['id']))
  self.assertEqual(self.merged(old,reduced),sorted(full,key=lambda f:f['id']))
 def test_failed_lookup_changes_consumer_owner(self):
  files={'A.java':'class A { void run(){ new B().missing(); } }','B.java':'class B {}'}
  old=self.analyze([self.context('app',files)])['facts']
  files['B.java']='class B { void missing(){} }'
  context=self.context('app',files)
  delta=self.analyze([context],old)
  full=self.analyze([context])
  self.assertEqual(self.merged(old,delta),sorted(full['facts'],key=lambda f:f['id']))
  replaced={o['id'] for o in delta['replacements'] if o['kind']=='source'}
  self.assertEqual(replaced,{'app/A.java','app/B.java'})
 def test_lombok_and_spring(self):
  files={
   'lombok/Getter.java':'package lombok; public @interface Getter {}',
   'org/springframework/stereotype/Component.java':'package org.springframework.stereotype; public @interface Component { String value() default ""; }',
   'p/Bean.java':'package p; @org.springframework.stereotype.Component("named") class Bean { @lombok.Getter private String name; String use(){ return getName(); } }',
  }
  ctx=self.context('framework',files,lombok='model',spring=True)
  facts=self.analyze([ctx])['facts'];self.merged([],dict(facts=facts,replacements=[]))
  getter=next(f for f in facts if f['signature']=='p.Bean#getName()')
  self.assertEqual(getter['origin'],'lombok_model');self.assertIsNone(getter['span'])
  self.assertTrue(any(f['relation']=='java.type' and f['parent']==getter['id'] for f in facts))
  self.assertTrue(any(f['relation']=='java.bean' and f['name']=='named' for f in facts))
  self.assertTrue(any(f['relation']=='java.lombok' for f in facts))
 def test_lombok_static_subset_and_unsupported_access_level(self):
  ctx=self.context('lombok',{
   'lombok/Data.java':'package lombok; public @interface Data {}',
   'lombok/Value.java':'package lombok; public @interface Value {}',
   'lombok/Getter.java':'package lombok; public @interface Getter { AccessLevel value() default AccessLevel.PUBLIC; }',
   'lombok/AccessLevel.java':'package lombok; public enum AccessLevel { PUBLIC, NONE }',
   'A.java':'@lombok.Data class A { boolean ready; final String fixed="x"; @lombok.Getter(lombok.AccessLevel.NONE) String hidden; } @lombok.Value class V { int number; }'
  },lombok='model')
  facts=self.analyze([ctx])['facts'];signatures={f['signature'] for f in facts}
  self.assertIn('A#isReady()',signatures)
  self.assertIn('A#setReady(boolean)',signatures)
  self.assertIn('A#getFixed()',signatures)
  self.assertNotIn('A#setFixed(java.lang.String)',signatures)
  self.assertNotIn('A#getHidden()',signatures)
  self.assertIn('V#getNumber()',signatures)
  self.assertNotIn('V#setNumber(int)',signatures)
 def test_spring_candidate_and_framework_disable_delta(self):
  files={
   'org/springframework/stereotype/Component.java':'package org.springframework.stereotype; public @interface Component { String value() default ""; }',
   'org/springframework/beans/factory/annotation/Autowired.java':'package org.springframework.beans.factory.annotation; public @interface Autowired {}',
   'App.java':'@org.springframework.stereotype.Component class Service {} class App { @org.springframework.beans.factory.annotation.Autowired Service service; }'
  }
  enabled=self.context('app',files,spring=True)
  old=self.analyze([enabled])['facts']
  self.assertTrue(any(f['relation']=='injects_possible' and f['status']=='modeled' for f in old))
  disabled=self.context('app',files,spring=False)
  disabled['digest']=sha((disabled['digest']+'spring-off').encode())
  delta=self.analyze([disabled],old);full=self.analyze([disabled])
  self.assertEqual(self.merged(old,delta),sorted(full['facts'],key=lambda f:f['id']))
  self.assertFalse(any(f['relation']=='java.bean' for f in full['facts']))
 def test_framework_annotation_array_names(self):
  ctx=self.context('app',{
   'org/springframework/context/annotation/Bean.java':'package org.springframework.context.annotation; public @interface Bean { String[] name() default {}; String destroyMethod() default ""; }',
   'Config.java':'class Config { @org.springframework.context.annotation.Bean(destroyMethod="cleanup") String bean(){return "";} @org.springframework.context.annotation.Bean(name={"one","two"}) String other(){return "";} }'
  },spring=True)
  facts=self.analyze([ctx])['facts']
  self.assertEqual([f['name'] for f in facts if f['relation']=='java.bean'],['bean','one','two'])
  self.assertTrue(all(f['attributes']['runtime_confirmed'] is False for f in facts if f['relation']=='java.bean'))
 def test_sofa_without_spring(self):
  ctx=self.context('app',{'Api.java':'interface Api {}'},sofa=True)
  xml=self.root/'sofa.xml';xml.write_text('<beans xmlns:sofa="http://sofastack.io/schema/sofa"><sofa:service id="service" interface="Api"/><sofa:reference id="api" interface="Api"/></beans>')
  ctx['resources']=[dict(unit=dict(path='sofa.xml',world='resources',hash=sha(xml.read_bytes())),input=str(xml))]
  facts=self.analyze([ctx])['facts']
  self.assertEqual({f['attributes']['kind'] for f in facts if f['relation']=='java.sofa'},{'service','reference'})
  self.assertFalse(any(f['owner']['producer']=='spring-model-v1' for f in facts))
 def test_initializers_lambdas_and_locals_have_owners(self):
  ctx=self.context('app',{'A.java':'class A { static int x=f(); { f(); } static int f(){return 1;} void m(){ int local=f(); Runnable r=()->f(); System.out.println(local); } }'})
  facts=self.analyze([ctx])['facts'];self.merged([],dict(facts=facts,replacements=[]))
  byid={f['id']:f for f in facts}
  sites=[f for f in facts if f['kind']=='call_site']
  for site in sites:self.assertIn(byid[site['parent']]['attributes']['element_kind'],['METHOD','CONSTRUCTOR','LAMBDA','INITIALIZER','FIELD','ENUM_CONSTANT'])
  self.assertTrue(any(f['name']=='local' and f['kind']=='declaration' for f in facts))
 def test_initializer_locals_and_lambda_parameters_keep_source_identity_and_delta(self):
  context=self.context('initializers',initializer_sources())
  initial=self.analyze([context],cache=True)['facts']
  assert_initializer_facts(self,initial)
  changed=self.context('initializers',initializer_sources('2'))
  delta=self.analyze([changed],initial,cache=True)
  full=self.analyze([changed],cache=True)['facts']
  self.assertEqual(self.merged(initial,delta),sorted(full,key=lambda f:f['id']))
  assert_initializer_facts(self,full)
 def test_ambiguous_calls_not_confirmed(self):
  ctx=self.context('app',{'A.java':'class A { void f(String s){} void f(Integer i){} void call(){ f(null); } }'})
  facts=self.analyze([ctx])['facts']
  self.assertTrue(any(f['kind']=='call_site' and f['status']=='unresolved' for f in facts))
  self.assertFalse(any(f['kind']=='call_target' and '#f(' in next((d['signature'] for d in facts if d['id']==f['target']),'') for f in facts))
 def test_structured_generic_types_and_selected_binding(self):
  ctx=self.context('types',{'Types.java':'import java.util.*; class Types<T extends Number> { List<? extends T>[] values; T first(List<T> xs) { return xs.get(0); } }'})
  facts=self.analyze([ctx])['facts']
  field=next(f for f in facts if f['signature']=='Types#values')
  ty=field['attributes']['type_model']
  self.assertEqual(ty['kind'],'ARRAY')
  self.assertEqual(ty['component']['element'],'java.util.List')
  self.assertEqual(ty['component']['arguments'][0]['kind'],'WILDCARD')
  self.assertEqual(ty['component']['arguments'][0]['extends']['kind'],'TYPEVAR')
  self.assertTrue(any(f['relation']=='java.type' and f['parent']==field['id'] for f in facts))
  call=next(f for f in facts if f['kind']=='call_site' and f['name']=='xs.get')
  self.assertEqual(call['attributes']['selected_type_model']['kind'],'EXECUTABLE')
  self.assertEqual(call['attributes']['selected_type_model']['return']['kind'],'TYPEVAR')
  self.assertTrue(any(f['relation']=='java.binding' and f['parent']==call['id'] for f in facts))
 def test_failed_generic_inference_keeps_partial_model_diagnostics_and_delta(self):
  template='import java.util.*;import java.util.stream.*;class A%s { int healthy(){ return %s; } List<%s> f(Map<Integer,%s> m){ return m.entrySet().stream().sorted(Comparator.comparing(o -> o.getValue())).map(Map.Entry::getValue).collect(Collectors.toList()); }}'
  def incomplete(value):
   if isinstance(value,dict):
    if value.get('reason')=='TYPE_VARIABLE_LOWER_BOUND_UNAVAILABLE':
     self.assertEqual(value['kind'],'TYPEVAR');self.assertIs(value['complete'],False)
     return 1
    return sum(incomplete(v) for v in value.values())
   return sum(incomplete(v) for v in value) if isinstance(value,list) else 0
  for parameter,kind in [('<T>','T'),('','Missing'),('','String')]:
   with self.subTest(type=kind):
    context=self.context('inference',{'A.java':template%(parameter,'1',kind,kind)})
    initial=self.analyze([context],cache=True)['facts']
    errors=[f for f in initial if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR']
    self.assertEqual(bool(errors),kind!='String')
    self.assertEqual(bool(incomplete(initial)),kind!='String')
    self.assertTrue(any(f['kind']=='declaration' and f['signature']=='A#healthy()' for f in initial))
    changed=self.context('inference',{'A.java':template%(parameter,'2',kind,kind)})
    delta=self.analyze([changed],initial,cache=True)
    full=self.analyze([changed],cache=True)['facts']
    self.assertEqual(self.merged(initial,delta),sorted(full,key=lambda f:f['id']))

 def test_capture_identity_bounds_literal_and_source_order_are_stable(self):
  text='''import java.util.*; class Captures {
   static final String literal="capture#783";
   void use(List<? extends Number> a,List<? extends String> b) { Number x=a.get(0); String y=b.get(0); }
  }'''
  context=self.context('captures',{'Captures.java':text})
  old=self.analyze([context],cache=True)['facts']
  self.assertFalse([f for f in old if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
  captured=[]
  def collect(value):
   if isinstance(value,dict):
    if value.get('capture'):captured.append(value)
    for item in value.values():collect(item)
   elif isinstance(value,list):
    for item in value:collect(item)
  collect(old)
  owners={value['owner']:value['upper'].get('element') for value in captured if isinstance(value.get('upper'),dict)}
  self.assertTrue({'java.lang.Number','java.lang.String'}.issubset(set(owners.values())))
  self.assertGreaterEqual(len(owners),2)
  literal=next(f for f in old if f['kind']=='declaration' and f['signature']=='Captures#literal')
  self.assertEqual(literal['attributes']['constant_value'],'capture#783')
  reordered=self.context('captures',{'Other.java':'class Other { Object f(java.util.List<?> values){ return values.get(0); } }','Captures.java':text})
  full=self.analyze([reordered])['facts']
  self.assertEqual([f for f in old if f['source']=='captures/Captures.java'],[f for f in full if f['source']=='captures/Captures.java'])
  changed=self.context('captures',{'Captures.java':text.replace('a.get(0)','a.get(1)')})
  delta=self.analyze([changed],old,cache=True)
  self.assertEqual(self.merged(old,delta),sorted(self.analyze([changed],cache=True)['facts'],key=lambda f:f['id']))

 def test_sam_and_method_reference_model(self):
  ctx=self.context('sam',{'Fn.java':'interface Fn<T,R> { R apply(T value); } class Use { String id(String value){return value;} void run(){ Fn<String,String> lambda = value -> id(value); Fn<String,String> reference = this::id; } }'})
  facts=self.analyze([ctx])['facts']
  lambdas=[f for f in facts if f['kind']=='declaration' and f['attributes'].get('element_kind')=='LAMBDA']
  self.assertEqual(len(lambdas),1)
  self.assertEqual(lambdas[0]['attributes']['sam']['method'],'Fn#apply(java.lang.Object)')
  refs=[f for f in facts if f['kind']=='call_site' and f['attributes'].get('mechanism')=='method_reference']
  self.assertEqual(len(refs),1)
  self.assertTrue(refs[0]['attributes']['sam']['complete'])
 def test_external_binary_metadata(self):
  with tempfile.TemporaryDirectory(prefix='codeana-binary-fixture-') as temporary:
   binary=pathlib.Path(temporary);source=binary/'lib/Api.java';source.parent.mkdir(parents=True)
   source.write_text('package lib; public class Api { public java.util.List<String> names(){ return null; } }')
   subprocess.run([str(JDK8/'bin/javac'),'-d',str(binary),str(source)],check=True)
   jar=binary/'api.jar'
   subprocess.run([str(JDK8/'bin/jar'),'cf',str(jar),'-C',str(binary),'lib/Api.class'],check=True)
   ctx=self.context('consumer',{'Use.java':'class Use { Object use() { return new lib.Api().names(); } }'},classpath=[str(jar)])
   facts=self.analyze([ctx])['facts']
   external=next(f for f in facts if f['signature']=='lib.Api#names()')
   self.assertEqual(external['attributes']['binary_class']['name'],'lib.Api')
   self.assertEqual(external['attributes']['binary_member']['descriptor'],'()Ljava/util/List;')
   self.assertIn('java/util/List',external['attributes']['binary_member']['generic_signature'])
if __name__=='__main__':unittest.main(verbosity=2)
