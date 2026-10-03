#!/usr/bin/env python3
"""Execute binding regressions through the release Rust host and native C ABI."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import time
import unittest
import java_alignment as alignment
import java_functional_reference_alignment as reference
import java_rust_validator
from java_binding_alignment import Bindings
from java_graph_call_alignment import GraphCalls, ConcreteGraphCalls
from java_target_return_alignment import TargetReturns
from java_functional_output_alignment import FunctionalOutputs, SourceParameters
from java_generic_reference_alignment import GenericReferences
from java_constructor_reference_alignment import ConstructorReferences
from java_collector_alignment import Collectors
from java_cast_alignment import CastBindings
from java_builder_chain_alignment import BuilderChains
from java_supplemental_signature_alignment import SupplementalSignatures
from java_module_abi_boundary_alignment import ModuleBoundary
from java_diamond_alignment import Diamonds
from java_lambda_input_alignment import LambdaInputs
from java_contextual_invocation_alignment import ContextualInvocations
from java_type_reference_alignment import SourceTypeReferences
from java_encoding_alignment import Encodings
from java_diagnostic_boundary_alignment import DiagnosticBoundaries
from java_expression_alignment import Expressions
from java_static_import_alignment import StaticImports
from java_annotated_type_alignment import AnnotatedMirrors
from java_written_annotation_alignment import WrittenAnnotations
from java_module_annotation_alignment import ModuleAnnotations
from java_annotation_alignment import Annotations


def main():
    evidence = alignment.ROOT / 'target/java-alignment/evidence' / ('native-fixtures-' + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(exist_ok=False)
    # CastBindings calls the public Rust graph validator without running its script's
    # main. A clean native checkout must build and freeze that executable as well.
    validator = java_rust_validator.build(alignment.ROOT, evidence)
    validator_sha = reference.digest(validator)
    frozen = alignment.input_files()
    artifacts = reference.probe_artifacts(True)
    classes = (Bindings, GraphCalls, ConcreteGraphCalls, TargetReturns, FunctionalOutputs, SourceParameters,
               GenericReferences, ConstructorReferences, Collectors, CastBindings, BuilderChains, SupplementalSignatures, ModuleBoundary, Diamonds, LambdaInputs, ContextualInvocations, SourceTypeReferences, Encodings, DiagnosticBoundaries, Expressions, StaticImports, AnnotatedMirrors, WrittenAnnotations, ModuleAnnotations, Annotations)
    suite = unittest.TestSuite()
    for case in classes:
        case.analyze = reference.native_analyze
        case.rust_validator = validator
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(case))
    started = time.monotonic()
    log = evidence / 'regressions.log'
    with log.open('w') as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    current = alignment.input_files()
    current_artifacts = reference.probe_artifacts(True)
    drift = sorted(p for p in frozen.keys() | current.keys() if frozen.get(p) != current.get(p))
    artifact_drift = sorted(p for p in artifacts.keys() | current_artifacts.keys() if artifacts.get(p) != current_artifacts.get(p))
    receipt = dict(tests=result.testsRun, successful=result.wasSuccessful(), changed_inputs=drift,
                   artifacts=artifacts, changed_artifacts=artifact_drift, elapsed_seconds=round(time.monotonic()-started, 3),
                   log=str(log.relative_to(alignment.ROOT)), log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),
                   inputs=frozen, full_semantic_diff_accepted=False)
    receipt['all_ok'] = result.wasSuccessful() and not drift and not artifact_drift
    receipt['rust_validator_sha256'] = validator_sha
    receipt['rust_validator_changed'] = validator_sha != reference.digest(validator)
    receipt['all_ok'] = receipt['all_ok'] and not receipt['rust_validator_changed']
    (evidence/'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2)+'\n')
    print(str(evidence/'receipt.json'), flush=True)
    if not receipt['all_ok']:
        print(log.read_text()[-9000:])
    return 0 if receipt['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
