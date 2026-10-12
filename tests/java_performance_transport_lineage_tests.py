import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


lineage = module('transport_lineage', ROOT / 'scripts/java-performance-transport-lineage.py')
codec = module('jar_entries', ROOT / 'scripts/java-model-runtime-equivalence.py')


class LineageTests(unittest.TestCase):
    def pair(self, directory, *, extra=None, removed=None, dependency=False):
        paths = [directory / 'old.jar', directory / 'new.jar']
        for index, path in enumerate(paths):
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nBuild-Jdk-Spec: 25\r\nMain-Class: test.Main\r\n\r\n')
                for name in lineage.CHANGED:
                    if index and name == removed:
                        continue
                    archive.writestr(name, str(index).encode())
                archive.writestr('com/anatomist/Model.class', b'dev/codeana/common/Json' if dependency else b'unchanged model producer')
                if index and extra:
                    archive.writestr(extra, b'unapproved')
        return paths

    def test_model_change_and_extra_transport_class_are_rejected(self):
        for extra in ('com/anatomist/AddedModel.class', 'dev/codeana/common/Other.class'):
            with tempfile.TemporaryDirectory() as temporary:
                before, after = self.pair(Path(temporary), extra=extra)
                with self.assertRaisesRegex(ValueError, 'UNEXPECTED_PRODUCTION_ENTRY_CHANGE'):
                    lineage.compare(codec, before, after)

    def test_removing_an_allowed_transport_class_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            before, after = self.pair(Path(temporary), removed='dev/codeana/common/Json.class')
            with self.assertRaisesRegex(ValueError, 'TRANSPORT_CLASS_REMOVED'):
                lineage.compare(codec, before, after)

    def test_unchanged_model_dependency_on_changed_transport_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            before, after = self.pair(Path(temporary), dependency=True)
            with self.assertRaisesRegex(ValueError, 'MODEL_DEPENDS_ON_CHANGED_TRANSPORT'):
                lineage.compare(codec, before, after)


if __name__ == '__main__':
    unittest.main()
