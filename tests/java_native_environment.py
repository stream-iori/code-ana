#!/usr/bin/env python3
"""The current product rejects an unsupported target JDK before analysis."""
import json
import unittest
from product_support import JavaProductHarness


class JavaEnvironment(JavaProductHarness):
    def test_wrong_jdk(self):
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('source_version = "8"','source_version = "17"'))
        _,terminal=self.cli('index',code=1,timeout=150)
        self.assertIn('SOURCE_VERSION_UNSUPPORTED',json.dumps(terminal))



if __name__ == '__main__':
    unittest.main(verbosity=2)
