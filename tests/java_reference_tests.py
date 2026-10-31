#!/usr/bin/env python3
"""Reference selection must use an existing full commit, never HEAD fallback."""
import pathlib
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
from java_reference import frozen_reference

class FrozenReference(unittest.TestCase):
    def test_missing_repo_and_commit_fail_explicitly(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            with self.assertRaisesRegex(ValueError, 'REFERENCE_REPOSITORY_UNAVAILABLE'):
                frozen_reference(root / 'missing', '0' * 40)
            subprocess.run(['git', '-C', str(root), 'init', '-q'], check=True)
            with self.assertRaisesRegex(ValueError, 'REFERENCE_COMMIT_UNAVAILABLE'):
                frozen_reference(root, '0' * 40)
            with self.assertRaisesRegex(ValueError, 'REFERENCE_FULL_COMMIT_REQUIRED'):
                frozen_reference(root, 'HEAD')

if __name__ == '__main__': unittest.main()
