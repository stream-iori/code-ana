#!/usr/bin/env python3
"""Execute every available published-graph audit; keep missing domains explicit."""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import time

from java_declaration_inventory_diff import connection, digest
from java_real_acceptance import publication_accepted
import java_real_acceptance as acceptance
from java_project_model_api_alignment import accepted_context, expected_contexts
from java_real_compiler_diagnostics import compiler_context as acceptance_context

ROOT=pathlib.Path(__file__).resolve().parents[1]
REFERENCE_SHA='457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e'
DOMAINS=(
    ('calls','java_call_inventory_diff.py'),
    ('declaration_attributes','java_declaration_inventory_diff.py'),
    ('types_hierarchy_reflection','java_type_inventory_diff.py'),
    ('fields_control_owners','java_field_inventory_diff.py'),
    ('lexical_controls','java_control_inventory_diff.py'),
    ('annotations_meta','java_annotation_inventory_diff.py'),
    ('beans_injections','java_framework_inventory_diff.py'),
    ('framework_registrations','java_registration_inventory_diff.py'),
    ('sofa_xml','java_sofa_xml_inventory_diff.py'),
    ('xml_configuration_models','java_xml_model_inventory_diff.py'),
    ('xml_callable_bindings','java_xml_binding_inventory_diff.py'),
    ('xml_wiring','java_xml_wiring_inventory_diff.py'),
    ('xml_complete_graph','java_xml_graph_inventory_diff.py'),
    ('di_wiring','java_wiring_inventory_diff.py'),
)
# These require independent, complete proofs before this gate can certify parity.
PENDING_DOMAINS=('complete_model_api_inventory','complete_lombok_ast_model_inventory')


def validate_model_inventory(report,publication,manifest,project,mode='raw'):
    if report.get('kind')=='anatomist_frozen_project_preprojection_models':
        validate_anatomist_inventory(report,publication,manifest,project,mode)
        return
    if report.get('model_api_mode','raw')!=mode:
        raise ValueError('SEMANTIC_MODEL_INVENTORY_MODE_MISMATCH')
    expected=[module+'@'+scope for module,scope in expected_contexts(manifest)]
    if (report.get('kind')!='complete_frozen_maven_model_api_inventory' or report.get('state')!='finished'
        or report.get('complete_model_api_inventory') is not True
        or report.get('changed_inputs')!=[] or report.get('changed_source_inputs')!=[]
        or report.get('inputs_manifest_sha256')!=publication.get('inputs_manifest_sha256')
        or report.get('source_inputs')!=publication.get('source_inputs')
        or report.get('expected_contexts')!=expected
        or [row.get('context') for row in report.get('contexts',[])]!=expected):
        raise ValueError('SEMANTIC_COMPLETE_MODEL_INVENTORY_REQUIRED')
    artifacts=publication.get('artifacts',{})
    if (not report.get('native_model_api_executable_sha256')
        or report.get('native_shared_library_sha256')!=artifacts.get('target/lib/java/native/libcodeana_java.dylib')
        or report.get('compiler_bridge_sha256')!=artifacts.get('target/lib/java/compiler-bridge.jar')):
        raise ValueError('SEMANTIC_MODEL_INVENTORY_RUNTIME_MISMATCH')
    for row in report['contexts']:
        if row.get('accepted') is not True:raise ValueError('SEMANTIC_MODEL_CONTEXT_FAILED')
        module,scope=row['context'].rsplit('@',1)
        request=acceptance_context(project,manifest,module,scope)
        if row.get('source_count')!=len(request['sources']) or row.get('published_source_count')!=sum(s['publish'] for s in request['sources']):
            raise ValueError('SEMANTIC_MODEL_CONTEXT_SOURCE_COUNT')
        if row.get('no_java_inputs') is True:
            if row.get('source_count')!=0 or row.get('published_source_count')!=0:
                raise ValueError('SEMANTIC_MODEL_EMPTY_CONTEXT_INVALID')
            continue
        path=pathlib.Path(row.get('receipt',''))
        if not path.is_file() or digest(path)!=row.get('receipt_sha256'):
            raise ValueError('SEMANTIC_MODEL_CONTEXT_PROOF_DRIFT')
        value=json.loads(path.read_text())
        if (not accepted_context(row,value) or value.get('model_api_mode','raw')!=mode or value.get('source_inputs')!=report['source_inputs']
            or value.get('native_model_api_executable_sha256')!=report['native_model_api_executable_sha256']):
            raise ValueError('SEMANTIC_MODEL_CONTEXT_INVENTORY_INCOMPLETE')
    for name,expected_hash in report.get('inputs',{}).items():
        if not pathlib.Path(name).is_file() or digest(name)!=expected_hash:
            raise ValueError('SEMANTIC_MODEL_INVENTORY_PROOF_DRIFT')


