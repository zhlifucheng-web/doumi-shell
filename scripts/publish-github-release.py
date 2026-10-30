#!/usr/bin/env python3
"""Publish only a complete, locally verified pair of platform packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

def expected_assets(version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Invalid stable version')
    packages = [f'Doumi.Shell-{version}.msi', f'doumi-shell-{version}-linux-x64.tar.gz']
    return packages + [name + '.sha256' for name in packages]

def validate_assets(directory, version):
    names = expected_assets(version)
    for name in names[:2]:
        package, checksum = directory / name, directory / (name + '.sha256')
        if not package.is_file() or package.stat().st_size == 0 or not checksum.is_file() or checksum.stat().st_size > 4096:
            raise ValueError(f'Missing or invalid asset: {name}')
        parts = checksum.read_text(encoding='ascii').strip().split()
        if len(parts) != 2 or parts[1].lstrip('*') != name or not re.fullmatch('[a-fA-F0-9]{64}', parts[0]):
            raise ValueError(f'Invalid checksum file: {name}')
        with package.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != parts[0].lower():
            raise ValueError(f'Checksum mismatch: {name}')
    return names

def publish_workflow_release():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'v\d+\.\d+\.\d+', args.tag):
        parser.error('Release tag must be vX.Y.Z')
    names = validate_assets(args.directory, args.tag[1:])
    repo = os.environ['GITHUB_REPOSITORY']
    def gh(*arguments, check=True):
        return subprocess.run(['gh', *arguments, '--repo', repo], check=check, capture_output=True, text=True)
    existing = gh('release', 'view', args.tag, '--json', 'isDraft', check=False)
    if existing.returncode == 0:
        if not json.loads(existing.stdout)['isDraft']:
            raise RuntimeError('Refusing to overwrite an already published release; create a new version tag')
    else:
        gh('release', 'create', args.tag, '--draft', '--verify-tag', '--title', f'Doumi Shell {args.tag[1:]}', '--generate-notes')
    gh('release', 'upload', args.tag, *[str(args.directory / name) for name in names], '--clobber')
    # gh resolves draft releases too; the public tag endpoint cannot see them.
    # Its asset list excludes GitHub's generated source archives.
    remote = gh('release', 'view', args.tag, '--json', 'assets')
    release = json.loads(remote.stdout)
    assets = {asset['name']: asset for asset in release['assets']}
    for name in names:
        asset = assets.get(name)
        if not asset or asset.get('state') != 'uploaded' or asset.get('size') != (args.directory / name).stat().st_size:
            raise RuntimeError(f'Release remains a draft because an uploaded asset is incomplete: {name}')
    gh('release', 'edit', args.tag, '--draft=false', '--latest')
    print(f'Published https://github.com/{repo}/releases/tag/{args.tag}')

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'zhlifucheng-web/doumi-shell'
API = f'https://api.github.com/repos/{REPOSITORY}'


def publish_current_release():
    import requests

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
    artifacts = [release_dir / name for name in validate_assets(release_dir, version)]

    session = requests.Session()
    session.headers.update({
        'Accept': 'application/vnd.github+json',
        'Authorization': f'Bearer {token}',
        'X-GitHub-Api-Version': '2022-11-28',
    })

    def call(method, path, **kwargs):
        for attempt in range(3):
            try:
                response = session.request(method, API + path, timeout=(20, 600), **kwargs)
                if not response.ok:
                    raise RuntimeError(f'GitHub {method} {path} failed: HTTP {response.status_code}')
                return response.json()
            except requests.RequestException:
                if attempt == 2:
                    raise
                time.sleep(2 * (attempt + 1))

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
        asset_name = artifact.name
        if asset_name in uploaded:
            print('Already uploaded:', asset_name)
            continue
        for attempt in range(3):
            try:
                with artifact.open('rb') as stream:
                    response = session.post(upload_url, params={'name': asset_name},
                                            headers={'Content-Type': 'application/octet-stream'},
                                            data=stream, timeout=(20, 600))
                if response.status_code != 201:
                    raise RuntimeError(f'GitHub upload failed for {asset_name}: HTTP {response.status_code}')
                print('Uploaded:', asset_name)
                break
            except requests.RequestException:
                release = call('GET', '/releases/tags/' + tag)
                uploaded = {asset.get('name') for asset in release.get('assets', [])}
                if asset_name in uploaded:
                    print('Upload confirmed after interrupted response:', asset_name)
                    break
                if attempt == 2:
                    raise
                time.sleep(2 * (attempt + 1))
    print(f'Release: https://github.com/{REPOSITORY}/releases/tag/{tag}')


def main():
    if len(sys.argv) > 1:
        publish_workflow_release()
    else:
        publish_current_release()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
