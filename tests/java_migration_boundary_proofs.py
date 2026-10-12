"""Read semantic expectations from a newly built, unmodified frozen upstream JAR."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

from java_fresh_reference import reference
from java_model_tests import JDK8, JDK25, Models
from java_lombok_alignment import Lombok

ROOT = Path(__file__).resolve().parents[1]


def declarations(rows):
    for value in rows:
        if isinstance(value, str) and value.startswith(('{', '[')):
            yield from declarations([json.loads(value)])
        elif isinstance(value, list):
            yield from declarations(value)
        elif isinstance(value, dict):
            if 'symbol' in value and 'signature' in value:
                yield value
            for child in value.values():
                if isinstance(child, (list, dict)):
                    yield from declarations([child])


def main():
    jar, proof = reference()
    output = ROOT / 'target/implementation-evidence/frozen-boundary-proof'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='codeana-frozen-boundary-') as temporary:
        work = Path(temporary)
        classes = work / 'classes'
        classes.mkdir()
        subprocess.run([str(JDK25 / 'bin/javac'), '-cp', str(jar), '-d', str(classes),
                        str(ROOT / 'tests/java-migration/ParityWitness.java')], check=True)
        fixture = Models()
        fixture.setUp()
        try:
            jars = []
            for number in (1, 2):
                source = work / ('external' + str(number)) / 'Constants.java'
                source.parent.mkdir()
                source.write_text('package ext; public class Constants {public static final int VALUE=' + str(number) + ';}')
                binary = source.parent / 'classes'
                binary.mkdir()
                subprocess.run([str(JDK8 / 'bin/javac'), '-proc:none', '-d', str(binary), str(source)], check=True)
                artifact = source.parent / 'constants.jar'
                subprocess.run([str(JDK8 / 'bin/jar'), 'cf', str(artifact), '-C', str(binary), '.'], check=True)
                jars.append(str(artifact))
            library = fixture.context('lib', {'p/Lib.java': 'package p; public class Lib {public static final int VALUE=ext.Constants.VALUE;}'}, classpath=[jars[0]])
            client = fixture.context('app', {'p/Use.java': 'package p; class Use {static final int VALUE=Lib.VALUE;}'}, classpath=[jars[1]], dependency_modules=['lib'])
            client['sources'] = [dict(row, publish=False) for row in library['sources']] + client['sources']
            lombok = fixture.context('lombok', Lombok.files(fixture, '@lombok.Builder class Built {@validation.NotBlank(message="required") String value;}') | {
                'validation/NotBlank.java': 'package validation; public @interface NotBlank {String message() default "required";}'})
            results = {}
            for name, contexts, mode in [('maven-producer-abi', [client, library], 'off'), ('lombok-declaration-annotations', [lombok], 'ast')]:
                request = work / (name + '.json')
                request.write_text(json.dumps(dict(contexts=contexts)))
                dump = output / name
                result = subprocess.run([str(JDK25 / 'bin/java'), '-cp', str(classes) + os.pathsep + str(jar),
                    'ParityWitness', str(fixture.root), str(JDK8), str(work / (name + '-cache')), mode, 'reference', str(dump), str(request)],
                    capture_output=True, text=True, timeout=120)
                if result.returncode:
                    raise RuntimeError(result.stderr)
                rows = list(declarations(json.loads((dump / 'raw.declarations.json').read_text())))
                wanted = {'p.Use#VALUE', 'p.Lib#VALUE'} if mode == 'off' else {'Built.BuiltBuilder#value', 'Built#value'}
                results[name] = [row for row in rows if row['symbol']['kind'] == 'FIELD' and row['symbol']['owner'] + '#' + row['symbol']['name'] in wanted]
            assert results['maven-producer-abi'], 'FROZEN_CONSTANT_WITNESS_MISSING'
            assert results['lombok-declaration-annotations'], 'FROZEN_BUILDER_WITNESS_MISSING'
            assert {r['symbol']['owner']: r['constant']['value'] for r in results['maven-producer-abi']} == {'p.Lib': 1, 'p.Use': 1}
            generated = next(r for r in results['lombok-declaration-annotations'] if r['origin'] == 'MODEL')
            assert not any(a['descriptor'] == 'Lvalidation/NotBlank;' for a in generated['annotations'])
            receipt = dict(reference_commit=proof['commit'], reference_sha256=proof['jar_sha256'], results=results)
            (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
            print(json.dumps(receipt))
        finally:
            fixture.doCleanups()


if __name__ == '__main__':
    main()
