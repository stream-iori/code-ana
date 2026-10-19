"""Optional static pyproject input preparation; target code is never run."""
import importlib.util
import pathlib
import shutil
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('python_export', ROOT/'scripts/export-python-inputs.py')
EXPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORT)


class PythonInputs(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = pathlib.Path(self.temporary.name)/'project'
        shutil.copytree(ROOT/'fixtures/python/metadata', self.project)

    def test_optional_metadata_and_absent_file(self):
        (self.project/'pyproject.toml').write_text('invalid TOML !!!')
        plain = EXPORT.export(self.project, ['src'], [])
        self.assertIsNone(plain['metadata'])
        self.assertEqual(plain['controls'], [])
        (self.project/'pyproject.toml').unlink()
        EXPORT.export(self.project, ['src'], [])
        with self.assertRaises(FileNotFoundError):
            EXPORT.export(self.project, ['src'], [], pyproject='pyproject.toml')

    def test_layout_extras_scope_and_explicit_root_order(self):
        main = EXPORT.export(self.project, [], [], pyproject='pyproject.toml')
        test = EXPORT.export(self.project, [], [], pyproject='pyproject.toml', extras=['dev'], scope='TEST')
        self.assertEqual([r['path'] for r in main['roots']], ['src'])
        self.assertEqual([r['path'] for r in test['roots']], ['tests', 'src'])
        self.assertEqual(main['metadata']['dependencies'], ['example-runtime>=1'])
        self.assertEqual(test['metadata']['dependencies'], ['example-runtime>=1', 'example-dev>=2'])
        self.assertEqual(test['contexts'][0]['scope'], 'TEST')
        self.assertEqual(main['metadata']['dynamic'], ['version'])
        explicit = EXPORT.export(self.project, ['src', 'tests'], [], pyproject='pyproject.toml', scope='TEST')
        self.assertEqual([r['path'] for r in explicit['roots']], ['src', 'tests'])
        self.assertEqual(explicit['metadata']['layout_source'], 'explicit')

    def test_unsupported_backend_and_unknown_extra(self):
        p = self.project/'pyproject.toml'
        p.write_text(p.read_text().replace('setuptools.build_meta', 'unsupported.backend'))
        with self.assertRaisesRegex(ValueError, 'PYTHON_SOURCE_ROOT_REQUIRED'):
            EXPORT.export(self.project, [], [], pyproject='pyproject.toml')
        self.assertIn('unsupported', ' '.join(EXPORT.export(self.project, ['src'], [], pyproject='pyproject.toml')['metadata']['warnings']))
        with self.assertRaisesRegex(ValueError, 'PYTHON_UNKNOWN_EXTRA'):
            EXPORT.export(self.project, ['src'], [], pyproject='pyproject.toml', extras=['missing'])

    def test_metadata_controls_and_symlink_escape(self):
        first = EXPORT.export(self.project, [], [], pyproject='pyproject.toml')
        p = self.project/'pyproject.toml'
        p.write_text(p.read_text()+'\n# changed\n')
        second = EXPORT.export(self.project, [], [], pyproject='pyproject.toml')
        self.assertNotEqual(first['controls'], second['controls'])
        (self.project/'link.toml').symlink_to(p)
        with self.assertRaisesRegex(ValueError, 'PYTHON_CONTROL_SYMLINK'):
            EXPORT.export(self.project, [], [], pyproject='link.toml')
        with self.assertRaisesRegex(ValueError, 'PYTHON_CONTROL_PATH'):
            EXPORT.export(self.project, [], [], pyproject='../outside.toml')

    def test_ambiguous_layout_and_metadata_budget_are_explicit(self):
        path = self.project/'pyproject.toml'
        path.write_text('[tool.setuptools.package-dir]\nrenamed="src"\n')
        with self.assertRaisesRegex(ValueError, 'PYTHON_SOURCE_ROOT_REQUIRED'):
            EXPORT.export(self.project, [], [], pyproject='pyproject.toml')
        result = EXPORT.export(self.project, ['src'], [], pyproject='pyproject.toml')
        self.assertIn('named package-dir', ' '.join(result['metadata']['warnings']))
        path.write_bytes(b'#'*(8*1024*1024+1))
        with self.assertRaisesRegex(ValueError, 'PYTHON_CONTROL_BUDGET'):
            EXPORT.export(self.project, ['src'], [], pyproject='pyproject.toml')


if __name__ == '__main__':
    unittest.main()
