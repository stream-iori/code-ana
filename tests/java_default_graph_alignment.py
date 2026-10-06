#!/usr/bin/env python3
"""Default graph mode and atomic bundle/request/compiler version rejection."""
import argparse
import json
import pathlib
import shutil
import subprocess
import time
import unittest

import java_alignment as alignment
import java_functional_reference_alignment as reference
import java_model_tests as models
from java_graph_fact_alignment import GraphFacts


class DefaultGraphs(GraphFacts):
    def analyze(self, contexts, baseline=None, **options):
        # Exercise absence of the internal graph override. Never rewrite output or baselines.
        contexts = [dict(context) for context in contexts]
        for context in contexts:
            if context.get('type_graph') is True:
                context.pop('type_graph')
        return super().analyze(contexts, baseline, **options)

    def test_default_binding_and_type_schemas_and_invalid_mode(self):
        context = self.context('default', {'Use.java': 'class Use { String read(){return String.valueOf(1);} }'})
        self.assertNotIn('type_graph', context)
        result = self.analyze([context])
        self.assertTrue([f for f in result['facts'] if f['relation'] == 'java.type' and f['attributes']['schema_version'] == 3])
        self.assertTrue([f for f in result['facts'] if f['relation'] == 'java.binding' and f['attributes']['schema_version'] == 4])
        for value in ('true', 0, None):
            with self.subTest(value=value):
                analyze = reference.native_analyze if self.native else models.Models.analyze
                with self.assertRaisesRegex(AssertionError, 'JAVA_TYPE_GRAPH_MODE'):
                    analyze(self, [dict(context, type_graph=value)])


class NativeVersions(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    def request(self, context):
        return dict(version=6, facts=True, full=True, baseline=[], contexts=[context],
                    java_home=str(models.JDK8), bridge=str(models.ROOT/'lang-adaptor/target/compiler-bridge.jar'),
                    scratch=str(self.root))

    def run_probe(self, request, library=None):
        path = self.root/'version-request.json'
        path.write_text(json.dumps(request))
        return subprocess.run([str(models.ROOT/'target/release/codeana-java-worker'),
                               str(library or models.ROOT/'lang-adaptor/target/native/libcodeana_java.dylib'),
                               '--probe', str(path)], capture_output=True, text=True, timeout=30)

    def test_stale_native_requests_and_compiler_context_fail_without_partial_output(self):
        context = self.context('version', {'A.java': 'class A {}'})
        for version in (2, 3, 4):
            with self.subTest(request=version):
                result = self.run_probe(dict(self.request(context), version=version))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('ABI_REQUEST_VERSION', result.stderr)
                self.assertEqual(result.stdout, '')
        result = self.run_probe(self.request(dict(context, version=7)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('COMPILER_PROTOCOL_VERSION', result.stderr)
        self.assertEqual(result.stdout, '')
        self.assertFalse(list(self.root.glob('compiler-*')))
        self.assertFalse(list(self.root.rglob('*.class')))

    def test_mixed_bundle_versions_and_missing_default_graph_receipt_are_rejected(self):
        bundle = self.root/'bundle'
        (bundle/'native').mkdir(parents=True)
        source = models.ROOT/'lang-adaptor/target'
        for name in ('native/libcodeana_java.dylib', 'compiler-bridge.jar'):
            shutil.copy2(source/name, bundle/name)
        manifest = json.loads((source/'native/manifest.json').read_text())
        self.assertEqual({k: manifest[k] for k in ('analysis_request', 'compiler_protocol', 'java_model', 'type_graph_default', 'type_schema', 'binding_schema')},
                         dict(analysis_request=6, compiler_protocol=9, java_model=8, type_graph_default=True, type_schema=3, binding_schema=4))
        context = self.context('bundle', {'A.java': 'class A {}'})
        for key, value in (('analysis_request', 4), ('compiler_protocol', 7), ('java_model', 6),
                           ('type_graph_default', False), ('type_schema', 2), ('binding_schema', 3)):
            with self.subTest(field=key):
                changed = dict(manifest, **{key: value})
                (bundle/'native/manifest.json').write_text(json.dumps(changed))
                result = self.run_probe(self.request(context), bundle/'native/libcodeana_java.dylib')
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('BUNDLE_VERSION_MISMATCH', result.stderr)
                self.assertEqual(result.stdout, '')
        changed = dict(manifest)
        changed.pop('type_graph_default')
        (bundle/'native/manifest.json').write_text(json.dumps(changed))
        result = self.run_probe(self.request(context), bundle/'native/libcodeana_java.dylib')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BUNDLE_VERSION_MISMATCH', result.stderr)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    evidence = alignment.ROOT/'target/java-alignment/evidence'/(
        'default-type-graph-'+('native-' if args.native else 'jvm-')+time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir()
    DefaultGraphs.native = args.native
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(DefaultGraphs)
    if args.native:
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(NativeVersions))
    inputs = alignment.input_files()
    artifacts = reference.probe_artifacts(args.native)
    started = time.monotonic()
    log = evidence/'regressions.log'
    with log.open('w') as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    current, current_artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    report = dict(native=args.native, tests=result.testsRun, successful=result.wasSuccessful(),
                  elapsed_seconds=round(time.monotonic()-started, 3), inputs=inputs, artifacts=artifacts,
                  changed_inputs=sorted(p for p in inputs.keys() | current.keys() if inputs.get(p) != current.get(p)),
                  changed_artifacts=sorted(p for p in artifacts.keys() | current_artifacts.keys() if artifacts.get(p) != current_artifacts.get(p)),
                  log=str(log.relative_to(alignment.ROOT)), log_sha256=reference.digest(log),
                  product_default_enabled=True, full_semantic_diff_accepted=False)
    report['all_ok'] = result.wasSuccessful() and not report['changed_inputs'] and not report['changed_artifacts']
    (evidence/'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(evidence/'receipt.json', flush=True)
    if not report['all_ok']:
        print(log.read_text()[-10000:])
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
