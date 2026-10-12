#!/usr/bin/env python3
"""Compare every frozen reactor context against the freshly verified JVM oracle.

This observes pre-projection models. Publication and public-query receipts remain
separate. Completed cases can be resumed only while every input and proof matches.
"""
import argparse
import json
import os
import pathlib
import signal
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_anatomist_project_models import reactor_contexts, configuration_inputs
from java_fresh_reference import reference
from java_decode_budget_reference import prepare_reference_budget, validate_budget
from java_real_acceptance import atomic_json, verify_frozen
from java_anatomist_reference import HOST, JDK

CATEGORIES = {
    'raw.syntax', 'raw.bindings', 'raw.types', 'raw.declarations',
    'raw.extensions', 'raw.source_hash', 'raw.diagnostics', 'facts.nodes',
    'facts.edges', 'facts.annotations', 'facts.annotationMetaRelations',
    'facts.semanticAnnotations', 'facts.declarations', 'facts.unresolvedCallSites',
}


def verified_categories(value):
    if set(value) != CATEGORIES:
        raise ValueError('PROJECT_MODEL_CATEGORY_COVERAGE')
    for row in value.values():
        if (row.get('encoding') != 'sorted-row-sha256-v2-graph-captures'
                or type(row.get('rows')) is not int or row['rows'] < 0
                or not isinstance(row.get('sha256'), str)
                or len(row['sha256']) != 64):
            raise ValueError('PROJECT_MODEL_CATEGORY_IDENTITY')
    return value


def resumed_cases(previous, identity):
    if previous.get('identity') != identity:
        raise ValueError('PROJECT_MODEL_RESUME_INPUT_DRIFT')
    rows, seen = [], set()
    for row in previous.get('cases', []):
        case = row['id']
        if case not in identity['expected'] or case in seen:
            raise ValueError('PROJECT_MODEL_RESUME_CASE_ID')
        seen.add(case)
        if row.get('status') != 'passed':
            continue
        proofs = row.get('proofs', {})
        if not proofs or any(sha256(p) != digest for p, digest in proofs.items()):
            raise ValueError('PROJECT_MODEL_RESUME_PROOF_DRIFT')
        results = row.get('results', {})
        if set(results) != {'reference', 'migrated'}:
            raise ValueError('PROJECT_MODEL_RESUME_BACKENDS')
        for label in ('reference', 'migrated'):
            files = [pathlib.Path(p) for p in proofs if pathlib.Path(p).name == label + '.json']
            if len(files) != 1 or json.loads(files[0].read_text()) != results[label]:
                raise ValueError('PROJECT_MODEL_RESUME_RESULT_EVIDENCE')
        requests = [pathlib.Path(p) for p in proofs if pathlib.Path(p).name == 'context.json']
        if len(requests) != 1:
            raise ValueError('PROJECT_MODEL_RESUME_CAPTURE_EVIDENCE')
        consumer = json.loads(requests[0].read_text())['contexts'][0]
        if (case.split(':', 1)[1] != consumer['module'] + '@' + consumer['scope']
                or row.get('source_count') != len(consumer['sources'])
                or row.get('published_source_count') != sum(s['publish'] for s in consumer['sources'])):
            raise ValueError('PROJECT_MODEL_RESUME_SOURCE_COVERAGE')
        if verified_categories(results['reference']) != verified_categories(results['migrated']):
            raise ValueError('PROJECT_MODEL_RESUME_DIFFERENCE')
        rows.append(row)
    return rows


def execute(command, directory, label, timeout, env):
    started = time.monotonic()
    process = None
    try:
        with (directory / (label + '.stdout')).open('wb') as output, \
                (directory / (label + '.stderr')).open('wb') as error:
            process = subprocess.Popen(command, stdout=output, stderr=error,
                                       env=env, start_new_session=True)
            try:
                code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                return dict(status='failed', reason='PROJECT_MODEL_TIMEOUT',
                            pid=process.pid, timeout_seconds=timeout)
        return dict(status='passed' if code == 0 else 'failed', exit_code=code,
                    pid=process.pid, unexpected_signal=-code if code < 0 else None,
                    seconds=round(time.monotonic() - started, 3))
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)


