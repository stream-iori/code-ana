#!/usr/bin/env python3
"""Read-only declaration attributes, not just declaration counts or names.

This projection audits effective modifiers, generated origin, field erasure and
callable parameter names/types/returns. It does not certify the complete model.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import sqlite3

from java_inventory_canonical import Canonical, verify_source_inputs
from java_type_views import type_attributes


KINDS = {'CLASS', 'INTERFACE', 'ENUM', 'ANNOTATION_TYPE', 'FIELD', 'ENUM_CONSTANT', 'METHOD', 'CONSTRUCTOR'}


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def product_kind(fact):
    a = fact['attributes']
    # javac calls an enum constant's anonymous subclass ENUM. The reference
    # graph calls it ANONYMOUS_CLASS; retain javac's original kind in the facts.
    return 'CLASS' if a.get('nesting_kind') == 'ANONYMOUS' else a.get('element_kind')


def connection(path):
    db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    return db


def descriptor_name(value):
    if not value:
        return None
    if value.startswith('['):
        component = descriptor_name(value[1:])
        return component + '[]' if component is not None else None
    if value.startswith('L') and value.endswith(';'):
        return value[1:-1].replace('/', '.')
    return {'Z': 'boolean', 'B': 'byte', 'C': 'char', 'S': 'short', 'I': 'int',
            'J': 'long', 'F': 'float', 'D': 'double', 'V': 'void'}.get(value)


def product_attributes(fact, canonical):
    a = type_attributes(fact)
    kind = a['element_kind']
    generated = 'lombok' if fact['origin'] == 'lombok_model' else 'java' if a.get('generated') else None
    overlay = a.get('model_extensions', {}).get('lombok-model-v1', {})
    effective = overlay.get('effective_modifiers')
    # The product deliberately stores static modeling beside compiler flags.
    # Compare the same effective view that the reference exports, without
    # altering either input or treating an empty overlay as permission to drop flags.
    modifiers = effective if effective else a.get('modifiers', [])
    result = dict(modifiers=sorted(modifiers), generation=generated)
    norm = lambda text: canonical(text or '', source=fact['source'], origin='product').replace(', ', ',')
    if kind in ('FIELD', 'ENUM_CONSTANT'):
        model = a.get('type_model', {})
        erased = descriptor_name(a.get('jvm_descriptor') or model.get('descriptor'))
        result['type'] = norm(erased if erased is not None else a.get('declared_type', model.get('display')))
    elif kind in ('METHOD', 'CONSTRUCTOR'):
        types = a.get('parameter_type_models', [])
        names = a.get('parameter_names', [])
        result['parameters'] = [dict(name=names[i] if i < len(names) else 'arg'+str(i),
                                     type=norm(t.get('display', ''))) for i, t in enumerate(types)]
        result['return'] = None if kind == 'CONSTRUCTOR' else norm(a.get('return_type_model', {}).get('display', a.get('return_type')))
    return result


def reference_attributes(node, canonical):
    a = json.loads(node['metadata'] or '{}')
    result = dict(modifiers=sorted(json.loads(node['modifiers'] or '[]')),
                  generation=a.get('generator', 'java' if node['synthetic'] else None))
    norm = lambda text: canonical(text or '').replace(', ', ',')
    if node['kind'] in ('FIELD', 'ENUM_CONSTANT'):
        result['type'] = norm(a.get('type'))
    elif node['kind'] in ('METHOD', 'CONSTRUCTOR'):
        result['parameters'] = [dict(name=p['name'], type=norm(p['type'])) for p in a.get('parameters', [])]
        result['return'] = None if node['kind'] == 'CONSTRUCTOR' else norm(a.get('returnType'))
    return result


def main():
    p = argparse.ArgumentParser()
    for key in ('product', 'reference', 'project', 'output'):
        p.add_argument('--'+key, type=pathlib.Path, required=True)
    args = p.parse_args()
    before = dict(product=digest(args.product), reference=digest(args.reference))
    with connection(args.product) as db, connection(args.reference) as ref:
        revision = db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:
            raise ValueError('PUBLISHED_REVISION_REQUIRED')
        inputs = verify_source_inputs(db, args.project)
        canonical = Canonical(db, ref, args.project)
        left, right = collections.Counter(), collections.Counter()
        for row in db.execute("SELECT body FROM facts WHERE kind='declaration'"):
            f = json.loads(row['body'])
            kind = product_kind(f)
            if kind not in KINDS:
                continue
            key = (f['source'], kind, canonical(f['signature'], kind, f['source'], 'product'))
            left[key + (json.dumps(product_attributes(f, canonical), sort_keys=True, separators=(',', ':')),)] += 1
        for row in ref.execute('SELECT * FROM nodes'):
            kind = {'ANNOTATION': 'ANNOTATION_TYPE', 'ANONYMOUS_CLASS': 'CLASS'}.get(row['kind'], row['kind'])
            if kind not in KINDS or row['scope'] not in ('MAIN', 'TEST'):
                continue
            key = (row['source_file'], kind, canonical(row['symbol_id'], kind))
            right[key + (json.dumps(reference_attributes(row, canonical), sort_keys=True, separators=(',', ':')),)] += 1
        sample = lambda values: [dict(key=key, count=count) for key, count in sorted(values.items())[:100]]
        report = dict(kind='published_native_declaration_attribute_projection',
                      fields=['effective_modifiers', 'generation', 'field_erasure', 'parameter_names', 'parameter_types', 'return_type'],
                      product=left.total(), reference=right.total(), missing=(right-left).total(), extra=(left-right).total(),
                      missing_sample=sample(right-left), extra_sample=sample(left-right),
                      identity_aliases=canonical.receipt, project_source_inputs=inputs,
                      revision=revision[0], database_sha256=before,
                      canonicalization=['binary nesting $ to source dot', 'varargs to array',
                          'known constructor only to <init>',
                          'anonymous class aliases require unique equal original-source spans',
                          'anonymous enum body to reference graph class category',
                          'explicit Lombok effective modifier overlay; original compiler flags retained'],
                      full_semantic_diff_accepted=False)
        after = dict(product=digest(args.product), reference=digest(args.reference))
        if before != after:
            raise ValueError('COMPARISON_DATABASE_DRIFT')
        report['changed_databases'] = []
        report['equal'] = left == right
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        print({k: report[k] for k in ('product', 'reference', 'missing', 'extra', 'equal')})
        return 0 if report['equal'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
