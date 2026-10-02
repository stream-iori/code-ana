#!/usr/bin/env python3
"""Frozen-reference expression recovery boundaries, including a real iopengw Builder shape."""
import unittest

import java_model_tests as models
import java_binding_alignment as bindings
import java_lombok_builder_alignment as builders


class Expressions(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged
    controls = builders.Builders.controls
    fixture = builders.Builders.fixture
    recovered = bindings.Bindings.recovered

    def source(self, expression):
        return '''@lombok.Builder class Response {String name; String region; boolean success;}
          class Proxy {@lombok.Getter String name; @lombok.Getter int cost;
            @lombok.Getter boolean fail; @lombok.Getter Integer boxed;
            @lombok.Getter String[] names; @lombok.Getter Other other;}
          class Other {} class Use {Response read(Proxy proxy,boolean flag){return ''' + expression + ';}}'

    def targets(self, result):
        return {signature for name, signature, fact in self.recovered(result['facts'])}

    def test_nullable_conditional_keeps_remaining_builder_chain(self):
        for argument in ('proxy != null ? proxy.getName() : null',
                         'proxy == null ? null : proxy.getName()',
                         'flag ? proxy.getName() : "default"'):
            with self.subTest(argument=argument):
                source = self.source('Response.builder().name(' + argument + ').region("r").build()')
                result = self.analyze([self.fixture(source)])
                self.assertTrue({'Response#builder()', 'Response$ResponseBuilder#name(java.lang.String)',
                    'Response$ResponseBuilder#region(java.lang.String)', 'Response$ResponseBuilder#build()'}
                    <= self.targets(result), self.targets(result))
                self.assertTrue(all(not fact['evidence_complete'] and fact['status'] == 'possible'
                    for _, _, fact in self.recovered(result['facts'])))

    def test_concatenation_and_boolean_operands_feedback_to_model_calls(self):
        arguments = [('name', 'proxy.getName() + ":" + proxy.getCost()', 'java.lang.String'),
                     ('success', 'proxy.getCost() > 0 && !proxy.isFail()', 'boolean'),
                     ('success', 'proxy.getBoxed() <= proxy.getCost() || proxy.isFail()', 'boolean'),
                     ('success', 'proxy.getName() != null', 'boolean')]
        for method, argument, parameter in arguments:
            with self.subTest(argument=argument):
                result = self.analyze([self.fixture(self.source(
                    'Response.builder().' + method + '(' + argument + ').build()'))])
                self.assertIn('Response$ResponseBuilder#' + method + '(' + parameter + ')', self.targets(result))
                self.assertIn('Response$ResponseBuilder#build()', self.targets(result))

    def test_unknown_and_unsupported_branch_types_do_not_produce_targets(self):
        arguments = ['flag ? proxy.getName() : proxy.getOther()',
                     'proxy.getCost() ? proxy.getName() : null',
                     'flag ? proxy.getNames() : proxy.getNames()',
                     'proxy.getCost() + proxy.getCost()',
                     'proxy.getName() && proxy.isFail()']
        for argument in arguments:
            with self.subTest(argument=argument):
                result = self.analyze([self.fixture(self.source(
                    'Response.builder().name(' + argument + ').build()'))])
                self.assertNotIn('Response$ResponseBuilder#name(java.lang.String)', self.targets(result))
                self.assertNotIn('Response$ResponseBuilder#build()', self.targets(result))

    def test_cache_delta_and_getter_deletion_do_not_keep_expression_guesses(self):
        source = self.source('Response.builder().name(proxy != null ? proxy.getName() : null).build()')
        initial = self.analyze([self.fixture(source)], cache=True)
        self.assertIn('Response$ResponseBuilder#build()', self.targets(initial))
        changed = self.fixture(source.replace('String region;', 'String region; int marker;'))
        delta = self.analyze([changed], initial['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda fact: fact['id']))
        removed = self.fixture(source.replace('@lombok.Getter String name;', 'String name;'))
        delta = self.analyze([removed], full['facts'], cache=True)
        rebuilt = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(full['facts'], delta), sorted(rebuilt['facts'], key=lambda fact: fact['id']))
        self.assertNotIn('Response$ResponseBuilder#build()', self.targets(rebuilt))


if __name__ == '__main__':
    unittest.main(verbosity=2)
