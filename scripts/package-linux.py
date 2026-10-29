import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import zipfile

root = Path(__file__).resolve().parent.parent
version = json.loads((root / 'shell-front/public/version.json').read_text(encoding='utf-8'))['version']
out = Path(os.environ.get('DOUMI_RELEASE_DIR', root / 'release'))
out.mkdir(parents=True, exist_ok=True)
package = out / f'doumi-shell-{version}-linux-x64.tar.gz'
prefix = f'doumi-shell-{version}'
release = Path(os.environ.get('DOUMI_RELEASE_DIR', root / 'release'))
files = [release / 'doumi-updater.jar', release / 'doumi-shell.jar', root / 'README.md', root / 'LICENSE',
         release / 'config.example.yml', root / 'scripts/run-linux.sh']
files += sorted(path for path in (root / 'docs').rglob('*') if path.is_file())
for path in files:
    if not path.is_file():
        raise FileNotFoundError(path)

with zipfile.ZipFile(release / 'doumi-shell.jar') as jar:
    embedded_version = json.loads(jar.read('BOOT-INF/classes/static/version.json'))['version']
if embedded_version != version:
    raise ValueError(f'JAR version {embedded_version} differs from package version {version}')

with tarfile.open(package, 'w:gz') as archive:
    for path in files:
        if path.is_relative_to(root / 'docs'):
            name = f'docs/{path.relative_to(root / "docs").as_posix()}'
        else:
            name = path.name
        content = path.read_bytes()
        if name == 'run-linux.sh':
            content = content.replace(b'\r\n', b'\n')
            if not content.startswith(b'#!/usr/bin/env bash\n'):
                raise ValueError('Linux launcher has an invalid header')
        info = tarfile.TarInfo(f'{prefix}/{name}')
        info.mode = 0o755 if name == 'run-linux.sh' else 0o644
        info.size = len(content)
        archive.addfile(info, io.BytesIO(content))

with package.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
package.with_name(package.name + '.sha256').write_text(f'{digest}  {package.name}\n', encoding='ascii')
with tarfile.open(package) as archive:
    launcher = archive.getmember(f'{prefix}/run-linux.sh')
    if launcher.mode & 0o111 == 0 or b'\r' in archive.extractfile(launcher).read():
        raise ValueError('Linux launcher permissions or line endings are invalid')
print(f'PASS: {package} ({package.stat().st_size} bytes, SHA256 {digest})')
