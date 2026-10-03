#!/usr/bin/env python3
"""Compare actual frozen-project model navigation with the independent SDK8 oracle.

One context is evidence, never a certificate for all Maven scopes or native publication.
"""
import argparse
import collections
import json
import os
import pathlib
import struct
import subprocess
import sys
import time

import java_alignment as alignment
from java_functional_reference_alignment import REFERENCE_SHA, compare, digest
from java_model_tests import JDK25, JDK8
from java_real_acceptance import atomic_json, verify_frozen
from java_real_compiler_diagnostics import compiler_context

ROOT=pathlib.Path(__file__).resolve().parents[1]
PROJECTIONS=('declarations','syntax','sources','type_lookup')


def validate_inputs(project,manifest,request):
    expected=compiler_context(project,manifest,request['module'],request['scope'])
    if request.get('version')!=8 or request.get('context')!=expected['context'] or request.get('world')!=expected['world']:
        raise ValueError('MODEL_API_CONTEXT_MISMATCH')
    fields=('path','hash','module','scope','world','publish')
    keys=lambda rows:[tuple(row.get(key) for key in fields) for row in rows]
    if keys(request['sources'])!=keys(expected['sources']):raise ValueError('MODEL_API_MAVEN_SOURCE_ORDER_OR_SCOPE')
    for row in request['sources']:
        path=pathlib.Path(row['input']).resolve()
        original=(project/row['path']).resolve()
        if not original.is_relative_to(project.resolve()) or digest(path)!=row['hash'] or digest(original)!=row['hash']:
            raise ValueError('MODEL_API_ORIGINAL_SOURCE_DRIFT')
    if [digest(pathlib.Path(p)) for p in request['classpath']]!=[digest(pathlib.Path(p)) for p in expected['classpath']]:
        raise ValueError('MODEL_API_ORDERED_CLASSPATH_DRIFT')
    return expected


def type_queries(path,published):
    names={'__codeana_model_inventory_missing_type__'}
    with path.open('rb') as stream:
        if stream.read(4)!=b'CAF1':raise ValueError('MODEL_API_ARCHIVE_VERSION')
        while prefix:=stream.read(4):
            if len(prefix)!=4:raise ValueError('MODEL_API_ARCHIVE_TRUNCATED')
            size=struct.unpack('>I',prefix)[0]
            if not 1<=size<=8*1024*1024:raise ValueError('MODEL_API_RECORD_BUDGET')
            body=stream.read(size)
            if len(body)!=size:raise ValueError('MODEL_API_ARCHIVE_TRUNCATED')
            row=json.loads(body)
            if row.get('kind')!='row' or row.get('section')!='declarations':continue
            declaration=row['value'];attrs=declaration.get('attributes',{})
            if declaration.get('source') not in published or attrs.get('element_kind') not in ('CLASS','INTERFACE','ENUM','ANNOTATION_TYPE'):continue
            for name in (declaration.get('signature',''),attrs.get('binary_name','')):
                if name:names.add(name)
            binary=attrs.get('binary_name','')
            if binary and attrs.get('nesting_kind') not in ('LOCAL','ANONYMOUS'):names.add(binary.replace('$','.'))
    return sorted(names)


