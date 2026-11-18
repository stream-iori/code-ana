#!/usr/bin/env python3
"""Build locked Go fixtures and require every detached semantic truth test.

This gate tests GRA1 row projection but does not create an SDK or claim storage/product integration.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    'TestSemanticDeclarationTablePreservesMergedSpacesAndOverloadProvenance',
    'TestPublicProjectionContainsDeclarationsAndExactCrossTagCallEndpoints',
    'TestSemanticCallsSeparateJSBindingSignaturesExternalAndUnknown',
    'TestAuxiliaryImplementationBodiesCannotBecomePublishedEndpoints',
    'TestSemanticStandardLibraryTargetsRemainExternalAndVersioned',
    'TestFilteredEmptySemanticsNeverHidesAFailedFrozenView',
    'TestSemanticIdentitiesAndCoordinatesIgnoreCaptureRootAndScratch',
    'TestSemanticExtractionKeepsSeedAndReturnsNoPartialFactsOnRefusal',
    'TestSemanticGlobalMergesShadowingVisibilityAndEscapedNames',
    'TestNamedDeclarationIdentitySurvivesBodyAndUnrelatedPrefixEdits',
    'TestSemanticReferenceRolesUseSyntaxAndCompilerSymbols',
    'TestAccessorReferencesAndJSXNamesKeepTheirOwnRoles',
    'TestSemanticSideEffectImportsAndReexportsKeepModuleEvidence',
    'TestTypeAndNamespaceMeaningsDoNotBecomeValueReads',
    'TestSourceCoordinatesUseRawUTF8AndUTF16Columns',
    'TestSourceIndexFundsAllocationAndLookupBeforeWork',
    'TestCoordinateLookupCannotRunAfterItsWorkAllowanceIsConsumed',
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--go-home', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    if not evidence.is_relative_to(ROOT/'target') or evidence.exists():
        parser.error('new evidence directory under repository target required')
    result = subprocess.run([
        sys.executable, str(ROOT/'scripts/build-typescript-native.py'),
        '--source', str(args.source.resolve()), '--go-home', str(args.go_home.resolve()),
        '--evidence', str(evidence), '--output', str(evidence/'unused-sdk'), '--prepare-only',
    ], cwd=ROOT, check=False)
    if result.returncode:
        return result.returncode
    receipt = json.loads((evidence/'receipt.json').read_text())
    assert receipt['status'] == 'prepared' and receipt['sdk_created'] is False
    command = next(command for command in receipt['commands'] if command['name'] == 'go-tests')
    log = evidence/command['log']
    assert command['exit_code'] == 0 and sha(log) == command['sha256']
    passed = Counter(re.findall(r'^--- PASS: (Test\w+) \(', log.read_text(), re.MULTILINE))
    assert all(passed[name] == 1 for name in REQUIRED), dict(passed)
    for path in (ROOT/'lang-typescript-adaptor/overlay/engine').rglob('*'):
        if path.is_file():
            key = 'codeana/'+str(path.relative_to(ROOT/'lang-typescript-adaptor/overlay'))
            assert sha(path) == receipt['source_files'][key], key
    proof = {
        'stage': 'T4 detached declaration and binding truth', 'status': 'passed',
        'executed_count': len(REQUIRED), 'required_tests': sorted(REQUIRED),
        'source_files': receipt['source_files'], 'go_tests_sha256': sha(log),
        'build_receipt_sha256': sha(evidence/'receipt.json'),
        'gate_sha256': sha(Path(__file__)), 'T4_complete': False,
        'public_graph_projection': True, 'storage_query': False, 'product_indexing': False,
        'sdk_created': False, 'native_accepted': False,
        'release_accepted': False, 'performance_accepted': False,
    }
    (evidence/'semantic-receipt.json').write_text(json.dumps(proof, indent=2)+'\n')
    print(json.dumps({key: proof[key] for key in ('status', 'executed_count', 'sdk_created')}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
