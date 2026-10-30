#!/usr/bin/env python3
"""对已连接、显式指定的车机做只读性能采样；不连接新地址、不安装/启动/停止App。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('com.ecarx.autoservice', 'autoservice-root', 'libgalaxy_hab_bridge.so', 'com.galaxy.autocluster')


def parse(text):
    records = []
    for name, pid, body in re.findall(r'BEGIN ([\w.\-]+) (\d+)\r?\n(.*?)END\r?\n', text, re.S):
        if name not in NAMES:
            continue
        record = {'process': name, 'pid': int(pid)}
        for key, value in re.findall(r'^(VmRSS|Threads|Pss|Rss|FD):\s*(\d+)', body, re.M):
            record[key] = int(value)
        stat = re.search(r'^\d+ \(.*\) (.+)$', body, re.M)
        if stat:
            fields = stat.group(1).split()
            if len(fields) >= 20:
                record['cpu_ticks'] = int(fields[11]) + int(fields[12])
                record['started_ticks'] = int(fields[19])
        records.append(record)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='已经连接的ADB设备；仅用于选择目标，不写入报告')
    parser.add_argument('--adb', default=str(ROOT / '.work/android-sdk/platform-tools/adb'))
    parser.add_argument('--scenario', required=True, choices=['idle', 'telemetry', 'static', 'map-media', 'stopped'])
    parser.add_argument('--warmup', type=int, default=60)
    parser.add_argument('--duration', type=int, default=300)
    parser.add_argument('--interval', type=float, default=5)
    parser.add_argument('--output', type=Path, default=ROOT / '.work/logs/autoservice-performance.json')
    args = parser.parse_args()
    if not (0 <= args.warmup <= 600 and 1 <= args.duration <= 3600 and 1 <= args.interval <= 60):
        parser.error('采样参数越界')
    def shell(command):
        result = subprocess.run([args.adb, '-s', args.serial, 'shell', command], capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError('只读ADB采样失败，未保存原始错误输出')
        return result.stdout
    identity = shell('id -u').strip()
    if identity != '0':
        parser.error('需要已有UID0 ADB读取helper的/proc；本脚本不请求Root或改变授权')
    ticks_text = shell('getconf CLK_TCK').strip()
    ticks = int(ticks_text) if ticks_text.isdigit() else None
    command = '\n'.join(
        f'for p in $(pidof {name}); do printf "BEGIN {name} %s\\n" "$p"; '
        'cat /proc/$p/stat; grep -E "^(VmRSS|Threads):" /proc/$p/status; '
        'grep -E "^(Pss|Rss):" /proc/$p/smaps_rollup 2>/dev/null; '
        'printf "FD: "; ls /proc/$p/fd 2>/dev/null | wc -l; printf "END\\n"; done'
        for name in NAMES)
    # 采样不改变场景，操作者提前设置好；计时期间只有只读/proc请求。
    time.sleep(args.warmup)
    started = time.monotonic()
    previous = {}
    samples = []
    while time.monotonic() - started < args.duration:
        at = time.monotonic()
        records = parse(shell(command))
        for item in records:
            key = (item['pid'], item.get('started_ticks'))
            old = previous.get(key)
            if ticks and old and 'cpu_ticks' in item:
                item['cpu_percent_one_core'] = 100 * (item['cpu_ticks'] - old[1]) / ticks / (at - old[0])
            previous[key] = (at, item.get('cpu_ticks', 0))
        samples.append({'seconds': round(at - started, 3), 'processes': records})
        time.sleep(min(args.interval, max(0, args.duration - (time.monotonic() - started))))
    summary = {}
    for name in NAMES:
        items = [p for sample in samples for p in sample['processes'] if p['process'] == name]
        cpu = [p['cpu_percent_one_core'] for p in items if 'cpu_percent_one_core' in p]
        summary[name] = {'observations': len(items), 'cpu_mean_one_core': sum(cpu) / len(cpu) if cpu else None,
                         'cpu_peak_one_core': max(cpu) if cpu else None,
                         'pss_peak_kib': max((p['Pss'] for p in items if 'Pss' in p), default=None),
                         'rss_peak_kib': max((p.get('Rss', p.get('VmRSS', 0)) for p in items), default=None),
                         'threads_peak': max((p.get('Threads', 0) for p in items), default=None),
                         'fd_peak': max((p.get('FD', 0) for p in items), default=None)}
    totals = []
    for sample in samples:
        processes = sample['processes']
        totals.append({'cpu': sum(p['cpu_percent_one_core'] for p in processes) if processes and all('cpu_percent_one_core' in p for p in processes) else None,
                       'pss': sum(p['Pss'] for p in processes) if processes and all('Pss' in p for p in processes) else None,
                       'rss': sum(p.get('Rss', p.get('VmRSS', 0)) for p in processes)})
    summary['owned_processes_total'] = {'cpu_mean_one_core': sum(t['cpu'] for t in totals if t['cpu'] is not None) / sum(t['cpu'] is not None for t in totals) if any(t['cpu'] is not None for t in totals) else None,
                                        'pss_peak_kib': max((t['pss'] for t in totals if t['pss'] is not None), default=None),
                                        'rss_peak_kib': max((t['rss'] for t in totals), default=None)}
    report = {'recorded_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'scenario': args.scenario,
              'cpu_basis': '100%=one core', 'clock_ticks': ticks, 'warmup_seconds': args.warmup,
              'read_only': True, 'summary': summary, 'samples': samples}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'只读采样完成：{len(samples)}次；报告 {args.output}。未保存设备标识、Shell原文或原始输出。')


if __name__ == '__main__':
    main()
