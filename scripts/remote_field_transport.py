#!/usr/bin/env python3
"""将既有只读采样脚本接到个人HTTPS会话；不建立电脑ADB连接。"""
import json
import os
from pathlib import Path
import shlex
import sys
from remote_car import Car


def main():
    args = sys.argv[1:]
    if len(args) < 4 or args[:3] != ['-s', 'remote', 'shell']:
        raise ValueError('只接受 -s remote shell 命令')
    parts = args[3:]
    command = parts[0] if len(parts) == 1 else shlex.join(parts)
    session = Path(os.environ.get('GALAXY_REMOTE_SESSION', '.local/remote-session.json'))
    car = Car(json.loads(session.read_text()))
    result = car.job(dict(kind='shell', transport='ROOT', command=command))
    if result.get('error') or result.get('timedOut') or result.get('truncated') or result.get('exitCode') != 0:
        raise RuntimeError('远程采样失败；不保存原始命令输出')
    print(result.get('output', ''), end='')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('远程采样失败，请检查会话与车机连接；未自动重跑命令')
