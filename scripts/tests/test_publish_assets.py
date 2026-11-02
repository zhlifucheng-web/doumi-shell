import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

publish = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'publish-github-release.py'))
class PublishAssetsTest(unittest.TestCase):
    def test_requires_both_platforms_and_matching_filename_checksums(self):
        validate = publish['validate_assets']
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError):
                validate(root, '1.0.63')
            for name in publish['expected_assets']('1.0.63')[:2]:
                (root/name).write_bytes(b'fixture')
                (root/(name+'.sha256')).write_text(hashlib.sha256(b'fixture').hexdigest()+'  '+name+'\n')
            self.assertEqual(4, len(validate(root, '1.0.63')))
            (root/'Doumi.Shell-1.0.63.msi').write_bytes(b'corrupted')
            with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
                validate(root, '1.0.63')
            with self.assertRaises(ValueError):
                validate(root, '../bad')

    def test_verifies_draft_assets_before_publishing_without_public_tag_lookup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            packages=['Doumi.Shell-1.0.63.msi', 'doumi-shell-1.0.63-linux-x64.tar.gz']
            for name in packages:
                (root/name).write_bytes(b'fixture')
                (root/(name+'.sha256')).write_text(hashlib.sha256(b'fixture').hexdigest()+'  '+name+'\n')
            assets=[{'name':p.name, 'size':p.stat().st_size, 'state':'uploaded'} for p in root.iterdir()]
            for complete in (True, False):
                with self.subTest(complete=complete):
                    remote=assets if complete else assets[:-1]
                    published=[]
                    def gh(command, **kwargs):
                        if command[1]=='api':
                            raise subprocess.CalledProcessError(1, command, stderr='Draft is not available by public tag URL')
                        if command[1:3]==['release','view']:
                            body={'isDraft':True} if command[-3]=='isDraft' else {'assets':remote}
                            return subprocess.CompletedProcess(command, 0, json.dumps(body))
                        if command[1:3]==['release','edit']:
                            published.append(command)
                        return subprocess.CompletedProcess(command, 0, '')
                    with patch.object(subprocess, 'run', side_effect=gh), \
                         patch.object(sys, 'argv', ['publish', '--tag', 'v1.0.63', '--directory', str(root)]), \
                         patch.dict(os.environ, {'GITHUB_REPOSITORY':'zhlifucheng-web/doumi-shell'}):
                        if complete:
                            publish['publish_workflow_release']()
                            self.assertEqual(1, len(published))
                        else:
                            with self.assertRaisesRegex(RuntimeError, 'remains a draft'):
                                publish['publish_workflow_release']()
                            self.assertFalse(published)
if __name__ == '__main__':
    unittest.main()
