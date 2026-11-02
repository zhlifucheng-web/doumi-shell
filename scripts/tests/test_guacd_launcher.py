"""Launcher integration tests run real child processes with fake guacd/docker binaries."""
import json
import os
from pathlib import Path
import signal
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]

FAKE = r'''#!/usr/bin/env python3
import json, os, signal, socket, sys, time
from pathlib import Path
kind = Path(sys.argv[0]).name
base = Path(os.environ['FIXTURE'])
def log(event):
    with (base / 'events').open('a') as f: f.write(json.dumps(event)+'\n')
def stop(sig, frame):
    log([kind, 'signal', sig]); sys.exit(int(os.environ.get('JAVA_SIGNAL_EXIT', '0')) if kind == 'java' else 0)
signal.signal(signal.SIGTERM, stop); signal.signal(signal.SIGINT, stop)
log([kind, *sys.argv[1:]])
if kind == 'java':
    (base / 'java-started').write_text(str(os.getpid()))
    (base / 'java-env').write_text(json.dumps([os.environ.get('DOUMI_GUACD_HOST'), os.environ.get('DOUMI_GUACD_PORT'), os.environ.get('DOUMI_LAUNCHER_PID')]))
    if os.environ.get('JAVA_WAIT') == '1':
        while True: time.sleep(.03)
    sys.exit(int(os.environ.get('JAVA_EXIT', '0')))
if kind == 'guacd':
    (base / 'guacd-pid').write_text(str(os.getpid()))
    if os.environ.get('GATEWAY_FAIL') == '1': sys.exit(23)
    if os.environ.get('GATEWAY_NO_LISTEN') == '1':
        while True: time.sleep(.03)
    s=socket.socket(); s.bind(('127.0.0.1', int(sys.argv[sys.argv.index('-l')+1]))); s.listen()
    while True:
        c,a=s.accept(); c.close()
if kind == 'docker':
    args=sys.argv[1:]
    if args[0] == 'create':
        (base/'docker-args').write_text(json.dumps(args)); print('fixture-owned-id')
    elif args[0] == 'start':
        if os.environ.get('DOCKER_START_FAIL') == '1': sys.exit(1)
        import subprocess
        cmd=[str(base/'bin/guacd'), '-f', '-b', '127.0.0.1', '-l', os.environ['DOUMI_GUACD_PORT']]
        p=subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (base/'docker-pid').write_text(str(p.pid)); print(args[1])
    elif args[0] == 'inspect': print('true')
    elif args[0] == 'rm':
        p=base/'docker-pid'
        if p.exists():
            try: os.kill(int(p.read_text()), signal.SIGTERM)
            except ProcessLookupError: pass
'''

