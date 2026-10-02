#!/usr/bin/env python3
"""Task-owned archive and bounded UTF-8 encoding fault checks."""
import os
import json
import pathlib
import random
import subprocess
import tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = pathlib.Path(os.environ.get('JAVA_HOME', pathlib.Path.home()/'.sdkman/candidates/java/25.3.4+1.r25-graalce'))
dependencies = (ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                'dev.codeana.analysis.extension.OverlaySharingProbe'], check=True, timeout=60)

for mode in ('sink', 'spring', 'sofa'):
    subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                    str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                    'dev.codeana.analysis.extension.EmptyOutputProbe', mode], check=True, timeout=30)

subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                'dev.codeana.analysis.extension.CallHeaderProbe'], check=True, timeout=60)

subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                'dev.codeana.analysis.extension.FrameworkHeaderProbe'], check=True, timeout=60)

subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                'dev.codeana.analysis.extension.IdentitySelectionProbe'], check=True, timeout=60)

with tempfile.TemporaryDirectory(prefix='codeana-fact-header-test-') as root:
    jdk8 = pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME', pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu'))
    bridge = ROOT/'lang-adaptor/target/compiler-bridge.jar'
    subprocess.run([str(jdk8/'bin/javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', str(bridge), '-d', root,
                    str(ROOT/'lang-adaptor/src/test/java/dev/codeana/analysis/FactHeaderArchiveProbe.java'),
                    str(ROOT/'lang-adaptor/src/test/java/dev/codeana/analysis/JsonStringProbe.java'),
                    str(ROOT/'lang-adaptor/src/test/java/dev/codeana/analysis/JsonFactProjectionProbe.java'),
                    str(ROOT/'lang-adaptor/src/test/java/dev/codeana/analysis/EncodedOutputProbe.java')],
                   check=True, timeout=30)
    subprocess.run([str(jdk8/'bin/java'), '-Xmx32m', '-cp', os.pathsep.join([root, str(bridge)]),
                    'dev.codeana.analysis.FactHeaderArchiveProbe', root], check=True, timeout=60)
    subprocess.run([str(jdk8/'bin/java'), '-Xmx32m', '-cp', os.pathsep.join([root, str(bridge)]),
                    'dev.codeana.analysis.EncodedOutputProbe', root], check=True, timeout=60)
    import hashlib, struct
    expected = hashlib.sha256(); expected.update(b'{"facts":[')
    with (pathlib.Path(root)/'copy.bin').open('rb') as stream:
        assert stream.read(4) == bytes.fromhex('43414631')
        count = 0
        while prefix := stream.read(4):
            length = struct.unpack('>I', prefix)[0]
            if count: expected.update(b',')
            body = stream.read(length); assert len(body) == length
            expected.update(body); count += 1
    expected.update(b']}'); actual = hashlib.sha256()
    with (pathlib.Path(root)/'encoded-output.json').open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''): actual.update(chunk)
    assert count == 6000 and actual.digest() == expected.digest()
    atoms = [chr(i) for i in range(128)] + ['汉', '😀', 'é', '€', '𐐀', '\uFEFF']
    randomizer = random.Random(7319)
    values = [''.join(randomizer.choices(atoms, k=randomizer.randrange(200))) for _ in range(5000)]
    values += ['plain' * 200000, 'plain\\quoted"\n😀汉' * 20000, ''.join(map(chr, range(32))), '']
    inputs, outputs = pathlib.Path(root)/'strings-input.json', pathlib.Path(root)/'strings-output.json'
    inputs.write_bytes(json.dumps(values, ensure_ascii=True, separators=(',', ':')).encode())
    subprocess.run([str(jdk8/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([root, str(bridge)]),
                    'dev.codeana.analysis.JsonStringProbe', str(inputs), str(outputs)], check=True, timeout=60)
    assert outputs.read_bytes() == json.dumps(values, ensure_ascii=False, separators=(',', ':')).encode('UTF-8')
    facts, expected = [], []
    for index in range(1500):
        fact = dict(id=f'id{index}', signature=values[index], kind='call_target', source='中文/Api.java',
                    parent=f'id{index-1}', target=f'id{index+1}', relation='java.bean' if index%3 == 0 else '',
                    owner=dict(world=f'world{index%3}', unused=values[index+1500]),
                    attributes=dict(generated=index%2 == 0, receiver_type=values[index%17],
                                    binding=dict(text=values[index+3000], numbers=[0, -1, 1.25, 1e30],
                                                 graph=[dict(values=values[index:index+5])])))
        facts.append(fact)
        expected.append({**{key: fact[key] for key in ('id', 'signature', 'kind', 'source', 'parent', 'target')},
                         'world': fact['owner']['world'], 'generated': fact['attributes']['generated'],
                         'receiver_type': fact['attributes']['receiver_type']})
    inputs.write_bytes(json.dumps(facts, ensure_ascii=True, separators=(',', ':')).encode())
    subprocess.run([str(jdk8/'bin/java'), '-Xmx128m', '-cp', os.pathsep.join([root, str(bridge)]),
                    'dev.codeana.analysis.JsonFactProjectionProbe', str(inputs), str(outputs)], check=True, timeout=60)
    assert json.loads(outputs.read_bytes()) == expected

with tempfile.TemporaryDirectory(prefix='codeana-archive-test-') as root:
    subprocess.run([str(JDK/'bin/java'), '-cp', os.pathsep.join(str(ROOT/'lang-adaptor/target'/p)
                    for p in ('test-classes', 'classes')), 'dev.codeana.analysis.RecordArchiveProbe', root],
                   check=True, timeout=30)

with tempfile.TemporaryDirectory(prefix='codeana-lazy-rows-test-') as root:
    subprocess.run([str(JDK/'bin/java'), '-cp', os.pathsep.join(str(ROOT/'lang-adaptor/target'/p)
                    for p in ('test-classes', 'classes')), 'dev.codeana.analysis.ArchiveRowsProbe', root],
                   check=True, timeout=30)

with tempfile.TemporaryDirectory(prefix='codeana-type-substitution-test-') as root:
    subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join(str(ROOT/'lang-adaptor/target'/p)
                    for p in ('test-classes', 'classes')), 'dev.codeana.analysis.TypeSubstitutionProbe', root],
                   check=True, timeout=30)

