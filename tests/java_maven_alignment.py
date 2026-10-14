#!/usr/bin/env python3
"""Maven Resolver semantics through the shipped lifecycle-stopping extension."""
import importlib.util
import json
import hashlib
import shutil
import pathlib
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('java_inputs_exporter', ROOT / 'scripts/export-java-inputs.py')
EXPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORTER)
NS = 'xmlns="http://maven.apache.org/POM/4.0.0"'


def dependency(name, version='1', extra=''):
    return f'<dependency><groupId>fixture.external</groupId><artifactId>{name}</artifactId><version>{version}</version>{extra}</dependency>'


class MavenAlignment(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='codeana-maven-alignment-')
        self.addCleanup(self.tmp.cleanup)
        self.project = pathlib.Path(self.tmp.name) / 'project'
        self.project.mkdir()
        self.repo = pathlib.Path(self.tmp.name) / 'repo'
        self.settings = pathlib.Path(self.tmp.name) / 'settings.xml'
        self.settings.write_text(f'<settings><localRepository>{self.repo}</localRepository></settings>')
        for name in ('comp', 'hidden', 'optional', 'provided', 'runtime', 'test', 'profile'):
            for version in ('1', '2') if name == 'comp' else ('1',):
                directory = self.repo / 'fixture/external' / name / version
                directory.mkdir(parents=True)
                (directory / f'{name}-{version}.pom').write_text(
                    f'<project {NS}><modelVersion>4.0.0</modelVersion><groupId>fixture.external</groupId>'
                    f'<artifactId>{name}</artifactId><version>{version}</version></project>')
                with zipfile.ZipFile(directory / f'{name}-{version}.jar', 'w') as jar:
                    jar.writestr('META-INF/fixture', name)
        self.write('pom.xml', f'''<project {NS}><modelVersion>4.0.0</modelVersion>
          <groupId>fixture.reactor</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
          <properties><maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target></properties>
          <modules><module>lib</module><module>app</module></modules>
          <profiles><profile><id>extra</id><dependencies>{dependency('profile')}</dependencies></profile></profiles>
          </project>''')
        self.write('lib/pom.xml', self.module('lib', '<dependencies>' + dependency('comp') + dependency('hidden')
                   + dependency('optional', extra='<optional>true</optional>')
                   + dependency('provided', extra='<scope>provided</scope>')
                   + dependency('runtime', extra='<scope>runtime</scope>') + '</dependencies>'))
        self.write('app/pom.xml', self.module('app', '''<dependencies>
          <dependency><groupId>fixture.reactor</groupId><artifactId>lib</artifactId><version>1</version>
            <exclusions><exclusion><groupId>fixture.external</groupId><artifactId>hidden</artifactId></exclusion></exclusions>
          </dependency>''' + dependency('comp', '2') + dependency('test', extra='<scope>test</scope>') + '''</dependencies>
          <build><directory>${project.basedir}/custom/out</directory><sourceDirectory>${project.basedir}/code</sourceDirectory>
            <testSourceDirectory>${project.basedir}/checks</testSourceDirectory>
            <plugins><plugin><groupId>missing.plugin</groupId><artifactId>lifecycle-must-never-run</artifactId><version>1</version>
              <executions><execution><phase>validate</phase><goals><goal>explode</goal></goals></execution></executions>
            </plugin></plugins></build>'''))
        self.write('app/code/A.java', 'class A {}')
        self.write('app/checks/Test.java', 'class Test {}')
        self.write('detached/pom.xml', '<invalid-and-unrelated/>')

    def module(self, artifact, body):
        return f'''<project {NS}><modelVersion>4.0.0</modelVersion><parent><groupId>fixture.reactor</groupId>
          <artifactId>parent</artifactId><version>1</version></parent><artifactId>{artifact}</artifactId>{body}</project>'''

    def write(self, path, data):
        target = self.project / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(data)
        return target

    def export(self, **options):
        output = self.project / ('out-' + str(len(list(self.project.glob('out-*')))) + '.json')
        return EXPORTER.export(self.project, output, 'mvn', settings=self.settings, offline=True, extension=ROOT / 'lang-java-adapotr/target/maven-input-worker.jar', **options)

    def app(self, result):
        return next(m for m in result['modules'] if m['id'] == 'app')

    def artifacts(self, module, scope):
        return {a['coordinate'] for a in module['classpath'][scope]}

    def test_mediation_custom_layout_and_no_lifecycle(self):
        result = self.export(profiles=('extra',))
        app = self.app(result)
        self.assertEqual(result['receipt']['maven_processes'], 1)
        self.assertEqual({m['root'] for m in result['modules']}, {'.', 'lib', 'app'})
        self.assertNotIn('detached/pom.xml', result['controls'])
        self.assertEqual(app['root'], 'app')
        self.assertEqual(app['dependencies'], {'MAIN': ['lib'], 'TEST': ['lib']})
        self.assertEqual(self.artifacts(app, 'MAIN'), {'fixture.external:comp:2', 'fixture.external:profile:1'})
        self.assertEqual(self.artifacts(app, 'TEST'), {'fixture.external:comp:2', 'fixture.external:profile:1',
                                                     'fixture.external:test:1', 'fixture.external:runtime:1'})
        self.assertEqual(app['source_roots'], [{'path': 'code', 'scope': 'MAIN', 'generated': False, 'generated_from': {}}, {'path': 'checks', 'scope': 'TEST', 'generated': False, 'generated_from': {}}])
        self.assertFalse(list(self.project.rglob('*.class')))
        self.assertFalse((self.project / 'app/custom/out').exists())

    def test_main_only_does_not_resolve_missing_test_artifact(self):
        pom = self.project / 'app/pom.xml'
        pom.write_text(pom.read_text().replace('<artifactId>test</artifactId>', '<artifactId>missing-test</artifactId>'))
        result = self.export(modules=('app',), scopes=('MAIN',))
        app = self.app(result)
        self.assertEqual(set(app['classpath']), {'MAIN'})
        self.assertFalse(next(m for m in result['modules'] if m['id'] == 'lib')['selected'])
        failed = self.export(modules=('app',), scopes=('MAIN', 'TEST'))
        self.assertEqual(failed['dependency_mode'], 'source_only_fallback')
        self.assertFalse(failed['resolved_classpath'])

    def test_selected_consumer_preserves_provider_own_provided_and_optional_classpath(self):
        result = self.export(modules=('app',), scopes=('MAIN',))
        lib = next(m for m in result['modules'] if m['id'] == 'lib')
        self.assertFalse(lib['selected'])
        self.assertTrue(lib['resolved_classpath'])
        self.assertEqual(self.artifacts(lib, 'MAIN'), {'fixture.external:comp:1',
            'fixture.external:hidden:1', 'fixture.external:optional:1', 'fixture.external:provided:1'})
        self.assertEqual(self.artifacts(self.app(result), 'MAIN'), {'fixture.external:comp:2'})
        self.assertEqual(result['receipt']['maven_processes'], 1)
        self.assertEqual(result['receipt']['prerequisite_classpath_rounds'], 1)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_prerequisite_does_not_resolve_its_missing_test_dependency(self):
        pom = self.project / 'lib/pom.xml'
        pom.write_text(pom.read_text().replace('</dependencies>',
            dependency('missing-provider-test', extra='<scope>test</scope>') + '</dependencies>'))
        result = self.export(modules=('app',), scopes=('TEST',))
        lib = next(m for m in result['modules'] if m['id'] == 'lib')
        self.assertTrue(lib['resolved_classpath'])
        self.assertEqual(lib['classpath']['TEST'], [])
        self.assertIn('fixture.external:provided:1', self.artifacts(lib, 'MAIN'))

    def test_unrelated_module_with_missing_external_dependency_is_not_resolved(self):
        pom = self.project / 'pom.xml'
        pom.write_text(pom.read_text().replace('</modules>', '<module>unused</module></modules>'))
        self.write('unused/pom.xml', self.module('unused', '<dependencies>' + dependency('missing-unused') + '</dependencies>'))
        result = self.export(modules=('app',), scopes=('MAIN',))
        unused = next(m for m in result['modules'] if m['id'] == 'unused')
        self.assertFalse(unused['selected']); self.assertFalse(unused['resolved_classpath'])
        self.assertEqual(unused['classpath']['MAIN'], [])

    def test_external_dependency_can_reveal_a_reactor_prerequisite_after_resolution(self):
        directory = self.repo / 'fixture/external/bridge/1'
        directory.mkdir(parents=True)
        (directory / 'bridge-1.pom').write_text(f'<project {NS}><modelVersion>4.0.0</modelVersion>'
            '<groupId>fixture.external</groupId><artifactId>bridge</artifactId><version>1</version>'
            '<dependencies><dependency><groupId>fixture.reactor</groupId><artifactId>lib</artifactId>'
            '<version>1</version></dependency></dependencies></project>')
        with zipfile.ZipFile(directory / 'bridge-1.jar', 'w') as jar: jar.writestr('META-INF/fixture', 'bridge')
        self.write('app/pom.xml', self.module('app', '<dependencies>' + dependency('bridge') + '</dependencies>'))
        result = self.export(modules=('app',), scopes=('MAIN',))
        self.assertEqual(self.app(result)['dependencies']['MAIN'], ['lib'])
        lib = next(m for m in result['modules'] if m['id'] == 'lib')
        self.assertTrue(lib['resolved_classpath'])
        self.assertIn('fixture.external:provided:1', self.artifacts(lib, 'MAIN'))
        self.assertEqual(result['receipt']['prerequisite_classpath_rounds'], 1)

    def test_provider_provided_reactor_dependency_requires_another_official_round(self):
        pom = self.project / 'pom.xml'
        pom.write_text(pom.read_text().replace('</modules>', '<module>helper</module></modules>'))
        self.write('helper/pom.xml', self.module('helper', '<dependencies>' + dependency('provided', extra='<scope>provided</scope>') + '</dependencies>'))
        pom = self.project / 'lib/pom.xml'
        pom.write_text(pom.read_text().replace('</dependencies>', '<dependency><groupId>fixture.reactor</groupId>'
            '<artifactId>helper</artifactId><version>1</version><scope>provided</scope></dependency></dependencies>'))
        result = self.export(modules=('app',), scopes=('MAIN',))
        self.assertEqual(self.app(result)['dependencies']['MAIN'], ['lib'])
        lib = next(m for m in result['modules'] if m['id'] == 'lib')
        helper = next(m for m in result['modules'] if m['id'] == 'helper')
        self.assertEqual(lib['dependencies']['MAIN'], ['helper'])
        self.assertTrue(helper['resolved_classpath'])
        self.assertEqual(self.artifacts(helper, 'MAIN'), {'fixture.external:provided:1'})
        self.assertEqual(result['receipt']['prerequisite_classpath_rounds'], 2)
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_dependency_failure_discards_all_successful_classpaths(self):
        # lib resolves first; a missing app archive must discard lib's successful jars.
        pom = self.project / 'app/pom.xml'
        pom.write_text(pom.read_text().replace('</dependencies>', dependency('missing-app') + '</dependencies>'))
        result = self.export(scopes=('MAIN',))
        self.assertEqual(result['dependency_mode'], 'source_only_fallback')
        self.assertEqual(result['dependency_failure']['module'], 'app')
        self.assertEqual(result['dependency_failure']['pom'], 'app/pom.xml')
        self.assertEqual(result['dependency_failure']['round'], 0)
        self.assertIn('missing-app', result['dependency_failure']['coordinate'])
        self.assertEqual(len(result['receipt']['dependency_failure_sha256']), 64)
        self.assertEqual(result['receipt']['inputs_sha256'], result['receipt']['metadata_sha256'])
        self.assertEqual(self.app(result)['dependencies']['MAIN'], ['lib'])
        for module in result['modules']:
            self.assertFalse(module['resolved_classpath'])
            self.assertTrue(all(not jars for jars in module['classpath'].values()))
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_prerequisite_failure_uses_verified_original_topology(self):
        # provided is invisible to the consumer, so failure occurs in round 1.
        (self.repo / 'fixture/external/provided/1/provided-1.jar').unlink()
        result = self.export(modules=('app',), scopes=('MAIN',))
        self.assertEqual(result['dependency_mode'], 'source_only_fallback')
        self.assertEqual(result['dependency_failure']['module'], 'lib')
        self.assertEqual(result['dependency_failure']['pom'], 'lib/pom.xml')
        self.assertEqual(result['dependency_failure']['round'], 1)
        self.assertEqual(result['receipt']['prerequisite_classpath_rounds'], 1)
        self.assertEqual(self.app(result)['dependencies']['MAIN'], ['lib'])
        self.assertTrue(all(not jars for m in result['modules'] for jars in m['classpath'].values()))
        self.assertEqual(result['receipt']['maven_processes'], 1)

    def test_model_failure_does_not_degrade_or_create_output(self):
        self.write('app/pom.xml', '<project>broken')
        before = set(self.project.glob('out-*'))
        with self.assertRaisesRegex(ValueError, 'MAVEN_INPUTS_FAILED'):
            self.export()
        self.assertEqual(set(self.project.glob('out-*')), before)

    def test_source_only_needs_no_external_archives(self):
        import shutil
        shutil.rmtree(self.repo)
        result = self.export(source_only=True, scopes=('MAIN',))
        self.assertFalse(result['resolved_classpath'])
        self.assertEqual(result['dependency_mode'], 'source_only_requested')
        self.assertIsNone(result['dependency_failure'])
        self.assertEqual(self.app(result)['dependencies']['MAIN'], ['lib'])
        self.assertEqual(self.app(result)['classpath']['MAIN'], [])

    def test_test_only_preserves_main_prerequisite_roots_and_scope_classpaths(self):
        result = self.export(modules=('app',), scopes=('TEST',))
        app = self.app(result)
        self.assertEqual(result['scopes'], ['TEST'])
        self.assertEqual(app['source_roots'], [{'path':'code','scope':'MAIN','generated':False,'generated_from':{}}, {'path':'checks','scope':'TEST','generated':False,'generated_from':{}}])
        self.assertEqual(app['dependencies'], {'MAIN':['lib'], 'TEST':['lib']})
        self.assertEqual(self.artifacts(app,'MAIN'), {'fixture.external:comp:2'})
        self.assertEqual(self.artifacts(app,'TEST'), {'fixture.external:comp:2','fixture.external:test:1','fixture.external:runtime:1'})
        self.assertFalse(next(m for m in result['modules'] if m['id']=='lib')['selected'])
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_generated_root_has_frozen_origin(self):
        self.write('app/custom/out/generated-sources/tool/Generated.java', 'class Generated {}')
        app = self.app(self.export(scopes=('MAIN',)))
        generated = [r for r in app['source_roots'] if r.get('generated')]
        self.assertEqual(len(generated), 1)
        self.assertEqual(generated[0]['path'], 'custom/out/generated-sources')
        self.assertIn('app/code/A.java', generated[0]['generated_from'])

    def test_reactor_classifier_is_rejected(self):
        pom = self.project / 'app/pom.xml'
        pom.write_text(pom.read_text().replace('<artifactId>lib</artifactId><version>1</version>',
                                             '<artifactId>lib</artifactId><version>1</version><classifier>tests</classifier>'))
        with self.assertRaisesRegex(ValueError, 'classifier/type'):
            self.export()

    def test_invalid_selection_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'MAVEN_MODULE_SELECTION'):
            self.export(modules=('detached',))
    def test_external_classifier_identity_and_interleaved_resolver_order(self):
        file = self.repo / 'fixture/external/comp/2/comp-2-tests.jar'
        with zipfile.ZipFile(file, 'w') as jar:
            jar.writestr('META-INF/fixture-tests', 'different classifier')
        pom = self.project / 'app/pom.xml'
        pom.write_text(pom.read_text().replace('</dependencies>', dependency('comp', '2', '<type>test-jar</type>') + '</dependencies>'))
        app = self.app(self.export(scopes=('MAIN',)))
        rows = app['classpath']['MAIN']
        self.assertEqual([(a['coordinate'], a['classifier'], a['extension']) for a in rows], [('fixture.external:comp:2', '', 'jar'), ('fixture.external:comp:2', 'tests', 'jar')])
        self.assertNotEqual(rows[0]['sha256'], rows[1]['sha256'])
        self.assertEqual(app['dependency_order']['MAIN'], [dict(kind='reactor', module='lib', coordinate='fixture.reactor:lib:1', classifier='', extension='jar'), dict(kind='classpath', index=0), dict(kind='classpath', index=1)])

    def test_direct_provided_and_runtime_scope_visibility(self):
        pom = self.project / 'app/pom.xml'
        pom.write_text(pom.read_text().replace('</dependencies>', dependency('provided', extra='<scope>provided</scope>') + dependency('runtime', extra='<scope>runtime</scope>') + '</dependencies>'))
        app = self.app(self.export())
        self.assertIn('fixture.external:provided:1', self.artifacts(app, 'MAIN'))
        self.assertNotIn('fixture.external:runtime:1', self.artifacts(app, 'MAIN'))
        self.assertIn('fixture.external:runtime:1', self.artifacts(app, 'TEST'))
        self.assertEqual(sum((a['coordinate'] == 'fixture.external:runtime:1' for a in app['classpath']['TEST'])), 1)

    def test_physical_module_path_is_independent_of_artifact_id(self):
        parent = self.project / 'pom.xml'
        parent.write_text(parent.read_text().replace('<module>lib</module>', '<module>physical/shared</module>'))
        target = self.project / 'physical/shared'
        target.parent.mkdir()
        shutil.move(str(self.project / 'lib'), target)
        pom = target / 'pom.xml'
        pom.write_text(pom.read_text().replace('</parent>', '<relativePath>../../pom.xml</relativePath></parent>'))
        result = self.export(scopes=('MAIN',))
        self.assertEqual(self.app(result)['dependencies']['MAIN'], ['physical/shared'])
        self.assertIn('physical/shared/pom.xml', result['controls'])
        lib = next((m for m in result['modules'] if m['root'] == 'physical/shared'))
        self.assertEqual(lib['coordinate'], 'fixture.reactor:lib:1')
        self.assertEqual(lib['physical_id'], 'physical/shared')

    def test_wide_deep_reactor_preserves_physical_inventory_without_lifecycle(self):
        width, depth = 32, 12
        root = self.project / 'pom.xml'
        root.write_text(root.read_text().replace('<module>lib</module><module>app</module>',
            ''.join(f'<module>physical/w{i}</module>' for i in range(width)) + '<module>deep</module>'))
        expected = {'.'}
        for i in range(width):
            path = f'physical/w{i}'
            self.write(path + '/pom.xml', self.module(f'artifact-{i}', '').replace(
                '</parent>', '<relativePath>../../pom.xml</relativePath></parent>'))
            self.write(path + '/src/main/java/A.java', f'package p{i}; class A {{}}')
            expected.add(path)
        path, parent = 'deep', 'parent'
        for level in range(depth):
            child = '<modules><module>child</module></modules>' if level + 1 < depth else ''
            self.write(path + '/pom.xml', f'''<project {NS}><modelVersion>4.0.0</modelVersion>
                <parent><groupId>fixture.reactor</groupId><artifactId>{parent}</artifactId>
                <version>1</version></parent><artifactId>level-{level}</artifactId>
                <packaging>pom</packaging>{child}</project>''')
            expected.add(path)
            path, parent = path + '/child', f'level-{level}'
        result = self.export(scopes=('MAIN',))
        self.assertEqual({m['physical_id'] for m in result['modules']}, expected)
        self.assertEqual(len(result['modules']), 1 + width + depth)
        self.assertEqual(result['receipt']['maven_processes'], 1)
        self.assertTrue(result['resolved_classpath'])
        self.assertEqual({key for key in result['controls'] if key.endswith('/pom.xml')},
                         {path + '/pom.xml' for path in expected if path != '.'})
        self.assertNotIn('detached/pom.xml', result['controls'])
        self.assertFalse(list(self.project.rglob('*.class')))

    def test_profile_settings_and_parent_controls_are_frozen(self):
        plain = self.export(scopes=('MAIN',))
        active = self.export(scopes=('MAIN',), profiles=('extra',))
        self.assertNotEqual(plain['active_profiles_sha256'], active['active_profiles_sha256'])
        self.assertNotEqual(plain['effective_model_sha256'], active['effective_model_sha256'])
        self.assertEqual(active['selected_profiles'], ['extra'])
        self.assertEqual(active['external_controls'][str(self.settings.resolve())], hashlib.sha256(self.settings.read_bytes()).hexdigest())
        self.assertEqual(active['controls']['pom.xml'], hashlib.sha256((self.project / 'pom.xml').read_bytes()).hexdigest())
        self.assertEqual(active['receipt']['maven_processes'], 1)

    def test_rust_exporter_rejects_existing_output_and_invalid_timeout(self):
        output = self.project / 'existing.json'
        output.write_text('preserve')
        with self.assertRaisesRegex(ValueError, 'output must not exist'):
            EXPORTER.export(self.project, output, 'mvn', settings=self.settings, offline=True)
        self.assertEqual(output.read_text(), 'preserve')
        with self.assertRaisesRegex(ValueError, 'timeout must be'):
            self.export(timeout=0)
        self.assertFalse(list(self.project.glob('out-*.json')))
        self.assertFalse(list(self.project.rglob('*.class')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
