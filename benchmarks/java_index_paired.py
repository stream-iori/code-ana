#!/usr/bin/env python3
"""Three serial, alternating, cold application-cache index comparisons.
Run only after the matching serial semantic gate passes.
The independent benchmark harness is hashed separately from production fixture inputs.
"""
import argparse, json, os, pathlib, shutil, signal, sqlite3, subprocess, sys, threading, time, statistics, tarfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
import java_real_acceptance as acceptance
import java_alignment
from java_capacity import Rss
INVALID = 'app/component/message-protocol-apistandardview/src/test/java/com/alipay/iopengw/component/message/protocol/apistandardview/flowctrl/factor/PrincipalValueFetcherConfigDeserializerTest.java'
JDK25 = pathlib.Path.home()/'.sdkman/candidates/java/25.3.4+1.r25-graalce'
JDK8 = pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu'
MAVEN = pathlib.Path.home()/'.sdkman/candidates/maven/current/bin/mvn'
FROZEN = json.loads((ROOT/'target/java-alignment/recovery-stage2/target/java-alignment/iopengw-freeze.json').read_text())
ORIGINAL_POPEN = subprocess.Popen
parser = argparse.ArgumentParser()
parser.add_argument('--gate', type=pathlib.Path, required=True)
parser.add_argument('--evidence', type=pathlib.Path, required=True)
parser.add_argument('--reference-jar', type=pathlib.Path, required=True)
parser.add_argument('--native-heap-bytes', type=int, default=2147483648)
parser.add_argument('--worker-memory-bytes', type=int, default=4294967296)
args = parser.parse_args()
JAR = args.reference_jar.resolve()
BASE = args.evidence.resolve()
BASE.relative_to((ROOT/'target/java-alignment').resolve())
BASE.mkdir(parents=True, exist_ok=False)
REPORT = dict(kind='three_alternating_frozen_project_index_pairs', state='preflight', owner_pid=os.getpid(),
    performance_accepted=False, capacity_accepted=False, pairs=[], excluded_source=INVALID,
    scopes=['MAIN','TEST'], xml=True, lombok='ast', native_heap_bytes=args.native_heap_bytes, worker_memory_bytes=args.worker_memory_bytes,
    compiler_heap_bytes=536870912, parallelism=1, fresh_application_caches=True,
    os_caches_flushed=False, timing_boundary='Maven export plus daemon startup through successful index process exit; queries and comparisons excluded')
def save(): acceptance.atomic_json(BASE/'receipt.json', REPORT)
def interrupted(sig, frame): raise SystemExit(128+sig)
signal.signal(signal.SIGTERM, interrupted)
def unchanged():
    if acceptance.artifacts()!=REPORT['artifacts'] or java_alignment.input_files()!=REPORT['inputs']:
        raise RuntimeError('FROZEN_RUNTIME_CHANGED')
    if acceptance.digest(JAR)!=REPORT['reference_jar_sha256']:
        raise RuntimeError('FROZEN_REFERENCE_CHANGED')
def no_competing_analysis():
    table=subprocess.check_output(['ps','-axo','pid=,comm=,args='],text=True,timeout=3)
    competing=[]
    for line in table.splitlines():
        fields=line.split(None,2)
        if len(fields)!=3: continue
        pid,comm,argv=fields
        is_java=pathlib.Path(comm).name=='java'
        codeana_java=is_java and any(name in argv for name in ('dev.codeana.compiler.CompilerBridge','dev.codeana.analysis.AnalysisProbe','dev.codeana.analysis.ModelApi','DirectIndexProbe','NativeModelWitness','com.anatomist.worker.JavacWorker'))
        if pathlib.Path(comm).name.startswith('direct-native') or pathlib.Path(comm).name in ('codeana-daemon','codeana-java-worker','model-api-native') or codeana_java or ('-jar '+str(JAR)+' index ') in argv:
            competing.append(dict(pid=int(pid),executable=comm))
    if competing: raise RuntimeError('COMPETING_ANALYSIS_PROCESS: '+json.dumps(competing))

def command(argv, cwd, folder, env, sample=False):
    folder.mkdir(parents=True, exist_ok=False)
    started=time.monotonic()
    with (folder/'run.log').open('wb') as log:
        process=ORIGINAL_POPEN(list(map(str, argv)), cwd=cwd, env=env, stdout=log, stderr=log, start_new_session=True)
        REPORT['active']=dict(pid=process.pid,command=list(map(str,argv)),evidence=str(folder)); save()
        usage=Rss(process.pid) if sample else None
        try:
            if usage: usage.__enter__()
            exit_code=process.wait(timeout=86400)
        finally:
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=10)
            if usage: usage.__exit__()
    elapsed=time.monotonic()-started
    result=dict(exit_code=exit_code,elapsed_seconds=elapsed,unexpected_signal=(-exit_code if exit_code<0 else None),log=str(folder/'run.log'))
    if usage: result['rss_observation']=usage.summary() # Reference contains analyzer Java + child compiler; no Rust capacity verdict.
    REPORT.pop('active',None); save()
    acceptance.atomic_json(folder/'receipt.json',result)
    if exit_code: raise RuntimeError('COMMAND_FAILED: '+str(result))
    return result

