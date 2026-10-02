#!/usr/bin/env python3
"""Real NoCode cache eviction, immutable pins, corruption and cancellation."""
import json
import os
import subprocess
import unittest
import java_model_tests as models


class JavaCache(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context

    def check(self, mode):
        context = self.context('app', {
            'Api.java': 'class Api { int value(){return 1;} }',
            'Use.java': 'class Use { int read(){return new Api().value();} int one(){return 1;} int two(){return 2;} int three(){return 3;} }',
            'Added.java': 'class Added { int value(){return 2;} }',
        })
        request = dict(contexts=[context], java_home=str(models.JDK8),
                       bridge=str(models.ROOT/'lang-adaptor/target/compiler-bridge.jar'),
                       scratch=str(self.root), signature_cache=True, cache_root=str(self.root/'cache'),
                       profile='test-cache', cache_test=mode)
        path = self.root/'request.json'
        path.write_text(json.dumps(request))
        dependencies = (models.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
        classpath = os.pathsep.join([str(models.ROOT/'lang-adaptor/target'/d) for d in ('classes', 'test-classes')]+[dependencies])
        result = subprocess.run([str(models.JDK25/'bin/java'), '-cp', classpath,
                                 'dev.codeana.analysis.SignatureCacheProbe', str(path)],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)['accepted'])
        self.assertFalse(list(self.root.glob('compiler-*')))
        self.assertFalse(list(self.root.rglob('*.class')))

    def test_lru_and_javac_consumes_pin_after_original_eviction(self):
        self.check('lru')

    def test_corrupt_entry_repair_and_abandoned_commit_cleanup(self):
        self.check('corrupt')

    def test_concurrent_read_publish_and_budget(self):
        self.check('concurrent')

    def test_cancelled_pin_and_cleanup(self):
        self.check('cancel')


if __name__ == '__main__':
    unittest.main(verbosity=2)
