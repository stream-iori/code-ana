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
import time
import unittest

import java_alignment as alignment
import java_functional_reference_alignment as reference
import java_model_tests as models


MODULES = (
    'java_annotation_alignment', 'java_framework_alignment',
    'java_xml_alignment', 'java_sofa_xml_alignment',
    'java_reflection_alignment', 'java_encoding_alignment',
    'java_module_signature_alignment', 'java_module_parallel_alignment',
    'java_module_overlay_alignment', 'java_dependency_view_alignment',
    'java_static_import_alignment',
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    parser.add_argument('--failfast', action='store_true')
    args = parser.parse_args()
    evidence = alignment.ROOT / 'target/java-alignment/evidence' / (
        'type-graph-consumers-' + ('native-' if args.native else 'jvm-') + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir()
    inputs, artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    original_context = models.Models.context
    original_analyze = models.Models.analyze
    created = 0

    def graph_context(self, name, files, **options):
        nonlocal created
        if options.get('type_graph') is False:
            raise AssertionError('TREE_CONTEXT_IN_GRAPH_CONSUMER_GATE')
        created += 1
        return original_context(self, name, files, **dict(options, type_graph=True))

    models.Models.context = graph_context
    if args.native:
        models.Models.analyze = reference.native_analyze
    modules = MODULES if args.native else MODULES + ('java_extension_alignment',)
    started = time.monotonic()
    log = evidence / 'regressions.log'
    try:
        # Import after installing the context factory: these fixtures bind aliases
        # to Models.context at module load time. No assertion/output conversion.
        suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromModule(importlib.import_module(name))
                                   for name in modules)
        with log.open('w') as stream:
            result = unittest.TextTestRunner(stream=stream, verbosity=2, failfast=args.failfast).run(suite)
    finally:
        models.Models.context, models.Models.analyze = original_context, original_analyze
    current, current_artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    report = dict(native=args.native, tests=result.testsRun, successful=result.wasSuccessful(),
                  graph_contexts=created, modules=modules, inputs=inputs, artifacts=artifacts,
                  changed_inputs=sorted(p for p in inputs.keys() | current.keys() if inputs.get(p) != current.get(p)),
                  changed_artifacts=sorted(p for p in artifacts.keys() | current_artifacts.keys()
                                           if artifacts.get(p) != current_artifacts.get(p)),
                  elapsed_seconds=round(time.monotonic() - started, 3),
                  log=str(log.relative_to(alignment.ROOT)), log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),
                  product_default_enabled=False, full_semantic_diff_accepted=False)
    report['all_ok'] = (result.wasSuccessful() and created > 0
                        and not report['changed_inputs'] and not report['changed_artifacts'])
    (evidence / 'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(evidence / 'receipt.json', flush=True)
    if not report['all_ok']:
        print(log.read_text()[-10000:])
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
