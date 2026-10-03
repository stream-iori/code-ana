#!/usr/bin/env python3
"""Frozen-project publication with durable receipts and recoverable fixture configuration.

Publication is a prerequisite, never a proof of Anatomist parity or performance.
"""
import argparse
import fcntl
import hashlib
import json
import os
import pathlib
import signal
import sqlite3
import subprocess
import time

import java_alignment
from java_capacity import Rss

ROOT = java_alignment.ROOT


def digest(path):
    value = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            value.update(chunk)
    return value.hexdigest()


def artifacts():
    # Match release daemon installed_library(): sibling ../lib/java, no development fallback.
    binary = ROOT / 'target/release'
    bundle = (binary / '../lib/java').resolve()
    files = [binary / name for name in ('codeana-daemon', 'codeana-cli', 'codeana-java-worker')]
    files += [bundle / name for name in ('compiler-bridge.jar', 'maven-input-worker.jar',
                                       'native/libcodeana_java.dylib', 'native/manifest.json')]
    return {str(path.relative_to(ROOT)): digest(path) for path in files}


def atomic_bytes(path, body):
    path = pathlib.Path(path)
    if path.is_symlink():
        raise ValueError('ACCEPTANCE_SYMLINK: ' + str(path))
    temporary = path.with_name(path.name + '.tmp-' + str(os.getpid()))
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(body)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())


def process_identity(pid):
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart=', '-o', 'comm='],
                            capture_output=True, text=True, timeout=3)
    if result.returncode == 1 and not result.stdout.strip() and not result.stderr.strip():
        return None
    if result.returncode != 0 or not result.stdout.strip():
        raise RuntimeError('ACCEPTANCE_PROCESS_IDENTITY_UNAVAILABLE: ' + str(pid) + ': ' + result.stderr.strip())
    return dict(pid=pid, identity=result.stdout.strip())


def still_alive(identity):
    return identity is not None and process_identity(identity['pid']) == identity


def publication_accepted(report):
    return bool(report.get('published') and report.get('exit_code') == 0
                and report.get('query_exit_code') == 0
                and report.get('catalog_commits') and report.get('catalog_heads'))


class ConfigLease:
    """A project lock and write-ahead journal; recovery never stops an existing run."""
    def __init__(self, project, evidence):
        self.config = project / '.code-ana/config.toml'
        self.evidence = evidence
        self.journal = project / '.code-ana/acceptance-run.json'
        self.lock = None
        self.state = None

    def acquire(self):
        path = self.journal.with_suffix('.lock')
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        self.lock = os.fdopen(descriptor, 'a')
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if self.journal.exists():
                if self.journal.is_symlink():
                    raise ValueError('ACCEPTANCE_SYMLINK')
                previous = json.loads(self.journal.read_text())
                if any(still_alive(value) for value in previous['processes'].values()):
                    raise RuntimeError('PREVIOUS_ACCEPTANCE_PROCESS_ALIVE')
                self._restore(previous)
                atomic_json(self.evidence / 'recovered-run.json', previous)
                self.journal.unlink()
        except BaseException:
            self.lock.close()
            self.lock = None
            raise

    def apply(self, body):
        if self.lock is None:
            raise RuntimeError('ACCEPTANCE_CONFIG_NOT_LOCKED')
        original = self.config.read_bytes()
        backup = self.evidence / 'project-config.original.toml'
        applied = self.evidence / 'project-config.applied.toml'
        atomic_bytes(backup, original)
        atomic_bytes(applied, body)
        self.state = dict(evidence=str(self.evidence), original=str(backup),
                          original_sha256=hashlib.sha256(original).hexdigest(),
                          applied_sha256=hashlib.sha256(body).hexdigest(),
                          processes={'harness': process_identity(os.getpid())})
        atomic_json(self.journal, self.state)
        atomic_bytes(self.config, body)

    def process(self, role, pid):
        self.state['processes'][role] = process_identity(pid)
        atomic_json(self.journal, self.state)

    def _restore(self, state):
        backup = pathlib.Path(state['original'])
        if backup.is_symlink() or digest(backup) != state['original_sha256']:
            raise ValueError('ACCEPTANCE_BACKUP_CHANGED')
        actual = digest(self.config)
        if actual == state['original_sha256']:
            return
        if actual != state['applied_sha256']:
            raise ValueError('ACCEPTANCE_CONFIG_RESTORE_CONFLICT')
        atomic_bytes(self.config, backup.read_bytes())

    def close(self):
        try:
            if self.state is not None:
                if any(still_alive(value) for role, value in self.state['processes'].items()
                       if role != 'harness'):
                    raise RuntimeError('ACCEPTANCE_CLEANUP_INCOMPLETE')
                self._restore(self.state)
                self.journal.unlink()
        finally:
            if self.lock is not None:
                self.lock.close()
                self.lock = None


