#!/usr/bin/env python3
"""Reject ambiguous source identity normalization in independent comparisons."""
import json
import pathlib
import sqlite3
import tempfile
import unittest
import hashlib
from java_inventory_canonical import Canonical,verify_source_inputs,compiler_lines
from java_declaration_inventory_diff import product_attributes, product_kind, descriptor_name


class IdentityNormalization(unittest.TestCase):
    def test_unicode_line_separators_preserve_javac_utf16_coordinates(self):
        self.assertEqual(compiler_lines('a\u2028b\u2029c\x85d\r\ne\rf\n'),
                         ['a\u2028b\u2029c\x85d\r\n','e\r','f\n',''])
    def test_declaration_projection_compares_effective_overlay_without_losing_original_flags(self):
        attrs = dict(element_kind='ENUM', nesting_kind='ANONYMOUS', modifiers=['static','final'])
        fact = dict(attributes=attrs, origin='javac', source='Use.java')
        self.assertEqual(product_kind(fact), 'CLASS')
        self.assertEqual(attrs['element_kind'], 'ENUM')
        fact['attributes'] = dict(element_kind='CLASS', modifiers=['public'], model_extensions={
            'lombok-model-v1': {'effective_modifiers': ['public','final']}})
        norm = lambda value, **unused: value
        self.assertEqual(product_attributes(fact,norm)['modifiers'], ['final','public'])
        self.assertEqual(fact['attributes']['modifiers'], ['public'])
        fact['attributes']['model_extensions']['lombok-model-v1']['effective_modifiers'] = []
        self.assertEqual(product_attributes(fact,norm)['modifiers'], ['public'])

    def test_type_annotation_display_differences_are_not_erased_to_make_comparison_pass(self):
        fact = dict(origin='lombok_model', source='Use.java', attributes=dict(
            element_kind='METHOD', modifiers=['public'], parameter_names=['value'],
            parameter_type_models=[{'display':'java.util.List<@Use String>'}],
            return_type_model={'display':'(@Use :: int)'}))
        attributes = product_attributes(fact,lambda value, **unused: value)
        self.assertEqual(attributes['return'], '(@Use :: int)')
        self.assertEqual(attributes['parameters'][0]['type'], 'java.util.List<@Use String>')
        self.assertEqual(descriptor_name('[[Lp/Outer$Inner;'), 'p.Outer$Inner[][]')
        self.assertIsNone(descriptor_name('?'))

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

    def test_constructor_normalization_preserves_methods_named_after_the_class(self):
        with tempfile.TemporaryDirectory() as temp:
            product=sqlite3.connect(':memory:');reference=sqlite3.connect(':memory:')
            self.addCleanup(product.close);self.addCleanup(reference.close)
            product.row_factory=reference.row_factory=sqlite3.Row
            product.execute('CREATE TABLE facts(kind,source,body)')
            reference.execute('CREATE TABLE nodes(kind,symbol_id,source_file,begin_line,begin_column,end_line,end_column)')
            canonical=Canonical(product,reference,pathlib.Path(temp))
            self.assertEqual(canonical('p.Example#Example()', 'METHOD'),'p.Example#Example()')
            self.assertEqual(canonical('p.Example#Example()', 'CONSTRUCTOR'),'p.Example#<init>()')
            self.assertEqual(canonical('p.Example#<init>()', 'CONSTRUCTOR'),'p.Example#<init>()')
            self.assertNotEqual(canonical('p.Example#Example()', 'METHOD'),canonical('p.Example#Example()', 'CONSTRUCTOR'))

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
