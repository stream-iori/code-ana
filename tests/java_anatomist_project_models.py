#!/usr/bin/env python3
"""Independent JVM/native model evidence over every frozen Maven scope.

This gate observes pre-projection models. Published graph/query parity is a separate gate.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import shutil
import time

from java_anatomist_reference import REFERENCE, REFERENCE_SHA, JDK, HOST
from java_real_acceptance import verify_frozen, atomic_json
from java_real_compiler_diagnostics import compiler_context
from java_project_model_api_alignment import expected_contexts
import java_alignment

ROOT=pathlib.Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

def lombok_resources(project, sources):
    """Capture ancestor controls as Rust does, separately from Java/XML publication."""
    project = project.resolve()
    directories = set()
    for source in sources:
        directory = pathlib.Path(source['input']).resolve().parent
        if not directory.is_relative_to(project):
            raise ValueError('LOMBOK_SOURCE_OUTSIDE_FROZEN_PROJECT')
        for depth in range(256):
            directories.add(directory)
            if directory == project: break
            directory = directory.parent
        else: raise ValueError('LOMBOK_CONFIG_DEPTH_BUDGET')
    files = [(str((directory/'lombok.config').relative_to(project)), directory/'lombok.config')
             for directory in sorted(directories) if (directory/'lombok.config').is_file()]
    for index, directory in enumerate(project.parents):
        path = directory/'lombok.config'
        if path.is_file():
            files.append((f'.code-ana/lombok-ancestors/{index+1:06}/lombok.config', path))
    if len(files) > 4096: raise ValueError('LOMBOK_CONFIG_FILE_BUDGET')
    result = []; total = 0
    for relative, path in files:
        size = path.stat().st_size; total += size
        if size > 1024*1024 or total > 8*1024*1024:
            raise ValueError('LOMBOK_CONFIG_BYTE_BUDGET')
        result.append(dict(input=str(path),unit=dict(path=relative,hash=digest(path),
            module='',world='files',scope='JAVA_CONFIG')))
    return result

def configuration_inputs(project):
    paths = set(project.rglob('lombok.config'))
    paths.update(p/'lombok.config' for p in project.parents if (p/'lombok.config').is_file())
    return {str(p.resolve()):digest(p) for p in sorted(paths) if p.is_file()}

def reactor_contexts(project,manifest,module,scope,mode,manifest_digest):
    """Retain producer-owned classpaths when isolating a consumer publication.

    Auxiliary MAIN contexts supply signatures, never publication. A TEST consumer
    also needs its own MAIN producer; do not give every module the consumer jars.
    """
    modules={m['id']:m for m in manifest['modules']}
    identities={(module,scope)}
    pending=[(module,scope)]
    while pending:
        owner,world=pending.pop()
        needed={(d,'MAIN') for d in modules[owner]['dependencies'][world]}
        if world=='TEST':needed.add((owner,'MAIN'))
        for identity in sorted(needed-identities):
            identities.add(identity);pending.append(identity)
    contexts=[]
    for owner,world in [(module,scope),*sorted(identities-{(module,scope)})]:
        context=compiler_context(project,manifest,owner,world)
        context.update(digest=manifest_digest,dependency_modules=list(modules[owner]['dependencies'][world]),
            lombok='model' if mode=='ast' else 'off',spring=True,sofa=True,spring_xml=False,resources=[])
        if mode == 'ast': context['resources'] = lombok_resources(project, context['sources'])
        if (owner,world)!=(module,scope):
            context['prerequisite']=True
            for source in context['sources']:source['publish']=False
        contexts.append(context)
    return contexts

def verified_resume_rows(previous,current):
    for key in ('kind','frozen_project','reference_sha256','inputs','source_inputs',
                'inputs_manifest_sha256','native_shared_library_sha256','compiler_bridge_sha256',
                'signature_cache_policy','expected_contexts','run_options','configuration_inputs'):
        if previous.get(key)!=current.get(key):raise ValueError('MODEL_RESUME_INPUT_DRIFT: '+key)
    rows=[];seen=set()
    for row in previous.get('contexts',[]):
        if row.get('accepted') is not True:continue
        identity=(row['mode'],row['context'])
        if identity in seen or row['mode'] not in ('raw','ast') or row['context'] not in current['expected_contexts']:
            raise ValueError('MODEL_RESUME_CONTEXT_IDENTITY')
        seen.add(identity)
        if row.get('differences')!={'migrated_jvm':[],'migrated_native':[]}:
            raise ValueError('MODEL_RESUME_CONTEXT_FAILED')
        proofs=row.get('proofs',{})
        required={'reference.json','migrated_jvm.json','migrated_native.json','context.json','native-request.json'}
        if not required.issubset({pathlib.Path(p).name for p in proofs}):
            raise ValueError('MODEL_RESUME_PROOF_MISSING')
        for path,checksum in proofs.items():
            if not pathlib.Path(path).is_file() or digest(pathlib.Path(path))!=checksum:
                raise ValueError('MODEL_RESUME_PROOF_DRIFT: '+path)
        values=[json.loads(pathlib.Path(p).read_text()) for p in proofs
                if pathlib.Path(p).name in {'reference.json','migrated_jvm.json','migrated_native.json'}]
        if len(values)!=3 or not values[0]==values[1]==values[2]:
            raise ValueError('MODEL_RESUME_MODEL_MISMATCH')
        categories={'raw.syntax','raw.bindings','raw.types','raw.declarations','raw.extensions','raw.source_hash','raw.diagnostics',
            'facts.nodes','facts.edges','facts.annotations','facts.annotationMetaRelations','facts.semanticAnnotations','facts.declarations','facts.unresolvedCallSites'}
        if set(values[0])!=categories or any(v.get('encoding')!='sorted-row-sha256-v2-graph-captures'
                or type(v.get('rows')) is not int or v['rows']<0 or not isinstance(v.get('sha256'),str)
                or len(v['sha256'])!=64 for v in values[0].values()):raise ValueError('MODEL_RESUME_CATEGORY_PROOF')
        request=next(json.loads(pathlib.Path(p).read_text()) for p in proofs if pathlib.Path(p).name=='context.json')
        consumer=request['contexts'][0]
        if consumer['module']+'@'+consumer['scope']!=row['context'] or row['source_count']!=len(consumer['sources']) or row['published_source_count']!=sum(v['publish'] for v in consumer['sources']):
            raise ValueError('MODEL_RESUME_SOURCE_COVERAGE')
        rows.append(row)
    return rows


def seed_signature_caches(source, destination, modes):
    """Copy stable backend/mode namespaces; compiler keys still validate every entry."""
    proof = dict(copied=[], skipped=[], promotes_context_acceptance=False)
    for mode in modes:
        for backend in ('reference','migrated_jvm','migrated_native'):
            folder = mode+'-'+backend+'-signatures'
            origin = source/folder
            if not origin.is_dir(): continue
            for path in sorted(origin.rglob('*')):
                if not path.is_file() or path.is_symlink() or path.name.endswith('.lock'): continue
                target=destination/folder/path.relative_to(origin)
                target.parent.mkdir(parents=True,exist_ok=True)
                try:
                    before=digest(path);shutil.copy2(path,target)
                    if digest(path)!=before or digest(target)!=before:
                        target.unlink();proof['skipped'].append(str(path));continue
                    proof['copied'].append(dict(source=str(path),target=str(target),sha256=before))
                except FileNotFoundError:
                    target.unlink(missing_ok=True);proof['skipped'].append(str(path))
    atomic_json(destination/'cache-seed.json',proof)
    return proof


def model_result_summary(results):
    """Independent backend evidence never substitutes for a missing oracle."""
    summary = dict(accepted=False, comparison_available='reference' in results,
                   migrated_backend_comparison_available=all(k in results for k in ('migrated_jvm','migrated_native')))
    if summary['migrated_backend_comparison_available']:
        left, right = results['migrated_jvm'], results['migrated_native']
        summary['migrated_backend_differences'] = sorted(k for k in left.keys() | right.keys() if left.get(k) != right.get(k))
        summary['migrated_backends_equal'] = not summary['migrated_backend_differences']
    if len(results) == 3:
        reference = results['reference']
        summary['differences'] = {label: sorted(k for k in reference.keys() | results[label].keys()
            if reference.get(k) != results[label].get(k)) for label in ('migrated_jvm','migrated_native')}
        summary['accepted'] = not any(summary['differences'].values())
        raw_categories = {'raw.syntax','raw.bindings','raw.types','raw.declarations','raw.extensions','raw.source_hash','raw.diagnostics'}
        summary['raw_model_comparison_available'] = all(raw_categories <= set(value) for value in results.values())
        summary['raw_models_equal'] = summary['raw_model_comparison_available'] and all(
            not raw_categories.intersection(differences) for differences in summary['differences'].values())
    return summary


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--keep-going',action='store_true',help='Collect every context failure; failures still reject complete acceptance')
    parser.add_argument('--resume',action='store_true',help='Resume only independently verified matching context proofs')
    parser.add_argument('--project',type=pathlib.Path,default=ROOT/'target/java-alignment/iopengw')
    parser.add_argument('--inputs-manifest',type=pathlib.Path)
    parser.add_argument('--mode',choices=('raw','ast','both'),default='both')
    parser.add_argument('--context',help='A diagnostic subset never certifies the complete project')
    parser.add_argument('--row-evidence',action='store_true',help='Keep per-source model digests and original diagnostics')
    parser.add_argument('--shared-signature-cache',action='store_true',help='Reuse validated signatures between correctness contexts; not a cold performance run')
    parser.add_argument('--seed-signatures-from',type=pathlib.Path,help='Copy stable matching backend/mode caches; correctness collection only')
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    args=parser.parse_args();project=args.project.resolve()
    if args.seed_signatures_from and not args.shared_signature_cache:parser.error('Signature seeding requires shared correctness caches')
    manifest_path=args.inputs_manifest or project/'.code-ana/main-test-inputs-frozen.json'
    manifest=json.loads(manifest_path.read_text());expected=list(expected_contexts(manifest))
    assert len(expected)==80 and len(manifest['modules'])==40,'FULL_REACTOR_REQUIRED'
    if args.context and args.context not in [m+'@'+s for m,s in expected]:parser.error('Unknown frozen context: '+args.context)
    assert digest(REFERENCE)==REFERENCE_SHA,'FROZEN_REFERENCE_DRIFT'
    frozen=verify_frozen(project)
    assert frozen['commit']=='267c7ced4993aa88613f8f17a8099943fec98159'
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=args.resume)
    if args.seed_signatures_from and not args.resume:
        seed_signature_caches(args.seed_signatures_from.resolve(),evidence,('raw','ast') if args.mode=='both' else (args.mode,))
    probe=ROOT/'target/java-migration-probe'
    jar=ROOT/'lang-java-adapotr/native-bridge/target/native-bridge-0.1.0.jar'
    provenance=json.loads((probe/'build-provenance.json').read_text())
    required={str(p.resolve()) for p in [jar,probe/'native-model-witness',*sorted((ROOT/'tests/java-migration').glob('*.java'))]}
    assert set(provenance)==required,'MODEL_WITNESS_BUILD_PROVENANCE_INCOMPLETE'
    assert all(digest(pathlib.Path(p))==sha for p,sha in provenance.items()),'MODEL_WITNESS_STALE: rebuild scripts/build-java-migration-probe.sh'
    inputs={p.resolve():digest(p) for p in [REFERENCE,jar,probe/'native-model-witness',manifest_path,
        probe/'build-provenance.json',ROOT/'tests/java-migration/ParityWitness.java',ROOT/'tests/java-migration/NativeModelWitness.java',HOST/'release',HOST/'bin/java',JDK/'release',JDK/'bin/java',JDK/'lib/tools.jar']}
    report=dict(kind='anatomist_frozen_project_preprojection_models',state='running',frozen_project=frozen,
        reference_sha256=REFERENCE_SHA,inputs={str(p):sha for p,sha in inputs.items()},
        source_inputs=java_alignment.input_files(),inputs_manifest_sha256=digest(manifest_path),
        native_shared_library_sha256=digest(ROOT/'target/lib/java/native/libcodeana_java.dylib'),
        compiler_bridge_sha256=digest(ROOT/'target/lib/java/compiler-bridge.jar'),
        signature_cache_policy='shared_per_mode_and_backend' if args.shared_signature_cache else 'fresh_per_context',
        run_options=dict(mode=args.mode,context=args.context,row_evidence=args.row_evidence),
        expected_contexts=[m+'@'+s for m,s in expected],contexts=[],full_semantic_diff_accepted=False,
        complete_model_api_inventory=False,capacity_accepted=False)
    report['configuration_inputs'] = configuration_inputs(project)
    output=evidence/'receipt.json'
    if args.resume:
        if not output.is_file():raise ValueError('MODEL_RESUME_RECEIPT_REQUIRED')
        report['contexts']=verified_resume_rows(json.loads(output.read_text()),report)
    atomic_json(output,report)
    modes=['raw','ast'] if args.mode=='both' else [args.mode]
    for mode in modes:
        for index,(module,scope) in enumerate(expected):
            if args.context and module+'@'+scope!=args.context:continue
            if any(row['mode']==mode and row['context']==module+'@'+scope for row in report['contexts']):continue
            directory=evidence/(mode+'-'+str(index))
            attempt=1
            while directory.exists():
                attempt+=1;directory=evidence/(mode+'-'+str(index)+'-attempt-'+str(attempt))
            directory.mkdir()
            contexts=reactor_contexts(project,manifest,module,scope,mode,digest(manifest_path))
            request=contexts[0]
            configurations=sorted(project.rglob('lombok.config'))
            # Configuration resources have one stable owning world across contexts.
            request['resources']=[dict(input=str(p),unit=dict(path=str(p.relative_to(project)),hash=digest(p),module=module,world=request['world'],scope='JAVA_CONFIG')) for p in configurations]
            request_path=directory/'context.json';atomic_json(request_path,dict(contexts=contexts))
            row=dict(mode=mode,context=module+'@'+scope,source_count=len(request['sources']),published_source_count=sum(s['publish'] for s in request['sources']),prerequisite_contexts=len(contexts)-1,accepted=False)
            tick=time.monotonic()
            results={}
            for label in ['reference','migrated_jvm','migrated_native']:
                scratch=directory/(label+'-scratch');scratch.mkdir()
                cache=evidence/(mode+'-'+label+'-signatures') if args.shared_signature_cache else scratch
                cache.mkdir(exist_ok=True)
                if label=='migrated_native':
                    native_request=dict(version=8,full=True,facts=True,java_home=str(JDK),scratch=str(scratch),contexts=contexts,
                        defer_fact_dedup=True,signature_cache=True,cache_root=str(cache/"signature-cache"),compiler_timeout_seconds=600,compiler_heap_bytes=512<<20,result_bytes=4<<30,fact_limit=5000000)
                    path=directory/'native-request.json';atomic_json(path,native_request)
                    command=[str(probe/'native-model-witness'),str(path)]
                else:
                    classpath=str(probe/'classes')+':'+(str(REFERENCE) if label=='reference' else str(ROOT/'lang-java-adapotr/native-bridge/target/test-classes')+':'+str(jar))
                    command=[str(HOST/'bin/java'),'-Xmx1024m','-cp',classpath,'ParityWitness',str(project),str(JDK),str(cache),
                        'ast' if mode=='ast' else 'off','reference' if label=='reference' else 'captured','-',str(request_path)]
                environment=dict(os.environ)
                if args.row_evidence:environment['CODEANA_MODEL_ROW_EVIDENCE']=str(directory/(label+'-rows'))
                try:
                    process=subprocess.run(command,capture_output=True,text=True,timeout=1200,env=environment)
                except subprocess.TimeoutExpired as failure:
                    def text(value):return value.decode(errors='replace') if isinstance(value,bytes) else value or ''
                    (directory/(label+'.log')).write_text(text(failure.stderr))
                    (directory/(label+'.stdout')).write_text(text(failure.stdout))
                    row.setdefault('failed_stage',label)
                    row.setdefault('error','MODEL_WITNESS_TIMEOUT')
                    row.setdefault('failures',[]).append(dict(stage=label,error='MODEL_WITNESS_TIMEOUT'))
                    if args.keep_going:continue
                    break
                (directory/(label+'.log')).write_text(process.stderr)
                (directory/(label+'.stdout')).write_text(process.stdout)
                if process.returncode:
                    row.setdefault('failed_stage',label)
                    row.setdefault('exit_code',process.returncode)
                    row.setdefault('failures',[]).append(dict(stage=label,exit_code=process.returncode))
                    if args.keep_going:continue
                    break
                results[label]=json.loads(process.stdout.splitlines()[-1]);atomic_json(directory/(label+'.json'),results[label])
            row.update(model_result_summary(results))
            row['no_java_inputs']=row['source_count']==0
            row['publication_scope_empty']=row['published_source_count']==0
            row.update(elapsed_seconds=round(time.monotonic()-tick,3),evidence=str(directory))
            row['proofs']={str(p.resolve()):digest(p) for p in sorted([*directory.glob('*.json'),
                *directory.glob('*-rows/*.json'),*directory.glob('*.log'),*directory.glob('*.stdout')])}
            report['contexts'].append(row)
            report['contexts'].sort(key=lambda r:(['raw','ast'].index(r['mode']),report['expected_contexts'].index(r['context'])))
            atomic_json(output,report)
            print(json.dumps(row),flush=True)
            if not row['accepted'] and not args.keep_going:
                report['state']='failed';atomic_json(output,report);return 1
    report['changed_inputs']=[str(p) for p,sha in inputs.items() if digest(p)!=sha]
    current=java_alignment.input_files()
    report['changed_source_inputs']=sorted(k for k in report['source_inputs'].keys()|current.keys() if report['source_inputs'].get(k)!=current.get(k))
    current_controls=configuration_inputs(project)
    report['changed_configuration_inputs']=sorted(k for k in report['configuration_inputs'].keys()|current_controls.keys()
        if report['configuration_inputs'].get(k)!=current_controls.get(k))
    report['complete_model_api_inventory']=not args.context and args.mode=='both' and len(report['contexts'])==160 and all(r['accepted'] for r in report['contexts']) and not report['changed_inputs'] and not report['changed_source_inputs'] and not report['changed_configuration_inputs']
    report['state']='finished';atomic_json(output,report)
    return 0 if not report['changed_inputs'] and not report['changed_source_inputs'] and not report['changed_configuration_inputs'] and all(r['accepted'] for r in report['contexts']) else 1

if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception as failure:
        import sys
        if '--evidence' in sys.argv:
            path=pathlib.Path(sys.argv[sys.argv.index('--evidence')+1])/'receipt.json'
            if path.is_file():
                report=json.loads(path.read_text());report.update(state='failed',failure_type=type(failure).__name__,failure=str(failure))
                atomic_json(path,report)
        raise