def verify_frozen(project):
    frozen = json.loads((project.parent / 'iopengw-freeze.json').read_text())
    expected = json.loads((ROOT / 'tests/java_alignment_matrix.json').read_text())['real_project']
    if frozen['commit'] != expected['commit'] or frozen['source'] != expected['path'] or frozen['include_uncommitted']:
        raise ValueError('UNEXPECTED_FROZEN_PROJECT')
    # Never write the original business checkout, including through a directory symlink.
    project.relative_to((ROOT / 'target/java-alignment').resolve())
    entries = subprocess.check_output(['git', '-C', frozen['source'], 'ls-tree', '-rz', frozen['commit']]).split(b'\0')
    verified = java = 0
    for entry in entries:
        if not entry:
            continue
        head, relative = entry.split(b'\t', 1)
        mode, kind, expected_hash = head.split()
        if kind != b'blob':
            continue
        path = project / relative.decode()
        if mode == b'120000' or path.is_symlink():
            raise ValueError('FROZEN_SYMLINK')
        value = hashlib.sha1() if len(expected_hash) == 40 else hashlib.sha256()
        value.update(b'blob ' + str(path.stat().st_size).encode() + b'\0')
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(65536), b''):
                value.update(chunk)
        if value.hexdigest().encode() != expected_hash:
            raise ValueError('COMMITTED_INPUT_CHANGED: ' + relative.decode())
        verified += 1
        java += relative.endswith(b'.java')
    return dict(commit=frozen['commit'], verified_committed_files=verified,
                committed_java_files=java, include_uncommitted=False)


def project_config(scopes, xml, manifest, native_heap_bytes=536870912, worker_memory_bytes=1610612736):
    if not 8 * 1024 * 1024 <= native_heap_bytes <= 1024 * 1024 * 1024 or native_heap_bytes % (1024 * 1024):
        raise ValueError('JAVA_NATIVE_HEAP_RANGE')
    if not native_heap_bytes + (512 + 256 + 64) * 1024 * 1024 <= worker_memory_bytes <= 2147483648:
        raise ValueError('JAVA_PARALLELISM_MEMORY_BUDGET')
    return ('schema_version=1\nkind="project"\n[discovery]\nmode="auto"\n[scan]\n'
            'scopes=' + json.dumps(scopes) + '\ninclude=' + json.dumps(
                ['**/*.java', '**/pom.xml'] + (['**/*.xml'] if xml else [])) + '\n'
            '[languages.java]\nenabled=true\n[languages.java.discovery]\nbuild_system="maven"\n'
            'inputs_manifest=' + json.dumps(manifest) + '\n[languages.java.analysis]\n'
            'source_version="8"\nclasspath="existing"\n[languages.java.lombok]\nmode="model"\n'
            '[languages.java.extensions.spring]\nenabled=true\nxml=' + str(xml).lower() + '\n'
            '[languages.java.extensions.sofa]\nenabled=true\n[resources.index]\n'
            'worker_memory_bytes=' + str(worker_memory_bytes) + '\njava_compiler_heap_bytes=536870912\njava_parallelism=1\n'
            'java_model_record_limit=5000000\njava_model_archive_bytes=1073741824\n'
            'java_native_heap_bytes=' + str(native_heap_bytes) + '\njava_compiler_timeout_seconds=600\n'
            'java_result_bytes=4294967296\njava_fact_limit=5000000\njava_scratch_bytes=17179869184\n').encode()


