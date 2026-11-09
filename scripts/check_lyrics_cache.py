#!/usr/bin/env python3
"""核对公网歌词缓存命中；可显式重启自有galaxyd验证磁盘复用，不保存歌词正文。"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', default='https://bridge.example.invalid/galaxy')
    parser.add_argument('--title', default='不能说的秘密')
    parser.add_argument('--artist', default='周杰伦')
    parser.add_argument('--restart', action='store_true', help='显式重启自有galaxyd，不改原星枢服务')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not args.origin.startswith('https://'):
        parser.error('requires HTTPS')
    cafile = os.environ.get('GALAXY_CA_FILE') or ('/etc/ssl/cert.pem' if sys.platform == 'darwin' else None)
    context = ssl.create_default_context(cafile=cafile)
    address = args.origin + '/lyrics/search?' + urllib.parse.urlencode(dict(track_name=args.title, artist_name=args.artist))
    report = dict(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                  title=args.title, artist=args.artist, restarted=args.restart, passed=False, samples=[])

    def sample(label):
        started = time.monotonic()
        with urllib.request.urlopen(address, context=context, timeout=25) as response:
            payload = response.read(2 * 1024 * 1024 + 1)
            if len(payload) > 2 * 1024 * 1024:
                raise ValueError('response too large')
            result = json.loads(payload)
            state = response.headers.get('X-Galaxy-Lyrics-Cache')
        if not result or not any(row.get('syncedLyrics') or row.get('plainLyrics') for row in result):
            raise ValueError('no positive lyric result')
        record = dict(label=label, cache=state, milliseconds=round((time.monotonic() - started) * 1000),
                      content_sha256=hashlib.sha256(payload).hexdigest(), bytes=len(payload))
        report['samples'].append(record)
        return record

    try:
        first = sample('initial')
        second = sample('memory')
        if second['cache'] != 'HIT' or second['content_sha256'] != first['content_sha256']:
            raise ValueError('memory cache did not preserve result')
        if args.restart:
            target = os.environ.get('GALAXY_SERVER_SSH', 'root@192.0.2.100')
            key = os.environ.get('GALAXY_SERVER_SSH_KEY', '/Users/anjon/.ssh/id_ed25520_1panel')
            subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', '-i', key,
                            target, 'systemctl restart galaxyd.service'], check=True, timeout=20)
            for attempt in range(10):
                try:
                    with urllib.request.urlopen(args.origin + '/bridge/health', context=context, timeout=5) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                if attempt == 9:
                    raise TimeoutError('service did not recover')
                time.sleep(1)
            restored = sample('after_restart')
            if restored['cache'] != 'HIT' or restored['content_sha256'] != first['content_sha256']:
                raise ValueError('persistent cache did not survive restart')
        report['passed'] = True
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(passed=True, samples=report['samples']), ensure_ascii=False))


if __name__ == '__main__':
    main()
