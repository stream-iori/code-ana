#!/usr/bin/env python3
"""Read-only layered progress report. Strict gate results remain unchanged."""
import argparse
import hashlib
import json
import pathlib
from java_anatomist_project_models import model_result_summary, verified_resume_rows


def digest(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def model_progress(report):
    expected = {(mode, context) for mode in ('raw', 'ast') for context in report['expected_contexts']}
    seen = set(); rows = []
    verified_resume_rows(report, report)  # Verify all passes; failures never become resume passes.
    for row in report.get('contexts', []):
        identity = (row['mode'], row['context'])
        if identity not in expected or identity in seen:
            raise ValueError('LAYERED_MODEL_CONTEXT_IDENTITY')
        seen.add(identity)
        results = {}
        for path, checksum in row.get('proofs', {}).items():
            if digest(path) != checksum:
                raise ValueError('LAYERED_MODEL_PROOF_DRIFT')
            name = pathlib.Path(path).stem
            if pathlib.Path(path).suffix == '.json' and name in ('reference', 'migrated_jvm', 'migrated_native'):
                results[name] = json.loads(pathlib.Path(path).read_text())
        evidence = model_result_summary(results)
        limit = False
        log = pathlib.Path(row['evidence']) / 'reference.log'
        if row.get('failed_stage') == 'reference' and log.is_file():
            limit = 'JAVAC_DECODE_BUDGET_EXCEEDED' in log.read_text(errors='replace')
        rows.append(dict(mode=row['mode'], context=row['context'], strict_accepted=row.get('accepted') is True,
            published_source_count=row.get('published_source_count'), source_count=row.get('source_count'),
            reference_decode_limit=limit, reference_log_sha256=digest(log) if log.is_file() else None,
            **evidence))
    return dict(state=report['state'], expected=len(expected), collected=len(rows),
        strict_passes=sum(row['strict_accepted'] for row in rows),
        raw_model_passes=sum(row.get('raw_models_equal', False) for row in rows),
        empty_publication_contexts=sum(row.get('published_source_count') == 0 for row in rows),
        reference_decode_limits=sum(row['reference_decode_limit'] for row in rows),
        remaining=len(expected - seen), contexts=rows,
        complete_model_api_inventory=report.get('complete_model_api_inventory') is True)


def semantic_progress(report):
    domains=[]; seen=set()
    for domain in report['domains']:
        if domain['name'] in seen: raise ValueError('LAYERED_SEMANTIC_DOMAIN_DUPLICATE')
        seen.add(domain['name'])
        for field in ('output', 'log'):
            if digest(domain[field]) != domain[field + '_sha256']:
                raise ValueError('LAYERED_SEMANTIC_PROOF_DRIFT')
        domains.append(dict(name=domain['name'], strict_accepted=domain['accepted'], output=domain['output']))
    return dict(state=report['state'], passed=sum(d['strict_accepted'] for d in domains),
        total=len(domains), domains=domains, pending=report.get('pending_domains', []))


def main():
    parser=argparse.ArgumentParser()
    for name in ('publication_receipt','semantic_receipt','model_receipt','output'):
        parser.add_argument('--'+name.replace('_','-'), type=pathlib.Path, required=True)
    parser.add_argument('--runtime-root',type=pathlib.Path,required=True)
    args=parser.parse_args()
    inputs=[args.publication_receipt,args.semantic_receipt,args.model_receipt]
    frozen={str(path.resolve()):digest(path) for path in inputs}
    publication,semantic,models=[json.loads(path.read_text()) for path in inputs]
    if models.get('source_inputs') != publication.get('source_inputs'):
        raise ValueError('LAYERED_SOURCE_VERSION_MISMATCH')
    if models.get('native_shared_library_sha256') != publication['artifacts']['target/lib/java/native/libcodeana_java.dylib']:
        raise ValueError('LAYERED_NATIVE_VERSION_MISMATCH')
    if models.get('compiler_bridge_sha256') != publication['artifacts']['target/lib/java/compiler-bridge.jar']:
        raise ValueError('LAYERED_COMPILER_VERSION_MISMATCH')
    if semantic.get('input_sha256',{}).get(str(args.publication_receipt.resolve())) != digest(args.publication_receipt):
        raise ValueError('LAYERED_PUBLICATION_PROOF_MISMATCH')
    runtime={str((args.runtime_root / name).resolve()):sha for group in ('source_inputs','artifacts')
             for name,sha in publication[group].items()}
    if semantic.get('runtime_proof') != runtime or any(digest(path)!=sha for path,sha in runtime.items()):
        raise ValueError('LAYERED_RUNTIME_PROOF_MISMATCH')
    reference=semantic['reference_proof']
    if reference['reference_jar_sha256']!=models['reference_sha256'] or reference['inputs_manifest_sha256']!=publication['inputs_manifest_sha256'] or models['inputs_manifest_sha256']!=publication['inputs_manifest_sha256']:
        raise ValueError('LAYERED_REFERENCE_OR_MANIFEST_MISMATCH')
    report=dict(kind='layered_java_migration_progress', publication_accepted=publication.get('publication_accepted') is True,
        business_semantic_index_accepted=publication.get('semantic_index_accepted') is True,
        semantics=semantic_progress(semantic), models=model_progress(models), inputs=frozen,
        migration_delivery_accepted=False, comparable_range_accepted=False,
        full_semantic_diff_accepted=semantic.get('full_semantic_diff_accepted') is True,
        capacity_accepted=False, performance_accepted=False,
        note='Progress only: unresolved differences and missing incremental/performance proofs prevent delivery acceptance.')
    if any(digest(path)!=sha for path,sha in frozen.items()):
        raise ValueError('LAYERED_RECEIPT_CHANGED_DURING_READ')
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('publication_accepted','migration_delivery_accepted','comparable_range_accepted','full_semantic_diff_accepted')}))
    return 0

if __name__=='__main__':raise SystemExit(main())
