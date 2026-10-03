#!/usr/bin/env python3
"""Reject ambiguous source identity normalization in independent comparisons."""
import json
import pathlib
import sqlite3
import tempfile
import unittest
import hashlib
from java_inventory_canonical import Canonical,verify_source_inputs


class IdentityNormalization(unittest.TestCase):
    def test_source_coordinates_require_exact_captured_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            source=root/'Use.java';source.write_text('class Use {}')
            db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row;self.addCleanup(db.close)
            db.execute('CREATE TABLE facts(kind,source,body)')
            checksum=hashlib.sha256(source.read_bytes()).hexdigest()
            fact=dict(kind='provenance',source='Use.java',attributes=dict(source_hash=checksum))
            db.execute('INSERT INTO facts VALUES(?,?,?)',('provenance','Use.java',json.dumps(fact)))
            self.assertEqual(verify_source_inputs(db,root)['count'],1)
            source.write_text('class Use { /* shifted */ }')
            with self.assertRaisesRegex(ValueError,'CAPTURED_JAVA_SOURCE_DRIFT'):
                verify_source_inputs(db,root)

    def test_unicode_span_alias_requires_uniqueness_on_both_sides(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            (root / 'Use.java').write_text('中😀new Api(){}')
            product = sqlite3.connect(':memory:')
            reference = sqlite3.connect(':memory:')
            self.addCleanup(product.close)
            self.addCleanup(reference.close)
            product.row_factory = reference.row_factory = sqlite3.Row
            product.execute('CREATE TABLE facts(kind,source,body)')
            reference.execute('CREATE TABLE nodes(kind,symbol_id,source_file,begin_line,begin_column,end_line,end_column)')
            reference.execute('INSERT INTO nodes VALUES(?,?,?,?,?,?,?)',
                              ('ANONYMOUS_CLASS','p.Use$anon@1','Use.java',1,4,1,14))
            def add(signature):
                product.execute('INSERT INTO facts VALUES(?,?,?)', ('declaration','Use.java',json.dumps(
                    dict(signature=signature,span=dict(start=7,end=18),attributes=dict(nesting_kind='ANONYMOUS')))))
            add('p.Use$1')
            canonical = Canonical(product,reference,root)
            self.assertEqual(canonical('p.Use$1#run()','METHOD','Use.java','product'),
                             'p.Use.anon@1#run()')
            self.assertEqual(canonical.receipt['matched_anonymous_classes'],1)
            add('p.Use$2')
            ambiguous = Canonical(product,reference,root)
            self.assertEqual(ambiguous.receipt['matched_anonymous_classes'],0)
            self.assertEqual(len(ambiguous.receipt['unmatched_product']),2)
            self.assertEqual(ambiguous('p.Use$1#run()','METHOD','Use.java','product'),'p.Use.1#run()')
            product.execute('DELETE FROM facts WHERE json_extract(body,\'$.signature\')=?',('p.Use$2',))
            reference.execute('INSERT INTO nodes SELECT kind,?,source_file,begin_line,begin_column,end_line,end_column FROM nodes',('p.Use$anon@2',))
            self.assertEqual(Canonical(product,reference,root).receipt['matched_anonymous_classes'],0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
