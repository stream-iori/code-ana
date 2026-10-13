#!/usr/bin/env python3
"""Run actual release-package CLI/daemon/native smoke without rebuilding frozen artifacts.

This certifies the packaged runtime only. All Java parity and exclusive capacity
gates remain independently required; this fixture does not sign final acceptance.
"""
import argparse
import json
import os
import pathlib
import subprocess
import shutil
from java_product_runtime import sha
import tempfile
import time

import java_product_runtime as alignment
from acceptance_support import sha256
from java_product_runtime import inventory


def atomic_binary_copy(source, destination):
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.release-test-', delete=False) as stream:
        stage = pathlib.Path(stream.name)
    try:
        shutil.copy2(source, stage)
        os.replace(stage, destination)
    finally:
        stage.unlink(missing_ok=True)


def release_artifacts():
    """Freeze the actual packaging inputs, including the migrated adapter bundle."""
    root = alignment.ROOT
    files = [root / 'target/release' / name for name in
             ('codeana-cli', 'codeana-daemon', 'codeana-java-worker')]
    files += [root / 'lang-java-adapotr/target' / name for name in
              ('compiler-bridge.jar', 'maven-input-worker.jar',
               'native/libcodeana_java.dylib', 'native/manifest.json')]
    return {str(path.relative_to(root)): sha256(path) for path in files}


