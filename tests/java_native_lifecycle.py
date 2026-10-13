"""Exercise the actual shared library's C ABI, isolate and task ownership."""
import ctypes
import hashlib
import json
import os
import pathlib
import struct
import subprocess
import sys
import socket
import tempfile
import threading
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def bundle_path():
    return pathlib.Path(os.environ.get('CODEANA_TEST_JAVA_BUNDLE',
        ROOT / 'lang-java-adapotr/target/native')).resolve()


def child_environment():
    return dict(os.environ, CODEANA_NATIVE_LIFECYCLE_CHILD='1')


class NativeLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='codeana-native-lifecycle-')
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        bundle = bundle_path()
        manifest = json.loads((bundle / 'manifest.json').read_text())
        self.assertEqual(manifest['backend'], 'native')
        self.assertEqual(manifest['abi'], 4)
        self.assertEqual(manifest['analysis_request'], 9)
        for name, expected in manifest['files'].items():
            path = bundle.parent / name
            self.assertTrue(path.resolve().is_relative_to(bundle.parent))
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected)
        self.library = ctypes.CDLL(str(bundle / 'libcodeana_java.dylib'))
        pointer = ctypes.c_void_p
        self.bind('graal_create_isolate', [pointer, ctypes.POINTER(pointer), ctypes.POINTER(pointer)])
        self.bind('graal_tear_down_isolate', [pointer])
        self.bind('graal_attach_thread', [pointer, ctypes.POINTER(pointer)])
        self.bind('graal_detach_thread', [pointer])
        self.bind('codeana_java_abi_version', [pointer])
        self.bind('codeana_java_session_create', [pointer], ctypes.c_uint64)
        self.bind('codeana_java_set_completion_fd', [pointer, ctypes.c_uint64, ctypes.c_int])
        self.bind('codeana_java_input_chunk', [pointer, ctypes.c_uint64, pointer, ctypes.c_int])
        self.bind('codeana_java_read_result_chunk',
            [pointer, ctypes.c_uint64, ctypes.c_uint64, pointer, ctypes.c_int])
        for name in ('seal_input', 'analyze_start', 'poll_event', 'cancel', 'publish_ack', 'session_close'):
            self.bind('codeana_java_' + name, [pointer, ctypes.c_uint64])
        self.isolate, self.thread = self.create_isolate()
        self.tasks = set()
        self.addCleanup(self.close_isolate)
        self.assertEqual(self.library.codeana_java_abi_version(self.thread), 4)
        source = self.root / 'A.java'
        source.write_text('class A {String value="中文😀"; void run(){target();} void target(){}}')
        unit = dict(path='src/A.java', input=str(source), hash=hashlib.sha256(source.read_bytes()).hexdigest(),
                    module='.', world='main', scope='MAIN', publish=True)
        self.request = dict(version=9, full=True, facts=True,
            java_home=str(pathlib.Path.home() / '.sdkman/candidates/java/8.0.492-zulu'),
            scratch=str(self.root), compiler_timeout_seconds=30,
            contexts=[dict(context='.@MAIN', module='.', world='main', scope='MAIN',
                digest=unit['hash'], sources=[unit], resources=[], classpath=[], dependency_modules=[], lombok='off')])

    def bind(self, name, arguments, result=ctypes.c_int):
        function = getattr(self.library, name)
        function.argtypes, function.restype = arguments, result

    def create_isolate(self):
        isolate, thread = ctypes.c_void_p(), ctypes.c_void_p()
        self.assertEqual(self.library.graal_create_isolate(None, ctypes.byref(isolate), ctypes.byref(thread)), 0)
        self.assertTrue(isolate.value and thread.value)
        return isolate, thread

    def close_isolate(self):
        for task in list(self.tasks):
            self.close(task)
        self.assertEqual(self.library.graal_tear_down_isolate(self.thread), 0)

    def task(self):
        task = self.library.codeana_java_session_create(self.thread)
        self.assertNotEqual(task, 0)
        self.tasks.add(task)
        return task

    def control(self, operation, task):
        return getattr(self.library, 'codeana_java_' + operation)(self.thread, task)

    def close(self, task):
        self.assertEqual(self.control('session_close', task), 0)
        self.tasks.discard(task)

    def submit(self, request=None, start=True):
        task = self.task()
        data = json.dumps(request or self.request, ensure_ascii=False).encode()
        # Deliberately split UTF-8 and JSON tokens across C ABI input chunks.
        for offset in range(0, len(data), 17):
            chunk = ctypes.create_string_buffer(data[offset:offset + 17])
            self.assertEqual(self.library.codeana_java_input_chunk(
                self.thread, task, chunk, len(chunk.raw) - 1), 0)
        self.assertEqual(self.control('seal_input', task), 0)
        if start:
            self.assertEqual(self.control('analyze_start', task), 0)
        return task

    def result(self, task):
        until = time.monotonic() + 60
        state = self.control('poll_event', task)
        while state == 0 and time.monotonic() < until:
            time.sleep(.01)
            state = self.control('poll_event', task)
        self.assertIn(state, (-1, 1), 'native task did not reach a terminal state')
        output = bytearray()
        buffer = ctypes.create_string_buffer(41)
        while True:
            count = self.library.codeana_java_read_result_chunk(self.thread, task, len(output), buffer, 41)
            self.assertGreaterEqual(count, 0)
            if count == 0:
                break
            output.extend(buffer.raw[:count])
            self.assertLess(len(output), 8 << 20)
        self.assertEqual(self.library.codeana_java_read_result_chunk(
            self.thread, task, len(output) + 1, buffer, 41), -2)
        return state, json.loads(output)

    def facts(self, result):
        descriptor = result['graph_archive']
        path = pathlib.Path(descriptor['path'])
        body = path.read_bytes()
        self.assertEqual(hashlib.sha256(body).hexdigest(), descriptor['sha256'])
        self.assertEqual(len(body), descriptor['bytes'])
        self.assertEqual(body[:4], b'GRA1')
        offset, facts = 4, []
        while offset < len(body):
            length = struct.unpack('>I', body[offset:offset + 4])[0]
            offset += 4
            self.assertGreater(length, 0)
            self.assertLessEqual(offset + length, len(body))
            facts.append(body[offset:offset + length])
            offset += length
        self.assertEqual(len(facts), descriptor['count'])
        return path, sorted(facts)

    def test_completion_notification_success_error_and_unstarted_close(self):
        for request in (self.request, dict(self.request, version=0)):
            task = self.submit(request, start=False)
            reader, writer = socket.socketpair()
            reader.settimeout(60)
            self.addCleanup(reader.close)
            self.addCleanup(writer.close)
            self.assertNotEqual(self.library.codeana_java_set_completion_fd(self.thread, task, -1), 0)
            self.assertEqual(self.library.codeana_java_set_completion_fd(self.thread, task, writer.fileno()), 0)
            self.assertNotEqual(self.library.codeana_java_set_completion_fd(self.thread, task, writer.fileno()), 0)
            writer.close()  # The native task owns a duplicate, never the caller's FD.
            self.assertEqual(self.control('analyze_start', task), 0)
            self.assertEqual(reader.recv(1), b'\x01')
            self.assertEqual(reader.recv(1), b'')
            self.assertIn(self.control('poll_event', task), (-1, 1))
            self.result(task)
            self.close(task)
        task = self.task()
        reader, writer = socket.socketpair()
        reader.settimeout(3)
        self.addCleanup(reader.close)
        self.assertEqual(self.library.codeana_java_set_completion_fd(self.thread, task, writer.fileno()), 0)
        writer.close()
        self.close(task)
        self.assertEqual(reader.recv(1), b'')

    def test_same_isolate_reuses_tasks_and_releases_archives_after_close(self):
        observed = []
        for _ in range(2):
            task = self.submit()
            state, result = self.result(task)
            self.assertEqual(state, 1, result)
            archive, facts = self.facts(result)
            observed.append(facts)
            self.assertEqual(self.control('publish_ack', task), 0)
            self.assertTrue(archive.is_file())
            self.close(task)
            self.assertFalse(archive.exists())
            self.assertEqual(self.control('poll_event', task), -2)
        self.assertEqual(observed[0], observed[1])
        self.assertTrue(any(row.startswith(b'\x08\x02') and b'CALLS' in row for row in observed[0]))
        self.assertFalse(list(self.root.rglob('*.class')))

    def test_session_capacity_and_closed_handle_are_explicit(self):
        tasks = [self.task() for _ in range(4)]
        self.assertEqual(len(set(tasks)), 4)
        self.assertEqual(self.library.codeana_java_session_create(self.thread), 0)
        self.close(tasks[0])
        replacement = self.task()
        self.assertNotIn(replacement, tasks)
        self.assertEqual(self.control('session_close', tasks[0]), -2)
        # Rendezvous keeps both processes' full session tables alive together.
        barrier = os.environ.get('CODEANA_NATIVE_LIFECYCLE_BARRIER')
        if barrier:
            directory = pathlib.Path(barrier)
            (directory / ('ready-' + str(os.getpid()))).touch()
            until = time.monotonic() + 30
            while not (directory / 'release').exists():
                self.assertLess(time.monotonic(), until, 'process isolation rendezvous timeout')
                time.sleep(.01)

    def test_input_output_and_start_state_boundaries_reject_invalid_requests(self):
        task = self.task()
        buffer = ctypes.create_string_buffer(b'{}')
        self.assertEqual(self.control('analyze_start', task), -2)
        self.assertEqual(self.control('publish_ack', task), -2)
        self.assertEqual(self.library.codeana_java_input_chunk(self.thread, task, None, 0), -2)
        self.assertEqual(self.library.codeana_java_input_chunk(self.thread, task, buffer, -1), -2)
        self.assertEqual(self.library.codeana_java_input_chunk(self.thread, task, buffer, (8 << 20) + 1), -2)
        self.assertEqual(self.library.codeana_java_read_result_chunk(self.thread, task, 0, buffer, 1), -2)
        self.assertEqual(self.library.codeana_java_input_chunk(self.thread, task, buffer, 2), 0)
        self.assertEqual(self.control('seal_input', task), 0)
        self.assertEqual(self.control('seal_input', task), -2)
        self.assertEqual(self.library.codeana_java_input_chunk(self.thread, task, buffer, 2), -2)

    def test_version_failure_remains_readable_and_isolate_can_run_again(self):
        task = self.submit(dict(self.request, version=6))
        state, result = self.result(task)
        self.assertEqual(state, -1)
        self.assertIn('ABI_REQUEST_VERSION', result['error'])
        self.assertEqual(self.control('publish_ack', task), -2)
        self.close(task)
        task = self.submit()
        self.assertEqual(self.result(task)[0], 1)

    def test_cancelled_task_can_close_and_next_task_can_succeed(self):
        task = self.submit(start=False)
        self.assertEqual(self.control('cancel', task), 0)
        self.assertEqual(self.control('analyze_start', task), 0)
        state, result = self.result(task)
        self.assertEqual(state, -1)
        self.assertEqual(result['error'], 'CANCELLED')
        self.close(task)
        task = self.submit()
        self.assertEqual(self.result(task)[0], 1)

    def test_thread_attach_detach(self):
        outcomes = []
        def attached():
            thread = ctypes.c_void_p()
            outcomes.append(self.library.graal_attach_thread(self.isolate, ctypes.byref(thread)))
            if outcomes[-1] == 0:
                outcomes.append(self.library.codeana_java_abi_version(thread))
                outcomes.append(self.library.graal_detach_thread(thread))
        worker = threading.Thread(target=attached)
        worker.start()
        worker.join(timeout=15)
        self.assertFalse(worker.is_alive())
        self.assertEqual(outcomes, [0, 4, 0])
    def test_independent_isolates(self):
        _, other = self.create_isolate()
        try:
            self.assertEqual(self.library.codeana_java_abi_version(other), 4)
            tasks = [self.task() for _ in range(4)]
            separate = self.library.codeana_java_session_create(other)
            self.assertNotEqual(separate, 0)
            self.assertEqual(self.library.codeana_java_session_close(other, separate), 0)
            self.assertEqual(self.library.codeana_java_session_create(self.thread), 0)
            for task in tasks:
                self.close(task)
        finally:
            self.assertEqual(self.library.graal_tear_down_isolate(other), 0)


