#!/usr/bin/env python3
"""Original text-search limits through the real Rust CLI and daemon."""
import unittest
import subprocess
import rust_e2e


class TextSearchProduct(rust_e2e.Product):
    def test_upstream_closed_text_output_does_not_hang_or_stop_daemon(self):
        (self.project / 'many.txt').write_text('hit\n' * 10000)
        command = [str(rust_e2e.BIN / 'codeana-cli'), '--home', str(self.home),
                   '--project', str(self.project), 'search-text', 'hit', '--path', 'many.txt',
                   '--max-records', '10000', '--max-output-bytes', '8388608']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        process.stdout.close()
        try:
            self.assertNotEqual(process.wait(timeout=5), 0)
            self.assertIsNone(self.daemon.poll(), self.daemon_log())
            rows, _ = self.cli('search-text', 'missing', '--path', 'many.txt')
            self.assertEqual(rows, [])
        finally:
            if process.poll() is None:
                process.kill(); process.wait(timeout=5)
            process.stderr.close()

    def test_upstream_text_limit_retains_cursor_and_invalid_regex_without_files(self):
        source = self.project / 'many.txt'
        source.write_text('hit\nhit\nhit\n')
        rows, terminal = self.cli('search-text', 'hit', '--path', 'many.txt', '--max-records', '1', '--context', '1')
        self.assertEqual(len(rows), 1)
        self.assertTrue(terminal['cursor'])
        self.assertFalse(terminal['evidence']['enumeration'])
        self.assertEqual(rows[0]['context_after'], [{'line': 2, 'text': 'hit'}])
        missing, _ = self.cli('search-text', 'missing', '--path', 'many.txt')
        self.assertEqual(missing, [])
        for path in self.project.rglob('*'):
            if path.is_file() and '.code-ana' not in path.parts:
                path.unlink()
        _, rejected = self.cli('search-text', '[', '--regex', code=1)
        self.assertIn('TEXT_REGEX_INVALID', rejected['message'])


def load_tests(loader, tests, pattern):
    return unittest.TestSuite([TextSearchProduct(name) for name in
        loader.getTestCaseNames(TextSearchProduct) if name.startswith('test_upstream_')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
