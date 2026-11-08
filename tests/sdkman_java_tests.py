"""Compiler selection must ignore SDKMAN current and deduplicate aliases."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("sdkman_java", Path(__file__).resolve().parents[1] / "scripts/resolve-sdkman-java.py")
sdkman = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sdkman)


class Selection(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jdk(self, name, version):
        home = self.root / name
        (home / "bin").mkdir(parents=True)
        (home / "release").write_text(f'JAVA_VERSION="{version}"\n')
        for binary in ("java", "javac"):
            path = home / "bin" / binary
            path.write_text("#!/bin/sh\n")
            path.chmod(0o755)
        return home.resolve()

    def test_aliases_and_current(self):
        home = self.jdk("17.0.17-zulu", "17.0.17")
        (self.root / "17-zulu").symlink_to(home)
        other = self.jdk("8-zulu", "1.8.0_492")
        (self.root / "current").symlink_to(other)
        self.assertEqual(sdkman.resolve(self.root, 17), home)
        self.assertEqual(sdkman.resolve(self.root, 8), other)

    def test_ambiguity_requires_selection(self):
        home = self.jdk("17-a", "17.0.17")
        self.jdk("17-b", "17.0.18")
        with self.assertRaises(ValueError):
            sdkman.resolve(self.root, 17)
        self.assertEqual(sdkman.resolve(self.root, 17, "17-a"), home)

    def test_wrong_version_missing_and_non_executable(self):
        home = self.jdk("17-bad", "8.0.492")
        with self.assertRaises(ValueError):
            sdkman.resolve(self.root, 17, str(home))
        with self.assertRaises(ValueError):
            sdkman.resolve(self.root, 17, "missing")
        (home / "release").write_text('JAVA_VERSION="17.0.17"\n')
        (home / "bin/javac").chmod(0o644)
        with self.assertRaises(ValueError):
            sdkman.resolve(self.root, 17)


if __name__ == "__main__":
    unittest.main()
