#!/usr/bin/env python3
"""Long generated receiver chains keep bounded recovery and candidate checks."""
import unittest
import java_model_tests as models
import java_lombok_builder_alignment as builders

SOURCE = ('@lombok.Builder class Api {' + ' '.join('String f' + str(i) + ';' for i in range(40))
          + '} class Use {Api read(){return Api.builder()'
          + ''.join('.f' + str(i) + '("x")' for i in range(40)) + '.build();}}')

GETTER_SOURCE = ('@lombok.Getter class Def {' + ' '.join('String f' + str(i) + ';' for i in range(14))
                 + '} @lombok.Builder class Api {' + ' '.join('String f' + str(i) + ';' for i in range(14))
                 + '} class Use {Api read(Def d){return Api.builder()'
                 + ''.join('.f' + str(i) + '(d.getF' + str(i) + '())' for i in range(14)) + '.build();}}')

def ordinary_chain(length):
    return ('@lombok.Getter class Trigger {String value;} '
            'class Plain {Plain next(){return this;} void done(){}} '
            'class Use {void read(Plain p){p' + '.next()' * length + '.done();}}')


class BuilderChains(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged
    controls = builders.Builders.controls
    fixture = builders.Builders.fixture

    def targets(self, facts):
        by = {f['id']: f for f in facts}
        return {by[f['target']]['signature']: f for f in facts if f['kind'] == 'call_target'}

    def test_plain_eight_receiver_chain_ignores_absent_modeled_capability(self):
        source = ('@lombok.Getter class Trigger {String value;} '
                  'class Plain {Plain next(){return this;} void done(){}} '
                  'class Use {void read(Plain p){p' + '.next()' * 8 + '.done();}}')
        base = self.analyze([self.fixture(source)], cache=True)
        targets = [fact for fact in base['facts'] if fact['kind'] == 'call_target']
        self.assertEqual(len(targets), 9)
        self.assertTrue(all(fact['status'] == 'resolved' for fact in targets))
        changed = self.fixture(source.replace('return this;', 'return null;'))
        delta = self.analyze([changed], base['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        self.assertFalse([fact for fact in full['facts'] if fact['attributes'].get('recovery_failure')])

    def test_plain_ten_receivers_keep_independent_call_and_expression_budgets(self):
        for graph in (False, True):
            with self.subTest(graph=graph):
                context = self.fixture(ordinary_chain(10))
                context['type_graph'] = graph
                facts = self.analyze([context])['facts']
                targets = [fact for fact in facts if fact['kind'] == 'call_target']
                self.assertEqual(len(targets), 11)
                self.assertTrue(all(fact['status'] == 'resolved' for fact in targets))
                self.assertFalse([fact for fact in facts if fact['attributes'].get('recovery_failure')])

    def test_plain_eighty_receivers_keep_frozen_budget_boundary_and_cached_delta(self):
        source = ordinary_chain(80)
        for graph in (False, True):
            with self.subTest(graph=graph):
                context = self.fixture(source)
                context['type_graph'] = graph
                base = self.analyze([context], cache=True)
                targets = [fact for fact in base['facts'] if fact['kind'] == 'call_target']
                self.assertEqual(len(targets), 32)
                blocked = [fact for fact in base['facts'] if fact['kind'] == 'call_site'
                           and fact['attributes'].get('recovery_failure') == 'recovery_budget']
                self.assertEqual(len(blocked), 49)
                self.assertTrue(all(fact['attributes'].get('recovery_target_blocked') for fact in blocked))
                changed = self.fixture(source.replace('return this;', 'return null;'))
                changed['type_graph'] = graph
                delta = self.analyze([changed], base['facts'], cache=True)
                full = self.analyze([changed], cache=True)
                self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))

    def test_forty_setters_recover_build_without_relaxing_limits(self):
        facts = self.analyze([self.fixture(SOURCE)])['facts']
        targets = self.targets(facts)
        self.assertEqual(len(targets), 42)
        for signature in ['Api#builder()', 'Api$ApiBuilder#build()'] + ['Api$ApiBuilder#f' + str(i) + '(java.lang.String)' for i in range(40)]:
            self.assertEqual(targets[signature]['status'], 'possible')
            self.assertFalse(targets[signature]['evidence_complete'])
        self.assertFalse([f for f in facts if f['attributes'].get('recovery_failure') == 'recovery_budget'])

    def test_wrong_argument_blocks_remaining_chain(self):
        facts = self.analyze([self.fixture(SOURCE.replace('.f20("x")', '.f20(1)'))])['facts']
        targets = self.targets(facts)
        self.assertIn('Api$ApiBuilder#f19(java.lang.String)', targets)
        self.assertNotIn('Api$ApiBuilder#f20(java.lang.String)', targets)
        self.assertNotIn('Api$ApiBuilder#build()', targets)

    def test_fourteen_setters_with_generated_getters_keep_bounded_recovery(self):
        facts = self.analyze([self.fixture(GETTER_SOURCE)])['facts']
        targets = self.targets(facts)
        self.assertEqual(len(targets), 30)
        self.assertEqual(targets['Api$ApiBuilder#build()']['status'], 'possible')
        self.assertFalse([f for f in facts if f['attributes'].get('recovery_failure') == 'recovery_budget'])

    def test_getter_type_change_blocks_builder_and_matches_cached_full_delta(self):
        base = self.analyze([self.fixture(GETTER_SOURCE)], cache=True)
        changed = self.fixture(GETTER_SOURCE.replace('String f7;', 'Integer f7;', 1))
        delta = self.analyze([changed], base['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        targets = self.targets(full['facts'])
        self.assertIn('Def#getF7()', targets)
        self.assertNotIn('Api$ApiBuilder#f7(java.lang.String)', targets)
        self.assertNotIn('Api$ApiBuilder#build()', targets)

    def test_field_type_change_and_builder_removal_match_full_delta(self):
        base = self.analyze([self.fixture(SOURCE)], cache=True)
        changed = SOURCE.replace('String f20;', 'Integer f20;').replace('.f20("x")', '.f20(1)')
        for source in (changed, changed.replace('@lombok.Builder ', '')):
            context = self.fixture(source)
            delta = self.analyze([context], base['facts'], cache=True)
            full = self.analyze([context], cache=True)
            self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
            if '@lombok.Builder' in source:
                self.assertIn('Api$ApiBuilder#build()', self.targets(full['facts']))
            else:
                self.assertNotIn('Api$ApiBuilder#build()', self.targets(full['facts']))
            base = full


if __name__ == '__main__':
    unittest.main(verbosity=2)
