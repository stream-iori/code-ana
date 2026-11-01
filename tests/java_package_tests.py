#!/usr/bin/env python3
"""A stale shaded dependency must fail before Native Image or JVM manifest."""
import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('java_package',ROOT/'scripts/check-java-package.py')
package=importlib.util.module_from_spec(spec);spec.loader.exec_module(package)

class JavaPackage(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=pathlib.Path(self.temp.name)
    def stage(self,backend,stale=False,missing=False,duplicate=False):
        main='native-bridge' if backend=='native' else 'jvm-runner'
        artifact=self.root/main/'target'/(main+'-0.1.0.jar');artifact.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(artifact,'w') as jar:
            for n,module in enumerate((*package.MODULES,main)):
                path=self.root/module/'target/classes'/('Type'+str(n)+'.class')
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(('current-'+module).encode())
                if missing and n==0:continue
                jar.writestr(path.name,b'old-dependency' if stale and n==0 else path.read_bytes())
                if duplicate and n==0:
                    import warnings
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore');jar.writestr(path.name,path.read_bytes())
    def test_current_reactor_classes_are_checked_for_both_backends(self):
        for backend in ('native','jvm'):
            self.stage(backend);self.assertEqual(package.verify(self.root,backend)['classes'],len(package.MODULES)+1)
    def test_stale_dependency_is_rejected_even_when_main_entrypoint_is_unchanged(self):
        self.stage('native',stale=True)
        with self.assertRaisesRegex(ValueError,'JAVA_PACKAGE_STALE_CLASS'):package.verify(self.root,'native')
    def test_missing_or_duplicate_classes_are_rejected(self):
        for option in ('missing','duplicate'):
            self.stage('jvm',**{option:True})
            with self.assertRaisesRegex(ValueError,'JAVA_PACKAGE_CLASS_MISSING_OR_DUPLICATE'):package.verify(self.root,'jvm')

if __name__=='__main__':unittest.main(verbosity=2)
