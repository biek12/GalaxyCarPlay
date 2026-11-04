#!/usr/bin/env python3
"""只读记录CarPlay两路请求与输出关系；不切换地图、不推断已实现车道级导航。"""
import argparse
from datetime import datetime
import json
from pathlib import Path
from zoneinfo import ZoneInfo
from prepare_autoservice_adb import Channel, RootShell
from remote_car import Car


COMMAND = 'dumpsys activity service com.ecarx.carplay/com.shilapi.xcertplay.DiPlaySessionService --navigation-state'
KNOWN_URLS = {'maps:/car/instrumentcluster', 'maps:/car/instrumentcluster/map',
              'maps:/car/instrumentcluster/instructioncard'}


def parse(raw):
    line = next((line.strip().removeprefix('NAVIGATION_STATE=') for line in raw.splitlines()
                 if line.strip().startswith('NAVIGATION_STATE=')), None)
    if line is None or len(line) > 32768:
        raise ValueError('navigation_diagnostics_missing')
    data = json.loads(line)
    if data.get('schema') != 1:
        raise ValueError('navigation_diagnostics_version')
    request = data.get('request') or {}
    outputs = data.get('outputs') or {}
    session = request.get('session') or {}

    def geometry(name):
        value = request.get(name)
        if not isinstance(value, dict):
            return None
        return {key: value.get(key) if type(value.get(key)) is int and 0 < value[key] <= 16384 else None
                for key in ('width', 'height')}

    def urls(name):
        value = session.get(name)
        return [item for item in value if isinstance(item, str) and item in KNOWN_URLS][:3] if isinstance(value, list) else []

    displays = data.get('androidDisplays')
    displays = [{key: item.get(key) for key in ('id', 'width', 'height', 'flags', 'presentation', 'private')
                 if type(item.get(key)) in (bool, int)} for item in displays[:12] if isinstance(item, dict)] if isinstance(displays, list) else []
    return dict(connected=data.get('connected') is True, main=geometry('main'), cluster=geometry('cluster'),
                android_displays=displays,
                active=request.get('active') is True,
                outputs={key: outputs.get(key) if type(outputs.get(key)) is bool else None
                         for key in ('mainAssigned', 'clusterAssigned', 'sharedSurface', 'mainVideoReceived')},
                mainFrameRendered=session.get('mainFrameRendered') is True,
                selectedClusterUrl=session.get('selectedClusterUrl') if session.get('selectedClusterUrl') in KNOWN_URLS else None,
                offeredClusterUrls=urls('offeredClusterUrls'),
                suggestionSeen=session.get('suggestionSeen') is True, suggestedClusterUrls=urls('suggestedClusterUrls'),
                detailMode='unknown')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    transport = parser.add_mutually_exclusive_group(required=True)
    transport.add_argument('--host', help='当前可达的Android Root Telnet地址，不是ADB地址')
    transport.add_argument('--remote', action='store_true', help='使用已配对的个人HTTPS会话')
    parser.add_argument('--session', type=Path, default=Path('.local/remote-session.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.remote:
        result = Car(json.loads(args.session.read_text())).job(dict(kind='shell', transport='ROOT', command=COMMAND))
        if result.get('exitCode') != 0 or result.get('timedOut') or result.get('truncated'):
            raise RuntimeError('remote_diagnostic_failed')
        raw = result.get('output', '')
    else:
        channel = Channel(args.host, 23)
        try:
            shell = RootShell(channel)
            if shell.command('uname -s; id -u').splitlines() != ['Linux', '0']:
                raise ValueError('android_identity_failed')
            raw = shell.command(COMMAND)
        finally:
            channel.socket.close()
    state = parse(raw)
    report = dict(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), read_only=True,
                  lane_level_visual_verified=False, state=state)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('已保存两路地图诊断；不代表车道级画面或限速已经验收。')


if __name__ == '__main__':
    main()
