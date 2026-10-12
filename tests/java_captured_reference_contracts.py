"""Small independent frozen-source contracts behind calibrated product assertions."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from java_fresh_reference import reference
import java_lombok_alignment as lombok_alignment
import java_model_tests as models
JDK8, JDK25 = models.JDK8, models.JDK25
from java_supplemental_signature_alignment import SUPER_LIBRARY, SUPER_CONSUMER

ROOT = Path(__file__).resolve().parents[1]


def objects(value):
    if isinstance(value, str) and value.startswith(('{', '[')):
        yield from objects(json.loads(value))
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)
    elif isinstance(value, dict):
        yield value
        for child in value.values():
            if isinstance(child, (dict, list)):
                yield from objects(child)


class FrozenCapturedContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jar, cls.proof = reference()
        cls.workspace = tempfile.TemporaryDirectory(prefix='codeana-reference-contracts-')
        cls.addClassCleanup(cls.workspace.cleanup)
        cls.classes = Path(cls.workspace.name)/'classes'
        cls.classes.mkdir()
        subprocess.run([str(JDK25/'bin/javac'), '-cp', str(cls.jar), '-d', str(cls.classes),
                        str(ROOT/'tests/java-migration/ParityWitness.java')],
                       check=True, capture_output=True)

    def setUp(self):
        self.fixture = models.Models()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        (self.fixture.root/'lombok.config').write_text(
            'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n')

    def observe(self, contexts):
        root = self.fixture.root
        request = root/'request.json'
        request.write_text(json.dumps(dict(contexts=contexts)))
        output = root/'reference-dump'
        self.output = output
        subprocess.run([str(JDK25/'bin/java'), '-cp', str(self.classes)+os.pathsep+str(self.jar),
                        'ParityWitness', str(root), str(JDK8), str(root/'cache'), 'ast',
                        'reference', str(output), str(request)],
                       check=True, capture_output=True, timeout=180)
        edges = [row for row in objects(json.loads((output/'facts.edges.json').read_text()))
                 if row.get('relation') == 'CALLS']
        diagnostics = json.loads((output/'raw.diagnostics.json').read_text())
        return edges, diagnostics

    def test_super_builder_keeps_two_javac_bindings_and_six_candidates(self):
        files = lombok_alignment.Lombok.files(self.fixture, SUPER_LIBRARY)
        files['lombok/experimental/SuperBuilder.java'] = (
            'package lombok.experimental;public @interface SuperBuilder {'
            'String builderMethodName() default "builder";String buildMethodName() default "build";'
            'boolean toBuilder() default false;String setterPrefix() default "";}')
        main = self.fixture.context('app', files, prerequisite=True)
        client = self.fixture.context('app', {'Use.java': SUPER_CONSUMER}, scope='TEST')
        client['sources']=[dict(row,scope='TEST',world='app-test') for row in client['sources']]
        client['sources'] = [dict(row, publish=False) for row in main['sources']] + client['sources']
        edges, _ = self.observe([client, main])
        confirmed = [edge for edge in edges if edge.get('confidence') == 'EXTRACTED']
        candidates = [edge for edge in edges if edge.get('confidence') == 'INFERRED']
        self.assertEqual(len(confirmed), 2)
        self.assertEqual(len(candidates), 6)
        self.assertEqual(len(edges), 8)
        self.assertTrue(any('asList' in str(edge) for edge in confirmed))
        self.assertTrue(any('Leaf' in str(edge) for edge in confirmed))

    def test_unicode_annotation_name_matches_the_frozen_source_limitation(self):
        source = ('package p;import java.lang.annotation.*;'
                  '@Target(ElementType.FIELD) @interface Note {float value();String text();}'
                  'class Annotated {@\\u004eote(value=0.1F,text="原字节😀") int marked;}')
        context = self.fixture.context('app', {'Note.java': source})
        self.observe([context])
        annotations = list(objects(json.loads((self.output/'facts.annotations.json').read_text())))
        selected = [row for row in annotations if row.get('annotationFqn') == 'p.Note']
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]['rawName'], 'eote')
        self.assertEqual(selected[0]['resolutionStatus'], 'exact')
        self.assertIn('\\u004eote', source)

    def test_auxiliary_primitive_candidate_does_not_require_compiler_error(self):
        main=self.fixture.context('app',lombok_alignment.Lombok.files(
            self.fixture,'package p; @lombok.Getter class Api {Integer delay;}'),prerequisite=True)
        client=self.fixture.context('app',{'Use.java':'''package p; /* 😀中 */ class Use {void read(Api api){
            java.util.Optional.ofNullable(api).map(Api::getDelay)
            .map(value -> Math.min(value,10)*1000).ifPresent(value -> {
                try{Thread.sleep(value);}catch(InterruptedException error){}
            });}}'''},scope='TEST')
        client['sources']=[dict(row,scope='TEST',world='app-test') for row in client['sources']]
        client['sources']=[dict(row,publish=False) for row in main['sources']]+client['sources']
        edges,diagnostics=self.observe([client,main])
        self.assertTrue(any('java.lang.Math' in str(edge) and 'min' in str(edge) for edge in edges))
        self.assertEqual(diagnostics,[])

    def test_selected_provider_abi_does_not_require_consumer_error(self):
        artifact = Path.home()/'.m2/repository/org/projectlombok/lombok/1.18.6/lombok-1.18.6.jar'
        self.assertTrue(artifact.is_file(), 'fixture Lombok archive required')
        library = self.fixture.context('lib', {
            'Api.java': 'package p;import lombok.Getter;@Getter public class Api {String name;}'},
            classpath=[str(artifact)], prerequisite=True)
        client = self.fixture.context('use', {
            'Use.java': 'package p;class Use {int read(Api api){return api.getName().length();}}'},
            dependency_modules=['lib'])
        client['sources'] = [dict(row, publish=False) for row in library['sources']] + client['sources']
        edges, diagnostics = self.observe([client, library])
        self.assertTrue(any('getName' in str(edge) for edge in edges))
        self.assertEqual(diagnostics, [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
