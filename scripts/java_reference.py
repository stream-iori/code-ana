"""Resolve an explicit frozen reference; never substitute the checkout's HEAD."""
from pathlib import Path
import re
import subprocess


def frozen_reference(path, commit):
    root = Path(path).expanduser().resolve()
    if not root.is_dir():
        raise ValueError('REFERENCE_REPOSITORY_UNAVAILABLE: ' + str(root))
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('REFERENCE_FULL_COMMIT_REQUIRED: ' + commit)
    result = subprocess.run(['git', '-C', str(root), 'cat-file', '-e', commit + '^{commit}'],
                            capture_output=True)
    if result.returncode:
        raise ValueError('REFERENCE_COMMIT_UNAVAILABLE: ' + str(root) + ' @ ' + commit)
    return root
