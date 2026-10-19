#!/usr/bin/env python3
"""Export Cargo's resolved inputs without executing build scripts or proc macros.

Run explicitly before indexing. Dependency source copies and generated inputs live
under the project, so the indexing worker only needs immutable captured bytes.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tomllib


def digest(data):
    return hashlib.sha256(data).hexdigest()


def identity(*parts):
    return digest(json.dumps(parts, separators=(',', ':')).encode())


def run(argv, cwd):
    result = subprocess.run(argv, cwd=cwd, capture_output=True, timeout=120, check=False)
    if result.returncode:
        code = 'RUST_INPUT_TOOL_CRASHED' if result.returncode < 0 else 'RUST_INPUT_EXPORT_COMMAND_FAILED'
        raise ValueError(f'{code}: {argv[0]} {argv[1]} (exit {result.returncode}); '+result.stderr.decode(errors='replace')[-4096:])
    if len(result.stdout) > 64 << 20:
        raise ValueError('RUST_INPUT_EXPORT_BUDGET')
    return result.stdout


def relative(root, path):
    return pathlib.Path(path).resolve().relative_to(root).as_posix()


def prepare(config, paths, worker=None):
    # Use the installed worker beside this script, or the stable development path.
    binary = pathlib.Path(worker) if worker else pathlib.Path(__file__).resolve().parent / 'codeana-rust-worker'
    if not binary.is_file():
        binary = pathlib.Path(__file__).resolve().parents[1] / 'target/debug/codeana-rust-worker'
    result = subprocess.run([str(binary), '--prepare-inputs'],
                            input=json.dumps(dict(config=config, paths=paths)).encode(),
                            capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError('RUST_INPUT_POLICY_INVALID: ' + result.stderr.decode(errors='replace')[-2000:])
    return json.loads(result.stdout)


def source_paths(directory):
    for path in directory.rglob('*.rs'):
        if any(p in ('target', '.git', 'node_modules', '.code-ana') for p in path.relative_to(directory).parts):
            continue
        yield path


def export(root, output, cargo='cargo', rustc='rustc', build_messages=None, cargo_configs=(), worker=None,
           entry_manifest=None, namespace=None, config_file=None):
    root = root.resolve()
    output = output.resolve()
    output.relative_to(root)
    native_cwd = (root / entry_manifest).resolve().parent if entry_manifest else root
    native_cwd.relative_to(root)
    config = tomllib.loads(pathlib.Path(config_file or root / '.code-ana/config.toml').read_text())
    language = config.get('languages', {}).get('rust', {})
    if not language.get('enabled'):
        raise ValueError('RUST_LANGUAGE_NOT_ENABLED')
    policy = prepare(config, [], worker)['policy']
    bundle = output.parent / (output.stem + '-sources')
    toolchain = run([rustc, '-vV'], native_cwd).decode().strip()
    host = next(line.split(': ', 1)[1] for line in toolchain.splitlines() if line.startswith('host: '))
    configured = [m for m in config.get('modules', []) if m.get('language') == 'rust']
    for analysis in [language.get('analysis', {})] + [m.get('analysis', {}) for m in configured]:
        if analysis.get('toolchain', 'auto') != 'auto':
            raise ValueError('RUST_TOOLCHAIN_ALIAS_UNSUPPORTED: select --cargo/--rustc explicitly and use toolchain=auto')
    variants, module_variants = {}, {}
    for module in configured:
        for world in module.get('worlds') or [{'id': 'default', 'target': 'host'}]:
            spec = dict(world)
            spec['target'] = host if spec.get('target', 'host') == 'host' else spec['target']
            variants[identity(spec)] = spec
            module_variants.setdefault(module['id'], set()).add(identity(spec))
    if not variants:
        variants[identity('default', host)] = {'id': 'default', 'target': host}
    if namespace:
        variants = {identity(namespace, key): value for key, value in variants.items()}
        module_variants = {name: {identity(namespace, key) for key in values} for name, values in module_variants.items()}
    if build_messages and len(variants) != 1:
        raise ValueError('RUST_BUILD_MESSAGES_WORLD_AMBIGUOUS: export one world with matching build messages')
    controls, crates, generated, dependency_sources, omitted = {}, [], {}, {}, {}
    selection = dict(candidate_crates=0, retained_crates=0, filtered_source_instances=0)
    builds = {}
    if build_messages:
        for line in pathlib.Path(build_messages).read_text().splitlines():
            message = json.loads(line)
            if message.get('reason') == 'build-script-executed':
                builds[message['package_id']] = message
    for variant_key, variant in sorted(variants.items()):
        args = [cargo] + [arg for value in cargo_configs for arg in ('--config', value)] + ['metadata', '--offline', '--locked', '--format-version', '1', '--filter-platform', variant['target']]
        if entry_manifest:
            args += ['--manifest-path', str(root / entry_manifest)]
        if not variant.get('default_features', True):
            args.append('--no-default-features')
        if variant.get('features'):
            args += ['--features', ','.join(variant['features'])]
        metadata = json.loads(run(args, native_cwd))
        if not metadata.get('resolve'):
            raise ValueError('RUST_RESOLVED_GRAPH_REQUIRED')
        nodes = {n['id']: n for n in metadata['resolve']['nodes']}
        members = set(metadata['workspace_members'])
        cfg = run([rustc, '--print', 'cfg', '--target', variant['target']], native_cwd).decode().splitlines()
        for requested in variant.get('cfg', []):
            name, _, value = requested.partition('=')
            if not re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', name) or ('=' in requested and not isinstance(json.loads(value), str)):
                raise ValueError('RUST_INVALID_CFG')
            if name.startswith('target_') or name in ('unix', 'windows', 'panic', 'test', 'feature'):
                raise ValueError('RUST_RESERVED_CFG: ' + name)
        entries, libs, candidates = {}, {}, []
        for package in sorted(metadata['packages'], key=lambda p: p['id']):
            own = package['id'] in members
            directory = pathlib.Path(package['manifest_path']).resolve().parent
            module = next((m for m in configured if (root / m['root']).resolve() == directory), None)
            selected = own and (bool(namespace) or not configured or module is not None and variant_key in module_variants[module['id']])
            if namespace and module is None and own:
                base = (root / entry_manifest).parent.resolve()
                module = dict(id=namespace if directory == base else namespace+'-'+identity(relative(root, directory))[:16], root=relative(root, directory))
            destination = directory if own else bundle / 'dependencies' / identity(package['id'])[:20]
            for target in package['targets']:
                if 'custom-build' in target['kind']:
                    continue
                library = any(k in ('lib', 'rlib', 'dylib', 'cdylib', 'staticlib', 'proc-macro') for k in target['kind'])
                if not own and not library:
                    continue
                if not set(target.get('required-features', [])).issubset(nodes[package['id']]['features']):
                    continue
                edition = (module or {}).get('analysis', {}).get('edition', language.get('analysis', {}).get('edition'))
                if selected and edition not in (None, 'auto', target['edition']):
                    raise ValueError('RUST_EDITION_MISMATCH: ' + package['name'])
                tests = [False, True] if own and target.get('test') and target['kind'][0] in ('lib', 'bin') else ['test' in target['kind'] or 'bench' in target['kind']]
                for test in tests:
                    crate_id = identity(package['id'], target['name'], target['kind'], variant_key, test)
                    source = pathlib.Path(target['src_path']).resolve()
                    source_relative = source.relative_to(directory)
                    mapped = destination / source_relative
                    entry = dict(id=crate_id, name=target['name'].replace('-', '_'),
                                 module=module['id'] if module else package['name'], world='rust-world-' + crate_id,
                                 scope='TEST' if test else 'MAIN', root=relative(root, mapped),
                                 directory=relative(root, destination), edition=target['edition'], target=variant['target'],
                                 cfg=sorted(set(cfg + variant.get('cfg', []) + ['feature="' + f + '"' for f in nodes[package['id']]['features']] + (['test'] if test else []))),
                                 dependencies={}, environment={}, publish=False,
                                 proc_macro='proc-macro' in target['kind'], gaps=['RUST_SYSROOT_MISSING'])
                    entries[crate_id] = (entry, package, directory, destination, own, module, test, library)
                    if library and not test:
                        libs[package['id']] = crate_id
                    if selected:
                        candidates.append((crate_id, dict(path=relative(root, source), scope=entry['scope'], module=entry['module'], dependency=False)))
        selection['candidate_crates'] += len(entries)
        selected_flags = prepare(config, [c for _, c in candidates], worker)['selected']
        roots = {cid for (cid, _), flag in zip(candidates, selected_flags) if flag}
        for cid, (entry, package, _, _, _, _, test, library) in entries.items():
            for dep in nodes[package['id']]['deps']:
                if any(d['kind'] is None or (test and d['kind'] == 'dev') for d in dep['dep_kinds']):
                    if dep['pkg'] in libs:
                        entry['dependencies'][dep['name']] = libs[dep['pkg']]
                    else:
                        entry['gaps'].append('RUST_DEPENDENCY_MISSING')
            if not library and package['id'] in libs:
                entry['dependencies'][package['name'].replace('-', '_')] = libs[package['id']]
        keep = roots | {d for cid in roots for d in entries[cid][0]['dependencies'].values()}
        sources, filtered = {}, set()
        for cid in sorted(keep):
            entry, package, directory, destination, own, module, _, _ = entries[cid]
            paths = list(source_paths(directory))
            requests = [dict(path=relative(root, p) if own else p.relative_to(directory).as_posix(),
                             scope=entry['scope'] if cid in roots else '', module=entry['module'] if own else None, dependency=not own) for p in paths]
            flags = prepare(config, requests, worker)['selected']
            selected_paths = []
            for source, flag in zip(paths, flags):
                if not flag:
                    selection['filtered_source_instances'] += 1
                    filtered.add(cid)
                    continue
                if source.is_symlink():
                    raise ValueError('RUST_SOURCE_SYMLINK')
                mapped = destination / source.relative_to(directory)
                selected_paths.append(relative(root, mapped))
                if not own:
                    data = source.read_bytes()
                    if len(data) > 8 << 20:
                        raise ValueError('RUST_DEPENDENCY_SOURCE_BUDGET')
                    mapped.parent.mkdir(parents=True, exist_ok=True)
                    mapped.write_bytes(data)
            sources[cid] = selected_paths
        keep = {cid for cid in keep if entries[cid][0]['root'] in sources[cid]}
        roots &= keep
        selection['retained_crates'] += len(keep)
        for cid in sorted(keep):
            entry, package, directory, _, own, _, _, _ = entries[cid]
            entry['publish'] = cid in roots
            removed = {name: target for name, target in entry['dependencies'].items() if target not in keep}
            if removed:
                omitted[cid] = removed
            entry['dependencies'] = {name: target for name, target in entry['dependencies'].items() if target in keep}
            if own:
                control = relative(root, package['manifest_path'])
                controls[control] = digest((root / control).read_bytes())
            env = {'CARGO_PKG_NAME': package['name'], 'CARGO_PKG_VERSION': package['version']}
            for component, value in zip(('MAJOR', 'MINOR', 'PATCH'), package['version'].split('.')):
                env['CARGO_PKG_VERSION_' + component] = value
            build_targets = [t for t in package['targets'] if 'custom-build' in t['kind']]
            if build_targets:
                if own:
                    control = relative(root, build_targets[0]['src_path'])
                    controls[control] = digest((root / control).read_bytes())
                build = builds.get(package['id'])
                if build is None:
                    entry['gaps'].append('RUST_BUILD_OUTPUT_MISSING')
                else:
                    entry['cfg'] = sorted(set(entry['cfg'] + build.get('cfgs', [])))
                    env.update(build.get('env', []))
                    out_dir = pathlib.Path(build['out_dir']).resolve()
                    destination = bundle / 'generated' / identity(package['id'])[:20]
                    paths = list(source_paths(out_dir))
                    flags = prepare(config, [dict(path=relative(root, destination / p.relative_to(out_dir)) if own else p.relative_to(out_dir).as_posix(),
                                                     scope=entry['scope'] if cid in roots else '', module=entry['module'] if own else None, dependency=not own) for p in paths], worker)['selected']
                    for source, flag in zip(paths, flags):
                        if not flag:
                            selection['filtered_source_instances'] += 1
                            filtered.add(cid)
                            continue
                        if source.is_symlink():
                            raise ValueError('RUST_GENERATED_SYMLINK')
                        data = source.read_bytes()
                        if len(data) > 8 << 20:
                            raise ValueError('RUST_GENERATED_SOURCE_BUDGET')
                        copied = destination / source.relative_to(out_dir)
                        copied.parent.mkdir(parents=True, exist_ok=True)
                        copied.write_bytes(data)
                        path = relative(root, copied)
                        generated[path] = digest(data)
                        sources[cid].append(path)
                    env['OUT_DIR'] = relative(root, destination)
            entry['environment'] = env
            if cid in roots:
                relevant = {cid} | set(entry['dependencies'].values())
                if relevant & set(omitted) or any(d not in keep for d in entries[cid][0]['dependencies'].values()):
                    entry['gaps'].append('RUST_DEPENDENCY_DEPTH_LIMIT')
                if (relevant | set(removed.values())) & filtered:
                    entry['gaps'].append('RUST_INPUT_FILTERED')
            else:
                dependency_sources[cid] = sorted(set(sources[cid]))
            crates.append(entry)
        # A direct dependency may be visited after its consumer above.
        for entry in crates:
            if entry['publish'] and ({entry['id']} | set(entry['dependencies'].values())) & set(omitted):
                entry['gaps'] = sorted(set(entry['gaps'] + ['RUST_DEPENDENCY_DEPTH_LIMIT']))
    if not any(c['publish'] for c in crates):
        raise ValueError('RUST_NO_PROJECT_SOURCE: all project targets were filtered')
    for name in ('Cargo.toml', 'Cargo.lock', 'rust-toolchain.toml', 'rust-toolchain'):
        path = root / name
        if path.is_file():
            controls[name] = digest(path.read_bytes())
        if entry_manifest:
            for directory in ((root / entry_manifest).parent, pathlib.Path(metadata['workspace_root'])):
                path = directory / name
                if path.is_file():
                    controls[relative(root, path)] = digest(path.read_bytes())
    result = dict(schema_version=2, toolchain=toolchain, policy=policy, crates=crates,
                  controls=controls, generated=generated, dependency_sources=dependency_sources,
                  omitted_dependencies=omitted, selection=selection)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=pathlib.Path, default=pathlib.Path('.'))
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--cargo', default='cargo')
    parser.add_argument('--rustc', default='rustc')
    parser.add_argument('--build-messages', type=pathlib.Path)
    parser.add_argument('--cargo-config', action='append', default=[])
    parser.add_argument('--worker', type=pathlib.Path)
    parser.add_argument('--entry-manifest')
    parser.add_argument('--namespace')
    parser.add_argument('--config-file', type=pathlib.Path)
    options = parser.parse_args()
    project = options.project.resolve()
    result = export(project, options.output or project / '.code-ana/rust-inputs.json', options.cargo, options.rustc, options.build_messages, options.cargo_config, options.worker,
                    options.entry_manifest, options.namespace, options.config_file)
    print(json.dumps({'crates': len(result['crates']), 'schema_version': result['schema_version']}))
