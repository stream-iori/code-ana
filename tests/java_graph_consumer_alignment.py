#!/usr/bin/env python3
"""Run existing consumer expectations on graph facts without rewriting facts or baselines.

Legacy wire-format tests remain in test-java.sh; graph wire/cycle validation is
covered separately by java_graph_fact_alignment.py. This gate checks consumers,
including unchanged full/delta assertions, on the real graph compiler path.
"""
import argparse
import hashlib
import importlib
import json
import subprocess
import shutil
import time
import unittest

import java_alignment as alignment
import java_model_tests as models


MODULES = (
    'java_annotation_alignment', 'java_framework_alignment',
    'java_xml_alignment', 'java_sofa_xml_alignment',
    'java_reflection_alignment', 'java_encoding_alignment',
    'java_module_signature_alignment', 'java_module_parallel_alignment',
    'java_module_overlay_alignment', 'java_module_abi_boundary_alignment', 'java_dependency_view_alignment',
    'java_static_import_alignment',
)

BINDING_MODULES = (
    'java_binding_alignment', 'java_graph_call_alignment', 'java_diagnostic_boundary_alignment',
    'java_target_return_alignment', 'java_functional_output_alignment',
    'java_generic_reference_alignment', 'java_constructor_reference_alignment',
    'java_collector_alignment', 'java_cast_alignment', 'java_builder_chain_alignment',
    'java_supplemental_signature_alignment', 'java_diamond_alignment',
    'java_lambda_input_alignment', 'java_contextual_invocation_alignment',
    'java_expression_alignment', 'java_type_reference_alignment',
    'java_lombok_alignment', 'java_lombok_builder_alignment',
    'java_lombok_super_builder_alignment', 'java_signature_alignment',
    'java_cache_alignment', 'java_shared_cache_alignment', 'java_session_alignment',
)

# These tests assemble private JVM probes, not the product C ABI. They remain
# mandatory in the complete JVM gate; do not count them as native executions.
JVM_PRIVATE_MODULES = frozenset(('java_cache_alignment', 'java_shared_cache_alignment'))
JVM_PRIVATE_TESTS = frozenset((
    'java_type_reference_alignment.SourceTypeBudgets.test_budget_limits_one_owner_and_publishes_partial_coverage',
    'java_session_alignment.Sessions.test_phase_journal_is_bounded_monotonic_and_task_owned',
    'java_session_alignment.Sessions.test_large_class_path_lookup_has_stable_declaration_and_reference_identity',
))


def cases(suite):
    for case in suite:
        if isinstance(case, unittest.TestSuite):
            yield from cases(case)
        else:
            yield case


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    parser.add_argument('--failfast', action='store_true')
    parser.add_argument('--bindings', action='store_true', help='Run advanced binding, Lombok and cache consumers')
    args = parser.parse_args()
    evidence = alignment.ROOT / 'target/java-alignment/evidence' / (
        ('type-graph-bindings-' if args.bindings else 'type-graph-consumers-')
        + ('native-' if args.native else 'jvm-') + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir()
    original_context = models.Models.context
    original_analyze = models.Models.analyze
    created = 0
    requests = 0
    validated = 0

    def graph_context(self, name, files, **options):
        nonlocal created
        if options.get('type_graph') is False:
            raise AssertionError('TREE_CONTEXT_IN_GRAPH_CONSUMER_GATE')
        created += 1
        return original_context(self, name, files, **dict(options, type_graph=True))

    def graph_analyze(self, contexts, *arguments, **options):
        nonlocal requests, validated
        if not contexts or any(context.get('type_graph') is not True for context in contexts):
            raise AssertionError('NON_GRAPH_REQUEST_IN_GRAPH_CONSUMER_GATE')
        requests += 1
        analyze = reference.native_analyze if args.native else original_analyze
        result = analyze(self, contexts, *arguments, **options)
        # A private JVM probe may return its own diagnostic/model projection.
        # Every real Fact IR result uses the unchanged Rust publication validators.
        if 'mode' in result and 'facts' in result:
            baseline = arguments[0] if arguments else options.get('baseline')
            self.rust_validator = validator
            GraphFacts.validate_rust(self, contexts, result, baseline)
            validated += 1
        return result

    models.Models.context = graph_context
    models.Models.analyze = graph_analyze
    # This module imports binding fixtures and their method aliases. Both hooks
    # must be installed before importing it, including for native dispatch.
    import java_functional_reference_alignment as reference
    from java_graph_fact_alignment import GraphFacts
    compiled = subprocess.run(['cargo', 'build', '-p', 'codeana-model', '--example', 'validate_java_graph_facts', '--locked'],
                              cwd=alignment.ROOT, capture_output=True, text=True, timeout=180)
    if compiled.returncode:
        raise RuntimeError(compiled.stderr)
    # Workspace builds can replace the shared target output during a gate. Each
    # task executes and fingerprints its own immutable copy instead.
    validator = evidence / 'validate-java-graph-facts'
    shutil.copy2(alignment.ROOT / 'target/debug/examples/validate_java_graph_facts', validator)
    def probe_artifacts():
        artifacts = reference.probe_artifacts(args.native)
        artifacts[str(validator)] = reference.digest(validator)
        return artifacts

    inputs, artifacts = alignment.input_files(), probe_artifacts()
    modules = BINDING_MODULES if args.bindings else MODULES if args.native else MODULES + ('java_extension_alignment',)
    jvm_private_tests = []
    started = time.monotonic()
    log = evidence / 'regressions.log'
    try:
        # Import after installing the context factory: these fixtures bind aliases
        # to Models.context at module load time. No assertion/output conversion.
        suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromModule(importlib.import_module(name))
                                   for name in modules)
        if args.native and args.bindings:
            native_cases = []
            for case in cases(suite):
                identity = case.id()
                if identity.split('.')[0] in JVM_PRIVATE_MODULES or identity in JVM_PRIVATE_TESTS:
                    jvm_private_tests.append(dict(id=identity, reason='private JVM probe assembly',
                                                 required_gate='java-fixtures',
                                                 command=['tests/java_graph_consumer_alignment.py', '--bindings']))
                else:
                    native_cases.append(case)
            suite = unittest.TestSuite(native_cases)
        with log.open('w') as stream:
            result = unittest.TextTestRunner(stream=stream, verbosity=2, failfast=args.failfast).run(suite)
    finally:
        models.Models.context, models.Models.analyze = original_context, original_analyze
    current, current_artifacts = alignment.input_files(), probe_artifacts()
    report = dict(native=args.native, tests=result.testsRun, successful=result.wasSuccessful(),
                  graph_contexts=created, graph_requests=requests, modules=modules, inputs=inputs, artifacts=artifacts,
                  rust_validated_requests=validated,
                  jvm_private_tests=jvm_private_tests,
                  changed_inputs=sorted(p for p in inputs.keys() | current.keys() if inputs.get(p) != current.get(p)),
                  changed_artifacts=sorted(p for p in artifacts.keys() | current_artifacts.keys()
                                           if artifacts.get(p) != current_artifacts.get(p)),
                  elapsed_seconds=round(time.monotonic() - started, 3),
                  log=str(log.relative_to(alignment.ROOT)), log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),
                  product_default_enabled=False, full_semantic_diff_accepted=False)
    report['all_ok'] = (result.wasSuccessful() and created > 0 and requests > 0 and validated > 0
                        and not report['changed_inputs'] and not report['changed_artifacts'])
    (evidence / 'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(evidence / 'receipt.json', flush=True)
    if not report['all_ok']:
        print(log.read_text()[-10000:])
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
