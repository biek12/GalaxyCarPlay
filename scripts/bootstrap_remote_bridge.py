#!/usr/bin/env python3
"""首次局域网安装并准备个人远程通道；登记材料/验证码仅在内存流转，不进入报告。"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import time
from install_field_http import install
from prepare_autoservice_adb import Channel, RootShell
from remote_car import request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--enrollment-file', type=Path, required=True)
    parser.add_argument('--hours', type=int, choices=[12, 24], default=12)
    parser.add_argument('--session', type=Path, default=Path('.local/remote-session.json'))
    parser.add_argument('--origin', default='https://bridge.example.invalid/galaxy')
    args = parser.parse_args()
    enrollment = args.enrollment_file.read_text().strip()
    if not re.fullmatch('[0-9a-f]{64}', enrollment):
        raise ValueError('invalid_enrollment')
    install(args.bundle, args.host, args.results)
    channel = Channel(args.host, 23)
    try:
        shell = RootShell(channel)
        if shell.command('uname -s; id -u').splitlines() != ['Linux', '0']:
            raise ValueError('android_identity_failed')
        user = shell.command('am get-current-user')
        if not user.isdigit():
            raise ValueError('android_user_invalid')
        directory = '/data/user/' + user + '/com.ecarx.autoservice'
        destination = directory + '/no_backup/remote-enrollment'
        # 值严格限定十六进制；不打印Shell正文，不把登记材料放进APK或诊断报告。
        shell.command('umask 077; printf "%s\\n" ' + shlex.quote(enrollment) + ' > ' + shlex.quote(destination) + '; chown "$(stat -c %u:%g ' + shlex.quote(directory) + ')" ' + shlex.quote(destination))
        prefs = directory + '/shared_prefs/remote-access.xml'
        read_code = "sed -n 's/.*<string name=\"code\">\\([0-9]*\\)<\\/string>.*/\\1/p' " + shlex.quote(prefs)
        old_code = shell.command('if [ -f ' + shlex.quote(prefs) + ' ]; then ' + read_code + '; fi')
        shell.command('am start-foreground-service -n com.ecarx.autoservice/.AutoService -a remote-authorize --ei hours ' + str(args.hours))
        shell.command('am start-foreground-service -n com.galaxy.autocluster/.ClusterRenderService -a sync-energy')
        deadline = time.monotonic() + 90
        session = None
        while time.monotonic() < deadline:
            code = shell.command('if [ -f ' + shlex.quote(prefs) + ' ]; then ' + read_code + '; fi')
            if re.fullmatch('[0-9]{8}', code) and code != old_code:
                session = request(args.origin, '/bridge/pair', {'code': code})
                break
            time.sleep(2)
        if session is None:
            raise TimeoutError('remote_registration_not_ready')
        session['origin'] = args.origin
        args.session.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(args.session, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump(session, output)
        report = dict(paired=True, until=session['until'], energy_sync_requested=True,
                      scope='pairing only; physical SIM routing and display acceptance separate')
        (args.results / 'remote-bootstrap.json').write_text(json.dumps(report, indent=2))
        print('远程配对完成；电脑会话已保存，未输出验证码或凭据。')
    finally:
        channel.socket.close()


if __name__ == '__main__':
    main()
