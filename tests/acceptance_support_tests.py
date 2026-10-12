import pathlib
import tempfile
import unittest

from acceptance_support import read_surefire, run_command, summarize


class ReceiptTests(unittest.TestCase):
    def test_only_complete_matching_success_passes(self):
        self.assertEqual(summarize(['a'], [{'id': 'a', 'status': 'passed'}], {'jar': 'x'}, {'jar': 'x'})['status'], 'passed')
        for status in ['failed', 'blocked', 'skipped']:
            self.assertEqual(summarize(['a'], [{'id': 'a', 'status': status, 'known_issue': True}], {}, {})['status'], 'failed')

    def test_missing_duplicate_zero_execution_and_artifact_drift_fail(self):
        success = {'id': 'a', 'status': 'passed'}
        for expected, cases, before, after in [
            (['a', 'b'], [success], {}, {}),
            (['a'], [success, success], {}, {}),
            (['a', 'a'], [success], {}, {}),
            ([], [], {}, {}),
            (['a'], [success], {'jar': 'old'}, {'jar': 'new'}),
        ]:
            self.assertEqual(summarize(expected, cases, before, after)['status'], 'failed')

    def test_xml_child_failure_and_skipped_are_not_hidden_by_suite_totals(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'TEST-suite.xml'
            path.write_text('<testsuite tests="2" failures="0"><testcase classname="C" name="ok"/><testcase classname="C" name="bad"><failure>assertion</failure></testcase></testsuite>')
            cases = read_surefire([path])
            self.assertEqual(summarize(['C#ok', 'C#bad'], cases, {}, {})['status'], 'failed')
            path.write_text('<testsuite><testcase classname="C" name="skip"><skipped/></testcase></testsuite>')
            self.assertEqual(read_surefire([path])[0]['status'], 'skipped')

    def test_timeout_nonzero_and_missing_command_never_pass(self):
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            for command, timeout, expected in [
                ([sys.executable, '-c', 'raise SystemExit(3)'], 5, 'failed'),
                ([sys.executable, '-c', 'import time; time.sleep(10)'], .05, 'failed'),
                (['/does/not/exist'], 5, 'blocked'),
            ]:
                self.assertEqual(run_command(command, pathlib.Path(tmp) / 'log', timeout)['status'], expected)

    def test_injected_parameter_types_do_not_hide_missing_or_duplicate_invocations(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'TEST-suite.xml'
            path.write_text('<testsuite><testcase classname="C" name="test(Path)"/></testsuite>')
            cases = read_surefire([path])
            self.assertEqual(cases[0]['reported_id'], 'C#test(Path)')
            self.assertEqual(summarize(['C#test'], cases, {}, {})['status'], 'passed')
            self.assertEqual(summarize(['C#test', 'C#other'], cases, {}, {})['status'], 'failed')
            self.assertEqual(summarize(['C#test'], cases + cases, {}, {})['status'], 'failed')


if __name__ == '__main__':
    unittest.main()