class PhaseCapture:
    def __init__(self, daemon, evidence):
        self.daemon, self.evidence = daemon, evidence
        self.paths, self.last = set(), {}
        self.failure = None

    def sample(self):
        table = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,comm='], text=True, timeout=3)
        rows = [line.split(None, 2) for line in table.splitlines()]
        owned = {self.daemon}
        while True:
            old = len(owned)
            owned.update(int(r[0]) for r in rows if len(r) == 3 and int(r[1]) in owned)
            if old == len(owned):
                break
        identities = {int(r[0]): process_identity(int(r[0])) for r in rows
                      if len(r) == 3 and int(r[0]) in owned and int(r[0]) != self.daemon}
        for row in rows:
            if len(row) != 3 or int(row[0]) not in owned or not row[2].endswith('/java'):
                continue
            cwd = subprocess.run(['/usr/sbin/lsof', '-a', '-p', row[0], '-d', 'cwd', '-Fn'],
                                 capture_output=True, text=True, timeout=3)
            for line in cwd.stdout.splitlines():
                if not line.startswith('n/'):
                    continue
                work = pathlib.Path(line[1:])
                if work.name.startswith('jvm-') and work.parent.name.startswith('compiler-') and work.parent.parent.name.startswith('codeana-java-'):
                    self.paths.add(work.parent / 'phases.ndjson')
        for path in self.paths:
            try:
                with path.open('rb') as stream:
                    body = stream.read(1024 * 1024 + 1)
            except FileNotFoundError:
                continue
            if len(body) > 1024 * 1024:
                raise ValueError('PHASE_JOURNAL_BYTE_BUDGET')
            body = body[:body.rfind(b'\n') + 1]
            rows = [json.loads(line) for line in body.splitlines()]
            if len(rows) > 4096:
                raise ValueError('PHASE_JOURNAL_RECORD_BUDGET')
            if rows and self.last != rows[-1]:
                atomic_bytes(self.evidence / 'compiler-phases.ndjson', body)
                self.last = rows[-1]
                print('Phase: ' + json.dumps(self.last), flush=True)
        return identities


def stop(process):
    if process is None:
        return None
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    return process.returncode


