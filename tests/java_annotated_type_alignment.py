#!/usr/bin/env python3
"""SDK8 annotated mirrors select accessors by TypeKind, with incremental/native parity."""
import unittest
import java_model_tests as m
SOURCE = """import java.lang.annotation.*;
@Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface Mark {}
class Api {
 @Mark String value(@Mark String text){return text;}
 @Mark int number(@Mark int x){return x;}
 String @Mark [] array(String @Mark [] values){return values;}
 void read(){value("x").length();number(1);array(new String[]{"x"})[0].length();}
}"""
class AnnotatedMirrors(unittest.TestCase):
 setUp=m.Models.setUp
 context=m.Models.context
 analyze=m.Models.analyze
 merged=m.Models.merged
 def test_annotated_functional_target_keeps_lambda_and_member_reference(self):
  source='''import java.lang.annotation.*;
   import java.util.function.Function;
   @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface Mark {}
   class Api {
    String run(@Mark Function<String,String> fn){return fn.apply("x");}
    String text(String x){return x;}
    void read(){run(x -> x).length();run(this::text).length();}
   }'''
  for graph in (False,True):
   with self.subTest(graph=graph):
    result=self.analyze([self.context('app',{'Api.java':source},type_graph=graph)])
    targets=[f for f in result['facts'] if f['kind']=='call_target']
    self.assertEqual(len(targets),6)
    self.assertTrue(all(f['status']=='resolved' and f['evidence_complete'] for f in targets))
 def test_annotated_declared_primitive_and_array_selected_executables(self):
  for graph in (False,True):
   with self.subTest(graph=graph):
    context=self.context('app',{'Api.java':SOURCE},type_graph=graph)
    result=self.analyze([context],cache=True)
    targets=[f for f in result['facts'] if f['kind']=='call_target']
    self.assertEqual(len(targets),5)
    self.assertTrue(all(f['status']=='resolved' and f['evidence_complete'] for f in targets))
    changed=self.context('app',{'Api.java':SOURCE.replace('return text;','return null;')},type_graph=graph)
    delta=self.analyze([changed],result['facts'],cache=True)
    full=self.analyze([changed],cache=True)
    self.assertEqual(self.merged(result['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
if __name__=='__main__':unittest.main(verbosity=2)
