#!/usr/bin/env python3
"""本机合成帧对照；不连接设备、不读取认证、不可代替目标车性能验收。"""
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess
import tempfile
from datetime import datetime
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
HAB = ROOT / 'galaxy/service/src/main/cpp/hab'


def main():
    with tempfile.TemporaryDirectory(prefix='galaxy-frame-bench-') as folder:
        binary = Path(folder) / 'receiver-bench'
        sources = [HAB / f'galaxy_hab_{name}.c' for name in ('protocol', 'owned', 'hmac', 'receiver')]
        subprocess.run([*shlex.split(os.environ.get('CC', 'cc')), '-O2', '-std=c11',
                        '-Wall', '-Wextra', '-Wpedantic', '-Werror', '-I', str(HAB),
                        *map(str, sources), str(ROOT / 'tools/hab/bench_receiver.c'),
                        '-o', str(binary)], check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True, check=True, timeout=60)
    report = json.loads(result.stdout)
    report.update(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                  host=f'{platform.system()} {platform.machine()}',
                  target_vehicle_measured=False,
                  excludes=['GPU rendering/readback', 'pipe', 'HAB transport', 'QNX Screen post/scanout',
                            'target CPU/PSS/threads/FD', 'OEM fault monitoring'])
    output = ROOT / '.work/logs/projection-copy-benchmark.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f'本机结果：{output}；未进行8155/仪表实车验收。')


if __name__ == '__main__':
    main()