class ProcessLifecycle(unittest.TestCase):
    """A G1 process must never recreate an isolate, including after teardown."""
    def __init__(self, native_name):
        super().__init__('runTest')
        self.native_name = native_name

    def id(self):
        return 'G1Process.' + self.native_name

    def shortDescription(self):
        return self.id()

    def runTest(self):
        result = subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve()),
                                 'NativeLifecycle.' + self.native_name],
                                env=child_environment(), capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class IndependentProcesses(unittest.TestCase):
    def test_session_capacity_is_process_scoped(self):
        with tempfile.TemporaryDirectory(prefix='codeana-g1-processes-') as temporary:
            barrier = pathlib.Path(temporary)
            env = dict(child_environment(), CODEANA_NATIVE_LIFECYCLE_BARRIER=str(barrier))
            processes = []
            try:
                for _ in range(2):
                    processes.append(subprocess.Popen(
                        [sys.executable, str(pathlib.Path(__file__).resolve()),
                         'NativeLifecycle.test_session_capacity_and_closed_handle_are_explicit'],
                        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True))
                until = time.monotonic() + 30
                while len(list(barrier.glob('ready-*'))) != 2:
                    for process in processes:
                        if process.poll() is not None:
                            output, error = process.communicate()
                            self.fail('isolation child exited before rendezvous: ' + output + error)
                    self.assertLess(time.monotonic(), until, 'process isolation rendezvous timeout')
                    time.sleep(.01)
                (barrier / 'release').touch()
                for process in processes:
                    output, error = process.communicate(timeout=30)
                    self.assertEqual(process.returncode, 0, output + error)
            finally:
                for process in processes:
                    if process.poll() is None:
                        process.kill()
                    process.communicate()


def load_tests(loader, tests, pattern):
    names = loader.getTestCaseNames(NativeLifecycle)
    gc = json.loads((bundle_path() / 'manifest.json').read_text()).get('native_gc', 'serial')
    if gc == 'G1' and os.environ.get('CODEANA_NATIVE_LIFECYCLE_CHILD') != '1':
        # Preserve common ABI assertions; replace only unsupported in-process isolation.
        suite = unittest.TestSuite(ProcessLifecycle(name) for name in names
                                   if name != 'test_independent_isolates')
        suite.addTests(loader.loadTestsFromTestCase(IndependentProcesses))
        return suite
    return loader.loadTestsFromTestCase(NativeLifecycle)


if __name__ == '__main__':
    unittest.main(verbosity=2)
