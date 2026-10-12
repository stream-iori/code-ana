"""Shared fixture transport for the current request/CAF contract and selected backend."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def analyze(test, contexts, baseline=None, parallelism=1, cache=False, compact=False):
    if getattr(test, 'probe', 'dev.codeana.analysis.AnalysisProbe') != 'dev.codeana.analysis.AnalysisProbe':
        raise ValueError('MODEL_PROBE_REQUIRES_CURRENT_DETACHED_MODEL_TRANSPORT')
    backend = os.environ.get('CODEANA_JAVA_BACKEND', 'native')
    bundle = Path(os.environ.get('CODEANA_TEST_JAVA_BUNDLE', ROOT / 'lang-java-adapotr/target' / backend))
    runtime = bundle / ('runner.jar' if backend == 'jvm' else 'libcodeana_java.dylib')
    binaries = Path(os.environ.get('CODEANA_TEST_BIN', ROOT / 'target/debug'))
    from java_model_tests import JDK8, compact_request
    request = dict(version=8, facts=True, full=baseline is None, java_home=str(JDK8),
                   scratch=str(test.root), contexts=contexts, parallelism=parallelism)
    if baseline is not None:
        archive = test.root / 'baseline.caf'
        with archive.open('wb') as output:
            output.write(b'CAF1')
            for fact in baseline:
                encoded = json.dumps(fact, ensure_ascii=False, separators=(',', ':')).encode()
                output.write(struct.pack('>I', len(encoded)))
                output.write(encoded)
        request['baseline_archive'] = dict(path=str(archive), count=len(baseline),
            bytes=archive.stat().st_size, sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
    if cache:
        request.update(signature_cache=True, cache_root=str(test.root / 'signature-cache'))
    if compact:
        request = compact_request(request)
    if hasattr(test, 'mutate_request'):
        test.mutate_request(request)
    path = test.root / 'request.json'
    path.write_text(json.dumps(request))
    result = subprocess.run([str(binaries / 'codeana-java-worker'), str(runtime),
        '--backend', backend, '--probe', str(path)], capture_output=True, text=True, timeout=150)
    test.assertEqual(result.returncode, 0, result.stderr)
    test.assertFalse(list(test.root.rglob('*.class')))
    return json.loads(result.stdout)
