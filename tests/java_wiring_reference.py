"""Compare Rust candidate derivation with the actual frozen Anatomist resolver."""
import hashlib
import json
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def projection(facts, directory):
    from java_fresh_reference import reference as verified_reference
    reference, proof = verified_reference()
    REFERENCE_SHA = proof['jar_sha256']
    java = pathlib.Path(os.environ.get('JAVA_HOME', pathlib.Path.home() / '.sdkman/candidates/java/25.3.4+1.r25-graal'))
    directory = pathlib.Path(directory)
    classes = directory / 'wiring-reference-classes'
    classes.mkdir(exist_ok=True)
    source = ROOT / 'tests/java-reference/WiringReferenceProbe.java'
    subprocess.run([str(java / 'bin/javac'), '-cp', str(reference), '-d', str(classes), str(source)], check=True, capture_output=True)
    by = {f['id']: f for f in facts}
    # Normalize compiler-world aliases before handing the raw graph to the
    # frozen resolver. Published source/provenance establishes identity;
    # the resolver itself still decides every candidate and confidence.
    published = {}
    hashes = {}
    for f in facts:
        if f['kind'] == 'declaration':
            published.setdefault((f['source'], f['owner']['world'], f['signature']), []).append(f)
        elif f['kind'] == 'coverage':
            hashes[(f['source'], f['owner']['world'])] = f['attributes'].get('input_hash')

    def canonical(f):
        origin = f['attributes'].get('source_origin')
        if f['kind'] != 'external_symbol' or not origin:
            return f
        key = (origin['path'], origin['world'], f['signature'])
        matches = published.get(key, [])
        if len(matches) == 1 and hashes.get(key[:2]) == origin['hash']:
            return matches[0]
        return f
    inputs = []
    product = []
    for f in facts:
        if f['kind'] != 'relation':
            continue
        span = json.dumps(f.get('span'), sort_keys=True, separators=(',', ':'))
        if f['relation'] == 'wires_possible':
            a = f['attributes']
            if a.get('projection') == 'declaring_type':
                continue  # This gate independently checks point-level resolver inputs.
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
        point, target = canonical(point), canonical(by[f['target']])
        inputs.append(dict(source=point['id'], target=target['id'], relation=relation,
                           external=point['kind'] != 'declaration' or target['kind'] != 'declaration',
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