def validate_anatomist_inventory(report,publication,manifest,project,mode):
    if mode not in ('raw','lombok_ast'):raise ValueError('SEMANTIC_MODEL_INVENTORY_MODE_MISMATCH')
    expected=[module+'@'+scope for module,scope in expected_contexts(manifest)]
    contexts=report.get('contexts',[])
    if (report.get('state')!='finished' or report.get('complete_model_api_inventory') is not True
        or report.get('changed_inputs')!=[] or report.get('changed_source_inputs')!=[]
        or report.get('source_inputs')!=publication.get('source_inputs')
        or report.get('inputs_manifest_sha256')!=publication.get('inputs_manifest_sha256')
        or report.get('reference_sha256')!=REFERENCE_SHA or report.get('expected_contexts')!=expected
        or [(row.get('mode'),row.get('context')) for row in contexts]!=[(m,c) for m in ('raw','ast') for c in expected]):
        raise ValueError('SEMANTIC_COMPLETE_MODEL_INVENTORY_REQUIRED')
    artifacts=publication.get('artifacts',{})
    if (not report.get('native_shared_library_sha256') or not report.get('compiler_bridge_sha256')
        or report.get('native_shared_library_sha256')!=artifacts.get('target/lib/java/native/libcodeana_java.dylib')
        or report.get('compiler_bridge_sha256')!=artifacts.get('target/lib/java/compiler-bridge.jar')):
        raise ValueError('SEMANTIC_MODEL_INVENTORY_RUNTIME_MISMATCH')
    inputs=report.get('inputs',{})
    if not any(pathlib.Path(p).name=='native-model-witness' for p in inputs) or not any(pathlib.Path(p).name=='build-provenance.json' for p in inputs):
        raise ValueError('SEMANTIC_MODEL_INVENTORY_PROOF_MISSING')
    for path,checksum in inputs.items():
        if not pathlib.Path(path).is_file() or digest(pathlib.Path(path))!=checksum:
            raise ValueError('SEMANTIC_MODEL_INVENTORY_PROOF_DRIFT')
    categories={'raw.syntax','raw.bindings','raw.types','raw.declarations','raw.extensions','raw.source_hash','raw.diagnostics',
        'facts.nodes','facts.edges','facts.annotations','facts.annotationMetaRelations','facts.semanticAnnotations','facts.declarations','facts.unresolvedCallSites'}
    selected='ast' if mode=='lombok_ast' else 'raw'
    for row in contexts:
        if row['mode']!=selected:continue
        if row.get('accepted') is not True or row.get('differences')!={'migrated_jvm':[],'migrated_native':[]}:
            raise ValueError('SEMANTIC_MODEL_CONTEXT_FAILED')
        module,scope=row['context'].rsplit('@',1)
        request=acceptance_context(project,manifest,module,scope)
        if row.get('source_count')!=len(request['sources']) or row.get('published_source_count')!=sum(s['publish'] for s in request['sources']):
            raise ValueError('SEMANTIC_MODEL_CONTEXT_SOURCE_COUNT')
        proofs=row.get('proofs',{})
        if not {'context.json','native-request.json','reference.json','migrated_jvm.json','migrated_native.json'}.issubset({pathlib.Path(p).name for p in proofs}):
            raise ValueError('SEMANTIC_MODEL_CONTEXT_PROOF_MISSING')
        for path,checksum in proofs.items():
            if not pathlib.Path(path).is_file() or digest(pathlib.Path(path))!=checksum:
                raise ValueError('SEMANTIC_MODEL_CONTEXT_PROOF_DRIFT')
        values=[json.loads(pathlib.Path(p).read_text()) for p in proofs if pathlib.Path(p).name in {'reference.json','migrated_jvm.json','migrated_native.json'}]
        if len(values)!=3 or any(set(value)!=categories for value in values) or not values[0]==values[1]==values[2]:
            raise ValueError('SEMANTIC_MODEL_CONTEXT_INVENTORY_INCOMPLETE')
        if any(v.get('encoding')!='sorted-row-sha256-v2-graph-captures' or not isinstance(v.get('rows'),int) or v['rows']<0
            or not isinstance(v.get('sha256'),str) or len(v['sha256'])!=64 for v in values[0].values()):
            raise ValueError('SEMANTIC_MODEL_CONTEXT_INVENTORY_INCOMPLETE')


