#!/usr/bin/env python3
"""Frozen MAIN/TEST generated-signature proofs, including byte positions and status."""
import argparse
import collections
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import time
import java_alignment as alignment
import java_functional_reference_alignment as reference
import java_model_tests as models
from java_supplemental_signature_alignment import SupplementalSignatures, LIBRARY, CONSUMER, MAP_LIBRARY, MAP_CONSUMER, SUPER_LIBRARY, SUPER_CONSUMER


def proof(name, library, consumer, shape, evidence, native):
    scope = "MAIN" if name.startswith("main-") else "TEST"
    base = evidence / name
    base.mkdir()
    case = SupplementalSignatures()
    case.setUp()
    try:
        if native:
            case.analyze = reference.native_analyze.__get__(case)
        context = case.fixture(library, consumer, shape, scope=scope)
        auxiliary_roots = sorted({unit['path'].split('/')[0] for unit in context['sources'] if not unit['publish']})
        facts = case.analyze([context])['facts']
        for unit in context['sources']:
            target = base / unit['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(unit['input'], target)
    finally:
        case.doCleanups()
    (base / 'lombok.config').write_text(reference.CONTROL)
    config = base / '.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\njavac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    command = [str(models.JDK25 / 'bin/java'), '-Xmx512m', '-jar',
               str(alignment.ROOT.parent / 'anatomist/target/anatomist.jar'), 'index', str(base),
               '--source-root', 'app@MAIN=' + ','.join(auxiliary_roots), '--source-root', 'app@TEST=use',
               '--jdk-home', str(models.JDK8), '--java-version', '8', '--lombok', 'ast',
               '--vm-classpath', 'true', '--no-classpath', '--full', '--output', str(base / 'index.db')]
    if scope == 'MAIN':
        command = command[:command.index('--source-root')] + ['--source-root','app@MAIN=.'] + command[command.index('--jdk-home'):]
    with (base / 'reference.log').open('wb') as stream:
        completed = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=120,
                                   env=dict(os.environ, ANATOMIST_HOME=str(base / 'reference-state')))
    if completed.returncode:
        raise RuntimeError((base / 'reference.log').read_text()[-5000:])
    (base / 'product.json').write_text(json.dumps(facts, ensure_ascii=False) + '\n')
    by = {fact['id']: fact for fact in facts}
    left, right = collections.Counter(), collections.Counter()
    lines = {}
    def offset(path, line, column, last=False):
        if line is None or column is None:
            return None
        if path not in lines:
            lines[path] = (base / path).read_bytes().decode('utf8').splitlines(keepends=True)
        rows = lines[path]
        return sum(len(row.encode('utf8')) for row in rows[:line-1]) + len(
            rows[line-1].encode('utf-16-le')[:2*(column-1+last)].decode('utf-16-le').encode('utf8'))
    for fact in facts:
        if fact['source'] != 'use/Use.java' or fact['relation'] != 'type_reference':
            continue
        owner, target = by[fact['parent']], by[fact['target']]
        span = fact['span'] or {}
        identity = ('lambda', owner['span']['start']) if owner['attributes']['element_kind'] == 'LAMBDA' else reference.canonical(owner['signature'])
        left[(fact['source'], identity, reference.canonical(target['signature']), span.get('start'),
              span.get('end'), fact['attributes'].get('type_context'), fact['status'])] += 1
    with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as db:
        for row in db.execute("""SELECT n.symbol_id,coalesce(t.symbol_id,e.external_target_fqn),e.source_file,
                e.begin_line,e.begin_column,e.end_line,e.end_column,e.context,n.kind,
                n.begin_line,n.begin_column,e.confidence FROM edges e JOIN nodes n ON n.id=e.source_id
                LEFT JOIN nodes t ON t.id=e.target_id WHERE e.relation='REFERENCES' AND e.source_file='use/Use.java'"""):
            signature, target, path, line, column, end_line, end_column, context, kind, ol, oc, confidence = row
            if kind not in ('CLASS', 'METHOD', 'CONSTRUCTOR', 'FIELD', 'LAMBDA'):
                continue
            identity = ('lambda', offset(path, ol, oc)) if kind == 'LAMBDA' else reference.canonical(signature, kind)
            right[(path, identity, reference.canonical(target), offset(path, line, column),
                   offset(path, end_line, end_column, True), context,
                   'possible' if confidence == 'INFERRED' else 'complete')] += 1
    call_left, call_right = collections.Counter(), collections.Counter()
    for fact in facts:
        if fact['kind'] != 'call_target' or fact['source'] != 'use/Use.java': continue
        site = by[fact['parent']]; span = site['span']
        call_left[(span['start'], span['end'], reference.canonical(by[fact['target']]['signature']), fact['status'])] += 1
    with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as db:
        for row in db.execute("SELECT s.begin_line,s.begin_column,s.end_line,s.end_column,coalesce(n.symbol_id,t.external_target_symbol,t.external_target_fqn),t.resolution_status FROM call_site_targets t JOIN call_sites s ON s.site_pk=t.call_site_pk JOIN call_site_owners o ON o.owner_pk=s.owner_pk LEFT JOIN nodes n ON n.id=t.target_id WHERE o.source_file='use/Use.java'"):
            line,column,end_line,end_column,signature,status = row
            call_right[(offset('use/Use.java',line,column),offset('use/Use.java',end_line,end_column,True),reference.canonical(signature),{'exact':'resolved','heuristic':'possible'}.get(status,status))] += 1
    call_diff = reference.compare(call_left, call_right)
    result = reference.compare(left, right)
    minimum_types = 0 if name.endswith('-negative') else 3
    result.update(accepted=result['equal'] and sum(right.values()) >= minimum_types
                  and call_diff['equal'] and call_diff['reference'] >= 1, call_targets=call_diff,
                  source_hashes={str(p.relative_to(base)): reference.digest(p) for directory in auxiliary_roots + ['use']
                                 for p in sorted((base / directory).rglob('*.java'))})
    (base / 'diff.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    jar = alignment.ROOT.parent / 'anatomist/target/anatomist.jar'
    if reference.digest(jar) != reference.REFERENCE_SHA:
        raise ValueError('REFERENCE_ARTIFACT_CHANGED')
    evidence = alignment.ROOT / 'target/java-alignment/evidence' / ('supplemental-reference-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    evidence.mkdir()
    inputs, artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    cases = {
        'primitive-expression': ('package p; @lombok.Getter class Api {Integer delay;}',
            'package p; /* 😀中 */ class Use {void read(Api api){java.util.Optional.ofNullable(api).map(Api::getDelay).map(value -> Math.min(value,10)*1000).ifPresent(value -> {try{Thread.sleep(value);}catch(InterruptedException error){}});}}', 'getter'),
        'main-getter': (LIBRARY,CONSUMER,'getter'),
        'complete-object-callbacks': (MAP_LIBRARY, MAP_CONSUMER, 'getter'),
        'getter': (LIBRARY, CONSUMER, 'getter'),
        'generic-builder': ('@lombok.Builder @lombok.Getter class Api<T extends Leaf> {java.util.List<T> values;} class Leaf {void run(){}}',
                            'class Use {void read(){Api.<Leaf>builder().values(java.util.Arrays.asList(new Leaf())).build().getValues().forEach(leaf -> leaf.run());}}', 'builder'),
        'recursive-super-builder': ('@lombok.experimental.SuperBuilder @lombok.Getter class Parent<T> {java.util.List<T> values;} @lombok.experimental.SuperBuilder class Child extends Parent<Leaf> {int age;} class Leaf {void run(){}}',
                                    'class Use {void read(){Child.builder().values(java.util.Arrays.asList(new Leaf())).age(1).build().getValues().forEach(leaf -> leaf.run());}}', 'super-builder'),
        'super-builder-argument-negative': (SUPER_LIBRARY, SUPER_CONSUMER.replace('new Leaf()', '"wrong"'), 'super-builder'),
        'super-builder-removal-negative': (SUPER_LIBRARY.replace('@lombok.experimental.SuperBuilder class Child', 'class Child'), SUPER_CONSUMER, 'super-builder'),
    }
    report = dict(reference_sha256=reference.REFERENCE_SHA, native=args.native, inputs=inputs, artifacts=artifacts, cases={}, all_ok=False)
    try:
        for name, values in cases.items():
            report['cases'][name] = proof(name, *values, evidence, args.native)
    finally:
        current, current_artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
        report['changed_inputs'] = sorted(p for p in inputs.keys() | current.keys() if inputs.get(p) != current.get(p))
        report['changed_artifacts'] = sorted(p for p in artifacts.keys() | current_artifacts.keys() if artifacts.get(p) != current_artifacts.get(p))
        report['reference_unchanged'] = reference.digest(jar) == reference.REFERENCE_SHA
        report['all_ok'] = len(report['cases']) == len(cases) and all(c['accepted'] for c in report['cases'].values()) and not report['changed_inputs'] and not report['changed_artifacts'] and report['reference_unchanged']
        (evidence / 'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(evidence / 'receipt.json', flush=True)
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
