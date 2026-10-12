#!/usr/bin/env python3
"""Build a fresh, independent JVM oracle from the pinned committed source tree."""
import argparse
import hashlib
import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import identities, run_command, sha256

COMMIT = 'dff2e3954096853352ec91fcbfc6b995fbd4f390'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=pathlib.Path, default=ROOT.parent / 'anatomist')
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'target/frozen-reference')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source = output / 'source'
    source.mkdir()
    archive = subprocess.check_output(['git', '-C', str(args.source), 'archive', COMMIT])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tree:
        tree.extractall(source, filter='data')
    before = identities(sorted(path for path in source.rglob('*') if path.is_file()))
    host = pathlib.Path(os.environ.get('CODEANA_JAVA_HOST_HOME', pathlib.Path.home() / '.sdkman/candidates/java/25.3.4+1.r25-graal'))
    env = dict(os.environ, JAVA_HOME=str(host), PATH=str(host / 'bin') + os.pathsep + os.environ['PATH'])
    result = run_command(['mvn', '-q', '-Dmaven.test.skip=true', '-Djacoco.skip=true', 'package'],
                         output / 'build.log', 1800, cwd=source, env=env)
    receipt = dict(commit=COMMIT, tree=subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', COMMIT + '^{tree}'], text=True).strip(),
                   backend='jvm', execution=result, sources={str(pathlib.Path(path).relative_to(source)): digest for path, digest in before.items()},
                   changed_inputs=[path for path, digest in before.items() if sha256(path) != digest],
                   toolchain=identities([host / 'release', host / 'bin/java', host / 'bin/javac']))
    if result['status'] == 'passed' and not receipt['changed_inputs']:
        shutil.copy2(source / 'target/anatomist.jar', output / 'anatomist.jar')
        receipt['jar_sha256'] = sha256(output / 'anatomist.jar')
    else:
        receipt['execution']['status'] = 'failed'
    (output / 'manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'status': receipt['execution']['status'], 'commit': COMMIT, 'jar_sha256': receipt.get('jar_sha256')}))
    return 0 if receipt['execution']['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
