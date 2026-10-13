"""Process fixtures shared by the current typed-graph product regressions.

Helpers have no test cases and never read the retired Fact JSON tables.
"""
import hashlib
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
BIN = pathlib.Path(os.environ.get('CODEANA_TEST_BIN', ROOT / 'target/debug'))
FIX = pathlib.Path(os.environ.get('CODEANA_TEST_FIXTURES', ROOT / 'tests/fixtures'))
JDK = pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME',
    pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu')).resolve()


class ProductHarness(unittest.TestCase):
    def cleanup(self):
        self.stop()
        self.temp.cleanup()


    def stop(self, kill=False):
        if self.daemon is not None:
            if self.daemon.poll() is None:
                self.daemon.kill() if kill else self.daemon.terminate()
            try:
                self.daemon.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.daemon.kill()
                self.daemon.wait(timeout=5)
            self.daemon = None
        if self.log is not None:
            self.log.close()
            self.log = None


    def daemon_log(self):
        path = self.root / 'daemon.log'
        return path.read_text() if path.exists() else ''


    def cli(self, *args, code=0, raw=False, timeout=30):
        p = subprocess.run(
            [str(BIN / 'codeana-cli'), '--home', str(self.home),
             '--project', str(self.project), *map(str, args)],
            capture_output=True, text=True, timeout=timeout)
        self.assertEqual(p.returncode, code,
                         f'args={args}\nstdout={p.stdout}\nstderr={p.stderr}\n{self.daemon_log()}')
        if raw:
            return p.stdout if code == 0 else p.stdout + p.stderr
        records, terminal = [], None
        for line in p.stdout.splitlines():
            if line.startswith('record '):
                records.append(json.loads(line[7:]))
            if line.startswith('terminal '):
                terminal = json.loads(line[9:])
        if code != 0 and terminal is None:
            return [], {'message': p.stderr, 'code': p.returncode}
        self.assertIsNotNone(terminal, p.stdout + p.stderr)
        return records, terminal


    def task(self, task_id):
        return json.loads(self.cli('task', 'status', task_id, raw=True))


    def wait_task(self, task_id, state='running'):
        until = time.monotonic() + 10
        while time.monotonic() < until:
            status = self.task(task_id)
            if status['state'] == state:
                return status
            time.sleep(.03)
        self.fail(f'task {task_id} did not reach {state}: {self.task(task_id)}')



class MockProductHarness(ProductHarness):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='codeana-e2e-')
        self.root = pathlib.Path(self.temp.name)
        self.home = self.root / 'home'
        self.project = self.root / 'project'
        shutil.copytree(FIX / 'java/base', self.project)
        self.daemon = None
        self.log = None
        self.addCleanup(self.cleanup)
        self.start()


    def start(self, fault=None):
        self.log = open(self.root / 'daemon.log', 'a')
        env = os.environ.copy()
        env.pop('CODEANA_TEST_FAULT', None)
        if fault:
            env['CODEANA_TEST_FAULT'] = fault
        self.daemon = subprocess.Popen(
            [str(BIN / 'codeana-daemon'), '--home', str(self.home),
             '--dev-mock-fixtures', str(FIX / 'mock.json')],
            stdout=self.log, stderr=self.log, env=env)
        until = time.monotonic() + 10
        while time.monotonic() < until:
            if self.daemon.poll() is not None:
                raise RuntimeError(self.daemon_log())
            if (self.home / 'endpoint.json').exists():
                p = subprocess.run(
                    [str(BIN / 'codeana-cli'), '--home', str(self.home), 'daemon', 'status'],
                    capture_output=True, timeout=3)
                if p.returncode == 0:
                    return
            time.sleep(.03)
        raise RuntimeError('daemon start timeout\n' + self.daemon_log())


    def state(self, name):
        origin = FIX / 'java' / name
        for p in origin.rglob('*'):
            if p.is_file():
                dest = self.project / p.relative_to(origin)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(p, dest)


    def require_debug_faults(self):
        if BIN.resolve() != (ROOT / 'target/debug').resolve():
            self.skipTest('fault points exist only in debug mock binary')


    def assert_crash_boundary(self, point, committed):
        self.require_debug_faults()
        old = self.cli('index')[1]['revision']
        self.stop()
        self.state('edited')
        self.start(f'crash:{point}')
        task_id = json.loads(self.cli('index', '--background', raw=True))['task_id']
        self.assertEqual(self.daemon.wait(timeout=10), 75, self.daemon_log())
        self.stop()
        self.start()
        head = self.cli('overview')[1]['revision']
        status = self.task(task_id)
        if committed:
            self.assertNotEqual(head, old)
            self.assertEqual(status['terminal']['publication'], 'published')
            self.assertEqual(status['terminal']['revision'], head)
        else:
            self.assertEqual(head, old)
            self.assertEqual(status['terminal']['code'], 'DAEMON_RESTARTED')
        self.assertFalse(list((self.home / 'candidates').iterdir()))
        self.assertEqual(len(list((self.home / 'revisions').iterdir())), 2 if committed else 1)



