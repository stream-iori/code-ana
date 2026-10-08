#!/usr/bin/env python3
"""Prepare a direct Java diagnostic request from an existing frozen Maven manifest.

No Maven invocation, target compilation, daemon, or publication. Capture XML from
manifest resource roots and ancestor lombok.config; other Rust control/resource
inputs are not reproduced, so this does not certify full capture parity.
"""
import argparse
import functools
import json
import pathlib

import java_real_compiler_diagnostics as compiler
from java_real_acceptance import digest, verify_frozen
from java_model_tests import key


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=pathlib.Path, required=True)
    parser.add_argument('--manifest', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--scratch', type=pathlib.Path, required=True)
    parser.add_argument('--cache', type=pathlib.Path, required=True)
    args = parser.parse_args()
    project = args.project.resolve()
    verify_frozen(project)
    manifest = json.loads(args.manifest.read_text())
    manifest_hash = digest(args.manifest)
    # Shared jars are checked once during preparation, outside the measured index.
    compiler.digest = functools.lru_cache(maxsize=None)(compiler.digest)
    inputs, paths, jars, jar_slots, contexts = [], {}, [], {}, []
    configurations = sorted(project.rglob('lombok.config'))

    def source_slot(unit, input_path):
        if unit['path'] not in paths:
            paths[unit['path']] = len(inputs)
            inputs.append(dict(unit=unit, input=input_path, publish=False))
        return paths[unit['path']]

    for module in manifest['modules']:
        for scope in manifest['scopes']:
            context = compiler.compiler_context(project, manifest, module['id'], scope)
            selected, published, resources, classpath = [], [], [], []
            for source in context.pop('sources'):
                unit = {k: v for k, v in source.items() if k not in ('input', 'publish')}
                unit['language'] = 'java'
                slot = source_slot(unit, source['input'])
                selected.append(slot)
                if source['publish']:
                    published.append(slot)
            for root in module['resource_roots']:
                if root['scope'] != scope:
                    continue
                for path in sorted((project / module['root'] / root['path']).rglob('*.xml')):
                    unit = dict(path=str(path.relative_to(project)), hash=digest(path),
                                module=module['id'], scope=scope, world=context['world'])
                    resources.append(source_slot(unit, str(path)))
            for path in configurations:
                if not any(path.parent in pathlib.Path(inputs[slot]['input']).parents
                           for slot in selected):
                    continue
                unit = dict(path=str(path.relative_to(project)), hash=digest(path),
                            module='.', scope='JAVA_CONFIG', world=key('world', '.', 'JAVA_CONFIG'))
                resources.append(source_slot(unit, str(path)))
            for jar in context.pop('classpath'):
                if jar not in jar_slots:
                    jar_slots[jar] = len(jars)
                    jars.append(jar)
                classpath.append(jar_slots[jar])
            context.update(digest=manifest_hash, dependency_modules=module['dependencies'][scope],
                           lombok='model', spring=True, sofa=True, spring_xml=True,
                           source_indices=selected, publish_indices=published,
                           resource_indices=resources, classpath_indices=classpath)
            contexts.append(context)
    args.scratch.mkdir(parents=True, exist_ok=False)
    if args.cache.exists():
        raise ValueError('FRESH_DIAGNOSTIC_CACHE_REQUIRED')
    request = dict(version=7, full=True, compact_inputs=True, input_table=inputs,
                   classpath_table=jars, contexts=contexts, parallelism=1,
                   java_home=str(pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'),
                   scratch=str(args.scratch.resolve()), cache_root=str(args.cache.resolve()),
                   signature_cache=True, compiler_timeout_seconds=600,
                   compiler_heap_bytes=512 << 20, result_bytes=4 << 30, fact_limit=5000000)
    args.output.write_text(json.dumps(request) + '\n')
    print(json.dumps(dict(contexts=len(contexts), inputs=len(inputs),
                          request_bytes=args.output.stat().st_size)))


if __name__ == '__main__':
    main()
