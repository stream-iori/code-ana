#!/usr/bin/env python3
"""Prove an exact, limited transport change; never sign a complete Java gate."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_source_cases import test_cases

CHANGED = {'dev/codeana/common/Json.class', 'dev/codeana/common/FactArchive.class'}
SOURCE_CHANGES = {'lang-java-adapotr/java-bridge/src/main/java/dev/codeana/common/Json.java',
                  'lang-java-adapotr/java-bridge/src/main/java/dev/codeana/common/FactArchive.java'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def manifest(path):
    with zipfile.ZipFile(path) as archive:
        text = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
    return dict(line.split(': ', 1) for line in text.splitlines() if ': ' in line)


def compare(codec, before, after):
    old, new = codec.entries(before.read_bytes()), codec.entries(after.read_bytes())
    changed = {name for name in old.keys() | new.keys() if old.get(name) != new.get(name)}
    require(CHANGED <= old.keys() & new.keys(), 'TRANSPORT_CLASS_REMOVED')
    require(CHANGED <= changed and changed <= CHANGED | {'META-INF/MANIFEST.MF'}, 'UNEXPECTED_PRODUCTION_ENTRY_CHANGE')
    prior, current = manifest(before), manifest(after)
    require(current.get('Java-Version', '25') == '25', 'JAVA_BUILD_VERSION_CHANGED')
    for key in ('Created-By', 'Java-Version'):
        prior.pop(key, None)
        current.pop(key, None)
    require(prior == current and current.get('Build-Jdk-Spec') == '25', 'RUNTIME_MANIFEST_BEHAVIOR_CHANGED')
    # Reject even an unchanged model class if it depends on a changed transport.
    with zipfile.ZipFile(after) as archive:
        for name in archive.namelist():
            if name.startswith('com/anatomist/') and name.endswith('.class'):
                content = archive.read(name)
                require(not any(target[:-6].encode() in content for target in CHANGED), 'MODEL_DEPENDS_ON_CHANGED_TRANSPORT: ' + name)
    return dict(changed_entries=sorted(changed), unchanged_entries=len(old) - len(changed))


def main():
    parser = argparse.ArgumentParser()
    for name in ('old-jvm', 'new-jvm', 'old-native', 'new-native', 'jvm-gate', 'native-gate',
                 'old-source', 'new-source', 'components', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'NEW_LINEAGE_RECEIPT_REQUIRED')
    inputs = {str(path.resolve()): sha256(path) for name, path in vars(args).items() if name != 'output'}
    inputs[str(Path(__file__).resolve())] = sha256(__file__)
    inputs[str(ROOT / 'tests/java_source_cases.py')] = sha256(ROOT / 'tests/java_source_cases.py')
    try:
        for backend in ('jvm', 'native'):
            gate = json.loads(getattr(args, backend + '_gate').read_text())
            require(gate.get('status') == 'passed' and gate.get('jvm_migration_accepted' if backend == 'jvm' else 'native_accepted') is True,
                    'TRUSTED_ORIGINAL_JAVA_GATE_REQUIRED')
            require(sha256(getattr(args, 'old_' + backend)) in gate['inputs'].values(), 'OLD_JAR_NOT_IN_ACCEPTED_GATE')
        before, after = [json.loads(path.read_text()) for path in (args.old_source, args.new_source)]
        production = {name for name in before['files'] if '/src/main/java/' in name and name.endswith('.java')}
        require(production == {name for name in after['files'] if '/src/main/java/' in name and name.endswith('.java')}, 'PRODUCTION_SOURCE_SET_CHANGED')
        require({name for name in production if before['files'][name] != after['files'][name]} == SOURCE_CHANGES, 'UNEXPECTED_PRODUCTION_SOURCE_CHANGE')
        for path, value in ((args.old_source, before), (args.new_source, after)):
            for name, digest in value['files'].items():
                source = (path.parent / name).resolve()
                require(source.is_relative_to(path.parent.resolve()) and sha256(source) == digest, 'JAVA_SOURCE_SNAPSHOT_DRIFT')
                inputs[str(source)] = digest
        components = json.loads(args.components.read_text())
        expected_cases = []
        for name in after['files']:
            if Path(name).parts[2:5] == ('src', 'test', 'java') and name.endswith('.java') and Path(name).parts[1] != 'native-bridge':
                source = args.new_source.parent / name
                expected_cases.extend(test_cases(source.read_text(), source.stem))
        require(components.get('status') == 'passed' and components.get('expected_count') == components.get('executed_count') == 656
                and components.get('previous_cases_retained') is True and len(components.get('added_cases', [])) == 2
                and all(row['status'] == 'passed' for row in components['cases'])
                and len(expected_cases) == 656 and set(expected_cases) == {row['id'] for row in components['cases']}
                and all(components.get(key) == [] for key in ('missing', 'duplicate', 'unexpected', 'expected_duplicate', 'artifact_drift')),
                'COMPLETE_TRANSPORT_COMPONENT_TESTS_REQUIRED')
        inputs.update(components['inputs'])
        tool = ROOT / 'scripts/java-model-runtime-equivalence.py'
        spec = importlib.util.spec_from_file_location('codec_entries', tool)
        codec = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(codec)
        proofs = {backend: compare(codec, getattr(args, 'old_' + backend), getattr(args, 'new_' + backend)) for backend in ('jvm', 'native')}
        a, b = codec.entries(args.new_jvm.read_bytes()), codec.entries(args.new_native.read_bytes())
        shared = {name for name in a if name.startswith(('com/anatomist/', 'dev/codeana/adapter/', 'dev/codeana/common/', 'META-INF/anatomist/'))}
        require(bool(shared) and all(a[name] == b.get(name) for name in shared), 'NATIVE_JVM_SHARED_BYTECODE_DRIFT')
        require(not any(name.startswith(('com/anatomist/', 'dev/codeana/adapter/', 'dev/codeana/common/')) for name in b.keys() - a.keys()),
                'NATIVE_EXTRA_PRODUCER_ENTRY')
        inputs[str(tool)] = sha256(tool)
        for path, digest in inputs.items():
            require(sha256(path) == digest, 'LINEAGE_INPUT_DRIFT')
        report = dict(status='passed', raw_model_producer_bytes_unchanged=True, transport_changes_only=True,
                      shared_native_jvm_producer_bytes_identical=True, shared_entries=len(shared), bytecode=proofs,
                      new_native_jar_sha256=sha256(args.new_native), new_jvm_jar_sha256=sha256(args.new_jvm),
                      scope='exact two transport classes; complete component suite; unchanged raw model producer lineage',
                      complete_java_gate=False, native_accepted=False, performance_accepted=False, inputs=inputs)
    except Exception as error:
        report = dict(status='failed', failure=str(error), complete_java_gate=False, native_accepted=False, performance_accepted=False)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'inputs'}))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
