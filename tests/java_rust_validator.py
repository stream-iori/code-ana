"""Build and freeze a current validator, independently of Cargo's shared example alias."""
import atexit
import pathlib
import shutil
import subprocess
import tempfile

_temporary = None
_validator = None


def build(root, evidence):
    evidence = pathlib.Path(evidence)
    target = evidence / 'rust-validator-build'
    target.mkdir(exist_ok=False)
    with (evidence / 'rust-validator-build.log').open('wb') as log:
        subprocess.run(['cargo', 'build', '--target-dir', str(target), '-p',
                        'codeana-model', '--example', 'validate_java_graph_facts', '--locked'],
                       cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    validator = evidence / 'validate_java_graph_facts'
    shutil.copy2(target / 'debug/examples/validate_java_graph_facts', validator)
    return validator


def default(root):
    global _temporary, _validator
    if _validator is None:
        _temporary = tempfile.TemporaryDirectory(prefix='codeana-current-fact-validator-')
        atexit.register(_temporary.cleanup)
        _validator = build(root, pathlib.Path(_temporary.name))
    return _validator
