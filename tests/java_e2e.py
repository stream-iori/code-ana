#!/usr/bin/env python3
"""Production Java worker acceptance; no mock fixtures or product fault switches."""
import json, os, pathlib, shutil, sqlite3, subprocess, tempfile, time, unittest
import rust_e2e as harness
from java_type_views import type_attributes
ROOT=pathlib.Path(__file__).resolve().parents[1]
BIN=pathlib.Path(os.environ.get('CODEANA_TEST_BIN', ROOT/'target/debug'))
JDK=pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME', pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu')).resolve()
class JavaProduct(unittest.TestCase):
    facts=harness.Product.facts
    cli=harness.Product.cli
    stop=harness.Product.stop
    cleanup=harness.Product.cleanup
    daemon_log=harness.Product.daemon_log

    def test_source_binary_meta_graph_publishes_original_provenance_and_delta(self):
        import hashlib
        shutil.rmtree(self.project/'src')
        (self.project/'src').mkdir()
        definitions=self.project/'src/Meta.java'
        definitions.write_text('''package p; import java.lang.annotation.*;
            @Target({ElementType.TYPE,ElementType.ANNOTATION_TYPE}) @B @interface A {}
            @Target({ElementType.TYPE,ElementType.ANNOTATION_TYPE}) @A @interface B {}''')
        use=self.project/'src/Use.java'
        use.write_text('package p; @A @Deprecated class Use {int body=1;}')
        def verify(facts):
            meta=[f for f in facts if f['relation']=='java.annotation.meta']
            source=next(f for f in meta if f['source']=='src/Use.java' and f['attributes']['annotation_fqn']=='p.A'
                        and f['attributes']['meta_annotation_fqn']=='java.lang.annotation.Target')
            self.assertEqual(source['attributes']['meta_annotation_fqn'], 'java.lang.annotation.Target')
            origin=source['attributes']['source_origin']
            self.assertEqual(origin['path'],'src/Meta.java')
            self.assertEqual(origin['hash'],hashlib.sha256(definitions.read_bytes()).hexdigest())
            self.assertIn('@interface A {}',definitions.read_bytes()[origin['span']['start']:origin['span']['end']].decode())
            self.assertTrue(any(f['source']=='src/Use.java' and f['attributes']['annotation_fqn']=='java.lang.Deprecated'
                                and f['attributes']['source_origin']['path']=='src/Use.java' for f in meta))
            self.assertFalse([f for f in facts if f['name']=='ANNOTATION_META_INCOMPLETE'])
            return meta
        _,first=self.cli('index','--full',timeout=180)
        before=self.facts(first['revision']);verify(before)
        use.write_text(use.read_text().replace('body=1','body=2'))
        _,delta=self.cli('index',timeout=180)
        changed=self.facts(delta['revision']);verify(changed)
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(changed,self.facts(full['revision']))
        use.write_text(use.read_text().replace('@A @Deprecated ',''))
        _,delta=self.cli('index',timeout=180)
        removed=self.facts(delta['revision'])
        self.assertFalse([f for f in removed if f['relation']=='java.annotation.meta' and f['source']=='src/Use.java'])
        self.assertTrue([f for f in removed if f['relation']=='java.annotation.meta' and f['source']=='src/Meta.java'])
        definitions.unlink()
        _,delta=self.cli('index',timeout=180)
        deleted=self.facts(delta['revision'])
        self.assertFalse([f for f in deleted if f['relation']=='java.annotation.meta'])
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(deleted,self.facts(full['revision']))
        self.assertEqual(before,self.facts(first['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_registration_original_evidence_publishes_and_survives_delta(self):
        from java_registration_reference_alignment import DEFINITIONS, SOURCE, product_projection
        shutil.rmtree(self.project/'src')
        for relative, text in (DEFINITIONS | {'Use.java': SOURCE}).items():
            path = self.project/'src'/relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        annotation = self.project/'src/Note.java'
        annotation.write_text('''package p; import java.lang.annotation.*;
            @Target(ElementType.FIELD) @interface Note { float value(); String text(); }
            class Annotated { @\\u004eote(value=0.1F,text="原字节😀") int marked; }''')
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()
            .replace('[languages.java.extensions.spring]\nenabled = false', '[languages.java.extensions.spring]\nenabled = true')
            .replace('[languages.java.extensions.sofa]\nenabled = false', '[languages.java.extensions.sofa]\nenabled = true'))

        def verify(facts, route, unique, number):
            by = {f['id']: f for f in facts}
            projection = product_projection(facts)
            self.assertEqual(projection.total(), 7)
            registrations = [f for f in facts if f['relation']=='java.spring.mvc']
            raw = next(f for f in registrations if f['attributes']['path']=='/base/'+route)
            self.assertEqual(raw['attributes']['parameters'][0]['type'], 'List< /* 中文😀 */ String >')
            self.assertEqual(raw['attributes']['parameters'][1]['type'], 'String...')
            self.assertEqual(raw['attributes']['parameters'][0]['resolved_type'], 'java.util.List<java.lang.String>')
            event = next(f for f in facts if f['relation']=='java.spring.handler' and f['attributes']['mechanism']=='spring.annotation.event'
                         and by[f['parent']]['name']=='event')
            self.assertEqual(event['attributes']['source_parameter_types'], ['List< /* 原字节😀 */ String >'])
            self.assertEqual(event['attributes']['written_attributes']['condition'], '"event.valid"')
            service = next(f for f in facts if f['relation']=='java.sofa' and f['attributes']['role']=='provider')
            self.assertEqual(service['attributes']['unique_id'], unique)
            self.assertEqual(service['attributes']['written_attributes']['uniqueId'], '"'+unique+'"')
            use = next(f for f in facts if f['relation']=='java.annotation' and by[f['parent']]['signature']=='p.Annotated#marked')
            self.assertEqual(use['attributes']['written_name'], '\\u004eote')
            self.assertEqual(use['attributes']['source_name'], 'Note')
            self.assertEqual(use['attributes']['written_values'], dict(value=number, text='原字节😀'))
            self.assertTrue(use['attributes']['written_values_complete'])
            self.assertEqual(use['attributes']['written_attributes']['value'], str(number)+'F')
            self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['status']=='ERROR'])
            return projection

        _, first = self.cli('index', '--full', timeout=180)
        self.assertEqual(first['publication'], 'published')
        before = self.facts(first['revision'])
        initial = verify(before, 'raw', 'one', 0.1)
        source = self.project/'src/Use.java'
        source.write_text(source.read_text().replace('void init(){}', 'void init(){int changed=1;}'))
        _, delta = self.cli('index', timeout=180)
        body = self.facts(delta['revision'])
        self.assertEqual(verify(body, 'raw', 'one', 0.1), initial)
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(body, self.facts(full['revision']))
        source.write_text(source.read_text().replace('"/raw"', '"/changed"').replace('uniqueId="one"', 'uniqueId="two"'))
        annotation.write_text(annotation.read_text().replace('0.1F', '0.2F'))
        _, delta = self.cli('index', timeout=180)
        changed = self.facts(delta['revision'])
        self.assertNotEqual(verify(changed, 'changed', 'two', 0.2), initial)
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(changed, self.facts(full['revision']))
        config.write_text(config.read_text()
            .replace('[languages.java.extensions.spring]\nenabled = true', '[languages.java.extensions.spring]\nenabled = false')
            .replace('[languages.java.extensions.sofa]\nenabled = true', '[languages.java.extensions.sofa]\nenabled = false'))
        _, disabled = self.cli('index', timeout=180)
        after = self.facts(disabled['revision'])
        self.assertFalse([f for f in after if f['relation'] in ('java.spring.mvc','java.spring.handler','java.sofa','handles_possible')])
        self.assertTrue([f for f in after if f['relation']=='java.annotation'])
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(after, self.facts(full['revision']))
        self.assertEqual(before, self.facts(first['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_spring_constraints_and_exact_injection_metadata_publish_and_delta(self):
        from java_framework_reference_alignment import DEFINITIONS, SOURCE, product_projection
        shutil.rmtree(self.project/'src')
        for relative, text in (DEFINITIONS | {'Use.java':SOURCE}).items():
            path=self.project/'src'/relative
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(text)
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()
            .replace('[languages.java.extensions.spring]\nenabled = false','[languages.java.extensions.spring]\nenabled = true')
            .replace('[languages.java.extensions.sofa]\nenabled = false','[languages.java.extensions.sofa]\nenabled = true'))
        source=self.project/'src/Use.java'
        _,first=self.cli('index','--full',timeout=180)
        self.assertEqual(first['publication'],'published')
        before=self.facts(first['revision'])
        projection=product_projection(before)
        self.assertEqual(len(projection['beans']),3)
        self.assertEqual(len(projection['injections']),5)
        marker=next(b for b in projection['beans'] if b['owner']=='Marker')
        self.assertTrue(marker['priority'])
        config_bean=next(b for b in projection['beans'] if b['owner']=='Config')
        self.assertEqual(config_bean['constraints'][0]['attributes']['value'],'{ "prod", /* 原字节😀 */ "cloud" }')
        constructor=next(p for p in projection['injections'] if '#<init>' in p['owner'])
        self.assertTrue(constructor['implicit'])
        self.assertIsNone(constructor['qualifier'])
        limited=next(p for p in projection['injections'] if p['owner']=='Config#limited')
        self.assertTrue(limited['unsupported'])
        self.assertEqual(limited['resource_written'],{'type':'Object.class','lookup':'""'})
        many=next(p for p in projection['injections'] if p['owner']=='Config#many')
        self.assertEqual((many['shape'],many['collection'],many['element']),('array','array','Api'))
        self.assertTrue(any(p['owner']=='Config#setter:URL' and p['resource_default']=='URL' for p in projection['injections']))
        source.write_text(source.read_text().replace('return null;','return (Api)null;'))
        _,delta=self.cli('index',timeout=180)
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        source.write_text(source.read_text().replace('@javax.annotation.Priority(10)','')
            .replace('@org.springframework.context.annotation.Lazy(false)','')
            .replace('@com.alipay.sofa.runtime.api.annotation.SofaReference ',''))
        _,removed=self.cli('index',timeout=180)
        after=self.facts(removed['revision'])
        self.assertEqual(len(product_projection(after)['injections']),7)
        self.assertFalse(next(b for b in product_projection(after)['beans'] if b['owner']=='Marker')['priority'])
        _,again=self.cli('index','--full',timeout=180)
        self.assertEqual(after,self.facts(again['revision']))
        self.assertEqual(before,self.facts(first['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_default_type_graph_deep_source_native_queries_and_delta(self):
        shutil.rmtree(self.project/'src')
        (self.project/'src').mkdir()
        source = self.project/'src/Deep.java'
        def write(depth):
            source.write_text('class Leaf {} class Box<T> {} class Deep { '
                              +'Box<'*depth+'Leaf'+'>'*depth+' value; '
                              +'String read(){return String.valueOf(1);} }')
        def verify(facts, depth):
            field = next(f for f in facts if f['kind']=='declaration' and f['signature']=='Deep#value')
            self.assertIn('$java_type',field['attributes']['type_model'])
            value = type_attributes(field)['type_model']
            for level in range(depth):
                self.assertEqual(value['element'],'Box',level)
                self.assertTrue(value['complete'])
                value = value['arguments'][0]
            self.assertEqual(value['element'],'Leaf')
            self.assertTrue(value['complete'])
            self.assertTrue([f for f in facts if f['relation']=='java.type' and f['attributes']['schema_version']==3])
            self.assertTrue([f for f in facts if f['relation']=='java.binding' and f['attributes']['schema_version']==4])
            self.assertFalse([f for f in facts if f['kind']=='diagnostic'])
            return field
        write(80)
        _, initial = self.cli('index','--full',timeout=180)
        before = self.facts(initial['revision'])
        field = verify(before,80)
        rows, _ = self.cli('references',field['id'])
        self.assertIsInstance(rows,list)
        site = next(f for f in before if f['kind']=='call_site' and f['name']=='String.valueOf')
        rows, terminal = self.cli('dispatch',site['id'])
        self.assertTrue(terminal['evidence']['semantic'])
        self.assertTrue(rows)
        write(81)
        _, delta = self.cli('index',timeout=180)
        changed = self.facts(delta['revision'])
        verify(changed,81)
        _, full = self.cli('index','--full',timeout=180)
        self.assertEqual(changed,self.facts(full['revision']))
        self.assertEqual(before,self.facts(initial['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def java_worker_pid(self):
        rows=subprocess.check_output(['ps','-axo','pid=,ppid=,comm='],text=True)
        workers=[int(fields[0]) for line in rows.splitlines() if (fields:=line.split(None,2)) and len(fields)==3 and int(fields[1])==self.daemon.pid and 'codeana-java-worker' in fields[2]]
        self.assertLessEqual(len(workers),1)
        return workers[0] if workers else None
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='codeana-java-e2e-')
        self.root=pathlib.Path(self.temp.name);self.home=self.root/'home';self.project=self.root/'project'
        self.daemon=None;self.log=None
        self.home.mkdir(mode=0o700)
        (self.home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=1073741824\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(JDK))+'\n')
        shutil.copytree(ROOT/'tests/fixtures/java-native/base',self.project)
        self.addCleanup(self.cleanup)
        self.log=open(self.root/'daemon.log','a')
        self.daemon=subprocess.Popen([str(BIN/'codeana-daemon'),'--home',str(self.home)],stdout=self.log,stderr=self.log)
        until=time.monotonic()+10
        while time.monotonic()<until:
            if self.daemon.poll() is not None:raise RuntimeError(self.daemon_log())
            if (self.home/'endpoint.json').exists():return
            time.sleep(.03)
        raise RuntimeError('daemon start timeout')

    def test_invalid_encoding_isolates_owner_preserves_bytes_and_strict_head(self):
        import hashlib
        path = self.project / 'src/p/Broken.java'
        captured = 'package p; class Broken {} // 单元测试'.encode('gbk')
        path.write_bytes(captured)
        _, first = self.cli('index', '--full', timeout=180)
        self.assertEqual(first['publication'], 'published')
        facts = self.facts(first['revision'])
        diagnostic = next(f for f in facts if f['name'] == 'SOURCE_ENCODING_INVALID')
        self.assertEqual(diagnostic['source'], 'src/p/Broken.java')
        self.assertEqual(diagnostic['attributes']['sha256'], hashlib.sha256(captured).hexdigest())
        self.assertEqual(path.read_bytes(), captured)
        coverage = {f['source']: f['status'] for f in facts if f['kind'] == 'coverage'}
        self.assertEqual(coverage['src/p/Broken.java'], 'partial')
        self.assertEqual(coverage['src/p/App.java'], 'complete')
        self.assertTrue([f for f in facts if f['signature'] == 'p.App#main(java.lang.String[])'])
        _, strict = self.cli('index', '--strict', code=3, timeout=180)
        self.assertNotEqual(strict['publication'], 'published')
        self.assertEqual(self.facts(first['revision']), facts)
        path.write_text('package p; class Broken {}')
        _, delta = self.cli('index', timeout=180)
        healed = self.facts(delta['revision'])
        self.assertFalse([f for f in healed if f['name'] == 'SOURCE_ENCODING_INVALID'])
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(sorted(healed, key=lambda f: f['id']),
                         sorted(self.facts(full['revision']), key=lambda f: f['id']))

    def test_rust_injection_wiring_matches_frozen_resolver_query_and_delta(self):
        from java_framework_alignment import DI
        from java_wiring_reference import projection
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text().replace('[languages.java.extensions.spring]\nenabled = false',
                                                    '[languages.java.extensions.spring]\nenabled = true'))
        for relative, content in DI.items():
            path = self.project / 'src' / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        source = self.project / 'src/p/Bindings.java'
        source.write_text('''package p; interface Api {} class First implements Api {}
            class Child extends First {} class Bindings {
            @org.springframework.beans.factory.annotation.Autowired(required=false)
            @org.springframework.beans.factory.annotation.Qualifier("first") Api dependency;
        }''')
        second = self.project / 'src/p/Second.java'
        second.write_text('package p; class Second implements Api {}')
        _, initial = self.cli('index', '--full', timeout=180)
        self.assertEqual(initial['publication'], 'published')
        facts = self.facts(initial['revision'])
        product, expected = projection(facts, self.root)
        self.assertEqual(product, expected)
        self.assertEqual(len(product), 2)
        self.assertTrue(all(row['confidence'] == 'AMBIGUOUS' for row in product))
        point = next(f['id'] for f in facts if f['signature'] == 'p.Bindings#dependency')
        rows, query = self.cli('type-relations', point, '--relation', 'wires_possible')
        self.assertEqual(len(rows), 2)
        self.assertFalse(query['evidence']['semantic'])
        self.assertTrue(all(row['status'] == 'possible' for row in rows))
        for new_source, count in [('package p; class Second {}', 1), (None, 1)]:
            if new_source is None:
                second.unlink()
            else:
                second.write_text(new_source)
            _, delta = self.cli('index', timeout=180)
            actual = self.facts(delta['revision'])
            product, expected = projection(actual, self.root)
            self.assertEqual(product, expected)
            self.assertEqual(len(product), count)
            self.assertTrue(all(row['confidence'] == 'INFERRED' for row in product))
            _, full = self.cli('index', '--full', timeout=180)
            self.assertEqual(sorted(actual, key=lambda f: f['id']), sorted(self.facts(full['revision']), key=lambda f: f['id']))
        source.write_text(source.read_text().replace('@org.springframework.beans.factory.annotation.Autowired(required=false)', ''))
        _, delta = self.cli('index', timeout=180)
        self.assertFalse([f for f in self.facts(delta['revision']) if f['relation'] == 'wires_possible'])
        self.assertEqual(self.facts(initial['revision']), facts)

    def test_cross_module_wiring_published_aliases_main_test_and_delta(self):
        import hashlib
        from java_framework_alignment import DI
        from java_wiring_reference import projection
        shutil.rmtree(self.project / 'src')
        sources = dict(
            **{'api/src/' + path: text for path, text in DI.items()},
            **{'api/src/p/Api.java': 'package p; public interface Api {}',
               'impl/src/p/First.java': 'package p; public class First implements Api {}',
               'app/src/p/Bindings.java': '''package p; class Second implements Api {}
                 class Child extends First {} class Bindings {
                   @org.springframework.beans.factory.annotation.Autowired Api dependency;
                 }''',
               'app/test/p/BindingsTest.java': '''package p; class BindingsTest {
                   @org.springframework.beans.factory.annotation.Autowired Api dependency;
                 }'''})
        for relative, text in sources.items():
            path = self.project / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        pom = self.project / 'pom.xml'
        pom.write_text('<project/>')
        sha = lambda value: hashlib.sha256(value).hexdigest()
        modules = []
        for module, parents in [('api', []), ('impl', ['api']), ('app', ['api', 'impl'])]:
            roots = [dict(path='src', scope='MAIN')]
            if module == 'app':
                roots.append(dict(path='test', scope='TEST'))
            modules.append(dict(id=module, root=module, source_version='8', source_roots=roots,
                                dependencies={'MAIN': parents, 'TEST': parents}))
        inputs = dict(schema_version=3, scopes=['MAIN', 'TEST'],
                      controls={'pom.xml': sha(pom.read_bytes())},
                      effective_model_sha256=sha(b'cross-module wiring'),
                      active_profiles_sha256=sha(b'profiles'), modules=modules)
        (self.project / '.code-ana/inputs.json').write_text(json.dumps(inputs))
        (self.project / '.code-ana/config.toml').write_text('''schema_version=1
kind="project"
[scan]
scopes=["MAIN","TEST"]
[languages.java]
enabled=true
[languages.java.analysis]
source_version="8"
toolchain="java8"
[languages.java.discovery]
build_system="maven"
inputs_manifest=".code-ana/inputs.json"
[languages.java.extensions.spring]
enabled=true
[resources.index]
java_parallelism=1
''')

        def verify(facts, count):
            product, expected = projection(facts, self.root)
            self.assertEqual(product, expected)
            self.assertEqual(len(product), count)
            self.assertTrue(all(row['confidence'] == ('AMBIGUOUS' if count == 4 else 'INFERRED')
                                for row in product))
            ids = {f['id']: f for f in facts}
            wires = [f for f in facts if f['relation'] == 'wires_possible']
            self.assertEqual(len({f['owner']['world'] for f in wires}), 2)
            self.assertTrue(any(ids[f['target']]['owner']['world'] != f['owner']['world'] for f in wires))
            self.assertTrue(any(f['kind'] == 'external_symbol' and f['signature'] == 'p.Api'
                                and f['attributes'].get('source_origin') for f in facts))

        _, initial = self.cli('index', '--full', timeout=180)
        self.assertEqual(initial['publication'], 'published')
        before = self.facts(initial['revision'])
        verify(before, 4)
        _, cached = self.cli('index', timeout=180)
        self.assertEqual(before, self.facts(cached['revision']))
        first = self.project / 'impl/src/p/First.java'
        first.write_text('package p; public class First {}')
        _, delta = self.cli('index', timeout=180)
        changed = self.facts(delta['revision'])
        verify(changed, 2)
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(changed, self.facts(full['revision']))
        self.assertEqual(before, self.facts(initial['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_supplemental_primitive_proof_publishes_and_invalidates_through_daemon(self):
        from java_lombok_alignment import Lombok
        library = 'package p; @lombok.Getter class Api {Integer delay;}'
        # Stub annotations have no Lombok jar/version from which to infer this policy.
        (self.project / 'lombok.config').write_text(
            'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        for relative, text in Lombok.files(self, library).items():
            path = self.project / 'src' / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        consumer = self.project / 'test/p/Use.java'
        consumer.parent.mkdir(parents=True)
        consumer.write_text('''package p; /* 😀中 */ class Use {void read(Api api){
            java.util.Optional.ofNullable(api).map(Api::getDelay)
            .map(value -> Math.min(value,10)*1000).ifPresent(value -> {
                try{Thread.sleep(value);}catch(InterruptedException error){}
            });}}''')
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text().replace('scopes = ["MAIN"]', 'scopes = ["MAIN", "TEST"]')
                          + '\n[[modules.source_roots]]\npath="test"\nscope="TEST"\n'
                            '\n[languages.java.lombok]\nmode="model"\n')
        _, initial = self.cli('index', '--full', timeout=180)
        self.assertEqual(initial['publication'], 'published')
        before = self.facts(initial['revision'])
        by = {f['id']: f for f in before}
        target = next(f for f in before if f['kind'] == 'call_target'
                      and by[f['target']]['signature'] == 'java.lang.Math#min(int,int)')
        site = by[target['parent']]
        self.assertEqual(type_attributes(site)['expression_type_model']['kind'], 'ERROR')
        records, terminal = self.cli('dispatch', site['id'])
        self.assertFalse(terminal['evidence']['semantic'])
        self.assertTrue(any(row.get('target') == target['target'] and row.get('possible') for row in records))
        provenance = next(f for f in before if f['kind'] == 'provenance' and f['source'] == 'test/p/Use.java')
        proof = next(p for p in type_attributes(provenance)['supplemental_expression_evidence'] if p['node_kind'] == 'MULTIPLY')
        self.assertEqual(proof['type_model']['kind'], 'INT')
        self.assertTrue(proof['original_evidence_retained'])
        self.assertEqual(len(proof['signature_sha256']), 64)
        self.assertEqual(len(proof['auxiliary_inputs_sha256']), 64)
        span = proof['span']
        self.assertEqual(consumer.read_bytes()[span['start']:span['end']], b'Math.min(value,10)*1000')
        self.assertTrue(any(f['kind'] == 'diagnostic' and f['status'] == 'ERROR' for f in before))
        # Incomplete generated-annotation policy must invalidate the whole auxiliary ABI.
        policy = self.project / 'lombok.config'
        captured_policy = policy.read_bytes()
        policy.unlink()
        _, incomplete = self.cli('index', timeout=180)
        incomplete_facts = self.facts(incomplete['revision'])
        summary = next(f['attributes'] for f in incomplete_facts
                       if f['relation'] == 'java.lombok' and f['source'] == 'src/App.java')
        self.assertIn('generated_annotations', summary['partial_capabilities'])
        self.assertFalse(any(f['attributes'].get('supplemental_expression_evidence')
                             for f in incomplete_facts))
        _, incomplete_full = self.cli('index', '--full', timeout=180)
        self.assertEqual(incomplete_facts, self.facts(incomplete_full['revision']))
        policy.write_bytes(captured_policy)
        _, restored = self.cli('index', timeout=180)
        self.assertEqual(before, self.facts(restored['revision']))
        (self.project / 'src/App.java').write_text(library.replace('Integer', 'String'))
        _, delta = self.cli('index', timeout=180)
        changed = self.facts(delta['revision'])
        ids = {f['id']: f for f in changed}
        self.assertFalse(any(f['kind'] == 'call_target' and ids[f['target']]['signature'] == 'java.lang.Math#min(int,int)'
                             for f in changed))
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(changed, self.facts(full['revision']))
        self.assertEqual(before, self.facts(initial['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_super_builder_receiver_proof_preserves_ambiguity_and_removal_through_daemon(self):
        from java_lombok_alignment import Lombok
        from java_supplemental_signature_alignment import SUPER_LIBRARY, SUPER_CONSUMER
        (self.project / 'lombok.config').write_text(
            'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        files = Lombok.files(self, SUPER_LIBRARY)
        files['lombok/experimental/SuperBuilder.java'] = (
            'package lombok.experimental;public @interface SuperBuilder {'
            'String builderMethodName() default "builder";String buildMethodName() default "build";'
            'boolean toBuilder() default false;String setterPrefix() default "";}')
        for relative, content in files.items():
            path = self.project / 'src' / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        consumer = self.project / 'test/Use.java'
        consumer.parent.mkdir(parents=True)
        consumer.write_text(SUPER_CONSUMER)
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text().replace('scopes = ["MAIN"]', 'scopes = ["MAIN", "TEST"]')
                          + '\n[[modules.source_roots]]\npath="test"\nscope="TEST"\n'
                            '\n[languages.java.lombok]\nmode="model"\n')
        _, initial = self.cli('index', '--full', timeout=180)
        self.assertEqual(initial['publication'], 'published')
        before = self.facts(initial['revision'])
        by = {f['id']: f for f in before}
        targets = [f for f in before if f['kind'] == 'call_target' and f['source'] == 'test/Use.java']
        self.assertEqual(len(targets), 8)
        self.assertNotIn('Child#builder()', {by[f['target']]['signature'] for f in targets})
        provenance = next(f for f in before if f['kind'] == 'provenance' and f['source'] == 'test/Use.java')
        proof = next(p for p in type_attributes(provenance)['supplemental_receiver_evidence']
                     if p['receiver_type_model']['element'] == 'Child$ChildBuilder')
        self.assertEqual(proof['selected_declaration'], 'Parent$ParentBuilder#values(java.util.List)')
        self.assertTrue(proof['original_evidence_retained'])
        (self.project / 'src/App.java').write_text(
            SUPER_LIBRARY.replace('@lombok.experimental.SuperBuilder class Child', 'class Child'))
        _, delta = self.cli('index', timeout=180)
        changed = self.facts(delta['revision'])
        ids = {f['id']: f for f in changed}
        self.assertFalse(any(f['kind'] == 'call_target' and ids[f['target']]['signature'] == 'Child$ChildBuilder#age(int)'
                             for f in changed))
        self.assertFalse(any(p['receiver_type_model'].get('element') == 'Child$ChildBuilder'
                             for f in changed for p in type_attributes(f).get('supplemental_receiver_evidence', [])))
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(changed, self.facts(full['revision']))
        self.assertEqual(before, self.facts(initial['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_source_type_relationships_keep_navigation_queries_and_delta(self):
        from java_type_reference_alignment import SOURCE
        path = self.project / 'src/p/Types.java'
        path.write_text(SOURCE)
        _, first = self.cli('index', '--full', timeout=180)
        self.assertEqual(first['publication'], 'published')
        facts = self.facts(first['revision'])
        by = {f['id']: f for f in facts}
        leaf = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'p.Leaf')
        refs = [f for f in facts if f['source'] == 'src/p/Types.java' and f['relation'] == 'type_reference']
        self.assertEqual(len(refs), 23)
        body_navigation = [f for f in facts if f['relation'] == 'type_navigation' and f['target'] == leaf['id']
                           and '#read(' in by[f['parent']]['signature']
                           and f['span']['start'] > SOURCE.encode().index(b'Leaf local=')]
        self.assertTrue(body_navigation)
        rows, terminal = self.cli('references', leaf['id'])
        self.assertEqual(terminal['code'], 'OK')
        self.assertTrue(rows)
        self.assertIn('type_navigation', json.dumps(rows))
        path.write_text(SOURCE.replace('Box<Leaf> field;', 'Leaf field;'))
        _, delta = self.cli('index', timeout=180)
        changed = self.facts(delta['revision'])
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(sorted(changed, key=lambda f: f['id']),
                         sorted(self.facts(full['revision']), key=lambda f: f['id']))
        path.unlink()
        _, removed = self.cli('index', timeout=180)
        self.assertFalse([f for f in self.facts(removed['revision']) if f['source'] == 'src/p/Types.java'])

    def test_functional_outputs_source_types_queries_and_cache_delta(self):
        from java_lombok_alignment import Lombok
        source = 'package p;\n    class Holder {@lombok.Getter Api api; @lombok.Getter Factory factory;}\n    class Api {@lombok.Getter String value; void run(){} }\n    class Factory {static <T> T create(java.util.function.Supplier<T> supplier){return null;}\n     <T,R> R convert(java.util.function.Function<T,R> function){return null;}}\n    class Use {void read(Holder h){Factory.create(() -> h.getApi()).run();\n     Factory.create(h::getApi).run();h.getFactory().convert(Api::getValue).length();}}\n    '
        source += '\n@lombok.Getter class Envelope {java.util.Map<String,Object> extraContent; <T> T getExtraContent(String key){return null;}}\nclass OverloadUse {void read(Envelope e){e.getExtraContent().get("name");}}\n'
        source = source.replace('class Factory {', 'class Factory {<T,R> R convert(T input,java.util.function.Function<T,R> fn){return null;} ')
        source += '\nclass Box<T> {@lombok.Getter T value;} class Child extends Box<Api> {}\nclass GenericUse {static void read(Holder h,Box<Api> box,Child child,java.util.Map.Entry<String,Api> entry){h.getFactory().convert(box,Box::getValue).run();h.getFactory().convert(child,Child::getValue).run();h.getFactory().convert(entry,java.util.Map.Entry::getValue).run();}}\nclass Receiver {static Api current(){return null;} static void read(){current().getValue();current().absent();}}\n'
        source += '\nclass Context {@lombok.Getter java.util.Map<String,String> headers;} class ConstructorUse {static java.util.Map<String,String> read(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElseGet(java.util.HashMap::new);}}\n@lombok.NoArgsConstructor @lombok.AllArgsConstructor class Bean {String value;} class Store {static ThreadLocal<Bean> local=ThreadLocal.withInitial(Bean::new);}\n'
        source += '\nclass Rule {@lombok.Getter String key;static Rule build(String text){return null;}} class CollectorUse {static java.util.Map<String,Rule> build(java.util.List<String> texts){return texts.stream().map(Rule::build).collect(java.util.stream.Collectors.toMap(Rule::getKey,item -> item,(v1,v2)->v2));}}\n@lombok.Setter class ItemsContext {java.util.List<Rule> items;} class DiamondUse {void read(ItemsContext c){c.setItems(new java.util.ArrayList<>());c.setItems(new java.util.ArrayList<String>());}}\n'
        for path, text in Lombok.files(self, source).items():
            dest = self.project / 'src' / path
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(text)
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text() + '\n[languages.java.lombok]\nmode="model"\n')

        def verify(facts):
            by_id = {f['id']: f for f in facts}
            symbols = {f['signature']: f for f in facts if f['kind'] in ('declaration', 'external_symbol')}
            targets = [f for f in facts if f['kind'] == 'call_target' and f['status'] == 'possible']
            for signature in ('p.Factory#create(java.util.function.Supplier)', 'p.Api#run()', 'p.Api#getValue()', 'java.lang.String#length()', 'p.Factory#convert(java.util.function.Function)', 'p.Envelope#getExtraContent()', 'java.util.Map#get(java.lang.Object)'):
                self.assertIn(signature, {by_id[f['target']]['signature'] for f in targets})
            self.assertTrue(all((not f['evidence_complete'] for f in targets)))
            generic = symbols['p.GenericUse#read(p.Holder,p.Box,p.Child,java.util.Map$Entry)']
            owned = [f for f in targets if by_id[f['parent']]['parent'] == generic['id']]
            self.assertEqual(sum(by_id[f['target']]['signature'] == 'p.Api#run()' for f in owned), 3)
            self.assertEqual(sum(by_id[f['target']]['signature'] == 'p.Factory#convert(java.lang.Object,java.util.function.Function)' for f in owned), 3)
            receiver = symbols['p.Receiver#read()']
            inner = [f for f in facts if f['kind'] == 'call_site' and f['parent'] == receiver['id'] and f['name'] == 'current']
            self.assertEqual(len(inner), 2)
            self.assertTrue(all(f['status'] == 'resolved' and f['evidence_complete'] for f in inner))
            constructor = next(f for f in targets if by_id[f['target']]['signature'] == 'java.util.HashMap#<init>()')
            self.assertEqual([a['element'] for a in type_attributes(constructor)['recovered_return_type_model']['arguments']], ['java.lang.String', 'java.lang.String'])
            self.assertIn('java.lang.ThreadLocal#withInitial(java.util.function.Supplier)', {by_id[f['target']]['signature'] for f in targets})
            for signature in ('java.util.stream.Stream#collect(java.util.stream.Collector)',
                              'java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function,java.util.function.BinaryOperator)',
                              'p.Rule#getKey()'):
                self.assertIn(signature, {by_id[f['target']]['signature'] for f in targets})
            setters = [f for f in targets if by_id[f['target']]['signature'] == 'p.ItemsContext#setItems(java.util.List)']
            self.assertEqual(len(setters), 1)
            self.assertEqual(type_attributes(setters[0])['recovered_parameter_type_models'][0]['arguments'][0]['element'], 'p.Rule')
            method = symbols['p.Use#read(p.Holder)']
            parameter_types = [f for f in facts if f['relation'] == 'type_reference' and f['parent'] == method['id'] and (f['target'] == symbols['p.Holder']['id'])]
            self.assertEqual(len(parameter_types), 1)
            calls, terminal = self.cli('calls', method['id'])
            self.assertFalse([r for r in calls if r.get('to') == symbols['p.Factory#create(java.util.function.Supplier)']['id']])
            selected = next((f for f in targets if by_id[f['target']]['signature'] == 'p.Factory#create(java.util.function.Supplier)'))
            dispatch, terminal = self.cli('dispatch', selected['parent'])
            self.assertTrue(any((r.get('target') == selected['target'] and r.get('possible') for r in dispatch)), dispatch)
            rows, terminal = self.cli('source', method['id'])
            self.assertTrue(rows)
        _, terminal = self.cli('index', '--full', timeout=180)
        self.assertEqual(terminal['publication'], 'published')
        first = self.facts(terminal['revision'])
        verify(first)
        _, unchanged = self.cli('index', timeout=180)
        self.assertEqual(unchanged['publication'], 'published')
        verify(self.facts(unchanged['revision']))
        source_file = self.project / 'src/App.java'
        source_file.write_text(source.replace('String value;', 'String value; int marker;'))
        _, delta = self.cli('index', timeout=180)
        delta_facts = self.facts(delta['revision'])
        verify(delta_facts)
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(sorted(delta_facts, key=lambda f: f['id']), sorted(self.facts(full['revision']), key=lambda f: f['id']))

    def test_field_calls_and_callbacks_publish_query_and_delta(self):
        path=self.project/'src/p/Fields.java'
        path.write_text('''package p; import java.util.function.*; class Fields {
          static String build(String text){return text;}
          static String produce(){return "value";}
          static final String STATIC=build("static");
          String instance=build("instance");
          Supplier<String> lazy=() -> build("lambda");
          Supplier<String> reference=Fields::produce;
          static { build("block"); }
        } enum Choice { FIRST(Fields.build("enum")); Choice(String text){} }''')
        def verify(facts):
            declarations={f['id']:f for f in facts if f['kind']=='declaration'}
            calls=[f for f in facts if f['kind']=='call_site' and f['source']=='src/p/Fields.java']
            self.assertFalse([f for f in declarations.values() if '<field-init:' in f['signature']])
            for name in ('STATIC','instance','FIRST'):
                owner=next(f for f in declarations.values() if f['name']==name and f['attributes']['element_kind'] in ('FIELD','ENUM_CONSTANT'))
                owned=[f for f in calls if f['parent']==owner['id'] and f['attributes'].get('lookup_name')=='build']
                self.assertEqual(len(owned),1,(name,owned))
                resolved,_=self.cli('calls',owner['id'])
                self.assertTrue(any(declarations[f['to']]['signature']=='p.Fields#build(java.lang.String)' for f in resolved),resolved)
                source,_=self.cli('source',owner['id'])
                self.assertIn('build',json.dumps(source))
            for name,kind in (('lazy','LAMBDA'),('reference','METHOD_REF')):
                field=next(f for f in declarations.values() if f['name']==name and f['attributes']['element_kind']=='FIELD')
                callback=next(f for f in declarations.values() if f['parent']==field['id'] and f['attributes']['element_kind']==kind)
                self.assertEqual(len([f for f in calls if f['parent']==callback['id']]),1)
        _,initial=self.cli('index','--full',timeout=150)
        self.assertEqual(initial['publication'],'published');verify(self.facts(initial['revision']))
        path.write_text(path.read_text().replace('"static"','"changed"'))
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        actual=self.facts(delta['revision']);self.assertEqual(actual,self.facts(full['revision']));verify(actual)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_large_partial_index_terminal_preserves_revision_and_complete_task_evidence(self):
        folder=self.project/'src/p'/('terminal-output-'+('x'*88))/('evidence-path-'+('y'*88))
        folder.mkdir(parents=True)
        for number in range(192):
            (folder/f'Partial{number}.java').write_text(f'package p; class Partial{number} {{ missing.Type value; }}')
        records,terminal=self.cli('index','--full',timeout=180)
        self.assertEqual(terminal['publication'],'published')
        self.assertEqual(terminal['code'],'OK')
        self.assertEqual(terminal['exit_code'],0)
        self.assertFalse(terminal['evidence']['semantic'])
        self.assertIn('terminal_evidence_details',terminal['evidence']['limits'])
        omitted=[reason for reason in terminal['evidence']['reasons'] if reason.startswith('terminal_reasons_omitted:')]
        self.assertEqual(len(omitted),1)
        revision=terminal['revision']
        self.assertTrue(any(record.get('revision')==revision for record in records))
        with sqlite3.connect('file:'+str(self.home/'catalog.db')+'?mode=ro',uri=True) as db:
            row=db.execute("SELECT result FROM tasks WHERE json_extract(result,'$.revision')=?",(revision,)).fetchone()
        persisted=json.loads(row[0])
        self.assertEqual(persisted['publication'],'published')
        self.assertEqual(persisted['code'],'OK')
        details=persisted['output']['evidence']['reasons']
        self.assertGreater(len(json.dumps(details).encode()),32768)
        self.assertEqual(len(details),len(terminal['evidence']['reasons'])-1+int(omitted[0].split(':',1)[1]))
        self.assertNotIn('terminal_evidence_details',persisted['output']['evidence']['limits'])
        definitions,_=self.cli('resolve','Partial0','--revision',revision)
        self.assertTrue(any(record.get('signature')=='p.Partial0' for record in definitions))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_aligned_lombok_names_inheritance_poly_and_enum_source_projections(self):
        from java_lombok_alignment import Lombok
        source='''package p; interface Root {} interface Child extends Root {}
          class Explicit extends Object {} @lombok.Data class Item {String oAuthToken;}
          enum Choice {FIRST,SECOND} class Holder {@lombok.Getter Choice choice;}
          @lombok.Builder class Built {@validation.NotBlank(message="required") String value;}
          class Api {java.util.List<Item> items(String key){return null;}}
          class Aligned {void run(Api api,java.util.Map<String,java.util.function.Function<String,String>> actions){
            actions.put("key", key -> {java.util.List<Item> items=api.items(key);
              items.forEach(item -> item.toString());return "value";});}
            int choice(Holder value){switch(value.getChoice()) {case FIRST:return 1;case SECOND:return 2;default:return 0;}}
            Class<?> type(){return Item.class;}}'''
        files=Lombok.files(self,source)
        files['lombok/Builder.java']='package lombok; public @interface Builder {}'
        files['validation/NotBlank.java']='package validation; public @interface NotBlank {String message() default "required";}'
        for name,body in files.items():
            path=self.project/'src'/('p/Aligned.java' if name=='App.java' else name)
            path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body)
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        (self.project/'lombok.config').write_text('config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        def verify(facts):
            declarations={f['id']:f for f in facts if f['kind'] in ('declaration','external_symbol')}
            signatures={f['signature']:f for f in declarations.values()}
            self.assertIn('p.Item#getOAuthToken()',signatures)
            self.assertNotIn('p.Item#getoAuthToken()',signatures)
            self.assertEqual(signatures['p.Item#equals(java.lang.Object)']['attributes']['parameter_names'],['other'])
            inheritance={(declarations[f['parent']]['signature'],f['relation'],declarations[f['target']]['signature']) for f in facts if f['kind']=='relation' and f['relation'] in ('extends','implements')}
            self.assertIn(('p.Child','extends','p.Root'),inheritance)
            self.assertNotIn(('p.Child','implements','p.Root'),inheritance)
            self.assertIn(('p.Explicit','extends','java.lang.Object'),inheritance)
            self.assertNotIn(('p.Item','extends','java.lang.Object'),inheritance)
            targets={declarations[f['target']]['signature'] for f in facts if f['kind']=='call_target' and f['status']=='possible'}
            self.assertTrue({'p.Item#toString()','p.Api#items(java.lang.String)',
                'java.util.Map#put(java.lang.Object,java.lang.Object)',
                'java.lang.Iterable#forEach(java.util.function.Consumer)'}.issubset(targets),targets)
            labels=[f for f in facts if f['relation']=='reads' and f['origin']=='source_recovery']
            self.assertEqual({declarations[f['target']]['signature'] for f in labels},{'p.Choice#FIRST','p.Choice#SECOND'})
            self.assertTrue(all(f['status']=='possible' and not f['evidence_complete'] for f in labels))
            field=signatures['p.Built$BuiltBuilder#value']
            self.assertTrue([f for f in facts if f['relation']=='java.annotation' and f['parent']==field['id'] and f['origin']=='source_projection' and f['attributes']['resolved_type']=='validation.NotBlank'])
            self.assertFalse([f for f in declarations.values() if f['signature'].endswith('#class')])
            method=signatures['p.Aligned#run(p.Api,java.util.Map)']
            sites,_=self.cli('call-sites',method['id'])
            self.assertTrue(sites)
            dispatch,_=self.cli('dispatch',sites[0]['id'])
            self.assertTrue(any(row.get('possible') is True for row in dispatch),dispatch)
        _,initial=self.cli('index','--full',timeout=180);self.assertEqual(initial['publication'],'published')
        verify(self.facts(initial['revision']))
        path=self.project/'src/p/Aligned.java';path.write_text(path.read_text().replace('return 2','return 3'))
        _,delta=self.cli('index',timeout=180);_,full=self.cli('index','--full',timeout=180)
        actual=self.facts(delta['revision']);verify(actual);self.assertEqual(actual,self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_core(self):
        first_records,terminal=self.cli('index','--full',timeout=150)
        self.assertEqual(terminal['publication'],'published')
        first_worker=self.java_worker_pid();self.assertIsNotNone(first_worker,str(first_records)+'\n'+self.daemon_log())
        declarations,_=self.cli('resolve','main')
        self.assertEqual(len(declarations),1)
        sites,_=self.cli('call-sites','main')
        self.assertGreaterEqual(len(sites),5)
        calls,_=self.cli('calls','main')
        self.assertTrue(calls)
        source,_=self.cli('source','main')
        self.assertIn('service.greet',json.dumps(source))
        self.assertFalse(list(self.project.rglob('*.class')))
        _,again=self.cli('index',timeout=150)
        self.assertEqual(again['publication'],'published')
        self.assertEqual(self.java_worker_pid(),first_worker)
        self.assertEqual(self.facts(terminal['revision']),self.facts(again['revision']))
        path=self.project/'src/p/App.java'
        path.write_text(path.read_text().replace('service.greet("two");','service.greet("changed"); service.greet("added");'))
        records,delta=self.cli('index',timeout=150)
        self.assertEqual(records[0]['mode'],'delta')
        self.assertEqual(self.java_worker_pid(),first_worker)
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
    def test_parenthesized_field_writes_and_array_elements_delta(self):
        source = self.project/'src/p/Access.java'
        source.write_text('''package p;
          class Fields { static int count; int value; int[] array; }
          class Access { void run(Fields fields) {
            ((Fields.count))=1; (Fields.count)+=2; ++(Fields.count); ((Fields.count))--;
            (fields.value)=3; ((fields.value))+=4; ++((fields.value)); (fields.value)--;
            ((fields.array[Fields.count]))=5;
            (fields.array[Fields.count])+=6;
            ++((fields.array[0]));
          } Object self(){return this;} int size(){return new int[1].length;} }''')

        def check(facts):
            by_id = {f['id']: f for f in facts}
            self.assertFalse([f for f in facts if f['signature'] in ('p.Access#this', 'Array#length')])
            counts = {}
            for fact in facts:
                if fact['relation'] not in ('reads', 'writes'):
                    continue
                signature = by_id[fact['target']]['signature']
                if not signature.startswith('p.Fields#'):
                    continue
                self.assertIsNotNone(fact['span'])
                key = (fact['relation'], signature)
                counts[key] = counts.get(key, 0) + 1
            self.assertEqual(counts, {('reads', 'p.Fields#count'): 5,
                                     ('writes', 'p.Fields#count'): 4,
                                     ('reads', 'p.Fields#value'): 3,
                                     ('writes', 'p.Fields#value'): 4,
                                     ('reads', 'p.Fields#array'): 3})

        _, first = self.cli('index', '--full', timeout=150)
        check(self.facts(first['revision']))
        source.write_text(source.read_text().replace('=1;', '=7;'))
        _, delta = self.cli('index', timeout=150)
        _, full = self.cli('index', '--full', timeout=150)
        current = self.facts(delta['revision'])
        self.assertEqual(current, self.facts(full['revision']))
        check(current)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_binary_field_static_import_receiver_and_enum_accesses_delta(self):
        import hashlib
        shutil.rmtree(self.project/'src')
        (self.project/'src').mkdir()
        library = self.root/'library/ext'
        library.mkdir(parents=True)
        (library/'Base.java').write_text('package ext; public class Base { public int value; public static int count; }')
        (library/'Kind.java').write_text('package ext; public enum Kind { ONE }')
        classes = self.root/'classes'
        classes.mkdir()
        subprocess.run([str(JDK/'bin/javac'), '-proc:none', '-d', str(classes),
                        str(library/'Base.java'), str(library/'Kind.java')],
                       check=True, capture_output=True, timeout=30)
        jar = self.root/'fields.jar'
        subprocess.run([str(JDK/'bin/jar'), 'cf', str(jar), '-C', str(classes), '.'],
                       check=True, capture_output=True, timeout=30)
        shutil.rmtree(classes)
        use = self.project/'src/Use.java'
        use.write_text('''import static ext.Base.count;
          class Use extends ext.Base { int[] array; int length;
            int read() { this.value=1; super.value+=2; (this.value)++;
              count=1; count+=2; ++count; count--;
              int n=array.length+((int[])null).length+length;
              ext.Kind k=ext.Kind.ONE; return this.value+super.value+n;
            } Object self(){return this;}
          }''')
        pom = self.project/'pom.xml'
        pom.write_text('<project/>')
        metadata = dict(schema_version=3, controls={'pom.xml': hashlib.sha256(pom.read_bytes()).hexdigest()},
                        effective_model_sha256=hashlib.sha256(b'fields effective model').hexdigest(),
                        active_profiles_sha256=hashlib.sha256(b'fields profiles').hexdigest(),
                        modules=[dict(id='app', root='.', source_version='8',
                                      source_roots=[dict(path='src', scope='MAIN')],
                                      classpath={'MAIN':[dict(path=str(jar),sha256=hashlib.sha256(jar.read_bytes()).hexdigest())]})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n')

        def check(facts):
            by_id = {f['id']:f for f in facts}
            counts = {}
            self.assertFalse([f for f in facts if f['signature'] in ('Use#this', 'Use#super', 'Array#length')])
            for fact in facts:
                if fact['relation'] in ('reads', 'writes'):
                    key = (fact['relation'], by_id[fact['target']]['signature'])
                    counts[key] = counts.get(key, 0) + 1
            self.assertEqual(counts, {('writes', 'ext.Base#value'): 3, ('reads', 'ext.Base#value'): 4,
                                     ('writes', 'ext.Base#count'): 4, ('reads', 'ext.Base#count'): 3,
                                     ('reads', 'Use#array'): 1, ('reads', 'Use#length'): 1,
                                     ('reads', 'ext.Kind#ONE'): 1})
        _, first = self.cli('index', '--full', timeout=150)
        check(self.facts(first['revision']))
        use.write_text(use.read_text().replace('count=1;', 'count=7;'))
        _, delta = self.cli('index', timeout=150)
        _, full = self.cli('index', '--full', timeout=150)
        current = self.facts(delta['revision'])
        self.assertEqual(current, self.facts(full['revision']))
        check(current)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_lombok_captured_configuration_and_declaration_delta(self):
        shutil.rmtree(self.project/'src')
        source = self.project/'src'
        (source/'lombok').mkdir(parents=True)
        (source/'lombok/Getter.java').write_text('package lombok; public @interface Getter {}')
        (source/'Bean.java').write_text('@lombok.Getter class Bean { boolean ready; } class Use { boolean read(Bean b){return b.getReady();} }')
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        (self.root/'lombok.config').write_text('lombok.getter.noIsPrefix=false\n')
        control = self.project/'lombok.config'
        control.write_text('config.stopBubbling=true\nlombok.getter.noIsPrefix=true\nlombok.addLombokGeneratedAnnotation=false\n')
        _, first = self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        old = self.facts(first['revision'])
        getter = next(f for f in old if f['signature']=='Bean#getReady()')
        self.assertEqual(getter['origin'],'lombok_model')
        self.assertFalse(getter['declaration_resolved'])
        self.assertTrue(any(f['kind']=='call_target' and f['target']==getter['id'] and f['status']=='possible' for f in old))
        summary = next(f['attributes'] for f in old if f['relation']=='java.lombok' and 'configuration_inputs' in f['attributes'])
        self.assertEqual([item[0] for item in summary['configuration_inputs']], ['lombok.config'])
        control.write_text(control.read_text().replace('noIsPrefix=true','noIsPrefix=false'))
        _, delta = self.cli('index',timeout=150)
        _, full = self.cli('index','--full',timeout=150)
        actual = self.facts(delta['revision'])
        self.assertEqual(actual,self.facts(full['revision']))
        self.assertFalse(any(f['signature']=='Bean#getReady()' for f in actual))
        self.assertTrue(any(f['signature']=='Bean#isReady()' for f in actual))
        control.unlink()
        _, delta = self.cli('index',timeout=150)
        _, full = self.cli('index','--full',timeout=150)
        actual = self.facts(delta['revision'])
        self.assertEqual(actual,self.facts(full['revision']))
        summary = next(f['attributes'] for f in actual if f['relation']=='java.lombok' and 'configuration_inputs' in f['attributes'])
        self.assertEqual([item[0] for item in summary['configuration_inputs']], ['.code-ana/lombok-ancestors/000001/lombok.config'])
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_lombok_builder_recursive_generics_defaults_and_delta(self):
        from java_lombok_builder_alignment import Builders
        fixture=Builders();fixture.setUp();self.addCleanup(fixture.doCleanups)
        source='@lombok.Builder(toBuilder=true) class A<T extends Number & Comparable<T>> {@lombok.Builder.Default final int count=7;T value;} class Use {A<Integer> run(){return A.<Integer>builder().value(1).count(2).build();}}'
        context=fixture.fixture(source)
        shutil.rmtree(self.project/'src');(self.project/'src').mkdir()
        for unit in context['sources']:
            target=self.project/'src'/pathlib.Path(unit['path']).relative_to('app')
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(unit['input'],target)
        (self.project/'lombok.config').write_text('config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        config=self.project/'.code-ana/config.toml';config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        _,first=self.cli('index','--full',timeout=150);facts=self.facts(first['revision'])
        declarations={f['signature']:f for f in facts if f['kind']=='declaration'}
        for signature in ('A$ABuilder','A#builder()','A#toBuilder()','A#$default$count()','A$ABuilder#count$value','A$ABuilder#count$set','A$ABuilder#value(java.lang.Number)','A$ABuilder#build()'):
            self.assertIn(signature,declarations)
            self.assertEqual(declarations[signature]['origin'],'lombok_model')
            self.assertFalse(declarations[signature]['declaration_resolved'])
        setter=declarations['A$ABuilder#value(java.lang.Number)']
        self.assertEqual(type_attributes(setter)['parameter_type_models'][0]['owner'],'A$ABuilder')
        self.assertTrue(any(f['kind']=='call_target' and f['target']==setter['id'] and f['status']=='possible' for f in facts))
        path=self.project/'src/Bean.java'
        # The fixture's primary class filename is fixed by the shared annotation helper.
        if not path.exists():path=next(p for p in (self.project/'src').glob('*.java') if 'class A<' in p.read_text())
        path.write_text(path.read_text().replace('toBuilder=true','toBuilder=false').replace('count=7','count=9'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(any(f['signature']=='A#toBuilder()' for f in self.facts(full['revision'])))
        path.write_text(path.read_text().replace('@lombok.Builder(toBuilder=false)','').replace('@lombok.Builder.Default',''))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        self.assertFalse(any(f['kind']=='declaration' and f['signature']=='A$ABuilder' for f in actual))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_lombok_super_builder_inherited_generic_chain_and_delta(self):
        from java_lombok_super_builder_alignment import SuperBuilders
        fixture=SuperBuilders();fixture.setUp();self.addCleanup(fixture.doCleanups)
        source='@lombok.experimental.SuperBuilder abstract class Parent<T> {T name;} @lombok.experimental.SuperBuilder class Child<N extends Number> extends Parent<String> {@lombok.Builder.Default int count=7;N value;} class Use {Child<Integer> run(){return Child.<Integer>builder().name("x").value(1).count(2).build();}}'
        context=fixture.fixture(source)
        shutil.rmtree(self.project/'src');(self.project/'src').mkdir()
        for unit in context['sources']:
            target=self.project/'src'/pathlib.Path(unit['path']).relative_to('app')
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(unit['input'],target)
        (self.project/'lombok.config').write_text('config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        config=self.project/'.code-ana/config.toml';config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        _,first=self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        facts=self.facts(first['revision']);declarations={f['signature']:f for f in facts if f['kind']=='declaration'}
        for signature in ('Parent$ParentBuilder','Child$ChildBuilder','Child$ChildBuilderImpl','Child#$default$count()','Child$ChildBuilder#count$set'):
            self.assertIn(signature,declarations)
            self.assertFalse(declarations[signature]['declaration_resolved'])
        self.assertNotIn('Parent#builder()',declarations)
        targets={f['target'] for f in facts if f['kind']=='call_target' and f['status']=='possible'}
        for signature in ('Child#builder()','Parent$ParentBuilder#name(java.lang.Object)','Child$ChildBuilder#value(java.lang.Number)','Child$ChildBuilder#count(int)','Child$ChildBuilder#build()'):
            self.assertIn(declarations[signature]['id'],targets,signature)
        call=next(f for f in facts if f['kind']=='call_site' and f['attributes'].get('lookup_name')=='value')
        self.assertEqual(type_attributes(call)['recovered_parameter_type_models'][0]['element'],'java.lang.Integer')
        path=next(p for p in (self.project/'src').glob('*.java') if 'class Child<' in p.read_text())
        path.write_text(path.read_text().replace('count=7','count=9').replace('value(1)','value("wrong")'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        call=next(f for f in actual if f['kind']=='call_site' and f['attributes'].get('lookup_name')=='value')
        self.assertEqual(call['attributes']['recovery_failure'],'no_applicable_candidate')
        path.write_text(path.read_text().replace('@lombok.experimental.SuperBuilder','').replace('@lombok.Builder.Default',''))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        self.assertFalse(any(f['attributes'].get('modeled_capability')=='super_builder' for f in actual))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_last_source_deletion(self):
        self.cli('index',timeout=150)
        (self.project/'src/p/App.java').unlink()
        _,deleted=self.cli('index',timeout=150)
        self.assertEqual(deleted['publication'],'published')
        self.assertFalse(any(f['kind']=='declaration' for f in self.facts(deleted['revision'])))
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(deleted['revision']),self.facts(full['revision']))

    def test_modeled_overrides_query_dispatch_and_delta(self):
        from java_lombok_alignment import Lombok
        shutil.rmtree(self.project/'src')
        source = '''interface Contract<T> { T getValue(); }
          @lombok.Getter class Bean implements Contract<String> { String value; }
          @lombok.Getter class Parent<T> { T value; }
          class Child extends Parent<String> { public String getValue(){return "yes";} }
          class Bad extends Parent<String> { public int getValue(){return 1;} }
          class Use { Object run(Contract<String> contract){return contract.getValue();} }'''
        for name, text in Lombok.files(self, source).items():
            path = self.project/'src'/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        def verify(facts):
            by_id = {f['id']:f for f in facts}
            edges = [f for f in facts if f['relation']=='overrides']
            self.assertEqual({(by_id[f['parent']]['signature'],by_id[f['target']]['signature']) for f in edges},
                             {('Bean#getValue()','Contract#getValue()'),('Child#getValue()','Parent#getValue()')})
            self.assertTrue(all(f['origin']=='modeled_override' and f['status']=='possible'
                                and not f['evidence_complete'] and not f['declaration_resolved'] for f in edges))
            bean = next(f for f in facts if f['signature']=='Bean#getValue()')
            relations,_ = self.cli('callable-relations',bean['id'])
            self.assertEqual([f['id'] for f in relations],[f['id'] for f in edges if f['parent']==bean['id']])
            call = next(f for f in facts if f['kind']=='call_site' and f['name']=='contract.getValue')
            dispatch,terminal = self.cli('dispatch',call['id'])
            candidate = next(f for f in dispatch if f['target']==bean['id'])
            self.assertTrue(candidate['possible'])
            self.assertFalse(terminal['evidence']['semantic'])
        _,first = self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        verify(self.facts(first['revision']))
        path=self.project/'src/App.java'
        path.write_text(path.read_text().replace('return "yes";','return "changed";'))
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        actual=self.facts(delta['revision']);self.assertEqual(actual,self.facts(full['revision']));verify(actual)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_recovered_source_calls_method_references_dispatch_and_delta(self):
        shutil.rmtree(self.project/'src')
        source = self.project/'src'
        (source/'lombok').mkdir(parents=True)
        for annotation in ('Getter','Setter'):
            (source/f'lombok/{annotation}.java').write_text('package lombok; public @interface '+annotation+' {}')
        path = source/'Use.java'
        path.write_text('''class Holder { @lombok.Getter Api api; @lombok.Setter String name; }
          class Api { public void choose(long value){} public void choose(Integer value){}
            public <T extends Number & Comparable<T>> T echo(T value){return value;}
            public void spread(String... values){} private void hidden(){} }
          class Use { void run(Holder h){ h.setName("yes"); h.getApi().choose(1);
            h.getApi().echo(1); h.getApi().<Integer>echo(1); h.getApi().<Integer>echo(null);
            h.getApi().<Number>echo(1); h.getApi().<String>echo(1);
            h.getApi().spread("a","b"); h.getApi().hidden();
            java.util.function.Supplier<Api> ref=h::getApi; } }''')
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        _, first = self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        facts = self.facts(first['revision'])
        by_id = {f['id']:f for f in facts}
        recovered = [f for f in facts if f['kind']=='call_target' and f['origin']=='source_recovery']
        self.assertEqual(len(recovered),5)
        explicit = [f for f in recovered if f['attributes'].get('explicit_type_argument_models')]
        self.assertEqual(len(explicit),2)
        self.assertTrue(all(type_attributes(f)['explicit_type_argument_models'][0]['element']=='java.lang.Integer' for f in explicit))
        self.assertTrue(all(f['status']=='possible' and not f['evidence_complete'] and f['declaration_resolved'] for f in recovered))
        self.assertFalse([f for f in facts if f['kind']=='call_target' and by_id[f['parent']]['name']=='h.getApi().hidden'])
        pairs = [(by_id[f['parent']]['name'],by_id[f['target']]['signature']) for f in facts if f['kind']=='call_target']
        self.assertIn(('h::getApi','Holder#getApi()'),pairs)
        caller = next(f for f in facts if f['kind']=='declaration' and f['signature']=='Use#run(Holder)')
        calls,_ = self.cli('calls',caller['id'])
        self.assertEqual(calls,[])
        selected = next(f for f in recovered if by_id[f['parent']]['name']=='h.getApi().choose')
        dispatch,_ = self.cli('dispatch',selected['parent'])
        self.assertEqual(dispatch[0]['binding_origin'],'source_recovery')
        self.assertEqual(dispatch[0]['basis'],'modeled_expression')
        self.assertTrue(dispatch[0]['possible'])
        self.assertEqual(dispatch[0]['recovery_phase'],0)
        path.write_text(path.read_text().replace('choose(long value)','choose(double value)'))
        _, delta = self.cli('index',timeout=150)
        _, full = self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_nested_parameter_identity_and_signature_cache_delta(self):
        shutil.rmtree(self.project/'src')
        source = self.project/'src'; source.mkdir()
        (source/'Outer.java').write_text('class Outer { static class Inner {} }')
        (source/'Api.java').write_text('class Api { static void accept(Outer.Inner value, Outer.Inner[][] array){} }')
        use = source/'Use.java'
        use.write_text('class Use { void run(Outer.Inner value, Outer.Inner[][] array){ Api.accept(value,array); } }')
        _, first = self.cli('index','--full',timeout=150)
        facts = self.facts(first['revision'])
        target = next(f for f in facts if f['signature']=='Api#accept(Outer$Inner,Outer$Inner[][])' and f['kind']=='declaration')
        self.assertTrue(any(f['kind']=='call_target' and f['target']==target['id'] and f['status']=='resolved' for f in facts))
        use.write_text(use.read_text().replace('Api.accept(value,array);','Api.accept(value,array); Api.accept(value,array);'))
        _, delta = self.cli('index',timeout=150)
        _, full = self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_initializer_locals_lambda_parameters_and_delta(self):
        from java_model_tests import initializer_sources, assert_initializer_facts
        shutil.rmtree(self.project/'src')
        source=self.project/'src';source.mkdir()
        for name,text in initializer_sources().items():
            (source/name).write_text(text)
        _,initial=self.cli('index','--full',timeout=150)
        self.assertEqual(initial['publication'],'published')
        assert_initializer_facts(self,self.facts(initial['revision']))
        for name,text in initializer_sources('2').items():
            (source/name).write_text(text)
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        assert_initializer_facts(self,self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_failed_inference_retains_diagnostics_and_stable_delta(self):
        shutil.rmtree(self.project/'src');(self.project/'src').mkdir()
        source=self.project/'src/Inference.java'
        text='''import java.util.*; import java.util.stream.*; class Inference<T> {
          int healthy(){return 1;} List<T> broken(Map<Integer,T> m){return m.entrySet().stream()
            .sorted(Comparator.comparing(o -> o.getValue())).map(Map.Entry::getValue).collect(Collectors.toList());}
        }'''
        source.write_text(text)
        _,initial=self.cli('index','--full',timeout=150)
        self.assertEqual(initial['publication'],'published')
        old=self.facts(initial['revision'])
        self.assertTrue(any(f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR' for f in old))
        self.assertIn('TYPE_VARIABLE_LOWER_BOUND_UNAVAILABLE',json.dumps(old))
        self.assertTrue(any(f['kind']=='declaration' and f['signature']=='Inference#healthy()' for f in old))
        source.write_text(text.replace('return 1;','return 2;'))
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(delta['publication'],'published')
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_spring_xml_tree_member_spans_and_resource_delta(self):
        from java_xml_alignment import SOURCE
        shutil.rmtree(self.project/'src')
        source = self.project/'src'
        source.mkdir()
        (source/'Api.java').write_text(SOURCE+'interface RpcApi {}')
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('[languages.java.extensions.spring]\nenabled = false',
                                                   '[languages.java.extensions.spring]\nenabled = true\nxml = true')
                          .replace('[languages.java.extensions.sofa]\nenabled = false','[languages.java.extensions.sofa]\nenabled = true'))
        xml = self.project/'beans.xml'
        xml.write_text('<beans xmlns:sofa="http://schema.alipay.com/sofa/schema/service"><bean id="api" class="Api" init-method="start"/>'
                      '<alias name="api" alias="external"/><bean id="holder" class="Holder">'
                      '<constructor-arg ref="external"/><property name="nested"><bean class="Api"/>'
                      '</property></bean><sofa:reference id="rpc" interface="RpcApi"><sofa:binding.tr/></sofa:reference>'
                      '<sofa:consumer id="consumer"><sofa:listener ref="api"/><sofa:channel value="topic">'
                      '<sofa:event value="message"/></sofa:channel></sofa:consumer></beans>')
        _, first = self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        facts = self.facts(first['revision'])
        self.assertTrue(any(f['relation']=='config_nested_bean' for f in facts))
        self.assertTrue(any(f['relation']=='java.sofa' and f['attributes'].get('element')=='event'
                            and f['attributes']['channel']=='topic' for f in facts))
        by_id = {f['id']:f for f in facts}
        self.assertTrue(any(f['relation']=='config_member' and by_id[f['target']]['signature']=='Holder#<init>(Api)' for f in facts))
        raw = xml.read_bytes()
        property = next(f for f in facts if f['relation']=='java.spring.xml' and f['attributes'].get('kind')=='property')
        self.assertTrue(raw[property['span']['start']:property['span']['end']].startswith(b'<property'))
        xml.write_text(xml.read_text().replace('init-method="start"','init-method="stop"'))
        _, delta = self.cli('index',timeout=150)
        _, full = self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        xml.unlink()
        _, delta = self.cli('index',timeout=150)
        _, full = self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(any(f['relation'] in ('java.bean','java.spring.xml','java.sofa') for f in self.facts(delta['revision'])))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_reflection_dispatch_preserves_binding_origin_and_delta(self):
        source = self.project/'src/p/App.java'
        source.write_text('''package p; import java.lang.reflect.*;
          class Target { public void work() {} }
          class App { void inspect() throws Exception {
            Method handle=Target.class.getDeclaredMethod("work"); handle.invoke(new Target());
          } }''')
        _, first = self.cli('index', '--full', timeout=150)
        self.assertEqual(first['publication'], 'published')
        facts = self.facts(first['revision'])
        reflected = next(f for f in facts if f['kind']=='call_target' and f['origin']=='reflection_model')
        target = next(f for f in facts if f['id']==reflected['target'])
        self.assertEqual(target['signature'], 'p.Target#work()')
        self.assertFalse(reflected['declaration_resolved'])
        dispatch, terminal = self.cli('dispatch', reflected['parent'])
        candidates = [f for f in dispatch if f.get('binding_origin')=='reflection_model']
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0]['target'], target['id'])
        self.assertEqual(candidates[0]['basis'], 'REFLECTION')
        self.assertTrue(candidates[0]['possible'])
        self.assertFalse(terminal['evidence']['semantic'])
        calls, _ = self.cli('calls', 'inspect')
        self.assertNotIn(target['id'], json.dumps(calls))
        source.write_text(source.read_text().replace('handle.invoke(new Target());', 'handle=null; handle.invoke(new Target());'))
        _, delta = self.cli('index', timeout=150)
        _, full = self.cli('index', '--full', timeout=150)
        self.assertEqual(self.facts(delta['revision']), self.facts(full['revision']))
        self.assertFalse(any(f['kind']=='call_target' and f['origin']=='reflection_model' for f in self.facts(full['revision'])))

    def test_partial_and_strict(self):
        source=self.project/'src/p/App.java'
        source.write_text('package p; class App { void broken() { missing(); } }')
        _,terminal=self.cli('index',timeout=150)
        self.assertEqual(terminal['publication'],'published')
        self.assertFalse(terminal['evidence']['semantic'])
        _,terminal=self.cli('index','--strict',code=3,timeout=150)
        self.assertNotEqual(terminal['publication'],'published')
    def test_spring_xml_binary_only_members_and_resource_delta(self):
        import hashlib
        from java_xml_alignment import Xml
        shutil.rmtree(self.project/'src');(self.project/'src').mkdir()
        (self.project/'src/Anchor.java').write_text('class Anchor {}')
        fixture=Xml('test_binary_only_factory_constructor_inherited_members_and_delta');fixture.setUp();self.addCleanup(fixture.doCleanups)
        context=fixture.binary_context()
        jar=context['classpath'][0]
        resource=self.project/'resources/beans.xml';resource.parent.mkdir()
        shutil.copyfile(fixture.root/'xml/0',resource)
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'binary xml model').hexdigest(),active_profiles_sha256=hashlib.sha256(b'binary xml profiles').hexdigest(),modules=[
            dict(id='app',root='.',source_version='8',source_roots=[dict(path='src',scope='MAIN')],resource_roots=[dict(path='resources',scope='MAIN')],classpath={'MAIN':[dict(path=jar,sha256=hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest())]})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[discovery]\nmode="auto"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[languages.java.extensions.spring]\nenabled=true\nxml=true\n')
        _,first=self.cli('index','--full',timeout=150)
        old=self.facts(first['revision']);fixture.assert_binary_xml(old)
        resource.write_text(resource.read_text().replace('init-method="start"','init-method="missing"')
            .replace('<constructor-arg>','<constructor-arg type="java.lang.String">'))
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        facts=self.facts(full['revision']);ids={f['id']:f for f in facts}
        targets={ids[f['target']]['signature'] for f in facts if f['relation']=='config_member'}
        self.assertIn('ext.Factory#ambiguous(java.lang.String)',targets)
        self.assertNotIn('ext.Factory#ambiguous(java.lang.Integer)',targets)
        resource.unlink()
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        actual=self.facts(delta['revision']);self.assertEqual(actual,self.facts(full['revision']))
        self.assertFalse(any(f['owner']['producer']=='spring-model-v1' and f['kind']=='external_symbol' for f in actual))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_module_nocode_catalogues_corruption_and_delta(self):
        import hashlib
        from java_module_signature_alignment import ModuleProof
        shutil.rmtree(self.project/'src')
        fixture=ModuleProof();fixture.setUp();self.addCleanup(fixture.doCleanups)
        fixture.reactor()
        for module in ('lib','mid','app'):shutil.copytree(fixture.root/module,self.project/module)
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'module NoCode model').hexdigest(),active_profiles_sha256=hashlib.sha256(b'module NoCode profiles').hexdigest(),modules=[
            dict(id=module,root=module,source_version='8',source_roots=[dict(path='.',scope='MAIN')],dependencies={'MAIN':parents,'TEST':parents})
            for module,parents in [('app',['lib','mid']),('mid',['lib']),('lib',[])]])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[resources.index]\njava_parallelism=1\n[discovery]\nmode="auto"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n')
        _,first=self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        cache=self.home/'cache/java-signatures.managed-v1/java-signatures-modules'
        self.assertTrue(list(cache.glob('*/payload/catalogue.bin')))
        before=self.facts(first['revision'])
        def navigate_auxiliary(facts):
            auxiliary=next(f for f in facts if f['kind']=='external_symbol'
                           and f['signature']=='p.Api#get()' and f['attributes'].get('source_origin'))
            producer=next(f for f in facts if f['kind']=='declaration' and f['signature']==auxiliary['signature'])
            self.assertNotEqual(auxiliary['owner']['world'],producer['owner']['world'])
            source,terminal=self.cli('source',auxiliary['id'],'--revision',first['revision'])
            self.assertEqual(terminal['publication'],'not_requested')
            self.assertEqual({row['path'] for row in source},{producer['source']})
            self.assertEqual({row['hash'] for row in source},{auxiliary['attributes']['source_origin']['hash']})
            self.assertIn('return value;', ''.join(row['text'] for row in source))
            self.assertEqual(source[0]['span']['start'],producer['span']['start'])
            self.assertEqual(source[-1]['span']['end'],producer['span']['end'])
            self.assertEqual(self.facts(first['revision']),before)
        navigate_auxiliary(before)
        captured=self.project/'lib/p/Api.java';original=captured.read_bytes()
        try:
            captured.write_bytes(original.replace(b'return value;',b'return null;'))
            navigate_auxiliary(before) # SOURCE reads the revision blob, not the changed workspace.
            auxiliary=next(f for f in before if f['kind']=='external_symbol'
                           and f['signature']=='p.Api#get()' and f['attributes'].get('source_origin'))
            _,mismatch=self.cli('source',auxiliary['id'],code=1)
            self.assertIn('SOURCE_HASH_MISMATCH',mismatch['message'])
        finally:
            captured.write_bytes(original)
        for file in cache.glob('*/payload/catalogue.bin'):file.write_bytes(b'corrupt source catalogue')
        _,rebuilt=self.cli('index','--full',timeout=150)
        self.assertEqual(before,self.facts(rebuilt['revision']))
        use=self.project/'app/p/Use.java';use.write_text(use.read_text().replace('"before"','"after"'))
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_generic_written_return_constraints_and_delta(self):
        from java_target_return_alignment import TargetReturns
        fixture=TargetReturns();fixture.setUp();self.addCleanup(fixture.doCleanups)
        context=fixture.fixture('String read(Holder h){java.util.List<String> values=h.getApi().values();String bad=h.getApi().number();return (h.getApi().make());}')
        shutil.rmtree(self.project/'src');(self.project/'src').mkdir()
        for unit in context['sources']:
            file=self.project/'src'/pathlib.Path(unit['path']).relative_to('app')
            file.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(unit['input'],file)
        config=self.project/'.code-ana/config.toml';config.write_text(config.read_text()+'\n[languages.java.lombok]\nmode="model"\n')
        _,first=self.cli('index','--full',timeout=150);self.assertEqual(first['publication'],'published')
        def targets(facts):
            rows={f['id']:f for f in facts}
            return {rows[f['target']]['signature']:f for f in facts if f['kind']=='call_target' and rows[f['parent']]['name'].startswith('h.getApi().')}
        selected=targets(self.facts(first['revision']))
        self.assertEqual(set(selected),{'Api#values()','Api#make()'})
        self.assertEqual(type_attributes(selected['Api#values()'])['recovered_return_type_model']['arguments'][0]['element'],'java.lang.String')
        self.assertEqual(type_attributes(selected['Api#make()'])['recovered_return_type_model']['element'],'java.lang.String')
        self.assertTrue(all(f['status']=='possible' and f['origin']=='source_recovery' for f in selected.values()))
        app=self.project/'src/App.java';app.write_text(app.read_text().replace('String read(Holder h)','Number read(Holder h)'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        self.assertEqual(type_attributes(targets(actual)['Api#make()'])['recovered_return_type_model']['element'],'java.lang.Number')
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_lombok_module_original_abi_configuration_and_delta(self):
        import hashlib
        from java_module_overlay_alignment import OverlayModules
        fixture=OverlayModules();fixture.setUp();self.addCleanup(fixture.doCleanups)
        fixture.plan('package p;@lombok.Getter class A {boolean n;}','package p;class Use {boolean read(A value){return value.n;}}')
        shutil.rmtree(self.project/'src')
        for module in ('app','client'):shutil.copytree(fixture.root/module,self.project/module)
        control=self.project/'lombok.config';control.write_text('config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'original Lombok ABI').hexdigest(),active_profiles_sha256=hashlib.sha256(b'profiles').hexdigest(),modules=[
            dict(id=module,root=module,source_version='8',source_roots=[dict(path='.',scope='MAIN')],dependencies={'MAIN':parents})
            for module,parents in [('client',['app']),('app',[])]])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[languages.java.lombok]\nmode="model"\n[resources.index]\njava_parallelism=2\n')
        _,first=self.cli('index','--full',timeout=150);self.assertEqual(first['publication'],'published')
        before=self.facts(first['revision']);self.assertTrue(list((self.home/'cache/java-signatures.managed-v1/java-signatures-modules').glob('*/payload/catalogue.bin')))
        self.assertTrue(any(f['kind']=='declaration' and f['signature']=='p.A#isN()' and f['origin']=='lombok_model' for f in before))
        control.write_text(control.read_text()+'lombok.getter.noIsPrefix=true\n')
        use=self.project/'client/p/Use.java';use.write_text(use.read_text().replace('value.n','value.getN()'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        getters={f['id'] for f in actual if f['signature']=='p.A#getN()'}
        targets=[f for f in actual if f['kind']=='call_target' and f['target'] in getters]
        self.assertTrue(targets);self.assertTrue(all(f['status']=='possible' and not f['declaration_resolved'] for f in targets))
        self.assertFalse(any(f['signature']=='p.A#isN()' for f in actual))
        app=self.project/'app/App.java';app.write_text(app.read_text().replace('@lombok.Getter ',''))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        self.assertFalse(any(f['signature']=='p.A#getN()' for f in actual))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_maven_mediated_classpath_constant_cache_and_delta(self):
        import hashlib
        shutil.rmtree(self.project/'src')
        artifacts=[]
        for value in (1,2):
            source=self.root/('external'+str(value))/'Constants.java';source.parent.mkdir()
            source.write_text('package ext;public class Constants {public static final int VALUE='+str(value)+';}')
            classes=source.parent/'classes';classes.mkdir()
            subprocess.run([str(JDK/'bin/javac'),'-proc:none','-d',str(classes),str(source)],check=True,capture_output=True)
            jar=source.parent/'constants.jar'
            subprocess.run([str(JDK/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True)
            shutil.rmtree(classes)
            artifacts.append(dict(path=str(jar),sha256=hashlib.sha256(jar.read_bytes()).hexdigest()))
        for path,text in {'lib/src/p/Lib.java':'package p;public class Lib {public static final int VALUE=ext.Constants.VALUE;}',
                          'app/src/p/Use.java':'package p;class Use {static final int VALUE=Lib.VALUE;}'}.items():
            file=self.project/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'mediated constants').hexdigest(),active_profiles_sha256=hashlib.sha256(b'profiles').hexdigest(),modules=[
            dict(id='lib',root='lib',source_version='8',source_roots=[dict(path='src',scope='MAIN')],classpath={'MAIN':[artifacts[0]]}),
            dict(id='app',root='app',source_version='8',source_roots=[dict(path='src',scope='MAIN')],classpath={'MAIN':[artifacts[1]]},dependencies={'MAIN':['lib']})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[resources.index]\njava_parallelism=2\n')
        _,first=self.cli('index','--full',timeout=150)
        self.assertEqual(first['publication'],'published')
        before=self.facts(first['revision'])
        values={f['signature']:f['attributes']['constant_value'] for f in before if f['signature'] in ('p.Lib#VALUE','p.Use#VALUE') and f['kind']=='declaration'}
        self.assertEqual(values,{'p.Lib#VALUE':1,'p.Use#VALUE':2})
        use=self.project/'app/src/p/Use.java';use.write_text(use.read_text().replace('VALUE=Lib.VALUE','VALUE=Lib.VALUE+1'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual)
        self.assertEqual(next(f['attributes']['constant_value'] for f in actual if f['kind']=='declaration' and f['signature']=='p.Use#VALUE'),3)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_parallel_module_nocode_corruption_and_delta(self):
        import hashlib
        from java_module_parallel_alignment import ParallelModules
        fixture=ParallelModules();fixture.setUp();self.addCleanup(fixture.doCleanups);fixture.reactor()
        shutil.rmtree(self.project/'src')
        for module in ('left','right','client'):shutil.copytree(fixture.root/module,self.project/module)
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'parallel NoCode model').hexdigest(),active_profiles_sha256=hashlib.sha256(b'profiles').hexdigest(),modules=[
            dict(id=module,root=module,source_version='8',source_roots=[dict(path='.',scope='MAIN')],dependencies={'MAIN':parents})
            for module,parents in [('client',['left','right']),('left',[]),('right',[])]])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[resources.index]\njava_parallelism=2\n')
        _,first=self.cli('index','--full',timeout=150);self.assertEqual(first['publication'],'published')
        before=self.facts(first['revision']);cache=self.home/'cache/java-signatures.managed-v1/java-signatures-modules'
        self.assertGreaterEqual(len(list(cache.glob('*/payload/catalogue.bin'))),3)
        for file in cache.glob('*/payload/catalogue.bin'):file.write_bytes(b'corrupt parallel catalogue')
        _,rebuilt=self.cli('index','--full',timeout=150);self.assertEqual(before,self.facts(rebuilt['revision']))
        left=self.project/'left/p/Left.java';left.write_text(left.read_text().replace('N=1','N=3'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertNotEqual(before,self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_legacy_zip64_jar_binding_numeric_constants_and_delta(self):
        import hashlib,struct,zipfile
        shutil.rmtree(self.project/'src');(self.project/'src').mkdir()
        source=self.root/'external-src/ext/Api.java';source.parent.mkdir(parents=True)
        source.write_text('package ext;public class Api { public static final double FRACTION=0.125; public static final double NAN=0.0/0.0; public static double value(){return FRACTION;} }')
        classes=self.root/'external-classes';classes.mkdir()
        subprocess.run([str(JDK/'bin/javac'),'-proc:none','-d',str(classes),str(source)],check=True,capture_output=True)
        jar=self.root/'legacy.jar';data=(classes/'ext/Api.class').read_bytes()
        with zipfile.ZipFile(jar,'w') as archive:
            info=zipfile.ZipInfo('ext/Api.class');info.extra=struct.pack('<HHQQ',1,16,len(data),len(data));archive.writestr(info,data)
        use=self.project/'src/Use.java';use.write_text('class Use {double read(){return ext.Api.value()+ext.Api.FRACTION;} }')
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'legacy ZIP model').hexdigest(),active_profiles_sha256=hashlib.sha256(b'legacy ZIP profiles').hexdigest(),modules=[dict(id='app',root='.',source_version='8',source_roots=[dict(path='src',scope='MAIN')],classpath={'MAIN':[dict(path=str(jar),sha256=hashlib.sha256(jar.read_bytes()).hexdigest())]})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[discovery]\nmode="auto"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n')
        _,first=self.cli('index','--full',timeout=150);self.assertEqual(first['publication'],'published')
        facts=self.facts(first['revision']);by_id={f['id']:f for f in facts}
        self.assertTrue(any(f['kind']=='call_target' and f['status']=='resolved' and by_id[f['target']]['signature']=='ext.Api#value()' for f in facts))
        external=next(f for f in facts if f['signature']=='ext.Api#value()' and f['kind']=='external_symbol')
        self.assertIn('binary_member',external['attributes'])
        use.write_text(use.read_text().replace('+ext.Api.FRACTION','+0.375'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_maven_publication_labels_share_physical_compiler_ownership(self):
        import hashlib
        from java_model_tests import Models, key
        shutil.rmtree(self.project/'src')
        fixture=Models();fixture.setUp();self.addCleanup(fixture.doCleanups)
        fixture.labeled_contexts()
        shutil.copytree(fixture.root/'physical',self.project/'physical')
        binary=self.root/'external';binary.mkdir()
        (binary/'External.java').write_text('package ext; public class External { public static String value(){return "jar";} }')
        classes=binary/'classes';classes.mkdir()
        subprocess.run([str(JDK/'bin/javac'),'-d',str(classes),str(binary/'External.java')],check=True,capture_output=True)
        jar=binary/'external.jar'
        subprocess.run([str(JDK/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True)
        bridge=self.project/'physical/bridge/p/Bridge.java'
        bridge.write_text(bridge.read_text().replace('return "a";', 'return "a"+ext.External.value();'))
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        artifact=dict(path=str(jar),sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'labels model').hexdigest(),active_profiles_sha256=hashlib.sha256(b'labels profiles').hexdigest(),modules=[
            dict(id='physical',root='physical',source_version='8',source_roots=[dict(path='src',scope='MAIN'),dict(path='bridge',scope='MAIN'),dict(path='tests',scope='TEST')],classpath={'MAIN':[artifact],'TEST':[artifact]})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        config=self.project/'.code-ana/config.toml'
        config.write_text('schema_version=1\nkind="project"\n[discovery]\nmode="auto"\n[scan]\nscopes=["MAIN","TEST"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[[modules]]\nid="physical"\nroot="physical"\nlanguage="java"\nsource_roots=[{path="src",scope="MAIN",label="api"},{path="bridge",scope="MAIN",label="bridge"},{path="tests",scope="TEST",label="tests"}]\n')
        _,first=self.cli('index','--full',timeout=150)
        old=self.facts(first['revision']);fixture.assert_labeled_facts(old)
        by_id={f['id']:f for f in old}
        self.assertTrue(any(f['kind']=='call_target' and f['status']=='resolved' and by_id[f['target']]['signature']=='ext.External#value()' for f in old))
        binary=next(f for f in old if f['kind']=='external_symbol' and f['signature']=='ext.External#value()')
        _,unavailable=self.cli('source',binary['id'],code=1)
        self.assertIn('SOURCE_UNAVAILABLE',unavailable['message'])
        bridge.write_text(bridge.read_text().replace('"a"','"b"'))
        records,delta=self.cli('index',timeout=150)
        self.assertEqual(records[0]['mode'],'delta')
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        config.write_text(config.read_text().replace('label="api"','label="public-api"'))
        _,renamed=self.cli('index',timeout=150)
        actual=self.facts(renamed['revision'])
        api=next(f for f in actual if f['kind']=='declaration' and f['signature']=='p.Api')
        self.assertEqual(api['attributes']['logical_key'],key('logical','public-api','p.Api'))
        self.assertEqual(api['owner']['world'],key('world','physical','MAIN'))
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(actual,self.facts(full['revision']))
        config.write_text(config.read_text().replace('source_roots=[','source_roots=[{path="src",scope="MAIN",label="conflict"},'))
        _,failure=self.cli('index',code=1,timeout=150)
        self.assertIn('AMBIGUOUS_SOURCE_OWNER',json.dumps(failure))
        self.assertEqual(self.cli('overview')[1]['revision'],full['revision'])
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_selected_maven_provider_classpath_proof_and_delta_through_daemon(self):
        import hashlib
        from java_maven_alignment import EXPORTER, NS
        shutil.rmtree(self.project/'src')
        parent = self.project/'pom.xml'
        parent.write_text(f'<project {NS}><modelVersion>4.0.0</modelVersion><groupId>fixture.proof</groupId>'
            '<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>'
            '<properties><maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target></properties>'
            '<modules><module>lib</module><module>use</module><module>bad</module></modules></project>')
        def module(name, dependencies=''):
            directory=self.project/name;directory.mkdir()
            (directory/'pom.xml').write_text(f'<project {NS}><modelVersion>4.0.0</modelVersion>'
                '<parent><groupId>fixture.proof</groupId><artifactId>parent</artifactId><version>1</version></parent>'
                '<artifactId>'+name+'</artifactId>'+dependencies+'<build><sourceDirectory>src</sourceDirectory></build></project>')
            (directory/'src').mkdir();return directory/'src'
        library=module('lib','<dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>'
            '<version>1.18.6</version><scope>provided</scope></dependency></dependencies>')/'Api.java'
        library.write_text('package p;import lombok.Getter;@Getter public class Api {String name;}')
        use=module('use','<dependencies><dependency><groupId>fixture.proof</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>')/'Use.java'
        use.write_text('package p;class Use {int read(Api api){return api.getName().length();}}')
        bad=module('bad')/'Broken.java';bad.write_text('class Broken {'+''.join('Missing'+str(i)+' x'+str(i)+';' for i in range(10020))+'}')
        (self.project/'lombok.config').write_text('config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')
        output=self.project/'.code-ana/inputs.json'
        metadata=EXPORTER.export(self.project,output,str(pathlib.Path.home()/'.sdkman/candidates/maven/current/bin/mvn'),
            modules=('use',),scopes=('MAIN',),offline=True)
        lib=next(m for m in metadata['modules'] if m['id']=='lib')
        self.assertFalse(lib['selected']);self.assertTrue(lib['resolved_classpath'])
        self.assertEqual(metadata['receipt']['prerequisite_classpath_rounds'],1)
        self.assertTrue(any('lombok' in jar['coordinate'] for jar in lib['classpath']['MAIN']))
        self.assertFalse(any('lombok' in jar['coordinate'] for m in metadata['modules'] if m['id']=='use' for jar in m['classpath']['MAIN']))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["MAIN"]\n'
            '[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n'
            '[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[languages.java.lombok]\nmode="model"\n')
        def check(facts,present):
            ids={f['id']:f for f in facts};calls=[f for f in facts if f['kind']=='call_target' and ids[f['target']]['signature']=='p.Api#getName()']
            self.assertEqual(len(calls),int(present))
            if present:
                self.assertEqual(calls[0]['status'],'possible');self.assertFalse(calls[0]['evidence_complete'])
                proof=calls[0]['attributes']['prerequisite_annotation_proof'];self.assertEqual(proof['context'],'lib@MAIN')
                self.assertEqual(proof['source_hash'],hashlib.sha256(library.read_bytes()).hexdigest())
                self.assertTrue(any(f['kind']=='diagnostic' and f['source']=='use/src/Use.java' for f in facts))
            self.assertFalse([f for f in facts if f['kind']=='declaration' and f['source'].startswith(('lib/','bad/'))])
            self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['source'].startswith('bad/')])
        _,first=self.cli('index','--full',timeout=180);self.assertEqual(first['publication'],'published')
        before=self.facts(first['revision']);check(before,True)
        use.write_text(use.read_text().replace('api.getName().length()','api.getName().length()+1'))
        _,delta=self.cli('index',timeout=180);check(self.facts(delta['revision']),True)
        _,full=self.cli('index','--full',timeout=180);self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        library.write_text(library.read_text().replace('@Getter ',''))
        _,removed=self.cli('index',timeout=180);check(self.facts(removed['revision']),False)
        _,full=self.cli('index','--full',timeout=180);self.assertEqual(self.facts(removed['revision']),self.facts(full['revision']))
        self.assertEqual(self.facts(first['revision']),before);self.assertFalse(list(self.project.rglob('*.class')))

    def test_selected_module_with_unrelated_broken_module_and_source_dependency(self):
        import hashlib
        from java_lombok_alignment import Lombok
        for relative, text in Lombok.files(self, 'package app; class App {int read(lib.Api api){return api.getValue().length();}}').items():
            p = self.project / 'app/src' / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
        dependency = self.project / 'lib/src/lib/Api.java'
        dependency.parent.mkdir(parents=True)
        dependency.write_text('package lib; @lombok.Getter public class Api {String value;}')
        bad = self.project / 'bad/src/Bad.java'
        bad.parent.mkdir(parents=True)
        bad.write_text('class Bad {' + ''.join(('Missing' + str(i) + ' v' + str(i) + ';' for i in range(10020))) + '}')
        (self.project / 'pom.xml').write_text('<project/>')
        sha = lambda b: hashlib.sha256(b).hexdigest()
        modules = []
        for name in ['app', 'lib', 'bad']:
            m = dict(id=name, root=name, source_version='8', selected=name == 'app', source_roots=[dict(path='src', scope='MAIN')])
            if name == 'app':
                m['dependencies'] = {'MAIN': ['lib']}
            modules.append(m)
        manifest = dict(schema_version=3, controls={'pom.xml': sha((self.project / 'pom.xml').read_bytes())}, effective_model_sha256=sha(b'model'), active_profiles_sha256=sha(b'profiles'), scopes=['MAIN'], modules=modules)
        (self.project / '.code-ana/inputs.json').write_text(json.dumps(manifest))
        (self.project / '.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["MAIN"]\n[languages.java]\nenabled=true\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[languages.java.lombok]\nmode="model"\n')
        _, terminal = self.cli('index', '--full', timeout=180)
        self.assertEqual(terminal['publication'], 'published')
        initial = self.facts(terminal['revision'])

        def check(facts, return_owner):
            ids = {f['id']: f for f in facts}
            targets = {ids[f['target']]['signature'] for f in facts if f['kind'] == 'call_target'}
            self.assertIn('lib.Api#getValue()', targets)
            self.assertIn(return_owner + '#length()', targets)
            self.assertFalse([f for f in facts if f['kind'] == 'declaration' and f['source'].startswith(('bad/', 'lib/'))])
            self.assertFalse([f for f in facts if f['kind'] == 'diagnostic' and f['source'].startswith('bad/')])
        check(initial, 'java.lang.String')
        dependency.write_text(dependency.read_text().replace('String value', 'CharSequence value'))
        _, delta = self.cli('index', timeout=180)
        changed = self.facts(delta['revision'])
        check(changed, 'java.lang.CharSequence')
        _, full = self.cli('index', '--full', timeout=180)
        self.assertEqual(sorted(changed, key=lambda f: f['id']), sorted(self.facts(full['revision']), key=lambda f: f['id']))
        dependency.write_text(dependency.read_text().replace('@lombok.Getter ', ''))
        _, removed = self.cli('index', timeout=180)
        facts = self.facts(removed['revision'])
        ids = {f['id']: f for f in facts}
        self.assertNotIn('lib.Api#getValue()', {ids[f['target']]['signature'] for f in facts if f['kind'] == 'call_target'})

    def test_maven_test_only_main_prerequisites_and_delta(self):
        import hashlib
        shutil.rmtree(self.project/'src')
        sources={'lib/src/p/Api.java':'package p;public class Api {public static final int VALUE=1;public static int read(){return VALUE;}}',
                 'app/src/p/App.java':'package p;public class App {public int read(){return Api.read();}}',
                 'app/test/p/Check.java':'package p;class Check {int test(){return new App().read()+Api.VALUE;}}'}
        for path,text in sources.items():
            file=self.project/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        metadata=dict(schema_version=3,scopes=['TEST'],controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'test only').hexdigest(),active_profiles_sha256=hashlib.sha256(b'profiles').hexdigest(),modules=[
            dict(id='lib',root='lib',selected=False,source_version='8',source_roots=[dict(path='src',scope='MAIN')]),
            dict(id='app',root='app',source_version='8',source_roots=[dict(path='src',scope='MAIN'),dict(path='test',scope='TEST')],dependencies={'MAIN':['lib'],'TEST':['lib']})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n[scan]\nscopes=["TEST"]\n[languages.java]\nenabled=true\n[languages.java.analysis]\nsource_version="8"\ntoolchain="java8"\n[languages.java.discovery]\nbuild_system="maven"\ninputs_manifest=".code-ana/inputs.json"\n[resources.index]\njava_parallelism=1\n')
        _,first=self.cli('index','--full',timeout=150);self.assertEqual(first['publication'],'published')
        facts=self.facts(first['revision']);by_id={f['id']:f for f in facts}
        self.assertTrue(any(f['kind']=='call_target' and f['status']=='resolved' and by_id[f['target']]['signature']=='p.App#read()' for f in facts))
        self.assertEqual({f['source'] for f in facts if f['kind']=='coverage'},{'app/test/p/Check.java'})
        self.assertFalse(any(f['source'].startswith(('app/src/','lib/src/')) for f in facts))
        hidden=next(f for f in facts if f['kind']=='external_symbol' and f['signature']=='p.App#read()')
        _,unavailable=self.cli('source',hidden['id'],code=1)
        self.assertTrue(any(code in unavailable['message'] for code in ('SOURCE_UNAVAILABLE','SOURCE_ORIGIN_MISMATCH')))
        file=self.project/'lib/src/p/Api.java';file.write_text(file.read_text().replace('VALUE=1','VALUE=2'))
        _,delta=self.cli('index',timeout=150);_,full=self.cli('index','--full',timeout=150)
        actual=self.facts(full['revision']);self.assertEqual(self.facts(delta['revision']),actual);self.assertNotEqual(facts,actual)
        _,again=self.cli('index',timeout=150);self.assertEqual(self.facts(again['revision']),actual)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_maven_scope_and_framework_xml(self):
        import hashlib
        shutil.rmtree(self.project/'src')
        sources={'base/src/p/Base.java':'package p; public class Base { public static String value(){return "x";} }',
                 'app/src/p/App.java':'package p; public class App { public String call(){ return Base.value(); } }',
                 'app/test/p/TestOnly.java':'package p; class TestOnly { void test(){ new App().call(); } }',
                 'app/target/generated/p/Generated.java':'package p; class Generated { String call(){return new App().call();} }'}
        for path,text in sources.items():
            p=self.project/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
        pom=self.project/'pom.xml';pom.write_text('<project/>')
        xml=self.project/'app/resources/beans.xml';xml.parent.mkdir(parents=True,exist_ok=True)
        xml.write_text('<beans xmlns="http://www.springframework.org/schema/beans" xmlns:sofa="http://sofastack.io/schema/sofa"><bean id="app" class="p.App"/><bean id="base" class="p.Base"><property name="other" ref="app"/></bean><sofa:reference id="api" interface="p.Base"/></beans>')
        metadata=dict(schema_version=3,controls={'pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},effective_model_sha256=hashlib.sha256(b'fixture effective model').hexdigest(),active_profiles_sha256=hashlib.sha256(b'fixture active profiles').hexdigest(),modules=[
            dict(id='base',root='base',source_version='8',source_roots=[dict(path='src',scope='MAIN')]),
            dict(id='app',root='app',source_version='8',source_roots=[dict(path='src',scope='MAIN'),dict(path='test',scope='TEST'),dict(path='target/generated',scope='MAIN',generated=True,generated_from={'app/src/p/App.java':hashlib.sha256((self.project/'app/src/p/App.java').read_bytes()).hexdigest()})],resource_roots=[dict(path='resources',scope='MAIN')],dependencies={'MAIN':['base'],'TEST':['base']})])
        (self.project/'.code-ana/inputs.json').write_text(json.dumps(metadata))
        (self.project/'.code-ana/config.toml').write_text('''schema_version=1
kind="project"
[discovery]
mode="auto"
[scan]
scopes=["MAIN","TEST"]
[languages.java]
enabled=true
[languages.java.analysis]
source_version="8"
toolchain="java8"
[languages.java.discovery]
build_system="maven"
inputs_manifest=".code-ana/inputs.json"
[languages.java.extensions.spring]
enabled=true
xml=true
[languages.java.extensions.sofa]
enabled=true
''')
        _,first=self.cli('index',timeout=150)
        facts=self.facts(first['revision'])
        self.assertTrue(any(f['relation']=='java.bean' for f in facts))
        self.assertTrue(any(f['relation']=='java.sofa' for f in facts))
        test_decls=[f for f in facts if f['name']=='TestOnly' and f['kind']=='declaration']
        self.assertEqual(len(test_decls),1)
        self.assertTrue(any(f['name']=='Generated' and f['kind']=='declaration' for f in facts))
        _,unchanged=self.cli('index',timeout=150)
        self.assertEqual(self.facts(first['revision']),self.facts(unchanged['revision']))
        xml.write_text(xml.read_text()+'<!-- no semantic change -->')
        _,comment=self.cli('index',timeout=150)
        # Byte hashes in core provenance must change; XML semantic facts remain identical.
        self.assertEqual([f for f in self.facts(first['revision']) if f['owner']['kind']=='resource'],
                         [f for f in self.facts(comment['revision']) if f['owner']['kind']=='resource'])
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('[languages.java.extensions.spring]\nenabled=true','[languages.java.extensions.spring]\nenabled=false'))
        _,disabled=self.cli('index',timeout=150)
        disabled_facts=self.facts(disabled['revision'])
        self.assertTrue(any(f['relation']=='java.sofa' for f in disabled_facts))
        self.assertFalse(any(f['owner']['producer']=='spring-model-v1' for f in disabled_facts))
        xml.unlink()
        _,delta=self.cli('index',timeout=150)
        _,full=self.cli('index','--full',timeout=150)
        self.assertEqual(self.facts(delta['revision']),self.facts(full['revision']))
        self.assertFalse(any(f['relation']=='java.bean' for f in self.facts(delta['revision'])))
    def test_cancel_and_crash_preserve_head(self):
        import signal
        good=self.cli('index',timeout=150)[1]['revision']
        # Long enough to observe the real javac child, with no product fault hooks.
        source=self.project/'src/p/App.java'
        source.write_text('package p; class App {' + ''.join('int m%d(){return %d;}'%(i,i) for i in range(5000)) + '}')
        def processes():
            rows=subprocess.check_output(['ps','-axo','pid=,ppid=,stat=,comm='],text=True)
            return [line.split(None,3) for line in rows.splitlines() if line.strip()]
        for action in ('cancel','cancel_again','crash'):
            with self.subTest(action=action):
                task=json.loads(self.cli('index','--full','--background',raw=True))['task_id']
                until=time.monotonic()+90
                owned=[]
                while time.monotonic()<until:
                    rows=processes()
                    workers=[int(pid) for pid,ppid,stat,comm in rows if int(ppid)==self.daemon.pid and 'codeana-java-worker' in comm]
                    children=[int(pid) for pid,ppid,stat,comm in rows if int(ppid) in workers and comm.endswith('/java')]
                    if workers and children:
                        owned=workers+children
                        break
                    time.sleep(.02)
                self.assertTrue(owned,'javac child was not observed: '+self.daemon_log())
                if action.startswith('cancel'):
                    self.cli('task','cancel',task,raw=True)
                else:
                    os.kill(workers[0],signal.SIGKILL)
                self.cli('task','watch',task,code=130 if action.startswith('cancel') else 1,timeout=30)
                self.assertEqual(self.cli('overview')[1]['revision'],good)
                until=time.monotonic()+5
                while time.monotonic()<until:
                    alive=[int(pid) for pid,ppid,stat,comm in processes() if int(pid) in owned and not stat.startswith('Z')]
                    if not alive:break
                    time.sleep(.05)
                self.assertFalse(alive,'owned processes leaked')

    def test_compiler_oom_preserves_head(self):
        good=self.cli('index',timeout=150)[1]['revision']
        source=self.project/'src/p/App.java'
        source.write_text('package p; class App {'+''.join('int m%d(){return %d;}'%(i,i) for i in range(5000))+'}')
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\njava_compiler_heap_bytes=8388608\n')
        records,failure=self.cli('index','--full',code=1,timeout=150)
        self.assertIn('OutOfMemoryError',json.dumps(records)+json.dumps(failure))
        self.assertEqual(self.cli('overview')[1]['revision'],good)
        self.assertFalse(list(self.project.rglob('*.class')))
        self.assertFalse(self.java_worker_pid())

    def test_scratch_disk_full_preserves_head(self):
        import signal
        good=self.cli('index',timeout=150)[1]['revision']
        self.stop()
        image=self.root/'scratch.sparseimage';mount=self.root/'scratch-volume';mount.mkdir()
        create=subprocess.run(['/usr/bin/hdiutil','create','-size','16m','-fs','HFS+','-volname','CodeanaScratchTest','-type','SPARSE',str(image)],capture_output=True,text=True)
        self.assertEqual(create.returncode,0,create.stderr)
        attach=subprocess.run(['/usr/bin/hdiutil','attach','-nobrowse','-mountpoint',str(mount),str(image)],capture_output=True,text=True)
        self.assertEqual(attach.returncode,0,attach.stderr)
        def close_volume():
            self.stop()
            subprocess.run(['/usr/bin/hdiutil','detach',str(mount)],capture_output=True,text=True,timeout=30)
        self.addCleanup(close_volume)
        self.log=open(self.root/'daemon.log','a')
        self.daemon=subprocess.Popen([str(BIN/'codeana-daemon'),'--home',str(self.home)],stdout=self.log,stderr=self.log,env=dict(os.environ,TMPDIR=str(mount)))
        until=time.monotonic()+10
        while time.monotonic()<until:
            status=subprocess.run([str(BIN/'codeana-cli'),'--home',str(self.home),'daemon','status'],capture_output=True)
            if status.returncode==0:break
            time.sleep(.03)
        else:self.fail('daemon restart failed: '+self.daemon_log())
        source=self.project/'src/p/App.java'
        source.write_text('package p; class App {'+''.join('int m%d(){return %d;}'%(i,i) for i in range(5000))+'}')
        task=json.loads(self.cli('index','--full','--background',raw=True))['task_id']
        child=None
        until=time.monotonic()+90
        while time.monotonic()<until:
            rows=[line.split(None,2) for line in subprocess.check_output(['ps','-axo','pid=,ppid=,comm='],text=True).splitlines()]
            workers=[int(pid) for pid,ppid,comm in rows if int(ppid)==self.daemon.pid and 'codeana-java-worker' in comm]
            children=[int(pid) for pid,ppid,comm in rows if int(ppid) in workers and comm.endswith('/java')]
            if children:child=children[0];break
            time.sleep(.02)
        self.assertIsNotNone(child,'javac child not observed: '+self.daemon_log())
        os.kill(child,signal.SIGSTOP)
        filler=mount/'fill.bin'
        with filler.open('wb') as stream:
            while True:
                try:stream.write(b'0'*1048576);stream.flush();os.fsync(stream.fileno())
                except OSError as error:
                    self.assertEqual(error.errno,28)
                    break
        os.kill(child,signal.SIGCONT)
        records,failure=self.cli('task','watch',task,code=1,timeout=90)
        self.assertTrue(any(code in json.dumps(records+[failure]) for code in ('COMPILER_DISK_FULL','COMPILER_FAILED')))
        self.assertEqual(self.cli('overview')[1]['revision'],good)
        filler.unlink()
        until=time.monotonic()+5
        while time.monotonic()<until:
            rows=subprocess.check_output(['ps','-axo','pid=,stat='],text=True)
            alive=[fields[1] for line in rows.splitlines() if (fields:=line.split()) and int(fields[0])==child and not fields[1].startswith('Z')]
            if not alive:break
            time.sleep(.05)
        self.assertFalse(alive,'javac child leaked after disk full')

    def test_memory_budget_preserves_head(self):
        good=self.cli('index',timeout=150)[1]['revision']
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\nworker_memory_bytes=8388608\n')
        _,failure=self.cli('index','--full',code=1,timeout=150)
        self.assertIn('WORKER_MEMORY_BUDGET',json.dumps(failure))
        self.assertEqual(self.cli('overview')[1]['revision'],good)

    def test_explicit_model_and_native_heap_budgets_preserve_head_and_reuse(self):
        good = self.cli('index', '--full', timeout=150)[1]['revision']
        config = self.project/'.code-ana/config.toml'
        original_config = config.read_text()
        source = self.project/'src/p'
        for i in range(80):
            (source/f'Extra{i}.java').write_text(f'package p; class Extra{i} {{ int x; int read(){{return x+{i};}} }}')
        config.write_text(original_config+'\n[resources.index]\njava_model_archive_bytes=65536\n')
        _, failed = self.cli('index', '--full', code=1, timeout=150)
        self.assertIn('RECORD_ARCHIVE_BUDGET', json.dumps(failed))
        self.assertIn('byte_limit=65536', json.dumps(failed))
        self.assertEqual(self.cli('overview')[1]['revision'], good)
        config.write_text(original_config+'\n[resources.index]\njava_model_archive_bytes=8388608\n'
                          'java_compiler_timeout_seconds=120\njava_native_heap_bytes=536870912\n'
                          'java_result_bytes=8589934592\njava_fact_limit=5000000\njava_scratch_bytes=34359738368\n')
        records, accepted = self.cli('index', '--full', timeout=150)
        self.assertEqual(accepted['publication'], 'published')
        self.assertTrue(records)
        self.assertTrue(all(record.get('ack_warning') is None for record in records), records)
        worker = self.java_worker_pid()
        self.assertIsNotNone(worker, records)
        _, unchanged = self.cli('index', timeout=150)
        self.assertEqual(self.java_worker_pid(), worker)
        self.assertEqual(self.facts(accepted['revision']), self.facts(unchanged['revision']))
        path = source/'Extra79.java'
        path.write_text(path.read_text().replace('x+79', 'x+80'))
        _, delta = self.cli('index', timeout=150)
        self.assertEqual(self.java_worker_pid(), worker)
        _, full = self.cli('index', '--full', timeout=150)
        self.assertEqual(self.facts(delta['revision']), self.facts(full['revision']))
        good = full['revision']
        config.write_text(config.read_text().replace('java_fact_limit=5000000', 'java_fact_limit=1'))
        _, failed = self.cli('index', '--full', code=1, timeout=150)
        self.assertTrue(any(reason in json.dumps(failed) for reason in ('count_limit=1','FACT_BUDGET')))
        self.assertEqual(self.cli('overview')[1]['revision'], good)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_scratch_budget_kills_owned_processes_preserves_head_and_cleans_files(self):
        import signal
        good = self.cli('index', '--full', timeout=150)[1]['revision']
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\njava_scratch_bytes=134217728\n')
        source = self.project/'src/p'
        # Hold a real compiler with one source unit; the test concerns task scratch,
        # not the latency of discovering/capturing one thousand independent files.
        (source/'ScratchHold.java').write_text('package p; class ScratchHold { int x; '
            + ''.join(f'int read{i}(){{return x+{i};}}' for i in range(2000)) + '}')
        submitted = subprocess.run([str(BIN/'codeana-cli'), '--home', str(self.home), '--project', str(self.project),
                                    'index', '--full', '--background'], capture_output=True, text=True, check=True, timeout=30)
        task = json.loads(submitted.stdout)['task_id']
        until = time.monotonic()+60
        child = None
        while time.monotonic()<until:
            worker = self.java_worker_pid()
            if worker:
                rows = subprocess.check_output(['/bin/ps', '-axo', 'pid=,ppid=,command='], text=True)
                children = [int(fields[0]) for line in rows.splitlines()
                            if len(fields:=line.split(None, 2))==3 and int(fields[1])==worker
                            and 'dev.codeana.compiler.CompilerBridge --session' in fields[2]]
                if children:
                    child = children[0]
                    os.kill(child, signal.SIGSTOP)
                    break
            time.sleep(.01)
        if child is None:
            status = subprocess.run([str(BIN/'codeana-cli'), '--home', str(self.home),
                '--project', str(self.project), 'task', 'status', task], capture_output=True, text=True, timeout=10)
            self.fail('Compiler session did not become observable: '+status.stdout+status.stderr+self.daemon_log())
        cwd = subprocess.run(['/usr/sbin/lsof', '-a', '-p', str(child), '-d', 'cwd', '-Fn'],
                             capture_output=True, text=True, check=True).stdout
        work = pathlib.Path(next(line[1:] for line in cwd.splitlines() if line.startswith('n')))
        scratch = work.parent.parent
        self.assertTrue(scratch.name.startswith('codeana-java-'), str(scratch))
        with (scratch/'budget-overflow.bin').open('wb') as file:
            file.truncate(134217729)
        records, failed = self.cli('task', 'watch', task, code=1, timeout=90)
        self.assertIn('JAVA_SCRATCH_BUDGET', json.dumps(records+[failed]))
        self.assertEqual(self.cli('overview')[1]['revision'], good)
        self.assertFalse(scratch.exists(), str(scratch))
        rows = subprocess.check_output(['/bin/ps', '-axo', 'pid=,stat='], text=True)
        live = [line for line in rows.splitlines() if (fields:=line.split()) and int(fields[0])==child and not fields[1].startswith('Z')]
        self.assertFalse(live, str(live))

    def test_explicit_large_budget_crosses_default_fact_and_result_limits(self):
        import java_capacity
        good = self.cli('index', '--full', timeout=150)[1]['revision']
        self.stop()
        user = self.home/'config.toml'
        user.write_text(user.read_text().replace('max_worker_memory_bytes=1073741824', 'max_worker_memory_bytes=1610612736'))
        self.log = open(self.root/'daemon.log', 'a')
        self.daemon = subprocess.Popen([str(BIN/'codeana-daemon'), '--home', str(self.home)], stdout=self.log, stderr=self.log)
        until = time.monotonic()+10
        while time.monotonic()<until:
            status = subprocess.run([str(BIN/'codeana-cli'), '--home', str(self.home), 'daemon', 'status'], capture_output=True, timeout=3)
            if status.returncode==0: break
            time.sleep(.03)
        else: self.fail(self.daemon_log())
        for i in range(1000):
            (self.project/f'src/p/Large{i}.java').write_text(f'package p; class Large{i} {{ '+
                ''.join(f'int m{j}(){{int x={i+j}; return x+x;}}' for j in range(18))+' }')
        config = self.project/'.code-ana/config.toml'
        base = config.read_text()
        # Raise only model limits first; default fact/result budgets must still reject.
        common = '\n[resources.index]\nworker_memory_bytes=1610612736\njava_compiler_heap_bytes=268435456\njava_parallelism=1\njava_model_record_limit=1000000\njava_model_archive_bytes=536870912\njava_native_heap_bytes=536870912\n'
        config.write_text(base+common)
        _, failed = self.cli('index', '--full', '--deadline-seconds', '300', code=1, timeout=330)
        self.assertTrue(any(reason in json.dumps(failed) for reason in ('FACT_BUDGET', 'RECORD_ARCHIVE_BUDGET', 'OUTPUT_BYTE_BUDGET')),
                        json.dumps(failed))
        self.assertEqual(self.cli('overview')[1]['revision'], good)
        config.write_text(base+common+'java_result_bytes=4294967296\njava_fact_limit=5000000\njava_scratch_bytes=17179869184\n')
        with java_capacity.Rss(self.daemon.pid) as usage:
            _, accepted = self.cli('index', '--full', '--deadline-seconds', '300', timeout=330)
        self.assertEqual(accepted['publication'], 'published')
        connection = sqlite3.connect(self.home/'revisions'/accepted['revision']/'facts.db')
        count, body_bytes = connection.execute('SELECT COUNT(*),SUM(length(CAST(body AS BLOB))) FROM facts').fetchone()
        connection.close()
        self.assertGreater(count, 100000)
        self.assertGreater(body_bytes, 64*1024*1024)
        print('large budget: '+json.dumps(dict(facts=count, fact_body_bytes=body_bytes, rss=usage.receipt())), flush=True)
        self.assertLessEqual(usage.receipt()['process_tree_peak_bytes'], 2*1024*1024*1024)
        before = java_capacity.graph(self, accepted['revision'])
        _, unchanged = self.cli('index', '--deadline-seconds', '300', timeout=330)
        self.assertEqual(before, java_capacity.graph(self, unchanged['revision']))
        path = self.project/'src/p/Large999.java'
        path.write_text(path.read_text().replace('int x=999;', 'int x=998;'))
        _, delta = self.cli('index', '--deadline-seconds', '300', timeout=330)
        _, full = self.cli('index', '--full', '--deadline-seconds', '300', timeout=330)
        self.assertEqual(java_capacity.graph(self, delta['revision']), java_capacity.graph(self, full['revision']))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_wrong_jdk(self):
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('source_version = "8"','source_version = "17"'))
        _,terminal=self.cli('index',code=1,timeout=150)
        self.assertIn('SOURCE_VERSION_UNSUPPORTED',json.dumps(terminal))
if __name__=='__main__': unittest.main(verbosity=2)
