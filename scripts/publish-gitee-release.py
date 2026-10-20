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
                 '- 修复配置导入成功数量与实际落库记录不一致的问题，导入完成前核验记录完整性。\n'
                 '- 支持直接删除主机并级联清理其账户、主机脚本和定时配置；保留执行历史，运行中的任务会阻止删除。\n'
                 '- 定时脚本由服务端独立建立 SSH 连接，不依赖浏览器或已有交互会话；未完整发送脚本或未收到远端退出码时不会报告成功。\n'
                 '- 执行历史对话框支持复制标准输出、错误输出和错误信息。\n'
                 '- AI 助手默认直接读取信息，仅对命令、创建、修改、删除等写操作逐项询问授权。\n'
                 '- 可为当前会话启用全部工具权限，写操作自动执行。\n'
                 '- README 补充产品体验、AI 能力及 Windows/Linux 安装说明。\n'
                 '- 提供 Windows MSI 与 Linux x64 安装包。\n')
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