with tempfile.TemporaryDirectory(prefix='codeana-syntax-partition-test-') as root:
    subprocess.run([str(JDK/'bin/java'), '-Xmx128m', '-cp', os.pathsep.join(str(ROOT/'lang-adaptor/target'/p)
                    for p in ('test-classes', 'classes')), 'dev.codeana.analysis.SyntaxFactsProbe', root],
                   check=True, timeout=30)

with tempfile.TemporaryDirectory(prefix='codeana-reflection-archive-test-') as root:
    jdk8 = pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME', pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu'))
    dependencies = (ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
    subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                    str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                    'dev.codeana.analysis.ReflectionArchiveProbe', root, str(jdk8)], check=True, timeout=60)

with tempfile.TemporaryDirectory(prefix='codeana-extension-archive-test-') as root:
    dependencies = (ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
    subprocess.run([str(JDK/'bin/java'), '-Xmx128m', '-cp', os.pathsep.join([
                    str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                    'dev.codeana.analysis.ExtensionArchiveProbe', root], check=True, timeout=180)

with tempfile.TemporaryDirectory(prefix='codeana-extension-lookup-test-') as root:
    dependencies = (ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
    subprocess.run([str(JDK/'bin/java'), '-Xmx128m', '-cp', os.pathsep.join([
                    str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                    'dev.codeana.analysis.extension.LookupArchiveProbe', root], check=True, timeout=180)

subprocess.run([str(JDK/'bin/java'), '-Xmx64m', '-cp', os.pathsep.join([
                str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                'dev.codeana.analysis.extension.ProjectStageProbe'], check=True, timeout=30)

subprocess.run([str(JDK/'bin/java'), '-Xmx128m', '-cp', os.pathsep.join([
                str(ROOT/'lang-adaptor/target/test-classes'), str(ROOT/'lang-adaptor/target/classes'), dependencies]),
                'dev.codeana.analysis.AnnotationPathProbe'], check=True, timeout=30)
