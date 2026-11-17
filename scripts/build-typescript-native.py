#!/usr/bin/env python3
"""Build a new TypeScript SDK from locked sources; never install over a runtime."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import time

from sdk_manifest import validate_manifest

ROOT = Path(__file__).resolve().parents[1]
ADAPTOR = ROOT/'lang-typescript-adaptor'
spec = importlib.util.spec_from_file_location('baseline', ROOT/'scripts/multilang-baseline.py')
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
sha = baseline.sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Fixed upstream checkout to clone, never modify')
    parser.add_argument('--go-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New absolute SDK directory')
    parser.add_argument('--evidence', type=Path, required=True, help='New directory under target')
    parser.add_argument('--prepare-only', action='store_true', help='Validate sources, generate bindings and test Go packages; do not create an SDK')
    args = parser.parse_args()
    if not args.prepare_only and not (ADAPTOR/'overlay/bridge').is_dir():
        parser.error('production bridge is not present; --prepare-only validates the current component foundations')
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('only macOS ARM64 has a locked toolchain')
    if not args.output.is_absolute() or args.output.exists():
        parser.error('output must be a new absolute directory')
    evidence = args.evidence.resolve()
    if not evidence.is_relative_to(ROOT/'target'):
        parser.error('evidence must be under target')
    output = args.output.resolve()
    for original in (args.source.resolve(), args.go_home.resolve()):
        if evidence.is_relative_to(original) or output.is_relative_to(original):
            parser.error('build destinations cannot modify the original source or Go toolchain')
    evidence.mkdir(parents=True, exist_ok=False)
    lock = json.loads((ADAPTOR/'upstream.lock.json').read_text())
    production = json.loads((ADAPTOR/'production.lock.json').read_text())
    env = dict(os.environ, GOTOOLCHAIN='local', CGO_ENABLED='1', GOWORK='off', GOENV='off', GOFLAGS='',
               GOOS='darwin', GOARCH='arm64', CGO_CFLAGS='-O2 -g', CGO_LDFLAGS='-O2 -g')
    env['PATH'] = str(Path(sys.executable).parent)+os.pathsep+env['PATH']
    receipt = dict(stage='T2 production SDK build', status='failed', commands=[],
                   native_accepted=False, complete_plan=False, performance_accepted=False)

    def command(name, argv, cwd=ROOT, timeout=300):
        log = evidence/(name+'.log')
        entry = dict(name=name, argv=list(map(str, argv)), cwd=str(cwd), log=log.name)
        receipt['commands'].append(entry)
        started = time.monotonic()
        with log.open('wb') as stream:
            process = subprocess.Popen(entry['argv'], cwd=cwd, env=env, stdout=stream,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            try:
                process.wait(timeout=timeout)
            finally:
                baseline.signal_group(process.pid, signal.SIGKILL)
                entry.update(exit_code=process.wait(), seconds=time.monotonic()-started, sha256=sha(log))
        if entry['exit_code']:
            raise RuntimeError(f'{name} failed; see {log}')
        return log.read_text()

    try:
        if sha(ADAPTOR/'upstream.lock.json') != production['upstream_lock_sha256']:
            raise ValueError('UPSTREAM_LOCK_DRIFT')
        go_home = args.go_home.resolve()
        env['GOROOT'] = str(go_home)
        for path, digest in lock['go']['tools_sha256'].items():
            if sha(go_home/path) != digest:
                raise ValueError('GO_TOOLCHAIN_DIGEST')
        go = go_home/'bin/go'
        if command('go-version', [go, 'version']).strip() != lock['go']['version']:
            raise ValueError('GO_TOOLCHAIN_VERSION')
        env['CC'] = command('clang-path', ['xcrun', '--find', 'clang']).strip()
        if command('clang-version', [env['CC'], '--version']).strip() != lock['c_compiler']['version']:
            raise ValueError('C_COMPILER_VERSION')
        env['SDKROOT'] = command('sdk-path', ['xcrun', '--show-sdk-path']).strip()
        if command('sdk-version', ['xcrun', '--show-sdk-version']).strip() != lock['macos_sdk']['version']:
            raise ValueError('MACOS_SDK_VERSION')
        if sha(Path(env['SDKROOT'])/'SDKSettings.json') != lock['macos_sdk']['settings_sha256']:
            raise ValueError('MACOS_SDK_DIGEST')
        protoc = Path(shutil.which('protoc')).resolve()
        if sha(protoc) != production['protoc']['sha256'] or command('protoc-version', [protoc, '--version']).strip() != production['protoc']['version']:
            raise ValueError('PROTOC_IDENTITY')
        source = evidence/'upstream'
        command('clone', ['git', 'clone', '--shared', '--no-checkout', args.source.resolve(), source])
        command('checkout', ['git', 'checkout', '--detach', lock['commit']], source)
        submodule = source/'_submodules/TypeScript'
        command('clone-typescript', ['git', 'clone', '--shared', '--no-checkout', args.source.resolve()/'_submodules/TypeScript', submodule])
        command('checkout-typescript', ['git', 'checkout', '--detach', lock['typescript']['commit']], submodule)
        if lock['typescript']['commit'] not in command('gitlink', ['git', 'ls-tree', 'HEAD', '_submodules/TypeScript'], source):
            raise ValueError('TYPESCRIPT_GITLINK')
        for path, digest in lock['source_sha256'].items():
            if sha(source/path) != digest:
                raise ValueError('UPSTREAM_SOURCE_DIGEST: '+path)
        command('standard-library', [go, 'generate', './internal/bundled'], source)
        for path, digest in lock['standard_library']['files'].items():
            if sha(source/path) != digest:
                raise ValueError('STANDARD_LIBRARY_DIGEST: '+path)
        module_file = evidence/'production.mod'
        module_file.write_bytes((source/'go.mod').read_bytes())
        (evidence/'production.sum').write_bytes((source/'go.sum').read_bytes())
        dependency_licenses = []
        for dependency in production['dependencies']:
            version = dependency['module']+'@'+dependency['version']
            command('module-edit', [go, 'mod', 'edit', '-modfile='+str(module_file), '-require='+version], source)
            downloaded = json.loads(command('module-download', [go, 'mod', 'download', '-modfile='+str(module_file), '-json', version], source))
            if downloaded['Sum'] != dependency['sum'] or downloaded['GoModSum'] != dependency['go_mod_sum'] or sha(Path(downloaded['Dir'])/dependency['license']) != dependency['license_sha256']:
                raise ValueError('PROTOBUF_DEPENDENCY_IDENTITY')
            dependency_licenses.append((dependency['module'].replace('/', '-')+'-LICENSE', Path(downloaded['Dir'])/dependency['license']))
        tools = evidence/'tools'
        env['GOBIN'] = str(tools)
        generator = production['generator']
        command('generator-install', [go, 'install', generator['module']+'@'+generator['version']], source)
        overlay = source/'codeana'
        shutil.copytree(ADAPTOR/'overlay', overlay)
        mappings = production['generated_packages']
        command('generate-protobuf', [protoc, '-I'+str(ROOT/'proto'),
                '--plugin=protoc-gen-go='+str(tools/'protoc-gen-go'), '--go_out='+str(source),
                '--go_opt=module=github.com/microsoft/typescript-go',
                *['--go_opt=M'+path+'='+package for path, package in mappings.items()], *mappings], source)
        if command('format', [go_home/'bin/gofmt', '-l', overlay]).strip():
            raise ValueError('GO_FORMAT')
        model = (ROOT/'crates/model/src/lib.rs').read_text()
        graph_model = int(re.search(r'pub const VERSION: u32 = (\d+)', model)[1])
        worker = int(re.search(r'pub const WORKER_PROTOCOL_VERSION: u32 = (\d+)', model)[1])
        storage = (ROOT/'crates/storage/src/graph_store.rs').read_text()
        schema = int(re.search(r'pub const SOURCE_SCHEMA_VERSION: i64 = (\d+)', storage)[1])
        stdlib = {'standard-library/'+Path(path).name: digest for path, digest in lock['standard_library']['files'].items() if path.endswith('.d.ts')}
        stdlib_digest = hashlib.sha256(json.dumps(stdlib, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        compiler_version = re.search(r'var version = "([^"]+)"', (source/'internal/core/version.go').read_text())[1]
        adapter = f'typescript-go-{lock["commit"][:12]}-codeana-graph{graph_model}-tsabi1'
        values = dict(Adapter=adapter, UpstreamCommit=lock['commit'], TypeScriptCommit=lock['typescript']['commit'],
                      StandardLibraryDigest=stdlib_digest,
                      AnalysisProtoSHA256=sha(ROOT/'proto/codeana/typescript/v1/analysis.proto'),
                      GraphProtoSHA256=sha(ROOT/'proto/codeana/v1/graph.proto'),
                      WorkerProtocol=str(worker), GraphModel=str(graph_model), GraphSchema=str(schema))
        ldflags = ' '.join('-X github.com/microsoft/typescript-go/codeana/engine.'+key+'='+value for key,value in values.items())
        parameters = ['-modfile='+str(module_file), '-mod=readonly', '-trimpath', '-ldflags='+ldflags]
        command('go-tests', [go, 'test', '-v', *parameters, './codeana/...'], source, timeout=600)
        if args.prepare_only:
            receipt.update(status='prepared', sdk_created=False,
                           source_files={str(p.relative_to(source)):sha(p) for p in overlay.rglob('*') if p.is_file()},
                           go_mod_sha256=sha(module_file), go_sum_sha256=sha(evidence/'production.sum'))
            return 0
        output.mkdir(parents=True, exist_ok=False)
        library = output/'libcodeana_typescript.dylib'
        command('go-build', [go, 'build', *parameters, '-buildmode=c-shared', '-o', library, './codeana/bridge'], source, timeout=600)
        shutil.copy2(ADAPTOR/'include/codeana_ts.h', output/'codeana_ts.h')
        check = evidence/'header-check.c'
        check.write_text(f'#include "{output}/codeana_ts.h"\n#include "{library.with_suffix(".h")}"\n')
        command('headers', [env['CC'], '-isysroot', env['SDKROOT'], '-Werror', '-fsyntax-only', check])
        command('symbols', ['nm', '-gU', library])
        command('dependencies', ['otool', '-L', library])
        for path in stdlib:
            destination = output/path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source/'internal/bundled/libs'/destination.name, destination)
        licenses = output/'licenses';licenses.mkdir()
        shutil.copy2(source/'LICENSE', licenses/'typescript-go-LICENSE')
        shutil.copy2(submodule/'LICENSE.txt', licenses/'TypeScript-LICENSE')
        shutil.copy2(go_home/'LICENSE', licenses/'Go-LICENSE')
        for name, path in dependency_licenses:
            shutil.copy2(path, licenses/name)
        for path in ('upstream.lock.json', 'production.lock.json'):
            shutil.copy2(ADAPTOR/path, output/path)
        for path in mappings:
            destination = output/path;destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT/'proto'/path, destination)
        receipt.update(status='compiled', source_files={str(p.relative_to(source)):sha(p) for p in overlay.rglob('*') if p.is_file()},
                       go_mod_sha256=sha(module_file), go_sum_sha256=sha(evidence/'production.sum'), runtime_values=values)
        (output/'build-provenance.json').write_text(json.dumps(receipt, indent=2)+'\n')
        files = {str(p.relative_to(output)):sha(p) for p in output.rglob('*') if p.is_file()}
        manifest = dict(version=4, worker_protocol=worker, fact_ir=graph_model, adapter=adapter, graph_schema=schema,
                        graph_encoding='GRA1-protobuf-row-v1', os='Darwin', arch='arm64', files=files,
                        backend=dict(kind='typescript', abi=1, analysis_request=1, upstream_commit=lock['commit'],
                            typescript_commit=lock['typescript']['commit'], compiler_version=compiler_version,
                            analysis_proto_sha256=values['AnalysisProtoSHA256'], graph_proto_sha256=values['GraphProtoSHA256'],
                            go_version=lock['go']['distribution']['version'],
                            build_options=['-mod=readonly', '-trimpath', '-buildmode=c-shared', '-ldflags='+ldflags],
                            standard_library=stdlib, header_sha256=sha(output/'codeana_ts.h')))
        validate_manifest(manifest)
        (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        receipt.update(status='built', sdk=str(output), sdk_files={**files, 'manifest.json':sha(output/'manifest.json')})
    except Exception as failure:
        receipt.update(status='failed', failure=str(failure), failure_type=type(failure).__name__)
        raise
    finally:
        (evidence/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
