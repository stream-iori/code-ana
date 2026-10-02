#!/usr/bin/env python3
"""Isolated binding regression from frozen committed Java files; not reactor acceptance."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time
import unittest

import java_functional_reference_alignment as probes
import java_lombok_alignment as lombok
import java_model_tests as models

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATHS = (
    'app/core/service/src/test/java/com/alipay/iopengw/core/service/expression/function/util/OutReqUnicode2AsciiFunctionTest.java',
    'app/common/service/facade/src/main/java/com/alipay/iopengw/service/facade/message/OpengwMessage.java',
    'app/component/message-protocol-common/src/main/java/com/alipay/iopengw/component/message/protocol/common/enums/MessageType.java',
)


class OriginalAnonymousConstructor(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def fixture(self, constructor=True):
        files = {pathlib.Path(path).name: (self.project / path).read_text(encoding='utf8') for path in PATHS}
        # Fixture stubs satisfy unrelated imports; target project business code is never compiled.
        files['OutReqUnicode2AsciiFunction.java'] = (
            'package com.alipay.iopengw.core.service.expression.function.util;'
            'public class OutReqUnicode2AsciiFunction {'
            'public com.googlecode.aviator.runtime.type.AviatorObject call(java.util.Map<String,Object> env){return null;}'
            'public String getName(){return null;}}')
        files['JSONUtil.java'] = (
            'package com.alipay.iopengw.service.facade.util;public class JSONUtil {'
            'public static Object getValueByPath(Object o,String path){return null;}'
            'public static String objToJsonString(Object o){return null;}}')
        trigger = ('@lombok.AllArgsConstructor ' if constructor else '') + '@lombok.Getter class OtherData {Object data;}'
        files.update(lombok.Lombok.files(self, trigger))
        return self.context('fixture', files, lombok='model', classpath=self.classpath)

    def assert_constructor(self, facts):
        declarations = {f['id']: f for f in facts if f['kind'] in ('declaration', 'external_symbol')}
        sites = [f for f in facts if f['kind'] == 'call_site'
                 and f['source'].endswith('OutReqUnicode2AsciiFunctionTest.java')
                 and f['attributes'].get('javac_selected_declaration', '').endswith('Test$1#<init>()')]
        self.assertEqual(len(sites), 1)
        site = sites[0]
        self.assertEqual(site['status'], 'resolved')
        self.assertNotEqual(site['attributes'].get('recovery_failure'), 'recovery_budget')
        targets = [f for f in facts if f['kind'] == 'call_target' and f['parent'] == site['id']]
        self.assertEqual(len(targets), 1)
        self.assertEqual(targets[0]['status'], 'resolved')
        self.assertTrue(declarations[targets[0]['target']]['signature'].endswith('$1#<init>()'))

    def test_confirmed_constructor_avoids_unrelated_parent_overload_budget(self):
        self.assert_constructor(self.analyze([self.fixture()])['facts'])

    def test_full_delta_and_generated_constructor_removal(self):
        base = self.analyze([self.fixture()], cache=True)
        changed = self.fixture(False)
        delta = self.analyze([changed], base['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        self.assert_constructor(full['facts'])
        self.assertEqual(self.merged(base['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=pathlib.Path, default=ROOT / 'target/java-alignment/iopengw')
    parser.add_argument('--inputs-manifest', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path)
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    freeze = json.loads((args.project.parent / 'iopengw-freeze.json').read_text())
    expected = json.loads((ROOT / 'tests/java_alignment_matrix.json').read_text())['real_project']
    if freeze['commit'] != expected['commit'] or freeze['include_uncommitted']:
        raise ValueError('FROZEN_PROJECT_IDENTITY_CHANGED')
    sources = {}
    for path in PATHS:
        actual = (args.project / path).read_bytes()
        committed = subprocess.check_output(['git', '-C', expected['path'], 'cat-file', 'blob', expected['commit'] + ':' + path])
        if actual != committed:
            raise ValueError('FROZEN_SOURCE_CHANGED: ' + path)
        sources[path] = hashlib.sha256(actual).hexdigest()
    manifest = json.loads(args.inputs_manifest.read_text())
    module = next(m for m in manifest['modules'] if m['id'] == 'app/core/service')
    jars = [pathlib.Path(row['path']) for row in module['classpath']['TEST']]
    dependencies = {str(path): probes.digest(path) for path in jars}
    artifacts = probes.probe_artifacts(args.native)
    case = OriginalAnonymousConstructor
    case.project = args.project.resolve()
    case.classpath = [str(path) for path in jars]
    if args.native:
        case.analyze = probes.native_analyze
    evidence = (args.evidence or ROOT / 'target/java-alignment/evidence' /
                ('real-binding-fixture-' + time.strftime('%Y%m%d-%H%M%S'))).resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    with (evidence / 'regressions.log').open('w') as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(case))
    unchanged = artifacts == probes.probe_artifacts(args.native)
    unchanged &= dependencies == {str(path): probes.digest(path) for path in jars}
    unchanged &= sources == {path: hashlib.sha256((args.project / path).read_bytes()).hexdigest() for path in PATHS}
    report = dict(kind='frozen_source_isolated_binding_fixture', commit=expected['commit'],
                  native_verified=args.native, tests=result.testsRun, successful=result.wasSuccessful(),
                  elapsed_seconds=round(time.monotonic() - started, 3), sources=sources,
                  dependencies=dependencies, artifacts=artifacts, inputs_unchanged=unchanged,
                  all_ok=result.wasSuccessful() and unchanged, full_semantic_diff_accepted=False)
    (evidence / 'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(evidence / 'receipt.json', flush=True)
    if not report['all_ok']:
        print((evidence / 'regressions.log').read_text()[-8000:])
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
