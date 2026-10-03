#!/usr/bin/env python3
"""Written extension annotations without a dependency jar; original javac errors survive."""
import pathlib
import subprocess
import zipfile
import unittest
import java_model_tests as models
import java_lombok_alignment as lombok


class WrittenAnnotations(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def fixture(self, annotation='@lombok.Getter', imported='', field='String name;'):
        context = self.context('app', {'Api.java': imported + '\n' + annotation + ' class Api {' + field + '}',
                                      'Use.java': 'class Use {String read(Api api){return api.getName();}}'}, lombok='model')
        return lombok.Lombok.controls(self, context, {'app/lombok.config':
            'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'})

    def generated_targets(self, facts):
        by = {fact['id']: fact for fact in facts}
        return [fact for fact in facts if fact['kind'] == 'call_target'
                and by[fact['target']]['signature'] == 'Api#getName()']

    def test_qualified_missing_annotation_recovers_without_confirming_javac(self):
        for annotation, imported in [('@lombok.Getter', '')]:
            with self.subTest(annotation=annotation):
                facts = self.analyze([self.fixture(annotation, imported)])['facts']
                targets = self.generated_targets(facts)
                self.assertEqual(len(targets), 1)
                self.assertEqual(targets[0]['status'], 'possible')
                self.assertFalse(targets[0]['evidence_complete'])
                self.assertTrue([f for f in facts if f['kind']=='diagnostic' and f['status']=='ERROR'])
                original = [f for f in facts if f['relation']=='java.annotation' and f['source']=='app/Api.java' and not f['attributes'].get('generated')]
                self.assertEqual(len(original), 1)
                self.assertFalse(original[0]['attributes']['complete'])
                self.assertEqual(original[0]['attributes']['resolved_type'], 'Getter' if imported else 'lombok.Getter')

    def test_missing_wildcard_ambiguous_import_and_unqualified_spelling_do_not_guess(self):
        for imported in ['', 'import lombok.Getter;', 'import lombok.*;', 'import lombok.Getter;import other.Getter;',
                         'import static lombok.Getter;']:
            with self.subTest(imported=imported):
                self.assertFalse(self.generated_targets(self.analyze([self.fixture(annotation='@Getter', imported=imported)])['facts']))

    def test_known_other_annotation_does_not_become_lombok(self):
        context = self.fixture(annotation='@Getter', imported='')
        other = self.context('other', {'Getter.java': '@interface Getter {}'})
        context['sources'] += [dict(s, publish=False, world=context['world'], module=context['module']) for s in other['sources']]
        self.assertFalse(self.generated_targets(self.analyze([context])['facts']))

    def test_unknown_and_lazy_written_options_do_not_generate_getter(self):
        for annotation in ['@lombok.Getter(lazy=true)', '@lombok.Getter(lazy=runtime())', '@lombok.Getter(lazy=true,lazy=false)']:
            with self.subTest(annotation=annotation):
                self.assertFalse(self.generated_targets(self.analyze([self.fixture(annotation)])['facts']))

    def test_annotation_removal_clears_generated_target_and_full_delta_matches(self):
        base = self.analyze([self.fixture()], cache=True)['facts']
        self.assertEqual(len(self.generated_targets(base)), 1)
        changed = self.fixture(annotation='')
        delta = self.analyze([changed], base, cache=True)
        full = self.analyze([changed], cache=True)['facts']
        self.assertFalse(self.generated_targets(full))
        self.assertEqual(self.merged(base, delta), sorted(full, key=lambda f: f['id']))

    def test_missing_class_retained_binary_annotation_does_not_abort_healthy_bindings(self):
        definitions = self.context('library', {
            'p/Mark.java': 'package p;@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Mark {}',
            'p/Api.java': 'package p;public class Api {@Mark public String getValue(){return "value";}}'})
        classes = self.root/'fixture-classes'
        classes.mkdir()
        subprocess.run([str(models.JDK8/'bin/javac'), '-proc:none', '-d', str(classes)] +
                       [s['input'] for s in definitions['sources']],check=True,capture_output=True)
        jar = self.root/'api.jar'
        with zipfile.ZipFile(jar,'w') as archive:
            archive.write(classes/'p/Api.class','p/Api.class')
        import shutil
        shutil.rmtree(classes)
        context = self.context('consumer', {'Use.java':'class Use {String read(){return new p.Api().getValue();}}'},classpath=[str(jar)])
        facts = self.analyze([context])['facts']
        by = {f['id']:f for f in facts}
        targets = [by[f['target']]['signature'] for f in facts if f['kind']=='call_target']
        self.assertIn('p.Api#getValue()',targets)
        member = next(f for f in facts if f['kind']=='external_symbol' and f['signature']=='p.Api#getValue()')
        self.assertTrue(any(a.get('reason')=='ANNOTATION_TYPE_UNAVAILABLE' and not a['complete']
                            for a in member['attributes']['annotations']))

    def test_direct_missing_framework_annotation_keeps_written_literal_and_model_status(self):
        source = 'import org.springframework.stereotype.Component;@Component("chosen") class Use {}'
        context = self.context('spring', {'Use.java': source}, spring=True)
        facts = self.analyze([context])['facts']
        beans = [f for f in facts if f['relation']=='java.bean']
        self.assertEqual([f['name'] for f in beans], ['chosen'])
        self.assertFalse(beans[0]['attributes']['runtime_confirmed'])
        self.assertTrue([f for f in facts if f['kind']=='diagnostic' and f['status']=='ERROR'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
