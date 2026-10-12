"""Negative controls for the complete historical-fact comparison."""
import importlib.util
import json
import pathlib
import sqlite3
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    'committed_history', ROOT / 'scripts/java-jvm-committed-history.py')
history = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history)


class HistoricalGraphProof(unittest.TestCase):
    def database(self, root, name, rows):
        path = root / name
        with sqlite3.connect(path) as connection:
            connection.execute('CREATE TABLE facts(id TEXT PRIMARY KEY, body TEXT NOT NULL)')
            connection.executemany('INSERT INTO facts VALUES(?,?)', rows)
        return path

    def test_key_order_and_insertion_order_do_not_change_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            left = self.database(root, 'left.db', [
                ('b', '{"id":"b","attributes":{"count":1,"status":"possible"}}'),
                ('a', '{"id":"a","kind":"call_site"}')])
            right = self.database(root, 'right.db', [
                ('a', '{"kind":"call_site","id":"a"}'),
                ('b', '{"attributes":{"status":"possible","count":1},"id":"b"}')])
            self.assertEqual(history.graph(left), history.graph(right))

    def test_equal_row_counts_cannot_hide_evidence_or_position_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            original = dict(id='site', kind='call_site', span=[12, 19],
                            attributes=dict(status='possible', control_context='if-then@L2'))
            left = self.database(root, 'left.db', [('site', json.dumps(original))])
            for number, change in enumerate([
                    dict(span=[12, 20]),
                    dict(attributes=dict(status='complete', control_context='if-then@L2')),
                    dict(attributes=dict(status='possible', control_context=None))]):
                right = self.database(root, f'right{number}.db', [
                    ('site', json.dumps(dict(original, **change)))])
                self.assertEqual(history.graph(left)['count'], history.graph(right)['count'])
                self.assertNotEqual(history.graph(left)['sha256'], history.graph(right)['sha256'])

    def test_distinct_occurrences_are_not_reduced_to_a_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            body = '{"kind":"reference","target":"p.Api"}'
            single = self.database(root, 'single.db', [('one', body)])
            repeated = self.database(root, 'repeated.db', [('one', body), ('two', body)])
            self.assertEqual(history.graph(repeated)['count'], 2)
            self.assertNotEqual(history.graph(single), history.graph(repeated))


if __name__ == '__main__':
    unittest.main(verbosity=2)
