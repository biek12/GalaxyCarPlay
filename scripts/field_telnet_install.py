#!/usr/bin/env python3
"""电脑ADB尚未连通时，经已授权Android root入口安装同轮自有APK；原签名/保留数据。"""
import argparse
import base64
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time
import uuid
from prepare_autoservice_adb import Channel, RootShell


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--results', required=True, type=Path)
    args = parser.parse_args()
    if args.results.resolve().is_relative_to(args.bundle.resolve()):
        parser.error('记录必须在包外')
    subprocess.run([sys.executable, str(args.bundle / 'verify_bundle.py'), str(args.bundle)], check=True)
    manifest = json.loads((args.bundle / 'manifest.json').read_text())
    items = [('AutoService', 'com.ecarx.autoservice', 'app/AutoService-debug.apk'),
             ('CarPlay', 'com.ecarx.carplay', 'app/CarPlay-debug.apk'),
             ('AutoCluster', 'com.galaxy.autocluster', 'app/AutoCluster-debug.apk'),
             ('ProjectionSmoke', 'com.ecarx.autoservice.test', 'tests/ProjectionSmoke-test.apk')]
    report = {'recorded_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'passed': False,
              'preserve_data': True, 'packages': {}, 'private_key_transferred': False}
    channel = None
    try:
        channel = Channel(args.host, 23)
        shell = RootShell(channel)
        if shell.command('uname -s; id -u').splitlines() != ['Linux', '0']:
            raise ValueError('android_root_identity_failed')
        # 只调整本次Telnet自有PTY；关闭回显和PS2后流式here-doc，避免每行回显堵塞。
        shell.command('stty -echo')
        channel.socket.sendall(b"PS2=''\n")
        def upload(source, destination):
            marker = 'ASTRANSFER_' + uuid.uuid4().hex
            payload = base64.encodebytes(source.read_bytes())
            failure = []
            def send():
                try:
                    channel.socket.sendall(('base64 -d > ' + shlex.quote(destination) + " <<'" + marker + "_DATA'\n").encode())
                    for start in range(0, len(payload), 32768):
                        channel.socket.sendall(payload[start:start + 32768])
                    channel.socket.sendall((marker + '_DATA\n_as_transfer=$?; printf "\\n' + marker + ':%%s\\n" "$_as_transfer"\n').replace('%%s', '%s').encode())
                except OSError as error:
                    failure.append(type(error).__name__)
            sender = threading.Thread(target=send, daemon=True)
            sender.start()
            deadline = time.monotonic() + 150
            response = ''
            while time.monotonic() < deadline:
                response += channel.receive(.5)
                if len(response) > 65536 or failure:
                    raise ValueError('transfer_stream_failed')
                match = re.search(r'(?:^|\n)' + marker + r':(\d+)(?:\r?\n|$)', response)
                if match:
                    sender.join(timeout=2)
                    if match[1] != '0':
                        raise ValueError('transfer_decode_failed')
                    return
            raise ValueError('transfer_stream_timeout')
        stamp = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S')
        remote = '/data/local/tmp/galaxy-field-' + stamp
        shell.command('umask 077; mkdir ' + shlex.quote(remote))
        for label, package, relative in items:
            print(label + ': 核对/传输/同包更新', flush=True)
            expected = manifest['files'][relative]['sha256']
            actual = shell.command('apk=$(pm path ' + package + ' | sed -n "s/^package://p"); '
                                   'if [ -n "$apk" ]; then sha256sum "$apk" | cut -d" " -f1; fi')
            entry = {'expected_sha256': expected, 'already_matched': actual == expected, 'installed': False}
            report['packages'][label] = entry
            if actual != expected:
                destination = remote + '/' + label + '.apk'
                upload(args.bundle / relative, destination)
                digest = shell.command('sha256sum ' + shlex.quote(destination) + ' | cut -d" " -f1')
                if digest != expected:
                    raise ValueError('transferred_apk_digest_failed')
                result = shell.command('pm install -r ' + shlex.quote(destination), timeout=60)
                if not any(line.strip() == 'Success' for line in result.splitlines()):
                    raise ValueError('package_update_failed')
                entry['installed'] = True
                shell.command('rm -f ' + shlex.quote(destination))
            installed = shell.command('set -eu; apk=$(pm path ' + package + ' | sed -n "s/^package://p"); '
                                      'sha256sum "$apk" | cut -d" " -f1')
            entry['installed_sha256'] = installed
            if installed != expected:
                raise ValueError('installed_apk_digest_failed')
            print(label + ': 车机已装摘要一致', flush=True)
        shell.command('rmdir ' + shlex.quote(remote))
        report['passed'] = True
    except (OSError, ValueError) as error:
        allowed_errors = {'android_root_identity_failed', 'transferred_apk_digest_failed', 'package_update_failed',
                          'installed_apk_digest_failed', 'root_command_failed', 'root_command_timeout', 'root_response_limit'}
        report['error'] = str(error) if str(error) in allowed_errors else type(error).__name__
        print('现场安装未通过：' + report['error'], file=sys.stderr)
    finally:
        if channel:
            channel.socket.close()
        args.results.mkdir(parents=True, exist_ok=True)
        report['tool_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        (args.results / 'telnet-installed-check.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
