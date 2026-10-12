#!/usr/bin/env python3
"""Source-bound, single-round diagnosis. Never signs optimization acceptance."""
import argparse
import csv
import hashlib
import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPERATURES = ('cold_application_cache', 'warm_application_cache')


def sha(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, value):
    path = pathlib.Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(path)


def freeze(destination):
    destination.mkdir(parents=True, exist_ok=False)
    files = {}
    for directory in ('crates', 'proto', 'lang-java-adapotr', 'scripts', 'tests'):
        for source in (ROOT / directory).rglob('*'):
            if (not source.is_file() or 'target' in source.relative_to(ROOT).parts
                    or '__pycache__' in source.parts or source.suffix not in
                    ('.rs', '.proto', '.java', '.py', '.toml', '.xml', '.sh', '.sql')):
                continue
            relative = source.relative_to(ROOT)
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            files[str(relative)] = sha(source)
    for name in ('Cargo.toml', 'Cargo.lock', 'AGENTS.md'):
        shutil.copy2(ROOT / name, destination / name)
        files[name] = sha(ROOT / name)
    write(destination / 'source-manifest.json', dict(source_root=str(ROOT), files=files))


def verify_sources(manifest):
    manifest = pathlib.Path(manifest).resolve()
    value = json.loads(manifest.read_text())
    for name, digest in value['files'].items():
        archive = (manifest.parent / name).resolve()
        current = (ROOT / name).resolve()
        if not archive.is_relative_to(manifest.parent) or not current.is_relative_to(ROOT):
            raise ValueError('SOURCE_PATH_ESCAPE')
        if sha(archive) != digest or sha(current) != digest:
            raise ValueError('DIAGNOSTIC_SOURCE_DRIFT: ' + name)


def verify_inputs(inputs):
    if not isinstance(inputs, dict) or not inputs:
        raise ValueError('NONEMPTY_PROOFS_REQUIRED')
    for name, digest in inputs.items():
        if sha(name) != digest:
            raise ValueError('DIAGNOSTIC_INPUT_DRIFT: ' + name)


def validate_fixtures(receipts):
    expected = {('native', 'probe'): 17, ('native', 'product'): 58,
                ('native', 'lifecycle'): 6, ('jvm', 'probe'): 17, ('jvm', 'product'): 58}
    found = {}
    for filename in receipts:
        value = json.loads(pathlib.Path(filename).read_text())
        if value.get('status') != 'passed' or value.get('artifact_drift'):
            raise ValueError('CURRENT_FIXTURE_FAILURE: ' + filename)
        if value.get('missing') or value.get('duplicate') or value.get('executed_count') != value.get('expected_count'):
            raise ValueError('INCOMPLETE_FIXTURE: ' + filename)
        if any(row.get('status') != 'passed' for row in value.get('cases', [])):
            raise ValueError('NONPASSING_FIXTURE_CASE: ' + filename)
        key = (value['backend'], value['group'])
        if value.get('complete_group_gate'):
            if key in found:
                raise ValueError('DUPLICATE_FIXTURE_GROUP')
            found[key] = value['executed_count']
        verify_inputs(value['sources_and_artifacts'])
    if found != expected:
        raise ValueError('COMPLETE_CURRENT_FIXTURES_REQUIRED')


def runtime_delta(before, after):
    result = {}
    for available, field in (('thread_cpu_available', 'thread_cpu_ns'),
                             ('thread_allocated_available', 'thread_allocated_bytes')):
        left, right = before.get(field), after.get(field)
        result[field] = (right - left if before.get(available) and after.get(available)
                         and isinstance(left, int) and isinstance(right, int) and right >= left else None)
    left = {row['name']: row for row in before.get('gc', [])}
    right = {row['name']: row for row in after.get('gc', [])}
    valid = before.get('gc_available') and after.get('gc_available') and left.keys() == right.keys()
    valid = valid and all(right[k]['time_ms'] >= left[k]['time_ms'] and right[k]['count'] >= left[k]['count'] for k in left)
    result['gc_ms'] = sum(right[k]['time_ms'] - left[k]['time_ms'] for k in left) if valid else None
    result['gc_count'] = sum(right[k]['count'] - left[k]['count'] for k in left) if valid else None
    return result


