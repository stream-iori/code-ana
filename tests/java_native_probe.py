#!/usr/bin/env python3
"""Real shared-library proof. Expectations are derived from this repository's fixture."""
import hashlib, json, os, pathlib, shutil, struct, subprocess, tempfile, zipfile
from java_type_views import type_attributes
ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME', pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu')).resolve()
TARGET = ROOT/'lang-adaptor/target'
BUNDLE = TARGET/'native/libcodeana_java.dylib'

def main():
    with zipfile.ZipFile(TARGET/'compiler-bridge.jar') as jar:
        for name in jar.namelist():
            if name.endswith('.class'):
                assert struct.unpack('>H', jar.read(name)[6:8])[0] == 52, name
    with tempfile.TemporaryDirectory(prefix='codeana-java-probe-') as tmp:
        root = pathlib.Path(tmp)
        # Verify the exact installed build-JDK header against the Rust bootstrap layout.
        layout = root/'isolate-layout.c'
        layout.write_text('#include <stddef.h>\n#include "graal_isolate.h"\n'
                          '_Static_assert(__graal_create_isolate_params_version==5,"version");\n'
                          '_Static_assert(sizeof(graal_create_isolate_params_t)==56,"size");\n'
                          '_Static_assert(offsetof(graal_create_isolate_params_t,argc)==32,"argc");\n'
                          '_Static_assert(offsetof(graal_create_isolate_params_t,argv)==40,"argv");\n'
                          '_Static_assert(offsetof(graal_create_isolate_params_t,pkey)==48,"pkey");\n'
                          '_Static_assert(offsetof(graal_create_isolate_params_t,_reserved_5)==54,"reserved");\n')
        subprocess.run(['cc', '-fsyntax-only', '-I', str(TARGET/'native'), str(layout)], check=True, capture_output=True)
        for heap in (128*1024*1024, 512*1024*1024):
            measured = subprocess.run([str(ROOT/'target/debug/codeana-java-worker'), str(BUNDLE),
                '--native-heap-bytes', str(heap), '--heap-probe'], capture_output=True, text=True, timeout=15)
            assert measured.returncode==0, measured.stderr
            assert int(measured.stdout)==heap, measured.stdout
        default_heap = subprocess.run([str(ROOT/'target/debug/codeana-java-worker'), str(BUNDLE), '--heap-probe'],
                                      capture_output=True, text=True, timeout=15)
        assert default_heap.returncode==0 and int(default_heap.stdout)==256*1024*1024, default_heap.stderr
        source = root/'App.java'
        shutil.copyfile(ROOT/'tests/fixtures/java-native/base/src/p/App.java',source)
        record = dict(path='src/p/App.java', input=str(source), hash=hashlib.sha256(source.read_bytes()).hexdigest())
        context = dict(version=9,context='probe-8',sources=[record],classpath=[])
        request = dict(version=6,java_home=str(JDK),bridge=str(TARGET/'compiler-bridge.jar'),scratch=str(root),contexts=[context])
        path = root/'request.json';path.write_text(json.dumps(request))
        env = dict(os.environ, JAVA_HOME='/invalid', PATH='', JAVA_TOOL_OPTIONS='-invalid-option')
        result = subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)], env=env,capture_output=True,text=True,timeout=150)
        assert result.returncode == 0, result.stderr
        model = json.loads(result.stdout)['models'][0]
        declarations = {x['signature']:x for x in model['declarations']}
        assert 'p.App#main(java.lang.String[])' in declarations
        calls = [x for x in model['observations'] if x['kind']=='call']
        selected = [x for x in calls if x['target']=='p.Service#greet(java.lang.String)' and x['attributes']['mechanism']=='invoke']
        assert len(selected)==2 and all(x['resolved'] for x in selected),selected
        assert any(x['attributes']['mechanism']=='method_reference' and x['resolved'] for x in calls)
        assert not [x for x in model['diagnostics'] if x['severity']=='ERROR'],model['diagnostics']
        assert not list(root.rglob('*.class'))
        assert not list(root.glob('compiler-*'))
        cancelled=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path),'--cancel-after-ms','50'],capture_output=True,text=True,timeout=10)
        assert cancelled.returncode != 0 and 'CANCELLED' in cancelled.stderr,cancelled.stderr
        assert not list(root.glob('compiler-*'))
        # Compile an explicitly test-owned processor trap; -proc:none must prevent loading it.
        processor=root/'processor';processor.mkdir()
        marker=root/'processor-ran'
        (processor/'Trap.java').write_text('import javax.annotation.processing.*; import javax.lang.model.*; import javax.lang.model.element.*; import java.util.*; @SupportedAnnotationTypes("*") @SupportedSourceVersion(SourceVersion.RELEASE_8) public class Trap extends AbstractProcessor { public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) { try { new java.io.File('+json.dumps(str(marker))+').createNewFile(); } catch(Exception e) { throw new RuntimeException(e); } return false; } }')
        subprocess.run([str(JDK/'bin/javac'),'-d',str(processor),str(processor/'Trap.java')],check=True,capture_output=True)
        service=processor/'META-INF/services/javax.annotation.processing.Processor';service.parent.mkdir(parents=True);service.write_text('Trap\n')
        trap=root/'trap.jar'
        subprocess.run([str(JDK/'bin/jar'),'cf',str(trap),'-C',str(processor),'.'],check=True,capture_output=True)
        context['classpath']=[str(trap)];path.write_text(json.dumps(request))
        result=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert result.returncode==0,result.stderr
        assert not marker.exists(),'annotation processor was executed'
        # Verify copied bundles fail before entering incompatible or modified code.
        copied=root/'bundle';(copied/'native').mkdir(parents=True)
        shutil.copyfile(BUNDLE,copied/'native'/BUNDLE.name)
        shutil.copyfile(TARGET/'compiler-bridge.jar',copied/'compiler-bridge.jar')
        manifest=json.loads((TARGET/'native/manifest.json').read_text())
        manifest['abi']=99
        (copied/'native/manifest.json').write_text(json.dumps(manifest))
        def rejected(message):
            failure=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(copied/'native'/BUNDLE.name)],capture_output=True,text=True,timeout=10)
            assert failure.returncode!=0 and message in failure.stderr,failure.stderr
        rejected('BUNDLE_VERSION_MISMATCH')
        manifest['abi']=2;(copied/'native/manifest.json').write_text(json.dumps(manifest))
        with open(copied/'compiler-bridge.jar','ab') as changed:changed.write(b'changed')
        rejected('BUNDLE_HASH_MISMATCH')
        source.write_text('package p; class App { void bad() { missing(); } }')
        record['hash']=hashlib.sha256(source.read_bytes()).hexdigest();path.write_text(json.dumps(request))
        result=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert result.returncode==0,result.stderr
        model=json.loads(result.stdout)['models'][0]
        assert any(x['severity']=='ERROR' for x in model['diagnostics'])
        assert any(x['kind']=='call' and not x['resolved'] for x in model['observations'])
        contexts=[]
        for name in ('a','b'):
            file=root/(name.upper()+'.java')
            file.write_text('class '+name.upper()+' { void run(){ System.out.println("'+name+'"); } }')
            unit=dict(path=name+'/'+file.name,input=str(file),hash=hashlib.sha256(file.read_bytes()).hexdigest(),world='world-'+name,module=name,scope='MAIN',publish=True)
            contexts.append(dict(version=9,context=name+'@MAIN',module=name,world=unit['world'],scope='MAIN',sources=[unit],classpath=[],digest=unit['hash'],lombok='off',spring=False,spring_xml=False,sofa=False,resources=[]))
        request=dict(version=6,facts=True,full=True,baseline=[],java_home=str(JDK),bridge=str(TARGET/'compiler-bridge.jar'),scratch=str(root),contexts=contexts,parallelism=2)
        path.write_text(json.dumps(request))
        parallel=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert parallel.returncode==0,parallel.stderr
        parallel_result=json.loads(parallel.stdout)
        assert parallel_result['compiler_peak']==2,parallel_result
        request['parallelism']=1;path.write_text(json.dumps(request))
        serial=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert serial.returncode==0,serial.stderr
        assert parallel_result['facts']==json.loads(serial.stdout)['facts']
        cache_sources=[]
        for name,text in [('A','class A { String text(){ return "a"; } }'),
                          ('B','class B { void call(){ System.out.println(new A().text()+"old"); } }')]:
            file=root/(name+'.java');file.write_text(text)
            cache_sources.append(dict(path='cache/'+file.name,input=str(file),hash=hashlib.sha256(file.read_bytes()).hexdigest(),world='world-cache',module='cache',scope='MAIN',publish=True))
        cache_context=dict(version=9,context='cache@MAIN',module='cache',world='world-cache',scope='MAIN',sources=cache_sources,classpath=[],digest=hashlib.sha256(json.dumps([(s['path'],s['hash']) for s in cache_sources]).encode()).hexdigest(),lombok='off',spring=False,spring_xml=False,sofa=False,resources=[])
        request=dict(version=6,facts=True,full=True,baseline=[],java_home=str(JDK),bridge=str(TARGET/'compiler-bridge.jar'),scratch=str(root),contexts=[cache_context],parallelism=1,profile='native-probe',signature_cache=True,cache_root=str(root/'cache'))
        path.write_text(json.dumps(request))
        first=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert first.returncode==0,first.stderr
        old=json.loads(first.stdout)['facts']
        # Rust/Java CAF1 framing: a frozen baseline is referenced, not embedded in JSON.
        baseline_file=root/'baseline.bin'
        with baseline_file.open('wb') as output:
            output.write(struct.pack('>I',0x43414631))
            for fact in old:
                encoded=json.dumps(fact,ensure_ascii=False,separators=(',',':')).encode('utf8')
                output.write(struct.pack('>I',len(encoded)));output.write(encoded)
        request.pop('baseline')
        reference=dict(path=str(baseline_file),sha256=hashlib.sha256(baseline_file.read_bytes()).hexdigest(),count=len(old),bytes=baseline_file.stat().st_size)
        request['full']=False;request['baseline_archive']=reference
        path.write_text(json.dumps(request))
        unchanged=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert unchanged.returncode==0,unchanged.stderr
        assert json.loads(unchanged.stdout)['facts']==[]
        reference['count']+=1;path.write_text(json.dumps(request))
        wrong_count=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=10)
        assert wrong_count.returncode!=0 and 'BASELINE_ARCHIVE_IDENTITY' in wrong_count.stderr,wrong_count.stderr
        reference['count']-=1
        source=root/'B.java';source.write_text(source.read_text().replace('"old"','"new"'))
        cache_sources[1]['hash']=hashlib.sha256(source.read_bytes()).hexdigest()
        cache_context['digest']=hashlib.sha256(json.dumps([(s['path'],s['hash']) for s in cache_sources]).encode()).hexdigest()
        request['full']=False;path.write_text(json.dumps(request))
        reduced=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert reduced.returncode==0,reduced.stderr
        delta=json.loads(reduced.stdout)
        assert delta['compiler_sources']==1 and delta['reduced_contexts']==1,delta
        request.pop('baseline_archive');request['full']=True;request['baseline']=[];path.write_text(json.dumps(request))
        comparison=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
        assert comparison.returncode==0,comparison.stderr
        owners={json.dumps(owner,sort_keys=True) for owner in delta['replacements']}
        merged=[fact for fact in old if json.dumps(fact['owner'],sort_keys=True) not in owners]+delta['facts']
        assert sorted(merged,key=lambda fact:fact['id'])==sorted(json.loads(comparison.stdout)['facts'],key=lambda fact:fact['id'])
        # Native NoCode must refresh framework source spans while retaining omitted owners.
        from java_framework_alignment import MVC, API
        from java_model_tests import Models
        fixture=Models();fixture.root=root/'framework';fixture.root.mkdir()
        files=MVC|{'Other.java':f'@{API}.RestController class Other {{ @{API}.GetMapping("/other") String route(){{return "other";}} }}',
                   'Use.java':f'@{API}.RestController class Use {{ void grow(){{System.out.println("short"+new Other().route());}} @{API}.GetMapping("/item") void route(){{}} }}'}
        framework=fixture.context('app',files,spring=True)
        request=dict(version=6,facts=True,full=True,baseline=[],java_home=str(JDK),bridge=str(TARGET/'compiler-bridge.jar'),scratch=str(root),contexts=[framework],parallelism=1,profile='native-framework',signature_cache=True,cache_root=str(root/'framework-cache'))
        def native_facts():
            path.write_text(json.dumps(request))
            result=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(BUNDLE),'--probe',str(path)],capture_output=True,text=True,timeout=150)
            assert result.returncode==0,result.stderr
            return json.loads(result.stdout)
        previous=native_facts()['facts']
        old_span=next(f['span'] for f in previous if f['relation']=='java.spring.mvc' and f['name']=='GET /item')
        files['Use.java']=files['Use.java'].replace('short','a much longer method body literal')
        request['contexts']=[fixture.context('app',files,spring=True)]
        request['full']=False;request['baseline']=previous
        delta=native_facts()
        assert delta['reduced_contexts']==1,delta
        request['full']=True;request['baseline']=[]
        expected=native_facts()['facts']
        changed={json.dumps(o,sort_keys=True) for o in delta['replacements']}
        current=[f for f in previous if json.dumps(f['owner'],sort_keys=True) not in changed]+delta['facts']
        assert old_span!=next(f['span'] for f in expected if f['relation']=='java.spring.mvc' and f['name']=='GET /item')
        assert sorted(current,key=lambda f:f['id'])==sorted(expected,key=lambda f:f['id'])
        from java_framework_alignment import BASE, DI
        files = BASE | DI | {'Use.java':'''interface Api {} @org.springframework.stereotype.Component class Service implements Api {}
          class Target { public void work(){} } class Use {
            @jakarta.inject.Inject Api field;
            void reflect() throws Exception { Target.class.getDeclaredMethod("work").invoke(new Target()); }
          }'''}
        request.update(contexts=[fixture.context('alignment', files, spring=True)], full=True, baseline=[], signature_cache=False)
        aligned = native_facts()['facts']
        reflected = [f for f in aligned if f['kind']=='call_target' and f['origin']=='reflection_model']
        assert len(reflected)==1 and not reflected[0]['declaration_resolved'],reflected
        injection = [f for f in aligned if f['relation']=='java.spring.injection']
        assert len(injection)==1 and injection[0]['attributes']['annotation']=='jakarta.inject.Inject',injection
        request.update(full=False, baseline=aligned)
        assert native_facts()['facts']==[]
        from java_lombok_alignment import Lombok
        nested_files = {'Outer.java':'class Outer { static class Inner {} }',
                        'Api.java':'class Api { static void accept(Outer.Inner value, Outer.Inner[][] array){} }',
                        'Use.java':'class Use { void run(Outer.Inner value, Outer.Inner[][] array){ Api.accept(value,array); } }'}
        request.update(contexts=[fixture.context('nested', nested_files)],full=True,baseline=[],signature_cache=True)
        nested_facts = native_facts()['facts']
        target = next(f for f in nested_facts if f['kind']=='declaration' and f['signature']=='Api#accept(Outer$Inner,Outer$Inner[][])')
        assert any(f['kind']=='call_target' and f['target']==target['id'] and f['status']=='resolved' for f in nested_facts)
        nested_files['Use.java'] = nested_files['Use.java'].replace('Api.accept(value,array);','Api.accept(value,array); Api.accept(value,array);')
        request.update(contexts=[fixture.context('nested', nested_files)],full=False,baseline=nested_facts)
        delta = native_facts()
        assert delta['reduced_contexts']==1,delta
        request.update(full=True,baseline=[])
        assert fixture.merged(nested_facts,delta)==sorted(native_facts()['facts'],key=lambda f:f['id'])
        lombok = Lombok('test_common_signatures_and_value_effective_modifiers')
        lombok.setUp()
        try:
            lombok_context = lombok.context('native-lombok', lombok.files('''@lombok.Data class Data { final int x; String text; }
                @lombok.extern.java.Log class Logged {} @lombok.Value class Value { int value; }'''), lombok='model')
            lombok_context = lombok.controls(lombok_context, {'lombok.config': '''config.stopBubbling=true
                lombok.addLombokGeneratedAnnotation=true
                lombok.log.fieldName=LOGGER
            '''})
            request.update(contexts=[lombok_context], full=True, baseline=[])
            modeled = native_facts()['facts']
            declarations = {f['signature']:f for f in modeled if f['kind']=='declaration'}
            assert declarations['Data#<init>(int)']['origin']=='lombok_model'
            assert 'Data#<init>()' not in declarations
            assert declarations['Value#<init>(int)']['declaration_resolved'] is False
            assert declarations['Logged#LOGGER']['attributes']['declared_type']=='java.util.logging.Logger'
            getter = declarations['Data#getText()']
            assert any(f['relation']=='java.annotation' and f['parent']==getter['id']
                       and f['attributes']['resolved_type']=='lombok.Generated' and not f['declaration_resolved'] for f in modeled)
            request.update(full=False, baseline=modeled)
            assert native_facts()['facts']==[]
        finally:
            lombok.doCleanups()
        from java_binding_alignment import Bindings
        binding = Bindings('test_recursive_intersection_bounds_keep_formal_identity')
        binding.setUp()
        try:
            binding_source = '''class Holder { @lombok.Getter Api api; @lombok.Setter String name; }
              class Api { void choose(long value){} void choose(Integer value){}
                <T extends Number & Comparable<T>> T echo(T value){return value;}
                void spread(String... values){} private void hidden(){} }
              class Use { void run(Holder h){ h.setName("yes"); h.getApi().choose(1);
                h.getApi().echo(1); h.getApi().<Integer>echo(1); h.getApi().<Integer>echo(null);
                h.getApi().<Number>echo(1); h.getApi().<String>echo(1);
                h.getApi().spread("a","b"); h.getApi().hidden();
                java.util.function.Supplier<Api> ref=h::getApi; } }'''
            binding_files = Lombok.files(binding, binding_source)
            context = binding.context('native-binding', binding_files, lombok='model')
            request.update(contexts=[context], full=True, baseline=[])
            recovered_facts = native_facts()['facts']
            pairs = binding.recovered(recovered_facts)
            assert ('h.getApi().choose','Api#choose(long)') in [(n,s) for n,s,_ in pairs]
            assert ('h::getApi','Holder#getApi()') in [(n,s) for n,s,_ in pairs]
            assert not [p for p in pairs if p[0]=='h.getApi().hidden']
            recovered = [p[2] for p in pairs if p[2]['origin']=='source_recovery']
            assert len(recovered)==5
            explicit = [f for f in recovered if f['attributes'].get('explicit_type_argument_models')]
            assert len(explicit)==2 and all(type_attributes(f)['explicit_type_argument_models'][0]['element']=='java.lang.Integer' for f in explicit)
            request.update(full=False, baseline=recovered_facts)
            assert native_facts()['facts']==[]
            binding_files['App.java'] = binding_files['App.java'].replace('choose(long value)','choose(double value)')
            request.update(contexts=[binding.context('native-binding', binding_files, lombok='model')])
            delta = native_facts()
            request.update(full=True, baseline=[])
            assert binding.merged(recovered_facts,delta)==sorted(native_facts()['facts'],key=lambda f:f['id'])
        finally:
            binding.doCleanups()
        from java_xml_alignment import Xml, SOURCE
        xml = Xml('test_alias_nested_value_tree_construction_and_member_relations')
        xml.setUp()
        try:
            files = {'beans.xml': '<beans xmlns:sofa="http://schema.alipay.com/sofa/schema/service"><bean id="api" class="Api" init-method="start"/>'
                     '<alias name="api" alias="external"/><bean id="holder" class="Holder">'
                     '<constructor-arg ref="external"/><property name="nested"><bean class="Api"/>'
                     '</property></bean><sofa:reference id="rpc" interface="RpcApi"><sofa:binding.tr/></sofa:reference>'
                     '<sofa:consumer id="consumer"><sofa:listener ref="api"/><sofa:channel value="topic">'
                     '<sofa:event value="message"/></sofa:channel></sofa:consumer></beans>'}
            def xml_context():
                context = xml.captured(files,source=SOURCE+'interface RpcApi {}')
                context['sofa'] = True
                return context
            request.update(contexts=[xml_context()],full=True,baseline=[])
            modeled = native_facts()['facts']
            assert len([f for f in modeled if f['relation']=='java.bean'])==3
            assert any(f['relation']=='config_nested_bean' and f['span'] for f in modeled)
            assert any(f['relation']=='java.spring.xml' and f['attributes']['kind']=='constructor-arg' for f in modeled)
            assert any(f['relation']=='java.sofa' and f['attributes'].get('transport')=='tr' for f in modeled)
            assert any(f['relation']=='java.sofa' and f['attributes'].get('element')=='event'
                       and f['attributes']['channel']=='topic' for f in modeled)
            request.update(full=False,baseline=modeled)
            assert native_facts()['facts']==[]
            files['beans.xml'] = files['beans.xml'].replace('init-method="start"','init-method="stop"')
            request.update(contexts=[xml_context()])
            delta = native_facts()
            changed = {json.dumps(owner,sort_keys=True) for owner in delta['replacements']}
            actual = [f for f in modeled if json.dumps(f['owner'],sort_keys=True) not in changed]+delta['facts']
            request.update(full=True,baseline=[])
            assert sorted(actual,key=lambda f:f['id'])==sorted(native_facts()['facts'],key=lambda f:f['id'])
        finally:
            xml.doCleanups()
        binary_xml=Xml('test_binary_only_factory_constructor_inherited_members_and_delta');binary_xml.setUp()
        try:
            context=binary_xml.binary_context()
            request.update(contexts=[context],full=True,baseline=[],signature_cache=False)
            old=native_facts()['facts'];binary_xml.assert_binary_xml(old)
            from java_model_tests import compact_request
            legacy_request=request;request=compact_request(request);request['version']=6
            assert native_facts()['facts']==old
            request=legacy_request
            resource=binary_xml.root/'xml/0'
            resource.write_text(resource.read_text().replace('init-method="start"','init-method="missing"')
                .replace('<constructor-arg>','<constructor-arg type="java.lang.String">'))
            context['resources'][0]['unit']['hash']=hashlib.sha256(resource.read_bytes()).hexdigest()
            context['digest']=hashlib.sha256((context['digest']+'changed').encode()).hexdigest()
            request.update(full=False,baseline=old)
            delta=native_facts();request.update(full=True,baseline=[])
            full=native_facts()['facts']
            assert binary_xml.merged(old,delta)==sorted(full,key=lambda f:f['id'])
            ids={f['id']:f for f in full}
            targets={ids[f['target']]['signature'] for f in full if f['relation']=='config_member'}
            assert 'ext.Factory#ambiguous(java.lang.String)' in targets
            assert 'ext.Factory#ambiguous(java.lang.Integer)' not in targets
        finally:
            binary_xml.doCleanups()
        from java_model_tests import Models, compact_request
        labels=Models();labels.setUp()
        try:
            request.update(contexts=labels.labeled_contexts(),full=True,baseline=[],signature_cache=False)
            old=native_facts()['facts'];labels.assert_labeled_facts(old)
            legacy_request=request
            request=compact_request(request);request['version']=6
            assert native_facts()['facts']==old
            request=legacy_request
            request.update(contexts=labels.labeled_contexts('"b"'),full=False,baseline=old)
            delta=native_facts();actual=labels.merged(old,delta)
            request.update(full=True,baseline=[])
            full=native_facts()['facts'];labels.assert_labeled_facts(full)
            assert actual==sorted(full,key=lambda f:f['id'])
        finally:
            labels.doCleanups()
        from java_model_tests import initializer_sources, assert_initializer_facts
        initializers=Models();initializers.setUp()
        try:
            context=initializers.context('initializers',initializer_sources())
            request.update(contexts=[context],full=True,baseline=[],signature_cache=False)
            old=native_facts()['facts'];assert_initializer_facts(initializers,old)
            changed=initializers.context('initializers',initializer_sources('2'))
            request.update(contexts=[changed],full=False,baseline=old)
            delta=native_facts();request.update(full=True,baseline=[])
            full=native_facts()['facts'];assert_initializer_facts(initializers,full)
            assert initializers.merged(old,delta)==sorted(full,key=lambda f:f['id'])
        finally:
            initializers.doCleanups()
        inference=Models();inference.setUp()
        try:
            text='''import java.util.*; import java.util.stream.*; class Inference<T> {
              int healthy(){return 1;} List<T> broken(Map<Integer,T> m){return m.entrySet().stream()
                .sorted(Comparator.comparing(o -> o.getValue())).map(Map.Entry::getValue).collect(Collectors.toList());}
            }'''
            request.update(contexts=[inference.context('inference',{'Inference.java':text})],full=True,baseline=[],signature_cache=True)
            old=native_facts()['facts']
            assert any(f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR' for f in old)
            assert any(f['kind']=='declaration' and f['signature']=='Inference#healthy()' for f in old)
            assert 'TYPE_VARIABLE_LOWER_BOUND_UNAVAILABLE' in json.dumps(old)
            request.update(contexts=[inference.context('inference',{'Inference.java':text.replace('return 1;','return 2;')})],full=False,baseline=old)
            delta=native_facts();request.update(full=True,baseline=[])
            assert inference.merged(old,delta)==sorted(native_facts()['facts'],key=lambda f:f['id'])
        finally:
            inference.doCleanups()
        from java_semantic_alignment import JavaSemanticAlignment
        callbacks=JavaSemanticAlignment();callbacks.setUp()
        try:
            def analyze_callbacks(contexts,baseline=None,parallelism=1,cache=False,compact=False):
                request.update(contexts=contexts,full=baseline is None,baseline=baseline or [],
                               parallelism=parallelism,signature_cache=cache)
                return native_facts()
            callbacks.analyze=analyze_callbacks
            callbacks.test_field_enum_and_callback_initializers_keep_their_lexical_owner()
            callbacks.test_binding_evidence_has_one_canonical_type_graph()
            callbacks.test_method_reference_callbacks_and_intrinsic_array_constructors()
            callbacks.test_nested_receiver_lambda_keeps_deferred_evaluation_separate()
            callbacks.test_invalid_array_reference_keeps_intrinsic_navigation_without_confirmed_binding()
            callbacks.test_binary_generic_models_and_complete_large_member_headers()
        finally:
            callbacks.doCleanups()
        from java_annotation_alignment import Annotations
        annotations=Annotations();annotations.setUp()
        try:
            annotations.analyze=analyze_callbacks
            annotations.test_repeated_irrelevant_meta_queries_preserve_late_injection_and_alias_values()
        finally:
            annotations.doCleanups()
        from java_reflection_alignment import Reflection
        reflection=Reflection();reflection.setUp()
        try:
            reflection.analyze=analyze_callbacks
            reflection.test_switch_merges_exhaustive_states_and_preserves_fallthrough_uncertainty()
            reflection.test_equal_branch_values_merge_across_constant_and_handle_provenance()
        finally:
            reflection.doCleanups()
        from java_binding_alignment import Bindings
        bindings=Bindings();bindings.setUp()
        try:
            bindings.analyze=analyze_callbacks
            bindings.test_modeled_overrides_substitute_source_generics_and_reject_illegal_methods()
            bindings.test_generated_overrides_cover_interfaces_covariance_and_preserve_javac()
            bindings.test_modeled_overrides_binary_generics_raw_erasure_and_final_guards()
            bindings.test_modeled_overrides_package_paths_and_raw_parameter_subsignatures()
        finally:
            bindings.doCleanups()
        from java_module_overlay_alignment import OverlayModules
        overlays=OverlayModules();overlays.setUp()
        try:
            overlays.analyze=analyze_callbacks
            overlays.test_data_value_and_utility_ctor_modifications_are_reapplied()
            overlays.test_modeled_overrides_recover_restored_source_and_remove_stale_edges()
        finally:
            overlays.doCleanups()
        crash_bundle=root/'crash-bundle';(crash_bundle/'native').mkdir(parents=True)
        shutil.copyfile(TARGET/'compiler-bridge.jar',crash_bundle/'compiler-bridge.jar')
        crash_source=root/'native-crash.c'
        crash_source.write_text('''
int graal_create_isolate(void *params, void **isolate, void **thread) { *isolate=(void*)1; *thread=(void*)1; return 0; }
int graal_tear_down_isolate(void *thread) { return 0; }
int codeana_java_abi_version(void *thread) { return 3; }
int codeana_java_set_completion_fd(void *thread, unsigned long long task, int fd) { return 0; }
unsigned long codeana_java_session_create(void *thread) { return 1; }
int codeana_java_input_chunk(void *thread, unsigned long task, const char *data, int length) { return 0; }
int codeana_java_seal_input(void *thread, unsigned long task) { return 0; }
int codeana_java_analyze_start(void *thread, unsigned long task) { *(volatile int*)0=1; return 0; }
''')
        crash_library=crash_bundle/'native'/BUNDLE.name
        subprocess.run(['cc','-dynamiclib','-o',str(crash_library),str(crash_source)],check=True,capture_output=True)
        crash_manifest=json.loads((TARGET/'native/manifest.json').read_text())
        crash_manifest['files']={'native/'+BUNDLE.name:hashlib.sha256(crash_library.read_bytes()).hexdigest(),
                                 'compiler-bridge.jar':hashlib.sha256((crash_bundle/'compiler-bridge.jar').read_bytes()).hexdigest()}
        (crash_bundle/'native/manifest.json').write_text(json.dumps(crash_manifest))
        crashed=subprocess.run([str(ROOT/'target/debug/codeana-java-worker'),str(crash_library),'--probe',str(path)],capture_output=True,text=True,timeout=10)
        assert crashed.returncode==-11,('native SIGSEGV was not isolated',crashed.returncode,crashed.stderr)
        assert not list(root.glob('compiler-*'))
        print('native → SDKMAN Java 8 → detached model: passed; cache/full equivalence, native SIGSEGV isolation, cleanup/cancel, processor trap and bundle integrity passed')
if __name__=='__main__': main()
