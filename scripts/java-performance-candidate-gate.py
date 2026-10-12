#!/usr/bin/env python3
"""Bind complete candidate fixture results to one release build and frozen source."""
import argparse
import ast
import importlib.util
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from acceptance_support import sha256

GROUPS={'native_product':('native','product','java_e2e','JavaProduct'),
        'native_probe':('native','probe','java_anatomist_native','Native'),
        'native_lifecycle':('native','lifecycle','java_native_lifecycle','NativeLifecycle'),
        'jvm_product':('jvm','product','java_e2e','JavaProduct'),
        'jvm_probe':('jvm','probe','java_anatomist_native','Native')}
SPECIAL={'native_unchanged','jvm_unchanged'}
EMPTY=('missing','duplicate','unexpected','expected_duplicate','artifact_drift')


def require(value,code):
    if not value:raise ValueError(code)


def verify_inputs(inputs):
    require(isinstance(inputs,dict) and bool(inputs),'NONEMPTY_PROOFS_REQUIRED')
    for name,digest in inputs.items():
        p=Path(name);require(p.is_absolute() and p.is_file() and sha256(p)==digest,'PROOF_DRIFT: '+name)
    return inputs


def case_ids(source,module,classname):
    tree=ast.parse((source/'tests'/(module+'.py')).read_text())
    cls=next(node for node in tree.body if isinstance(node,ast.ClassDef) and node.name==classname)
    return {module+'.'+classname+'.'+node.name for node in cls.body
            if isinstance(node,ast.FunctionDef) and node.name.startswith('test_')}


def validate_cases(report,expected):
    rows=report.get('cases',[])
    require(report.get('status')=='passed' and all(report.get(k)==[] for k in EMPTY),'CASE_FAILURE_OR_OMISSION')
    require(report.get('expected_count')==report.get('executed_count')==len(expected)
            and len(rows)==len(expected) and {r['id'] for r in rows}==expected
            and all(r.get('status')=='passed' for r in rows),'COMPLETE_CASE_SET_REQUIRED')


