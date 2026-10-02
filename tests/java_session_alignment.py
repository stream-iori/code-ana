#!/usr/bin/env python3
"""Real Java 8 process reuse, isolation, and deterministic pool scheduling."""
import json
import os
import subprocess
import time
import unittest
import java_model_tests as model_tests


class Sessions(unittest.TestCase):
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze

    def contexts(self, count=8):
        # Same binary name with incompatible members detects javac context leakage.
        return [self.context('module' + str(i), {'Same.java':
                'class Same { int member' + str(i) + '(){return ' + str(i) + ';} '
                'void run(){System.out.println(member' + str(i) + '());} }'})
                for i in range(count)]

    def test_phase_journal_is_bounded_monotonic_and_task_owned(self):
        dependencies = (model_tests.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
        classpath = os.pathsep.join([str(model_tests.ROOT/'lang-adaptor/target'/p)
                                  for p in ('classes', 'test-classes')]+[dependencies])
        subprocess.run([str(model_tests.JDK25/'bin/java'), '-Xmx64m', '-cp', classpath,
                        'dev.codeana.analysis.CompilerPhaseProbe', str(self.root), str(model_tests.JDK8),
                        str(model_tests.ROOT/'lang-adaptor/target/compiler-bridge.jar')],
                       check=True, timeout=30)

    def test_serial_reuses_one_jvm_without_symbols_leaking(self):
        result = self.analyze(self.contexts())
        self.assertEqual(result['compiler_launches'], 1)
        self.assertEqual(result['compiler_requests'], 8)
        self.assertEqual(result['compiler_peak'], 1)
        self.assertFalse(any(f['kind'] == 'diagnostic' for f in result['facts']))
        self.assertEqual(len([f for f in result['facts'] if f['kind'] == 'declaration'
                            and f['name'].startswith('member')]), 8)

    def test_pool_one_two_four_eight_preserves_all_facts(self):
        contexts = self.contexts()
        baseline = self.analyze(contexts)
        for size in (2, 4, 8):
            with self.subTest(size=size):
                result = self.analyze(contexts, parallelism=size)
                self.assertEqual(result['facts'], baseline['facts'])
                self.assertEqual(result['compiler_launches'], size)
                self.assertEqual(result['compiler_peak'], size)
                self.assertEqual(result['compiler_requests'], 8)

    def test_reused_context_does_not_keep_prior_diagnostics(self):
        contexts = [self.context('bad', {'Same.java': 'class Same { void bad(){missing();} }'}),
                    self.context('good', {'Same.java': 'class Same { void good(){} }'})]
        result = self.analyze(contexts)
        self.assertEqual(result['compiler_launches'], 1)
        self.assertTrue(any(f['kind'] == 'diagnostic' for f in result['facts']))
        self.assertFalse(any(f['kind'] == 'diagnostic' and f['owner']['world'] == contexts[1]['world']
                             for f in result['facts']))

    def test_large_class_path_lookup_has_stable_declaration_and_reference_identity(self):
        context = self.context('large', {'Large.java':'class Large {'+
            ''.join(f'int m{i}(){{int x={i}; return x+x;}}' for i in range(10000))+'}'},
            model_record_limit=1000000, model_archive_bytes=512*1024*1024)
        request = dict(java_home=str(model_tests.JDK8), bridge=str(model_tests.ROOT/'lang-adaptor/target/compiler-bridge.jar'),
                       scratch=str(self.root), compiler_heap_bytes=256*1024*1024, contexts=[context])
        path = self.root/'request.json'
        path.write_text(json.dumps(request))
        dependencies = (model_tests.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
        classpath = os.pathsep.join([str(model_tests.ROOT/'lang-adaptor/target'/p) for p in ('classes','test-classes')]+[dependencies])
        process = subprocess.run([str(model_tests.JDK25/'bin/java'), '-cp', classpath,
                                  'dev.codeana.analysis.CompilerBudgetProbe', str(path)], capture_output=True, text=True, timeout=60)
        self.assertEqual(process.returncode, 0, process.stderr)
        counts = json.loads(process.stdout)
        self.assertEqual(counts['locals'], 10000)
        self.assertEqual(counts['references'], 20000)
        self.assertGreater(counts['syntax'], 100000)
        self.assertFalse(list(self.root.glob('compiler-*')))
        self.assertFalse(list(self.root.rglob('*.class')))

    def test_context_timeout_reaps_owned_javac_and_cleans_task_files(self):
        context = self.context('timeout', {'A.java':'class A {}'})
        for seconds in (0, 3601, 1.5):
            self.mutate_request = lambda request, value=seconds: request.update(compiler_timeout_seconds=value)
            with self.subTest(seconds=seconds), self.assertRaisesRegex(AssertionError, 'COMPILER_TIMEOUT_BUDGET|JSON_OBJECT_END'):
                self.analyze([context])
        # A fixture-owned Java 8 session that never acknowledges any command forces the deadline.
        source = self.root / 'CompilerBridge.java'
        source.write_text('package dev.codeana.compiler; public class CompilerBridge { '
                          'public static void main(String[] args) throws Exception { Thread.sleep(60000); } }')
        classes = self.root / 'timeout-classes'
        classes.mkdir()
        subprocess.run([str(model_tests.JDK8/'bin/javac'), '-d', str(classes), str(source)], check=True, capture_output=True)
        bridge = self.root / 'timeout-bridge.jar'
        subprocess.run([str(model_tests.JDK8/'bin/jar'), 'cf', str(bridge), '-C', str(classes), '.'], check=True, capture_output=True)
        import shutil
        shutil.rmtree(classes)
        self.mutate_request = lambda request: request.update(compiler_timeout_seconds=1, bridge=str(bridge))
        started = time.monotonic()
        with self.assertRaisesRegex(AssertionError, 'COMPILER_TIMEOUT'):
            self.analyze([context])
        self.assertLess(time.monotonic()-started, 10)
        self.assertFalse(list(self.root.glob('compiler-*')))
        processes = subprocess.run(['/bin/ps', '-axo', 'command='], capture_output=True, text=True, check=True).stdout
        self.assertNotIn(str(bridge), processes)

    def test_invalid_source_encoding_reports_original_hash_path_and_cleans_child(self):
        context = self.context('encoding', {'A.java': 'class A {}'})
        source = self.root/'encoding/A.java'
        data = 'class A {} // 单元测试'.encode('gbk')
        source.write_bytes(data)
        context['sources'][0]['hash'] = model_tests.sha(data)
        facts = self.analyze([context])['facts']
        errors = [f for f in facts if f['kind']=='diagnostic' and f['name']=='SOURCE_ENCODING_INVALID']
        self.assertEqual(len(errors),1)
        self.assertEqual(errors[0]['attributes']['context'],'encoding@MAIN')
        self.assertEqual(errors[0]['source'],'encoding/A.java')
        self.assertEqual(errors[0]['attributes']['encoding'],'UTF-8')
        self.assertEqual(errors[0]['attributes']['sha256'],model_tests.sha(data))
        self.assertTrue([f for f in facts if f['kind']=='coverage' and f['status']=='partial'])
        self.assertEqual(source.read_bytes(), data)
        self.assertFalse(list(self.root.glob('compiler-*')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
