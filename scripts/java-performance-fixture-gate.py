#!/usr/bin/env python3
"""Run Java correctness fixture groups against source-bound Rust binaries."""
import argparse
import importlib.util
import json
import os
import pathlib
import sys
import tempfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from acceptance_support import sha256
import java_product_runtime
import java_jvm_product_gate


def main():
    parser=argparse.ArgumentParser()
    for name in ('release-binaries','bundle-root','build-manifest','build-receipt','evidence'):
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    parser.add_argument('--backend',choices=('jvm','native'),required=True)
    parser.add_argument('--group',choices=('probe','product','lifecycle','unchanged'),required=True)
    parser.add_argument('--build-profile',choices=('debug','release'),default='release')
    parser.add_argument('--case',action='append',default=[])
    args=parser.parse_args()
    build=json.loads(args.build_receipt.read_text())
    if build.get('status')!='passed' or build.get('build_profile')!=args.build_profile or build['source_manifest_sha256']!=sha256(args.build_manifest):
        raise ValueError('PROVEN_BUILD_REQUIRED')
    for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
        binary=(args.release_binaries/name).resolve()
        if build['binaries'].get(str(binary))!=sha256(binary): raise ValueError('RELEASE_BINARY_DRIFT')
    manifest=json.loads(args.build_manifest.read_text())
    for relative,digest in manifest['files'].items():
        source=(args.build_manifest.parent/relative).resolve()
        if not source.is_relative_to(args.build_manifest.parent.resolve()) or sha256(source)!=digest: raise ValueError('RELEASE_SOURCE_DRIFT')
    spec=importlib.util.spec_from_file_location('stable_runtime',ROOT/'scripts/java-performance-stable-runtime.py')
    stable=importlib.util.module_from_spec(spec);spec.loader.exec_module(stable)
    original_prepare=stable.prepare
    original_artifacts=java_jvm_product_gate.artifact_paths
    stable_inputs=[]
    proofs=[pathlib.Path(__file__),ROOT/'scripts/java-performance-stable-runtime.py',args.build_receipt,args.build_manifest]+[(args.release_binaries/name).resolve() for name in ('codeana-cli','codeana-daemon','codeana-java-worker')]+[args.build_manifest.parent/name for name in manifest['files']]
    with tempfile.TemporaryDirectory(prefix='release-fixture-') as directory:
        facade=pathlib.Path(directory)
        (facade/'target').mkdir()
        (facade/'target/debug').symlink_to(args.release_binaries.resolve(),target_is_directory=True)
        (facade/'lang-java-adapotr').symlink_to(args.bundle_root.resolve()/'lang-java-adapotr',target_is_directory=True)
        def prepare(root,destination,backend):
            result=original_prepare(facade,destination,backend)
            if args.group=='unchanged':
                import java_e2e, rust_e2e
                java_e2e.BIN=pathlib.Path(result['binary_directory']); rust_e2e.BIN=java_e2e.BIN
            stable_inputs.extend(pathlib.Path(p) for p in result['installed_files'])
            (destination.parent/(args.build_profile+'-build.json')).write_text(json.dumps(build,indent=2)+'\n')
            return result
        java_product_runtime.prepare=prepare
        java_jvm_product_gate.artifact_paths=lambda output:original_artifacts(output)+proofs+stable_inputs
        group=args.group
        selected=[value for case in args.case for value in ('--case',case)]
        if group=='unchanged':
            # Set runtime paths before importing the existing fixture module (its BIN is static).
            os.environ['CODEANA_TEST_BIN']=str(args.release_binaries.resolve())
            os.environ['CODEANA_PERFORMANCE_TIMINGS_DIR']=str(args.evidence.resolve()/'unchanged-timings')
            from java_performance_unchanged import test_unchanged_manifest_preserves_warm_worker_without_java_analysis
            import java_e2e
            java_e2e.JavaProduct.test_unchanged_manifest_preserves_warm_worker_without_java_analysis=test_unchanged_manifest_preserves_warm_worker_without_java_analysis
            group='product'
            selected=['--case','test_unchanged_manifest_preserves_warm_worker_without_java_analysis']
        sys.argv=[str(ROOT/'tests/java_jvm_product_gate.py'),'--backend',args.backend,'--group',group,'--evidence',str(args.evidence)]+selected
        return java_jvm_product_gate.main()


if __name__=='__main__':raise SystemExit(main())