def packaged_fixture_smoke(package, evidence, binaries=None):
    """Exercise shipped source/config and automatic daemon start, not a generated fixture."""
    with tempfile.TemporaryDirectory(prefix='codeana-release-demo-') as folder:
        workspace=pathlib.Path(folder);project=workspace/'project';home=workspace/'home'
        shutil.copytree(package/'fixtures/base',project);home.mkdir(mode=0o700)
        jdk=pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu'
        (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\n'
            'max_worker_memory_bytes=536870912\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(jdk))+'\n')
        command=[str((binaries or package/'bin')/'codeana-cli'),'--home',str(home),'--project',str(project)]
        steps=[]
        primary_failure = None
        try:
            for name in ('index','calls','source'):
                result=subprocess.run(command+[name]+([] if name=='index' else ['main']),
                    capture_output=True,text=True,timeout=180)
                (evidence/(name+'.stdout')).write_text(result.stdout)
                (evidence/(name+'.stderr')).write_text(result.stderr)
                records=[json.loads(line[7:]) for line in result.stdout.splitlines() if line.startswith('record ')]
                terminals=[json.loads(line[9:]) for line in result.stdout.splitlines() if line.startswith('terminal ')]
                if result.returncode or len(terminals)!=1 or terminals[0]['execution']!='completed' or not records:
                    raise ValueError('PACKAGED_FIXTURE_QUERY_FAILED: '+name+' '+result.stdout[-2000:]+' '+result.stderr[-2000:])
                if name=='index' and (terminals[0]['publication']!='published'
                    or terminals[0]['evidence'].get('semantic') is not True or records[0].get('facts',0)<=0):
                    raise ValueError('PACKAGED_FIXTURE_EMPTY_OR_INCOMPLETE_INDEX')
                steps.append(dict(name=name,records=len(records),exit_code=result.returncode))
        except BaseException as error:
            primary_failure = error
            raise
        finally:
            daemon_log = home/'daemon.log'
            if daemon_log.exists():
                shutil.copy2(daemon_log, evidence/'daemon.log')
            stopped=subprocess.run(command+['daemon','stop'],capture_output=True,text=True,timeout=30)
            (evidence/'stop.stdout').write_text(stopped.stdout)
            (evidence/'stop.stderr').write_text(stopped.stderr)
            if stopped.returncode and primary_failure is None:
                raise ValueError('PACKAGED_FIXTURE_DAEMON_STOP_FAILED')
        return steps


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=pathlib.Path)
    parser.add_argument('--runtime-root',type=pathlib.Path)
    parser.add_argument('--bin-root',type=pathlib.Path,
        help='Keep existing application paths; binaries and installed bundle must match the package byte-for-byte')
    parser.add_argument('--stage-release',action='store_true',
        help='Temporarily stage package binaries into --bin-root, archive and restore original bytes')
    args=parser.parse_args()
    if args.stage_release and not args.bin_root:
        parser.error('--stage-release requires --bin-root')
    if args.runtime_root:
        alignment.ROOT=args.runtime_root.resolve()
    base=(args.evidence or alignment.ROOT/'target/java-alignment/evidence'/
          ('release-package-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))).resolve()
    base.mkdir(parents=True,exist_ok=False)
    frozen=alignment.input_files();artifacts=release_artifacts()
    package=base/'package';started=time.monotonic()
    report=dict(kind='release_package_real_runtime',all_ok=False,full_semantic_diff_accepted=False,
                final_capacity_accepted=False,inputs=frozen,artifacts=artifacts,gates=[])
    original_binaries={}
    try:
        with (base/'package.log').open('wb') as log:
            subprocess.run(['./scripts/package-java.sh','--from-build','--output',str(package)],
                           cwd=alignment.ROOT,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
        before=inventory(package)
        entries=(package/'SHA256SUMS').read_text().splitlines()
        expected={}
        for entry in entries:
            checksum,path=entry.split('  ',1)
            target=package/path
            if not target.resolve().is_relative_to(package):raise ValueError('PACKAGE_CHECKSUM_PATH')
            if sha256(target)!=checksum:raise ValueError('PACKAGE_CHECKSUM_MISMATCH: '+path)
            if path in expected:raise ValueError('DUPLICATE_PACKAGE_CHECKSUM: '+path)
            expected[path]=checksum
        if expected!={key:value for key,value in before.items() if key!='SHA256SUMS'}:
            raise ValueError('INCOMPLETE_PACKAGE_CHECKSUMS')
        binaries = args.bin_root.resolve() if args.bin_root else package/'bin'
        if args.stage_release:
            active=subprocess.check_output(['ps','-axo','pid=,comm='],text=True)
            for line in active.splitlines():
                fields=line.strip().split(None,1)
                if len(fields)==2 and fields[1].startswith(str(binaries)+'/codeana-'):
                    raise ValueError('STOP_RUNTIME_BEFORE_RELEASE_TEST: '+fields[0])
            saved=base/'original-binaries';saved.mkdir()
            for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
                path=binaries/name
                shutil.copy2(path,saved/name)
                original_binaries[path]=saved/name
                atomic_binary_copy(package/'bin'/name,path)
            report['temporarily_staged_release']=True
        if args.bin_root:
            for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
                if sha256(binaries/name) != sha256(package/'bin'/name):
                    raise ValueError('PACKAGE_STABLE_BINARY_MISMATCH: '+name)
            for name in ('native/libcodeana_java.dylib','compiler-bridge.jar','maven-input-worker.jar','native/manifest.json'):
                if sha256(binaries.parent/'lib/java'/name) != sha256(package/'lib/java'/name):
                    raise ValueError('PACKAGE_STABLE_BUNDLE_MISMATCH: '+name)
        report['execution_binary_directory']=str(binaries)
        report['package_bytes_verified_at_execution_paths']=bool(args.bin_root)
        environment=dict(os.environ,CODEANA_TEST_BIN=str(binaries))
        for name, script, selector in [('typed_graph', 'java_graph_storage.py', None),
                                       ('wrong_jdk', 'java_native_environment.py', None)]:
            command=[os.sys.executable,'-m','unittest','discover','-s','tests','-p',script,'-v']
            if selector:
                command += ['-k', selector]
            log=base/(name+'.log')
            with log.open('wb') as stream:
                result=subprocess.run(command,cwd=alignment.ROOT,env=environment,stdout=stream,
                                      stderr=subprocess.STDOUT,timeout=900)
            report['gates'].append(dict(name=name,exit_code=result.returncode,log_sha256=sha256(log)))
        demo=base/'shipped-fixture';demo.mkdir()
        report['shipped_fixture_steps']=packaged_fixture_smoke(package,demo,binaries)
        report['gates'].append(dict(name='shipped_fixture_automatic_daemon_index_calls_source',exit_code=0))
        current_package=inventory(package)
        current_inputs=alignment.input_files();current_artifacts=release_artifacts()
        report.update(package_files=before,changed_package=sorted(key for key in before.keys()|current_package.keys()
            if before.get(key)!=current_package.get(key)))
        report['changed_inputs']=sorted(key for key in frozen.keys()|current_inputs.keys()
            if frozen.get(key)!=current_inputs.get(key))
        report['changed_artifacts']=sorted(key for key in artifacts.keys()|current_artifacts.keys()
            if artifacts.get(key)!=current_artifacts.get(key))
        report['all_ok']=len(report['gates'])==3 and all(g['exit_code']==0 for g in report['gates']) and not any(
            report[key] for key in ('changed_package','changed_inputs','changed_artifacts'))
    except Exception as failure:
        report.update(failure=str(failure),failure_type=type(failure).__name__)
    finally:
        for path, saved in original_binaries.items():
            atomic_binary_copy(saved,path)
        if original_binaries:
            report['original_binaries_restored']=all(sha(path)==sha(saved) for path,saved in original_binaries.items())
    report['elapsed_seconds']=round(time.monotonic()-started,3)
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