@unittest.skipUnless(os.name == 'posix', 'Linux launcher process and signal tests require a POSIX host')
class GatewayLauncherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        (self.base/'bin').mkdir()
        (self.base/'doumi-shell.jar').touch()
        for name in ('run-linux.sh', 'guacd-helper.sh'):
            source = ROOT/'scripts'/name
            if source.exists(): (self.base/name).write_bytes(source.read_bytes())
        for name in ('java', 'guacd', 'docker'):
            p=self.base/'bin'/name; p.write_text(FAKE.replace('#!/usr/bin/env python3', '#!'+sys.executable, 1)); p.chmod(0o755)
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); self.port=s.getsockname()[1]
        self.env={**os.environ, 'PATH':str(self.base/'bin')+':'+os.environ['PATH'],
                  'FIXTURE':str(self.base), 'DOUMI_GUACD_PORT':str(self.port),
                  'DOUMI_GUACD_START_TIMEOUT':'3', 'DOUMI_GUACD_BIN':str(self.base/'bin/guacd')}
        self.env.pop('DOUMI_GUACD_MODE', None)
        self.processes=[]

    def tearDown(self):
        for p in self.processes:
            if p.poll() is None: p.kill(); p.wait()
        for name in ('guacd-pid', 'docker-pid'):
            p=self.base/name
            if p.exists():
                try: os.kill(int(p.read_text()), signal.SIGTERM)
                except ProcessLookupError: pass
        self.tmp.cleanup()

    def launch(self, *args, **env):
        p=subprocess.Popen(['bash', str(self.base/'run-linux.sh'), *args], env={**self.env, **env},
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.processes.append(p); return p

    def events(self):
        p=self.base/'events'
        return [json.loads(line) for line in p.read_text().splitlines()] if p.exists() else []

    def await_file(self, name):
        end=time.monotonic()+4
        while time.monotonic()<end:
            try:
                contents=(self.base/name).read_text().strip()
            except FileNotFoundError:
                contents=''
            if contents: return contents
            time.sleep(.02)
        self.fail(f'{name} was not ready; events={self.events()}')

    def test_default_and_external_start_java_without_gateway(self):
        (self.base/'guacd-helper.sh').unlink(missing_ok=True)
        for env in ({}, {'DOUMI_GUACD_MODE':'external', 'DOUMI_GUACD_HOST':'unreachable.invalid'}):
            with self.subTest(env=env):
                p=self.launch(**env); stdout,stderr=p.communicate(timeout=4)
                self.assertEqual(0, p.returncode, stderr)
                self.assertFalse(any(e[0] in ('guacd','docker') for e in self.events()))

    def test_native_waits_for_readiness_and_cleans_owned_process_preserving_java_status(self):
        p=self.launch(DOUMI_GUACD_MODE='native', JAVA_EXIT='17')
        stdout,stderr=p.communicate(timeout=5)
        self.assertEqual(17, p.returncode, stderr)
        self.assertTrue((self.base/'guacd-pid').exists(), 'native gateway was not started')
        self.assertIn(['guacd', 'signal', signal.SIGTERM], self.events())
        self.assertTrue((self.base/'java-started').exists())
        self.assertEqual(['127.0.0.1', str(self.port), str(p.pid)], json.loads((self.base/'java-env').read_text()))

    def test_application_args_survive_external_and_managed_launch(self):
        args=['--doumi.update.health-id=fixture id', '--server.port=8081', '--doumi.storage.data-dir=/tmp/data with spaces']
        for mode in ('external', 'native'):
            with self.subTest(mode=mode):
                p=self.launch(*args, DOUMI_GUACD_MODE=mode)
                stdout,stderr=p.communicate(timeout=5)
                self.assertEqual(0,p.returncode,stderr)
                java_events=[e for e in self.events() if e[0]=='java']
                self.assertEqual(args, java_events[-1][3:])
                if mode == 'native':
                    self.assertEqual(str(p.pid), json.loads((self.base/'java-env').read_text())[2])

    def test_gateway_failure_prevents_java(self):
        p=self.launch(DOUMI_GUACD_MODE='native', GATEWAY_FAIL='1')
        stdout,stderr=p.communicate(timeout=5)
        self.assertNotEqual(0, p.returncode)
        self.assertFalse((self.base/'java-started').exists())
        self.assertIn('guacd', stderr.lower())

    def test_readiness_timeout_cleans_gateway_without_starting_java(self):
        p=self.launch(DOUMI_GUACD_MODE='native', GATEWAY_NO_LISTEN='1', DOUMI_GUACD_START_TIMEOUT='2')
        stdout,stderr=p.communicate(timeout=5)
        self.assertNotEqual(0,p.returncode)
        self.assertFalse((self.base/'java-started').exists())
        self.assertIn(['guacd','signal',signal.SIGTERM], self.events())

    def test_docker_readiness_failure_removes_only_owned_container(self):
        p=self.launch(DOUMI_GUACD_MODE='docker', GATEWAY_NO_LISTEN='1', DOUMI_GUACD_START_TIMEOUT='2')
        stdout,stderr=p.communicate(timeout=5)
        self.assertNotEqual(0,p.returncode)
        self.assertFalse((self.base/'java-started').exists())
        self.assertIn(['docker','rm','--force','fixture-owned-id'], self.events())

    def test_docker_start_failure_removes_only_created_container(self):
        p=self.launch(DOUMI_GUACD_MODE='docker', DOCKER_START_FAIL='1')
        stdout,stderr=p.communicate(timeout=5)
        self.assertNotEqual(0,p.returncode)
        self.assertFalse((self.base/'java-started').exists())
        self.assertIn(['docker','rm','--force','fixture-owned-id'], self.events())

    def test_term_is_forwarded_to_java_and_gateway_is_cleaned(self):
        self.assert_signal_forwarded(signal.SIGTERM)

    def test_int_gracefully_terminates_java_and_gateway_is_cleaned(self):
        self.assert_signal_forwarded(signal.SIGINT, signal.SIGTERM)

    def test_int_stops_real_jvm_and_owned_gateway(self):
        jdk=Path(os.environ.get('JAVA_HOME', '/nonexistent'))
        tools={name: str(jdk/'bin'/name) if (jdk/'bin'/name).is_file() else shutil.which(name) for name in ('java', 'javac', 'jar')}
        if not all(tools.values()):
            self.skipTest('JDK java, javac and jar are required for real JVM signal regression')
        for name in ('java', 'javac'):
            check=subprocess.run([tools[name], '-version'], capture_output=True, timeout=15)
            if check.returncode:
                self.skipTest(f'{name} is installed without a usable JDK')
        source=self.base/'SignalFixture.java'
        source.write_text('''import java.nio.file.*;
public class SignalFixture {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.write(root.resolve("java-stopped"), "stopped".getBytes("UTF-8")); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }));
        Files.write(root.resolve("java-started"), java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0].getBytes("UTF-8"));
        Thread.sleep(Long.MAX_VALUE);
    }
}
''')
        subprocess.run([tools['javac'], '-d', str(self.base), str(source)], check=True, capture_output=True, timeout=20)
        subprocess.run([tools['jar'], 'cfe', str(self.base/'doumi-shell.jar'), 'SignalFixture', '-C', str(self.base), 'SignalFixture.class'], check=True, capture_output=True, timeout=20)
        runtime=self.base/'runtime/bin'; runtime.mkdir(parents=True)
        (runtime/'java').symlink_to(tools['java'])
        p=self.launch(str(self.base), DOUMI_GUACD_MODE='native')
        java_pid=int(self.await_file('java-started'))
        p.send_signal(signal.SIGINT)
        try:
            stdout,stderr=p.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            os.kill(java_pid, signal.SIGTERM)
            p.communicate(timeout=5)
            self.fail('SIGINT left the real JVM and managed launcher running')
        self.assertEqual(143,p.returncode,stderr)
        self.assertTrue((self.base/'java-stopped').exists(), 'JVM shutdown hook did not run')
        with self.assertRaises(ProcessLookupError): os.kill(java_pid, 0)
        self.assertIn(['guacd','signal',signal.SIGTERM], self.events())

    def assert_signal_forwarded(self, forwarded_signal, java_signal=None):
        p=self.launch(DOUMI_GUACD_MODE='native', JAVA_WAIT='1', JAVA_SIGNAL_EXIT='19')
        self.await_file('java-started'); p.send_signal(forwarded_signal)
        stdout,stderr=p.communicate(timeout=5)
        self.assertEqual(19, p.returncode, stderr)
        self.assertIn(['java','signal',java_signal or forwarded_signal], self.events())
        self.assertIn(['guacd','signal',signal.SIGTERM], self.events())

    def test_docker_uses_pinned_image_loopback_and_only_removes_owned_id(self):
        p=self.launch(DOUMI_GUACD_MODE='docker')
        stdout,stderr=p.communicate(timeout=5)
        self.assertEqual(0,p.returncode,stderr)
        self.assertTrue((self.base/'docker-args').exists(), 'Docker gateway was not created')
        args=json.loads((self.base/'docker-args').read_text())
        self.assertEqual('guacamole/guacd:1.6.0',args[-1])
        self.assertIn(f'127.0.0.1:{self.port}:4822',args)
        self.assertTrue(args[args.index('--name')+1].startswith('doumi-guacd-'))
        self.assertIn(['docker','rm','--force','fixture-owned-id'],self.events())

    def test_managed_modes_reject_nonloopback_host_and_occupied_port(self):
        p=self.launch(DOUMI_GUACD_MODE='native', DOUMI_GUACD_HOST='0.0.0.0')
        stdout,stderr=p.communicate(timeout=4)
        self.assertNotEqual(0,p.returncode)
        with socket.socket() as s:
            s.bind(('127.0.0.1',self.port)); s.listen()
            p=self.launch(DOUMI_GUACD_MODE='native')
            stdout,stderr=p.communicate(timeout=4)
            self.assertNotEqual(0,p.returncode)
        self.assertFalse((self.base/'java-started').exists())
        self.assertFalse((self.base/'guacd-pid').exists())

class FixtureReadinessTest(unittest.TestCase):
    def test_waits_for_marker_contents_when_file_is_created_before_pid_is_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            launcher=GatewayLauncherTest()
            launcher.base=Path(tmp)
            marker=launcher.base/'java-started'
            marker.touch()
            # Resume the writer on the first poll, after its empty file was visible.
            with patch.object(time, 'sleep', side_effect=lambda _: marker.write_text('12345')):
                self.assertEqual('12345', launcher.await_file('java-started'))

if __name__ == '__main__': unittest.main()
