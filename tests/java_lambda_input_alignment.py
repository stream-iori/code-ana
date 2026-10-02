#!/usr/bin/env python3
"""Confirmed lambda inputs and target-neutral diamond evidence."""
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok
SOURCE = 'class Generated {@lombok.Getter String value; @lombok.Getter int index; @lombok.Getter Object key;}\nclass Tag {int getIndex(){return 0;}}\nclass Use {static boolean read(ThreadLocal<String> local){return java.util.Optional.ofNullable(local).map(v -> v.get().equals("x")).orElse(false);}\nstatic void block(java.util.List<String> texts){texts.forEach(v -> {System.out.println(v);});}\nstatic void nested(java.util.Map<Tag,Object> values){values.entrySet().stream().filter(e -> e.getValue()!=null).sorted(java.util.Comparator.comparingInt(e -> e.getKey().getIndex())).map(e ->e.getKey()).count();}}\nclass Envelope {@lombok.Getter java.util.Map<String,Object> extraContent;}\nclass OtherUse {static Object read(Envelope e){return java.util.Optional.ofNullable(e.getExtraContent().get("headers")).orElse(new java.util.HashMap<>());}}\n'
class LambdaInputs(unittest.TestCase):
    setUp=models.Models.setUp
    context=models.Models.context
    analyze=models.Models.analyze
    merged=models.Models.merged
    def fixture(self,source=SOURCE):return self.context('app',lombok.Lombok.files(self,source),lombok='model')
    def targets(self,result):
        by={f['id']:f for f in result['facts']}
        return [(by[f['target']]['signature'],f) for f in result['facts'] if f['kind']=='call_target']
    def test_jdk_lambdas_keep_original_bindings(self):
        pairs=self.targets(self.analyze([self.fixture()]))
        for sig in ('java.util.Optional#map(java.util.function.Function)','java.lang.Iterable#forEach(java.util.function.Consumer)','java.util.stream.Stream#filter(java.util.function.Predicate)','java.util.stream.Stream#sorted(java.util.Comparator)','java.util.Comparator#comparingInt(java.util.function.ToIntFunction)','java.util.stream.Stream#map(java.util.function.Function)'):
            found=[f for s,f in pairs if s==sig];self.assertEqual(len(found),1,sig);self.assertEqual(found[0]['status'],'resolved',sig)
    def test_object_target_keeps_concrete_javac_diamond(self):
        pairs=self.targets(self.analyze([self.fixture()]))
        self.assertIn(('java.util.Optional#orElse(java.lang.Object)','possible'),[(s,f['status']) for s,f in pairs])
        ctor=[f for s,f in pairs if s=='java.util.HashMap#<init>()'];self.assertEqual(len(ctor),1);self.assertEqual(ctor[0]['status'],'resolved')
    def test_type_edit_delta_and_unrelated_getter_removal(self):
        initial=self.analyze([self.fixture()],cache=True)
        changed=self.fixture(SOURCE.replace('String value;', 'Integer value;'))
        delta=self.analyze([changed],initial['facts'],cache=True);full=self.analyze([changed],cache=True)
        self.assertEqual(self.merged(initial['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
        removed=self.fixture(SOURCE.replace('@lombok.Getter ',''))
        delta=self.analyze([removed],full['facts'],cache=True);full_removed=self.analyze([removed],cache=True)
        self.assertEqual(self.merged(full['facts'],delta),sorted(full_removed['facts'],key=lambda f:f['id']))

if __name__=='__main__':
    unittest.main(verbosity=2)
