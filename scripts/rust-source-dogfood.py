#!/usr/bin/env python3
"""Prepare an isolated code-ana source snapshot for real Rust parser dogfood.

Build messages must come from an explicit developer build. Indexing never builds.
The output stays under target/ and does not change the repository's config.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import shutil
import subprocess
import time
import tarfile
import os
import re
import sqlite3
import threading

ROOT = pathlib.Path(__file__).resolve().parents[1]

def prepare(destination, worker_memory_bytes=268435456):
    destination.resolve().relative_to(ROOT)
    destination.mkdir(parents=True, exist_ok=True)
    for name in ('Cargo.toml', 'Cargo.lock', 'proto', 'crates', 'lang-rust-adaptor'):
        source = ROOT / name
        target = destination / name
        if source.is_dir():
            shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns('target', '__pycache__', '.tmp*'))
        else:
            shutil.copy2(source, target)
    config = destination / '.code-ana/config.toml'
    config.parent.mkdir(exist_ok=True)
    config.write_text('schema_version=1\nkind="project"\n[languages.rust]\nenabled=true\nmode="semantic"\n[languages.rust.discovery]\ninputs_manifest=".code-ana/rust-inputs.json"\n[scan]\nscopes=["MAIN"]\nexclude=["**/tests/**","**/examples/**","**/benches/**"]\n[languages.rust.dependencies]\ninclude=["**"]\nexclude=["tests/**","examples/**","benches/**"]\n[resources.index]\nworker_memory_bytes=268435456\nrust_result_bytes=536870912\nrust_fact_limit=2000000\n[observability]\nmode="full"\n')
    set_memory(config, worker_memory_bytes)
    return destination

def set_memory(config, memory):
    text=config.read_text()
    if not re.search(r'^worker_memory_bytes\s*=', text, re.M):
        raise ValueError('RUST_DOGFOOD_MEMORY_CONFIG_REQUIRED')
    config.write_text(re.sub(r'^worker_memory_bytes\s*=.*$', 'worker_memory_bytes='+str(memory), text, flags=re.M))

class MemoryMonitor:
    """Same-timestamp RSS sum, not a sum of independent process peaks."""
    def __init__(self, pid):
        self.pid=pid
        self.stopped=threading.Event()
        self.samples=[]
        self.errors=[]
        self.thread=threading.Thread(target=self.run,daemon=True)
    def run(self):
        while not self.stopped.is_set():
            try:
                output=subprocess.check_output(['ps','-axo','pid=,ppid=,rss=,comm='],text=True)
                processes={}
                for line in output.splitlines():
                    pid,parent,rss,name=line.split(None,3)
                    processes[int(pid)]=(int(parent),int(rss)*1024,name)
                selected={self.pid}
                while True:
                    added={pid for pid,(parent,_,_) in processes.items() if parent in selected}
                    if added<=selected: break
                    selected.update(added)
                daemon=processes.get(self.pid,(0,0,''))[1]
                worker=sum(processes[p][1] for p in selected if p!=self.pid and p in processes)
                self.samples.append(dict(at=time.time(),daemon_rss_bytes=daemon,worker_tree_rss_bytes=worker,total_rss_bytes=daemon+worker))
            except (OSError,ValueError,subprocess.SubprocessError) as error:
                self.errors.append(type(error).__name__)
            self.stopped.wait(.1)
    def finish(self, evidence):
        self.stopped.set()
        self.thread.join(timeout=5)
        (evidence/'memory-samples.json').write_text(json.dumps(self.samples)+'\n')
        return dict(sample_interval_seconds=.1, samples=len(self.samples),errors=self.errors,
                    daemon_peak_rss_bytes=max((s['daemon_rss_bytes'] for s in self.samples),default=0),
                    worker_tree_peak_rss_bytes=max((s['worker_tree_rss_bytes'] for s in self.samples),default=0),
                    simultaneous_peak_rss_bytes=max((s['total_rss_bytes'] for s in self.samples),default=0))

def measure(project, evidence, build_messages=None, cargo_configs=(), *, worker_memory_bytes=268435456, profile="debug", reuse_inputs=False, purpose="measurement", expected_input_sha256=None,reference_evidence=None):
    evidence = evidence.resolve()
    evidence.relative_to(ROOT)
    evidence.mkdir(parents=True, exist_ok=True)
    marker = evidence/'measurement-started.json'
    if marker.exists() or (evidence/'receipt.json').exists():
        raise ValueError('RUST_DOGFOOD_ALREADY_MEASURED: original evidence is retained')
    home = evidence/'home'
    if home.exists():
        raise ValueError('RUST_DOGFOOD_HOME_NOT_EMPTY')
    project=project.resolve()
    project.relative_to(ROOT)
    if reference_evidence:
        if not reuse_inputs:
            raise ValueError('RUST_DOGFOOD_REFERENCE_REQUIRES_REUSE')
        reference_evidence.resolve().relative_to(ROOT)
        with tarfile.open(reference_evidence/'frozen-sources.tar.gz','r:gz') as archive:
            for member in archive.getmembers():
                if not member.isfile() or member.name=='.code-ana/config.toml': continue
                path=project/member.name
                path.resolve().relative_to(project)
                with archive.extractfile(member) as source, path.open('rb') as actual:
                    if hashlib.file_digest(source,'sha256').digest()!=hashlib.file_digest(actual,'sha256').digest():
                        raise ValueError('RUST_DOGFOOD_REFERENCE_CHANGED: '+member.name)
    if reuse_inputs:
        set_memory(project/'.code-ana/config.toml',worker_memory_bytes)
        inputs=json.loads((project/'.code-ana/rust-inputs.json').read_text())
    else:
        previous = project/'.code-ana/rust-inputs.json'
        if previous.is_file() and not (evidence/'previous-inputs.json').exists():
            shutil.copy2(previous, evidence/'previous-inputs.json')
        project = prepare(project, worker_memory_bytes)
        spec = importlib.util.spec_from_file_location('rust_export', ROOT/'scripts/export-rust-inputs.py')
        exporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(exporter)
        if build_messages:
            # Own-source snapshots have different Cargo package IDs. Remap only workspace
            # paths copied verbatim by prepare; registry package identities stay unchanged.
            mapped = evidence/'build-messages.json'
            messages = []
            for line in build_messages.read_text().splitlines():
                message = json.loads(line)
                if 'package_id' in message:
                    message['package_id'] = message['package_id'].replace(
                        'path+' + ROOT.as_uri() + '/', 'path+' + project.resolve().as_uri() + '/')
                messages.append(json.dumps(message))
            mapped.write_text('\n'.join(messages)+'\n')
            build_messages = mapped
        inputs = exporter.export(project, project/'.code-ana/rust-inputs.json',
                                 build_messages=build_messages, cargo_configs=cargo_configs)
    input_bytes = (project/'.code-ana/rust-inputs.json').read_bytes()
    if expected_input_sha256 and hashlib.sha256(input_bytes).hexdigest()!=expected_input_sha256:
        raise ValueError('RUST_DOGFOOD_INPUT_CHANGED')
    (evidence/'frozen-inputs.json').write_bytes(input_bytes)
    # Preserve the exact source snapshot independently of future preparations.
    sources = {}
    for name in ('crates', 'lang-rust-adaptor'):
        for source in (project/name).rglob('*.rs'):
            sources[source.relative_to(project).as_posix()] = hashlib.sha256(source.read_bytes()).hexdigest()
    frozen_paths = set(sources) | set(inputs['controls']) | set(inputs['generated']) | {'.code-ana/config.toml', '.code-ana/rust-inputs.json'}
    frozen_paths.update(path for paths in inputs['dependency_sources'].values() for path in paths)
    with tarfile.open(evidence/'frozen-sources.tar.gz', 'w:gz') as archive:
        for path in sorted(frozen_paths):
            archive.add(project/path, arcname=path, recursive=False)
    binaries = {name: hashlib.sha256((ROOT/'target'/profile/name).read_bytes()).hexdigest()
                for name in ('codeana-cli', 'codeana-daemon', 'codeana-rust-worker')}
    frozen = dict(input_sha256=hashlib.sha256(input_bytes).hexdigest(), binaries=binaries,
                  source_sha256=sources, frozen_sources_sha256=hashlib.sha256((evidence/'frozen-sources.tar.gz').read_bytes()).hexdigest(), profile=profile, purpose=purpose,worker_memory_bytes=worker_memory_bytes,
                  result_bytes=536870912, fact_limit=2000000, budget_seconds=300,
                  selection=inputs['selection'], crates=len(inputs['crates']),reference_evidence=str(reference_evidence) if reference_evidence else None)
    home.mkdir()
    (home/'config.toml').write_text(f'schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes={worker_memory_bytes}\nmax_total_worker_memory_bytes={worker_memory_bytes}\n')
    log = open(evidence/'daemon.stdout', 'w')
    environment=os.environ.copy()
    environment['CODEANA_PERFORMANCE_TIMINGS_DIR']=str(evidence/'timings')
    (evidence/'timings').mkdir()
    daemon = subprocess.Popen([str(ROOT/'target'/profile/'codeana-daemon'), '--home', str(home)], stdout=log, stderr=log,env=environment)
    monitor=None
    try:
        until = time.monotonic()+15
        while not (home/'endpoint.json').exists():
            if daemon.poll() is not None or time.monotonic() > until:
                raise ValueError('RUST_DOGFOOD_DAEMON_START_FAILED')
            time.sleep(.05)
        marker.write_text(json.dumps(frozen, indent=2)+'\n')
        monitor=MemoryMonitor(daemon.pid)
        monitor.thread.start()
        start = time.monotonic()
        try:
            result = subprocess.run([str(ROOT/'target'/profile/'codeana-cli'), '--home', str(home),
                                 '--project', str(project), 'index', '--full', '--deadline-seconds', '300'],
                                    capture_output=True, text=True, timeout=330)
        except subprocess.TimeoutExpired as error:
            result = subprocess.CompletedProcess(error.cmd, 124,
                (error.stdout or b'').decode(errors='replace'), (error.stderr or b'').decode(errors='replace'))
        elapsed = time.monotonic()-start
        memory=monitor.finish(evidence)
        monitor=None
        (evidence/'index.stdout').write_text(result.stdout)
        (evidence/'index.stderr').write_text(result.stderr)
        terminal = next((json.loads(line[9:]) for line in result.stdout.splitlines() if line.startswith('terminal ')), None)
        receipt = dict(frozen, elapsed_seconds=elapsed, exit_code=result.returncode,
                       terminal=terminal, state='empty-home', performance_accepted=False,memory=memory)
        for key in ('rust_input_summary', 'rust_worker_memory','rust_stream'):
            for line in result.stderr.splitlines():
                if key+' ' in line:
                    receipt[key] = json.loads(line.split(key+' ',1)[1])
        if terminal and terminal['code']=='WORKER_MEMORY_BUDGET':
            message = terminal['message']
            receipt['rust_worker_memory'] = json.JSONDecoder().raw_decode(message[message.index('{'):])[0]
        if terminal and terminal.get('revision'):
            revision=terminal['revision']
            database=home/'revisions'/revision/'facts.db'
            receipt['facts_db_bytes']=database.stat().st_size
            receipt['catalog_db_bytes']=(home/'catalog.db').stat().st_size
            blobs=[p for p in (home/'blobs').iterdir() if p.is_file()]
            receipt['blobs']=dict(files=len(blobs),bytes=sum(p.stat().st_size for p in blobs))
            with sqlite3.connect(database) as db:
                receipt['graph_counts']={table:db.execute('SELECT count(*) FROM '+table).fetchone()[0] for table in ('nodes','edges','call_sites','call_site_targets','rust_analysis_dependencies')}
                receipt['dbstat']={name:size for name,size in db.execute('SELECT name,sum(pgsize) FROM dbstat GROUP BY name')}
            with sqlite3.connect(home/'catalog.db') as db:
                report=db.execute("SELECT body FROM objects WHERE kind='report' ORDER BY rowid DESC LIMIT 1").fetchone()
                if report:
                    report=json.loads(report[0])
                    receipt['timing']=report['summary']['timing']
                    (evidence/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        (evidence/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
        return receipt
    except Exception as error:
        if not (evidence/'receipt.json').exists():
            (evidence/'receipt.json').write_text(json.dumps(dict(frozen,exit_code=1,error=str(error),performance_accepted=False),indent=2)+'\n')
        raise
    finally:
        if monitor: monitor.finish(evidence)
        daemon.terminate()
        try:
            daemon.wait(timeout=10)
        except subprocess.TimeoutExpired:
            daemon.kill()
            daemon.wait(timeout=5)
        log.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=pathlib.Path, default=ROOT/'target/rust-dogfood/project')
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--measure', action='store_true')
    mode.add_argument('--functional', action='store_true')
    parser.add_argument('--worker-memory-mib',type=int,default=256)
    parser.add_argument('--profile',choices=['debug','release'],default='debug')
    parser.add_argument('--reuse-inputs',action='store_true')
    parser.add_argument('--expected-input-sha256')
    parser.add_argument('--reference-evidence',type=pathlib.Path)
    parser.add_argument('--evidence', type=pathlib.Path, default=ROOT/'target/rust-dogfood/memory-v2')
    parser.add_argument('--build-messages', type=pathlib.Path)
    parser.add_argument('--cargo-config', action='append', default=[])
    args = parser.parse_args()
    if not 16<=args.worker_memory_mib<=4096:
        parser.error('--worker-memory-mib must be 16..4096')
    if args.measure or args.functional:
        receipt = measure(args.destination, args.evidence, args.build_messages, args.cargo_config,worker_memory_bytes=args.worker_memory_mib*1024*1024,profile=args.profile,reuse_inputs=args.reuse_inputs,purpose='measurement' if args.measure else 'functional',expected_input_sha256=args.expected_input_sha256,reference_evidence=args.reference_evidence)
        print(json.dumps({'receipt':str(args.evidence/'receipt.json'), 'exit_code':receipt['exit_code'], 'terminal':receipt['terminal']}))
    else:
        print(json.dumps({'project': str(prepare(args.destination,args.worker_memory_mib*1024*1024))}))
