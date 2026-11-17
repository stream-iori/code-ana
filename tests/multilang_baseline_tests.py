"""R0 receipts must distinguish failure and timeout from a successful baseline."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'multilang_baseline', Path(__file__).resolve().parents[1] / 'scripts/multilang-baseline.py')
BASELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASELINE)


class BaselineTests(unittest.TestCase):
    def test_terminal_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in ('AGENTS.md', 'scripts/test-java.sh', 'scripts/build-java-jvm.sh',
                         'lang-java-adaptor/.sdkmanrc'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            binary = root / 'bin'
            binary.mkdir()
            command = binary / 'just'
            def capture(argv, env):
                return 'rustc 1.96.0 (fixture)' if argv[0] == 'rustc' else 'fixture'
            with patch.object(BASELINE, 'ROOT', root), patch.object(BASELINE, 'capture', capture), \
                    patch.dict(os.environ, {'PATH': str(binary) + os.pathsep + os.environ['PATH']}):
                for name, body, timeout, expected in (
                    ('success', 'exit 0', '5', 'passed'),
                    ('failure', 'exit 7', '5', 'failed'),
                    ('timeout', 'sleep 30', '0.1', 'failed'),
                ):
                    with self.subTest(name=name):
                        command.write_text('#!/bin/sh\n' + body + '\n')
                        command.chmod(0o755)
                        evidence = root / 'target' / name
                        with patch.object(sys, 'argv', ['baseline', '--evidence', str(evidence),
                                                       '--timeout', timeout]):
                            self.assertEqual(BASELINE.main(), 0 if expected == 'passed' else 1)
                        receipt = json.loads((evidence / 'receipt.json').read_text())
                        self.assertEqual(receipt['status'], expected)
                        self.assertEqual(receipt['commands'][0]['sha256'], BASELINE.sha(evidence / 'java.log'))
                        if name == 'timeout':
                            self.assertIn('TimeoutExpired', receipt['error'])
                        if name == 'failure':
                            self.assertEqual(receipt['commands'][0]['exit_code'], 7)
                        with patch.object(sys, 'argv', ['baseline', '--evidence', str(evidence)]):
                            with self.assertRaises(FileExistsError):
                                BASELINE.main()


if __name__ == '__main__':
    unittest.main()
