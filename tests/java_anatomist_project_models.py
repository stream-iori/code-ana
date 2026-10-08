#!/usr/bin/env python3
"""Independent JVM/native model evidence over every frozen Maven scope.

This gate observes pre-projection models. Published graph/query parity is a separate gate.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time

from java_anatomist_reference import REFERENCE, REFERENCE_SHA, JDK, HOST
from java_real_acceptance import verify_frozen, atomic_json
from java_real_compiler_diagnostics import compiler_context
from java_project_model_api_alignment import expected_contexts

ROOT=pathlib.Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

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
        if (owner,world)!=(module,scope):
            context['prerequisite']=True
            for source in context['sources']:source['publish']=False
        contexts.append(context)
    return contexts

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--project',type=pathlib.Path,default=ROOT/'target/java-alignment/iopengw')
    parser.add_argument('--inputs-manifest',type=pathlib.Path)
    parser.add_argument('--mode',choices=('raw','ast','both'),default='both')
    parser.add_argument('--context',help='A diagnostic subset never certifies the complete project')
    parser.add_argument('--evidence',type=pathlib.Path,required=True)
    args=parser.parse_args();project=args.project.resolve()
    manifest_path=args.inputs_manifest or project/'.code-ana/main-test-inputs-frozen.json'
    manifest=json.loads(manifest_path.read_text());expected=list(expected_contexts(manifest))
    assert len(expected)==80 and len(manifest['modules'])==40,'FULL_REACTOR_REQUIRED'
    if args.context and args.context not in [m+'@'+s for m,s in expected]:parser.error('Unknown frozen context: '+args.context)
    assert digest(REFERENCE)==REFERENCE_SHA,'FROZEN_REFERENCE_DRIFT'
    frozen=verify_frozen(project)
    assert frozen['commit']=='267c7ced4993aa88613f8f17a8099943fec98159'
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    probe=ROOT/'target/java-migration-probe'
    jar=ROOT/'lang-java-adapotr/native-bridge/target/native-bridge-0.1.0.jar'
    provenance=json.loads((probe/'build-provenance.json').read_text())
    required={str(p.resolve()) for p in [jar,probe/'native-model-witness',*sorted((ROOT/'tests/java-migration').glob('*.java'))]}
    assert set(provenance)==required,'MODEL_WITNESS_BUILD_PROVENANCE_INCOMPLETE'
    assert all(digest(pathlib.Path(p))==sha for p,sha in provenance.items()),'MODEL_WITNESS_STALE: rebuild scripts/build-java-migration-probe.sh'
    inputs={p.resolve():digest(p) for p in [REFERENCE,jar,probe/'native-model-witness',manifest_path,
        probe/'build-provenance.json',ROOT/'tests/java-migration/ParityWitness.java',ROOT/'tests/java-migration/NativeModelWitness.java']}
    report=dict(kind='anatomist_frozen_project_preprojection_models',state='running',frozen_project=frozen,
        reference_sha256=REFERENCE_SHA,inputs={str(p):sha for p,sha in inputs.items()},
        expected_contexts=[m+'@'+s for m,s in expected],contexts=[],full_semantic_diff_accepted=False,
        complete_model_api_inventory=False,capacity_accepted=False)
    output=evidence/'receipt.json';atomic_json(output,report)
    modes=['raw','ast'] if args.mode=='both' else [args.mode]
    for mode in modes:
        for index,(module,scope) in enumerate(expected):
            if args.context and module+'@'+scope!=args.context:continue
            directory=evidence/(mode+'-'+str(index));directory.mkdir()
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
                if label=='migrated_native':
                    native_request=dict(version=7,full=True,facts=True,java_home=str(JDK),scratch=str(scratch),contexts=contexts,
                        signature_cache=True,cache_root=str(scratch/"signature-cache"),compiler_timeout_seconds=600,compiler_heap_bytes=512<<20,result_bytes=4<<30,fact_limit=5000000)
                    path=directory/'native-request.json';atomic_json(path,native_request)
                    command=[str(probe/'native-model-witness'),str(path)]
                else:
                    classpath=str(probe/'classes')+':'+(str(REFERENCE) if label=='reference' else str(ROOT/'lang-java-adapotr/native-bridge/target/test-classes')+':'+str(jar))
                    command=[str(HOST/'bin/java'),'-Xmx1024m','-cp',classpath,'ParityWitness',str(project),str(JDK),str(scratch),
                        'ast' if mode=='ast' else 'off','reference' if label=='reference' else 'captured','-',str(request_path)]
                process=subprocess.run(command,capture_output=True,text=True,timeout=1200)
                (directory/(label+'.log')).write_text(process.stderr)
                (directory/(label+'.stdout')).write_text(process.stdout)
                if process.returncode:
                    row.update(failed_stage=label,exit_code=process.returncode);break
                results[label]=json.loads(process.stdout.splitlines()[-1]);atomic_json(directory/(label+'.json'),results[label])
            if len(results)==3:
                row['differences']={label:[k for k in results['reference'] if results['reference'][k]!=results[label][k]] for label in ['migrated_jvm','migrated_native']}
                row['accepted']=not any(row['differences'].values())
                row['no_java_inputs']=row['source_count']==0
            row.update(elapsed_seconds=round(time.monotonic()-tick,3),evidence=str(directory))
            report['contexts'].append(row);atomic_json(output,report)
            print(json.dumps(row),flush=True)
            if not row['accepted']:
                report['state']='failed';atomic_json(output,report);return 1
    report['changed_inputs']=[str(p) for p,sha in inputs.items() if digest(p)!=sha]
    report['complete_model_api_inventory']=not args.context and args.mode=='both' and len(report['contexts'])==160 and not report['changed_inputs']
    report['state']='finished';atomic_json(output,report)
    return 0 if not report['changed_inputs'] else 1

if __name__=='__main__':raise SystemExit(main())
