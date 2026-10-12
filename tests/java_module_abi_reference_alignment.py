#!/usr/bin/env python3
"""Independent frozen Maven module proof for imported generated Builder local types."""
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
from java_module_abi_boundary_alignment import ModuleBoundary


def proof(evidence, name, extra, native):
    base = evidence / name
    base.mkdir()
    case = ModuleBoundary()
    case.setUp()
    try:
        if native:
            case.analyze = reference.native_analyze.__get__(case)
        use, library = case.plan(extra)
        result = case.analyze([use, library], cache=True)
        for unit in use['sources']:
            target = base / unit['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(unit['input'], target)
    finally:
        case.doCleanups()
    namespace = 'http://maven.apache.org/POM/4.0.0'
    (base / 'pom.xml').write_text(
        '<project xmlns="' + namespace + '"><modelVersion>4.0.0</modelVersion>'
        '<groupId>dev.codeana.fixture</groupId><artifactId>reactor</artifactId><version>1</version>'
        '<packaging>pom</packaging><modules><module>lib</module><module>use</module></modules>'
        '<properties><maven.compiler.source>1.8</maven.compiler.source>'
        '<maven.compiler.target>1.8</maven.compiler.target></properties></project>')
    for module in ('lib', 'use'):
        dependency = (
            '<dependencies><dependency><groupId>dev.codeana.fixture</groupId>'
            '<artifactId>lib</artifactId><version>1</version></dependency></dependencies>'
            if module == 'use' else '')
        (base / module / 'pom.xml').write_text(
            '<project xmlns="' + namespace + '"><modelVersion>4.0.0</modelVersion>'
            '<parent><groupId>dev.codeana.fixture</groupId><artifactId>reactor</artifactId>'
            '<version>1</version></parent><artifactId>' + module + '</artifactId>' + dependency
            + '<build><sourceDirectory>.</sourceDirectory></build></project>')
    (base / 'lombok.config').write_text(reference.CONTROL)
    config = base / '.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\n'
                      'javac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    command = [str(models.JDK25 / 'bin/java'), '-Xmx512m', '-jar',
               str(reference.REFERENCE_JAR), 'index', str(base),
               '--jdk-home', str(models.JDK8), '--java-version', '8', '--lombok', 'ast',
               '--vm-classpath', 'true', '--no-classpath', '--full', '--output', str(base / 'index.db')]
    environment = dict(os.environ, ANATOMIST_HOME=str(base / 'reference-state'))
    environment['PATH'] = str(pathlib.Path.home() / '.sdkman/candidates/maven/current/bin') + os.pathsep + environment['PATH']
    with (base / 'reference.log').open('wb') as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                   timeout=180, env=environment)
    if completed.returncode:
        raise RuntimeError((base / 'reference.log').read_text()[-5000:])
    facts = result['facts']
    (base / 'product.json').write_text(json.dumps(facts, ensure_ascii=False) + '\n')
    by = {fact['id']: fact for fact in facts}
    left, right = collections.Counter(), collections.Counter()
    for fact in facts:
        if fact['kind'] == 'call_target' and fact['source'] == 'use/Use.java':
            span = by[fact['parent']]['span']
            left[(span['start'], span['end'], reference.canonical(by[fact['target']]['signature']), fact['status'])] += 1
    source = (base / 'use/Use.java').read_bytes().decode('utf8').splitlines(keepends=True)
    def offset(line, column, last=False):
        return sum(len(row.encode('utf8')) for row in source[:line-1]) + len(
            source[line-1].encode('utf-16-le')[:2*(column-1+last)].decode('utf-16-le').encode('utf8'))
    with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as database:
        for row in database.execute('''SELECT s.begin_line,s.begin_column,s.end_line,s.end_column,
                coalesce(n.symbol_id,t.external_target_symbol,t.external_target_fqn),t.resolution_status
                FROM call_site_targets t JOIN call_sites s ON s.site_pk=t.call_site_pk
                JOIN call_site_owners o ON o.owner_pk=s.owner_pk LEFT JOIN nodes n ON n.id=t.target_id
                WHERE o.source_file='use/Use.java' '''):
            line, column, end_line, end_column, signature, status = row
            right[(offset(line, column), offset(end_line, end_column, True), reference.canonical(signature),
                   {'heuristic': 'possible', 'exact': 'resolved'}[status])] += 1
    comparison = reference.compare(left, right)
    comparison['expected_reference_targets'] = 1 if extra else 4
    comparison['accepted'] = comparison['equal'] and comparison['reference'] == comparison['expected_reference_targets']
    (base / 'diff.json').write_text(json.dumps(comparison, indent=2) + '\n')
    return comparison


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    jar = reference.REFERENCE_JAR
    if reference.digest(jar) != reference.REFERENCE_SHA:
        raise ValueError('FROZEN_REFERENCE_JAR_CHANGED')
    evidence = alignment.ROOT / 'target/java-alignment/evidence' / ('module-abi-reference-' + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir()
    inputs, artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    cases = {name: proof(evidence, name, extra, args.native) for name, extra in (
        ('complete-builder', ''), ('partial-delegate', '@lombok.experimental.Delegate String delegate;'))}
    current_inputs, current_artifacts = alignment.input_files(), reference.probe_artifacts(args.native)
    changed_inputs = sorted(p for p in inputs.keys() | current_inputs.keys() if inputs.get(p) != current_inputs.get(p))
    changed_artifacts = sorted(p for p in artifacts.keys() | current_artifacts.keys() if artifacts.get(p) != current_artifacts.get(p))
    receipt = dict(native=args.native, cases=cases, inputs=inputs, artifacts=artifacts,
                   reference_sha256=reference.REFERENCE_SHA, changed_inputs=changed_inputs,
                   changed_artifacts=changed_artifacts, full_semantic_diff_accepted=False,
                   all_ok=all(case['accepted'] for case in cases.values()) and not changed_inputs and not changed_artifacts)
    (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(str(evidence / 'receipt.json'))
    return 0 if receipt['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
