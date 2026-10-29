import hashlib
from pathlib import Path
import runpy
import tempfile
import unittest

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
if __name__ == '__main__':
    unittest.main()