def selected_cases(expected, context, excluded):
    selected = [case for case in expected if (not context or case.split(':', 1)[1] == context)
                and case.split(':', 1)[1].rsplit('@', 1)[0] not in excluded]
    if not selected:
        raise ValueError('PROJECT_MODEL_EMPTY_SELECTION')
    return selected


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=pathlib.Path, required=True)
    parser.add_argument('--inputs-manifest', type=pathlib.Path, required=True)
    parser.add_argument('--freeze-receipt', type=pathlib.Path, required=True)
    parser.add_argument('--bundle', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--context', help='Diagnostic subset; cannot pass the complete gate')
    parser.add_argument('--exclude-module', action='append', default=[], help='Defer a module; complete gate remains false')
    parser.add_argument('--decode-budget-bytes', type=int, default=512 << 20)
    parser.add_argument('--witness', type=pathlib.Path)
    parser.add_argument('--timeout-seconds', type=int, default=1800)
    parser.add_argument('--keep-going', action='store_true')
    args = parser.parse_args()
    project, manifest_path = args.project.resolve(), args.inputs_manifest.resolve()
    frozen = verify_frozen(project, args.freeze_receipt)
    manifest = json.loads(manifest_path.read_text())
    contexts = [(m['id'], scope) for m in manifest['modules']
                if m.get('selected', True) for scope in ('MAIN', 'TEST')]
    if len(manifest['modules']) != 40 or len(contexts) != 80:
        raise ValueError('PROJECT_MODEL_FULL_REACTOR_REQUIRED')
    if args.context and args.context not in [m + '@' + s for m, s in contexts]:
        raise ValueError('PROJECT_MODEL_UNKNOWN_CONTEXT')
    validate_budget(args.decode_budget_bytes)
    if not set(args.exclude_module) <= {m['id'] for m in manifest['modules']}:
        raise ValueError('PROJECT_MODEL_UNKNOWN_EXCLUDED_MODULE')
    oracle, oracle_proof = reference()
    bundle = args.bundle.resolve()
    runner = bundle / 'runner.jar'
    bundle_manifest = json.loads((bundle / 'manifest.json').read_text())
    if (bundle_manifest.get('backend') != 'jvm'
            or sha256(runner) != bundle_manifest['files']['runner.jar']):
        raise ValueError('PROJECT_MODEL_CURRENT_JVM_BUNDLE_REQUIRED')
    witness = args.witness.resolve() if args.witness else ROOT / 'tests/java-migration/ParityWitness.java'
    expected = [mode + ':' + m + '@' + s for mode in ('raw', 'ast') for m, s in contexts]
    selected = selected_cases(expected, args.context, set(args.exclude_module))
    inputs = [pathlib.Path(__file__).with_name('java_decode_budget_reference.py'), oracle, runner, bundle / 'manifest.json', witness, manifest_path,
              args.freeze_receipt.resolve(), pathlib.Path(__file__).resolve(),
              HOST / 'bin/java', HOST / 'bin/javac', HOST / 'release',
              JDK / 'bin/java', JDK / 'release', JDK / 'lib/tools.jar']
    identity = dict(frozen=frozen, oracle=oracle_proof['jar_sha256'],
                    inputs={str(p): sha256(p) for p in inputs},
                    committed_sources=json.loads(args.freeze_receipt.read_text())['files'],
                    configuration_inputs=configuration_inputs(project),
                    expected=expected, selected=selected, excluded_modules=sorted(set(args.exclude_module)),
                    decode_budget_bytes=args.decode_budget_bytes, context=args.context,
                    timeout_seconds=args.timeout_seconds)
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=args.resume)
    receipt = evidence / 'receipt.json'
    previous = json.loads(receipt.read_text()) if args.resume else {}
    if args.resume:
        atomic_json(evidence / ('receipt-before-resume-' + str(time.time_ns()) + '.json'), previous)
    if args.resume:
        budget_policy = previous['reference_budget_policy']
        for p, digest in budget_policy['classes'].items():
            if sha256(p) != digest:
                raise ValueError('PROJECT_MODEL_REFERENCE_POLICY_DRIFT')
    else:
        budget_policy = prepare_reference_budget(oracle, oracle_proof, HOST, evidence / 'reference-budget', args.decode_budget_bytes)
    identity['inputs'].update({p: sha256(p) for p in [budget_policy['adapted_source'],
        budget_policy['manifest_path'], *budget_policy['classes']]})
    identity['reference_budget_policy'] = budget_policy
    report = dict(reference_budget_policy=budget_policy, deferred_cases=[c for c in expected if c not in selected],
                  subset_accepted=False, kind='fresh_oracle_frozen_project_jvm_models', status='running',
                  identity=identity, cases=resumed_cases(previous, identity) if args.resume else [],
                  complete_model_gate=False, native_accepted=False,
                  publication_accepted=False, public_queries_accepted=False)
    atomic_json(receipt, report)
    classes = evidence / 'classes'
    if not args.resume:
        classes.mkdir()
        subprocess.run([str(HOST / 'bin/javac'), '-cp', str(runner), '-d', str(classes),
                        str(witness)], check=True, capture_output=True)
        atomic_json(evidence / 'witness-classes.json',
                    {str(p): sha256(p) for p in classes.rglob('*.class')})
    for p, digest in json.loads((evidence / 'witness-classes.json').read_text()).items():
        if sha256(p) != digest:
            raise ValueError('PROJECT_MODEL_WITNESS_CLASS_DRIFT')
    class_manifest_sha = sha256(evidence / 'witness-classes.json')
    if args.resume and previous.get('witness_classes_manifest_sha256') != class_manifest_sha:
        raise ValueError('PROJECT_MODEL_WITNESS_MANIFEST_DRIFT')
    report['witness_classes_manifest_sha256'] = class_manifest_sha
    atomic_json(receipt, report)
    started = time.monotonic()
    try:
        for mode in ('raw', 'ast'):
            for index, (module, scope) in enumerate(contexts):
                case_id = mode + ':' + module + '@' + scope
                if case_id not in selected:
                    continue
                if any(row['id'] == case_id and row['status'] == 'passed' for row in report['cases']):
                    continue
                directory = evidence / (mode + '-' + str(index) + '-' + str(time.time_ns()))
                directory.mkdir()
                row = dict(id=case_id, status='failed', results={}, executions={})
                try:
                    captured = reactor_contexts(project, manifest, module, scope, mode,
                                                identity['inputs'][str(manifest_path)])
                    for context in captured:
                        context['model_decode_bytes'] = args.decode_budget_bytes
                    atomic_json(directory / 'context.json', dict(contexts=captured))
                    row.update(source_count=len(captured[0]['sources']),
                               published_source_count=sum(s['publish'] for s in captured[0]['sources']))
                    for label, jar in [('reference', oracle), ('migrated', runner)]:
                        cache = evidence / ('cache-' + mode + '-' + label)
                        cache.mkdir(exist_ok=True)
                        classpath = str(classes) + os.pathsep
                        if label == 'reference':
                            classpath += budget_policy['classpath'] + os.pathsep
                        command = [str(HOST / 'bin/java'), '-Xmx3g',
                                   '-Dcodeana.javac.decode.bytes=' + str(args.decode_budget_bytes), '-cp',
                                   classpath + str(jar), 'ParityWitness',
                                   str(project), str(JDK), str(cache),
                                   'ast' if mode == 'ast' else 'off',
                                   'reference' if label == 'reference' else 'captured', '-',
                                   str(directory / 'context.json')]
                        rows = directory / (label + '-rows')
                        result = execute(command, directory, label, args.timeout_seconds,
                                         dict(os.environ, CODEANA_MODEL_ROW_EVIDENCE=str(rows)))
                        row['executions'][label] = result
                        if result['status'] == 'passed':
                            value = json.loads((directory / (label + '.stdout')).read_text().splitlines()[-1])
                            row['results'][label] = verified_categories(value)
                            atomic_json(directory / (label + '.json'), value)
                    if len(row['results']) == 2:
                        row['differences'] = sorted(k for k in CATEGORIES
                            if row['results']['reference'][k] != row['results']['migrated'][k])
                        row['status'] = 'passed' if not row['differences'] else 'failed'
                except Exception as failure:
                    row['failure'] = str(failure)
                row['proofs'] = {str(p): sha256(p) for p in directory.rglob('*')
                                 if p.is_file() and p.suffix in ('.json', '.stdout', '.stderr')}
                report['cases'].append(row)
                atomic_json(receipt, report)
                print(json.dumps({k: row[k] for k in ('id', 'status', 'executions')}, ensure_ascii=False), flush=True)
                if row['status'] != 'passed' and not args.keep_going:
                    break
            else:
                continue
            break
    except BaseException:
        report['status'] = 'interrupted'
        atomic_json(receipt, report)
        raise
    report['changed_inputs'] = [p for p, digest in identity['inputs'].items() if sha256(p) != digest]
    report['source_verified_after'] = verify_frozen(project, args.freeze_receipt) == frozen
    report['configuration_unchanged'] = configuration_inputs(project) == identity['configuration_inputs']
    passed = {r['id'] for r in report['cases'] if r['status'] == 'passed'}
    report['complete_model_gate'] = (not args.context and not args.exclude_module and passed == set(expected)
        and not report['changed_inputs'] and report['source_verified_after']
        and report['configuration_unchanged'])
    report['subset_accepted'] = (set(selected) <= passed and not report['changed_inputs']
        and report['source_verified_after'] and report['configuration_unchanged'])
    report['status'] = ('passed' if report['complete_model_gate'] else
                        'partial' if report['subset_accepted'] else 'failed')
    report['seconds_this_invocation'] = round(time.monotonic() - started, 3)
    atomic_json(receipt, report)
    return 0 if report['subset_accepted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
