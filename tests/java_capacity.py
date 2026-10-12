#!/usr/bin/env python3
"""Production capacity baseline. This is not the complete reactor/JAR/XML capacity gate."""
import argparse
import collections
import hashlib
import json
import pathlib
import shutil
import sqlite3
import subprocess
import threading
import time

import java_e2e

GIB = 1024 ** 3
MIB = 1024 ** 2


def artifacts():
    files = [java_e2e.BIN/name for name in ('codeana-daemon', 'codeana-cli', 'codeana-java-worker')]
    files += [java_e2e.ROOT/'lang-java-adapotr/target'/name for name in
              ('compiler-bridge.jar', 'native/libcodeana_java.dylib', 'native/manifest.json')]
    result = {}
    for path in files:
        digest = hashlib.sha256()
        with path.open('rb') as source:
            while chunk := source.read(1024*1024):
                digest.update(chunk)
        result[str(path.relative_to(java_e2e.ROOT))] = digest.hexdigest()
    return result


def processes():
    rows = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,rss=,comm=,args='], text=True, timeout=2)
    result = []
    for line in rows.splitlines():
        fields = line.split(None, 4)
        if len(fields) == 5:
            result.append((int(fields[0]), int(fields[1]), int(fields[2]) * 1024, fields[3], fields[4]))
    return result


class Rss:
    def __init__(self, daemon):
        self.daemon = daemon
        self.done = threading.Event()
        self.peak = self.daemon_peak = self.samples = self.max_workers = self.max_javac = self.max_hosts = 0
        self.gap = 0
        self.failure = None
        self.sampling_errors = []
        self.sampling_error_count = 0
        self.thread = threading.Thread(target=self.sample, daemon=True)

    def sample(self):
        previous = None
        tick = time.monotonic()
        consecutive_failures = 0
        try:
            while not self.done.is_set():
                now = time.monotonic()
                if previous is not None:
                    self.gap = max(self.gap, now - previous)
                previous = now
                try:
                    table = processes()
                except subprocess.TimeoutExpired as error:
                    consecutive_failures += 1
                    self.sampling_error_count += 1
                    if len(self.sampling_errors) < 16:
                        self.sampling_errors.append(str(error))
                    if consecutive_failures >= 3:
                        raise RuntimeError('RSS_QUERY_TIMEOUT_AFTER_3_ATTEMPTS') from error
                    # Avoid catching up every missed tick after a slow query.
                    tick = time.monotonic()
                    self.done.wait(.1)
                    continue
                consecutive_failures = 0
                owned = {self.daemon}
                while True:
                    old = len(owned)
                    owned.update(row[0] for row in table if row[1] in owned)
                    if len(owned) == old:
                        break
                tree = [row for row in table if row[0] in owned]
                self.peak = max(self.peak, sum(row[2] for row in tree))
                self.daemon_peak = max(self.daemon_peak, next((row[2] for row in tree if row[0] == self.daemon), 0))
                self.max_workers = max(self.max_workers, sum('codeana-java-worker' in row[3] for row in tree))
                self.max_javac = max(self.max_javac, sum(
                    'com.anatomist.worker.JavacWorker' in row[4] if len(row) > 4
                    else row[3].endswith('/java') for row in tree))
                self.max_hosts = max(self.max_hosts, sum(
                    len(row) > 4 and '-jar' in row[4] and 'runner.jar' in row[4] for row in tree))
                self.samples += 1
                tick = max(tick + .1, time.monotonic())
                self.done.wait(max(0, tick - time.monotonic()))
        except Exception as error:
            self.failure = str(error)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.done.set()
        self.thread.join(3)

    def receipt(self):
        if self.failure or self.thread.is_alive():
            raise AssertionError('RSS sampler failed: ' + str(self.failure))
        if not self.samples or self.sampling_error_count:
            raise AssertionError('RSS sampling incomplete: ' + str(self.summary()))
        if self.peak > 2 * GIB or self.daemon_peak > 512 * MIB or self.max_workers > 1 or self.max_javac > 1:
            raise AssertionError('Capacity RSS/parallelism profile exceeded: ' + str(vars(self)))
        return self.summary()

    def summary(self):
        """Observation only; never certifies capacity or rejects semantic results."""
        return dict(process_tree_peak_bytes=self.peak, daemon_peak_bytes=self.daemon_peak,
                    max_workers=self.max_workers, max_javac=self.max_javac, max_jvm_hosts=self.max_hosts,
                    samples=self.samples, scheduled_interval_ms=100, largest_sample_gap_ms=round(self.gap * 1000, 3),
                    sampling_complete=bool(self.samples and not self.failure and not self.sampling_error_count
                                           and not self.thread.is_alive()),
                    sampling_failure=self.failure, sampling_error_count=self.sampling_error_count,
                    sampling_errors=list(self.sampling_errors))