def projections(value):
    encode=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False,separators=(',',':'))
    return dict(declarations=collections.Counter((encode(dict(row,members=sorted(row['members'],key=encode))),) for row in value['rows']),
        syntax=collections.Counter((encode(row),) for row in value['syntax']),
        sources=collections.Counter((encode(dict(source=path,**row)),) for path,row in value['sources'].items()),
        type_lookup=collections.Counter((encode(dict(row,candidates=sorted(row['candidates'],key=encode))),) for row in value['types']))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--project',type=pathlib.Path,required=True)
    parser.add_argument('--inputs-manifest',type=pathlib.Path,required=True)
    parser.add_argument('--compiler-request',type=pathlib.Path,required=True)
    parser.add_argument('--model-archive',type=pathlib.Path,required=True)
    parser.add_argument('--model-receipt',type=pathlib.Path,required=True)
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    args=parser.parse_args();evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    started=time.monotonic();inputs={};report=dict(kind='independent_frozen_project_model_api_inventory',state='preparing',
        equal=False,projections={},publication_accepted=False,native_product_verified=False,
        full_project_all_scopes_accepted=False,full_semantic_diff_accepted=False,capacity_accepted=False)
    output=evidence/'receipt.json';atomic_json(output,report)
    try:
        project=args.project.resolve();report['frozen_project']=verify_frozen(project)
        request=json.loads(args.compiler_request.read_text());manifest=json.loads(args.inputs_manifest.read_text())
        validate_inputs(project,manifest,request)
        receipt=json.loads(args.model_receipt.read_text());archive=args.model_archive.resolve()
        if ('error' in receipt or receipt.get('version')!=8 or receipt.get('context')!=request['context']
            or receipt.get('sha256')!=digest(archive) or receipt.get('bytes')!=archive.stat().st_size):
            raise ValueError('MODEL_API_ACTUAL_COMPILER_ARCHIVE_REQUIRED')
        jar=ROOT.parent/'anatomist/target/anatomist.jar'
        if not jar.exists():jar=ROOT/'target/java-alignment/anatomist/target/anatomist.jar'
        if digest(jar)!=REFERENCE_SHA:raise ValueError('MODEL_API_FROZEN_REFERENCE_DRIFT')
        bridge=ROOT/'lang-adaptor/target/compiler-bridge.jar'
        inputs={str(p.resolve()):digest(p) for p in (args.compiler_request,args.inputs_manifest,args.model_receipt,archive,jar,bridge)}
        inputs.update({str(pathlib.Path(row['input']).resolve()):row['hash'] for row in request['sources']})
        inputs.update({str(pathlib.Path(p).resolve()):digest(pathlib.Path(p)) for p in request['classpath']})
        for directory in ('classes','test-classes'):
            inputs.update({str(p.resolve()):digest(p) for p in (ROOT/'lang-adaptor/target'/directory).rglob('*.class')})
        report.update(context=request['context'],source_count=len(request['sources']),
            published_source_count=sum(bool(s['publish']) for s in request['sources']),
            ordered_classpath_sha256=[digest(pathlib.Path(p)) for p in request['classpath']],
            compiler_request_sha256=digest(args.compiler_request),archive_sha256=digest(archive),
            reference_jar_sha256=REFERENCE_SHA,source_inputs=alignment.input_files())
        selected={s['path'] for s in request['sources'] if s['publish']}
        queries=type_queries(archive,selected)
        if not selected or len(queries)<2:raise ValueError('MODEL_API_NONEMPTY_PUBLISHED_SCOPE_REQUIRED')
        adapted=dict(version=5,facts=False,java_home=str(JDK8),bridge=str(bridge),contexts=[request],
            scratch=str(evidence/'scratch'),compiler_heap_bytes=536870912,compiler_timeout_seconds=3600,
            model_api_inventory=True,model_api_published_only=True,model_api_exact_context=True,model_type_queries=queries,
            model_api_archive=str(archive),model_api_archive_sha256=digest(archive))
        (evidence/'scratch').mkdir();path=evidence/'request.json';atomic_json(path,adapted)
        classes=evidence/'frozen-classes';classes.mkdir()
        cp=[str(ROOT/'lang-adaptor/target/classes'),str(ROOT/'lang-adaptor/target/test-classes')]
        reference_cp=os.pathsep.join([str(classes),*cp,str(jar)])
        with (evidence/'reference-build.log').open('wb') as log:
            subprocess.run([str(JDK25/'bin/javac'),'-cp',reference_cp,'-d',str(classes),
                str(ROOT/'lang-adaptor/src/frozen-test/java/dev/codeana/analysis/DetachedModelApiReferenceProbe.java'),
                str(ROOT/'lang-adaptor/src/frozen-test/java/com/anatomist/core/javac/FrozenExactModelApiContext.java')],
                stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
        product_cp=os.pathsep.join([*cp,(ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()])
        env=os.environ.copy()
        for name in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'):env.pop(name,None)
        results={};report['state']='running';atomic_json(output,report)
        for side,classpath,probe in (('product',product_cp,'dev.codeana.analysis.DetachedModelApiProbe'),
                                    ('reference',reference_cp,'dev.codeana.analysis.DetachedModelApiReferenceProbe')):
            begin=time.monotonic()
            with (evidence/(side+'.json')).open('wb') as stdout,(evidence/(side+'.log')).open('wb') as stderr:
                subprocess.run([str(JDK25/'bin/java'),'-Xmx1024m','-cp',classpath,probe,str(path)],
                    stdout=stdout,stderr=stderr,env=env,check=True,timeout=3600)
            results[side]=json.loads((evidence/(side+'.json')).read_text())
            if set(results[side]['sources'])!=selected:raise ValueError('MODEL_API_PUBLISHED_INVENTORY_INCOMPLETE: '+side)
            if results[side].get('closed') is not True:raise ValueError('MODEL_API_LIFETIME_NOT_VERIFIED')
            report[side+'_elapsed_seconds']=round(time.monotonic()-begin,3);atomic_json(output,report)
        witness=json.loads((evidence/'scratch/exact-reference/frozen-worker-receipt.json').read_text())
        raw_reference=evidence/'scratch/exact-reference/frozen-model.bin'
        if (witness.get('compilations')!=1 or witness.get('units')!=len(request['sources'])
            or witness.get('processing') is not False or witness.get('decode_view')!='one_unit_at_a_time'
            or witness.get('response_bytes')!=raw_reference.stat().st_size
            or witness.get('response_sha256')!=digest(raw_reference)):
            raise ValueError('MODEL_API_EXACT_REFERENCE_COMPILATION_REQUIRED')
        report['reference_compilation']=witness
        left,right=map(projections,(results['product'],results['reference']))
        report['projections']={name:compare(left[name],right[name]) for name in PROJECTIONS}
        report['equal']=all(row['equal'] for row in report['projections'].values())
    except Exception as error:report.update(failure=str(error),failure_type=type(error).__name__)
    finally:
        report['changed_inputs']=[name for name,sha in inputs.items() if not pathlib.Path(name).is_file() or digest(pathlib.Path(name))!=sha]
        before=report.get('source_inputs',{});after=alignment.input_files()
        report['changed_source_inputs']=sorted(name for name in before.keys()|after.keys() if before.get(name)!=after.get(name)) if before else []
        report['equal']=report['equal'] and not report['changed_inputs'] and not report['changed_source_inputs']
        report.update(state='finished',elapsed_seconds=round(time.monotonic()-started,3));atomic_json(output,report)
    print(output,flush=True)
    return 0 if report['equal'] else 1


if __name__=='__main__':sys.exit(main())
