#!/usr/bin/env python3
"""Static import recovery uses captured declarations, never guessed names."""
import pathlib
import shutil
import subprocess
import unittest
import zipfile

import java_model_tests as models
import java_lombok_alignment as lombok
import java_binding_alignment as bindings


class StaticImports(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged
    recovered = bindings.Bindings.recovered

    def enum_jar(self):
        source = self.root / 'fixture/Mode.java'
        source.parent.mkdir()
        source.write_text('package p; public enum Mode {ONE,TWO}')
        classes = self.root / 'fixture-classes'
        classes.mkdir()
        subprocess.run([str(models.JDK8 / 'bin/javac'), '-proc:none', '-d', str(classes), str(source)],
                       check=True, capture_output=True, timeout=30)
        jar = self.root / 'enum.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for path in sorted(classes.rglob('*.class')):
                archive.write(path, path.relative_to(classes))
        shutil.rmtree(classes)
        return jar

    def fixture(self, source):
        context = self.context('app', lombok.Lombok.files(self, source), lombok='model')
        context['classpath'] = [str(self.enum_jar())]
        return context

    def test_binary_static_import_preserves_owner_original_binding_and_delta(self):
        source = '''import static p.Mode.valueOf;
// 中文位置
class Holder {@lombok.Getter String name;} class Use {int read(Holder h){
 switch(valueOf(h.getName())){case ONE:return 1;case TWO:return 2;default:return 0;}}}'''
        context = self.fixture(source)
        initial = self.analyze([context], cache=True)
        def verify(result):
            targets = self.recovered(result['facts'])
            call = next(f for name, signature, f in targets if signature == 'p.Mode#valueOf(java.lang.String)')
            self.assertEqual(call['attributes']['recovery_static_owner_origin'], 'javac_selected_declaration')
            self.assertEqual(call['attributes']['javac_selected_declaration'], 'p.Mode#valueOf(java.lang.String)')
            self.assertEqual(call['status'], 'possible')
            self.assertFalse(call['evidence_complete'])
            ids = {f['id']: f for f in result['facts']}
            self.assertFalse([f for f in result['facts'] if f['relation'] == 'reads'
                              and ids[f['target']]['signature'].startswith('p.Mode#')])
        verify(initial)
        changed = self.context('app', lombok.Lombok.files(self, source.replace('return 1;', 'return 10;')), lombok='model')
        changed['classpath'] = context['classpath']
        delta = self.analyze([changed], initial['facts'], cache=True)
        full = self.analyze([changed], cache=True)
        verify(full)
        self.assertEqual(self.merged(initial['facts'], delta), sorted(full['facts'], key=lambda f: f['id']))

    def test_confirmed_binary_enum_labels_are_retained_once(self):
        context = self.fixture('class Holder {@lombok.Getter String name;} class Use {void read(p.Mode mode){switch(mode){case ONE:break;case TWO:break;}}}')
        facts = self.analyze([context])['facts']
        ids = {f['id']: f for f in facts}
        reads = [f for f in facts if f['relation'] == 'reads' and ids[f['target']]['signature'].startswith('p.Mode#')]
        self.assertEqual(len(reads), 2)
        self.assertEqual({ids[f['target']]['signature'] for f in reads}, {'p.Mode#ONE', 'p.Mode#TWO'})
        self.assertTrue(all(f['evidence_complete'] for f in reads))

    def test_wrong_argument_is_not_made_applicable_by_static_owner(self):
        context = self.fixture('import static p.Mode.valueOf; class Holder {@lombok.Getter int count;} class Use {void read(Holder h){valueOf(h.getCount());}}')
        facts = self.analyze([context])['facts']
        self.assertFalse([signature for name, signature, fact in self.recovered(facts) if signature.startswith('p.Mode#valueOf')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
