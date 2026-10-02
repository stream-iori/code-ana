#!/usr/bin/env python3
"""Preserve syntax nodes and source replacement when a file requires bounded partitions."""
import unittest
import java_model_tests as models


class SyntaxAlignment(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def assert_partitions(self, result, context):
        parts = [f for f in result['facts'] if f['relation'] == 'java.syntax']
        self.assertGreater(len(parts), 1)
        parts.sort(key=lambda f: f['attributes']['chunk_index'])
        offset = 0
        nodes = []
        for index, part in enumerate(parts):
            attributes = part['attributes']
            self.assertEqual(attributes['schema_version'], 2)
            self.assertEqual(attributes['source_hash'], context['sources'][0]['hash'])
            self.assertEqual(attributes['chunk_index'], index)
            self.assertEqual(attributes['node_offset'], offset)
            self.assertEqual(attributes['last_chunk'], index == len(parts) - 1)
            self.assertEqual(part['owner']['id'], context['sources'][0]['path'])
            self.assertLess(len(models.json.dumps(part, ensure_ascii=False).encode()), 8 * 1024 * 1024)
            nodes.extend(attributes['nodes'])
            offset += len(attributes['nodes'])
        self.assertEqual(offset, parts[-1]['attributes']['total_nodes'])
        ids = {n['id'] for n in nodes}
        self.assertEqual(len(ids), len(nodes))
        for node in nodes:
            if node.get('parent'):
                self.assertIn(node['parent'], ids)
        return parts

    def test_partition_boundaries_delta_shrink_and_unchanged(self):
        text = 'class Large {' + ''.join(f'int m{i}(){{return {i};}}' for i in range(1500)) + '}'
        first = self.context('large', {'Large.java': text}, model_record_limit=1000000)
        baseline = self.analyze([first])
        self.assert_partitions(baseline, first)
        unchanged = self.analyze([first], baseline['facts'])
        self.assertEqual(self.merged(baseline['facts'], unchanged), sorted(baseline['facts'], key=lambda f: f['id']))
        changed = self.context('large', {'Large.java': text.replace('return 1499;', 'return 1500;')}, model_record_limit=1000000)
        delta = self.analyze([changed], baseline['facts'])
        full = self.analyze([changed])
        self.assert_partitions(full, changed)
        self.assertEqual(self.merged(baseline['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))
        small = self.context('large', {'Large.java': 'class Large {int m(){return 1;}}'})
        delta = self.analyze([small], full['facts'])
        rebuilt = self.analyze([small])
        self.assertEqual(self.merged(full['facts'], delta), sorted(rebuilt['facts'], key=lambda f: f['id']))
        syntax = [f for f in rebuilt['facts'] if f['relation'] == 'java.syntax']
        self.assertEqual(len(syntax), 1)
        self.assertEqual(syntax[0]['attributes']['schema_version'], 1)
        self.assertEqual(syntax[0]['id'], models.key('syntax', small['world'], small['sources'][0]['path']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
