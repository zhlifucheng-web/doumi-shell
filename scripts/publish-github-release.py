"""Publish the current Windows and Linux packages to the GitHub release for version.json."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import requests

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'zhlifucheng-web/doumi-shell'
API = f'https://api.github.com/repos/{REPOSITORY}'


def main():
    token = os.environ.get('GITHUB_TOKEN') or os.environ.get('GITHUB_ACCESS_TOKEN')
    if not token:
        raise RuntimeError('Set GITHUB_TOKEN for this process before publishing')

    version = json.loads((ROOT / 'shell-front/public/version.json').read_text(encoding='utf-8'))['version']
    if not isinstance(version, str) or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise RuntimeError('Invalid application version')
    tag = 'v' + version
    commit = subprocess.check_output(['git', 'rev-parse', tag + '^{}'], cwd=ROOT, text=True).strip()
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise RuntimeError('Commit all source changes before publishing')

    release_dir = Path(os.environ.get('DOUMI_RELEASE_DIR', ROOT / 'release'))
    artifacts = [
        release_dir / f'Doumi Shell-{version}.msi',
        release_dir / f'Doumi Shell-{version}.msi.sha256',
        release_dir / f'doumi-shell-{version}-linux-x64.tar.gz',
        release_dir / f'doumi-shell-{version}-linux-x64.tar.gz.sha256',
    ]
    for artifact in artifacts:
        if not artifact.is_file():
            raise FileNotFoundError(artifact)
    for artifact in (artifacts[0], artifacts[2]):
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        checksum = artifact.with_name(artifact.name + '.sha256').read_text(encoding='ascii').split()[0]
        if digest != checksum:
            raise RuntimeError(f'SHA256 mismatch for {artifact.name}')

    session = requests.Session()
    session.headers.update({
        'Accept': 'application/vnd.github+json',
        'Authorization': f'Bearer {token}',
        'X-GitHub-Api-Version': '2022-11-28',
    })

    def call(method, path, **kwargs):
        response = session.request(method, API + path, timeout=(20, 600), **kwargs)
        if not response.ok:
            raise RuntimeError(f'GitHub {method} {path} failed: HTTP {response.status_code}')
        return response.json()

    try:
        release = call('GET', '/releases/tags/' + tag)
    except RuntimeError as error:
        if 'HTTP 404' not in str(error):
            raise
        changelog = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
        section = re.search(r'^## ' + re.escape(version) + r'[^\n]*\n(.*?)(?=^## |\Z)',
                            changelog, re.MULTILINE | re.DOTALL)
        if not section:
            raise RuntimeError(f'Missing changelog for {version}')
        notes = f'## Doumi Shell {version}\n\n' + section.group(1).strip() + '\n'
        release = call('POST', '/releases', json={
            'tag_name': tag,
            'target_commitish': commit,
            'name': f'Doumi Shell {version}',
            'prerelease': False,
            'body': notes,
        })

    uploaded = {asset.get('name') for asset in release.get('assets', [])}
    upload_url = release['upload_url'].split('{', 1)[0]
    for artifact in artifacts:
        if artifact.name in uploaded:
            print('Already uploaded:', artifact.name)
            continue
        with artifact.open('rb') as stream:
            response = session.post(upload_url, params={'name': artifact.name},
                                    headers={'Content-Type': 'application/octet-stream'},
                                    data=stream, timeout=(20, 600))
        if response.status_code != 201:
            raise RuntimeError(f'GitHub upload failed for {artifact.name}: HTTP {response.status_code}')
        print('Uploaded:', artifact.name)
    print(f'Release: https://github.com/{REPOSITORY}/releases/tag/{tag}')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
