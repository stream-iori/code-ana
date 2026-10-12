"""Independent frozen protocol overlay: change allocation admission, never model rules."""
import hashlib
import json
import pathlib
import subprocess

DEFAULT_BYTES = 512 << 20
MAXIMUM_BYTES = 2 << 30
SOURCE = 'anatomist-core/src/main/java/com/anatomist/core/javac/JavacProtocol.java'
PROPERTY = 'codeana.javac.decode.bytes'


def validate_budget(value):
    if type(value) is not int or not (65536 <= value <= MAXIMUM_BYTES):
        raise ValueError('JAVA_MODEL_DECODE_BYTES_RANGE')
    return value


def adapted_source(text):
    needle = 'new JavacDecodeBudget(JavacDecodeBudget.DEFAULT_BYTES)'
    marker = '    static final int MAGIC'
    if text.count(needle) != 1 or text.count(marker) != 1:
        raise ValueError('FROZEN_DECODE_POLICY_ADMISSION_POINT')
    method = '''    private static long resourceDecodeBudget() {
        long bytes = Long.getLong("codeana.javac.decode.bytes", JavacDecodeBudget.DEFAULT_BYTES);
        if (bytes < (64L << 10) || bytes > (2L << 30))
            throw new IllegalArgumentException("JAVA_MODEL_DECODE_BYTES_RANGE");
        return bytes;
    }

'''
    return text.replace(needle, 'new JavacDecodeBudget(resourceDecodeBudget())').replace(marker, method + marker)


def prepare_reference_budget(jar, proof, host, directory, budget_bytes):
    validate_budget(budget_bytes)
    original = jar.parent / 'source' / SOURCE
    data = original.read_bytes()
    original_sha = hashlib.sha256(data).hexdigest()
    if proof['sources'].get(SOURCE) != original_sha:
        raise ValueError('FROZEN_DECODE_POLICY_SOURCE_DRIFT')
    directory.mkdir(exist_ok=False)
    source = directory / 'source/com/anatomist/core/javac/JavacProtocol.java'
    source.parent.mkdir(parents=True)
    source.write_text(adapted_source(data.decode()))
    classes = directory / 'classes'
    classes.mkdir()
    command = [str(host / 'bin/javac'), '-cp', str(jar), '-d', str(classes), str(source)]
    with (directory / 'compile.log').open('wb') as output:
        subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=120)
    manifest = dict(policy='frozen-protocol-decode-admission-only-v1',
                    default_bytes=DEFAULT_BYTES, configured_bytes=budget_bytes,
                    property=PROPERTY, original_source=SOURCE, original_sha256=original_sha,
                    adapted_source=str(source),
                    manifest_path=str(directory / 'manifest.json'),
                    adapted_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                    oracle_jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                    classpath=str(classes),
                    classes={str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in sorted(classes.rglob('*.class'))})
    if not manifest['classes']:
        raise ValueError('FROZEN_DECODE_POLICY_CLASSES_MISSING')
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest
