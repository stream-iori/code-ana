#!/usr/bin/env python3
"""Compare every immutable Fact IR body across actually published backends."""
import argparse
import importlib.util
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_real_acceptance import atomic_json
spec = importlib.util.spec_from_file_location('history_graph', ROOT / 'scripts/java-jvm-committed-history.py')
history = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history)


def publications(receipts):
    values = {backend: json.loads(path.read_text()) for backend, path in receipts.items()}
    for backend, value in values.items():
        if (value.get('backend') != backend or value.get('publication_accepted') is not True
                or value.get('state') != 'finished'
                or value.get('unexpected_signal_observed') is not False
                or value.get('changed_artifacts') != []
                or value.get('changed_source_inputs') != []
                or not value.get('inputs_manifest_sha256')):
            raise ValueError('ACCEPTED_BACKEND_PUBLICATION_REQUIRED: ' + backend)
        if value.get('inputs_manifest_sha256') != values['jvm'].get('inputs_manifest_sha256'):
            raise ValueError('BACKEND_MANIFEST_IDENTITY_MISMATCH')
    return values


def main():
    parser = argparse.ArgumentParser()
    for name in ['jvm-publication', 'native-publication', 'output']:
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('NEW_EQUIVALENCE_RECEIPT_REQUIRED')
    receipts = dict(jvm=args.jvm_publication.resolve(), native=args.native_publication.resolve())
    values = publications(receipts)
    databases = {backend: pathlib.Path(value['published_facts']) for backend, value in values.items()}
    inputs = {str(path): sha256(path) for path in [*receipts.values(), *databases.values(), pathlib.Path(__file__).resolve(), ROOT / 'scripts/java-jvm-committed-history.py']}
    report = dict(status='running', kind='whole_fact_ir_backend_equivalence', inputs=inputs,
                  whole_fact_ir_equal=False, native_accepted=False, graphs={})
    atomic_json(args.output, report)
    try:
        for backend, database in databases.items():
            report['graphs'][backend] = history.graph(database)
            atomic_json(args.output, report)
        report['changed_inputs'] = [path for path, digest in inputs.items() if sha256(path) != digest]
        report['whole_fact_ir_equal'] = (report['graphs']['jvm']['count'] > 0
                                        and report['graphs']['jvm'] == report['graphs']['native'])
        report['status'] = 'passed' if report['whole_fact_ir_equal'] and not report['changed_inputs'] else 'failed'
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    atomic_json(args.output, report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
