#!/usr/bin/env python3
"""Build both distributions from the same tested frontend/backend sources."""
import argparse
import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent

def run(args, cwd):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    version = args.version.removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        parser.error('version must be X.Y.Z or vX.Y.Z')
    if any(int(part) > limit for part, limit in zip(version.split('.'), (255, 255, 65535))):
        parser.error('version exceeds Windows MSI version limits')
    node = shutil.which('node')
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
    maven = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if not all((node, npm, maven)):
        parser.error('Node 24, npm and Maven with JDK 17 must be on PATH')
    (ROOT / 'shell-front/public/version.json').write_text(json.dumps({'version': version})+'\n', encoding='utf-8')
    # Keep local dependencies and editors untouched, while preserving Help.vue's docs-relative imports.
    build = ROOT / '.build/ci'
    frontend = build / 'frontend'
    frontend.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / 'docs', build / 'docs', dirs_exist_ok=True)
    for entry in ('src', 'public', 'tests'):
        shutil.copytree(ROOT / 'shell-front' / entry, frontend / entry, dirs_exist_ok=True)
    for entry in ('package.json', 'package-lock.json', 'vite.config.js', 'index.html'):
        shutil.copy2(ROOT / 'shell-front' / entry, frontend / entry)
    run([npm, 'ci'], frontend)
    run([node, '--test', *sorted(glob.glob('tests/*.test.js', root_dir=frontend))], frontend)
    run([npm, 'run', 'build'], frontend)
    static = ROOT / 'shell-end/src/main/resources/static'
    if static.exists():
        shutil.rmtree(static)
    shutil.copytree(frontend / 'dist', static)
    run([maven, '--batch-mode', 'clean', 'verify'], ROOT / 'shell-end')
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v'], ROOT)
    out = Path(os.environ.get('DOUMI_RELEASE_DIR', ROOT / 'release')).resolve()
    out.mkdir(parents=True, exist_ok=True)
    for source, name in ((ROOT / 'shell-end/target/shell-end-1.0.0-SNAPSHOT.jar', 'doumi-shell.jar'),
                         (ROOT / 'shell-end/target/shell-end-1.0.0-SNAPSHOT-updater.jar', 'doumi-updater.jar'),
                         (ROOT / 'shell-end/config/application.yml.example', 'config.example.yml')):
        shutil.copy2(source, out / name)
    if os.name == 'nt':
        powershell = shutil.which('pwsh') or shutil.which('powershell')
        run([powershell, '-NoProfile', '-File', ROOT / 'scripts/prepare-build-tools.ps1', '-SkipJdk'], ROOT)
        run([powershell, '-NoProfile', '-File', ROOT / 'scripts/package-msi.ps1', '-SkipBuild', '-Version', version,
             '-JdkHome', os.environ['JAVA_HOME'], '-WixHome', ROOT / '.build-tools/wix3/tools'], ROOT)
        for check in ('test-msi-upgrade.ps1', 'test-msi-install-options.ps1'):
            run([powershell, '-NoProfile', '-File', ROOT / 'scripts' / check, '-MsiPath', out / f'Doumi.Shell-{version}.msi'], ROOT)
    else:
        run([sys.executable, ROOT / 'scripts/package-linux.py'], ROOT)

if __name__ == '__main__':
    main()
