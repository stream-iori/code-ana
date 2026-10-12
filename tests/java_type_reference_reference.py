#!/usr/bin/env python3
"""Independent frozen SOURCE type relations; does not certify all semantic facets."""
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
import java_lombok_alignment as lombok
from java_type_reference_alignment import SOURCE, EXTENDED_SOURCE, ENUM_SOURCE, AMBIGUOUS_SOURCE, ANNOTATED_PARENT_SOURCE, ANONYMOUS_HIERARCHY_SOURCE, OBJECT_HIERARCHY_SOURCE
from java_collector_alignment import SOURCE as COLLECTOR_SOURCE

CASES = {
    'anonymous-object-hierarchy': (OBJECT_HIERARCHY_SOURCE, False, {}, 2),
    'generated-foreach-callback': ('class Filter {void run(){}} @lombok.Getter class Holder {java.util.List<Filter> values;} class Use{void read(Holder h){((Holder)h).getValues().forEach(filter->{filter.run();});}}', True, {}, 5),
    'anonymous-hierarchy': (ANONYMOUS_HIERARCHY_SOURCE, False, {}, 7),
    'anonymous-declaration-types': ('class Leaf {} class Box<T> {} class Use {void read(){Box<java.util.Map<String,Leaf>> box=new Box<java.util.Map<String,Leaf>>(){Leaf field; Leaf take(Leaf value){return value;}};}}',False,{},4),
    'generated-type-exclusion': ('class Leaf {} @lombok.Data class Holder {String name; java.util.Map<String,Leaf> map; Leaf leaf;} class Explicit {Object echo(Object value){return value;}}', True, {}, 13),
    'signatures-and-callbacks': (SOURCE, False, {}, 25),
    'unknown-annotated-nested': (EXTENDED_SOURCE, False, {}, 19),
    'unicode-crlf': ('// 类型𐐀\r\n' + EXTENDED_SOURCE.replace('\n', '\r\n'), False, {}, 19),
    'enum-interface': (ENUM_SOURCE, False, {}, 10),
    'annotated-hierarchy-inner': (ANNOTATED_PARENT_SOURCE, False, {}, 7),
    'ambiguous-import': (AMBIGUOUS_SOURCE, False,
        {'a/Leaf.java': 'package a;public class Leaf {}', 'b/Leaf.java': 'package b;public class Leaf {}'}, 1),
    'recovered-collector': (COLLECTOR_SOURCE, True, {}, 23),
    'captured-functional-type': (reference.SOURCES['functional-generated-overload'], True, {}, 14),
}


