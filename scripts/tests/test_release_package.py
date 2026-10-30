import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
class LinuxPackageTest(unittest.TestCase):
    def test_package_contains_updater_and_matching_checksum(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            version = json.loads((ROOT / 'shell-front/public/version.json').read_text())['version']
            with zipfile.ZipFile(out / 'doumi-shell.jar', 'w') as jar:
                jar.writestr('BOOT-INF/classes/static/version.json', json.dumps({'version': version}))
            (out / 'doumi-updater.jar').write_bytes(b'fixture-updater')
            (out / 'config.example.yml').write_text('server: {}')
            env = {**os.environ, 'DOUMI_RELEASE_DIR': str(out)}
            subprocess.run([sys.executable, str(ROOT / 'scripts/package-linux.py')], env=env, check=True, capture_output=True)
            package = out / f'doumi-shell-{version}-linux-x64.tar.gz'
            with tarfile.open(package) as archive:
                self.assertIn(f'doumi-shell-{version}/doumi-updater.jar', archive.getnames())
                self.assertFalse(any('/data/' in name for name in archive.getnames()))
                helper = archive.getmember(f'doumi-shell-{version}/guacd-helper.sh')
                self.assertTrue(helper.mode & 0o111)
                self.assertNotIn(b'\r', archive.extractfile(helper).read())
                self.assertIn(f'doumi-shell-{version}/docs/rdp.md', archive.getnames())
            self.assertEqual(hashlib.sha256(package.read_bytes()).hexdigest(), package.with_name(package.name+'.sha256').read_text().split()[0])
            with zipfile.ZipFile(out / 'doumi-shell.jar', 'w') as jar:
                jar.writestr('BOOT-INF/classes/static/version.json', '{"version":"0.0.0"}')
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/package-linux.py')], env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)
if __name__ == '__main__':
    unittest.main()
