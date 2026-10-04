#!/usr/bin/env python3
"""An actual exhausted task heap must return a terminal error and reap its javac child."""
import json
import os
import subprocess
import unittest
import java_model_tests as models


class NativeHeap(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context

    def test_real_heap_exhaustion_has_terminal_state_and_cleanup(self):
        ctx = self.context('heap', {f'C{i}.java': 'class C'+str(i)+' { '+''.join(f'int value{j}(){{return {i+j};}}' for j in range(8))+' }' for i in range(1000)})
        request = dict(version=5, facts=True, full=True, java_home=str(models.JDK8),
                       bridge=str(models.ROOT/'lang-adaptor/target/compiler-bridge.jar'), scratch=str(self.root),
                       compiler_heap_bytes=256*1024*1024, contexts=[ctx], parallelism=1, signature_cache=False)
        path = self.root/'request.json'
        path.write_text(json.dumps(request))
        dependencies = (models.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
        classpath = os.pathsep.join([str(models.ROOT/'lang-adaptor/target'/d) for d in ('classes','test-classes')] + [dependencies])
        result = subprocess.run([str(models.JDK25/'bin/java'), '-Xmx6m', '-cp', classpath,
                                 'dev.codeana.bridge.NativeTaskProbe', str(path)], capture_output=True, text=True, timeout=150)
        self.assertEqual(result.returncode, 0, result.stdout+'\n'+result.stderr)
        receipt=json.loads(result.stdout)
        self.assertEqual(receipt['error'],'NATIVE_HEAP_EXHAUSTED')
        if 'context' in receipt:
            self.assertEqual(receipt['context'],'heap@MAIN')
            self.assertIn(receipt['phase'],('compiler_model','compiler_model_decode','module_prepare','binary_enrich','model_extensions','call_recovery','reflection','fact_extract','java_extensions','file_cache','module_cache','task_accumulate','project_stage_prepare','project_extensions','result_finalize'))
        self.assertFalse(list(self.root.glob('compiler-*')))
        self.assertFalse(list(self.root.rglob('*.class')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
