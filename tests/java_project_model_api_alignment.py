#!/usr/bin/env python3
"""Audit every frozen Maven source context; fixtures/publication/capacity remain separate."""
import argparse
import json
import pathlib
import subprocess
import sys
import time

import java_alignment as alignment
from java_model_api_inventory_diff import PROJECTIONS
from java_real_acceptance import atomic_json, digest, verify_frozen
from java_real_compiler_diagnostics import compiler_context

ROOT=pathlib.Path(__file__).resolve().parents[1]


def expected_contexts(manifest):
    modules=manifest.get('modules',[])
    ids=[module.get('id') for module in modules]
    if (set(manifest.get('scopes',[]))!={'MAIN','TEST'} or len(modules)!=40
        or len(set(ids))!=40 or any(not identity for identity in ids)
        or any(module.get('selected',True) is not True or not module.get('resolved_classpath') for module in modules)):
        raise ValueError('MODEL_API_FULL_RESOLVED_REACTOR_REQUIRED')
    return [(identity,scope) for identity in ids for scope in ('MAIN','TEST')]


def accepted_context(row,report):
    return (row.get('exit_code')==0 and report.get('state')=='finished' and report.get('equal') is True
        and report.get('model_api_mode','raw')==row.get('model_api_mode','raw')
        and set(report.get('projections',{}))==set(PROJECTIONS)
        and all(value.get('equal') is True for value in report['projections'].values())
        and report.get('changed_inputs')==[] and report.get('changed_source_inputs')==[]
        and report.get('type_lookup_scope')=='all_context_source_types'
        and report.get('navigation_scope')=='all_context_sources'
        and report.get('native_model_api_executable_sha256')==row.get('native_model_api_executable_sha256')
        and report.get('reference_compilation',{}).get('compilations')==1)


