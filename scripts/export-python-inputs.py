#!/usr/bin/env python3
"""Freeze an explicit Python inventory without importing or installing targets."""
import argparse
import hashlib
import json
import os
import pathlib

SKIP = {'.venv', 'venv', '.git', '.code-ana', '__pycache__', '.mypy_cache', '.pytest_cache', '.ruff_cache', 'target', 'node_modules'}


def strings(value, field):
    if not isinstance(value, list) or any(not isinstance(item, str) or not item for item in value):
        raise ValueError('PYTHON_METADATA_FIELD: '+field)
    return value


def table(value, field):
    if not isinstance(value, dict):
        raise ValueError('PYTHON_METADATA_FIELD: '+field)
    return value


def project_metadata(project, path, extras, scope, explicit_sources):
    try:
        import tomllib
    except ImportError as error:
        raise ValueError('PYTHON_METADATA_REQUIRES_PYTHON_311') from error
    relative = pathlib.PurePath(path)
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('PYTHON_CONTROL_PATH')
    source = project
    for component in relative.parts:
        source = source/component
        if source.is_symlink():
            raise ValueError('PYTHON_CONTROL_SYMLINK')
    with source.open('rb') as stream:
        data = stream.read((8 << 20)+1)
    if len(data) > 8 << 20:
        raise ValueError('PYTHON_CONTROL_BUDGET')
    config = tomllib.loads(data.decode('utf-8'))
    definition = table(config.get('project', {}), 'project')
    for field in ('name', 'requires-python'):
        if field in definition and not isinstance(definition[field], str):
            raise ValueError('PYTHON_METADATA_FIELD: '+field)
    dependencies = strings(definition.get('dependencies', []), 'dependencies').copy()
    optional = table(definition.get('optional-dependencies', {}), 'optional-dependencies')
    for extra in extras:
        if extra not in optional:
            raise ValueError('PYTHON_UNKNOWN_EXTRA: '+extra)
        dependencies.extend(strings(optional[extra], 'optional-dependencies.'+extra))
    dynamic = strings(definition.get('dynamic', []), 'dynamic')
    backend = table(config.get('build-system', {}), 'build-system').get('build-backend')
    if backend is not None and not isinstance(backend, str):
        raise ValueError('PYTHON_METADATA_FIELD: build-backend')
    warnings = ['dependency versions and requires-python constraint are not verified']
    if dynamic:
        warnings.append('dynamic metadata not evaluated: '+', '.join(dynamic))
    tool = table(config.get('tool', {}), 'tool')
    setuptools = table(tool.get('setuptools', {}), 'tool.setuptools')
    supported = backend in (None, 'setuptools.build_meta', 'setuptools.build_meta:__legacy__')
    inferred = []
    named_layout = False
    if supported:
        package_dir = table(setuptools.get('package-dir', {}), 'package-dir')
        if any(not isinstance(p, str) for p in package_dir.values()):
            raise ValueError('PYTHON_METADATA_FIELD: package-dir')
        named_layout = any(package_dir)
        if named_layout:
            warnings.append('named package-dir mappings require explicit source roots')
        inferred = [p or '.' for p in package_dir.values()]
        packages = setuptools.get('packages', {})
        if isinstance(packages, dict):
            find = table(packages.get('find', {}), 'packages.find')
            if 'where' in find:
                inferred = strings(find['where'], 'packages.find.where')
        elif not isinstance(packages, list):
            raise ValueError('PYTHON_METADATA_FIELD: packages')
    else:
        warnings.append('unsupported build backend: '+backend+'; use explicit source roots')
    if named_layout:
        inferred = []
    if not explicit_sources and inferred and scope == 'TEST':
        pytest = table(tool.get('pytest', {}), 'tool.pytest')
        options = table(pytest.get('ini_options', {}), 'tool.pytest.ini_options')
        paths = options.get('pythonpath', [])
        if isinstance(paths, str):
            paths = paths.split()
        inferred = strings(paths, 'pytest.pythonpath')+inferred
    metadata = dict(name=definition.get('name'), requires_python=definition.get('requires-python'),
                    dependencies=dependencies, selected_extras=extras, dynamic=dynamic, build_backend=backend,
                    layout_source='explicit' if explicit_sources else 'setuptools', warnings=warnings)
    control = dict(path=relative.as_posix(), sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
    return inferred, metadata, [control]


def inventory(directory):
    if directory.is_symlink():
        raise ValueError(f'PYTHON_ROOT_SYMLINK: {directory}')
    files = []
    for parent, directories, names in os.walk(directory, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in SKIP)
        for name in directories:
            if (pathlib.Path(parent) / name).is_symlink():
                raise ValueError(f'PYTHON_SOURCE_SYMLINK: {parent}/{name}')
        for name in sorted(names):
            if not (name.endswith(('.py', '.pyi')) or name == 'py.typed'):
                continue
            source = pathlib.Path(parent) / name
            if source.is_symlink():
                raise ValueError(f'PYTHON_SOURCE_SYMLINK: {source}')
            with source.open('rb') as stream:
                data = stream.read((8 << 20) + 1)
            if len(data) > 8 << 20:
                raise ValueError(f'PYTHON_SOURCE_BUDGET: {source}')
            files.append(dict(path=source.relative_to(directory).as_posix(), sha256=hashlib.sha256(data).hexdigest(), bytes=len(data)))
            if len(files) > 100_000:
                raise ValueError('PYTHON_INPUT_BUDGET')
    return sorted(files, key=lambda item: item['path'])


def export(project, sources, dependencies, version='3.12', platform='darwin', *, pyproject=None, extras=(), scope='MAIN'):
    project = pathlib.Path(project).resolve(strict=True)
    if (version, platform) != ('3.12', 'darwin'):
        raise ValueError('PYTHON_TARGET_UNSUPPORTED')
    if scope not in ('MAIN', 'TEST'):
        raise ValueError('PYTHON_SCOPE')
    sources = list(sources)
    extras = list(dict.fromkeys(extras))
    metadata, controls = None, []
    if pyproject is not None:
        inferred, metadata, controls = project_metadata(project, pyproject, extras, scope, bool(sources))
        if not sources:
            sources = list(dict.fromkeys(inferred))
    elif extras:
        raise ValueError('PYTHON_EXTRA_REQUIRES_PYPROJECT')
    roots = []
    for publish, paths in [(True, sources), (False, dependencies)]:
        for index, value in enumerate(paths):
            path = project / value
            if path.is_symlink():
                raise ValueError(f'PYTHON_ROOT_SYMLINK: {path}')
            path = path.resolve(strict=True)
            if not path.is_dir():
                raise ValueError(f'PYTHON_ROOT_NOT_DIRECTORY: {path}')
            declared = path.relative_to(project).as_posix() if publish else str(path)
            roots.append(dict(id=f'{"source" if publish else "dependency"}-{index}', path=declared, publish=publish, files=inventory(path)))
    if not sources:
        raise ValueError('PYTHON_SOURCE_ROOT_REQUIRED')
    return dict(schema_version=2, roots=roots, controls=controls, metadata=metadata,
                contexts=[dict(id=scope.lower(), module='python', scope=scope, python_version=version, platform=platform, roots=[root['id'] for root in roots])])


def prepare(request):
    """Language provider for the Rust preparation service; never executes targets."""
    project = pathlib.Path(request['project']).resolve(strict=True)
    config = request['config']
    entries = {e['id']: e for e in config['inputs']['entries'] if e['language'] == 'python'}
    members = request['members']
    scopes = config.get('scan', {}).get('scopes') or ['MAIN']
    scopes = [s for s in scopes if s in ('MAIN', 'TEST')]
    if not scopes:
        raise ValueError('PYTHON_SCOPE_REQUIRED')
    options = [entries[m]['python'] for m in members]
    for option in options[1:]:
        if any(option.get(k) != options[0].get(k) for k in ('python_version', 'platform', 'dependency_roots')):
            raise ValueError('PYTHON_SHARED_ENVIRONMENT_CONFLICT')
    reachable = []
    def visit(name):
        if name in reachable:
            return
        reachable.append(name)
        for dependency in entries[name]['python'].get('dependencies', []):
            visit(dependency)
    for member in members:
        visit(member)
    roots, controls, metadata, per_entry, gaps = [], {}, {}, {}, []
    physical_owners = {}
    for name in reachable:
        entry = entries[name]
        directory = project / entry['root']
        module = next((m for m in config.get('modules', []) if m['language'] == 'python' and
                       (project / m['root']).resolve() == directory.resolve()), None)
        owner = module['id'] if module else name
        explicit = (module or {}).get('source_roots', [])
        manifest = entry.get('manifest')
        if manifest is None and (directory / 'pyproject.toml').is_file():
            manifest = 'pyproject.toml'
        per_entry[name] = {}
        for scope in dict.fromkeys(['MAIN', *scopes]):
            inferred = []
            if manifest is not None:
                inferred, meta, captured = project_metadata(directory, manifest,
                    entry['python'].get('extras', []), scope, bool(explicit))
                metadata[owner] = meta
                for control in captured:
                    control['path'] = (pathlib.Path(entry['root']) / control['path']).as_posix()
                    controls[control['path']] = control
            elif entry['python'].get('extras'):
                raise ValueError('PYTHON_EXTRA_REQUIRES_PYPROJECT')
            paths = [r['path'] for r in explicit if r['scope'] == 'MAIN' or scope == 'TEST' and r['scope'] == 'TEST'] if explicit else inferred
            selected = []
            for value in dict.fromkeys(paths):
                relative = (pathlib.Path(entry['root']) / value).as_posix()
                path = project / relative
                if not path.is_dir() or any(p.is_symlink() for p in [path, *list(path.parents)[:len(path.relative_to(project).parts)]]):
                    raise ValueError('PYTHON_SOURCE_ROOT_INVALID: '+relative)
                path.resolve().relative_to(project)
                root_id = name + '-' + hashlib.sha256(relative.encode()).hexdigest()[:16]
                if not any(r['id'] == root_id for r in roots):
                    files = inventory(path)
                    for file in files:
                        physical = (path / file['path']).resolve()
                        if physical in physical_owners and physical_owners[physical] != owner:
                            raise ValueError('PYTHON_SOURCE_OWNER_CONFLICT')
                        physical_owners[physical] = owner
                    roots.append(dict(id=root_id, path=relative, publish=True, module=owner, files=files))
                selected.append(root_id)
            per_entry[name][scope] = selected
    for index, value in enumerate(options[0].get('dependency_roots', [])):
        path = pathlib.Path(value)
        if not path.is_absolute():
            path = project / path
        if not path.exists():
            gaps.append('PYTHON_DEPENDENCY_ROOT_MISSING')
            continue
        if path.is_symlink():
            raise ValueError('PYTHON_ROOT_SYMLINK')
        roots.append(dict(id='dependency-'+hashlib.sha256(str(path).encode()).hexdigest()[:20],
                          path=str(path.resolve()), publish=False, files=inventory(path)))
    contexts = []
    for scope in scopes:
        published = list(dict.fromkeys(r for name in members for r in per_entry[name][scope]))
        visible = list(dict.fromkeys(r for name in reachable for r in per_entry[name][scope if name in members else 'MAIN' if 'MAIN' in per_entry[name] else scope]))
        visible += [r['id'] for r in roots if not r['publish']]
        if not published:
            raise ValueError('PYTHON_SOURCE_ROOT_REQUIRED')
        contexts.append(dict(id='context-'+hashlib.sha256(json.dumps([members, scope]).encode()).hexdigest()[:20],
                             module=members[0], scope=scope, python_version=options[0]['python_version'],
                             platform=options[0]['platform'], roots=visible, publish_roots=published, gaps=sorted(set(gaps))))
    used = {r for c in contexts for r in c['roots']}
    return dict(schema_version=3, roots=[r for r in roots if r['id'] in used], contexts=contexts,
                controls=list(controls.values()), metadata=None, module_metadata=metadata)


def main():
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == '--prepare-request':
        request = json.loads(pathlib.Path(sys.argv[2]).read_text())
        result = prepare(request)
        pathlib.Path(request['output']).write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=pathlib.Path, required=True)
    parser.add_argument('--source-root', action='append', default=[])
    parser.add_argument('--dependency-root', action='append', default=[])
    parser.add_argument('--python-version', required=True)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--pyproject', help='Optional project-relative TOML; no upward search or code execution')
    parser.add_argument('--extra', action='append', default=[])
    parser.add_argument('--scope', choices=['MAIN', 'TEST'], default='MAIN')
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('.code-ana/python-inputs.json'))
    args = parser.parse_args()
    result = export(args.project, args.source_root, args.dependency_root, args.python_version, args.platform,
                    pyproject=args.pyproject, extras=args.extra, scope=args.scope)
    output = args.project / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(output)


if __name__ == '__main__':
    main()
