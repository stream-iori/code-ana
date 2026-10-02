"""Auxiliary-source declaration views must preserve complete published evidence."""
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok


class DependencyViews(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged
    controls = lombok.Lombok.controls

    def test_auxiliary_locals_are_not_exported_but_framework_member_headers_remain(self):
        parent = self.context('library', {'Hidden.java': '''class Hidden {
            int count; int read(int input){int local=input; return local+count;}
            void close(){} java.util.function.Supplier<Integer> factory(){return () -> count;}
        }'''})
        context = self.context('app', {'Use.java':'class Use { int run(){return new Hidden().read(3);} }'})
        context['sources'] = [dict(s,publish=False) for s in parent['sources']] + context['sources']
        facts = self.analyze([context])['facts']
        hidden = [f for f in facts if f['kind']=='external_symbol' and f['attributes'].get('source_origin')]
        self.assertTrue({'Hidden', 'Hidden#count', 'Hidden#read(int)', 'Hidden#close()'} <= {f['signature'] for f in hidden})
        self.assertFalse([f for f in hidden if f['attributes'].get('element_kind') in
                          ('PARAMETER','LOCAL_VARIABLE','INITIALIZER','LAMBDA','METHOD_REF')])
        self.assertTrue(any(f['kind']=='call_target' and f['target'] in {d['id'] for d in hidden}
                            for f in facts))
        changed = self.context('app', {'Use.java':'class Use { int run(){return new Hidden().read(4);} }'})
        changed['sources'] = context['sources'][:-1] + changed['sources']
        self.assertEqual(self.merged(facts,self.analyze([changed],facts)),
                         sorted(self.analyze([changed])['facts'],key=lambda f:f['id']))

    def fixture(self, value='7'):
        files = lombok.Lombok.files(self, '@lombok.Data class Hidden { final int count='+value+'; '
                                   'java.util.ArrayList<String> values=new java.util.ArrayList<>(); '
                                   'int size(){return values.size();} }')
        files.pop('App.java')
        files['Hidden.java'] = '@lombok.Data class Hidden { final int count='+value+'; '
        files['Hidden.java'] += 'java.util.ArrayList<String> values=new java.util.ArrayList<>(); int size(){return values.size();} }'
        files.update({f'H{i}.java':f'class H{i} {{ int run(){{int v=0;'+''.join(f'v+={j};' for j in range(150))+'return v;} }' for i in range(40)})
        parent = self.context('library', files)
        current = self.context('app', {'Use.java':'class Use { int run(){return new Hidden().getCount();} }'}, lombok='model')
        current['sources'] = [dict(s,publish=False) for s in parent['sources']] + current['sources']
        current['digest'] = models.sha(models.json.dumps(current['sources'],sort_keys=True).encode())
        return self.controls(current, {'lombok.config':'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})

    def test_auxiliary_body_elision_preserves_external_recovery_lombok_and_delta(self):
        context = self.fixture()
        expected = self.analyze([context | dict(full_auxiliary_model=True)])['facts']
        compact = self.analyze([context])['facts']
        self.assertEqual(expected, compact)
        hidden_ctor = next(f for f in compact if f['signature']=='Hidden#<init>()')
        self.assertEqual(hidden_ctor['origin'], 'lombok_model')
        self.assertTrue(any(f['signature']=='java.util.ArrayList#size()' for f in compact))
        changed = self.fixture('9')
        self.assertEqual(self.merged(compact,self.analyze([changed],compact)),
                         sorted(self.analyze([changed | dict(full_auxiliary_model=True)])['facts'],key=lambda f:f['id']))

    def test_compiler_payload_is_bounded_by_published_bodies(self):
        context = self.fixture()
        self.probe = 'dev.codeana.analysis.DependencyViewProbe'
        original = self.analyze([context | dict(full_auxiliary_model=True)])
        compact = self.analyze([context])
        self.assertLess(compact['syntax'], original['syntax']//10)
        self.assertLess(compact['observations'], original['observations']//10)
        self.assertLess(compact['bytes'], original['bytes']//2)


if __name__ == '__main__':
    unittest.main(verbosity=2)