def graph(case, revision):
    digest = hashlib.sha256()
    kinds = collections.Counter()
    source_coverage = 0
    with sqlite3.connect(f'file:{case.home}/revisions/{revision}/facts.db?mode=ro', uri=True) as db:
        for body, in db.execute('SELECT body FROM facts ORDER BY id'):
            fact = json.loads(body)
            kinds[fact['kind']] += 1
            if fact['kind'] == 'diagnostic':
                raise AssertionError('Unexpected capacity fixture diagnostic: ' + body)
            if fact['kind'] == 'coverage' and fact['owner']['kind'] == 'source':
                source_coverage += 1
                if fact['status'] != 'complete':
                    raise AssertionError('Incomplete source coverage: ' + body)
            row = json.dumps(fact, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
            digest.update(len(row).to_bytes(8, 'big'))
            digest.update(row)
    return dict(sha256=digest.hexdigest(), counts=dict(kinds), source_coverage=source_coverage)


def start(case):
    case.log = (case.root/'daemon.log').open('a')
    case.daemon = subprocess.Popen([str(java_e2e.BIN/'codeana-daemon'), '--home', str(case.home)],
                                   stdout=case.log, stderr=case.log)
    end = time.monotonic() + 10
    while time.monotonic() < end:
        if case.daemon.poll() is not None:
            raise AssertionError(case.daemon_log())
        result = subprocess.run([str(java_e2e.BIN/'codeana-cli'), '--home', str(case.home), 'daemon', 'status'],
                                capture_output=True, timeout=3)
        if result.returncode == 0:
            return
        time.sleep(.05)
    raise AssertionError('Capacity daemon start timeout: ' + case.daemon_log())


def run(count, evidence):
    case = java_e2e.JavaProduct('test_core')
    case.setUp()
    result = dict(files=count, phases=[])
    path = evidence/f'{count}.json'
    try:
        case.stop()
        (case.home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\n'
                'max_worker_memory_bytes=1610612736\nmax_total_worker_memory_bytes=1610612736\n'
                '[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(java_e2e.JDK))+'\n')
        shutil.rmtree(case.project/'src')
        source_dir = case.project/'src'
        source_dir.mkdir()
        for i in range(count):
            (source_dir/f'C{i}.java').write_text(f'class C{i} {{ static int value(){{return {i};}} int read(){{return C{(i+1)%count}.value();}} }}')
        config = case.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[resources.index]\nworker_memory_bytes=1610612736\n'
                          'java_compiler_heap_bytes=536870912\njava_parallelism=1\n')
        start(case)

        def index(phase, full=False, expected=None):
            print(f'capacity {count} files: {phase}', flush=True)
            started = time.monotonic()
            with Rss(case.daemon.pid) as rss:
                records, terminal = case.cli('index', '--deadline-seconds', '1800', *(('--full',) if full else ()), timeout=1810)
            if terminal['publication'] != 'published' or not terminal['evidence']['semantic']:
                raise AssertionError('Capacity index failed: ' + str(terminal))
            actual = graph(case, terminal['revision'])
            if actual['source_coverage'] != count:
                raise AssertionError('Capacity source inventory mismatch: ' + str(actual))
            if expected is not None and actual != expected:
                raise AssertionError('Capacity full/delta multiset mismatch: ' + str((expected, actual)))
            result['phases'].append(dict(phase=phase, seconds=round(time.monotonic()-started, 3),
                                         revision=terminal['revision'], graph=actual, **rss.receipt()))
            path.write_text(json.dumps(result, indent=2)+'\n')
            return actual

        previous = index('full', True)
        worker = case.java_worker_pid()
        index('unchanged-hot', expected=previous)
        if case.java_worker_pid() != worker:
            raise AssertionError('Hot worker was not reused')
        source = source_dir/'C0.java'
        source.write_text(source.read_text().replace('return 0;', 'return 700000;'))
        changed = index('method-body-hot-delta')
        index('method-body-full-equivalence', True, changed)
        case.stop()
        start(case)
        source.write_text(source.read_text().replace('return 700000;', 'return 800000;'))
        changed = index('method-body-cold-delta')
        index('method-body-cold-full-equivalence', True, changed)
        source.write_text(source.read_text().replace('static int value()', 'public static int value()'))
        changed = index('declaration-delta')
        index('declaration-full-equivalence', True, changed)
        if list(case.project.rglob('*.class')):
            raise AssertionError('Target business bytecode was generated')
        result['baseline_accepted'] = True
    except Exception as error:
        result.update(baseline_accepted=False, failure=str(error), daemon_log=case.daemon_log()[-4000:])
        raise
    finally:
        path.write_text(json.dumps(result, indent=2)+'\n')
        case.doCleanups()
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--files', nargs='+', type=int, default=[100, 1000, 3000])
    args = parser.parse_args()
    if not args.files or any(n not in (100, 1000, 3000) for n in args.files) or len(set(args.files)) != len(args.files):
        parser.error('--files must be a unique subset of 100 1000 3000')
    evidence = java_e2e.ROOT/'target/java-alignment/evidence'/('capacity-baseline-'+time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(parents=True)
    report = dict(profile=dict(combined_rss_bytes=2*GIB, daemon_rss_bytes=512*MIB, workers=1,
                               javac_processes=1, compiler_heap_bytes=512*MIB, task_seconds=1800),
                  artifacts=artifacts(), cases=[], final_capacity_accepted=False,
                  remaining=['wide/deep Maven reactors', 'JAR/XML gradients', 'capacity cancellation/cleanup',
                             'large explicit semantic/scratch budgets', 'module cache eviction/pin equivalence'])
    try:
        for count in args.files:
            report['cases'].append(run(count, evidence))
        current = artifacts()
        report['changed_artifacts'] = [path for path, value in report['artifacts'].items() if current.get(path) != value]
        if report['changed_artifacts']:
            raise AssertionError('Capacity artifact drift: '+str(report['changed_artifacts']))
        report['baseline_accepted'] = True
    except BaseException as error:
        report.update(baseline_accepted=False, failure=str(error), failure_type=type(error).__name__)
        raise
    finally:
        (evidence/'receipt.json').write_text(json.dumps(report, indent=2)+'\n')
        print('Evidence: '+str(evidence.relative_to(java_e2e.ROOT)), flush=True)


if __name__ == '__main__':
    main()
