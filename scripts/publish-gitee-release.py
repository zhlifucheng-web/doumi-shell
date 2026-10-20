"""Publish the current Windows and Linux packages to the Gitee release for version.json."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import requests

ROOT = Path(__file__).resolve().parent.parent
BASE = 'https://gitee.com/api/v5/repos/hahadoumi/web-shell'


def main():
    token = os.environ.get('GITEE_TOKEN') or os.environ.get('GITEE_ACCESS_TOKEN')
    if not token:
        raise RuntimeError('Set GITEE_TOKEN for this process before publishing')

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

    def call(method, path, data=None, allow404=False):
        try:
            response = session.request(method, BASE + path, params={'access_token': token} if method == 'GET' else None,
                                      data=(dict(data or {}, access_token=token) if method != 'GET' else None),
                                      timeout=(20, 600))
        except requests.RequestException as error:
            raise RuntimeError(f'Gitee {method} {path} transport failed ({type(error).__name__}); credential omitted') from None
        if allow404 and response.status_code == 404:
            return None
        if not 200 <= response.status_code < 300:
            raise RuntimeError(f'Gitee {method} {path} failed: HTTP {response.status_code}')
        return response.json()

    release = call('GET', '/releases/tags/' + tag, allow404=True)
    if release is None:
        notes = (f'## Doumi Shell {version}\n\n'
                 '- 增加可持久化的 AI 历史会话，可按服务器恢复最近会话，并在数据管理中清理。\n'
                 '- AI 消息与工具事件显示时间，并以本机密钥加密保存在 SQLite。\n'
                 '- 服务重启后恢复历史；中断的待确认操作不会自动执行。\n'
                 '- 保留附件、会话授权与服务器切换等 AI 助手能力。\n')
        release = call('POST', '/releases', data={
            'tag_name': tag, 'target_commitish': commit,
            'name': f'Doumi Shell {version}', 'prerelease': 'false', 'body': notes,
        })
    release_id = release['id']
    attachments = call('GET', f'/releases/{release_id}/attach_files')
    if not isinstance(attachments, list):
        raise RuntimeError('Unexpected Gitee attachment response')

    def contains_attachment(items, name):
        return any(item.get('name') == name or item.get('filename') == name for item in items)

    def upload(artifact):
        command = [
            'curl.exe', '--silent', '--show-error', '--http1.1', '--connect-timeout', '30', '--max-time', '600',
            '--request', 'POST', '--form', f'access_token={token}', '--form', f'file=@{artifact}',
            '--output', '-', '--write-out', '\n%{http_code}', f'{BASE}/releases/{release_id}/attach_files',
        ]
        result = subprocess.run(command, capture_output=True)
        body, _, status = result.stdout.rpartition(b'\n')
        if result.returncode or status.strip() != b'201':
            detail = result.stderr.decode('utf-8', errors='replace')[:400].replace(token, '[redacted]')
            raise RuntimeError(f'Gitee upload failed for {artifact.name}: HTTP {status.strip()}, curl {result.returncode}; {detail}')
        return json.loads(body.decode('utf-8'))

    for artifact in artifacts:
        if contains_attachment(attachments, artifact.name):
            print('Already uploaded:', artifact.name)
            continue
        for attempt in range(2):
            try:
                uploaded = upload(artifact)
                attachments.append(uploaded)
                print('Uploaded:', uploaded.get('name', artifact.name))
                break
            except RuntimeError as error:
                attachments = call('GET', f'/releases/{release_id}/attach_files')
                if contains_attachment(attachments, artifact.name):
                    print('Upload confirmed after interrupted response:', artifact.name)
                    break
                if attempt or 'transport failed' not in str(error):
                    raise
    print(f'Release: https://gitee.com/hahadoumi/web-shell/releases/tag/{tag}')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
