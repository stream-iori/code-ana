#!/usr/bin/env python3
"""Strict frozen reference comparison with the annotation dependency deliberately absent."""
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
from java_written_annotation_alignment import WrittenAnnotations
from java_reflection_alignment import Reflection


def proof(name, annotation, imported, evidence, native):
    base = evidence / name
    base.mkdir()
    case = Reflection() if name == "reflection-control" else WrittenAnnotations()
    case.setUp()
    try:
        if native:
            case.analyze = reference.native_analyze.__get__(case)
        context = case.fixture(annotation) if name == "reflection-control" else case.fixture(annotation, imported)
        facts = case.analyze([context])['facts']
        for unit in context['sources']:
            path = base / unit['path']
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(unit['input'], path)
    finally:
        case.doCleanups()
    (base / 'app').mkdir(exist_ok=True)
    (base / 'app/lombok.config').write_text(reference.CONTROL)
    config = base / '.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\njavac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    jar = reference.REFERENCE_JAR
    command = [str(models.JDK25 / 'bin/java'), '-Xmx512m', '-jar', str(jar), 'index', str(base),
               '--source-root', 'app@MAIN=' + ('reflect' if name == 'reflection-control' else 'app'), '--jdk-home', str(models.JDK8), '--java-version', '8',
               '--lombok', 'ast', '--vm-classpath', 'true', '--no-classpath', '--full',
               '--output', str(base / 'index.db')]
    with (base / 'reference.log').open('wb') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=120,
                                env=dict(os.environ, ANATOMIST_HOME=str(base / 'reference-state')))
    if result.returncode:
        raise RuntimeError((base / 'reference.log').read_text()[-4000:])
    by = {fact['id']: fact for fact in facts}
    declarations = collections.Counter((reference.canonical(f['signature']),) for f in facts
        if f['kind']=='declaration' and f['attributes'].get('binding_origin')=='lombok_model')
    left, right = collections.Counter(), collections.Counter()
    reflection_left, reflection_right = collections.Counter(), collections.Counter()
    lines = {}
    def offset(path, line, column, end=False):
        if line is None or column is None:
            return None
        if path not in lines:
            lines[path] = (base / path).read_bytes().decode('utf8').splitlines(keepends=True)
        rows = lines[path]
        return sum(len(row.encode('utf8')) for row in rows[:line-1]) + len(
            rows[line-1].encode('utf-16-le')[:2*(column-1+end)].decode('utf-16-le').encode('utf8'))
    for fact in facts:
        if fact['relation'] == 'reflection_lookup':
            span = fact['span']
            reflection_left[(fact['source'], reference.canonical(by[fact['parent']]['signature']),
                reference.canonical(by[fact['target']]['signature']), span['start'], span['end'],
                fact['attributes'].get('type_context'),fact['status'])] += 1
        if fact['kind'] != 'call_target':
            continue
        site = by[fact['parent']]
        span = site['span']
        left[(fact['source'],span['start'],span['end'],reference.canonical(by[fact['target']]['signature']),fact['status'])] += 1
    expected = collections.Counter()
    with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as db:
        for signature, kind, metadata in db.execute('SELECT symbol_id,kind,metadata FROM nodes'):
            if json.loads(metadata or '{}').get('generator') == 'lombok':
                expected[(reference.canonical(signature,kind),)] += 1
        for row in db.execute('''SELECT o.source_file,s.begin_line,s.begin_column,s.end_line,s.end_column,
                coalesce(n.symbol_id,t.external_target_symbol,t.external_target_fqn),t.resolution_status
                FROM call_site_targets t JOIN call_sites s ON s.site_pk=t.call_site_pk
                JOIN call_site_owners o ON o.owner_pk=s.owner_pk LEFT JOIN nodes n ON n.id=t.target_id'''):
            path,line,column,end_line,end_column,signature,status = row
            right[(path,offset(path,line,column),offset(path,end_line,end_column,True),reference.canonical(signature),
                   {'exact':'resolved','heuristic':'possible'}.get(status,status))] += 1
        for row in db.execute("""SELECT e.source_file,s.symbol_id,t.symbol_id,e.external_target_symbol,
                e.begin_line,e.begin_column,e.end_line,e.end_column,e.context,e.confidence,t.kind
                FROM edges e JOIN nodes s ON s.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id
                WHERE e.relation='REFERENCES' AND json_extract(e.metadata,'$.via')='reflection'"""):
            path,owner,target,external,line,column,end_line,end_column,control,confidence,kind=row
            reflection_right[(path,reference.canonical(owner),reference.canonical(target or external,kind),
                offset(path,line,column),offset(path,end_line,end_column,True),control,
                'possible' if confidence=='INFERRED' else 'complete')] += 1
    report = dict(call_targets=reference.compare(left,right),generated_declarations=reference.compare(declarations,expected),
                  source_hashes={str(p.relative_to(base)):reference.digest(p) for p in (base/'app').rglob('*.java')},
                  annotation_dependency_absent=True,reflection_members=reference.compare(reflection_left,reflection_right),full_semantic_diff_accepted=False)
    report['accepted'] = report['call_targets']['equal'] and report['generated_declarations']['equal'] and report['reflection_members']['equal']
    (base/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
    (base/'diff.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    jar = reference.REFERENCE_JAR
    if reference.digest(jar) != reference.REFERENCE_SHA:
        raise ValueError('REFERENCE_ARTIFACT_CHANGED')
    evidence = alignment.ROOT/'target/java-alignment/evidence'/('written-annotation-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    evidence.mkdir()
    inputs, artifacts = alignment.input_files(),reference.probe_artifacts(args.native)
    cases = {
        'imported': ('@Getter','import lombok.Getter;'),
        'qualified': ('@lombok.Getter',''),
        'absent-import': ('@Getter',''),
        'ambiguous-import': ('@Getter','import lombok.Getter;import other.Getter;'),
        'missing-wildcard': ('@Getter','import lombok.*;'),
        'lazy-negative': ('@Getter(lazy=true)','import lombok.Getter;'),
        'unknown-option-negative': ('@Getter(lazy=runtime())','import lombok.Getter;'),
        'reflection-control': ('try {\n if(flag) { Target.class.getMethod("run"); }\n Target.class.getDeclaredConstructor();\n } catch(Exception error) { Target.class.getMethod("other"); }\n Target.class.getMethod("run");',''),
    }
    report = dict(native=args.native,reference_sha256=reference.REFERENCE_SHA,inputs=inputs,artifacts=artifacts,cases={},all_ok=False)
    try:
        for name, values in cases.items():
            report['cases'][name]=proof(name,*values,evidence,args.native)
    finally:
        current, built=alignment.input_files(),reference.probe_artifacts(args.native)
        report['changed_inputs']=sorted(p for p in inputs.keys()|current.keys() if inputs.get(p)!=current.get(p))
        report['changed_artifacts']=sorted(p for p in artifacts.keys()|built.keys() if artifacts.get(p)!=built.get(p))
        report['reference_unchanged']=reference.digest(jar)==reference.REFERENCE_SHA
        report['all_ok']=len(report['cases'])==len(cases) and all(v['accepted'] for v in report['cases'].values()) and not report['changed_inputs'] and not report['changed_artifacts'] and report['reference_unchanged']
        (evidence/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(evidence/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':
    raise SystemExit(main())
