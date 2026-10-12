#!/usr/bin/env python3
"""Independent declaration attributes, with exact full/delta checks.

Compare the reference's effective declaration view with the product's explicit
compiler/model overlay. No display annotations or generated declarations are
discarded to make a difference disappear.
"""
import argparse
import collections
import json
import os
import sqlite3
import subprocess
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_declaration_inventory_diff import KINDS, product_attributes, product_kind, reference_attributes
from java_inventory_canonical import Canonical, verify_source_inputs
from java_lombok_alignment import Lombok
from java_model_tests import JDK8, JDK25


SOURCE = '''interface Api { String read(int count); }
@lombok.Data class Holder<T extends Number> { T value; String[] names; }
@lombok.experimental.UtilityClass class Util { static int count; static void run() {} }
enum Mode { ONE { public String read(int count){return "one";} }; public abstract String read(int count); }
class Ordinary { protected final Api api=null; public String read(int count) {return "";} }
'''


def compare(facts, database, project):
    with sqlite3.connect(':memory:') as product, sqlite3.connect(database.resolve().as_uri()+'?mode=ro', uri=True) as ref:
        product.row_factory = ref.row_factory = sqlite3.Row
        product.execute('CREATE TABLE facts(kind,source,body)')
        product.executemany('INSERT INTO facts VALUES(?,?,?)',
            ((f['kind'], f['source'], json.dumps(f)) for f in facts))
        inputs = verify_source_inputs(product, project)
        canonical = Canonical(product, ref, project)
        left, right = collections.Counter(), collections.Counter()
        for f in facts:
            kind = product_kind(f)
            if f['kind'] != 'declaration' or kind not in KINDS:
                continue
            key = (f['source'], kind, canonical(f['signature'], kind, f['source'], 'product'))
            left[key + (json.dumps(product_attributes(f,canonical),sort_keys=True),)] += 1
        for n in ref.execute('SELECT * FROM nodes'):
            kind = {'ANNOTATION':'ANNOTATION_TYPE','ANONYMOUS_CLASS':'CLASS'}.get(n['kind'],n['kind'])
            if kind not in KINDS or n['scope'] != 'MAIN':
                continue
            key = (n['source_file'], kind, canonical(n['symbol_id'],kind))
            right[key + (json.dumps(reference_attributes(n,canonical),sort_keys=True),)] += 1
        rows = lambda values: [dict(key=key,count=count) for key,count in sorted(values.items(),key=str)]
        return dict(product=left.total(),reference=right.total(),equal=left==right,
                    missing=rows(right-left),extra=rows(left-right),
                    project_source_inputs=inputs,identity_aliases=canonical.receipt)


def index_reference(base, files):
    for path, text in files.items():
        target = base/'app'/path
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(text)
    (base/'app/lombok.config').write_text(reference.CONTROL)
    config = base/'.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\n'
                      'javac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    jar = reference.REFERENCE_JAR
    if reference.digest(jar) != reference.REFERENCE_SHA:
        raise ValueError('FROZEN_REFERENCE_JAR_CHANGED')
    command = [str(JDK25/'bin/java'),'-Xmx512m','-jar',str(jar),'index',str(base),
        '--source-root','app@MAIN=app','--jdk-home',str(JDK8),'--java-version','8',
        '--lombok','ast','--vm-classpath','true','--output',str(base/'index.db'),'--full','--no-classpath']
    with (base/'reference.log').open('wb') as log:
        run = subprocess.run(command,stdout=log,stderr=log,timeout=120,
                             env=dict(os.environ,ANATOMIST_HOME=str(base/'reference-state')))
    if run.returncode:
        raise RuntimeError((base/'reference.log').read_text()[-4000:])
    return base/'index.db'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--native',action='store_true')
    args = p.parse_args()
    evidence = alignment.ROOT/'target/java-alignment/evidence'/('declaration-reference-'
        +time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    evidence.mkdir(parents=True)
    inputs, artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    case = Lombok()
    case.setUp()
    if args.native:
        case.analyze = reference.native_analyze.__get__(case)
    proofs = []
    started = time.monotonic()
    try:
        baseline = None
        for name, source in [('baseline',SOURCE),('body',SOURCE.replace('return "one";','return "ONE";')),
                             ('declaration',SOURCE.replace('String[] names;','java.util.List<String> names;'))]:
            base = evidence/name
            base.mkdir()
            files = case.files(source)
            context = case.controls(case.context('app',files,lombok='model'),{'app/lombok.config':reference.CONTROL})
            facts = case.analyze([context],cache=True)['facts']
            if baseline is not None:
                case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),
                                 sorted(facts,key=lambda f:f['id']))
            baseline = facts
            database = index_reference(base,files)
            proof = compare(facts,database,base)
            case.assertGreater(proof['reference'],25)
            (base/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
            proof.update(name=name,product_sha256=reference.digest(base/'product.json'),
                         reference_sha256=reference.digest(database))
            (base/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof)
            print(name,proof['product'],proof['reference'],proof['equal'],flush=True)
    finally:
        case.doCleanups()
    current, now = alignment.input_files(), reference.probe_artifacts(args.native)
    drift = sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed = sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report = dict(kind='frozen_declaration_attribute_projection',native=args.native,proofs=proofs,
        reference_commit=reference.REFERENCE_COMMIT,reference_jar_sha256=reference.REFERENCE_SHA,
        inputs=inputs,artifacts=artifacts,changed_inputs=drift,changed_artifacts=changed,
        full_semantic_diff_accepted=False,elapsed_seconds=round(time.monotonic()-started,3))
    report['all_ok'] = len(proofs)==3 and all(x['equal'] for x in proofs) and not drift and not changed
    (evidence/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(evidence/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
