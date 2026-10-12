#!/usr/bin/env python3
"""Generate a fresh frozen-reference database with captured Maven and source proofs."""
import argparse,json,os,pathlib,shutil,signal,subprocess,sys,tarfile,time,xml.etree.ElementTree as ET
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/"tests"))
from java_fresh_reference import reference
import java_reference_proof as reference_validator
from java_real_acceptance import ROOT,atomic_json,digest,verify_frozen
from java_anatomist_reference import HOST,JDK
from java_reference_proof import validate_reference


def run_command(command, log, timeout, *, cwd=None, env=None):
    """Reap our own process group on timeout or an interrupted reference run."""
    started=time.monotonic();process=None
    result=dict(command=list(map(str,command)),status='failed',exit_code=None)
    try:
        with pathlib.Path(log).open('wb') as output:
            try:
                process=subprocess.Popen(command,cwd=cwd,env=env,stdout=output,
                    stderr=subprocess.STDOUT,start_new_session=True)
            except OSError as failure:
                result.update(status='blocked',error=str(failure))
            else:
                try:
                    result['exit_code']=process.wait(timeout=timeout)
                    result['status']='passed' if result['exit_code']==0 else 'failed'
                except subprocess.TimeoutExpired:
                    result['error']='TIMEOUT'
    finally:
        if process is not None and process.poll() is None:
            try:os.killpg(process.pid,signal.SIGTERM)
            except ProcessLookupError:pass
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                try:os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError:pass
                process.wait(timeout=10)
            result['exit_code']=process.returncode
        result.update(seconds=time.monotonic()-started,log=str(log),log_sha256=digest(log))
    return result


def verify_maven_xml(paths,project,manifest):
    wanted={m.get('root',m['id']):m for m in manifest['modules']};observed={}
    for path in paths:
        if path.stat().st_size>32<<20:raise ValueError('REFERENCE_MAVEN_BYTE_BUDGET')
        payload=path.read_bytes()
        if path.with_suffix('.sha256').read_text()!=digest(path):raise ValueError('REFERENCE_MAVEN_RESPONSE_CHECKSUM')
        if b'<!DOCTYPE' in payload or b'<!ENTITY' in payload:raise ValueError('REFERENCE_MAVEN_XML_ENTITY')
        document=ET.fromstring(payload)
        if document.tag!='maven-inputs' or document.get('resolved')!='true':continue
        for element in document.findall('module'):
            directory=pathlib.Path(element.attrib['directory']).resolve()
            name=str(directory.relative_to(project.resolve()))
            module=wanted.get(name)
            if module is None:
                roots=[pathlib.Path(v.text) for v in element if v.tag in ('main-root','test-root','generated-main-root','generated-test-root')]
                if any(any(root.rglob('*.java')) for root in roots):raise ValueError('REFERENCE_UNEXPECTED_JAVA_MODULE: '+name)
                continue  # Maven may also export an aggregator with no Java inputs.
            proof={}
            for scope,tag in [('MAIN','main-dependencies'),('TEST','test-dependencies')]:
                section=element.find(tag)
                if section is None:raise ValueError('REFERENCE_SCOPED_DEPENDENCIES_REQUIRED')
                jars=[dict(path=v.attrib['path'],sha256=digest(pathlib.Path(v.attrib['path'])),coordinate=v.attrib.get('coordinate',''),classifier=v.attrib.get('classifier',''),extension=v.attrib.get('extension','jar')) for v in section if v.tag=='external']
                dependencies=sorted(str(pathlib.Path(v.attrib['directory']).resolve().relative_to(project.resolve())) for v in section if v.tag=='reactor')
                if jars!=module['classpath'][scope] or dependencies!=sorted(module['dependencies'][scope]):
                    raise ValueError('REFERENCE_MAVEN_SEMANTIC_DRIFT: '+name+'@'+scope)
                proof[scope]=(jars,dependencies)
            roots=[]
            for scope,tag in [('MAIN','main-root'),('TEST','test-root'),('MAIN','generated-main-root'),('TEST','generated-test-root')]:
                for value in element.findall(tag):
                    directory_root=pathlib.Path(value.text).resolve()
                    if tag.startswith('generated-') and not directory_root.exists():continue
                    roots.append((scope,str(directory_root.relative_to(directory))))
            expected=[(v['scope'],v['path']) for v in module['source_roots']]
            if sorted(roots)!=sorted(expected):raise ValueError('REFERENCE_MAVEN_SOURCE_ROOT_DRIFT: '+name)
            if name in observed and observed[name]!=proof:raise ValueError('REFERENCE_MAVEN_CAPTURE_CONFLICT')
            observed[name]=proof
    if set(observed)!=set(wanted):raise ValueError('REFERENCE_MAVEN_CAPTURE_INCOMPLETE')


