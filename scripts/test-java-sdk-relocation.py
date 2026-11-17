#!/usr/bin/env python3
"""Check an identical SDK at another location using the same project/history."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import traceback

if sys.version_info < (3, 11):
    raise SystemExit('Python >= 3.11 is required; select it explicitly in PATH')

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tests'))
import product_support
from java_graph_storage import GraphStorage
from java_native_crash_e2e import start_daemon


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    output = args.evidence.resolve()
    if not output.is_relative_to((ROOT/'target').resolve()):
        parser.error('evidence must be a fresh directory under target')
    output.mkdir(parents=True, exist_ok=False)
    runtime = output/'runtime'
    binary = runtime/'bin'
    binary.mkdir(parents=True)
    original = product_support.BIN
    for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker'):
        shutil.copy2(original/name, binary/name)
        if sha(original/name) != sha(binary/name):
            raise RuntimeError('RELOCATED_BINARY_CHECKSUM: '+name)
    source = (original/'../lib/java').resolve()
    shutil.copytree(source, runtime/'lib/java')
    identities = {str(p.relative_to(source)): sha(p) for p in source.rglob('*') if p.is_file()}
    for relative, expected in identities.items():
        if sha(runtime/'lib/java'/relative) != expected:
            raise RuntimeError('RELOCATED_SDK_CHECKSUM: '+relative)
    os.environ['CODEANA_JAVA_BACKEND'] = 'native'
    case = GraphStorage('test_unchanged_index_preserves_graph_and_fts')
    receipt = dict(status='failed', native_accepted=False, sdk_sha256=identities,
        original_bin=str(original), relocated_bin=str(binary),
        application_sha256={name:sha(binary/name) for name in ('codeana-cli','codeana-daemon','codeana-java-worker')})
    case.daemon = case.log = None
    try:
        case.setUp()
        _, first = case.cli('index', '--full', timeout=180)
        graph = case.canonical(first['revision'])
        def profile(revision):
            return json.loads((case.home/'revisions'/revision/'revision.json').read_text())['profile']
        identity = profile(first['revision'])
        case.stop()
        product_support.BIN = binary
        start_daemon(case, binary)
        records, reused = case.cli('index', timeout=180)
        case.assertEqual(records[0]['mode'], 'delta')
        case.assertEqual(profile(reused['revision']), identity)
        case.assertEqual(case.canonical(reused['revision']), graph)
        report = case.cli('report', reused['report'])[0][0]
        workers = [w for slot in report['analysis_resources']['coordinator']['languages'] for w in slot['workers']]
        case.assertTrue(workers and all(w['phase'] == 'reused' for w in workers), workers)
        _, rebuilt = case.cli('index', '--full', timeout=180)
        case.assertEqual(profile(rebuilt['revision']), identity)
        case.assertEqual(case.canonical(rebuilt['revision']), graph)
        receipt.update(status='passed',
            scope='Native SDK relocation with same project/history: Reused then actual full analysis',
            semantic_profile=identity, reused_revision=reused['revision'], full_revision=rebuilt['revision'])
        print('identical SDK relocated: same profile, Reused receipt and full graph')
    except Exception:
        receipt['error'] = traceback.format_exc()
        raise
    finally:
        try:
            if getattr(case, 'root', None) is not None and (case.root/'daemon.log').exists():
                (output/'daemon.log').write_text(case.daemon_log())
            if getattr(case, 'temp', None) is not None:
                case.cleanup()
        except Exception:
            receipt['status'] = 'failed'
            receipt['cleanup_error'] = traceback.format_exc()
            raise
        finally:
            product_support.BIN = original
            (output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')


if __name__ == '__main__':
    main()
