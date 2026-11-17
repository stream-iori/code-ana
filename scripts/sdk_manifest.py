"""Current SDK metadata contract; no legacy shapes or engine defaults."""
import json
import math
import pathlib
import re

VERSION = 4
MAX_BYTES = 1 << 20


def _keys(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        raise ValueError('SDK_MANIFEST_INVALID: rebuild SDK for the current format')


def _positive(value):
    if type(value) is not int or not 0 < value <= 0xffffffff:
        raise ValueError('SDK_MANIFEST_INVALID: positive version required')


def _digest(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def validate_manifest(manifest):
    _keys(manifest, ('version', 'worker_protocol', 'fact_ir', 'adapter', 'graph_schema',
                     'graph_encoding', 'os', 'arch', 'files', 'backend'), ('producer_metadata',))
    if type(manifest['version']) is not int or manifest['version'] != VERSION:
        raise ValueError('SDK_MANIFEST_VERSION: rebuild SDK')
    for name in ('worker_protocol', 'fact_ir', 'graph_schema'):
        _positive(manifest[name])
    if manifest['graph_encoding'] != 'GRA1-protobuf-row-v1':
        raise ValueError('SDK_GRAPH_ENCODING_INVALID')
    for name in ('adapter', 'os', 'arch'):
        if not isinstance(manifest[name], str) or not manifest[name]:
            raise ValueError('SDK_MANIFEST_INVALID: '+name)
    if not isinstance(manifest['files'], dict) or not manifest['files']:
        raise ValueError('SDK_FILES_REQUIRED')
    for name, digest in manifest['files'].items():
        if not isinstance(name, str):
            raise ValueError('SDK_FILE_IDENTITY_INVALID')
        path = pathlib.PurePosixPath(name)
        if not name or not path.parts or name.startswith('./') or '\0' in name or path.is_absolute() or '..' in path.parts or '\\' in name or not _digest(digest):
            raise ValueError('SDK_FILE_IDENTITY_INVALID: '+name)
    backend = manifest['backend']
    if not isinstance(backend, dict):
        raise ValueError('SDK_BACKEND_INVALID: rebuild SDK')
    kind = backend.get('kind')
    if kind == 'java':
        _keys(backend, ('kind', 'analysis_request', 'compiler_batch_protocol', 'java_model',
                        'target_java', 'compiler_bridges', 'runtime'))
        for key in ('analysis_request', 'compiler_batch_protocol'):
            _positive(backend[key])
        if not isinstance(backend['java_model'], str) or not backend['java_model']:
            raise ValueError('SDK_JAVA_MODEL_REQUIRED')
        if backend['target_java'] != [8, 17] or backend['compiler_bridges'] != {
                '8': 'compiler-bridge.jar', '17': 'compiler-bridge17.jar'}:
            raise ValueError('SDK_JAVA_BRIDGES_INVALID')
        runtime = backend['runtime']
        if not isinstance(runtime, dict):
            raise ValueError('SDK_JAVA_RUNTIME_REQUIRED')
        if runtime.get('kind') == 'native':
            _keys(runtime, ('kind', 'abi', 'gc', 'build_mode'))
            _positive(runtime['abi'])
            if runtime['gc'] not in ('G1', 'serial'):
                raise ValueError('SDK_JAVA_GC_INVALID')
            if not isinstance(runtime['build_mode'], str) or not runtime['build_mode']:
                raise ValueError('SDK_BUILD_MODE_REQUIRED')
        elif runtime.get('kind') == 'jvm':
            _keys(runtime, ('kind',))
        else:
            raise ValueError('SDK_JAVA_RUNTIME_INVALID')
    elif kind == 'typescript':
        _keys(backend, ('kind', 'abi', 'analysis_request', 'upstream_commit', 'go_version',
                        'build_options', 'standard_library', 'header_sha256'))
        for key in ('abi', 'analysis_request'):
            _positive(backend[key])
        if not isinstance(backend['upstream_commit'], str) or not re.fullmatch('[0-9a-f]{40}', backend['upstream_commit']):
            raise ValueError('SDK_UPSTREAM_INVALID')
        if not isinstance(backend['go_version'], str) or not backend['go_version']:
            raise ValueError('SDK_GO_VERSION_REQUIRED')
        if not isinstance(backend['build_options'], list) or not backend['build_options'] or any(not isinstance(v, str) or not v for v in backend['build_options']):
            raise ValueError('SDK_BUILD_OPTIONS_REQUIRED')
        if not isinstance(backend['standard_library'], dict) or not backend['standard_library'] or not all(isinstance(k, str) and k and _digest(v) for k, v in backend['standard_library'].items()):
            raise ValueError('SDK_STANDARD_LIBRARY_INVALID')
        if not _digest(backend['header_sha256']):
            raise ValueError('SDK_HEADER_INVALID')
    else:
        raise ValueError('SDK_BACKEND_INVALID')
    if manifest.get('producer_metadata') is not None and not isinstance(manifest['producer_metadata'], dict):
        raise ValueError('SDK_PRODUCER_METADATA_INVALID')
    return manifest


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('SDK_MANIFEST_DUPLICATE_FIELD: '+key)
        result[key] = value
    return result


def load_manifest(path):
    with pathlib.Path(path).open('rb') as source:
        data = source.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError('SDK_MANIFEST_BUDGET')
    def invalid_constant(value):
        raise ValueError('SDK_MANIFEST_INVALID_NUMBER: '+value)
    def finite(value):
        number = float(value)
        return number if math.isfinite(number) else invalid_constant(value)
    value = json.loads(data, object_pairs_hook=_unique,
                       parse_constant=invalid_constant, parse_float=finite)
    nodes = 0
    def check(item, depth):
        nonlocal nodes
        nodes += 1
        if isinstance(item, dict):
            nodes += len(item)
        if nodes > 65536 or depth > 128:
            raise ValueError('SDK_JSON_BUDGET')
        if isinstance(item, dict):
            for child in item.values():
                check(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                check(child, depth + 1)
    check(value, 1)
    return validate_manifest(value)


def java(manifest, runtime=None):
    validate_manifest(manifest)
    identity = manifest['backend']
    if identity['kind'] != 'java' or (runtime is not None and identity['runtime']['kind'] != runtime):
        raise ValueError('SDK_BACKEND_MISMATCH')
    return identity