class JavaProductHarness(ProductHarness):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='codeana-java-e2e-')
        self.root=pathlib.Path(self.temp.name);self.home=self.root/'home';self.project=self.root/'project'
        self.daemon=None;self.log=None
        self.home.mkdir(mode=0o700)
        (self.home/'config.toml').write_text('schema_version=1\nkind="user"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=1073741824\n[toolchains.java8]\nlanguage="java"\nhome='+json.dumps(str(JDK))+'\n')
        shutil.copytree(ROOT/'tests/fixtures/java-native/base',self.project)
        self.addCleanup(self.cleanup)
        self.log=open(self.root/'daemon.log','a')
        self.daemon=subprocess.Popen([str(BIN/'codeana-daemon'),'--home',str(self.home)],stdout=self.log,stderr=self.log)
        until=time.monotonic()+10
        while time.monotonic()<until:
            if self.daemon.poll() is not None:raise RuntimeError(self.daemon_log())
            if (self.home/'endpoint.json').exists():return
            time.sleep(.03)
        raise RuntimeError('daemon start timeout')

    def java_worker_pid(self):
        rows=subprocess.check_output(['ps','-axo','pid=,ppid=,comm='],text=True)
        workers=[int(fields[0]) for line in rows.splitlines() if (fields:=line.split(None,2)) and len(fields)==3 and int(fields[1])==self.daemon.pid and 'codeana-java-worker' in fields[2]]
        self.assertLessEqual(len(workers),1)
        return workers[0] if workers else None

    def assert_cancel_and_crash_preserve_head(self):
        import signal
        good=self.cli('index',timeout=150)[1]['revision']
        # Long enough to observe the real javac child, with no product fault hooks.
        source=self.project/'src/p/App.java'
        source.write_text('package p; class App {' + ''.join('int m%d(){return %d;}'%(i,i) for i in range(5000)) + '}')
        def processes():
            rows=subprocess.check_output(['ps','-axo','pid=,ppid=,stat=,comm='],text=True)
            return [line.split(None,3) for line in rows.splitlines() if line.strip()]
        for action in ('cancel','cancel_again','crash'):
            with self.subTest(action=action):
                task=json.loads(self.cli('index','--full','--background',raw=True))['task_id']
                until=time.monotonic()+90
                owned=[]
                while time.monotonic()<until:
                    rows=processes()
                    workers=[int(pid) for pid,ppid,stat,comm in rows if int(ppid)==self.daemon.pid and 'codeana-java-worker' in comm]
                    children=[int(pid) for pid,ppid,stat,comm in rows if int(ppid) in workers and comm.endswith('/java')]
                    if workers and children:
                        owned=workers+children
                        break
                    time.sleep(.02)
                self.assertTrue(owned,'javac child was not observed: '+self.daemon_log())
                if action.startswith('cancel'):
                    self.cli('task','cancel',task,raw=True)
                else:
                    os.kill(workers[0],signal.SIGKILL)
                self.cli('task','watch',task,code=130 if action.startswith('cancel') else 1,timeout=30)
                self.assertEqual(self.cli('overview')[1]['revision'],good)
                until=time.monotonic()+5
                while time.monotonic()<until:
                    alive=[int(pid) for pid,ppid,stat,comm in processes() if int(pid) in owned and not stat.startswith('Z')]
                    if not alive:break
                    time.sleep(.05)
                self.assertFalse(alive,'owned processes leaked')