def fresh_project(folder):
    folder.mkdir()
    acceptance.atomic_json(folder/'iopengw-freeze.json',FROZEN)
    project=folder/'iopengw';project.mkdir()
    archive=folder/'committed.tar'
    with archive.open('wb') as out:
        subprocess.run(['git','-C',FROZEN['source'],'archive',FROZEN['commit']],stdout=out,check=True)
    with tarfile.open(archive) as src: src.extractall(project,filter='data')
    archive.unlink()
    (project/'.code-ana').mkdir(exist_ok=True)
    (project/'.code-ana/config.toml').write_text('schema_version=1\nkind="project"\n')
    acceptance.verify_frozen(project)
    return project

def manifest_semantics(path):
    value=json.loads(path.read_text())
    project_prefix=str(path.resolve().parent.parent)
    def normalized(item):
        if isinstance(item,dict):
            return {key:normalized(val) for key,val in item.items() if key!='effective_model_sha256'}
        if isinstance(item,list): return [normalized(val) for val in item]
        if isinstance(item,str) and item.startswith('/'):
            if item.startswith(project_prefix+'/'): return '$PROJECT/'+item[len(project_prefix)+1:]
            if item==project_prefix: return '$PROJECT'
        return item
    return normalized({key:value[key] for key in ('modules','scopes','selected_profiles','resolved_classpath')})

def environment():
    env=dict(os.environ,JAVA_HOME=str(JDK8))
    for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'): env.pop(key,None)
    return env

