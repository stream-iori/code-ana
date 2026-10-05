#!/usr/bin/env python3
"""Adversarial build-time extensions against real detached Java 8 model execution."""
import hashlib
import os
import subprocess
import unittest
import java_model_tests as model_tests


class Extensions(unittest.TestCase):
    probe = 'dev.codeana.analysis.ExtensionProbe'
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze
    merged = model_tests.Models.merged

    def test_lombok_ast_initializer_metadata_and_idempotence(self):
        cp=os.pathsep.join(str(model_tests.ROOT/'lang-adaptor/target'/p)
                           for p in ('classes','test-classes'))
        subprocess.run([str(model_tests.JDK25/'bin/java'), '-Xmx64m', '-cp', cp,
                        'dev.codeana.analysis.LombokAstSharingProbe'], check=True, timeout=30)

    def fixture(self, **options):
        context = self.context('app', {'Use.java': 'class Use { void run(){System.out.println("value");} }'}, **options)
        xml = self.root / 'resource.xml'
        xml.write_text('<beans/>')
        context['resources'] = [dict(unit=dict(path='resource.xml', world='resources', hash=hashlib.sha256(xml.read_bytes()).hexdigest()), input=str(xml))]
        return context

    def emitted(self, facts, stage):
        return [f for f in facts if f['owner']['producer']=='fixture-producer' and f['name']==stage]

    def assert_failure(self, facts, stage):
        failure = [f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('stage')==stage]
        self.assertTrue(failure)
        self.assertTrue(any(f['kind']=='coverage' and f['status']=='partial'
                            and f['attributes'].get('stage')==stage for f in facts))

    def test_all_five_points_and_read_view(self):
        result = self.analyze([self.fixture()])
        facts = result['facts']
        generated = next(f for f in facts if f['signature']=='Use#fixture()')
        self.assertFalse(generated['declaration_resolved'])
        self.assertIsNone(generated['span'])
        self.assertFalse(generated['attributes']['body_available'])
        project = self.emitted(facts, 'project')[0]['attributes']
        self.assertEqual(project['resource_count'], 1)
        self.assertTrue(project['saw_java_facts'])
        self.assertTrue(project['saw_call_evidence'])
        report = result['extension_reports'][self.fixture()['world']]
        self.assertEqual(set(report['calls']), {'model.fixture','java.fixture','call.fixture','resource.fixture','project.fixture'})
        self.assertEqual(report['failures'], [])
        self.merged([], dict(facts=facts, replacements=[]))


    def test_selector_filters_sealed_inventory_and_isolates_failures(self):
        context = self.fixture(test_mode='select')
        for path in ('config/accepted.cfg', 'config/ignored.txt'):
            file = self.root / path
            file.parent.mkdir(exist_ok=True)
            file.write_text('sealed')
            context['resources'].append(dict(unit=dict(path=path, world='resources',
                hash=hashlib.sha256(file.read_bytes()).hexdigest()), input=str(file)))
        old = self.analyze([context])['facts']
        self.merged([], dict(facts=old, replacements=[]))
        selected = self.emitted(old, 'project')[0]['attributes']
        self.assertEqual(selected['selected_paths'], ['config/accepted.cfg'])
        self.assertEqual(selected['inventory_paths'], ['config/accepted.cfg', 'config/ignored.txt', 'resource.xml'])
        for mode in ('selector-fail', 'selector-factory-fail'):
            context['test_mode'] = mode
            delta = self.analyze([context], old)
            full = self.analyze([context])['facts']
            self.assertEqual(self.merged(old, delta), sorted(full, key=lambda f: f['id']))
            self.assertEqual(self.emitted(full, 'project'), [])
            self.assertTrue(self.emitted(full, 'java'))
            self.assert_failure(full, 'project')
        context['test_mode'] = 'selector-cancel'
        with self.assertRaisesRegex(AssertionError, 'SELECTOR_CANCELLED'):
            self.analyze([context])

    def declaration_fixture(self, **options):
        return self.context('app', {'Use.java':'class Good {} class Bad { Bad(int x){} } class After {}'},
                            assembly='declarations', **options)

    def test_declaration_failures_preserve_successes_and_discard_pending_metadata(self):
        for mode in ('declaration-fail', 'declaration-reject'):
            with self.subTest(mode=mode):
                result = self.analyze([self.declaration_fixture(test_mode=mode)])
                facts = result['facts']
                signatures = {f['signature'] for f in facts}
                self.assertIn('Good#model()', signatures)
                self.assertIn('After#model()', signatures)
                self.assertNotIn('Bad#model()', signatures)
                bad = next(f for f in facts if f['signature']=='Bad')
                self.assertNotIn('model_extensions', bad['attributes'])
                successful = {f['name'] for f in facts if f['attributes'].get('stage')=='declaration'}
                self.assertEqual(successful, {'Good','After'})
                failures = [f for f in facts if f['kind']=='diagnostic' and f['attributes'].get('subject')=='Bad']
                self.assertEqual({f['attributes']['stage'] for f in failures}, {'model','java'})
                self.merged([], dict(facts=facts, replacements=[]))

    def test_source_constructor_removal_is_rejected_without_touching_other_declarations(self):
        facts = self.analyze([self.declaration_fixture(test_mode='source-removal')])['facts']
        bad = next(f for f in facts if f['signature']=='Bad#<init>(int)')
        self.assertTrue(bad['declaration_resolved'])
        self.assertEqual(bad['origin'], 'javac')
        self.assertFalse(any(f['signature']=='Bad#model()' for f in facts))
        self.assertTrue(any(f['signature']=='After#model()' for f in facts))
        self.assert_failure(facts, 'model')

    def test_declaration_failure_cleanup_delta_and_terminal_signals(self):
        context = self.declaration_fixture()
        old = self.analyze([context])['facts']
        context['test_mode'] = 'declaration-fail'
        delta = self.analyze([context], old)
        full = self.analyze([context])
        self.assertEqual(self.merged(old, delta), sorted(full['facts'], key=lambda f:f['id']))
        context['extension_enabled'] = False
        delta = self.analyze([context], old)
        full = self.analyze([context])
        self.assertEqual(self.merged(old, delta), sorted(full['facts'], key=lambda f:f['id']))
        self.assertFalse(any(f['owner']['producer']=='declarations-fixture' for f in full['facts']))
        for mode, reason in [('strict','DECLARATION_STRICT'),('cancel','DECLARATION_CANCELLED')]:
            with self.subTest(mode=mode), self.assertRaisesRegex(AssertionError, reason):
                self.analyze([self.declaration_fixture(test_mode=mode)])

    def test_project_phase_waits_for_all_visible_java_contexts(self):
        upstream = self.context('upstream', {'Other.java':'class Other {}'})
        app = self.fixture()
        app['sources'] += [dict(s, publish=False) for s in upstream['sources']]
        hidden = self.context('tests', {'TestOnly.java':'class TestOnly {}'}, scope='TEST')
        contexts = [app, upstream, hidden]
        serial = self.analyze(contexts)
        parallel = self.analyze(contexts, parallelism=2)
        self.assertEqual(serial['facts'], parallel['facts'])
        project = next(f for f in self.emitted(serial['facts'], 'project') if f['owner']['world']==app['world'])
        self.assertEqual(project['attributes']['visible_types'], ['Other','Use'])
        self.assertEqual(project['attributes']['visible_java_stages'], 2)
        self.merged([], dict(facts=serial['facts'], replacements=[]))
        upstream['test_mode'] = 'java-fail'
        delta = self.analyze(contexts, serial['facts'])
        full = self.analyze(contexts)
        self.assertEqual(self.merged(serial['facts'], delta), sorted(full['facts'], key=lambda f:f['id']))
        self.assertEqual(delta['analyzed_contexts'], 2)

    def test_registry_dependency_order_and_fingerprint_are_stable(self):
        ordered = self.analyze([self.fixture(assembly='ordered')])
        reverse = self.analyze([self.fixture(assembly='reverse')])
        self.assertEqual(ordered['facts'], reverse['facts'])
        left, right = [next(iter(r['extension_reports'].values())) for r in (ordered, reverse)]
        self.assertEqual(left['fingerprint'], right['fingerprint'])
        self.assertEqual([e['id'] for e in left['extensions']], ['fixture','dependent'])

    def test_invalid_registries_reject_before_analysis(self):
        for assembly, reason in [('duplicate','EXTENSION_ID_OR_VERSION'),('missing','EXTENSION_DEPENDENCY_MISSING'),('cycle','EXTENSION_DEPENDENCY_CYCLE'),
                                 ('reserved-producer','EXTENSION_ID_OR_VERSION'),('producer-collision','EXTENSION_ID_OR_VERSION')]:
            with self.subTest(assembly=assembly), self.assertRaisesRegex(AssertionError, reason):
                self.analyze([self.fixture(assembly=assembly)])

    def test_enabled_consumer_rejects_disabled_dependency(self):
        with self.assertRaisesRegex(AssertionError, 'EXTENSION_DEPENDENCY_DISABLED'):
            self.analyze([self.fixture(assembly='ordered', disable_base=True)])

    def test_overlay_failures_do_not_commit_headers(self):
        for mode in ('overlay-fail','overlay-owner','immutable'):
            with self.subTest(mode=mode):
                facts = self.analyze([self.fixture(test_mode=mode)])['facts']
                self.assertFalse(any(f['signature']=='Use#fixture()' for f in facts))
                self.assert_failure(facts, 'model')
                self.assertTrue(self.emitted(facts, 'java'))
                declaration = next(f for f in facts if f['signature']=='Use')
                self.assertNotIn('compiler_corruption', declaration['attributes'])

    def test_failed_callback_or_invalid_facts_are_atomic(self):
        for mode in ('java-fail','owner','target','schema','link'):
            with self.subTest(mode=mode):
                facts = self.analyze([self.fixture(test_mode=mode)])['facts']
                self.assertFalse(self.emitted(facts, 'java'))
                self.assert_failure(facts, 'java')
                self.assertTrue(self.emitted(facts, 'project'))
                self.assertTrue(any(f['kind']=='call_target' and f['status']=='resolved'
                                    and f['owner']['producer']=='javac-java8-v1' for f in facts))

    def test_external_binary_metadata_cannot_claim_compiler_or_source_evidence(self):
        facts=self.analyze([self.fixture(test_mode='external')])['facts']
        external=next(f for f in facts if f['kind']=='external_symbol' and f['signature']=='ext.Binary')
        self.assertFalse(external['declaration_resolved'])
        self.assertEqual(external['source'],'')
        for mode in ('external-confirmed','external-evidence','external-origin','external-source','external-span','external-binding'):
            with self.subTest(mode=mode):
                facts=self.analyze([self.fixture(test_mode=mode)])['facts']
                self.assertFalse(any(f['signature']=='ext.Binary' for f in facts))
                self.assertFalse(self.emitted(facts,'java'))
                self.assert_failure(facts,'java')
                self.assertTrue(self.emitted(facts,'project'))

    def test_call_failure_preserves_selected_targets(self):
        context = self.fixture()
        normal = self.analyze([context])['facts']
        context['test_mode'] = 'call-fail'
        failed = self.analyze([context])['facts']
        selected = lambda facts: [f for f in facts if f['kind']=='call_target']
        self.assertEqual(selected(normal), selected(failed))
        self.assert_failure(failed, 'call')

    def test_resources_are_frozen_and_discovery_failure_isolated(self):
        for mode in ('resource-fail','resource-unsealed'):
            with self.subTest(mode=mode):
                facts = self.analyze([self.fixture(test_mode=mode)])['facts']
                self.assert_failure(facts, 'resource')
                self.assertEqual(self.emitted(facts, 'project')[0]['attributes']['resource_count'], 0)
                self.assertTrue(self.emitted(facts, 'java'))

    def test_project_failure_preserves_java_and_call_evidence(self):
        facts = self.analyze([self.fixture(test_mode='project-fail')])['facts']
        self.assertFalse(self.emitted(facts, 'project'))
        self.assertTrue(self.emitted(facts, 'java'))
        self.assertTrue(any(f['attributes'].get('observation')=='source-use' for f in facts))
        self.assert_failure(facts, 'project')

    def test_strict_and_cancellation_are_terminal(self):
        for mode, reason in [('strict','FIXTURE_STRICT'),('cancel','FIXTURE_CANCELLED')]:
            with self.subTest(mode=mode), self.assertRaisesRegex(AssertionError, reason):
                self.analyze([self.fixture(test_mode=mode)])

    def test_version_and_config_change_invalidate_unchanged_sources(self):
        old = self.analyze([self.fixture()])['facts']
        for options in [dict(extension_version='2'), dict(test_mode='java-fail')]:
            with self.subTest(options=options):
                context = self.fixture(**options)
                delta = self.analyze([context], old)
                full = self.analyze([context])
                self.assertEqual(delta['analyzed_contexts'], 1)
                self.assertEqual(self.merged(old, delta), sorted(full['facts'], key=lambda f:f['id']))

    def test_disabled_extension_cleans_all_previous_facts_and_headers(self):
        context = self.fixture()
        old = self.analyze([context])['facts']
        self.assertEqual(self.analyze([context], old)['facts'], [])
        context['extension_enabled'] = False
        delta = self.analyze([context], old)
        full = self.analyze([context])
        self.assertEqual(self.merged(old, delta), sorted(full['facts'], key=lambda f:f['id']))
        self.assertFalse(any(f['owner']['producer']=='fixture-producer'
                             or f['signature']=='Use#fixture()' for f in full['facts']))


if __name__=='__main__':
    unittest.main(verbosity=2)
