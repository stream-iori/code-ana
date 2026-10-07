#!/usr/bin/env python3
"""Spring XML configuration syntax and relations over captured inputs."""
import hashlib
import json
import subprocess
import shutil
import unittest
import java_model_tests as models

SOURCE = '''class Api { void setOther(Api other){} void start(){} void stop(){} }
class Holder { Holder(Api api){} void setValues(java.util.Map<String,Object> values){}
  void setItems(java.util.List<Object> values){} void setNested(Api nested){} }
class Product {} class Maker { static Product create(){return new Product();}
  Product instance(){return new Product();} }
'''


class Xml(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def captured(self, files, enabled=True, source=SOURCE):
        context = self.context('app', {'Api.java': source}, spring=enabled, spring_xml=True)
        resources = []
        for index, (path, content) in enumerate(files.items()):
            captured = self.root/'xml'/str(index)
            captured.parent.mkdir(exist_ok=True)
            captured.write_text(content, encoding='utf8')
            resources.append(dict(unit=dict(path=path,world='resources',hash=hashlib.sha256(captured.read_bytes()).hexdigest()),input=str(captured)))
        context['resources'] = resources
        context['digest'] = hashlib.sha256((context['digest']+json.dumps(files,sort_keys=True)).encode()).hexdigest()
        return context

    def facts(self, files, source=SOURCE):
        facts = self.analyze([self.captured(files,source=source)])['facts']
        self.merged([],dict(facts=facts,replacements=[]))
        self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('code')=='EXTENSION_CALLBACK_FAILED'])
        return facts

    def test_resource_only_context_keeps_auxiliary_construction_and_lifecycle(self):
        context=self.captured({'beans.xml': '<beans><bean id="api" class="Api"/>'
            '<bean id="holder" class="Holder" init-method="start" destroy-method="stop">'
            '<constructor-arg ref="api"/><property name="name" value="one"/></bean></beans>'},
            source='class Api {} class Holder {public Holder(Api api){} '
                   'public void setName(String name){} public void start(){} public void stop(){}}')
        context['sources']=[dict(row,publish=False) for row in context['sources']]
        context['digest']=models.sha(json.dumps(context['sources'],sort_keys=True).encode())
        result=self.analyze([context]);facts=result['facts']
        self.merged([],dict(facts=facts,replacements=[]))
        by={fact['id']:fact for fact in facts}
        members={by[fact['target']]['signature'] for fact in facts
                 if fact['relation']=='config_member'}
        self.assertTrue({'Holder#<init>(Api)','Holder#setName(java.lang.String)',
                         'Holder#start()','Holder#stop()'} <= members,members)
        self.assertFalse([fact for fact in facts if fact['kind']=='call_site'])
        self.assertFalse([fact for fact in facts if fact['kind']=='declaration'])

    def test_comment_initial_greater_than_retains_original_bytes_and_all_nodes(self):
        xml = ('<beans><!-->原字节😀<--><bean id="api" class="Api"/>'
               '<!-- --><bean id="holder" class="Holder">'
               '<constructor-arg ref="api"/></bean></beans>')
        context = self.captured({'beans.xml':xml})
        full = self.analyze([context],cache=True)['facts']
        beans = [f for f in full if f['relation']=='java.bean']
        self.assertCountEqual([f['name'] for f in beans],['api','holder'])
        self.assertFalse(any('XML_LOCATION_MISMATCH' in f['attributes'].get('gaps',[]) for f in full))
        for fact in beans:
            self.assertTrue(xml.encode()[fact['span']['start']:fact['span']['end']].startswith(b'<bean '))
        changed = self.captured({'beans.xml':xml.replace('原字节😀','修改😀')})
        self.assertEqual(self.merged(full,self.analyze([changed],full,cache=True)),
                         sorted(self.analyze([changed],cache=True)['facts'],key=lambda f:f['id']))

    def test_auxiliary_source_constructor_and_members_survive_header_change(self):
        xml = '<beans><bean id="shared" class="p.Shared" init-method="start"><property name="name" value="one"/></bean></beans>'
        source = 'package p; public class Shared {public Shared(){} public void setName(String name){} public void start(){}}'

        def fixture(text):
            library = self.context('lib', {'p/Shared.java': text})
            app = self.captured({'beans.xml': xml})
            app['sources'] = [dict(unit, publish=False) for unit in library['sources']] + app['sources']
            app['digest'] = models.sha(json.dumps(app['sources'], sort_keys=True).encode())
            return app

        base = self.analyze([fixture(source)], cache=True)
        by = {fact['id']: fact for fact in base['facts']}
        members = {by[fact['target']]['signature'] for fact in base['facts'] if fact['relation'] == 'config_member'}
        self.assertEqual(members, {'p.Shared#<init>()', 'p.Shared#setName(java.lang.String)', 'p.Shared#start()'})
        for changed in (source.replace('Shared(){}', 'Shared(int n){}'), source.replace('setName(String name)', 'setOther(String name)')):
            context = fixture(changed)
            delta = self.analyze([context], base['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            by = {fact['id']: fact for fact in full['facts']}
            members = {by[fact['target']]['signature'] for fact in full['facts'] if fact['relation'] == 'config_member'}
            self.assertNotIn('p.Shared#<init>()' if 'Shared(int n)' in changed else 'p.Shared#setName(java.lang.String)', members)

    def test_module_nocode_resource_identical_edit_disable_and_delete_owners(self):
        xml = ('<beans xmlns:sofa="http://sofastack.io/schema/sofa">'
               '<bean id="shared" class="Shared"/>'
               '<sofa:reference id="api" interface="Shared"/></beans>')

        def contexts(content, spring=True):
            library = self.context('lib', {'Shared.java': 'public class Shared {}'})
            app = self.captured({} if content is None else {'beans.xml': content},
                                enabled=spring, source='class Anchor extends Shared {}')
            app['sofa'] = True
            app['sources'] = [dict(row, publish=False) for row in library['sources']] + app['sources']
            app['digest'] = models.sha((app['digest'] + str(spring)).encode())
            for resource in app['resources']:
                resource['unit']['world'] = app['world']
            return [app, library]

        first = self.analyze(contexts(xml), cache=True)
        self.assertGreater(first['module_signature_report']['module_archives_used'], 0)
        old = first['facts']
        self.assertEqual(self.analyze(contexts(xml), old, cache=True)['replacements'], [])
        # Changing captured bytes requires owner replacement even with identical XML facts.
        for content, spring in [(xml + '<!-- no semantic change -->', True),
                                (xml + '<!-- no semantic change -->', False), (None, False)]:
            with self.subTest(content=content, spring=spring):
                plan = contexts(content, spring)
                delta = self.analyze(plan, old, cache=True)
                self.assertGreater(delta['reduced_contexts'], 0)
                resource_owners = {json.dumps(f['owner'], sort_keys=True)
                                   for f in old if f['owner']['kind'] == 'resource'}
                replacements = {json.dumps(o, sort_keys=True) for o in delta['replacements']}
                self.assertTrue(resource_owners)
                self.assertTrue(resource_owners <= replacements)
                current = self.merged(old, delta)
                self.assertEqual(current, sorted(self.analyze(plan)['facts'], key=lambda f: f['id']))
                if not spring:
                    self.assertFalse(any(f['owner']['producer'] == 'spring-model-v1' for f in current))
                old = current
        self.assertFalse(any(f['owner']['kind'] == 'resource' for f in old))

    def binary_context(self, extra_files=None):
        binary=self.root/'binary';binary.mkdir(exist_ok=True)
        marker=self.root/'binary-initializer-ran'
        files={
            'Base.java':'package ext; public class Base { public void start(){} public void stop(){} public void setOther(Product other){} }',
            'Product.java':'package ext; public class Product extends Base { public Product(){} }',
            'Factory.java':'package ext; public class Factory { static { try { new java.io.File('+json.dumps(str(marker))+').createNewFile(); } catch(Exception e){throw new RuntimeException(e);} } public Factory(){} public static Product make(){return new Product();} public Product instance(){return new Product();} public static Product ambiguous(String s){return null;} public static Product ambiguous(Integer i){return null;} }'}
        files.update(extra_files or {})
        for path,text in files.items():(binary/path).write_text(text)
        classes=binary/'classes';classes.mkdir(exist_ok=True)
        subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-d',str(classes)]+[str(binary/p) for p in files],check=True,capture_output=True)
        jar=binary/'external.jar'
        subprocess.run([str(models.JDK8/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True)
        shutil.rmtree(classes)
        context=self.captured({'beans.xml': '<beans><bean id="product" class="ext.Product" init-method="start" destroy-method="stop"><property name="other" ref="product"/></bean>'
            '<bean id="factory" class="ext.Factory"/><bean id="static" class="ext.Factory" factory-method="make"/>'
            '<bean id="instance" factory-bean="factory" factory-method="instance"/>'
            '<bean id="ambiguous" class="ext.Factory" factory-method="ambiguous"><constructor-arg><null/></constructor-arg></bean>'
            '<bean id="missing" class="ext.Missing"/></beans>'},source='class Anchor {}')
        context['classpath']=[str(jar)]
        context['digest']=models.sha((context['digest']+models.sha(jar.read_bytes())).encode())
        return context
    def assert_binary_xml(self,facts):
        ids={f['id']:f for f in facts}
        beans={f['name']:f for f in facts if f['relation']=='java.bean'}
        for name in ('product','static','instance'):
            self.assertEqual(beans[name]['attributes']['type'],'ext.Product')
            self.assertEqual(ids[beans[name]['parent']]['signature'],'ext.Product')
        self.assertEqual(beans['ambiguous']['attributes']['type'],'ext.Product')
        targets={ids[f['target']]['signature'] for f in facts if f['relation']=='config_member'}
        self.assertTrue({'ext.Product#<init>()','ext.Base#start()','ext.Base#stop()',
            'ext.Base#setOther(ext.Product)','ext.Factory#make()','ext.Factory#instance()'}.issubset(targets))
        external=[f for f in facts if f['kind']=='external_symbol' and f['origin']=='spring-model-v1']
        self.assertTrue(external)
        self.assertTrue(all(f['source']=='' and f['span'] is None and f['status']=='possible'
            and not f['evidence_complete'] and not f['declaration_resolved']
            and f['attributes']['source_available'] is False for f in external))
        self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('code')=='EXTENSION_CALLBACK_FAILED'])
        self.assertFalse((self.root/'binary-initializer-ran').exists())
    def test_binary_only_factory_constructor_inherited_members_and_delta(self):
        context=self.binary_context()
        old=self.analyze([context])['facts'];self.assert_binary_xml(old)
        self.assertEqual(old,self.analyze([context],compact=True)['facts'])
        context['resources'][0]['unit']['language']='xml'
        self.assertEqual(old,self.analyze([context],compact=True)['facts'])
        context['resources'][0]['unit']['language']='java'
        with self.assertRaisesRegex(AssertionError,'JAVA_RESOURCE_INDEX_IDENTITY'):
            self.analyze([context],compact=True)
        context['resources'][0]['unit'].pop('language')
        unchanged=self.analyze([context],old);self.assertEqual(unchanged['facts'],[])
        captured=self.root/'xml/0'
        captured.write_text(captured.read_text().replace('init-method="start"','init-method="missing"'))
        context['resources'][0]['unit']['hash']=models.sha(captured.read_bytes())
        context['digest']=models.sha((context['digest']+'changed').encode())
        delta=self.analyze([context],old)
        full=self.analyze([context])
        self.assertEqual(self.merged(old,delta),sorted(full['facts'],key=lambda f:f['id']))
        context['resources']=[];context['digest']=models.sha(b'deleted xml')
        delta=self.analyze([context],full['facts'])
        current=self.merged(full['facts'],delta)
        self.assertEqual(current,sorted(self.analyze([context])['facts'],key=lambda f:f['id']))
        self.assertFalse([f for f in current if f['origin']=='spring-model-v1' and f['kind']=='external_symbol'])

    def test_complete_binary_members_query_budget_and_source_shadowing_are_conservative(self):
        for count in (2050,2000):
            with self.subTest(method_count=count):
                factory='package ext; public class Factory { '+''.join('public void unused%d(){}'%i for i in range(count))+' public static Product make(){return null;} }'
                context=self.binary_context({'Factory.java':factory})
                resource=self.root/'xml/0'
                xml='<beans>'+''.join('<bean id="product%d" class="ext.Factory" factory-method="make"/>'%i for i in range(80))+'</beans>'
                resource.write_text(xml)
                context['resources'][0]['unit']['hash']=models.sha(resource.read_bytes())
                context['digest']=models.sha((context['digest']+str(count)).encode())
                facts=self.analyze([context])['facts']
                self.assertFalse([f for f in facts if f['attributes'].get('code')=='EXTENSION_CALLBACK_FAILED'])
                beans=[f for f in facts if f['relation']=='java.bean']
                self.assertEqual(len(beans),80)
                self.assertTrue(any(f['kind']=='coverage' and f['status']=='partial' and f['source']=='beans.xml' for f in facts))
                self.assertTrue(any(f['attributes']['type']=='ext.Product' for f in beans))
                self.assertTrue(any(f['attributes']['type']=='' for f in beans))
                self.assertTrue(any(f['relation']=='java.spring.xml' and f['attributes'].get('lookup_complete') is False for f in facts))
        context=self.binary_context()
        shadow=self.context('app',{'Api.java':'package ext; class Product { void local(){} }'})
        context['sources']=shadow['sources'];context['digest']=models.sha((context['digest']+'source shadows jar').encode())
        facts=self.analyze([context])['facts'];ids={f['id']:f for f in facts}
        product=next(f for f in facts if f['kind']=='declaration' and f['signature']=='ext.Product')
        bean=next(f for f in facts if f['relation']=='java.bean' and f['name']=='product')
        self.assertEqual(bean['parent'],product['id'])
        self.assertFalse(any(f['relation']=='config_member' and ids[f['target']]['signature'].startswith('ext.Base#') for f in facts))

    def test_alias_nested_value_tree_construction_and_member_relations(self):
        files = {'config/beans.xml': '''<beans xmlns="http://www.springframework.org/schema/beans">
          <bean id="api" name="first, second; third" class="Api" init-method="start" destroy-method="stop"/>
          <alias name="second" alias="external"/>
          <bean id="holder" class="Holder"><constructor-arg index="0" ref="external"/>
            <property name="values"><map><entry key-ref="first" value-ref="third"/>
              <entry key="literal"><list><ref bean="second"/><idref bean="external"/>
                <value type="java.lang.String">hello</value><null/></list></entry></map></property>
            <property name="items"><list><value>one</value></list></property>
            <property name="nested"><bean class="Api"><property name="other" ref="api"/></bean></property>
          </bean></beans>'''}
        facts = self.facts(files)
        beans = [f for f in facts if f['relation']=='java.bean' and f['attributes'].get('kind')=='xml_bean']
        self.assertEqual(len(beans),3)
        api = next(f for f in beans if f['name']=='api')
        self.assertEqual(api['attributes']['aliases'],['api','first','second','third'])
        kinds = {f['attributes'].get('kind') for f in facts if f['relation']=='java.spring.xml'}
        self.assertTrue({'map','entry','list','ref','idref','value','null','constructor-arg','property','init','destroy'}.issubset(kinds))
        ids = {f['id']:f for f in facts}
        members = {ids[f['target']]['signature'] for f in facts if f['relation']=='config_member'}
        self.assertTrue({'Api#start()','Api#stop()','Api#setOther(Api)','Holder#<init>(Api)',
                         'Holder#setValues(java.util.Map)','Holder#setItems(java.util.List)','Holder#setNested(Api)'}.issubset(members))
        self.assertTrue(any(f['relation']=='config_nested_bean' for f in facts))
        self.assertTrue(all(not f['evidence_complete'] and not f['declaration_resolved'] for f in facts if f['relation']=='config_member'))
        self.assertTrue(all(f['status']=='complete' for f in facts if f['kind']=='coverage' and f['source']=='config/beans.xml'))

    def test_abstract_parent_static_and_instance_factory_type_evidence(self):
        facts = self.facts({'beans.xml': '''<beans><bean id="template" class="Product" abstract="true"/>
          <bean id="child" parent="template"/><bean id="maker" class="Maker"/>
          <bean id="static" class="Maker" factory-method="create"/>
          <bean id="instance" factory-bean="maker" factory-method="instance"/></beans>'''})
        beans = {f['name']:f for f in facts if f['relation']=='java.bean'}
        self.assertTrue(beans['template']['attributes']['abstract'])
        for name in ('child','static','instance'):
            self.assertEqual(beans[name]['attributes']['type'],'Product')
        ids = {f['id']:f for f in facts}
        members = {ids[f['target']]['signature'] for f in facts if f['relation']=='config_member'}
        self.assertIn('Maker#create()',members)
        self.assertIn('Maker#instance()',members)
        self.assertFalse(any(f['kind']=='call_target' and f['owner']['producer']=='spring-model-v1' for f in facts))

    def test_type_hints_overloads_argument_order_shared_returns_and_delta(self):
        sources={
            'Api.java':'public class Api {}', 'Other.java':'public class Other {}',
            'Product.java':'public class Product {}', 'Distinct.java':'public class Distinct {}',
            'Holder.java':'public class Holder { public Holder(Api a,String s){} public Holder(Other a,String s){} public void setItem(Api a){} public void setItem(Other a){} }',
            'Factory.java':'public class Factory { public static Product make(Api a){return null;} public static Distinct make(Other a){return null;} public static Product same(String a){return null;} public static Product same(Integer a){return null;} public Product instance(Api a){return null;} }'}
        xml='''<beans><bean id="api" class="fixture.Api"/><alias name="api" alias="alias"/>
          <bean id="holder" class="fixture.Holder"><constructor-arg index="1" type="java.lang.String" value="text"/><constructor-arg index="0" ref="alias"/><property name="item"><ref bean="alias"/></property></bean>
          <bean id="product" class="fixture.Factory" factory-method="make"><constructor-arg ref="alias"/></bean>
          <bean id="distinct" class="fixture.Factory" factory-method="make"><constructor-arg type="fixture.Other"><null/></constructor-arg></bean>
          <bean id="ambiguous" class="fixture.Factory" factory-method="same"><constructor-arg><null/></constructor-arg></bean>
          <bean id="bad" class="fixture.Factory" factory-method="make"><constructor-arg type="other.Api"><null/></constructor-arg></bean>
          <bean id="nonstatic" class="fixture.Factory" factory-method="instance"><constructor-arg ref="alias"/></bean></beans>'''
        for binary in (False,True):
            with self.subTest(binary=binary):
                context=self.captured({'beans.xml':xml},source='class Anchor {}')
                if binary:
                    folder=self.root/'typed-binary';folder.mkdir()
                    for name,text in sources.items():(folder/name).write_text('package fixture; '+text)
                    classes=folder/'classes';classes.mkdir()
                    subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-d',str(classes)]+[str(folder/name) for name in sources],check=True,capture_output=True)
                    jar=folder/'types.jar'
                    subprocess.run([str(models.JDK8/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True)
                    shutil.rmtree(classes)
                    context['classpath']=[str(jar)]
                else:
                    units=self.context('app',{'fixture/'+name:'package fixture; '+text for name,text in sources.items()})
                    context['sources']=units['sources']
                context['digest']=models.sha((context['digest']+str(binary)).encode())
                old=self.analyze([context])['facts'];self.merged([],dict(facts=old,replacements=[]))
                ids={f['id']:f for f in old};beans={f['name']:f for f in old if f['relation']=='java.bean'}
                for name,typ in [('product','fixture.Product'),('distinct','fixture.Distinct'),('ambiguous','fixture.Product'),('bad',''),('nonstatic','')]:
                    self.assertEqual(beans[name]['attributes']['type'],typ)
                targets={ids[f['target']]['signature'] for f in old if f['relation']=='config_member'}
                self.assertIn('fixture.Holder#<init>(fixture.Api,java.lang.String)',targets)
                self.assertNotIn('fixture.Holder#<init>(fixture.Other,java.lang.String)',targets)
                self.assertIn('fixture.Holder#setItem(fixture.Api)',targets)
                self.assertNotIn('fixture.Holder#setItem(fixture.Other)',targets)
                self.assertTrue(any(f['relation']=='java.spring.xml' and f['attributes'].get('parameter_type_hints')==['fixture.Api','java.lang.String'] for f in old))
                self.assertFalse([f for f in old if f['attributes'].get('code')=='EXTENSION_CALLBACK_FAILED'])
                self.assertTrue(all(not f['declaration_resolved'] and not f['evidence_complete'] for f in old if f['relation']=='config_member'))
                captured=self.root/'xml/0';captured.write_text(xml.replace('type="fixture.Other"','type="fixture.Api"'))
                context['resources'][0]['unit']['hash']=models.sha(captured.read_bytes())
                context['digest']=models.sha((context['digest']+'changed').encode())
                delta=self.analyze([context],old);full=self.analyze([context])['facts']
                self.assertEqual(self.merged(old,delta),sorted(full,key=lambda f:f['id']))

    def test_missing_ambiguous_alias_cycle_and_parent_container_remain_partial(self):
        facts = self.facts({'beans.xml': '''<beans><bean id="same" class="Api"/><bean id="same" class="Product"/>
          <alias name="loop2" alias="loop1"/><alias name="loop1" alias="loop2"/>
          <bean id="use" class="Api"><property name="other"><ref bean="same"/></property>
            <property name="other"><ref bean="missing"/></property>
            <property name="other"><ref bean="loop1"/></property>
            <property name="other"><ref parent="same"/></property></bean></beans>'''})
        references = [f['attributes'] for f in facts if f['relation']=='java.spring.xml' and f['attributes'].get('kind')=='bean_ref']
        self.assertIn(2,[r['candidate_count'] for r in references if r['bean']=='same'])
        self.assertTrue(all(r['candidate_count']==0 for r in references if r['bean'] in ('missing','loop1')))
        self.assertTrue(any(f['status']=='partial' for f in facts if f['kind']=='coverage' and f['source']=='beans.xml'))
        parent = next(f for f in facts if f['attributes'].get('kind')=='parent_context_ref')
        self.assertFalse(any(f['kind']=='reference' and f['parent']==parent['id'] for f in facts))

    def test_unicode_cdata_comments_bom_and_exact_utf8_spans(self):
        xml = '\ufeff<?xml version="1.0"?><beans><!--ignored >--><bean id="中文" class="Api">\n'
        xml += '<property name="other"><value><![CDATA[文字 < > &]]></value></property></bean></beans>'
        facts = self.facts({'unicode.xml':xml})
        value = next(f for f in facts if f['attributes'].get('kind')=='value' and f['relation']=='java.spring.xml')
        self.assertEqual(value['attributes']['value'],'文字 < > &')
        raw = xml.encode('utf8')
        self.assertEqual(raw[value['span']['start']:value['span']['end']].decode(),'<value><![CDATA[文字 < > &]]></value>')
        bean = next(f for f in facts if f['relation']=='java.bean')
        self.assertEqual(bean['name'],'中文')
        self.assertTrue(raw[bean['span']['start']:bean['span']['end']].decode().startswith('<bean id='))

    def test_malformed_doctype_namespace_and_budget_isolate_one_resource(self):
        files = {'good.xml':'<beans><bean id="good" class="Api"/></beans>',
                 'broken.xml':'<beans><bean id="unfinished" class="Api">',
                 'entity.xml':'<!DOCTYPE beans [<!ENTITY file SYSTEM "file:///etc/passwd">]><beans>&file;</beans>',
                 'depth.xml':'<beans><bean class="Api">'+'<list>'*130+'<null/>'+'</list>'*130+'</bean></beans>',
                 'spoof.xml':'<beans xmlns="urn:spoof"><bean id="spoof" class="Api"/></beans>',
                 'pom.xml':'<project><bean id="fake" class="Api"/></project>'}
        facts = self.facts(files)
        self.assertEqual([f['name'] for f in facts if f['relation']=='java.bean'],['good'])
        bad = {f['source'] for f in facts if f['kind']=='coverage' and f['status']=='partial' and f['owner']['producer']=='spring-model-v1'}
        self.assertTrue({'broken.xml','entity.xml','depth.xml'}.issubset(bad))

    def test_import_inventory_and_edit_delete_disable_full_delta_equivalence(self):
        files = {'config/main.xml':'<beans><import resource="extra.xml"/><bean id="main" class="Api"><property name="other" ref="extra"/></bean></beans>',
                 'config/extra.xml':'<beans><bean id="extra" class="Api"/></beans>'}
        context = self.captured(files)
        first = self.analyze([context])['facts']
        imported = next(f['attributes'] for f in first if f['attributes'].get('kind')=='import')
        self.assertEqual(imported['sealed_matches'],['config/extra.xml'])
        changed = dict(files)
        changed['config/main.xml'] = changed['config/main.xml'].replace('ref="extra"','ref="unknown"')
        context = self.captured(changed)
        delta = self.analyze([context],first)
        self.assertEqual(self.merged(first,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))
        current = self.merged(first,delta)
        del changed['config/extra.xml']
        context = self.captured(changed)
        delta = self.analyze([context],current)
        self.assertEqual(self.merged(current,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))
        current = self.merged(current,delta)
        context = self.captured(changed,enabled=False)
        delta = self.analyze([context],current)
        self.assertEqual(self.merged(current,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))
        self.assertFalse(any(f['owner']['producer']=='spring-model-v1' for f in self.merged(current,delta)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
