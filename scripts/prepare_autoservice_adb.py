#!/usr/bin/env python3
"""已授权L6的首次ADB准备：显式Android root Telnet入口，只处理AutoService和ADB开关。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path
import re
import shlex
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / ('diagnostics' if (HERE.parent / 'diagnostics').is_dir() else 'tools/hab')))
from telnet_session import Channel


class RootShell:
    """标记必须独占一行，防止Telnet命令回显被误认成已执行。原始响应不写日志。"""
    def __init__(self, channel):
        self.channel = channel

    def command(self, command, timeout=20):
        marker = 'ASPREP_' + uuid.uuid4().hex
        line = (f"printf '\\n{marker}_BEGIN\\n'; /system/bin/sh -c {shlex.quote(command)}; "
                f"_as_result=$?; printf '\\n{marker}_RC:%s\\n{marker}_END\\n' \"$_as_result\"\r\n")
        self.channel.socket.sendall(line.encode())
        deadline = time.monotonic() + timeout
        response = ''
        while time.monotonic() < deadline:
            response += self.channel.receive(min(.5, max(.01, deadline - time.monotonic())))
            if len(response) > 65536:
                raise ValueError('root_response_limit')
            normalized = response.replace('\r', '')
            match = re.search(r'(?:^|\n)' + marker + r'_BEGIN\n(.*?)\n' + marker + r'_RC:(\d+)\n' + marker + r'_END(?:\n|$)', normalized, re.S)
            if match:
                if match[2] != '0':
                    raise ValueError('root_command_failed')
                return match[1].strip()
        raise ValueError('root_command_timeout')


def foundation(raw, since):
    # dumpsys activity service会给App dump的每行加缩进；保留严格字段匹配，但不能依赖列0。
    line = next((s.strip().removeprefix('FOUNDATION_CHECK=') for s in raw.splitlines()
                 if s.strip().startswith('FOUNDATION_CHECK=')), '{}')
    data = json.loads(line)
    if data.get('state') != 'verified':
        return None
    if (type(data.get('adbUid')) is not int or data['adbUid'] != 0 or
        type(data.get('rootUid')) is not int or data['rootUid'] != 0 or
        type(data.get('tlsPort')) is not int or not 1 <= data['tlsPort'] <= 65535 or
        type(data.get('verifiedAtElapsedMillis')) is not int or data['verifiedAtElapsedMillis'] < since or
        data.get('identitySource') != 'autoservice_private_adb'):
        raise ValueError('app_foundation_evidence_invalid')
    return {k: data[k] for k in ('state', 'adbUid', 'rootUid', 'tlsPort', 'verifiedAtElapsedMillis', 'identitySource')}


KEY_READBACK = r'''set -eu
user=$(am get-current-user)
case "$user" in ''|*[!0-9]*) exit 20;; esac
pub=/data/user/$user/com.ecarx.autoservice/no_backup/adb/client.pub
target=/data/misc/adb/adb_keys
[ -s "$pub" ] && [ -f "$target" ]
blob=$(cut -d' ' -f1 "$pub")
awk -v expected="$blob" '$1 == expected {found=1} END {exit !found}' "$target"
[ "$(stat -c '%a:%u:%g' "$target")" = '640:1000:2000' ]
restorecon "$target"
printf 'key_registered=1\nmode=640:1000:2000\n'
ls -Zd "$target" | awk '{print "context=" $1}'
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, help='当前车机Android TCP/23地址；IPv6链路本地地址须带接口scope，不保存地址')
    parser.add_argument('--port', type=int, default=23)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--bundle', type=Path)
    source.add_argument('--apk', type=Path, help='定向修复时核对已安装的AutoService APK，无需重打其它App')
    parser.add_argument('--app-only', action='store_true', help='不从电脑赋权/开启TLS，由App独立完成首次准备')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--wait', type=int, default=150)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 30 <= args.wait <= 300:
        parser.error('port须为1..65535，wait须为30..300秒')
    if args.bundle and args.output.resolve().is_relative_to(args.bundle.resolve()):
        parser.error('报告必须在测试包之外')
    # 先纯离线验包，防止用旧APK产生的身份状态为新包背书。
    import subprocess
    if args.bundle:
        subprocess.run([sys.executable, str(args.bundle / 'verify_bundle.py'), str(args.bundle)], check=True)
    apk = args.apk if args.apk else args.bundle / 'app/AutoService-debug.apk'
    expected = hashlib.sha256(apk.read_bytes()).hexdigest()
    report = {'recorded_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'passed': False,
              'installed_by_script': False, 'private_key_exported': False, 'qnx_tested': False,
              'expected_autoservice_sha256': expected, 'app_managed_setup': args.app_only, 'steps': []}
    channel = None
    stage = 'android_root_entry'
    try:
        print('连接显式Android root入口；不会扫描地址或尝试口令。', flush=True)
        channel = Channel(args.host, args.port)
        shell = RootShell(channel)
        if shell.command('uname -s; id -u').splitlines() != ['Linux', '0']:
            raise ValueError('android_root_identity_failed')
        report['android_bootstrap_uid'] = 0
        report['steps'].append(stage)
        stage = 'installed_apk'
        actual = shell.command('set -eu; apk=$(pm path com.ecarx.autoservice | sed -n "s/^package://p"); '
                               '[ -n "$apk" ]; sha256sum "$apk" | cut -d" " -f1')
        if actual != expected:
            raise ValueError('install_matching_autoservice_first')
        report['steps'].append(stage)
        stage = 'app_managed_setup' if args.app_only else 'permission_tls_prepare'
        if not args.app_only:
            shell.command('set -eu; user=$(am get-current-user); '
                          'pm grant --user "$user" com.ecarx.autoservice android.permission.WRITE_SECURE_SETTINGS; '
                          'pm grant --user "$user" com.ecarx.autoservice android.permission.READ_LOGS; '
                          'settings put global adb_enabled 1; settings put global adb_wifi_enabled 1; '
                          'setprop persist.adb.tls_server.enable 1; '
                          '[ "$(settings get global adb_enabled)" = 1 ]; '
                          '[ "$(settings get global adb_wifi_enabled)" = 1 ]; '
                          '[ "$(getprop persist.adb.tls_server.enable)" = 1 ]')
        report['steps'].append(stage)
        since = int(shell.command("awk '{printf \"%.0f\\n\", $1 * 1000}' /proc/uptime"))
        stage = 'app_private_identity_authorization'
        # start使主动停止过的App恢复自动运行；prepare在同一服务owner队列内重验，不创建第二个helper owner。
        shell.command('set -e; am start-foreground-service --user current -n com.ecarx.autoservice/.AutoService -a start >/dev/null; '
                      'am start-foreground-service --user current -n com.ecarx.autoservice/.AutoService -a prepare >/dev/null')
        deadline = time.monotonic() + args.wait
        verified = None
        while time.monotonic() < deadline:
            raw = shell.command('dumpsys activity service com.ecarx.autoservice/.AutoService --foundation-check')
            verified = foundation(raw, since)
            if verified:
                break
            time.sleep(2)
        if verified is None:
            raise ValueError('app_foundation_not_verified')
        report['app_foundation'] = verified
        report['steps'].append(stage)
        stage = 'whitelist_readback'
        raw = shell.command(KEY_READBACK)
        if 'key_registered=1' not in raw.splitlines() or 'mode=640:1000:2000' not in raw.splitlines():
            raise ValueError('whitelist_readback_failed')
        context = re.search(r'^context=([A-Za-z0-9_:.,-]+)$', raw, re.M)
        if not context or not context[1].startswith('u:object_r:'):
            raise ValueError('whitelist_context_missing')
        report['whitelist'] = {'own_public_key_present': True, 'mode_owner_group': '640:1000:2000',
                               'selinux_context': context[1]}
        report['steps'].append(stage)
        report['passed'] = True
        print('通过：本App公钥在白名单、0640/system:shell、SELinux标签回读；AutoService自身TLS/ADB UID0与RootService UID0。QNX身份另验。')
    except (OSError, ValueError, KeyError, TypeError) as error:
        report['failed_stage'] = stage
        reason = str(error)
        report['reason'] = reason if reason in {'root_response_limit', 'root_command_failed', 'root_command_timeout',
            'android_root_identity_failed', 'install_matching_autoservice_first', 'app_foundation_evidence_invalid',
            'app_foundation_not_verified', 'whitelist_readback_failed', 'whitelist_context_missing'} else type(error).__name__
        print(f'未通过阶段：{stage} ({report["reason"]})；未保存原始响应/公钥/私钥/连接地址。', file=sys.stderr)
    finally:
        if channel:
            channel.socket.close()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
