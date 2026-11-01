#!/usr/bin/env python3
"""Fixed, generated local fixture; separate functional proof and one measured run.

The existing process monitor owns sampling. This driver starts the daemon as its
child, so the sampled tree retains the daemon and workers for the whole workload.
"""
import argparse
import contextlib
import hashlib
import json
import os
import pathlib
import sqlite3
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT/'fixtures/comment-indexing/acceptance.json'
SCRIPT = pathlib.Path(__file__).resolve()


def sha(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    pathlib.Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')


def inventory(root):
    return {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob('*'))
            if p.is_file() and '.git' not in p.relative_to(root).parts}


def artifacts(binaries):
    paths = [binaries/name for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker')]
    sdk = binaries.parent/'lib/java'
    paths += [sdk/name for name in ('native/libcodeana_java.dylib', 'native/manifest.json',
                                    'compiler-bridge.jar', 'maven-input-worker.jar')]
    return {str(p.resolve()): sha(p) for p in paths}


def prepare(args):
    args.directory.mkdir(parents=True, exist_ok=False)
    spec = json.loads(SPEC.read_text())
    project = args.directory/'project'
    sources = project/'src/bench'
    sources.mkdir(parents=True)
    for number in range(spec['java_classes']):
        name = f'Inventory{number:03d}'
        text = 'package bench;\n/** 库存仓库😀 */\npublic class '+name+' {\n'
        text += '    private static final String FAKE = "// 不是注释";\n'
        for method in range(spec['methods_per_class']):
            text += ('    /** 扣减库存文档 */\n'
                     f'    public int reserve{method}(int count) {{\n'
                     '        // 检查库存\n'
                     '        int remaining = count - 1; // 库存不足\n'
                     '        return remaining;\n    }\n')
        text += '}\n// 文件末尾库存\n'
        (sources/(name+'.java')).write_text(text)
    (project/'.code-ana').mkdir()
    config=(ROOT/'tests/fixtures/java-native/base/.code-ana/config.toml').read_text()
    config=config.replace('id = "app"','id = "bench"')
    config += '\n[resources.index]\nworker_memory_bytes='+str(spec['worker_memory_bytes'])+'\n'
    config += '\n[resources.index.analysis]\n'+''.join(f'{key}={value}\n' for key,value in spec['analysis'].items())
    (project/'.code-ana/config.toml').write_text(config)
    subprocess.run(['git','init','--quiet'],cwd=project,check=True,capture_output=True)
    proof=dict(kind='comment_feature_fixed_fixture',spec=spec,files=inventory(project),
               definition_sha256=sha(SPEC),driver_sha256=sha(SCRIPT),artifacts=artifacts(args.binaries),
               binaries=str(args.binaries),jdk=str(args.jdk),jdk_release_sha256=sha(args.jdk/'release'))
    write_json(args.directory/'fixture.json',proof)


def run(args):
    fixture=json.loads((args.directory/'fixture.json').read_text())
    project=args.directory/'project'
    if inventory(project)!=fixture['files'] or sha(SPEC)!=fixture['definition_sha256'] or sha(SCRIPT)!=fixture['driver_sha256']:
        raise ValueError('FIXED_FIXTURE_DRIFT')
    binaries=pathlib.Path(fixture['binaries'])
    if artifacts(binaries)!=fixture['artifacts']:
        raise ValueError('ACCEPTANCE_ARTIFACT_DRIFT')
    if sha(pathlib.Path(fixture['jdk'])/'release')!=fixture['jdk_release_sha256']:
        raise ValueError('ACCEPTANCE_TARGET_JDK_DRIFT')
    if args.mode=='measurement':
        functional=json.loads((args.directory/'functional/receipt.json').read_text())
        if functional.get('status')!='passed' or functional['fixture_sha256']!=sha(args.directory/'fixture.json'):
            raise ValueError('MATCHING_FUNCTIONAL_PROOF_REQUIRED')
        with (args.directory/'measurement-started.json').open('x') as marker:
            marker.write(json.dumps(dict(fixture_sha256=sha(args.directory/'fixture.json'),
                                         functional_sha256=sha(args.directory/'functional/receipt.json')))+'\n')
    output=args.directory/args.mode
    output.mkdir(exist_ok=False)
    home=output/'home'
    home.mkdir(mode=0o700)
    # A new home contains only explicit policy; caches, revisions and catalog do not exist.
    (home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\n'
        'max_worker_memory_bytes=2147483648\nmax_total_worker_memory_bytes=2147483648\n'
        '[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(fixture['jdk'])+'\n')
    base=[str(binaries/'codeana-cli'),'--home',str(home),'--project',str(project)]
    def cli(name,argv):
        completed=subprocess.run(base+argv,capture_output=True,text=True,timeout=600)
        (output/(name+'.stdout')).write_text(completed.stdout)
        (output/(name+'.stderr')).write_text(completed.stderr)
        records=[json.loads(line[7:]) for line in completed.stdout.splitlines() if line.startswith('record ')]
        terminals=[json.loads(line[9:]) for line in completed.stdout.splitlines() if line.startswith('terminal ')]
        if completed.returncode or len(terminals)!=1 or not terminals[0]['evidence']['semantic'] or not terminals[0]['evidence']['enumeration']:
            raise ValueError('COMMENT_ACCEPTANCE_FAILED: '+name+' '+completed.stdout[-3000:]+' '+completed.stderr[-1000:])
        return records,terminals[0]
    daemon=None
    receipt=dict(kind='comment_feature_'+args.mode,status='failed',fixture_sha256=sha(args.directory/'fixture.json'),
                 native_accepted=False,performance_accepted=False,cache_policy='independent_empty_application_state',
                 os_page_cache='uncontrolled',queries=[])
    with (output/'daemon.log').open('w') as log:
        try:
            daemon=subprocess.Popen([str(binaries/'codeana-daemon'),'--home',str(home)],stdout=log,stderr=log)
            until=time.monotonic()+15
            while not (home/'endpoint.json').exists():
                if daemon.poll() is not None or time.monotonic()>until:
                    raise ValueError('ACCEPTANCE_DAEMON_START_FAILED')
                time.sleep(.05)
            started=time.monotonic()
            _,terminal=cli('index',['index','--full','--strict','--deadline-seconds','600'])
            if args.mode=='measurement':receipt['index_seconds']=time.monotonic()-started
            receipt['revision']=terminal['revision']
            db_path=home/'revisions'/terminal['revision']/'facts.db'
            with contextlib.closing(sqlite3.connect('file:'+str(db_path)+'?mode=ro',uri=True)) as db:
                counts={table:db.execute('SELECT count(*) FROM '+table).fetchone()[0] for table in ('comments','comment_links','nodes','edges')}
                if counts['comments']!=fixture['spec']['expected_comments']:
                    raise ValueError('FIXTURE_COMMENT_COUNT: '+str(counts))
                if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok' or db.execute('PRAGMA foreign_key_check').fetchall():
                    raise ValueError('FIXTURE_GRAPH_INTEGRITY')
            receipt['counts']=counts
            receipt['graph_bytes']=db_path.stat().st_size
            for number,case in enumerate(fixture['spec']['queries']):
                options=['--path',fixture['spec']['path'],'--strict','--max-output-bytes','1048576']
                matches,_=cli(f'query-{number}-comments',['search-comments',case['text'],*options])
                entities,_=cli(f'query-{number}-code',['search-code',case['text'],'--in','comments',*options])
                expected={'methods':[f'bench.Inventory000#reserve{i}(int)' for i in range(fixture['spec']['methods_per_class'])],
                          'class':['bench.Inventory000'],'none':[]}[case['declarations']]
                if len(matches)!=case['comments'] or sorted(r['signature'] for r in entities)!=sorted(expected):
                    raise ValueError('FIXTURE_QUERY_EXPECTATION: '+json.dumps(dict(case=case,comments=len(matches),declarations=[r['signature'] for r in entities]),ensure_ascii=False))
                receipt['queries'].append(dict(text=case['text'],comments=len(matches),declarations=sorted(expected)))
            if artifacts(binaries)!=fixture['artifacts'] or inventory(project)!=fixture['files']:
                raise ValueError('ACCEPTANCE_INPUT_OR_ARTIFACT_DRIFT')
            receipt['status']='passed'
        except BaseException as error:
            receipt['failure']=str(error)
            raise
        finally:
            stopped=subprocess.run(base+['daemon','stop'],capture_output=True,text=True,timeout=30)
            (output/'stop.stdout').write_text(stopped.stdout)
            (output/'stop.stderr').write_text(stopped.stderr)
            if stopped.returncode and receipt['status']=='passed':
                receipt['status']='failed';receipt['cleanup_failure']='daemon stop command failed'
            if daemon is not None:
                try:daemon.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    daemon.kill();daemon.wait();receipt['status']='failed';receipt['cleanup_failure']='daemon did not stop'
            write_json(output/'receipt.json',receipt)
    if receipt['status']!='passed':raise ValueError('ACCEPTANCE_CLEANUP_FAILED')


def measure(args):
    functional=args.directory/'functional/receipt.json'
    if not functional.is_file() or json.loads(functional.read_text()).get('status')!='passed':
        raise ValueError('FUNCTIONAL_FIXTURE_MUST_PASS_BEFORE_MEASUREMENT')
    if (args.directory/'measurement-started.json').exists():
        raise ValueError('SINGLE_MEASUREMENT_ALREADY_STARTED')
    spec=dict(command=[os.sys.executable,str(SCRIPT),'run','--directory',str(args.directory),'--mode','measurement'],
              cwd=str(ROOT),backend='native',cache_policy='independent_empty_application_state',
              disk_roots=[str(args.directory/'measurement/home')],timeout_seconds=900,
              env={'CODEANA_JAVA_BACKEND':'native'},functional_receipt_sha256=sha(functional))
    spec_path=args.directory/'monitor-spec.json'
    with spec_path.open('x') as stream:stream.write(json.dumps(spec,indent=2)+'\n')
    subprocess.run([os.sys.executable,str(ROOT/'scripts/java-performance-monitor-single-v2.py'),
                    '--spec',str(spec_path),'--evidence',str(args.directory/'monitor')],check=True)
    observed=json.loads((args.directory/'monitor/receipt.json').read_text())
    execution=json.loads((args.directory/'measurement/receipt.json').read_text())
    summary=dict(status='passed' if observed['status']==execution['status']=='passed' else 'failed',
                 index_seconds=execution.get('index_seconds'),workload_seconds=observed['seconds'],
                 process_tree_peak_bytes=observed['process_tree_peak_bytes'],graph_bytes=execution.get('graph_bytes'),
                 counts=execution.get('counts'),queries=execution.get('queries'),samples=observed['samples'],
                 process_sample_seconds=observed['process_sample_seconds'],os_page_cache='uncontrolled',
                 native_accepted=False,performance_accepted=False,
                 scope='one local fixed fixture measurement; no capacity or cross-platform certification',
                 inputs={str(path):sha(path) for path in [functional,args.directory/'fixture.json',
                         args.directory/'measurement/receipt.json',args.directory/'monitor/receipt.json']})
    write_json(args.directory/'measurement-summary.json',summary)
    if summary['status']!='passed':raise ValueError('MEASUREMENT_FAILED')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['prepare','run','measure'])
    parser.add_argument('--directory',type=lambda s:pathlib.Path(s).resolve(),required=True)
    parser.add_argument('--binaries',type=lambda s:pathlib.Path(s).resolve(),default=ROOT/'target/release')
    parser.add_argument('--jdk',type=lambda s:pathlib.Path(s).resolve(),default=pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu')
    parser.add_argument('--mode',choices=['functional','measurement'],default='functional')
    args=parser.parse_args()
    {'prepare':prepare,'run':run,'measure':measure}[args.action](args)

if __name__=='__main__':main()
