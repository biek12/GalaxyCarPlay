#!/usr/bin/env python3
"""为现有只读现场工具提供 shell 传输；显式 Telnet 地址，绝不建立电脑 ADB 连接。"""
import shlex
import sys
from prepare_autoservice_adb import Channel, RootShell


def main():
    args = sys.argv[1:]
    if len(args) < 4 or args[0] != '-s' or args[2] != 'shell':
        raise ValueError('仅支持 -s 当前Android地址 shell 命令；不支持ADB连接或安装')
    host = args[1]
    parts = args[3:]
    command = parts[0] if len(parts) == 1 else shlex.join(parts)
    channel = Channel(host, 23)
    try:
        shell = RootShell(channel)
        if shell.command('uname -s; id -u').splitlines() != ['Linux', '0']:
            raise ValueError('android_root_identity_failed')
        print(shell.command(command, timeout=40))
    finally:
        channel.socket.close()


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError):
        # 地址、命令、原始响应和异常正文不进入诊断文件。
        raise SystemExit('Telnet现场命令失败；未保存地址或原始响应')
