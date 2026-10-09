#!/usr/bin/env python3
"""Compare published DI endpoints with an independently indexed Spring graph."""
import collections
import hashlib
import json
import sqlite3
import contextlib
import java_alignment as alignment

import java_functional_reference_alignment as functional
from java_framework_reference_alignment import reference_index
from java_wiring_inventory_diff import product_projection, reference_projection


SOURCE = '''package p;
interface Api {} class First implements Api {} class Second implements Api {}
class Use {
 @org.springframework.beans.factory.annotation.Autowired Api field;
 @org.springframework.beans.factory.annotation.Autowired void accept(
     Api ordinary, Api another) {}
 @org.springframework.beans.factory.annotation.Autowired Use(Api ordinary) {}
 static class Nested { @org.springframework.beans.factory.annotation.Autowired Api other; }
}
'''


def projection(facts, directory, source=SOURCE, path='src/p/Use.java'):
    # Keep independent DBs and raw differences after the daemon fixture cleans up.
    directory=alignment.ROOT/'target/java-alignment/evidence'/('di-graph-reference-'+directory.parent.name+'-'+directory.name)
    directory.mkdir(parents=True, exist_ok=False)
    # Index the original fixture, without supplying product facts to the resolver.
    reference_index(directory, source)
    reference_source = directory / 'app/Use.java'
    source_sha = hashlib.sha256(source.encode()).hexdigest()
    if hashlib.sha256(reference_source.read_bytes()).hexdigest() != source_sha:
        raise ValueError('DI_REFERENCE_SOURCE_DRIFT')
    coverage = [f for f in facts if f['kind']=='coverage' and f['source']==path]
    if not coverage or any(f['attributes']['input_hash']!=source_sha for f in coverage):
        raise ValueError('DI_PRODUCT_CAPTURE_DRIFT')
    canonical = lambda signature, kind=None, source='', origin='reference': functional.canonical(signature)
    left = product_projection(facts, canonical)
    database = directory / 'index.db'
    before = functional.digest(database)
    with contextlib.closing(sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True)) as db:
        db.row_factory=sqlite3.Row
        right=reference_projection(db,canonical)
    for row in right:
        if row['file']!='app/Use.java':raise ValueError('DI_REFERENCE_UNEXPECTED_SOURCE')
        row['file']=path
    if functional.digest(database)!=before:raise ValueError('DI_REFERENCE_DATABASE_DRIFT')
    counter=lambda rows:collections.Counter((json.dumps(row,ensure_ascii=False,sort_keys=True),) for row in rows)
    report=dict(kind='independent_published_di_declaring_type_graph',
        source_sha256=source_sha,reference_sha256=before,
        reference_jar_sha256=functional.REFERENCE_SHA,
        **functional.compare(counter(left),counter(right)),
        full_semantic_diff_accepted=False)
    (directory/'diff.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    (directory/'product-wires.json').write_text(json.dumps([f for f in facts if f['relation'] in
        ('wires_possible','injects_possible','java.spring.injection')],ensure_ascii=False,indent=2)+'\n')
    return sorted(left,key=lambda r:json.dumps(r,sort_keys=True)),sorted(right,key=lambda r:json.dumps(r,sort_keys=True))
