#!/usr/bin/env python3
"""Real Java resource failure must never publish a partial graph."""
import unittest
import sqlite3
import contextlib
from product_support import JavaProductHarness

class XmlPublication(JavaProductHarness):
    def test_full_and_delta_resource_failure_preserve_head_and_allow_retry(self):
        config = self.project / '.code-ana/config.toml'
        config.write_text(config.read_text().replace(
            '[languages.java.extensions.spring]\nenabled = false',
            '[languages.java.extensions.spring]\nenabled = true\nxml = true'))
        xml = self.project / 'beans.xml'
        valid = '<beans><bean id="service" class="p.Service"/></beans>'
        xml.write_text(valid)
        _, good = self.cli('index', '--full', timeout=180)
        old = good['revision']
        for args in [(), ('--full',)]:
            xml.write_text('<beans><bean id="early" class="p.Service"/>'
                           + '<list>' * 300 + '</list>' * 300 + '</beans>')
            _, failed = self.cli('index', *args, code=1, timeout=180)
            self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
            resources = self.cli('report', failed['report'])[0][0]['analysis_resources']
            self.assertEqual(resources['usage']['failure']['Limit']['resource'], 'depth')
            self.assertEqual(self.cli('overview')[1]['revision'], old)
            self.assertEqual(self.cli('overview', '--revision', old)[1]['revision'], old)
            xml.write_text(valid)
            _, retried = self.cli('index', *args, timeout=180)
            old = retried['revision']

    def test_sofa_xml_input_limit_preserves_both_registration_and_head(self):
        config = self.project / '.code-ana/config.toml'
        value = config.read_text().replace('[languages.java.extensions.spring]\nenabled = false',
            '[languages.java.extensions.spring]\nenabled = true\nxml = true').replace(
            '[languages.java.extensions.sofa]\nenabled = false', '[languages.java.extensions.sofa]\nenabled = true')
        config.write_text(value)
        xml = self.project / 'sofa.xml'
        prefix = '<beans xmlns:s="http://schema.alipay.com/sofa/schema/service">'
        valid = prefix + '<s:reference id="client" interface="p.Service"/></beans>'
        xml.write_text(valid)
        _, good = self.cli('index', '--full', timeout=180)
        old = good['revision']
        def registrations(revision):
            with contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db')) as db:
                rows = db.execute("SELECT id,metadata FROM nodes WHERE kind='FRAMEWORK_REGISTRATION' ORDER BY id").fetchall()
            self.assertTrue(any('sofa.xml.reference' in row[1] for row in rows), rows)
            return rows
        before = registrations(old)
        for args in [(), ('--full',)]:
            # Stay below the shared 8 MiB capture limit. Spring can ignore
            # comments; SOFA must deny its conservative read capacity first.
            xml.write_text(prefix + '<!--' + 'x'*(8*1024*1024-1024) + '--><s:reference id="bad" interface="p.Service"/></beans>')
            _, failed = self.cli('index', *args, code=1, timeout=180)
            self.assertEqual(failed['code'], 'ANALYSIS_RESOURCE_LIMIT')
            resources = self.cli('report', failed['report'])[0][0]['analysis_resources']
            failure = resources['usage']['failure']['Limit']
            self.assertEqual(failure['resource'], 'payload_bytes')
            self.assertEqual(failure['limit'], 64*1024*1024)
            self.assertEqual(self.cli('overview')[1]['revision'], old)
            self.assertEqual(registrations(old), before)
            xml.write_text(valid)
            _, restored = self.cli('index', *args, timeout=180)
            old = restored['revision']
            self.assertEqual(registrations(old), before)

    def test_lombok_configuration_and_overlay_share_task_budget_and_preserve_head(self):
        config = self.project / '.code-ana/config.toml'
        original = config.read_text() + '\n[languages.java.lombok]\nmode="model"\n'
        config.write_text(original)
        annotation = self.project / 'src/lombok/Data.java'
        annotation.parent.mkdir(parents=True)
        annotation.write_text('package lombok; public @interface Data {}')
        (self.project/'src/p/Model.java').write_text('package p; @lombok.Data class Model { String name; }')
        lombok = self.project/'lombok.config'
        valid = 'lombok.addLombokGeneratedAnnotation = false\n'
        lombok.write_text(valid)
        _, good = self.cli('index','--full',timeout=180)
        old = good['revision']
        self.assertTrue(self.cli('resolve','p.Model#getName()')[0])
        for args in [(),('--full',)]:
            lombok.write_text(valid + '#' + 'x'*200000 + '\n')
            config.write_text(original + '\n[resources.index.analysis]\nmax_work=100000\n')
            _, failed = self.cli('index',*args,code=1,timeout=180)
            self.assertEqual(failed['code'],'ANALYSIS_RESOURCE_LIMIT')
            account = self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
            self.assertIsNone(account['admission']['failure'])
            java = next(item for item in account['languages'] if item['allocation']['language']=='java')
            self.assertEqual(java['worker']['state'],'failed')
            self.assertEqual(java['worker']['usage']['failure']['Limit']['resource'],'work')
            self.assertEqual(self.cli('overview')[1]['revision'],old)
            self.assertTrue(self.cli('resolve','p.Model#getName()','--revision',old)[0])
            config.write_text(original)
            lombok.write_text(valid)
            _, good = self.cli('index',*args,timeout=180)
            old = good['revision']
            self.assertTrue(self.cli('resolve','p.Model#getName()')[0])

if __name__ == '__main__':
    unittest.main(verbosity=2)
