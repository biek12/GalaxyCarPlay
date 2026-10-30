#!/usr/bin/env python3
"""现场入口：默认只读核对/采样；显式authorize才使用已授权root Telnet准备本App的ADB。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent


def now():
    return datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def safe_snapshot(raw):
    """只保留自有dump的固定数值/布尔字段，不保存status、媒体或Shell原文。"""
    result = {}
    for key in ('vehiclePropertyCount', 'vehicleSampledCount', 'vehicleValidCount'):
        match = re.search(r'^' + key + r'=(\d+)$', raw, re.M)
        if match:
            result[key] = int(match[1])
    for key in ('projectionRequested', 'platformReady', 'preparing', 'paused', 'automatic',
                'adbIdentityConfigured', 'qnxCredentialsConfigured'):
        match = re.search(r'(?:^|\s)' + key + r'=(true|false)(?=\s|$)', raw, re.M)
        if match:
            result[key] = match[1] == 'true'
    line = next((line for line in raw.splitlines() if line.strip().startswith('hab=')), '')
    result['hab_numeric'] = {k: int(v) for k, v in re.findall(r'([A-Za-z][A-Za-z0-9]*)=(\d+)(?=\s|$)', line)}
    result['hab_inactive'] = line.strip() == 'hab=inactive'
    if not result.keys() - {'hab_numeric', 'hab_inactive'}:
        raise ValueError('自有服务无有效诊断数据；检查是否运行，本工具不会自动启动')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True, help='包外本轮记录目录')
    parser.add_argument('--adb', default=shutil.which('adb') or 'adb')
    parser.add_argument('--serial', help='已经连接的目标，不写入报告')
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('init', help='纯离线校验并创建记录，不访问设备')
    sub.add_parser('check', help='只读核对三个已安装APK')
    sub.add_parser('gpu', help='显式运行已安装的独立GPU测试')
    authorize = sub.add_parser('authorize', help='显式Android root Telnet准备AutoService自身ADB白名单与TLS，允许修改本App权限/ADB开关')
    authorize.add_argument('--host', required=True)
    authorize.add_argument('--port', type=int, default=23)
    snap = sub.add_parser('snapshot', help='只读记录本机ADB UID和限定服务指标')
    snap.add_argument('label', choices=['before', 'during', 'fault', 'after'])
    perf = sub.add_parser('perf')
    perf.add_argument('label', choices=['old-map', 'map10', 'map20', 'map30', 'static', 'editor', 'stopped'])
    perf.add_argument('--duration', type=int, default=120)
    perf.add_argument('--warmup', type=int, default=15)
    fields = sub.add_parser('fields')
    fields.add_argument('label', choices=['parked', 'cruise-off', 'cruise-standby', 'cruise-active',
                       'regen-low', 'regen-medium', 'regen-high', 'regen-auto', 'accelerating', 'coasting', 'recovering'])
    fields.add_argument('--duration', type=int, default=15)
    args = parser.parse_args()
    bundle, results = args.bundle.resolve(), args.results.resolve()
    if results.is_relative_to(bundle):
        parser.error('结果目录必须在包外，避免破坏摘要')
    # 每项前核包，防止现场拷贝时混用产物；不调用构建或网络。
    subprocess.run([sys.executable, str(bundle / 'verify_bundle.py'), str(bundle)], check=True)
    if args.action == 'init':
        if results.exists():
            parser.error('结果目录已存在；另选唯一目录，保留已有记录')
        if not shutil.which(args.adb):
            parser.error('ADB不可用，请使用--adb指定本机绝对路径')
        results.mkdir(parents=True)
        template = json.loads((bundle / 'acceptance-template.json').read_text())
        digest = hashlib.sha256((bundle / 'manifest.json').read_bytes()).hexdigest()
        template['bundle_manifest_sha256'] = digest
        write(results / 'acceptance.json', template)
        write(results / 'session.json', {'prepared_at': now(), 'bundle_manifest_sha256': digest,
                                       'vehicle_connected_by_tool': False, 'installed_by_tool': False})
        print(f'现场记录已准备：{results}；全部项目仍为not_tested。未访问设备。')
        return
    if not (results / 'session.json').is_file():
        parser.error('先运行init初始化本轮记录')
    digest = hashlib.sha256((bundle / 'manifest.json').read_bytes()).hexdigest()
    if json.loads((results / 'session.json').read_text())['bundle_manifest_sha256'] != digest:
        parser.error('本轮记录绑定了另一测试包，请新建记录目录')
    if args.action == 'authorize':
        stamp = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%H%M%S-%f')
        subprocess.run([sys.executable, str(HERE / 'prepare_autoservice_adb.py'), '--bundle', str(bundle),
                        '--host', args.host, '--port', str(args.port),
                        '--output', str(results / f'adb-authorization-{stamp}.json')], check=True)
        return
    if not args.serial:
        parser.error('现场操作必须显式指定--serial，不自动选择设备或连接地址')
    stamp = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%H%M%S-%f')
    output = results / f'{args.action}-{getattr(args, "label", "result")}-{stamp}.json'
    common = ['--adb', args.adb, '--serial', args.serial, '--output', str(output)]
    if args.action in ('check', 'gpu'):
        command = ['cluster_device_check.py', '--bundle', str(bundle), *common]
        if args.action == 'gpu':
            command.append('--gpu-smoke')
    elif args.action == 'perf':
        scenario = {'static': 'static', 'editor': 'map-media', 'stopped': 'stopped'}.get(args.label, 'map-media')
        command = ['measure_autoservice.py', *common, '--scenario', scenario,
                   '--duration', str(args.duration), '--warmup', str(args.warmup)]
        print(f'只读采样{args.label}；请先在UI设置对应场景，脚本不改变帧率/画面。', flush=True)
    elif args.action == 'fields':
        command = ['capture_cluster_fields.py', *common, '--label', args.label, '--duration', str(args.duration)]
    else:
        def shell(*parts):
            value = subprocess.run([args.adb, '-s', args.serial, 'shell', *parts],
                                   capture_output=True, text=True, timeout=20)
            if value.returncode:
                raise ValueError('只读快照失败；未保存原始Shell输出')
            return value.stdout
        uid = shell('id', '-u').strip()
        if not uid.isdigit():
            raise ValueError('未读到Android ADB UID')
        data = safe_snapshot(shell('dumpsys', 'activity', 'service', 'com.ecarx.autoservice/.AutoService'))
        write(output, {'recorded_at': now(), 'read_only': True, 'android_adb_uid': int(uid), **data})
        print(f'快照已保存：{output}；ADB UID不代表RootService或QNX UID。')
        return
    subprocess.run([sys.executable, str(HERE / command[0]), *command[1:]], check=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from error
