#!/usr/bin/env python3
"""SOFA 4 / MsgBroker XML contracts, multiplicity and sealed configuration."""
import unittest
import java_model_tests as models
import java_xml_alignment as xml_tests

NAMESPACE = 'http://schema.alipay.com/sofa/schema/service'
SOURCE = 'interface Api {} class Service implements Api {} class Listener {}'


class SofaXml(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def captured(self, files, enabled=True):
        context = xml_tests.Xml.captured(self,files,enabled=False,source=SOURCE)
        context['sofa'] = enabled
        return context

    def facts(self, body):
        files = {'beans.xml':f'<beans xmlns:sofa="{NAMESPACE}">{body}</beans>'}
        facts = self.analyze([self.captured(files)])['facts']
        self.merged([],dict(facts=facts,replacements=[]))
        self.assertFalse([f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('code')=='EXTENSION_CALLBACK_FAILED'])
        return facts

    def test_service_reference_bindings_transport_and_duplicate_occurrences(self):
        facts = self.facts('''<bean id="implementation" class="Service"/>
          <sofa:service id="api" interface="Api" unique-id="one" ref="implementation"><sofa:binding.tr/></sofa:service>
          <sofa:reference id="remote" interface="Api" unique-id="two"><sofa:binding.tri/><sofa:binding.rest/></sofa:reference>
          <sofa:reference id="duplicate" interface="Api"/><sofa:reference id="duplicate" interface="Api"/>''')
        registrations = [f for f in facts if f['relation']=='java.sofa' and f['attributes']['kind'] in ('service','reference')]
        self.assertEqual(len(registrations),4)
        self.assertEqual(len({f['id'] for f in registrations}),4)
        provider = next(f for f in registrations if f['name']=='api')
        self.assertEqual(provider['attributes']['role'],'provider')
        self.assertEqual(provider['attributes']['transport'],'tr')
        self.assertEqual(provider['attributes']['unique_id'],'one')
        self.assertTrue(provider['attributes']['contract_supported'])
        remote = next(f for f in registrations if f['name']=='remote')
        self.assertEqual(remote['attributes']['bindings'],['tri','rest'])
        self.assertEqual(remote['attributes']['transport'],'multiple')
        self.assertTrue(any(f['kind']=='reference' and f['relation']=='config' for f in facts))
        self.assertFalse(any(f['kind']=='call_target' and f['owner']['producer']=='sofa-model-v1' for f in facts))

    def test_publisher_consumer_channels_events_listener_tree(self):
        facts = self.facts('''<bean id="listener" class="Listener"/>
          <sofa:publisher id="publisher" group="pub"><sofa:channels><sofa:channel value="first"><sofa:event value="a"/></sofa:channel>
            <sofa:channel value="second"><sofa:event value="b"/></sofa:channel></sofa:channels></sofa:publisher>
          <sofa:consumer id="consumer" group="consume"><sofa:binding.msg_broker/>
            <sofa:listener ref="listener"/><sofa:channel value="second"><sofa:event value="c"/></sofa:channel></sofa:consumer>''')
        registrations = [f for f in facts if f['relation']=='java.sofa' and f['attributes']['kind'] in ('publisher','consumer')]
        self.assertEqual(len(registrations),2)
        consumer = next(f for f in registrations if f['name']=='consumer')
        self.assertEqual(consumer['attributes']['framework'],'msgbroker')
        self.assertEqual(consumer['attributes']['target_bean'],'listener')
        self.assertEqual(consumer['attributes']['transport'],'msg_broker')
        events = [f for f in facts if f['relation']=='java.sofa' and f['attributes'].get('element')=='event']
        self.assertEqual({f['attributes']['channel'] for f in events},{'first','second'})
        self.assertTrue(all(f['span'] and f['parent'] for f in events))
        self.assertTrue(all(f['attributes']['runtime_confirmed'] is False for f in registrations))

    def test_dynamic_parent_abstract_unknown_transport_namespace_and_parse_failures(self):
        facts = self.facts('''<sofa:reference id="dynamic" interface="${api.type}" unique-id="${id}"/>
          <sofa:service id="abstract" interface="Api" abstract="true"/>
          <sofa:reference id="parent" interface="Api" parent="template"/>
          <sofa:reference id="unknown" interface="Api"><sofa:binding.custom/></sofa:reference>''')
        registrations = [f for f in facts if f['relation']=='java.sofa' and f['attributes']['kind'] in ('service','reference')]
        self.assertTrue(all(not f['attributes']['contract_supported'] for f in registrations))
        self.assertTrue(any(f['kind']=='coverage' and f['status']=='partial' for f in facts))
        context = self.captured({'spoof.xml':'<beans xmlns:sofa="urn:not-sofa"><sofa:reference id="fake" interface="Api"/></beans>',
                                 'broken.xml':f'<beans xmlns:sofa="{NAMESPACE}"><sofa:reference id="unfinished" interface="Api">'})
        facts = self.analyze([context])['facts']
        self.assertFalse(any(f['relation']=='java.sofa' for f in facts))
        self.assertTrue(any(f['kind']=='coverage' and f['source']=='broken.xml' and f['status']=='partial' for f in facts))

    def test_resource_edit_delete_and_independent_disable_delta(self):
        files = {'beans.xml':f'<beans xmlns:sofa="{NAMESPACE}"><sofa:reference id="api" interface="Api"/></beans>'}
        context = self.captured(files)
        first = self.analyze([context])['facts']
        files['beans.xml'] = files['beans.xml'].replace('id="api"','id="api" unique-id="new"')
        context = self.captured(files)
        delta = self.analyze([context],first)
        self.assertEqual(self.merged(first,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))
        current = self.merged(first,delta)
        context = self.captured(files,enabled=False)
        delta = self.analyze([context],current)
        self.assertEqual(self.merged(current,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))
        self.assertFalse(any(f['owner']['producer']=='sofa-model-v1' for f in self.merged(current,delta)))
        context = self.captured({})
        delta = self.analyze([context],current)
        self.assertEqual(self.merged(current,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