def domain_accepted(name,report):
    if name=='xml_complete_graph':
        values=report.get('projections',{})
        return (report.get('equal') is True and report.get('schema_gaps')==[] and report.get('unknown_reference_edges')==[]
            and set(values)=={'CONFIGURES','XML_CONTAINS','XML_REFERS_TO','DEFINED_BY','PARENT_BEAN','FACTORY_BEAN','BINDS_TO','WIRES'}
            and all(value.get('equal') is True for value in values.values()))
    if name=='lexical_controls':
        values=report.get('projections',{})
        return set(values)=={'lexical_owners','calls','reads','writes','type_reference','reflection_lookup'} and all(v.get('equal') is True for v in values.values())
    if name=='calls':return all(report.get(key,{}).get('accepted') is True for key in ('declarations','sites','targets','owners'))
    if name=='types_hierarchy_reflection':
        values=report.get('projections',{})
        return set(values)=={'type_reference','reflection_member','extends','implements','overrides'} and all(v.get('equal') is True for v in values.values())
    if name=='annotations_meta':return report.get('equal') is True and report.get('meta',{}).get('equal') is True
    return report.get('equal') is True


def validate_publication(receipt,product,manifest):
    if receipt.get('state')!='finished' or not publication_accepted(receipt):raise ValueError('SEMANTIC_PUBLISHED_NATIVE_REQUIRED')
    if pathlib.Path(receipt.get('published_facts','')).resolve()!=product.resolve():raise ValueError('SEMANTIC_PUBLICATION_DATABASE_MISMATCH')
    if (receipt.get('commit')!='267c7ced4993aa88613f8f17a8099943fec98159'
        or receipt.get('include_uncommitted') is not False
        or receipt.get('verified_committed_files')!=5039 or receipt.get('committed_java_files')!=4217):
        raise ValueError('SEMANTIC_FROZEN_PROJECT_REQUIRED')
    if set(receipt.get('scopes',[]))!={'MAIN','TEST'} or receipt.get('xml_resources_included') is not True:
        raise ValueError('SEMANTIC_ALL_SCOPES_XML_REQUIRED')
    if (receipt.get('unexpected_signal_observed') is not False or receipt.get('changed_artifacts')
        or receipt.get('changed_source_inputs') or receipt.get('inputs_manifest_changed') is not False):
        raise ValueError('SEMANTIC_PUBLICATION_INPUT_DRIFT')
    if set(manifest.get('scopes',[]))!={'MAIN','TEST'} or len(manifest.get('modules',[]))!=40:
        raise ValueError('SEMANTIC_FULL_REACTOR_REQUIRED')
    if any(module.get('selected',True) is not True for module in manifest['modules']):
        raise ValueError('SEMANTIC_SELECTED_REACTOR_NOT_FULL')


def proof_files(runtime,receipt):
    frozen={}
    for group in ('source_inputs','artifacts'):
        values=receipt.get(group,{})
        if not values:raise ValueError('SEMANTIC_PUBLICATION_PROOF_MISSING: '+group)
        for relative,expected in values.items():
            path=runtime/relative
            if not path.resolve().is_relative_to(runtime.resolve()) or digest(path)!=expected:
                raise ValueError('SEMANTIC_RUNTIME_PROOF_DRIFT: '+relative)
            frozen[str(path.resolve())]=expected
    return frozen