def main():
    parser=argparse.ArgumentParser()
    for name in ('project','inputs-manifest','native-executable','evidence'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    parser.add_argument('--compiler-timeout-seconds',type=int,default=3600)
    parser.add_argument('--lombok-ast',action='store_true')
    args=parser.parse_args()
    if not 1<=args.compiler_timeout_seconds<=3600:parser.error('compiler timeout must be 1..3600')
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    started=time.monotonic();output=evidence/'receipt.json';frozen={}
    report=dict(kind='complete_frozen_maven_model_api_inventory',state='preparing',contexts=[],
        complete_model_api_inventory=False,publication_accepted=False,full_semantic_diff_accepted=False,
        capacity_accepted=False,time_is_performance_gate=False,
        model_api_mode='lombok_ast' if args.lombok_ast else 'raw')
    atomic_json(output,report)
    try:
        project=args.project.resolve();manifest_path=args.inputs_manifest.resolve()
        manifest=json.loads(manifest_path.read_text());expected=expected_contexts(manifest)
        report['frozen_project']=verify_frozen(project)
        report['expected_contexts']=[module+'@'+scope for module,scope in expected]
        frozen={str(path.resolve()):digest(path) for path in
            (manifest_path,args.native_executable,ROOT/'lang-adaptor/target/compiler-bridge.jar',
             ROOT/'lang-adaptor/target/native/libcodeana_java.dylib',ROOT/'lang-adaptor/target/native/manifest.json')}
        for directory in ('classes','test-classes'):
            frozen.update({str(path.resolve()):digest(path) for path in (ROOT/'lang-adaptor/target'/directory).rglob('*.class')})
        report.update(state='running',inputs_manifest_sha256=digest(manifest_path),source_inputs=alignment.input_files(),
            native_model_api_executable_sha256=digest(args.native_executable),
            native_shared_library_sha256=digest(ROOT/'lang-adaptor/target/native/libcodeana_java.dylib'),
            compiler_bridge_sha256=digest(ROOT/'lang-adaptor/target/compiler-bridge.jar'),inputs=frozen)
        atomic_json(output,report)
        for index,(module,scope) in enumerate(expected):
            begin=time.monotonic();directory=evidence/str(index);directory.mkdir()
            row=dict(context=module+'@'+scope,accepted=False,model_api_mode=report['model_api_mode'],
                native_model_api_executable_sha256=report['native_model_api_executable_sha256'])
            try:
                request=compiler_context(project,manifest,module,scope)
                row.update(source_count=len(request['sources']),published_source_count=sum(s['publish'] for s in request['sources']))
                for source in request['sources']:frozen[str((project/source['path']).resolve())]=source['hash']
                for jar in request['classpath']:frozen[str(pathlib.Path(jar).resolve())]=digest(jar)
                if not request['sources']:
                    # Resource-only scopes have no Java model to compare. Keep
                    # their presence explicit instead of dropping the module.
                    row.update(accepted=True,no_java_inputs=True)
                else:
                    compiler=directory/'compiler';inventory=directory/'inventory'
                    command=[sys.executable,str(ROOT/'tests/java_real_compiler_diagnostics.py'),
                        '--project',str(project),'--inputs-manifest',str(manifest_path),'--module',module,'--scope',scope,
                        '--compiler-timeout-seconds',str(args.compiler_timeout_seconds),'--evidence',str(compiler)]
                    with (directory/'compiler.log').open('wb') as log:
                        process=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
                    row['compiler_exit_code']=process.returncode
                    diagnostic=json.loads((compiler/'receipt.json').read_text())
                    if process.returncode or diagnostic.get('compiler_successful') is not True:
                        raise ValueError('MODEL_API_CONTEXT_COMPILER_FAILED: '+str(diagnostic.get('failure',diagnostic.get('compiler_receipt'))))
                    command=[sys.executable,str(ROOT/'tests/java_model_api_inventory_diff.py'),
                        '--project',str(project),'--inputs-manifest',str(manifest_path),
                        '--compiler-request',str(compiler/'request.json'),'--model-archive',str(compiler/'model.bin'),
                        '--model-receipt',str(compiler/'compiler-receipt.json'),
                        '--native-executable',str(args.native_executable.resolve()),'--evidence',str(inventory)]
                    if args.lombok_ast:command.append('--lombok-ast')
                    with (directory/'inventory.log').open('wb') as log:
                        process=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
                    receipt=inventory/'receipt.json';value=json.loads(receipt.read_text())
                    row.update(exit_code=process.returncode,receipt=str(receipt),receipt_sha256=digest(receipt),
                        healthy_published_source_count=value.get('healthy_published_source_count'),
                        invalid_utf8_sources=value.get('invalid_utf8_sources',{}))
                    row['accepted']=accepted_context(row,value)
                    frozen.update(value.get('inputs',{}))
                    reference=inventory/'scratch/exact-reference/frozen-model.bin'
                    if reference.exists():frozen[str(reference)]=value['reference_compilation']['response_sha256']
            except Exception as error:row.update(failure=str(error),failure_type=type(error).__name__)
            row['elapsed_seconds']=round(time.monotonic()-begin,3);report['contexts'].append(row)
            atomic_json(output,report);print(row['context']+': '+('passed' if row['accepted'] else 'failed'),flush=True)
        actual=[row['context'] for row in report['contexts']]
        report['complete_model_api_inventory']=(actual==report['expected_contexts'] and all(row['accepted'] for row in report['contexts']))
    except Exception as error:report.update(failure=str(error),failure_type=type(error).__name__)
    finally:
        report['changed_inputs']=[path for path,sha in frozen.items() if not pathlib.Path(path).is_file() or digest(path)!=sha]
        before=report.get('source_inputs',{});after=alignment.input_files()
        report['changed_source_inputs']=sorted(path for path in before.keys()|after.keys() if before.get(path)!=after.get(path)) if before else []
        report['complete_model_api_inventory']=report['complete_model_api_inventory'] and not report['changed_inputs'] and not report['changed_source_inputs']
        report.update(state='finished',elapsed_seconds=round(time.monotonic()-started,3));atomic_json(output,report)
    print(output,flush=True)
    return 0 if report['complete_model_api_inventory'] else 1


if __name__=='__main__':sys.exit(main())