def interrupted(signum, _frame):
    raise SystemExit(128 + signum)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=pathlib.Path, default=ROOT / 'target/java-alignment/iopengw')
    parser.add_argument('--scopes', choices=('MAIN', 'TEST', 'MAIN,TEST'), default='MAIN')
    parser.add_argument('--xml', action='store_true')
    parser.add_argument('--inputs-manifest', default='.code-ana/rust-main-inputs.json')
    parser.add_argument('--deadline-seconds', type=int, default=3600)
    parser.add_argument('--native-heap-bytes', type=int, default=536870912,
                        help='Explicit correctness-run native heap; this does not certify capacity')
    parser.add_argument('--worker-memory-bytes', type=int, default=1610612736,
                        help='Total worker reservation, including javac and non-heap space; at most 2 GiB')
    parser.add_argument('--evidence', type=pathlib.Path)
    args = parser.parse_args()
    if not 1 <= args.deadline_seconds <= 86400:
        parser.error('deadline must be within the product-supported 1..86400 seconds')
    try:
        configured = project_config(args.scopes.split(','), args.xml, args.inputs_manifest,
                                    args.native_heap_bytes, args.worker_memory_bytes)
    except ValueError as failure:
        parser.error(str(failure))
    previous_sigterm = signal.signal(signal.SIGTERM, interrupted)
    project = args.project.resolve()
    evidence = (args.evidence or ROOT / 'target/java-alignment/evidence' /
                ('iopengw-correctness-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))).resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    print('Evidence: ' + str(evidence), flush=True)
    receipt = evidence / 'receipt.json'
    report = dict(kind='frozen_project_correctness_publication', state='preparing',
                  publication_accepted=False, semantic_index_accepted=False,
                  semantic_diff_accepted=False, capacity_accepted=False,
                  full_project_all_scopes_accepted=False, time_is_performance_gate=False,
                  scopes=args.scopes.split(','), xml_resources_included=args.xml,
                  native_heap_bytes=args.native_heap_bytes,
                  compiler_heap_bytes=536870912, worker_memory_bytes=args.worker_memory_bytes,
                  protection_deadline_seconds=args.deadline_seconds, owner_pid=os.getpid())
    atomic_json(receipt, report)
    lease = ConfigLease(project, evidence)
    daemon = cli = usage = phases = None
    started = time.monotonic()
    failure = None
    try:
        report.update(verify_frozen(project))
        report['artifacts'] = artifacts()
        report['source_inputs'] = java_alignment.input_files()
        manifest = (project / args.inputs_manifest).resolve()
        manifest.relative_to(project)
        report['inputs_manifest_sha256'] = digest(manifest)
        home = evidence / 'home'
        home.mkdir(mode=0o700)
        jdk = pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'
        atomic_bytes(home / 'config.toml', ('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\n'
                     'max_worker_memory_bytes=' + str(args.worker_memory_bytes) + '\nmax_total_worker_memory_bytes=' + str(args.worker_memory_bytes) + '\n'
                     '[toolchains.java8]\nlanguage="java"\nhome=' + json.dumps(str(jdk)) + '\n').encode())
        lease.acquire()
        lease.apply(configured)
        with (evidence / 'daemon.log').open('wb') as log:
            daemon = subprocess.Popen([str(ROOT / 'target/release/codeana-daemon'), '--home', str(home)],
                                      stdout=log, stderr=log, start_new_session=True)
            lease.process('daemon', daemon.pid)
            report['daemon_pid'] = daemon.pid
            atomic_json(receipt, report)
            command = [str(ROOT / 'target/release/codeana-cli'), '--home', str(home), '--project', str(project)]
            end = time.monotonic() + 15
            while True:
                if daemon.poll() is not None:
                    raise RuntimeError('DAEMON_EXITED: ' + str(daemon.returncode))
                status = subprocess.run(command + ['daemon', 'status'], capture_output=True, timeout=3)
                if status.returncode == 0:
                    break
                if time.monotonic() >= end:
                    raise RuntimeError('DAEMON_START_TIMEOUT')
                time.sleep(.05)
            phases = PhaseCapture(daemon.pid, evidence)
            with Rss(daemon.pid) as usage:
                with (evidence / 'index.stdout').open('wb') as output, (evidence / 'index.stderr').open('wb') as error:
                    cli = subprocess.Popen(command + ['index', '--full', '--deadline-seconds', str(args.deadline_seconds)],
                                           stdout=output, stderr=error, start_new_session=True)
                    lease.process('cli', cli.pid)
                    report.update(state='running', cli_pid=cli.pid)
                    while cli.poll() is None:
                        if daemon.poll() is not None:
                            raise RuntimeError('DAEMON_EXITED: ' + str(daemon.returncode))
                        if time.monotonic() - started > args.deadline_seconds + 30:
                            raise TimeoutError('HARNESS_PROTECTION_DEADLINE')
                        try:
                            children = phases.sample()
                            for pid, identity in children.items():
                                role = 'child:' + str(pid)
                                if identity and lease.state['processes'].get(role) != identity:
                                    lease.state['processes'][role] = identity
                                    atomic_json(lease.journal, lease.state)
                        except Exception as error:
                            phases.failure = str(error)
                        report.update(elapsed_seconds=round(time.monotonic() - started, 3),
                                      last_phase=phases.last, phase_capture_failure=phases.failure,
                                      process_tree_peak_bytes=usage.peak, samples=usage.samples)
                        atomic_json(receipt, report)
                        time.sleep(1)
                    report['exit_code'] = cli.returncode
            try:
                report['rss'] = usage.receipt()
            except Exception as error:
                report['rss_failure'] = str(error)
            for line in (evidence / 'index.stdout').read_text().splitlines():
                if line.startswith('terminal '):
                    report['terminal'] = json.loads(line[9:])
            terminal = report.get('terminal', {})
            report['published'] = terminal.get('publication') == 'published'
            if report['published']:
                revision = terminal['revision']
                db = home / 'catalog.db'
                with sqlite3.connect(db.as_uri() + '?mode=ro', uri=True) as catalog:
                    report['catalog_commits'] = catalog.execute('SELECT task,revision FROM commits WHERE revision=?', (revision,)).fetchall()
                    report['catalog_heads'] = catalog.execute('SELECT target,revision FROM heads WHERE revision=?', (revision,)).fetchall()
                facts = home / 'revisions' / revision / 'facts.db'
                with sqlite3.connect(facts.as_uri() + '?mode=ro', uri=True) as inventory:
                    report['facts'] = inventory.execute('SELECT count(*) FROM facts').fetchone()[0]
                    selector = inventory.execute("SELECT id FROM facts WHERE kind='declaration' "
                        "AND json_extract(body,'$.attributes.element_kind')='CLASS' "
                        "AND json_extract(body,'$.span') IS NOT NULL ORDER BY id LIMIT 1").fetchone()
                    if selector is None:
                        raise ValueError('PUBLISHED_PROJECT_HAS_NO_SOURCE_CLASS')
                report['query_selector'] = selector[0]
                query = subprocess.run(command + ['structure', selector[0], '--revision', revision,
                    '--max-records', '1'], capture_output=True, timeout=30)
                atomic_bytes(evidence / 'query.stdout', query.stdout)
                atomic_bytes(evidence / 'query.stderr', query.stderr)
                report.update(published_revision=revision, published_facts=str(facts), query_exit_code=query.returncode)
                report['publication_accepted'] = publication_accepted(report)
                report['semantic_index_accepted'] = bool(report['publication_accepted']
                    and terminal.get('evidence', {}).get('semantic') is True)
            report['state'] = 'finished'
    except BaseException as error:
        failure = error
        report.update(state='interrupted' if isinstance(error, (KeyboardInterrupt, SystemExit)) else 'failed',
                      failure_type=type(error).__name__, failure=str(error))
    finally:
        report['cli_exit_code_observed'] = cli.poll() if cli is not None else None
        report['daemon_exit_code_before_cleanup'] = daemon.poll() if daemon is not None else None
        report['unexpected_signal_observed'] = any(value is not None and value < 0 for value in
            (report['cli_exit_code_observed'], report['daemon_exit_code_before_cleanup']))
        for role, process in (('cli', cli), ('daemon', daemon)):
            try:
                report[role + '_cleanup_exit_code'] = stop(process)
            except Exception as error:
                report[role + '_cleanup_failure'] = str(error)
        try:
            lease.close()
            report['config_restored'] = True
        except Exception as error:
            report['config_restored'] = False
            report['config_restore_failure'] = str(error)
        if usage is not None:
            try:
                report['rss'] = usage.receipt()
            except Exception as error:
                report['rss_failure'] = str(error)
        if 'artifacts' in report:
            try:
                report['changed_artifacts'] = sorted(key for key, value in report['artifacts'].items() if artifacts().get(key) != value)
                current = java_alignment.input_files()
                report['changed_source_inputs'] = sorted(key for key in report['source_inputs'].keys() | current.keys()
                    if report['source_inputs'].get(key) != current.get(key))
                report['inputs_manifest_changed'] = digest(project / args.inputs_manifest) != report['inputs_manifest_sha256']
            except Exception as error:
                report['input_verification_failure'] = str(error)
        if failure or not report.get('config_restored') or any(report.get(key) for key in
                ('changed_artifacts', 'changed_source_inputs', 'inputs_manifest_changed', 'input_verification_failure')):
            report['semantic_index_accepted'] = False
            report['publication_accepted'] = False
        report['elapsed_seconds'] = round(time.monotonic() - started, 3)
        atomic_json(receipt, report)
        print(json.dumps({k: v for k, v in report.items() if k not in ('artifacts', 'source_inputs')}, ensure_ascii=False), flush=True)
        signal.signal(signal.SIGTERM, previous_sigterm)
    return 0 if report['publication_accepted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
