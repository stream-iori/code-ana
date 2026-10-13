#!/usr/bin/env python3
"""A genuine C ABI SIGSEGV must not replace a daemon's published Java revision."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import time

import product_support


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    case = product_support.JavaProductHarness()
    case.setUp()
    previous_bin = product_support.BIN
    saved_bundle=None
    try:
        good = case.cli('index', timeout=150)[1]['revision']
        case.stop()
        # Keep the already authorized executable and bundle paths. Only this
        # stopped test uses the temporary fault library, restored in finally.
        binary_dir=previous_bin
        source_bundle=(previous_bin/'../lib/java').resolve()
        native_dir=source_bundle/'native'
        if not (native_dir/'manifest.json').is_file():
            raise AssertionError('STABLE_INSTALLED_NATIVE_BUNDLE_REQUIRED')
        saved_bundle=case.root/'saved-native';saved_bundle.mkdir()
        for name in ('libcodeana_java.dylib','manifest.json'):shutil.copy2(native_dir/name,saved_bundle/name)
        crash_source = case.root / 'native-crash.c'
        crash_source.write_text('''
int graal_create_isolate(void *params, void **isolate, void **thread) { *isolate=(void*)1; *thread=(void*)1; return 0; }
int graal_tear_down_isolate(void *thread) { return 0; }
int codeana_java_abi_version(void *thread) { return 4; }
int codeana_java_set_completion_fd(void *thread, unsigned long long task, int fd) { return 0; }
unsigned long codeana_java_heap_bytes(void *thread) { return 268435456; }
unsigned long codeana_java_session_create(void *thread) { return 1; }
int codeana_java_input_chunk(void *thread, unsigned long task, const char *data, int length) { return 0; }
int codeana_java_seal_input(void *thread, unsigned long task) { return 0; }
int codeana_java_analyze_start(void *thread, unsigned long task) { *(volatile int*)0=1; return 0; }
''')
        fault_library=case.root/'libcodeana_java.dylib'
        subprocess.run(['cc','-dynamiclib','-o',str(fault_library),str(crash_source)],check=True)
        library = native_dir / 'libcodeana_java.dylib'
        os.replace(fault_library,library)
        subprocess.run(['codesign', '--force', '--sign', '-', '--identifier', 'dev.codeana.fixture.native.' + case.root.name, str(library)], check=True, capture_output=True)
        manifest = json.loads((source_bundle / 'native/manifest.json').read_text())
        manifest['files'] = {
            'native/' + library.name: sha(library),
            'compiler-bridge.jar': sha(source_bundle/'compiler-bridge.jar'),
            'maven-input-worker.jar': sha(source_bundle/'maven-input-worker.jar'),
        }
        (native_dir / 'manifest.json').write_text(json.dumps(manifest))

        product_support.BIN = binary_dir
        case.log = open(case.root / 'daemon.log', 'a')
        case.daemon = subprocess.Popen(
            [str(binary_dir / 'codeana-daemon'), '--home', str(case.home)],
            stdout=case.log, stderr=case.log, env=dict(os.environ, CODEANA_JAVA_BACKEND='native'))
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if case.daemon.poll() is not None:
                raise AssertionError(case.daemon_log())
            if (case.home / 'endpoint.json').exists():
                status = subprocess.run(
                    [str(binary_dir / 'codeana-cli'), '--home', str(case.home), 'daemon', 'status'],
                    capture_output=True, timeout=3)
                if status.returncode == 0:
                    break
            time.sleep(.03)
        else:
            raise AssertionError('daemon restart timeout: ' + case.daemon_log())

        source = case.project / 'src/p/App.java'
        source.write_text(source.read_text() + '\n// force a new analysis\n')
        failed = subprocess.run(
            [str(binary_dir / 'codeana-cli'), '--home', str(case.home),
             '--project', str(case.project), 'index', '--full'],
            capture_output=True, text=True, timeout=90)
        assert failed.returncode != 0, failed.stdout
        evidence = failed.stdout + failed.stderr + case.daemon_log()
        assert 'signal: 11' in evidence or 'SIGSEGV' in evidence, evidence
        assert case.daemon.poll() is None, case.daemon_log()
        assert case.cli('overview')[1]['revision'] == good
        until = time.monotonic() + 5
        while time.monotonic() < until:
            rows = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,stat=,comm='], text=True)
            workers = [line for line in rows.splitlines()
                       if (fields := line.split(None, 3)) and len(fields) == 4
                       and int(fields[1]) == case.daemon.pid
                       and 'codeana-java-worker' in fields[3]
                       and not fields[2].startswith('Z')]
            if not workers:
                break
            time.sleep(.05)
        assert not workers, workers
        print('daemon native SIGSEGV: old head preserved, daemon alive, worker reaped')
    except BaseException as failure:
        directory = product_support.ROOT/'target/java-alignment/evidence'/('native-crash-failure-'+time.strftime('%Y%m%d-%H%M%S'))
        directory.mkdir(parents=True, exist_ok=True)
        evidence = str(failure)+'\n'+case.daemon_log()
        (directory/'failure.log').write_text(evidence)
        (directory/'receipt.json').write_text(json.dumps(dict(
            accepted=False, failure_type=type(failure).__name__, failure=str(failure),
            sigkill_observed='signal: 9' in evidence or 'SIGKILL' in evidence,
            expected_signal='SIGSEGV', daemon_pid=case.daemon.pid if case.daemon else None,
            worker_binary=str(binary_dir/'codeana-java-worker')), indent=2)+'\n')
        print('Evidence: '+str(directory.relative_to(product_support.ROOT)), flush=True)
        raise
    finally:
        case.stop()
        if saved_bundle is not None:
            for name in ('libcodeana_java.dylib','manifest.json'):
                temporary=native_dir/('.restore-'+name)
                shutil.copy2(saved_bundle/name,temporary)
                os.replace(temporary,native_dir/name)
        product_support.BIN = previous_bin
        case.doCleanups()


if __name__ == '__main__':
    main()
