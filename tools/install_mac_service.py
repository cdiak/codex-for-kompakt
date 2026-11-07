#!/usr/bin/env python3
"""Install a per-user companion service; no root, credentials, or public listener."""
import argparse
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys

LABEL = 'org.kompakt.codex.companion'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--codex', type=Path, required=True)
    parser.add_argument('--write-only', type=Path, help='Write a reviewable plist instead of installing')
    parser.add_argument('--install-dir', type=Path, default=Path.home() / 'Library/Application Support/CodexForKompakt', help='Companion application files, outside protected Documents/Desktop/Downloads folders')
    args = parser.parse_args()
    os.umask(0o077)
    root = Path(__file__).resolve().parent.parent
    runtime, workspace, codex = args.runtime.resolve(), args.workspace.resolve(), args.codex.resolve()
    if not codex.is_file():
        parser.error('Codex executable not found')
    if not all((runtime / name).is_file() for name in ('token', 'server.crt', 'server.key')):
        parser.error('Prepare and pair this runtime before installing the service.')
    runtime.mkdir(parents=True, exist_ok=True)
    workspace.mkdir(parents=True, exist_ok=True)
    install_dir = args.install_dir.resolve()
    for folder in ('Documents', 'Desktop', 'Downloads'):
        protected = Path.home() / folder
        for path in (runtime, workspace, install_dir):
            if path == protected or protected in path.parents:
                parser.error('Background services need runtime and project paths outside Documents/Desktop/Downloads, unless separately granted macOS access.')
    value = {
        'Label': LABEL,
        'ProgramArguments': ['/usr/bin/caffeinate', '-i', sys.executable,
            str(install_dir / 'companion.py'), '--runtime', str(runtime), '--workspace', str(workspace),
            '--codex', str(codex), '--bind', '127.0.0.1'],
        'WorkingDirectory': str(install_dir),
        'EnvironmentVariables': {'PYTHONDONTWRITEBYTECODE': '1', 'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
        'RunAtLoad': True,
        'KeepAlive': True,
        'ThrottleInterval': 10,
        'Umask': 0o077,
        'StandardOutPath': str(runtime / 'service.log'),
        'StandardErrorPath': str(runtime / 'service-error.log'),
    }
    destination = args.write_only or Path.home() / 'Library/LaunchAgents' / (LABEL + '.plist')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(plistlib.dumps(value))
    os.chmod(destination, 0o600)
    if args.write_only:
        print('Wrote service configuration:', destination)
        return
    domain = 'gui/' + str(os.getuid())
    subprocess.run(['launchctl', 'bootout', domain + '/' + LABEL], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    install_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / 'companion.py', install_dir / 'companion.py')
    for name in ('service.log', 'service-error.log'):
        log = runtime / name
        log.touch(exist_ok=True)
        os.chmod(log, 0o600)
    subprocess.run(['launchctl', 'bootstrap', domain, str(destination)], check=True)
    print('Installed companion service. Starts at login and prevents idle system sleep while running.')
    print('Stop: launchctl bootout ' + domain + '/' + LABEL)
    print('Remove the LaunchAgent plist as well to disable future automatic startup.')


if __name__ == '__main__':
    main()
