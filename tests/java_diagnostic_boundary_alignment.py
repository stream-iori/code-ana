#!/usr/bin/env python3
"""A diagnostic at the exclusive end cannot invalidate a receiver call."""
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok


class DiagnosticBoundaries(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def test_outer_missing_member_keeps_inner_binding_and_delta(self):
        source = '''class Holder {static Api current(){return null;}
static void read(){current().getValue(); current().absent();}}
class Api {@lombok.Getter String value;}'''
        context = self.context('app', lombok.Lombok.files(self, source), lombok='model')

        def verify(result, getter=True):
            facts = result['facts']
            by_id = {f['id']: f for f in facts}
            sites = [f for f in facts if f['kind'] == 'call_site']
            inner = [f for f in sites if f['name'] == 'current']
            self.assertEqual(len(inner), 2)
            for site in inner:
                self.assertEqual(site['status'], 'resolved')
                self.assertTrue(site['evidence_complete'])
                self.assertEqual(site['attributes']['binding_failure'], 'none')
                self.assertEqual(site['attributes']['diagnostic_codes'], [])
                targets = [f for f in facts if f['kind'] == 'call_target' and f['parent'] == site['id']]
                self.assertEqual([(by_id[f['target']]['signature'], f['status']) for f in targets],
                                 [('Holder#current()', 'resolved')])
            outer = next(f for f in sites if f['name'] == 'current().absent')
            self.assertEqual(outer['status'], 'unresolved')
            self.assertEqual(outer['attributes']['binding_failure'], 'missing_symbol')
            self.assertFalse([f for f in facts if f['kind'] == 'call_target' and f['parent'] == outer['id']])
            self.assertTrue([f for f in facts if f['kind'] == 'diagnostic' and f['name'].startswith('compiler.err.cant.resolve')])
            modeled = [f for f in facts if f['kind'] == 'call_target' and by_id[f['target']]['signature'] == 'Api#getValue()']
            self.assertEqual(len(modeled), int(getter))
            self.assertTrue(all(f['status'] == 'possible' and not f['evidence_complete'] for f in modeled))

        initial = self.analyze([context], cache=True)
        verify(initial)
        removed = self.context('app', lombok.Lombok.files(self, source.replace('@lombok.Getter ', '')), lombok='model')
        delta = self.analyze([removed], initial['facts'], cache=True)
        full = self.analyze([removed], cache=True)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        verify(full, False)


if __name__ == '__main__':
    unittest.main(verbosity=2)
