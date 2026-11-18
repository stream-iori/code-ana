"""Product gates declare backend scope and never certify skipped requirements."""
import contextlib
import io
import os
import unittest
from backend_test_support import required_backends, run_cases, select_cases

class BackendGates(unittest.TestCase):
    def test_matrix_scope_selects_actual_single_pair_and_triple_cases(self):
        from multilang_analysis_e2e import cases
        for backends, count in [({'rust'},1),({'python'},3),({'rust','python'},6),({'java','rust','python'},10),({'java','rust','python','typescript'},16),({'typescript'},0)]:
            selected, excluded = cases(backends)
            self.assertEqual(len(selected),count)
            self.assertEqual(len(selected)+len(excluded),16)
            self.assertTrue(all(required_backends(test)<=backends for test in selected))

    def test_prepared_java_requirements_are_excluded_or_executed_never_skipped(self):
        import prepared_inputs_e2e as prepared
        from java_jvm_product_gate import flatten
        tests = list(flatten(unittest.defaultTestLoader.loadTestsFromTestCase(prepared.PreparedInputs)))
        selected, excluded = select_cases(tests, {'rust','python'})
        self.assertEqual(len(excluded),4)
        self.assertEqual(len(selected),15)
        self.assertTrue(all('java' not in required_backends(test) for test in selected))
        all_cases, excluded = select_cases(tests, {'java','rust','python'})
        self.assertEqual(len(all_cases),19);self.assertFalse(excluded)
        for test in all_cases:
            self.assertFalse(getattr(getattr(test,test._testMethodName),'__unittest_skip__',False),test.id())

    def test_unexpected_skip_fails_a_declared_product_gate(self):
        class MissingBackend(unittest.TestCase):
            @unittest.skip('backend missing')
            def test_required(self):pass
        with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
            status=run_cases([MissingBackend('test_required')],['java'],{})
        self.assertEqual(status,1)

if __name__=='__main__':unittest.main(verbosity=2)