def phase_summary(folder, backend, temperature):
    phase = json.loads((folder / 'receipt.json').read_text())
    workers = [p for p in (folder / 'worker-timings').glob('worker-*') if p.is_dir()]
    if phase.get('status') != 'passed' or len(workers) != 1:
        raise ValueError('ONE_SUCCESSFUL_WORKER_REQUIRED')
    java = json.loads((workers[0] / 'java-timings.json').read_text())
    rust = json.loads((workers[0] / 'rust-timings.json').read_text())
    if java.get('state') != 1 or not java.get('diagnostics') or java.get('unfinished_phases'):
        raise ValueError('COMPLETE_JAVA_TIMINGS_REQUIRED')
    for name in ('graph_process', 'view_add', 'fact_projection', 'unit_projection'):
        if name not in java['phase_ms']:
            raise ValueError('MISSING_PROJECTION_PHASE: ' + name)
    timeline = json.loads((workers[0] / 'phases.json').read_text())
    elapsed = {row['phase']: row['elapsed_ms'] for row in timeline}
    result = dict(backend=backend, temperature=temperature, index_seconds=phase['index_seconds'],
                  java_seconds=java['elapsed_ms'] / 1000, java_phase_ms=java['phase_ms'],
                  java_metrics=java['diagnostics']['metrics'], rust_metrics=rust['metrics'],
                  runtime=runtime_delta(java['diagnostics']['runtime_before'], java['diagnostics']['runtime_after']),
                  java_projection_statistics=java['projection_statistics'], graph=phase['graph'], queries=phase['queries'],
                  archive_validation_seconds=(elapsed['archive_validated_and_deduplicated'] - elapsed[backend + '_finished']) / 1000,
                  delivery_seconds=(elapsed['result_end_sent'] - elapsed['archive_validated_and_deduplicated']) / 1000,
                  timing_files={str(p): sha(p) for p in workers[0].glob('*.json')})
    result['daemon_metrics'] = {}
    for name in ('transport', 'storage-detail', 'publish-detail'):
        paths = list((folder / 'worker-timings').glob(name + '-*.json'))
        if len(paths) != 1:
            raise ValueError('ONE_DAEMON_DETAIL_REQUIRED: ' + name)
        result['daemon_metrics'][name] = json.loads(paths[0].read_text())['metrics']
        result['timing_files'][str(paths[0])] = sha(paths[0])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--freeze', type=pathlib.Path)
    parser.add_argument('--spec', type=pathlib.Path)
    parser.add_argument('--evidence', type=pathlib.Path)
    args = parser.parse_args()
    if args.freeze:
        freeze(args.freeze.resolve())
        return 0
    if not args.spec or not args.evidence:
        parser.error('--spec and --evidence required')
    spec = json.loads(args.spec.read_text())
    output = args.evidence.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = dict(status='running', purpose='native_performance_analysis', rounds=[],
                  optimization_accepted=False, native_half_speed_accepted=False,
                  rounds_per_backend=1, os_page_cache='uncontrolled', inputs={}, failure=None)
    try:
        verify_sources(spec['build_manifest'])
        build = json.loads(pathlib.Path(spec['build_receipt']).read_text())
        if (build.get('status') != 'passed' or build.get('build_profile') != 'release'
                or build['source_manifest_sha256'] != sha(spec['build_manifest'])):
            raise ValueError('CURRENT_RELEASE_BUILD_REQUIRED')
        verify_inputs(build['binaries'])
        validate_fixtures(spec['fixtures'])
        for key in ('components_receipt', 'rust_receipt'):
            proof = json.loads(pathlib.Path(spec[key]).read_text())
            if proof.get('status') != 'passed':
                raise ValueError('CURRENT_TESTS_REQUIRED: ' + key)
            verify_inputs(proof['inputs'])
        for filename in spec['calibrations']:
            calibration = json.loads(pathlib.Path(filename).read_text())
            if calibration.get('status') != 'passed' or calibration['overhead_fraction'] > .03:
                raise ValueError('CALIBRATED_OBSERVATION_REQUIRED')
            verify_inputs(calibration['inputs'])
        proof_paths = [args.spec, pathlib.Path(__file__), pathlib.Path(spec['build_manifest']), pathlib.Path(spec['build_receipt'])]
        proof_paths += [pathlib.Path(spec[k]) for k in ('components_receipt', 'rust_receipt', 'expected_graph', 'query_plan')]
        proof_paths += [pathlib.Path(p) for p in spec['fixtures'] + spec['calibrations']]
        proof_paths += [ROOT / 'scripts' / p for p in ('java-performance-phase-single-v2.py', 'java-performance-monitor-single-v2.py', 'java-performance-stable-runtime.py')]
        report['inputs'] = {str(p.resolve()): sha(p) for p in proof_paths}
        report['budget'] = dict(model_decode_bytes=2 << 30, host_heap_bytes=2 << 30, worker_memory_bytes=4 << 30,
                                compiler_heap_bytes=512 << 20, result_bytes=8 << 30, scratch_bytes=32 << 30)
        write(output / 'receipt.json', report)
        sys.path.insert(0, str(ROOT / 'tests'))
        from java_real_acceptance import project_config, verify_frozen
        report['frozen_project'] = verify_frozen(pathlib.Path(spec['frozen_project']), spec['frozen_receipt'])
        report['inputs'][str(pathlib.Path(spec['frozen_receipt']).resolve())] = sha(spec['frozen_receipt'])
        loader = importlib.util.spec_from_file_location('stable', ROOT / 'scripts/java-performance-stable-runtime.py')
        stable = importlib.util.module_from_spec(loader)
        loader.loader.exec_module(stable)
        binaries = pathlib.Path(spec['release_binaries']).resolve()
        expected_binaries = ROOT / 'target/implementation-evidence/performance-candidate-build-01/release'
        if binaries != expected_binaries:
            raise ValueError('FIXED_APPLICATION_PATH_REQUIRED')
        for backend in ('jvm', 'native'):
            folder = output / backend
            folder.mkdir()
            project = folder / 'project'
            shutil.copytree(spec['frozen_project'], project)
            config = project_config(['MAIN', 'TEST'], True, '.code-ana/java-inputs-v2.json', 2 << 30, 4 << 30, 8 << 30, 32 << 30, 1800)
            config = config.replace(b'[resources.index]\n', b'[resources.index]\njava_model_decode_bytes=2147483648\n')
            (project / '.code-ana/config.toml').write_bytes(config)
            with tempfile.TemporaryDirectory(prefix='diagnostic-runtime-facade-') as temporary:
                facade = pathlib.Path(temporary)
                (facade / 'target').mkdir()
                (facade / 'target/debug').symlink_to(binaries, target_is_directory=True)
                (facade / 'lang-java-adapotr').symlink_to(pathlib.Path(spec['bundle_root']) / 'lang-java-adapotr', target_is_directory=True)
                stable.prepare(facade, folder / 'runtime', backend)
            home = folder / 'home'
            home.mkdir(mode=0o700)
            jdk = pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'
            (home / 'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=4294967296\nmax_total_worker_memory_bytes=4294967296\n[toolchains.java8]\nlanguage="java"\nhome=' + json.dumps(str(jdk)) + '\n')
            verify_frozen(project, spec['frozen_receipt'])
            for temperature in TEMPERATURES:
                verify_sources(spec['build_manifest'])
                phase = folder / temperature
                command = [sys.executable, str(ROOT / 'scripts/java-performance-phase-single-v2.py'), '--backend', backend,
                           '--runtime', str(folder / 'runtime'), '--build-receipt', spec['build_receipt'],
                           '--project', str(project), '--home', str(home), '--evidence', str(phase),
                           '--expected-graph', spec['expected_graph'], '--query-plan', spec['query_plan']]
                monitor_spec = folder / (temperature + '-spec.json')
                write(monitor_spec, dict(command=command, cwd=str(ROOT), env={}, backend=backend,
                                        cache_temperature=temperature, disk_roots=[str(folder)], timeout_seconds=86400))
                with (folder / (temperature + '.log')).open('wb') as log:
                    execution = subprocess.run([sys.executable, str(ROOT / 'scripts/java-performance-monitor-single-v2.py'),
                                                '--spec', str(monitor_spec), '--evidence', str(folder / (temperature + '-monitor'))],
                                               stdout=log, stderr=subprocess.STDOUT)
                if execution.returncode:
                    raise ValueError('DIAGNOSTIC_PHASE_FAILED: ' + str(phase))
                row = phase_summary(phase, backend, temperature)
                verify_sources(spec['build_manifest'])
                report['rounds'].append(row)
                write(output / 'receipt.json', report)
                print(json.dumps(dict(backend=backend, temperature=temperature, index_seconds=row['index_seconds'])), flush=True)
        if len({(r['graph']['count'], r['graph']['sha256']) for r in report['rounds']}) != 1:
            raise ValueError('FOUR_PHASE_GRAPH_DRIFT')
        for row in report['rounds']:
            verify_inputs(row['timing_files'])
        for filename in spec['calibrations']:
            verify_inputs(json.loads(pathlib.Path(filename).read_text())['inputs'])
        verify_inputs(report['inputs'])
        report['status'] = 'passed'
        write(output / 'analysis.json', dict(rounds=report['rounds'], budget=report['budget'], optimization_accepted=False))
        with (output / 'stages.csv').open('w') as stream:
            writer = csv.writer(stream)
            writer.writerow(('backend', 'temperature', 'phase', 'seconds'))
            for row in report['rounds']:
                for name, value in row['java_phase_ms'].items():
                    writer.writerow((row['backend'], row['temperature'], name, value / 1000))
    except BaseException as error:
        report.update(status='failed', failure=str(error))
    write(output / 'receipt.json', report)
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
