#!/usr/bin/env python3
"""显式只读对照CarPlay上行状态；不拨号、不切换麦克风、不读取原始语音。"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import time
from zoneinfo import ZoneInfo
from remote_car import Car

COMMAND = 'dumpsys activity service com.ecarx.carplay/com.shilapi.xcertplay.DiPlaySessionService --audio-input-state'


def parse(raw):
    line = next((line.strip().split('AUDIO_INPUT_STATE=', 1)[1] for line in raw.splitlines()
                 if line.strip().startswith('AUDIO_INPUT_STATE=')), None)
    if line is None or len(line) > 32768:
        raise ValueError('audio_input_state_missing')
    state = json.loads(line)
    if state.get('schema') != 1:
        raise ValueError('audio_input_state_version')
    value = state.get('input') or {}
    uplinks = value.get('uplinks') or []
    return dict(connected=state.get('connected') is True,
                audio_mode=value.get('audioMode') if type(value.get('audioMode')) is int else None,
                microphone_muted=value.get('microphoneMuted') if type(value.get('microphoneMuted')) is bool else None,
                uplinks=[{key: item[key] for key in ('audioType', 'active', 'capture') if key in item}
                         for item in uplinks[:8] if isinstance(item, dict)],
                recent=[item[:2048] for item in value.get('recent', [])[-16:]
                        if isinstance(item, str) and item.startswith('Microphone: ')],
                iphone_microphone_selected='unknown')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, default=Path('.local/remote-session.json'))
    parser.add_argument('--scenario', required=True, choices=['native-call', 'app-call-before-connect',
                        'app-call-after-connect', 'voice-message'])
    parser.add_argument('--samples', type=int, choices=range(1, 16), default=6)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    car = Car(json.loads(args.session.read_text()))
    samples = []
    for index in range(args.samples):
        result = car.job(dict(kind='shell', transport='ROOT', command=COMMAND))
        if result.get('exitCode') != 0 or result.get('timedOut') or result.get('truncated') or result.get('error'):
            raise RuntimeError('audio_input_diagnostic_transport_failed')
        samples.append(dict(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                            **parse(result.get('output', ''))))
        if index + 1 < args.samples:
            time.sleep(1)
    report = dict(scenario=args.scenario, read_only=True, samples=samples,
                  audibility_verified=False, iphone_input_route_verified=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'已保存 {len(samples)} 次只读状态；手机麦克风选择与对方可闻仍须独立核验。')


if __name__ == '__main__':
    main()
