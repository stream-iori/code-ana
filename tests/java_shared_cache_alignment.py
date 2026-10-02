#!/usr/bin/env python3
"""File/module cache budgets, immutable pins and real process coordination."""
import json
import os
import subprocess
import unittest

import java_model_tests as models


class SharedCache(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context

    def request(self, name):
        contexts = [
            self.context('lib', {'p/Api.java': 'package p;public class Api {public int read(){return 1;}}'}),
            self.context('added', {'p/Added.java': 'package p;public class Added {public int read(){return 2;}public int another(){return 3;}}'}),
            self.context('use', {'p/Use.java': 'package p;class Use {int read(){return new Api().read();}}'}),
        ]
        return dict(contexts=contexts, java_home=str(models.JDK8),
                    bridge=str(models.ROOT / 'lang-adaptor/target/compiler-bridge.jar'),
                    scratch=str(self.root), signature_cache=True,
                    cache_root=str(self.root / name), profile='shared-cache')

    def command(self, probe, request):
        path = self.root / 'request.json'
        path.write_text(json.dumps(request))
        dependencies = (models.ROOT / 'lang-adaptor/target/native-classpath.txt').read_text().strip()
        classpath = os.pathsep.join([
            str(models.ROOT / 'lang-adaptor/target/classes'),
            str(models.ROOT / 'lang-adaptor/target/test-classes'), dependencies])
        if os.environ.get('CODEANA_TEST_CLASSES'):
            classpath = os.environ['CODEANA_TEST_CLASSES'] + os.pathsep + classpath
        return [str(models.JDK25 / 'bin/java'), '-cp', classpath,
                'dev.codeana.analysis.' + probe, str(path)]

    def probe(self, name, request):
        result = subprocess.run(self.command(name, request), capture_output=True,
                                text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(list(self.root.glob('compiler-*')))
        self.assertFalse(list(self.root.rglob('*.class')))
        return result.stdout

    def test_cross_store_lru_and_evicted_pins_still_compile_and_restore(self):
        result = json.loads(self.probe('SharedCacheProbe', self.request('cache')))
        self.assertTrue(result['accepted'])
        self.assertLessEqual(result['persisted'], result['budget'])

    def test_combined_pin_reservation_and_close_release(self):
        request = self.request('warm')
        budget = json.loads(self.probe('SharedCacheProbe', request))['budget']
        request.update(cache_root=str(self.root / 'bounded'), pin_limit=budget)
        self.assertTrue(json.loads(self.probe('SharedCacheProbe', request))['accepted'])

    def test_two_real_processes_share_disk_budget(self):
        request = self.request('cache')
        request['pin_limit'] = json.loads(self.probe('SharedCacheProbe', request))['budget']
        command = self.command('SharedCacheWriter', request)
        writers = [subprocess.Popen(command + [kind], stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, text=True)
                   for kind in ('files', 'modules')]
        try:
            for writer in writers:
                out, err = writer.communicate(timeout=120)
                self.assertEqual(writer.returncode, 0, out + err)
                self.assertIn('accepted', out)
        finally:
            for writer in writers:
                if writer.poll() is None:
                    writer.kill()
                    writer.communicate(timeout=10)
        stores = (self.root / 'cache', self.root / 'cache-modules')
        persisted = sum(path.stat().st_size for store in stores
                        for path in store.rglob('*') if path.is_file())
        self.assertLessEqual(persisted, request['pin_limit'])
        self.assertFalse(list(self.root.glob('compiler-*')))
        self.assertFalse(list(self.root.rglob('*.class')))

    def test_symlink_guards_cancelled_lock_and_reservation_cleanup(self):
        self.assertIn('accepted', self.probe('SharedCacheNegative', self.request('negative')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
