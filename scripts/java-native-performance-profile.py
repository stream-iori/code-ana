#!/usr/bin/env python3
"""Independent CPU diagnosis using accepted baseline binaries; never a speed receipt."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('single_monitor', ROOT/'scripts/java-performance-monitor-single-v2.py')
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'evidence'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text())
    if baseline.get('status') != 'passed' or baseline.get('build_profile') != 'release':
        raise ValueError('PASSED_RELEASE_BASELINE_REQUIRED')
    native = next(r for r in baseline['rounds'] if r['backend'] == 'native')
    installation = next(r for r in baseline['retained_projects'] if r['backend'] == 'native')
    runtime = Path(installation['runtime'])
    project = Path(installation['project'])
    inputs = {p: digest for p, digest in native['phase']['inputs'].items()
              if Path(p).is_relative_to(runtime) or Path(p).is_relative_to(project)}
    inputs.update({str(p.resolve()): sha(p) for p in (args.baseline, Path(__file__), ROOT/'scripts/java-performance-monitor-single-v2.py')})
    if any(sha(Path(p)) != digest for p, digest in inputs.items()):
        raise ValueError('BASELINE_RUNTIME_DRIFT')
    user_config = Path(installation['home'])/'config.toml'
    inputs[str(user_config.resolve())] = sha(user_config)
    output = args.evidence.resolve()
    output.mkdir(parents=True, exist_ok=False)
    home = output/'home'
    home.mkdir()
    shutil.copy2(user_config, home/'config.toml')
    binaries = runtime/'target/debug'
    command = [str(binaries/'codeana-cli'), '--home', str(home), '--project', str(project)]
    report = dict(status='running', purpose='independent_cpu_profile', performance_accepted=False,
                  used_for_performance_comparison=False, inputs=inputs, samples=[])
    receipt = output/'receipt.json'
    def save():
        receipt.write_text(json.dumps(report, indent=2)+'\n')
    save()
    daemon = index = None
    started = time.monotonic()
    try:
        if MONITOR.foreign_analyzers(MONITOR.processes(), []):
            raise ValueError('EXCLUSIVE_PROFILE_REQUIRED')
        with (output/'daemon.log').open('wb') as log:
            daemon = subprocess.Popen([str(binaries/'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log,
                                      env=dict(os.environ, CODEANA_JAVA_BACKEND='native'))
        ready = time.monotonic()+15
        while subprocess.run(command+['daemon', 'status'], capture_output=True, timeout=3).returncode:
            if daemon.poll() is not None or time.monotonic() > ready:
                raise ValueError('PROFILE_DAEMON_START_FAILED')
            time.sleep(.05)
        with (output/'index.stdout').open('wb') as out, (output/'index.stderr').open('wb') as err:
            index = subprocess.Popen(command+['index', '--full', '--deadline-seconds', '3600'], stdout=out, stderr=err)
        wanted = [('rust_worker', 150), ('rust_worker', 300), ('rust_worker', 450), ('daemon', 300), ('daemon', 450)]
        next_progress = started+60
        scratch = None
        while index.poll() is None:
            if time.monotonic()-started > 3630:
                raise TimeoutError('PROFILE_DEADLINE')
            rows = MONITOR.processes()
            owned = MONITOR.owned_processes(rows, daemon.pid)
            if MONITOR.foreign_analyzers(rows, owned):
                raise ValueError('FOREIGN_ANALYZER_DURING_PROFILE')
            for row in owned:
                role = MONITOR.process_role(row['command'])
                if role == 'rust_worker' and scratch is None:
                    listing = subprocess.run(['/usr/sbin/lsof', '-p', str(row['pid']), '-Fn'], capture_output=True, text=True, timeout=5)
                    paths = [Path(p) for p in re.findall(r'/[^\n]*codeana-java-[^/\n]+', listing.stdout) if Path(p).is_dir()]
                    if len(paths) == 1:
                        scratch = paths[0]
                        report['worker_scratch'] = dict(pid=row['pid'], path=str(scratch))
                for pair in list(wanted):
                    if role == pair[0] and row['cpu_seconds'] >= pair[1]:
                        name = f'{role}-{pair[1]}'
                        sample = output/(name+'.txt')
                        execution = subprocess.run(['/usr/bin/sample', str(row['pid']), '3', '1', '-file', str(sample)], capture_output=True, text=True, timeout=30)
                        (output/(name+'.log')).write_text(execution.stdout+execution.stderr)
                        if execution.returncode or not sample.is_file():
                            raise ValueError('CPU_SAMPLE_FAILED: '+name)
                        report['samples'].append(dict(role=role, pid=row['pid'], cpu_seconds=row['cpu_seconds'], path=str(sample), sha256=sha(sample)))
                        wanted.remove(pair)
                        save()
            if scratch and (scratch/'native-timings.json').is_file() and not (output/'java-timings.json').exists():
                shutil.copy2(scratch/'native-timings.json', output/'java-timings.json')
            if time.monotonic() >= next_progress:
                report['seconds'] = time.monotonic()-started
                report['processes'] = [dict(pid=r['pid'], role=MONITOR.process_role(r['command']), cpu_seconds=r['cpu_seconds'], rss_bytes=r['rss_bytes']) for r in owned]
                save()
                print(json.dumps(dict(seconds=report['seconds'], samples=len(report['samples']), processes=report['processes'])), flush=True)
                next_progress += 60
            time.sleep(1)
        terminals = [json.loads(line[9:]) for line in (output/'index.stdout').read_text().splitlines() if line.startswith('terminal ')]
        if index.returncode or len(terminals) != 1 or terminals[0].get('publication') != 'published' or wanted:
            raise ValueError('PROFILE_INDEX_OR_SAMPLES_INCOMPLETE')
        if any(sha(Path(p)) != digest for p, digest in inputs.items()):
            raise ValueError('PROFILE_INPUT_DRIFT')
        report.update(status='passed', terminal=terminals[0])
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    finally:
        if daemon and daemon.poll() is None:
            subprocess.run(command+['daemon', 'stop'], capture_output=True, timeout=10)
            try:
                daemon.wait(timeout=10)
            except subprocess.TimeoutExpired:
                daemon.terminate()
                daemon.wait(timeout=10)
        if index and index.poll() is None:
            index.wait(timeout=15)
        report['seconds'] = time.monotonic()-started
        save()
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
