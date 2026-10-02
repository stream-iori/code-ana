#!/usr/bin/env python3
"""Actual SDK8 compiler/model/extractor graph wire and cold delta equality (JVM, not native acceptance)."""
import argparse
import json
import pathlib
import subprocess
import time
import unittest

import java_model_tests as models
import java_module_signature_alignment as modules
import java_alignment as alignment
import java_functional_reference_alignment as reference
import java_framework_alignment as frameworks
import java_lombok_alignment as lombok
import java_lombok_super_builder_alignment as super_builders


class GraphFacts(models.Models):
    native = False
    @classmethod
    def setUpClass(cls):
        compiled = subprocess.run(['cargo', 'build', '-p', 'codeana-model', '--example', 'validate_java_graph_facts', '--locked'],
                                  cwd=models.ROOT, capture_output=True, text=True, timeout=120)
        if compiled.returncode:
            raise RuntimeError(compiled.stderr)

    def validate_rust(self, contexts, result, baseline=None, accepted=True):
        sources = []
        for context in contexts:
            for unit in context['sources']:
                if unit['publish']:
                    source = {k: unit[k] for k in ('path', 'hash', 'world', 'module', 'scope')}
                    source.update(language='java', size=pathlib.Path(unit['input']).stat().st_size, auxiliary=False)
                    sources.append(source)
        payload = dict(manifest=dict(files=sources), mode=result['mode'], facts=result['facts'],
                       replacements=result['replacements'], baseline=baseline or [])
        path = self.root / 'rust-graph-facts.json'
        path.write_text(json.dumps(payload))
        process = subprocess.run([str(models.ROOT / 'target/debug/examples/validate_java_graph_facts'), str(path)],
                                 capture_output=True, text=True, timeout=60)
        if accepted:
            self.assertEqual(process.returncode, 0, process.stderr)
        else:
            self.assertNotEqual(process.returncode, 0)
            self.assertIn('INVALID_JAVA_TYPE_GRAPH', process.stderr)

    def analyze(self, contexts, baseline=None, **options):
        result = reference.native_analyze(self, contexts, baseline, **options) if self.native else super().analyze(contexts, baseline, **options)
        self.validate_rust(contexts, result, baseline)
        return result

    def graph_context(self, source):
        context = self.context('graph', {'App.java': source})
        context['type_graph'] = True
        return context

    def graph(self, fact, key):
        attributes = fact['attributes']
        reference = attributes[key]['$java_type']
        payload = attributes['java_type_graphs'][reference[0]]
        return payload, payload['roots'][reference[1]]

    def test_graph_real_extraction_preserves_80_generic_levels_without_nested_json(self):
        nested = 'Leaf'
        for _ in range(80):
            nested = 'Box<' + nested + '>'
        facts = self.analyze([self.graph_context('class Leaf{} class Box<T>{} class Use {' + nested + ' deep;}')])['facts']
        field = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#deep')
        payload, root = self.graph(field, 'type_model')
        for level in range(80):
            node = payload['nodes'][root]
            self.assertEqual(node['element'], 'Box', level)
            self.assertTrue(node['complete'], level)
            root = node['arguments'][0]
        self.assertEqual(payload['nodes'][root]['element'], 'Leaf')
        self.assertFalse([f for f in facts if f['kind'] == 'diagnostic' and f['attributes'].get('code', '').startswith('TYPE_MODEL_')])
        facet = next(f for f in facts if f['kind'] == 'facet' and f['relation'] == 'java.type' and f['parent'] == field['id'])
        self.assertEqual(facet['attributes']['schema_version'], 3)
        # Physical JSON nesting is bounded independently of semantic graph depth.
        def depth(value):
            if isinstance(value, dict):
                return 1 + max((depth(v) for v in value.values()), default=0)
            if isinstance(value, list):
                return 1 + max((depth(v) for v in value), default=0)
            return 0
        self.assertLess(depth(facts), 32)

    def test_graph_recursive_bounds_error_neighbor_and_binding_owner(self):
        source = 'class Use<T extends Comparable<T>> { T recursive; Missing broken; String read(String x){return x.trim();} }'
        facts = self.analyze([self.graph_context(source)])['facts']
        field = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#recursive')
        payload, root = self.graph(field, 'type_model')
        node = payload['nodes'][root]
        self.assertEqual(node['kind'], 'TYPEVAR')
        self.assertEqual(payload['nodes'][node['upper']]['arguments'][0], root)
        broken = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#broken')
        payload, root = self.graph(broken, 'type_model')
        self.assertFalse(payload['nodes'][root]['complete'])
        site = next(f for f in facts if f['kind'] == 'call_site' and f['name'] == 'x.trim')
        payload, root = self.graph(site, 'selected_type_model')
        self.assertEqual(payload['nodes'][root]['kind'], 'EXECUTABLE')
        binding = next(f for f in facts if f['relation'] == 'java.binding' and f['parent'] == site['id'])
        self.assertEqual(binding['attributes']['schema_version'], 4)
        self.assertEqual(binding['attributes']['evidence_owner'], site['id'])

    def test_graph_framework_reads_and_reencodes_types_before_rust_publication(self):
        source = '''interface Api {} @org.springframework.stereotype.Component class Use {
          @org.springframework.beans.factory.annotation.Autowired Api field;
          @org.springframework.context.annotation.Bean Api factory(Api dependency){return dependency;}
        }'''
        def context(body):
            return self.context('frameworkgraph', frameworks.BASE | frameworks.DI | {'Use.java': body},
                                spring=True, type_graph=True)
        facts = self.analyze([context(source)], cache=True)['facts']
        injections = [f for f in facts if f['relation'] == 'java.spring.injection']
        self.assertCountEqual([f['attributes']['role'] for f in injections], ['field', 'factory_parameter'])
        for injection in injections:
            payload, root = self.graph(injection, 'injected_type')
            self.assertEqual(payload['nodes'][root]['element'], 'Api')
        changed = context(source.replace('return dependency;', 'return field;'))
        delta = self.analyze([changed], facts, cache=True)
        full = self.analyze([changed])['facts']
        self.assertEqual(self.merged(facts, delta), sorted(full, key=lambda f: f['id']))

    def test_graph_generated_getter_retains_80_levels_and_super_builder_lower_bound(self):
        nested = 'Leaf'
        for _ in range(80):
            nested = 'Box<' + nested + '>'
        context = self.context('gettergraph', lombok.Lombok.files(
            self, 'class Leaf{} class Box<T>{} @lombok.Getter class Use {' + nested + ' deep;}'),
            lombok='model', type_graph=True)
        facts = self.analyze([context])['facts']
        getter = next(f for f in facts if f['kind'] == 'declaration' and f['signature'] == 'Use#getDeep()')
        payload, root = self.graph(getter, 'return_type_model')
        for _ in range(80):
            self.assertEqual(payload['nodes'][root]['element'], 'Box')
            self.assertTrue(payload['nodes'][root]['complete'])
            root = payload['nodes'][root]['arguments'][0]
        self.assertEqual(payload['nodes'][root]['element'], 'Leaf')
        helper = super_builders.SuperBuilders()
        helper.root = self.root
        builder = helper.fixture('''@lombok.experimental.SuperBuilder abstract class Parent<T> {T name;}
          @lombok.experimental.SuperBuilder class Child<N extends Number> extends Parent<String> {N value;}''')
        builder['type_graph'] = True
        generated = self.analyze([builder])['facts']
        self.assertTrue(any(f['signature'] == 'Child$ChildBuilder' for f in generated))
        field = next(f for f in generated if f['kind'] == 'declaration' and f['signature'] == 'Child#value')
        payload, root = self.graph(field, 'type_model')
        self.assertEqual(payload['nodes'][root]['kind'], 'TYPEVAR')
        self.assertEqual(payload['nodes'][payload['nodes'][root]['lower']]['kind'], 'NULL')

    def test_graph_cold_delta_deletion_and_visit_order_keep_public_identity(self):
        original = 'class Use<T extends Comparable<T>> { T value; String read(String x){return x.trim();} }'
        baseline = self.analyze([self.graph_context(original)])['facts']
        changed = original.replace('x.trim()', 'x.toLowerCase()')
        delta = self.analyze([self.graph_context(changed)], baseline)
        full = self.analyze([self.graph_context(changed)])['facts']
        self.assertEqual(self.merged(baseline, delta), sorted(full, key=lambda f: f['id']))
        deleted = self.analyze([self.graph_context('class Use {}')], full)
        self.assertEqual(self.merged(full, deleted), sorted(self.analyze([self.graph_context('class Use {}')])['facts'], key=lambda f: f['id']))

    def test_graph_rust_publication_rejects_corrupted_actual_compiler_edges(self):
        context = self.graph_context('class Use<T extends Comparable<T>> { T value; }')
        result = self.analyze([context])
        field = next(f for f in result['facts'] if f['signature'] == 'Use#value')
        payload, root = self.graph(field, 'type_model')
        payload['nodes'][root]['upper'] = len(payload['nodes'])
        self.validate_rust([context], result, accepted=False)

    def test_graph_recursive_generic_annotation_and_nested_module_nocode_equal(self):
        def reactor(body='"before"'):
            contexts = modules.ModuleProof.reactor(self, body)
            for context in contexts:
                context['type_graph'] = True
            return contexts
        contexts = reactor()
        ordinary = self.analyze(contexts)
        cached = self.analyze(contexts, cache=True)
        self.assertLess(cached['compiler_sources'], ordinary['compiler_sources'])
        self.assertEqual(sorted(ordinary['facts'], key=lambda f: f['id']), sorted(cached['facts'], key=lambda f: f['id']))
        changed = reactor('"after"')
        delta = self.analyze(changed, cached['facts'], cache=True)
        self.assertEqual(self.merged(cached['facts'], delta), sorted(self.analyze(changed)['facts'], key=lambda f: f['id']))

    def test_graph_file_signature_catalogue_keeps_cycles_and_rejects_tree_mode_reuse(self):
        source = 'class Api<T extends Comparable<T>> { T value; T read(){ return value; } }'
        source2 = 'class Use { String read(){ return "before".trim(); } }'
        context = self.context('filegraph', {'Api.java': source, 'Use.java': source2}, type_graph=True)
        baseline = self.analyze([context], cache=True)['facts']
        context = self.context('filegraph', {'Api.java': source, 'Use.java': source2.replace('before', 'after')}, type_graph=True)
        delta = self.analyze([context], baseline, cache=True)
        self.assertEqual(self.merged(baseline, delta), sorted(self.analyze([context])['facts'], key=lambda f: f['id']))
        catalogues = list((self.root / 'signature-cache').glob('*/declarations.json'))
        self.assertTrue(catalogues)
        self.assertTrue(any('java_type_graphs' in json.loads(path.read_text()) for path in catalogues))
        # Same bytes/profile cannot confuse the model representations in either cache.
        tree_context = dict(context, type_graph=False)
        trees = self.analyze([tree_context], cache=True)['facts']
        self.assertFalse(any('java_type_graphs' in fact['attributes'] for fact in trees))
        self.assertEqual(trees, self.analyze([tree_context])['facts'])

    def test_graph_type_use_values_on_recursive_formals_and_nested_arguments_survive(self):
        source = '''import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Mark { int value(); }
            class Box<T> {} class Use<T extends Comparable<T>> {
                @Mark(2) T value; Box<@Mark(3) String> nested;
            }'''
        facts = self.analyze([self.graph_context(source)])['facts']
        field = next(f for f in facts if f['signature'] == 'Use#value' and f['kind'] == 'declaration')
        payload, root = self.graph(field, 'type_model')
        node = payload['nodes'][root]
        self.assertIn('Mark', node['annotations'])
        self.assertEqual(node['annotation_models'][0]['explicit']['value'], 2)
        self.assertTrue(node['complete'])
        self.assertEqual(payload['nodes'][node['lower']]['kind'], 'NULL')
        nested = next(f for f in facts if f['signature'] == 'Use#nested' and f['kind'] == 'declaration')
        payload, root = self.graph(nested, 'type_model')
        argument = payload['nodes'][payload['nodes'][root]['arguments'][0]]
        self.assertIn('Mark', argument['annotations'])
        self.assertEqual(argument['annotation_models'][0]['explicit']['value'], 3)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    GraphFacts.native = args.native
    frozen = alignment.input_files()
    artifacts = reference.probe_artifacts(args.native)
    started = time.monotonic()
    suite = unittest.TestSuite(GraphFacts(name) for name in unittest.defaultTestLoader.getTestCaseNames(GraphFacts) if name.startswith('test_graph_'))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    current = alignment.input_files()
    current_artifacts = reference.probe_artifacts(args.native)
    drift = sorted(path for path in frozen.keys() | current.keys() if frozen.get(path) != current.get(path))
    artifact_drift = sorted(path for path in artifacts.keys() | current_artifacts.keys() if artifacts.get(path) != current_artifacts.get(path))
    accepted = result.wasSuccessful() and not drift and not artifact_drift
    if args.native:
        evidence = models.ROOT / 'target/java-alignment/evidence' / ('type-graph-native-' + time.strftime('%Y%m%d-%H%M%S'))
        evidence.mkdir(exist_ok=False)
        (evidence / 'receipt.json').write_text(json.dumps(dict(tests=result.testsRun, all_ok=accepted,
            native_verified=True, full_semantic_diff_accepted=False, product_default_enabled=False,
            elapsed_seconds=round(time.monotonic()-started, 3), inputs=frozen, changed_inputs=drift,
            artifacts=artifacts, changed_artifacts=artifact_drift), indent=2) + '\n')
        print(evidence / 'receipt.json')
    raise SystemExit(0 if accepted else 1)