def reference(folder, project):
    binpath=folder/'bin'; binpath.mkdir()
    guard=binpath/'mvn'
    guard.write_text('#!/bin/sh\nset -eu\nhave_extension=false\nhave_output=false\nhave_validate=false\nfor arg in "$@"; do\n case "$arg" in\n -Dmaven.ext.class.path=*) have_extension=true ;;\n -Danatomist.inputs.output=*) have_output=true ;;\n validate) have_validate=true ;;\n compile|test|package|install|deploy|clean|verify) exit 64 ;;\n esac\ndone\n[ "$have_extension" = true ] && [ "$have_output" = true ] && [ "$have_validate" = true ] || exit 64\nexec '+str(MAVEN)+' --offline "$@"\n')
    guard.chmod(0o700)
    config=project/'.anatomist/config.toml'; original=config.read_bytes()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\njavac_analysis_threads=1\njavac_worker_heap_mb=512\nspring_xml=true\n[extensions.lombok]\nmode="ast"\n')
    env=environment();env.update(ANATOMIST_HOME=str(folder/'home'),PATH=str(binpath)+os.pathsep+env['PATH'])
    output=folder/'index'
    try:
        result=command([JDK25/'bin/java','-Xmx'+str(args.native_heap_bytes//1048576)+'m','-jar',JAR,'index',project,'--jdk-home',JDK8,
            '--java-version','8','--scan-scope','MAIN','--scan-scope','TEST','--scan-exclude',INVALID,
            '--lombok','ast','--vm-classpath','true','--output',output/'index.db',
            '--report-file',output/'report.txt','--timings','--full'],project,output,env,True)
    finally: config.write_bytes(original)
    acceptance.verify_frozen(project)
    with sqlite3.connect((output/'index.db').as_uri()+'?mode=ro',uri=True) as db:
        result['nodes']=db.execute('SELECT count(*) FROM nodes').fetchone()[0]
        if not result['nodes']: raise RuntimeError('REFERENCE_EMPTY')
    return result

def product(folder, project):
    export=command([ROOT/'target/release/codeana-cli','--project',project,'java-inputs','export','--output',project/'.code-ana/fresh-inputs.json',
        '--maven',MAVEN,'--java-home',JDK8,'--offline','--timeout','600','--extension',ROOT/'target/lib/java/maven-input-worker.jar'],
        project,folder/'export',environment())
    baseline=ROOT/'target/java-alignment/recovery-stage2/target/java-alignment/iopengw/.code-ana/full-rewrite-inputs.json'
    if manifest_semantics(project/'.code-ana/fresh-inputs.json')!=manifest_semantics(baseline):
        raise RuntimeError('FRESH_MAVEN_SEMANTIC_INPUTS_DRIFT')
    clock={}; watchers=[]
    class TimedPopen(ORIGINAL_POPEN):
        def __init__(self, argv, *a, **kw):
            if pathlib.Path(str(argv[0])).name=='codeana-daemon': clock['start']=time.monotonic()
            super().__init__(argv,*a,**kw)
            if pathlib.Path(str(argv[0])).name=='codeana-cli' and 'index' in argv:
                def observe(): self.wait();clock['end']=time.monotonic()
                thread=threading.Thread(target=observe,daemon=True);watchers.append(thread);thread.start()
    original_config=acceptance.project_config
    def config(*a,**kw):
        return original_config(*a,**kw).replace(b'[scan]\n',('[scan]\nexclude='+json.dumps([INVALID])+'\n').encode(),1)
    old_argv=sys.argv; old_environment=dict(os.environ); clean_environment=environment()
    os.environ.clear();os.environ.update(clean_environment)
    subprocess.Popen=TimedPopen;acceptance.project_config=config
    evidence=folder/'index'
    sys.argv=['java_real_acceptance.py','--project',str(project),'--inputs-manifest','.code-ana/fresh-inputs.json','--scopes','MAIN,TEST','--xml',
        '--deadline-seconds','86400','--native-heap-bytes',str(args.native_heap_bytes),'--worker-memory-bytes',str(args.worker_memory_bytes),
        '--result-bytes','8589934592','--scratch-bytes','34359738368','--compiler-timeout-seconds','3600','--evidence',str(evidence)]
    try:
        with (folder/'driver.log').open('w') as log:
            original_stdout=sys.stdout;sys.stdout=log
            try: exit_code=acceptance.main()
            finally: sys.stdout=original_stdout
    finally:
        subprocess.Popen=ORIGINAL_POPEN;acceptance.project_config=original_config;sys.argv=old_argv
        os.environ.clear();os.environ.update(old_environment)
        for thread in watchers: thread.join(10)
    value=json.loads((evidence/'receipt.json').read_text())
    if exit_code or not value['publication_accepted'] or value.get('unexpected_signal_observed') or set(clock)!={'start','end'}:
        raise RuntimeError('PRODUCT_PUBLICATION_FAILED: '+str(evidence))
    index_seconds=clock['end']-clock['start']
    return dict(elapsed_seconds=export['elapsed_seconds']+index_seconds,maven_seconds=export['elapsed_seconds'],
        index_seconds=index_seconds,publication_accepted=True,query_exit_code=value['query_exit_code'],
        rss=value['rss'],capacity_passed=not value.get('rss_failure'),receipt=str(evidence/'receipt.json'))

try:
    gate=json.loads(args.gate.read_text())
    if not gate.get('full_semantic_diff_accepted'): raise RuntimeError('MATCHING_SEMANTIC_GATE_REQUIRED')
    REPORT.update(gate=str(args.gate.resolve()),gate_sha256=acceptance.digest(args.gate),
        artifacts=acceptance.artifacts(),inputs=java_alignment.input_files(),reference_jar_sha256=acceptance.digest(JAR),
        runtime_commit=gate.get('runtime_commit'),
        checkout_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        runner_sha256=acceptance.digest(pathlib.Path(__file__)), frozen=FROZEN)
    if REPORT['reference_jar_sha256']!='457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e': raise RuntimeError('REFERENCE_JAR_DRIFT')
    publication_path=args.gate.parent/'single-project/receipt.json'
    publication=json.loads(publication_path.read_text())
    if not publication.get('publication_accepted') or publication.get('artifacts')!=REPORT['artifacts'] or publication.get('source_inputs')!=REPORT['inputs']:
        raise RuntimeError('SEMANTIC_GATE_ARTIFACT_OR_SOURCE_MISMATCH')
    REPORT['publication_receipt_sha256']=acceptance.digest(publication_path)
    save()
    for number,order in enumerate((('anatomist','codeana'),('codeana','anatomist'),('anatomist','codeana')),1):
        pair=dict(round=number,order=list(order),results={});REPORT['pairs'].append(pair)
        for name in order:
            unchanged();no_competing_analysis();REPORT['state']=f'round-{number}-{name}';save()
            folder=BASE/f'round-{number}-{name}';project=fresh_project(folder)
            pair['results'][name]=(reference if name=='anatomist' else product)(folder,project)
            unchanged();save()
        pair['ratio']=pair['results']['codeana']['elapsed_seconds']/pair['results']['anatomist']['elapsed_seconds'];save()
    medians={name:statistics.median(pair['results'][name]['elapsed_seconds'] for pair in REPORT['pairs']) for name in ('codeana','anatomist')}
    REPORT.update(state='finished',median_seconds=medians,median_ratio=medians['codeana']/medians['anatomist'])
    REPORT['performance_accepted']=REPORT['median_ratio']<2 and all(pair['ratio']<2 for pair in REPORT['pairs'])
    REPORT['capacity_accepted']=all(pair['results']['codeana']['capacity_passed'] for pair in REPORT['pairs']);save()
except BaseException as error:
    REPORT.update(state='failed',failure=str(error),failure_type=type(error).__name__);save();raise

print(json.dumps({key:REPORT[key] for key in ('state','performance_accepted','capacity_accepted','median_ratio')},indent=2))
raise SystemExit(0 if REPORT['performance_accepted'] and REPORT['capacity_accepted'] else 1)
