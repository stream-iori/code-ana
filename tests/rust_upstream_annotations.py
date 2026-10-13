#!/usr/bin/env python3
"""Original annotation write outcomes through explicit Rust note IDs and CAS."""
import json
import unittest
import product_support


class AnnotationProduct(product_support.MockProductHarness):
    def setUp(self):
        super().setUp()
        self.cli('index')

    def body(self, label, source='LLM', confidence='MEDIUM'):
        return json.dumps(dict(category='REVIEWED', source=source, confidence=confidence, business_label=label))

    def add(self, symbol, body):
        return self.cli('annotate', symbol, '--name', 'add', '--text', body)[0][0]

    def update(self, note, body):
        return self.cli('annotate', '--name', 'update', '--base', note['id'],
                        '--expected-revision', str(note['version']), '--text', body)[0][0]

    def notes(self):
        return self.cli('annotate', '--name', 'list')[0][0]

    def test_upstream_annotation_inserts_new_row(self):
        self.add('app', self.body('reviewed'))
        rows = self.notes()
        self.assertEqual(len(rows), 1)
        self.assertEqual(json.loads(rows[0]['text'])['business_label'], 'reviewed')

    def test_upstream_annotation_explicit_update_preserves_one_identity(self):
        note = self.add('app', self.body('v1'))
        self.update(note, self.body('v2', confidence='HIGH'))
        rows = self.notes()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['id'], note['id'])
        self.assertEqual(json.loads(rows[0]['text'])['business_label'], 'v2')
        self.assertEqual(json.loads(rows[0]['text'])['confidence'], 'HIGH')
        self.cli('annotate', '--name', 'update', '--base', note['id'],
                 '--expected-revision', '1', '--text', 'stale overwrite', code=1)
        self.assertEqual(self.notes(), rows)

    def test_upstream_annotation_distinct_sources_coexist(self):
        self.add('app', self.body('from-llm'))
        self.add('app', self.body('from-doc', source='DOC', confidence='HIGH'))
        rows = self.notes()
        self.assertEqual(len(rows), 2)
        self.assertEqual({json.loads(row['text'])['source'] for row in rows}, {'LLM', 'DOC'})

    def test_upstream_annotation_update_and_new_write_retain_both_outcomes(self):
        note = self.add('app', self.body('old'))
        self.update(note, self.body('updated', confidence='HIGH'))
        self.add('greet', self.body('new'))
        rows = self.notes()
        self.assertEqual(len(rows), 2)
        self.assertEqual({json.loads(row['text'])['business_label'] for row in rows}, {'updated', 'new'})


def load_tests(loader, tests, pattern):
    return unittest.TestSuite([AnnotationProduct(name) for name in
        loader.getTestCaseNames(AnnotationProduct) if name.startswith('test_upstream_')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