def main():
    parser=argparse.ArgumentParser()
    for name in ('product','reference','project','publication_receipt','runtime_root','inputs_manifest','evidence'):
        parser.add_argument('--'+name.replace('_','-'),type=pathlib.Path,required=True)
    parser.add_argument('--reference-jar',type=pathlib.Path,required=True)
    parser.add_argument('--reference-receipt',type=pathlib.Path,required=True)
    parser.add_argument('--model-api-receipt',type=pathlib.Path)
    parser.add_argument('--lombok-model-api-receipt',type=pathlib.Path)
    args=parser.parse_args()
    args.evidence.mkdir(parents=True,exist_ok=False)
    started=time.monotonic()
    report=dict(kind='frozen_project_all_semantic_domains',state='running',domains=[],
                pending_domains=list(PENDING_DOMAINS),full_semantic_diff_accepted=False,capacity_accepted=False)
    output=args.evidence/'receipt.json'
    def save():output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    save()
    try:
        receipt=json.loads(args.publication_receipt.read_text())
        manifest=json.loads(args.inputs_manifest.read_text())
        validate_publication(receipt,args.product,manifest)
        if digest(args.inputs_manifest)!=receipt['inputs_manifest_sha256']:raise ValueError('SEMANTIC_MAVEN_MANIFEST_DRIFT')
        from java_reference_proof import validate_reference
        report['reference_proof']=validate_reference(args.reference_jar,args.reference,args.reference_receipt,args.inputs_manifest,args.project)
        acceptance.ROOT=args.runtime_root.resolve()
        report['frozen_project']=acceptance.verify_frozen(args.project.resolve())
        report['runtime_proof']=proof_files(args.runtime_root,receipt)
        report['input_sha256']={str(p.resolve()):digest(p) for p in
            (args.product,args.reference,args.reference_jar,args.reference_receipt,args.publication_receipt,args.inputs_manifest)}
        if args.model_api_receipt:
            validate_model_inventory(json.loads(args.model_api_receipt.read_text()),receipt,manifest,args.project.resolve())
            report['input_sha256'][str(args.model_api_receipt.resolve())]=digest(args.model_api_receipt)
            report['pending_domains'].remove('complete_model_api_inventory')
            report['complete_model_api_inventory_receipt']=str(args.model_api_receipt.resolve())
        if args.lombok_model_api_receipt:
            validate_model_inventory(json.loads(args.lombok_model_api_receipt.read_text()),receipt,manifest,args.project.resolve(),mode='lombok_ast')
            report['input_sha256'][str(args.lombok_model_api_receipt.resolve())]=digest(args.lombok_model_api_receipt)
            report['pending_domains'].remove('complete_lombok_ast_model_inventory')
            report['complete_lombok_ast_model_inventory_receipt']=str(args.lombok_model_api_receipt.resolve())
        report['auditor_sha256']={str(file.relative_to(ROOT/'tests')):digest(file)
            for file in sorted((ROOT/'tests').glob('*.py'))}
        with connection(args.product) as db:
            identity=dict(db.execute('SELECT key,value FROM identity'))
            if identity.get('revision')!=receipt['published_revision']:raise ValueError('SEMANTIC_REVISION_IDENTITY_DRIFT')
            report['revision']=identity['revision'];report['semantic_profile']=identity['profile']
        for name,file in DOMAINS:
            domain_output=args.evidence/(name+'.json');log=args.evidence/(name+'.log')
            command=[sys.executable,str(ROOT/'tests'/file),'--product',str(args.product),
                     '--reference',str(args.reference),'--project',str(args.project),'--output',str(domain_output)]
            tick=time.monotonic()
            with log.open('wb') as stream:
                process=subprocess.run(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,
                    env=dict(os.environ,ANATOMIST_REFERENCE_JAR=str(args.reference_jar.resolve())))
            value=json.loads(domain_output.read_text()) if domain_output.exists() else {}
            ok=process.returncode==0 and domain_accepted(name,value)
            report['domains'].append(dict(name=name,accepted=ok,exit_code=process.returncode,
                elapsed_seconds=round(time.monotonic()-tick,3),output=str(domain_output),
                output_sha256=digest(domain_output) if value else None,log=str(log),log_sha256=digest(log)))
            save();print(name+': '+('passed' if ok else 'failed'),flush=True)
        report['available_domains_accepted']=all(value['accepted'] for value in report['domains'])
        changed=[path for path,expected in report['input_sha256'].items() if digest(pathlib.Path(path))!=expected]
        changed.extend(path for path,expected in report['runtime_proof'].items() if digest(pathlib.Path(path))!=expected)
        changed.extend(str(ROOT/'tests'/file) for file,expected in report['auditor_sha256'].items() if digest(ROOT/'tests'/file)!=expected)
        if changed:raise ValueError('SEMANTIC_COMPARISON_INPUT_DRIFT: '+str(changed))
        report['changed_inputs']=[]
        report['full_semantic_diff_accepted']=report['available_domains_accepted'] and not report['pending_domains']
    except Exception as error:
        report.update(failure=str(error),failure_type=type(error).__name__)
    finally:
        report.update(state='finished',elapsed_seconds=round(time.monotonic()-started,3));save()
    return 0 if report['full_semantic_diff_accepted'] else 1


if __name__=='__main__':raise SystemExit(main())
