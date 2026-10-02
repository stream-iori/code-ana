#!/usr/bin/env python3
"""Frozen ASM semantic shapes and graph formal ownership/negative grammar checks."""
import hashlib,os,pathlib,subprocess,tempfile,unittest
import java_model_tests as models
REFERENCE_SHA='457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e'
class BinaryTypeGraphs(unittest.TestCase):
    def classpath(self):
        paths=[str(models.ROOT/'lang-adaptor/target/classes'),str(models.ROOT/'lang-adaptor/target/test-classes'),(models.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()]
        if os.environ.get('CODEANA_TEST_CLASSES'):paths.insert(0,os.environ['CODEANA_TEST_CLASSES'])
        return os.pathsep.join(paths)
    def test_formal_ownership_recursion_imports_unavailable_and_invalid_signatures(self):
        p=subprocess.run([str(models.JDK25/'bin/java'),'-cp',self.classpath(),'dev.codeana.analysis.BinarySignatureGraphInvariantProbe'],capture_output=True,text=True,timeout=30)
        self.assertEqual(p.returncode,0,p.stderr);self.assertIn('unavailable formal is explicit',p.stdout);self.assertEqual(p.stdout.count(' rejected '),3)
    def test_frozen_binary_signature_shapes(self):
        jar=models.ROOT.parent/'anatomist/target/anatomist.jar';self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(),REFERENCE_SHA)
        with tempfile.TemporaryDirectory(prefix='codeana-binary-graph-reference-') as temporary:
            cp=self.classpath()+os.pathsep+str(jar)
            source=models.ROOT/'lang-adaptor/src/frozen-test/java/dev/codeana/analysis/BinarySignatureGraphReferenceProbe.java'
            p=subprocess.run([str(models.JDK25/'bin/javac'),'-proc:none','-cp',cp,'-d',temporary,str(source)],capture_output=True,text=True,timeout=30);self.assertEqual(p.returncode,0,p.stderr)
            p=subprocess.run([str(models.JDK25/'bin/java'),'-cp',temporary+os.pathsep+cp,'dev.codeana.analysis.BinarySignatureGraphReferenceProbe'],capture_output=True,text=True,timeout=30);self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout.count(' equal'),6)
        self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(),REFERENCE_SHA)
if __name__=='__main__':unittest.main(verbosity=2)
