"""Explicit backend requirements shared by product and distribution gates."""
def requires_backends(*backends):
    def decorate(method):
        method.required_backends = frozenset(backends)
        return method
    return decorate


def required_backends(test):
    return frozenset(getattr(getattr(test, test._testMethodName), 'required_backends',
                             getattr(test, 'required_backends', ())))


def select_cases(tests, backends):
    selected, excluded = [], {}
    for test in tests:
        missing = required_backends(test) - set(backends)
        if missing:
            excluded[test.id()] = 'backend not in declared scope: '+','.join(sorted(missing))
        else:
            selected.append(test)
    return selected, excluded


def run_cases(tests, backends, excluded, evidence_directory=None):
    import json
    import os
    import pathlib
    import subprocess
    import time
    import unittest
    from product_support import ROOT, BIN
    if evidence_directory is None:
        print(json.dumps({'required_backends':list(backends), 'declared_scope_exclusions':excluded}), flush=True)
        result = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite(tests))
        return 0 if result.wasSuccessful() and not result.skipped else 1
    from acceptance_support import identities, summarize
    import java_jvm_product_gate as evidence
    output = pathlib.Path(evidence_directory).resolve()
    output.mkdir(parents=True, exist_ok=False)
    evidence.PROGRESS_FILE = output/'progress.json'
    paths = [ROOT/name for name in subprocess.check_output(['git','ls-files','-co','--exclude-standard'],cwd=ROOT,text=True).splitlines()]
    paths += [path for path in BIN.glob('codeana-*') if path.is_file()]
    if 'java' in backends:
        paths += [path for path in (ROOT/'target/lib/java').rglob('*') if path.is_file()]
        if os.environ.get('CODEANA_TEST_JAVA_BUNDLE'):
            paths += [path for path in pathlib.Path(os.environ['CODEANA_TEST_JAVA_BUNDLE']).rglob('*') if path.is_file()]
    if 'typescript' in backends:
        sdk = pathlib.Path(os.environ['CODEANA_TYPESCRIPT_SDK'])
        paths += [path for path in sdk.rglob('*') if path.is_file()]
    before = identities(paths)
    expected = [test.id() for test in tests]
    (output/'expected-cases.json').write_text(json.dumps(expected,indent=2)+'\n')
    started = time.monotonic()
    with (output/'tests.log').open('w') as log:
        result = unittest.TextTestRunner(stream=log,verbosity=2,resultclass=evidence.ReceiptResult).run(unittest.TestSuite(tests))
    receipt = summarize(expected,result.cases,before,identities(paths))
    receipt.update(required_backends=list(backends),declared_scope_exclusions=excluded,seconds=time.monotonic()-started,complete_plan=False,native_accepted=False,release_accepted=False,performance_accepted=False)
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    evidence.PROGRESS_FILE.write_text(json.dumps({'status':receipt['status'],'cases':result.cases},indent=2)+'\n')
    print(json.dumps({key:receipt[key] for key in ('status','expected_count','executed_count','artifact_drift')}))
    return 0 if receipt['status']=='passed' else 1
