"""Verify a newly built oracle from the frozen Git tree, independent of product JARs."""
import json
import os
import pathlib

from acceptance_support import sha256

ROOT = pathlib.Path(__file__).resolve().parents[1]
COMMIT = 'dff2e3954096853352ec91fcbfc6b995fbd4f390'
TREE = 'b398211b39e5efb73d7e26669fd469bfdcc4904a'


def reference():
    manifest = pathlib.Path(os.environ.get('ANATOMIST_REFERENCE_MANIFEST', ROOT / 'target/frozen-reference/manifest.json')).resolve()
    proof = json.loads(manifest.read_text())
    if (proof.get('commit') != COMMIT or proof.get('tree') != TREE
            or proof.get('backend') != 'jvm' or proof.get('changed_inputs') != []
            or proof.get('execution', {}).get('status') != 'passed'
            or proof.get('execution', {}).get('exit_code') != 0):
        raise ValueError('FRESH_REFERENCE_BUILD_PROOF_REQUIRED')
    jar = manifest.parent / 'anatomist.jar'
    if sha256(jar) != proof.get('jar_sha256'):
        raise ValueError('FRESH_REFERENCE_JAR_DRIFT')
    source = manifest.parent / 'source'
    if not proof.get('sources'):
        raise ValueError('FRESH_REFERENCE_SOURCE_PROOF_REQUIRED')
    for name, digest in proof['sources'].items():
        path = (source / name).resolve()
        if not path.is_relative_to(source.resolve()) or sha256(path) != digest:
            raise ValueError('FRESH_REFERENCE_SOURCE_DRIFT: ' + name)
    for name, digest in proof['toolchain'].items():
        if sha256(name) != digest:
            raise ValueError('FRESH_REFERENCE_TOOLCHAIN_DRIFT: ' + name)
    return jar, proof