def main():
    parser=argparse.ArgumentParser()
    for name in ('project','inputs-manifest','reference-jar','evidence'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    parser.add_argument("--freeze-receipt",type=pathlib.Path,required=True)
    args=parser.parse_args();source=args.project.resolve();evidence=args.evidence.resolve()
    oracle,oracle_proof=reference()
    expected_oracle=oracle_proof["jar_sha256"]
    reference_validator.REFERENCE_SHA=expected_oracle
    evidence.relative_to((ROOT/'target').resolve());evidence.mkdir(parents=True,exist_ok=False)
    verify_frozen(source,args.freeze_receipt);frozen=json.loads(args.freeze_receipt.read_text())
    if digest(args.reference_jar)!=expected_oracle:raise ValueError('REFERENCE_JAR_DRIFT')
    report=dict(kind='frozen_anatomist_database_generation',state='running',commit=frozen['commit'],
        fresh_oracle_proof=oracle_proof,freeze_receipt_sha256=digest(args.freeze_receipt),
        generator_source_sha256=digest(ROOT/'tests/java_frozen_reference.py'),
        compatibility_note='Shared provenance validator receives only the independently verified fresh frozen Git build SHA; old frozen JAR acceptance is not used.',
        reference_jar_sha256=digest(args.reference_jar),inputs_manifest_sha256=digest(args.inputs_manifest),
        scopes=['MAIN','TEST'],xml=True,lombok='ast',changed_inputs=[],maven_inputs_verified=False)
    receipt=evidence/'receipt.json';atomic_json(receipt,report);started=time.monotonic()
    project=evidence/'iopengw';project.mkdir()
    with (evidence/'committed.tar').open('wb') as out:
        subprocess.run(['git','-C',frozen['source'],'archive',frozen['commit']],stdout=out,check=True)
    with tarfile.open(evidence/'committed.tar') as archive:archive.extractall(project,filter='data')
    (evidence/'committed.tar').unlink();atomic_json(evidence/'iopengw-freeze.json',frozen);verify_frozen(project,args.freeze_receipt)
    paths=subprocess.check_output(['git','-C',frozen['source'],'ls-tree','-rz',frozen['commit']]).split(b'\0')
    report['source_sha256']={row.split(b'\t',1)[1].decode():digest(project/row.split(b'\t',1)[1].decode()) for row in paths if row and b' blob ' in row.split(b'\t',1)[0]}
    report['excluded_invalid_utf8']={}
    for relative,checksum in report['source_sha256'].items():
        if relative.endswith('.java'):
            try:(project/relative).read_bytes().decode('utf-8')
            except UnicodeDecodeError:report['excluded_invalid_utf8'][relative]=checksum
    report['inputs']={str(p.resolve()):digest(p) for p in [args.reference_jar,args.inputs_manifest,HOST/'release',HOST/'bin/java',JDK/'release',JDK/'bin/java',JDK/'lib/tools.jar',pathlib.Path(__file__),ROOT/'tests/java_reference_proof.py',args.freeze_receipt.resolve(),ROOT/'tests/java_frozen_reference.py']}
    captures=evidence/'maven-captures';captures.mkdir();binpath=evidence/'bin';binpath.mkdir()
    maven=pathlib.Path.home()/'.sdkman/candidates/maven/current/bin/mvn'
    wrapper=binpath/'mvn'
    wrapper.write_text('#!'+sys.executable+'\n'+'''import pathlib,subprocess,sys,shutil,uuid
args=sys.argv[1:]
if 'validate' not in args or any(v in args for v in ['compile','test','package','install','deploy','clean','verify']):sys.exit(64)
output=next((v.split('=',1)[1] for v in args if v.startswith('-Danatomist.inputs.output=')),None)
if not output or not any(v.startswith('-Dmaven.ext.class.path=') for v in args):sys.exit(64)
result=subprocess.run(['''+repr(str(maven))+''','--offline',*args])
if result.returncode==1:
 for path in pathlib.Path(output).glob('*.xml'):
  checksum=path.with_suffix('.sha256')
  if not checksum.is_file():continue
  prefix=uuid.uuid4().hex
  destination=pathlib.Path('''+repr(str(captures))+''')
  shutil.copy2(path,destination/(prefix+'.xml'))
  shutil.copy2(checksum,destination/(prefix+'.sha256'))
sys.exit(result.returncode)
''');wrapper.chmod(0o700)
    config=project/'.anatomist/config.toml';original=config.read_bytes()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\njavac_analysis_threads=1\njavac_worker_heap_mb=512\nspring_xml=true\n[extensions.lombok]\nmode="ast"\n')
    report['effective_config_sha256']=digest(config)
    command=[str(HOST/'bin/java'),'-Xmx2048m','-jar',str(args.reference_jar.resolve()),'index',str(project),
        '--jdk-home',str(JDK),'--java-version','8','--scan-scope','MAIN','--scan-scope','TEST',
        '--lombok','ast','--vm-classpath','true','--output',str(evidence/'reference.db'),
        '--report-file',str(evidence/'reference-report.txt'),'--timings','--full']
    for relative in report['excluded_invalid_utf8']:command.extend(['--scan-exclude',relative])
    report['command']=command;atomic_json(receipt,report)
    environment=dict(os.environ,JAVA_HOME=str(JDK),ANATOMIST_HOME=str(evidence/'home'),PATH=str(binpath)+os.pathsep+os.environ['PATH'])
    for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'):environment.pop(key,None)
    try:
        execution=run_command(command,evidence/"run.log",3600,cwd=project,env=environment)
        report["execution"]=execution
        report["exit_code"]=execution["exit_code"]
        report["unexpected_signal"]=(-execution["exit_code"] if isinstance(execution["exit_code"],int) and execution["exit_code"]<0 and not execution.get("error") else None)
        if execution["status"]!="passed":raise ValueError("REFERENCE_INDEX_FAILED")
        config.write_bytes(original);verify_frozen(project,args.freeze_receipt)
        proofs=sorted(captures.glob('*.xml'))
        verify_maven_xml(proofs,project,json.loads(args.inputs_manifest.read_text()))
        report['maven_proofs']={str(p):digest(p) for p in sorted(captures.iterdir())};report['maven_inputs_verified']=True
        report['changed_inputs']=[p for p,h in report['inputs'].items() if digest(pathlib.Path(p))!=h]
        report.update(state='finished',database_sha256=digest(evidence/'reference.db'),elapsed_seconds=time.monotonic()-started)
        atomic_json(receipt,report)
        verified=validate_reference(args.reference_jar,evidence/'reference.db',receipt,args.inputs_manifest,source)
        report['verified_source_coverage']=verified['verified_source_coverage']
    except BaseException as failure:
        report.update(state='interrupted' if isinstance(failure,(KeyboardInterrupt,SystemExit)) else 'failed',
                      failure=str(failure),failure_type=type(failure).__name__);raise
    finally:
        config.write_bytes(original);report['elapsed_seconds']=time.monotonic()-started;atomic_json(receipt,report)
    print(json.dumps(dict(state=report['state'],database=str(evidence/'reference.db'),receipt=str(receipt))))
    return 0

if __name__=='__main__':raise SystemExit(main())
