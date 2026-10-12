#!/usr/bin/env python3
"""Fresh-reference published graph comparisons for an explicitly selected backend."""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_fresh_reference import reference
from java_real_acceptance import atomic_json, verify_frozen
from java_real_semantic_diff import DOMAINS, domain_accepted
import java_reference_proof


def checked_publication(receipt, runtime, manifest, backend='jvm'):
    if (backend not in ('jvm', 'native') or receipt.get('backend') != backend or receipt.get('state') != 'finished'
            or receipt.get('publication_accepted') is not True
            or receipt.get('unexpected_signal_observed') is not False
            or receipt.get('changed_artifacts') != []
            or receipt.get('changed_source_inputs') != []
            or receipt.get('inputs_manifest_changed') is not False
            or receipt.get('config_restored') is not True):
        raise ValueError('CURRENT_' + backend.upper() + '_PUBLICATION_REQUIRED')
    if sha256(manifest) != receipt['inputs_manifest_sha256']:
        raise ValueError('PUBLICATION_MANIFEST_DRIFT')
    proofs = {str(runtime / p): h for p, h in receipt['artifacts'].items()}
    if not proofs or any(sha256(p) != h for p, h in proofs.items()):
        raise ValueError('PUBLICATION_RUNTIME_DRIFT')
    return proofs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', choices=('jvm', 'native'), default='jvm')
    for name in ('publication-receipt', 'reference-receipt', 'reference-database',
                 'inputs-manifest', 'freeze-receipt', 'project', 'runtime', 'evidence'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = dict(kind='fresh_reference_' + args.backend + '_published_graph_domains',
                  backend=args.backend, status='running',
                  domains=[], available_domains_accepted=False,
                  complete_model_gate=False, full_semantic_diff_accepted=False,
                  public_queries_accepted=False, native_accepted=False)
    receipt_path = args.evidence / 'receipt.json'
    atomic_json(receipt_path, report)
    started = time.monotonic()
    try:
        publication = json.loads(args.publication_receipt.read_text())
        oracle, oracle_proof = reference()
        # User waived the old JAR acceptance. The independent Git build remains
        # mandatory; the shared validator uses only this verified new identity.
        java_reference_proof.REFERENCE_SHA = oracle_proof['jar_sha256']
        report['reference_proof'] = java_reference_proof.validate_reference(
            oracle, args.reference_database, args.reference_receipt,
            args.inputs_manifest, args.project)
        report['frozen_project'] = verify_frozen(args.project, args.freeze_receipt)
        proofs = checked_publication(publication, args.runtime, args.inputs_manifest, args.backend)
        product = pathlib.Path(publication['published_facts'])
        for p in [product, args.reference_database, args.reference_receipt,
                  args.publication_receipt, args.inputs_manifest, args.freeze_receipt,
                  oracle, pathlib.Path(__file__)]:
            proofs[str(p.resolve())] = sha256(p)
        proofs.update({str(p): sha256(p) for p in (ROOT / 'tests').glob('*.py')})
        report['inputs'] = proofs
        report['published_revision'] = publication['published_revision']
        atomic_json(receipt_path, report)
        for name, script in DOMAINS:
            output = args.evidence / (name + '.json')
            log = args.evidence / (name + '.log')
            command = [sys.executable, str(ROOT / 'tests' / script),
                       '--product', str(product), '--reference', str(args.reference_database),
                       '--project', str(args.project), '--output', str(output)]
            if name == 'calls':
                command += ['--product-kind', 'jvm' if args.backend == 'jvm' else 'published_native']
            tick = time.monotonic()
            with log.open('wb') as stream:
                execution = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                    timeout=1800, env=dict(os.environ, ANATOMIST_REFERENCE_JAR=str(oracle)))
            value = json.loads(output.read_text()) if output.exists() else {}
            row = dict(name=name, status='passed' if execution.returncode == 0
                       and domain_accepted(name, value) else 'failed',
                       command=command, exit_code=execution.returncode,
                       seconds=round(time.monotonic() - tick, 3),
                       output=str(output), output_sha256=sha256(output) if output.exists() else None,
                       log=str(log), log_sha256=sha256(log))
            report['domains'].append(row)
            atomic_json(receipt_path, report)
            print(name + ': ' + row['status'], flush=True)
        report['changed_inputs'] = [p for p, h in proofs.items() if sha256(p) != h]
        report['available_domains_accepted'] = len(report['domains']) == len(DOMAINS) \
            and all(r['status'] == 'passed' for r in report['domains']) \
            and not report['changed_inputs']
        report['status'] = 'passed' if report['available_domains_accepted'] else 'failed'
    except BaseException as failure:
        report.update(status='interrupted' if isinstance(failure, (KeyboardInterrupt, SystemExit))
                      else 'failed', failure=str(failure), failure_type=type(failure).__name__)
        raise
    finally:
        report['seconds'] = round(time.monotonic() - started, 3)
        atomic_json(receipt_path, report)
    return 0 if report['available_domains_accepted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