def proof(name, source, modeled, extra, minimum, evidence, native):
    base = evidence / name
    base.mkdir()
    case = models.Models()
    case.setUp()
    try:
        if native:
            case.analyze = reference.native_analyze.__get__(case)
        files = lombok.Lombok.files(case, source) if modeled else {'App.java': source}
        context = case.context('app', files | extra, lombok='model' if modeled else 'off')
        if modeled:
            context = lombok.Lombok.controls(case, context, {'app/lombok.config': reference.CONTROL})
        facts = case.analyze([context])['facts']
        for unit in context['sources']:
            target = base / unit['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(unit['input'], target)
    finally:
        case.doCleanups()
    if modeled:
        (base / 'app/lombok.config').write_text(reference.CONTROL)
    config = base / '.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\njavac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    jar = reference.REFERENCE_JAR
    command = [str(models.JDK25 / 'bin/java'), '-Xmx512m', '-jar', str(jar), 'index', str(base),
        '--source-root', 'app@MAIN=app', '--jdk-home', str(models.JDK8), '--java-version', '8',
        '--lombok', 'ast' if modeled else 'off', '--vm-classpath', 'true',
        '--output', str(base/'index.db'), '--full', '--no-classpath']
    with (base/'reference.log').open('wb') as stream:
        completed = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=120,
            env=dict(os.environ, ANATOMIST_HOME=str(base/'reference-state')))
    if completed.returncode:
        raise RuntimeError((base/'reference.log').read_text()[-5000:])
    (base/'product.json').write_text(json.dumps(facts, ensure_ascii=False)+'\n')
    by = {fact['id']: fact for fact in facts}
    left, right = collections.Counter(), collections.Counter()
    lines = {}
    def offset(path, line, column, last=False):
        if line is None or column is None:
            return None
        if path not in lines:
            lines[path] = (base/path).read_bytes().decode('utf8').splitlines(keepends=True)
        rows = lines[path]
        return sum(len(row.encode('utf8')) for row in rows[:line-1]) + len(
            rows[line-1].encode('utf-16-le')[:2*(column-1+last)].decode('utf-16-le').encode('utf8'))
    for fact in facts:
        if fact['source'] != 'app/App.java' or fact['relation'] not in ('type_reference', 'extends', 'implements'):
            continue
        if fact['origin']=='modeled_override' or (fact['origin']=='lombok_model' and name!='generated-type-exclusion'):
            continue
        parent, target = by[fact['parent']], by[fact['target']]
        span = fact['span'] or {}
        owner = ('lambda', (parent['span'] or {}).get('start')) if parent['attributes']['element_kind']=='LAMBDA' else reference.canonical(parent['signature'])
        if name in ('anonymous-declaration-types', 'anonymous-hierarchy', 'anonymous-object-hierarchy'):owner=('CLASS' if parent['attributes']['element_kind']=='ANONYMOUS_CLASS' else parent['attributes']['element_kind'],(parent['span'] or {}).get('start'),(parent['span'] or {}).get('end'),parent['signature'].rsplit('#',1)[-1] if parent['attributes']['element_kind'] in ('METHOD','CONSTRUCTOR','FIELD','ENUM_CONSTANT') else '')
        left[(fact['source'], owner, reference.canonical(target['signature']), fact['relation'],
              span.get('start'), span.get('end'), fact['attributes'].get('type_context'), fact['status'])] += 1
    with sqlite3.connect((base/'index.db').as_uri()+'?mode=ro', uri=True) as db:
        query = """SELECT n.symbol_id, coalesce(t.symbol_id,e.external_target_fqn), e.source_file,
            e.begin_line,e.begin_column,e.end_line,e.end_column,e.context,e.relation,n.kind,
            n.begin_line,n.begin_column,e.confidence,n.metadata FROM edges e
            JOIN nodes n ON n.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id
            WHERE e.relation IN ('REFERENCES','INHERITS','IMPLEMENTS') AND e.source_file='app/App.java'"""
        for row in db.execute(query):
            signature, target, path, line, col, end_line, end_col, context, relation, kind, ol, oc, confidence, metadata = row
            generated=json.loads(metadata or '{}').get('generator')=='lombok'
            if generated and name!='generated-type-exclusion':
                continue
            if kind not in ('CLASS','ANONYMOUS_CLASS','INTERFACE','ENUM','ANNOTATION','ANNOTATION_TYPE','METHOD','CONSTRUCTOR','FIELD','ENUM_CONSTANT','LAMBDA'):
                continue
            owner = ('lambda', offset(path,ol,oc)) if kind == 'LAMBDA' else reference.canonical(signature,kind)
            if name in ('anonymous-declaration-types', 'anonymous-hierarchy', 'anonymous-object-hierarchy'):
                pos=db.execute('select end_line,end_column from nodes where symbol_id=?',(signature,)).fetchone()
                owner=('CLASS' if kind=='ANONYMOUS_CLASS' else kind,offset(path,ol,oc),offset(path,pos[0],pos[1],True),reference.canonical(signature,kind).rsplit('#',1)[-1] if kind in ('METHOD','CONSTRUCTOR','FIELD','ENUM_CONSTANT') else '')
            right[(path, owner, reference.canonical(target),
                   {'REFERENCES':'type_reference','INHERITS':'extends','IMPLEMENTS':'implements'}[relation],
                   offset(path,line,col),offset(path,end_line,end_col,True),context,
                   'possible' if confidence=='INFERRED' or generated else 'complete')] += 1
    result = reference.compare(left,right)
    result.update(minimum_reference=minimum,minimum_met=sum(right.values())>=minimum,
                  scope=('SOURCE and generated signatures in app/App.java' if name=='generated-type-exclusion' else 'SOURCE field/callable types and anonymous hierarchy; synthetic anonymous signatures excluded' if name=='anonymous-declaration-types' else 'SOURCE declarations and callbacks in app/App.java; generated signatures validated separately'))
    result['accepted'] = result['equal'] and result['minimum_met']
    (base/'diff.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    jar = reference.REFERENCE_JAR
    if reference.digest(jar) != reference.REFERENCE_SHA:
        raise RuntimeError('FROZEN_REFERENCE_JAR_CHANGED')
    evidence = alignment.ROOT/'target/java-alignment/evidence'/('type-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    evidence.mkdir()
    frozen, artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    receipt = dict(reference_commit=reference.REFERENCE_COMMIT, reference_sha256=reference.REFERENCE_SHA,
                   native=args.native,proofs={},inputs=frozen,artifacts=artifacts,all_ok=False,
                   full_semantic_diff_accepted=False)
    started=time.monotonic()
    for name, (source, modeled, extra, minimum) in CASES.items():
        receipt['proofs'][name]=proof(name, source, modeled, extra, minimum, evidence, args.native)
        (evidence/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
        print(name,receipt['proofs'][name]['accepted'],flush=True)
    current, current_artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    receipt.update(changed_inputs=sorted(p for p in frozen.keys()|current.keys() if frozen.get(p)!=current.get(p)),
                   changed_artifacts=sorted(p for p in artifacts.keys()|current_artifacts.keys() if artifacts.get(p)!=current_artifacts.get(p)),
                   reference_unchanged=reference.digest(jar)==reference.REFERENCE_SHA,
                   elapsed_seconds=round(time.monotonic()-started,3))
    receipt['all_ok']=all(p['accepted'] for p in receipt['proofs'].values()) and not receipt['changed_inputs'] and not receipt['changed_artifacts'] and receipt['reference_unchanged']
    (evidence/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    print(evidence/'receipt.json',flush=True)
    return 0 if receipt['all_ok'] else 1


if __name__=='__main__':
    raise SystemExit(main())
