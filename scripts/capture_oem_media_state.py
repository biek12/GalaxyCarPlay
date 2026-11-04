#!/usr/bin/env python3
"""只读核对原车媒体的原始手机透传、网络歌词投屏需求与媒体出口；不代表原厂可视验收。"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo
from remote_car import Car


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, default=Path('.local/remote-session.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    car = Car(json.loads(args.session.read_text()))
    result = car.job(dict(kind='shell', transport='ROOT', command=(
        'dumpsys activity service com.ecarx.carplay/com.shilapi.xcertplay.DiPlaySessionService --media-state; '
        'dumpsys activity service com.ecarx.autoservice/.AutoService | grep -E "projectionRequested=|features="; '
        'grep "OemDimMedia:" /data/user/0/com.ecarx.carplay/files/logs/debug.log | tail -n 16')))
    if result.get('exitCode') != 0 or result.get('timedOut') or result.get('truncated'):
        raise RuntimeError('media_diagnostic_transport_failed')
    raw = result.get('output', '')
    state = next((json.loads(line.split('MEDIA_STATE=', 1)[1]) for line in raw.splitlines() if 'MEDIA_STATE=' in line), None)
    if state is None:
        raise RuntimeError('carplay_media_session_not_running')
    # 去除传输ID与任意原始日志，只保留本轮选定的状态；不读取配对资料。
    def media(value):
        return {key: value.get(key) for key in ('title', 'artist', 'album', 'playing', 'elapsed')}
    report = dict(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), read_only=True,
                  original_instrument_visual_verified=False,
                  raw=media(state.get('raw', {})), oem=media(state.get('oem', {})),
                  canonical=media(state.get('canonical', {})),
                  raw_forwarded=media(state.get('raw', {})) == media(state.get('oem', {})),
                  oem_lyric_source=state.get('oemLyricSource'),
                  phone_lyric_present=state.get('phoneLyricPresent'),
                  lookup=state.get('lookup'), oem_dim=state.get('oemDim'),
                  projection_requested=bool(re.search(r'projectionRequested=true', raw)),
                  stages=[line.split('OemDimMedia:', 1)[1].strip() for line in raw.splitlines() if 'OemDimMedia:' in line])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('raw_forwarded', 'oem_lyric_source', 'phone_lyric_present',
                                                  'projection_requested', 'lookup', 'oem_dim')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
