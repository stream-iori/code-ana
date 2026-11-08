#!/usr/bin/env python3
"""Java 17 must cross the real configured worker, graph and public query boundary."""
import json
import sqlite3
from contextlib import closing
import shutil
import subprocess
import sys
import unittest
import time
from product_support import JavaProductHarness, ROOT, FIX


class Java17(JavaProductHarness):
    def setUp(self):
        super().setUp()
        self.jdk = subprocess.check_output([sys.executable, str(ROOT/'scripts/resolve-sdkman-java.py'), '17'], text=True).strip()
        user = self.home/'config.toml'
        user.write_text(user.read_text() + '\n[toolchains.java17]\nlanguage="java"\nhome='+json.dumps(self.jdk)+'\n')
        # User toolchains are frozen when the daemon starts.
        self.stop()
        self.log = open(self.root/'daemon.log', 'a')
        from product_support import BIN
        self.daemon = subprocess.Popen([str(BIN/'codeana-daemon'), '--home', str(self.home)], stdout=self.log, stderr=self.log)
        until = time.monotonic() + 10
        while time.monotonic() < until:
            probe = subprocess.run([str(BIN/'codeana-cli'), '--home', str(self.home), 'daemon', 'status'], capture_output=True)
            if probe.returncode == 0: break
            time.sleep(.03)
        else: raise RuntimeError(self.daemon_log())
        config = self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('source_version = "8"', 'source_version = "17"').replace('toolchain = "java8"', 'toolchain = "java17"'))
        shutil.rmtree(self.project/'src')
        self.source = self.project/'src/fixture/modern/Modern.java'
        self.source.parent.mkdir(parents=True)
        fixture = FIX/'java17/Modern.java'
        if not fixture.exists():
            fixture = FIX/'java-native/java17/Modern.java'
        shutil.copy2(fixture, self.source)

    def test_navigation_comments_source_and_incremental(self):
        _, first = self.cli('index', '--full', '--strict', timeout=180)
        revision = first['revision']
        doctor = self.cli('doctor')[0][0]
        self.assertEqual(doctor['java_toolchain']['target_java'], 17)
        self.assertTrue(doctor['java_toolchain']['actual_java_version'].startswith('17.'))
        self.assertEqual(doctor['java_toolchain']['home'], self.jdk)
        method = self.cli('resolve', 'fixture.modern.Modern#run(java.lang.Object)')[0]
        self.assertEqual(len(method), 1)
        selected = {}
        for signature in ('fixture.modern.Modern.Point#x()', 'fixture.modern.Modern.Named#name()', 'fixture.modern.Modern.Box#Box(java.lang.String)', 'fixture.modern.Modern.Point#Point(int)'):
            rows = self.cli('resolve', signature)[0]
            self.assertEqual(len(rows), 1, signature)
            selected[signature] = rows[0]['id']
        # Verify delivery of compiler-selected method-reference targets, whose
        # source owners are separate synthetic callables rather than constructors.
        with closing(sqlite3.connect('file:'+str(self.home/'revisions'/revision/'facts.db')+'?mode=ro', uri=True)) as db:
            reference_calls = db.execute("SELECT o.caller_id,t.target_id FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk JOIN call_site_owners o ON o.owner_pk=c.owner_pk WHERE o.caller_id LIKE '%$methodref%' AND t.target_id IS NOT NULL").fetchall()
        self.assertTrue(set(selected.values()).issubset({target for _,target in reference_calls}), reference_calls)
        self.assertTrue(any('Callbacks#Callbacks()$methodref' in owner and target == selected['fixture.modern.Modern.Named#name()'] for owner,target in reference_calls))
        calls = self.cli('calls', method[0]['id'])[0]
        rendered = json.dumps(calls)
        for target in ('java.util.List#of', 'java.util.List#copyOf', 'java.lang.String#isBlank', 'java.lang.String#formatted', 'java.util.stream.Stream#toList'):
            self.assertIn(target, rendered)
        comments = self.cli('search-comments', 'Java17 方法内')[0]
        self.assertEqual(len(comments), 1)
        self.assertNotIn('source', comments[0])
        self.assertEqual([link['declaration'] for link in comments[0]['links']], [method[0]['id']])
        projected = self.cli('search-code', 'Java17 方法内', '--in', 'comments')[0]
        self.assertEqual([row['id'] for row in projected], [method[0]['id']])
        snippets = self.cli('source', method[0]['id'], '--revision', revision)[0]
        text = ''.join(row['text'] for row in snippets)
        self.assertIn('public String run', text)
        self.assertIn('formatted', text)
        self.assertFalse(self.cli('search-code', 'lambda隔离', '--in', 'comments')[0])
        records, unchanged = self.cli('index', timeout=180)
        self.assertEqual(records[0]['mode'], 'delta')
        self.assertEqual(self.cli('calls', method[0]['id'])[0], calls)
        resources = self.cli('report', unchanged['report'])[0][0]['analysis_resources']
        self.assertIn('reused', json.dumps(resources))
        self.source.write_text(self.source.read_text().replace('方法内注释', '修改后注释'))
        _, delta = self.cli('index', timeout=180)
        self.assertNotEqual(delta['revision'], revision)
        self.assertFalse(self.cli('search-comments', '方法内注释')[0])
        self.assertTrue(self.cli('search-comments', '修改后注释')[0])
        self.assertTrue(self.cli('search-comments', '方法内注释', '--revision', revision)[0])

    def test_cancellation_and_worker_crash_keep_the_java17_revision(self):
        (self.project/'src/p').mkdir(parents=True, exist_ok=True)
        self.assert_cancel_and_crash_preserve_head()
        self.assertTrue(self.cli('search-comments', 'Java17 方法内')[0])

    def test_wrong_toolchain_and_unsupported_target_fail_before_analysis(self):
        config = self.project/'.code-ana/config.toml'
        original = config.read_text()
        for value in (original.replace('toolchain = "java17"', 'toolchain = "java8"'), original.replace('source_version = "17"', 'source_version = "25"')):
            config.write_text(value)
            _, failure = self.cli('index', '--full', code=1, timeout=60)
            self.assertIn(failure['code'], ('TARGET_JDK_REQUIRED', 'SOURCE_VERSION_UNSUPPORTED'))


if __name__ == '__main__':
    unittest.main()
