#!/usr/bin/env python3
"""Exercise independently captured Java/XML/SOFA switches through the real daemon."""
import argparse
import json
import os
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    args = parser.parse_args()
    runtime, evidence = args.runtime.resolve(), args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    os.environ.update(CODEANA_JAVA_BACKEND='jvm',
                      CODEANA_TEST_BIN=str(runtime / 'target/debug'),
                      CODEANA_TEST_JAVA_BUNDLE=str(runtime / 'target/lib/java/jvm'))
    from java_e2e import JavaProduct
    from java_jvm_product_gate import ReceiptResult
    import java_jvm_product_gate as gate
    from acceptance_support import identities, summarize

    class SwitchProduct(JavaProduct):
        def test_independent_java_xml_sofa_switches_and_delta(self):
            source = self.project / 'src/A.java'
            source.write_text('@org.springframework.stereotype.Component("a") '
                '@com.alipay.sofa.runtime.api.annotation.SofaService(interfaceType=Runnable.class) '
                'class A implements Runnable {public void run(){}}')
            for path, text in {
                'org/springframework/stereotype/Component.java':
                    'package org.springframework.stereotype;public @interface Component {String value() default "";}',
                'com/alipay/sofa/runtime/api/annotation/SofaService.java':
                    'package com.alipay.sofa.runtime.api.annotation;public @interface SofaService {Class<?> interfaceType();}',
            }.items():
                file = self.project / 'src' / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text)
            resource = self.project / 'src/main/resources/beans.xml'
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_text('<beans xmlns:sofa="http://schema.alipay.com/sofa/schema/service">'
                '<bean id="a" class="A"/><sofa:reference id="remote" interface="java.lang.Runnable"/></beans>')
            config = self.project / '.code-ana/config.toml'
            text = config.read_text().replace('[languages.java.extensions.spring]\nenabled = false',
                '[languages.java.extensions.spring]\nenabled = false\nxml = true')
            text = text.replace('[languages.java.extensions.sofa]\nenabled = false',
                                '[languages.java.extensions.sofa]\nenabled = true')
            self.assertIn('xml = true', text)
            self.assertIn('[languages.java.extensions.sofa]\nenabled = true', text)
            config.write_text(text)
            _, initial = self.cli('index', '--full', timeout=180)
            facts = self.facts(initial['revision'])
            source_bean = lambda rows: any(f['source'] == 'src/A.java' and
                                            f['relation'] == 'java.bean' for f in rows)
            self.assertFalse(source_bean(facts), 'Explicit Spring Java disable must be respected')
            self.assertTrue(any(f['source'] == 'src/A.java' and f['relation'] == 'java.sofa' for f in facts))
            self.assertTrue(any(f['source'].endswith('beans.xml') and f['relation'] == 'java.bean' for f in facts))
            config.write_text(text.replace('[languages.java.extensions.spring]\nenabled = false',
                                          '[languages.java.extensions.spring]\nenabled = true'))
            _, enabled = self.cli('index', timeout=180)
            self.assertTrue(source_bean(self.facts(enabled['revision'])))
            config.write_text(text)
            _, disabled = self.cli('index', timeout=180)
            disabled_facts = self.facts(disabled['revision'])
            self.assertFalse(source_bean(disabled_facts))
            _, full = self.cli('index', '--full', timeout=180)
            self.assertEqual(disabled_facts, self.facts(full['revision']))
            config.write_text(text.replace('xml = true', 'xml = false'))
            _, xml_disabled = self.cli('index', timeout=180)
            rows = self.facts(xml_disabled['revision'])
            self.assertFalse(source_bean(rows))
            self.assertTrue(any(f['source'] == 'src/A.java' and f['relation'] == 'java.sofa' for f in rows))
            self.assertFalse(any(f['source'].endswith('beans.xml') and f['kind'] == 'facet'
                                 for f in rows))
            _, full = self.cli('index', '--full', timeout=180)
            self.assertEqual(rows, self.facts(full['revision']))

    suite = unittest.TestSuite([SwitchProduct('test_independent_java_xml_sofa_switches_and_delta')])
    expected = [test.id() for test in suite]
    paths = [p for p in runtime.rglob('*') if p.is_file()]
    paths += [p for p in (ROOT / 'tests').rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    paths.append(pathlib.Path(__file__).resolve())
    before = identities(paths)
    gate.PROGRESS_FILE = evidence / 'progress.json'
    with (evidence / 'tests.log').open('w') as log:
        result = unittest.TextTestRunner(stream=log, verbosity=2, resultclass=ReceiptResult).run(suite)
    receipt = summarize(expected, result.cases, before, identities(paths))
    receipt.update(backend='jvm', runtime=str(runtime), inputs=before,
                   complete_fixture_gate=False, native_accepted=False)
    (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'])
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