def validate(spec,manifest_path,build_path):
    inputs={str(manifest_path):sha256(manifest_path),str(build_path):sha256(build_path),str(Path(__file__).resolve()):sha256(__file__)}
    source=manifest_path.parent;manifest=json.loads(manifest_path.read_text());build=json.loads(build_path.read_text())
    require(build.get('status')=='passed' and build.get('build_profile')=='release'
            and build.get('source_manifest_sha256')==sha256(manifest_path),'CURRENT_RELEASE_BUILD_REQUIRED')
    verify_inputs(build['binaries']);inputs.update(build['binaries'])
    for relative,digest in manifest['files'].items():
        p=(source/relative).resolve();require(p.is_relative_to(source.resolve()) and sha256(p)==digest,'SOURCE_DRIFT');inputs[str(p)]=digest
    required=set(GROUPS)|SPECIAL|{'rust_cli','rust_debug_build','original_jvm','original_native','native_variant'}
    require(set(spec['receipts'])==required,'COMPLETE_CANDIDATE_RECEIPTS_REQUIRED')
    reports={}
    for name,filename in spec['receipts'].items():
        path=Path(filename).resolve();inputs[str(path)]=sha256(path);reports[name]=json.loads(path.read_text())
    for name in set(GROUPS)|SPECIAL:
        report=reports[name]
        if name in GROUPS:
            backend,group,module,cls=GROUPS[name]
            expected=case_ids(source,module,cls)
            require(report.get('group')==group and report.get('complete_group_gate') is True,'COMPLETE_GROUP_REQUIRED: '+name)
        else:
            backend=name.split('_')[0]
            expected={'java_e2e.JavaProduct.test_unchanged_manifest_preserves_warm_worker_without_java_analysis'}
        require(report.get('backend')==backend,'BACKEND_MISMATCH: '+name)
        validate_cases(report,expected)
        proofs=verify_inputs(report.get('sources_and_artifacts'));inputs.update(proofs)
        require(proofs.get(str(manifest_path))==sha256(manifest_path)
                and proofs.get(str(build_path))==sha256(build_path),'FIXTURE_BUILD_BINDING_REQUIRED: '+name)
        for binary,digest in build['binaries'].items():
            if Path(binary).name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
                require(proofs.get(binary)==digest,'FIXTURE_BINARY_BINDING_REQUIRED: '+name)
    debug=reports['rust_debug_build'];require(debug.get('status')=='passed' and debug.get('build_profile')=='debug'
        and debug.get('source_manifest_sha256')==sha256(manifest_path),'CLI_CURRENT_DEBUG_BUILD_REQUIRED')
    cli=reports['rust_cli'];validate_cases(cli,case_ids(source,'rust_e2e','Product'))
    proofs=verify_inputs(cli.get('inputs'));inputs.update(proofs)
    # CLI fixture exercises mock and files-only production mode; native worker
    # correctness is supplied by the release Java groups above.
    for name in ('codeana-cli','codeana-daemon','codeana-mock-worker'):
        expected={d for p,d in debug['binaries'].items() if Path(p).name==name}
        recorded={d for p,d in proofs.items() if Path(p).name==name}
        require(len(expected)==1 and recorded==expected,'CLI_BINARY_DRIFT: '+name)
    require(any(p.endswith('/tests/rust_e2e.py') and d==sha256(source/'tests/rust_e2e.py') for p,d in proofs.items()),'CLI_CASE_SOURCE_DRIFT')
    tool=ROOT/'scripts/java-performance-single-v2.py'
    loader=importlib.util.spec_from_file_location('runner',tool);runner=importlib.util.module_from_spec(loader);loader.loader.exec_module(runner)
    archive=Path(spec['original_source_root']).resolve()
    for name,flag in [('original_jvm','jvm_migration_accepted'),('original_native','native_accepted')]:
        path=Path(spec['receipts'][name]).resolve();runner.accepted(path,flag,archive)
        for filename,digest in reports[name]['inputs'].items():
            p=Path(filename)
            if p.is_relative_to(ROOT) and (archive/p.relative_to(ROOT)).is_file():p=archive/p.relative_to(ROOT)
            inputs[str(p)]=digest
    variant=reports['native_variant'];require(variant.get('status')=='passed' and variant.get('changed_inputs')==[]
        and variant.get('heap_limit_changed') is False and variant.get('worker_budget_changed') is False,'NATIVE_VARIANT_DRIFT')
    inputs.update(verify_inputs(variant['inputs']))
    require(any(p.endswith('/native-bridge-0.1.0.jar') and d in reports['original_native']['inputs'].values()
                for p,d in variant['inputs'].items()),'ACCEPTED_NATIVE_BYTECODE_REQUIRED')
    installed={p:d for name in set(GROUPS)|SPECIAL for p,d in reports[name]['sources_and_artifacts'].items() if p.endswith(('.so','.dylib'))}
    require(set(installed.values())=={variant['library_sha256']},'MIXED_NATIVE_VARIANT')
    verify_inputs(inputs)
    return dict(status='passed',candidate_correctness_accepted=True,build_profile='release',
        source_manifest_sha256=sha256(manifest_path),build_receipt_sha256=sha256(build_path),
        unexplained_failures=[],inputs=inputs,scope='source-bound release fixture groups; unchanged Java full gates and exact Native bytecode lineage',
        native_accepted=False,performance_accepted=False)


def main():
    p=argparse.ArgumentParser()
    for name in ('spec','build-manifest','build-receipt','evidence'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();a.evidence.mkdir(parents=True,exist_ok=False)
    try:
        result=validate(json.loads(a.spec.read_text()),a.build_manifest.resolve(),a.build_receipt.resolve())
        result['inputs'][str(a.spec.resolve())]=sha256(a.spec)
    except Exception as error:
        result=dict(status='failed',candidate_correctness_accepted=False,failure=str(error),performance_accepted=False)
    (a.evidence/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='inputs'}))
    return 0 if result['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
