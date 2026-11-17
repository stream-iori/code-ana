#!/usr/bin/env python3
"""Actual pinned resolver -> worker preparation -> public Rust capture, no skips."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time
from acceptance_support import identities, sha256

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('baseline', ROOT / 'scripts/multilang-baseline.py')
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    sdk, output = args.sdk.resolve(), args.evidence.resolve()
    if not output.is_relative_to(ROOT / 'target/typescript-c-abi'):
        parser.error('new evidence under target/typescript-c-abi required')
    output.mkdir(parents=True, exist_ok=False)
    binary = ROOT / 'target/debug/codeana-typescript-worker'
    fixtures = json.loads((ROOT / 'lang-typescript-adaptor/overlay/engine/testdata/resolution.json').read_text())
    assert len(fixtures) == 4
    source_paths = [ROOT / name for name in subprocess.check_output(
        ['git', 'ls-files', '-co', '--exclude-standard'], cwd=ROOT, text=True).splitlines()]
    paths = [*source_paths, binary, *[path for path in sdk.rglob('*') if path.is_file()]]
    before = identities(paths)
    report = dict(status='failed', stage='T3 actual resolution and public capture',
                  commands=[], inputs=before, T3_complete=False, public_semantics=False,
                  release_accepted=False, performance_accepted=False)

    def run(name, argv, environment, timeout=45):
        log = output / (name + '.log')
        entry = dict(id=name, argv=list(map(str, argv)), log=log.name)
        report['commands'].append(entry)
        started = time.monotonic()
        with log.open('wb') as stream:
            process = subprocess.Popen(entry['argv'], cwd=ROOT, env=environment,
                                       stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                process.wait(timeout=timeout)
            finally:
                baseline.signal_group(process.pid, signal.SIGKILL)
                entry.update(exit_code=process.wait(), seconds=time.monotonic() - started,
                             sha256=sha256(log))
        assert entry['exit_code'] == 0, (name, entry, log.read_text())

    try:
        for case in fixtures:
            project = output / case['name']
            project.mkdir()
            state = project / '.code-ana'
            state.mkdir()
            for name, text in case['files'].items():
                path = project / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            for name, target in case['links'].items():
                (project / name).symlink_to(target, target_is_directory=True)
            (state / 'config.toml').write_text(
                "schema_version=1\nkind='project'\n[languages.typescript]\nenabled=true\n"
                "[languages.typescript.discovery]\ninputs_manifest='.code-ana/native.json'\n")
            argv = [binary, '--sdk', sdk, '--prepare-project', project,
                    '--prepare-config', project / 'tsconfig.json', '--output', state / 'native.json',
                    '--scratch', output / (case['name'] + '-scratch'), '--unit', case['name'],
                    '--module', 'app', '--world', 'typescript/' + case['name'],
                    '--publish-language', 'typescript']
            run(case['name'], argv, dict(os.environ, PATH=''))
            native = json.loads((state / 'native.json').read_text())
            context = native['contexts'][0]
            target = str(project) + case['target']
            assert any(file['path'] == target for file in context['files'])
            assert not any(Path(file['path']).name in ('runtime.js', 'runtime.cjs') for file in context['files'])
            if case['links']:
                assert context['links']
                assert next(file for file in context['files'] if file['path'] == target)['role'] == 'auxiliary'
        (output / 'cases.json').write_text(json.dumps([case['name'] for case in fixtures]))
        run('public-capture', ['cargo', 'test', '-p', 'codeana-config', '--test', 'typescript_inputs',
                              '--locked', 'actual_resolution_inventories_freeze_roles_and_reject_stale_observations',
                              '--', '--ignored', '--exact', '--nocapture'],
            dict(os.environ, CODEANA_TYPESCRIPT_RESOLUTION_FIXTURE=str(output)), timeout=300)
        proof = json.loads((output / 'capture-proof.json').read_text())
        assert len(proof) == 4 and all(item['live_files_deleted'] and item['control_and_failed_lookup_staleness'] for item in proof)
        after = identities(paths)
        report['artifact_drift'] = [name for name in sorted(before.keys() | after.keys()) if before.get(name) != after.get(name)]
        assert not report['artifact_drift']
        report.update(status='passed', executed_count=4, proof=proof)
    except Exception as failure:
        report.update(failure=str(failure), failure_type=type(failure).__name__)
        raise
    finally:
        (output / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('status', 'executed_count', 'artifact_drift')}))


if __name__ == '__main__':
    main()
