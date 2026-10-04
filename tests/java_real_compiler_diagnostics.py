#!/usr/bin/env python3
"""Isolate one frozen Maven javac context; never claim native/product acceptance."""
import argparse
import json
import os
import pathlib
import struct
import subprocess
import time

from java_capacity import Rss
from java_model_tests import JDK8, key
from java_real_acceptance import (ROOT, CompilerDiagnostics, atomic_json, digest,
                                  process_identity, stop, verify_frozen)


def compiler_context(project, manifest, module_id, scope):
    modules = {m['id']:m for m in manifest['modules']}
    if len(modules) != len(manifest['modules']):
        raise ValueError('AMBIGUOUS_MAVEN_MODULE')
    module = modules[module_id]
    if not module['resolved_classpath'] or scope not in manifest['scopes']:
        raise ValueError('RESOLVED_SCOPE_REQUIRED')
    selected = {module_id, *module['dependencies'][scope]}
    sources = {}
    for identity in sorted(selected):
        for root in modules[identity]['source_roots']:
            if root['scope'] != 'MAIN' and not (identity == module_id and scope == 'TEST' and root['scope'] == 'TEST'):
                continue
            directory = (project/modules[identity]['root']/root['path']).resolve()
            if not directory.is_relative_to(project.resolve()):
                raise ValueError('SOURCE_ROOT_OUTSIDE_FROZEN_PROJECT')
            for path in sorted(directory.rglob('*.java')):
                if path.is_symlink():
                    raise ValueError('SOURCE_SYMLINK')
                relative = str(path.relative_to(project.resolve()))
                row = dict(path=relative,input=str(path),hash=digest(path),module=identity,
                    scope=root['scope'],world=key('world',identity,root['scope']),
                    publish=identity==module_id and root['scope']==scope)
                if relative in sources and sources[relative] != row:
                    raise ValueError('AMBIGUOUS_MAVEN_SOURCE')
                sources[relative] = row
    jars = module['classpath'][scope]
    if any(digest(pathlib.Path(jar['path']))!=jar['sha256'] for jar in jars):
        raise ValueError('MAVEN_CLASSPATH_DRIFT')
    return dict(version=8,context=module_id+'@'+scope,module=module_id,scope=scope,
        world=key('world',module_id,scope),sources=list(sources.values()),
        classpath=[jar['path'] for jar in jars],full_auxiliary_model=False,
        type_graph=True,model_record_limit=5000000,model_archive_bytes=1073741824)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--project',type=pathlib.Path,required=True)
    parser.add_argument('--inputs-manifest',type=pathlib.Path,required=True)
    parser.add_argument('--module',default='app/core/service')
    parser.add_argument('--scope',choices=('MAIN','TEST'),default='TEST')
    parser.add_argument('--compiler-timeout-seconds',type=int,default=3600)
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    args=parser.parse_args()
    if not 1<=args.compiler_timeout_seconds<=3600:parser.error('compiler timeout must be 1..3600')
    args.evidence.mkdir(parents=True,exist_ok=False)
    project=args.project.resolve();evidence=args.evidence.resolve()
    report=dict(kind='isolated_frozen_maven_compiler_diagnostic',state='preparing',
        publication_accepted=False,full_semantic_diff_accepted=False,capacity_accepted=False,
        native_product_verified=False,time_is_performance_gate=False)
    output=evidence/'receipt.json';atomic_json(output,report)
    process=None;started=time.monotonic()
    try:
        report['frozen_project']=verify_frozen(project)
        manifest=json.loads(args.inputs_manifest.read_text())
        request=compiler_context(project,manifest,args.module,args.scope)
        report['manifest_sha256']=digest(args.inputs_manifest)
        bridge=ROOT/'lang-adaptor/target/compiler-bridge.jar'
        report['bridge_sha256']=digest(bridge)
        report['compiler_timeout_seconds']=args.compiler_timeout_seconds
        report['source_sha256']={row['path']:row['hash'] for row in request['sources']}
        report['classpath_sha256']={row['path']:row['sha256'] for row in
            next(m for m in manifest['modules'] if m['id']==args.module)['classpath'][args.scope]}
        report['published_source_count']=sum(row['publish'] for row in request['sources'])
        request_path=evidence/'request.json';atomic_json(request_path,request)
        model=evidence/'model.bin';result=evidence/'compiler-receipt.json'
        command=json.dumps(dict(request=str(request_path),result=str(model),receipt=str(result),nonce='diagnostic')).encode()
        env=os.environ.copy()
        for option in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'):env.pop(option,None)
        with (evidence/'compiler.log').open('wb') as log:
            process=subprocess.Popen([str(JDK8/'bin/java'),'-Xmx512m','-cp',
                str(bridge)+os.pathsep+str(JDK8/'lib/tools.jar'),
                'dev.codeana.compiler.CompilerBridge','--session'],stdin=subprocess.PIPE,
                stdout=log,stderr=log,cwd=evidence,env=env,start_new_session=True)
            identity=process_identity(process.pid)
            process.stdin.write(struct.pack('>i',len(command))+command);process.stdin.flush()
            diagnostics=CompilerDiagnostics(evidence)
            phase=dict(context=request['context'],phase='compiler_model')
            report.update(state='running',compiler_pid=process.pid)
            atomic_json(output,report)
            with Rss(process.pid) as usage:
                tick=time.monotonic()
                while not result.exists():
                    if process.poll() is not None:raise RuntimeError('COMPILER_EXITED: '+str(process.returncode))
                    if time.monotonic()-tick>args.compiler_timeout_seconds:raise TimeoutError('COMPILER_TIMEOUT')
                    diagnostics.sample(phase,{process.pid:(str(JDK8/'bin/java'),identity)})
                    report.update(elapsed_seconds=round(time.monotonic()-started,3),
                        process_tree_peak_bytes=usage.peak,thread_dump_count=len(diagnostics.samples))
                    atomic_json(output,report);time.sleep(1)
            report['compiler_receipt']=json.loads(result.read_text())
            report['compiler_successful']='error' not in report['compiler_receipt']
            # The measured parent is a compiler JVM, not a daemon. Do not apply
            # the daemon's 512 MiB capacity assertion to this diagnostic run.
            report['rss']=dict(usage.summary(),compiler_peak_bytes=usage.daemon_peak)
            if not report['rss']['sampling_complete']:
                report['rss_failure']='COMPILER_RSS_SAMPLER_INCOMPLETE'
            if report['compiler_successful']:
                if report['compiler_receipt']['sha256']!=digest(model):raise ValueError('COMPILER_MODEL_DRIFT')
                report['model_sha256']=digest(model)
    except Exception as error:
        report.update(failure=str(error),failure_type=type(error).__name__)
    finally:
        if process:
            if process.poll() is None:
                process.stdin.close()
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:report['cleanup_exit_code']=stop(process)
            report['compiler_exit_code']=process.returncode
            report['unexpected_signal_observed']=process.returncode<0 and 'cleanup_exit_code' not in report
        if 'source_sha256' in report:
            report['changed_sources']=[path for path,checksum in report['source_sha256'].items() if digest(project/path)!=checksum]
            report['changed_classpath']=[path for path,checksum in report['classpath_sha256'].items() if digest(pathlib.Path(path))!=checksum]
            report['manifest_changed']=digest(args.inputs_manifest)!=report['manifest_sha256']
            report['bridge_changed']=digest(bridge)!=report['bridge_sha256']
        report.update(state='finished',elapsed_seconds=round(time.monotonic()-started,3));atomic_json(output,report)
    print(output,flush=True)
    return 0 if report.get('compiler_successful') and not report.get('failure') and not any(
        report.get(key) for key in ('changed_sources','changed_classpath','manifest_changed','bridge_changed','unexpected_signal_observed')) else 1


if __name__=='__main__':raise SystemExit(main())
