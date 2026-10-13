#!/usr/bin/env python3
"""Observe one approved benchmark command; measurement is not acceptance."""
import argparse
import collections
import hashlib
import json
import os
import pathlib
import subprocess
import time


def duration(value):
    days, clock = value.split('-', 1) if '-' in value else ('0', value)
    parts = [float(part) for part in clock.split(':')]
    if len(parts) not in (2, 3) or any(part < 0 for part in parts):
        raise ValueError('INVALID_PROCESS_DURATION')
    return int(days) * 86400 + sum(part * 60 ** power for power, part in enumerate(reversed(parts)))


def owned_processes(rows, pid):
    owned = {pid}
    while True:
        added = {row['pid'] for row in rows if row['parent'] in owned} - owned
        if not added:
            return [row for row in rows if row['pid'] in owned]
        owned.update(added)


def process_role(command):
    if 'com.anatomist.worker.JavacWorker' in command:
        return 'javac'
    if 'codeana-java-worker' in command:
        return 'rust_worker'
    if 'codeana-daemon' in command:
        return 'daemon'
    if 'runner.jar' in command:
        return 'jvm_runner'
    if 'codeana-cli' in command:
        return 'cli'
    if 'NativeModelWitness' in command or 'native-model-witness' in command:
        return 'native_witness'
    return 'other'


def processes():
    result = []
    output = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,rss=,time=,lstart=,args='], text=True, timeout=3)
    for line in output.splitlines():
        parts = line.split(None, 9)
        if len(parts) == 10:
            result.append(dict(pid=int(parts[0]), parent=int(parts[1]), rss_bytes=int(parts[2]) * 1024,
                               cpu_seconds=duration(parts[3]), started=' '.join(parts[4:9]), command=parts[9]))
    return result


def record_cpu(cpu, roles, row):
    # ps can shorten an exited process to '(java)' or '(codeana-java-wor)'.
    # A role change must never create a second cumulative CPU counter.
    identity = (row['pid'], row['started'])
    role = process_role(row['command'])
    if role != 'other' or identity not in roles:
        roles[identity] = role
    cpu[identity] = max(cpu.get(identity, 0), row['cpu_seconds'])


def cpu_totals(cpu, roles):
    totals = collections.Counter()
    for identity, value in cpu.items():
        totals[roles[identity]] += value
    return dict(totals)


def disk_usage(roots):
    paths = set()
    logical = allocated = 0
    for root in roots:
        candidates = [root] if root.is_file() else root.rglob('*')
        for path in candidates:
            try:
                stat = path.stat()
                if not path.is_file() or (stat.st_dev, stat.st_ino) in paths:
                    continue
                paths.add((stat.st_dev, stat.st_ino))
                logical += stat.st_size
                allocated += stat.st_blocks * 512
            except FileNotFoundError:
                continue
    return dict(logical_bytes=logical, allocated_bytes=allocated)


def foreign_analyzers(rows, owned):
    pids = {row['pid'] for row in owned}
    return [row for row in rows if row['pid'] not in pids and
            pathlib.Path(row['command'].split(' ', 1)[0]).name in
            ('codeana-daemon', 'codeana-java-worker', 'native-model-witness')]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--spec', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    args = parser.parse_args()
    spec = json.loads(args.spec.read_text())
    command = spec['command']
    if (not isinstance(command, list) or not command or not all(isinstance(item, str) for item in command)
            or spec.get('backend') not in ('reference', 'jvm', 'native')
            or (spec.get('cache_policy') != 'independent_empty_application_state'
                and spec.get('cache_temperature') not in ('cold_application_cache', 'warm_application_cache'))):
        raise ValueError('EXPLICIT_BENCHMARK_SPEC_REQUIRED')
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = dict(status='running', process_sample_seconds=1, disk_sample_seconds=10, spec=spec, performance_accepted=False,
                  cpu_measurement='sampled cumulative CPU; short-lived children may be missed',
                  os_page_cache='uncontrolled', foreign_analyzers=[], sampling_errors=[],
                  process_tree_peak_bytes=0, role_peak_bytes={}, samples=0,
                  disk_peak=dict(logical_bytes=0, allocated_bytes=0))
    role_peak = collections.Counter()
    cpu = {}
    cpu_roles = {}
    process = None
    roots = [pathlib.Path(path) for path in spec.get('disk_roots', [])]
    started = time.monotonic()
    try:
        foreign = foreign_analyzers(processes(), [])
        if foreign:
            raise ValueError('EXCLUSIVE_ANALYZER_RUN_REQUIRED')
        with (args.evidence / 'stdout').open('wb') as out, (args.evidence / 'stderr').open('wb') as err:
            process = subprocess.Popen(command, cwd=spec['cwd'], stdout=out, stderr=err,
                                       env=dict(os.environ, **spec.get('env', {})), start_new_session=True)
            next_disk = started
            next_progress = started + 60
            with (args.evidence / 'samples.ndjson').open('w') as journal:
                while process.poll() is None:
                    if time.monotonic() - started > spec.get('timeout_seconds', 86400):
                        raise TimeoutError('BENCHMARK_PROTECTION_DEADLINE')
                    rows = processes()
                    tree = owned_processes(rows, process.pid)
                    owned = [row for row in tree if 'cpu_seconds' in row]
                    foreign = foreign_analyzers(rows, tree)
                    if foreign:
                        report['foreign_analyzers'] = foreign
                        raise ValueError('EXCLUSIVE_ANALYZER_RUN_LOST')
                    rss = collections.Counter()
                    for row in owned:
                        role = process_role(row['command'])
                        rss[role] += row['rss_bytes']
                        record_cpu(cpu, cpu_roles, row)
                    for role, value in rss.items():
                        role_peak[role] = max(role_peak[role], value)
                    report['process_tree_peak_bytes'] = max(report['process_tree_peak_bytes'], sum(rss.values()))
                    sample = dict(seconds=time.monotonic() - started, roles=dict(rss), processes=owned)
                    if time.monotonic() >= next_disk:
                        sample['disk'] = disk_usage(roots)
                        for key, value in sample['disk'].items():
                            report['disk_peak'][key] = max(report['disk_peak'][key], value)
                        next_disk = time.monotonic() + 10
                    journal.write(json.dumps(sample) + '\n'); journal.flush()
                    report['samples'] += 1
                    if time.monotonic() >= next_progress:
                        progress = dict(status='running', seconds=time.monotonic()-started, role_rss_bytes=dict(rss), process_tree_peak_bytes=report['process_tree_peak_bytes'],disk_peak=report['disk_peak'],processes=[dict(pid=r['pid'],role=process_role(r['command']),cpu_seconds=r['cpu_seconds']) for r in owned])
                        temporary=args.evidence/'progress.tmp'
                        temporary.write_text(json.dumps(progress,indent=2)+'\n')
                        temporary.replace(args.evidence/'progress.json')
                        print(json.dumps(progress),flush=True)
                        next_progress=time.monotonic()+60
                    time.sleep(1)
            report.update(status='passed' if process.returncode == 0 else 'failed',
                          exit_code=process.returncode, unexpected_signal=-process.returncode if process.returncode < 0 else None)
    except Exception as error:
        report.update(status='failed', failure=str(error))
    finally:
        if process is not None and process.poll() is None:
            import signal
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        report.update(seconds=time.monotonic() - started, role_peak_bytes=dict(role_peak),
                      sampled_cpu_seconds=cpu_totals(cpu, cpu_roles), inputs={
                          str(path.resolve()): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in [args.spec, pathlib.Path(__file__)]})
        (args.evidence / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
