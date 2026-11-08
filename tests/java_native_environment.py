#!/usr/bin/env python3
"""Wrong toolchains and unsupported targets fail before analysis."""
import unittest
from product_support import JavaProductHarness


class JavaEnvironment(JavaProductHarness):
    def test_wrong_jdk(self):
        config=self.project/'.code-ana/config.toml'
        original = config.read_text()
        for version, expected in [('17', 'TARGET_JDK_REQUIRED'), ('25', 'SOURCE_VERSION_UNSUPPORTED')]:
            with self.subTest(target=version):
                config.write_text(original.replace('source_version = "8"', 'source_version = "'+version+'"'))
                _,terminal=self.cli('index',code=1,timeout=150)
                self.assertEqual(terminal['code'], expected)




if __name__ == '__main__':
    unittest.main(verbosity=2)
