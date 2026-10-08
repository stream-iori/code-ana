#!/usr/bin/env python3
"""Native C ABI checks for the Anatomist-backed adapter, independent of legacy Java APIs."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'lang-java-adapotr/target'
JDK = Path(os.environ.get('CODEANA_JAVA8_HOME', Path.home() / '.sdkman/candidates/java/8.0.492-zulu'))

class Native(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='codeana-anatomist-native-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'input.java'
        self.source.write_text('class A { String text="😀"; void call(){ target(1); } void target(int x){} }')
        unit = dict(path='src/A.java', input=str(self.source), hash=hashlib.sha256(self.source.read_bytes()).hexdigest(), module='.', world='main', scope='MAIN', publish=True)
        self.context = dict(context='.@MAIN', module='.', world='main', scope='MAIN', digest=unit['hash'], sources=[unit], classpath=[], resources=[], dependency_modules=[], lombok='off')
        self.request = dict(version=8, full=True, facts=True, java_home=str(JDK), scratch=str(self.root), contexts=[self.context], compiler_timeout_seconds=30)
    def probe(self, *args, success=True):
        path = self.root / 'request.json'
        path.write_text(json.dumps(self.request))
        result = subprocess.run([str(ROOT/'target/debug/codeana-java-worker'), str(TARGET/'native/libcodeana_java.dylib'), '--probe', str(path), *args], capture_output=True, text=True, timeout=60)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stderr
    def test_native_projection_and_task_cleanup(self):
        first = self.probe()
        self.assertEqual(first['version'], 8)
        facts = first['facts']
        target = next(f for f in facts if f['signature']=='A#target(int)')
        self.assertTrue(any(f['kind']=='call_target' and f['target']==target['id'] and f['status']=='resolved' for f in facts))
        self.assertTrue(any(f['relation']=='java.syntax' for f in facts))
        self.assertTrue(any(f['relation']=='java.binding' and f['attributes']['schema_version']==4 for f in facts))
        self.assertEqual(facts, self.probe()['facts'])
        self.assertFalse(list(self.root.glob('anatomist-task-*')))
        self.assertFalse(list(self.root.rglob('*.class')))
    def test_java8_bytecode_and_manifest(self):
        with zipfile.ZipFile(TARGET/'compiler-bridge.jar') as jar:
            for name in jar.namelist():
                if name.endswith('.class'):
                    self.assertEqual(struct.unpack('>H',jar.read(name)[6:8])[0],52,name)
        manifest=json.loads((TARGET/'native/manifest.json').read_text())
        self.assertEqual(manifest['analysis_request'],8)
        for name,digest in manifest['files'].items():
            self.assertEqual(hashlib.sha256((TARGET/name).read_bytes()).hexdigest(),digest,name)
    def test_rejects_hash_and_old_request(self):
        self.source.write_text('class Changed {}')
        self.assertIn('CAPTURE_HASH_MISMATCH', self.probe(success=False))
        self.request['version']=6
        self.assertIn('ABI_REQUEST_VERSION',self.probe(success=False))
    def test_resource_world_is_retained_by_noop_delta(self):
        resource=self.root/'beans.xml'
        resource.write_text('<beans><bean id="a" class="A"/></beans>')
        self.context.update(spring=True,spring_xml=True,resources=[dict(input=str(resource),unit=dict(path='resources/beans.xml',hash=hashlib.sha256(resource.read_bytes()).hexdigest(),module='.',world='resource-world',scope='RESOURCE'))])
        initial=self.probe()['facts']
        self.assertTrue(any(f['relation']=='java.bean' for f in initial))
        baseline=self.root/'baseline.caf'
        with baseline.open('wb') as out:
            out.write(struct.pack('>I',0x43414631))
            for fact in initial:
                row=json.dumps(fact,separators=(',',':'),ensure_ascii=False).encode()
                out.write(struct.pack('>I',len(row)))
                out.write(row)
        self.request.update(full=False,baseline_archive=dict(path=str(baseline),sha256=hashlib.sha256(baseline.read_bytes()).hexdigest(),bytes=baseline.stat().st_size,count=len(initial)))
        delta=self.probe()
        self.assertEqual(delta['facts'],[])
        self.assertEqual(delta['replacements'],[])

    def test_xml_configuration_preserves_nested_references(self):
        resource=self.root/'beans.xml'
        resource.write_text('<beans><bean id="a" class="A"><property name="items"><list><ref bean="a"/><value>😀</value></list></property></bean></beans>')
        self.context.update(spring=True,spring_xml=True,resources=[dict(input=str(resource),unit=dict(path='resources/beans.xml',hash=hashlib.sha256(resource.read_bytes()).hexdigest(),module='.',world='resource-world',scope='RESOURCE'))])
        facts=self.probe()['facts']; by={f['id']:f for f in facts}
        bean=next(f for f in facts if f['relation']=='java.bean')
        model=bean['attributes']['xml_configuration_model']
        self.assertEqual(model['construction']['name'],'a')
        self.assertEqual([n['metadata']['xmlKind'] for n in model['nodes']],['property','list','ref','value'])
        links=[f for f in facts if f['relation']=='config']
        self.assertEqual(len(links),1)
        self.assertEqual(links[0]['target'],bean['id'])
        current=by[links[0]['parent']]; kinds=[]
        while current['id']!=bean['id']:
            kinds.append(current['attributes']['kind']);current=by[current['parent']]
        self.assertEqual(kinds,['bean_ref','list','property'])
        self.assertEqual({by[f['target']]['signature'] for f in facts if f['relation']=='config_owner'},{'A'})

    def test_unresolved_xml_symbols_do_not_claim_compiler_evidence(self):
        resource=self.root/'beans.xml'
        resource.write_text('<beans><bean id="missing" class="ext.Missing" init-method="start"/></beans>')
        self.context.update(spring=True,spring_xml=True,resources=[dict(input=str(resource),unit=dict(path='resources/beans.xml',hash=hashlib.sha256(resource.read_bytes()).hexdigest(),module='.',world='resources',scope='RESOURCE'))])
        facts=self.probe()['facts'];by={f['id']:f for f in facts}
        requests=[f for f in facts if f['relation'] in ('config_owner','config_type','config_member')]
        self.assertTrue(requests)
        for request in requests:
            symbol=by[request['target']]
            self.assertEqual(symbol['kind'],'external_symbol')
            self.assertEqual(symbol['origin'],'framework_model')
            self.assertEqual(symbol['status'],'possible')
            self.assertFalse(symbol['evidence_complete'])
            self.assertFalse(symbol['declaration_resolved'])
            self.assertFalse(symbol['attributes']['source_available'])
            self.assertEqual(symbol['source'],'')
            self.assertIsNone(symbol['span'])
        self.assertTrue(any(f['attributes'].get('resolutionStatus')=='unresolved' for f in facts if f['relation']=='config_member'))

    def test_nested_bean_and_sofa_children_are_queryable(self):
        resource=self.root/'beans.xml'
        resource.write_text('<beans xmlns:sofa="http://schema.alipay.com/sofa/schema/service"><!--中文😀--><bean id="outer" class="A"><property name="value"><bean id="inner" class="A"/></property></bean><sofa:consumer id="consumer"><sofa:channel value="topic"><sofa:event value="message"/></sofa:channel></sofa:consumer></beans>')
        self.context.update(spring=True,spring_xml=True,sofa=True,resources=[dict(input=str(resource),unit=dict(path='resources/beans.xml',hash=hashlib.sha256(resource.read_bytes()).hexdigest(),module='.',world='resources',scope='RESOURCE'))])
        facts=self.probe()['facts'];by={f['id']:f for f in facts}
        links=[f for f in facts if f['relation']=='config_nested_bean']
        self.assertEqual(len(links),1)
        self.assertEqual(by[links[0]['target']]['name'],'inner')
        self.assertEqual(by[links[0]['parent']]['attributes']['xmlKind'],'bean')
        prop=next(f for f in facts if f['relation']=='java.spring.xml' and f['attributes'].get('xmlKind')=='property')
        self.assertTrue(resource.read_bytes()[prop['span']['start']:prop['span']['end']].startswith(b'<property'))
        event=next(f for f in facts if f['relation']=='java.sofa' and f['attributes'].get('element')=='event')
        self.assertEqual(event['attributes']['channel'],'topic')
        self.assertEqual(event['attributes']['attributes']['value'],'message')
        self.assertEqual(event['status'],'possible')
        self.assertFalse(event['evidence_complete'])
        self.assertFalse(event['declaration_resolved'])

    def test_signature_cache_relocates_between_processes_and_rejects_corruption(self):
        library=self.root/'Library.java';library.write_text('package p; public class Library {public String value(){return "ok";}}')
        self.source.write_text('class A {String call(){return new p.Library().value();}}')
        main=self.context['sources'][0];main['hash']=hashlib.sha256(self.source.read_bytes()).hexdigest()
        lib=dict(path='lib/p/Library.java',input=str(library),hash=hashlib.sha256(library.read_bytes()).hexdigest(),module='lib',world='lib-main',scope='MAIN',publish=False)
        self.context.update(module='app',context='app@MAIN',sources=[main,lib],dependency_modules=['lib'])
        main['module']='app'
        prerequisite=dict(context='lib@MAIN',module='lib',world='lib-main',scope='MAIN',digest=lib['hash'],sources=[lib],classpath=[],resources=[],dependency_modules=[],lombok='off',prerequisite=True)
        cache=self.root/'cache';cache.mkdir()
        self.request.update(cache_root=str(cache),signature_cache=True,contexts=[prerequisite,self.context])
        first=self.probe()['facts']
        catalogues=list(cache.rglob('declarations.bin'));self.assertEqual(len(catalogues),1)
        self.assertIn(b'/__codeana_captured__/lib/p/Library.java',catalogues[0].read_bytes())
        self.assertNotIn(b'anatomist-task-',catalogues[0].read_bytes())
        before={str(p):p.read_bytes() for p in cache.rglob('*') if p.is_file() and p.name in ('declarations.bin','manifest.json','manifest.sha256','signatures.jar')}
        self.assertEqual(first,self.probe()['facts'])
        self.assertEqual(before,{str(p):p.read_bytes() for p in cache.rglob('*') if str(p) in before})
        catalogues[0].write_bytes(b'corrupt')
        self.assertEqual(first,self.probe()['facts'])
        self.assertNotEqual(catalogues[0].read_bytes(),b'corrupt')
    def test_budget_and_cancel(self):
        self.context['model_record_limit']=1
        self.assertIn('JAVA_MODEL_RECORD_LIMIT',self.probe(success=False))
        self.context.pop('model_record_limit')
        self.assertIn('CANCELLED',self.probe('--cancel-after-ms','1',success=False))
        self.assertFalse(list(self.root.glob('anatomist-task-*')))
    def test_processor_trap_is_never_executed(self):
        directory=self.root/'processor';directory.mkdir()
        marker=self.root/'processor-ran'
        (directory/'Trap.java').write_text('import javax.annotation.processing.*; import javax.lang.model.*; import javax.lang.model.element.*; import java.util.*; @SupportedAnnotationTypes("*") @SupportedSourceVersion(SourceVersion.RELEASE_8) public class Trap extends AbstractProcessor { public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) { try { new java.io.File('+json.dumps(str(marker))+').createNewFile(); } catch(Exception e) { throw new RuntimeException(e); } return false; } }')
        subprocess.run([str(JDK/'bin/javac'),'-d',str(directory),str(directory/'Trap.java')],check=True,capture_output=True)
        service=directory/'META-INF/services/javax.annotation.processing.Processor';service.parent.mkdir(parents=True);service.write_text('Trap\n')
        trap=self.root/'trap.jar'
        subprocess.run([str(JDK/'bin/jar'),'cf',str(trap),'-C',str(directory),'.'],check=True,capture_output=True)
        self.context['classpath']=[str(trap)]
        self.probe()
        self.assertFalse(marker.exists())

if __name__=='__main__':
    unittest.main(verbosity=2)
