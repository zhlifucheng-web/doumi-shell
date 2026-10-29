#!/usr/bin/env python3
"""Publish only a complete, locally verified pair of platform packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

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

def main():
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
    # API asset lists exclude GitHub's generated source archives.
    remote = subprocess.run(['gh', 'api', f'repos/{repo}/releases/tags/{args.tag}'], check=True, capture_output=True, text=True)
    release = json.loads(remote.stdout)
    assets = {asset['name']: asset for asset in release['assets']}
    for name in names:
        asset = assets.get(name)
        if not asset or asset.get('state') != 'uploaded' or asset.get('size') != (args.directory / name).stat().st_size:
            raise RuntimeError(f'Release remains a draft because an uploaded asset is incomplete: {name}')
    gh('release', 'edit', args.tag, '--draft=false', '--latest')
    print(f'Published https://github.com/{repo}/releases/tag/{args.tag}')

if __name__ == '__main__':
    main()
