"""Compare Rust candidate derivation with the actual frozen Anatomist resolver."""
import hashlib
import json
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
REFERENCE_SHA = '457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e'


def projection(facts, directory):
    reference = ROOT.parent / 'anatomist/target/anatomist.jar'
    if not reference.exists():
        reference = ROOT / 'target/java-alignment/anatomist/target/anatomist.jar'
    if hashlib.sha256(reference.read_bytes()).hexdigest() != REFERENCE_SHA:
        raise ValueError('WIRING_REFERENCE_ARTIFACT_DRIFT')
    java = pathlib.Path(os.environ.get('JAVA_HOME', pathlib.Path.home() / '.sdkman/candidates/java/25.3.4+1.r25-graalce'))
    directory = pathlib.Path(directory)
    classes = directory / 'wiring-reference-classes'
    classes.mkdir(exist_ok=True)
    source = ROOT / 'lang-adaptor/src/frozen-test/java/dev/codeana/analysis/WiringReferenceProbe.java'
    subprocess.run([str(java / 'bin/javac'), '-cp', str(reference), '-d', str(classes), str(source)], check=True, capture_output=True)
    by = {f['id']: f for f in facts}
    inputs = []
    product = []
    for f in facts:
        if f['kind'] != 'relation':
            continue
        span = json.dumps(f.get('span'), sort_keys=True, separators=(',', ':'))
        if f['relation'] == 'wires_possible':
            a = f['attributes']
            product.append(dict(source=f['parent'], target=f['target'], file=f['source'], span=span,
                                confidence=a['confidence'].upper(),
                                metadata=dict(via=a['via'], source=a['source'], candidates=a['candidates'])))
            continue
        if f['relation'] == 'implements':
            point = by[f['parent']]
            relation = 'IMPLEMENTS'
        elif f['relation'] == 'injects_possible':
            registration = by[f['parent']]
            if registration['relation'] != 'java.spring.injection':
                continue
            point = by[registration['parent']]
            relation = 'INJECTS'
        else:
            continue
        inputs.append(dict(source=point['id'], target=f['target'], relation=relation,
                           external=point['kind'] != 'declaration' or by[f['target']]['kind'] != 'declaration',
                           file=f['source'], span=span))
    request = directory / 'wiring-reference-input.json'
    request.write_text(json.dumps(inputs))
    result = subprocess.run([str(java / 'bin/java'), '-cp', os.pathsep.join([str(classes), str(reference)]),
                             'dev.codeana.analysis.WiringReferenceProbe', str(request)],
                            check=True, capture_output=True, text=True, timeout=30)
    expected = json.loads(result.stdout)
    order = lambda row: json.dumps(row, sort_keys=True)
    product, expected = sorted(product, key=order), sorted(expected, key=order)
    (directory / 'wiring-reference-diff.json').write_text(json.dumps(dict(
        reference_sha=REFERENCE_SHA, product=product, reference=expected, accepted=product == expected), indent=2))
    return product, expected
