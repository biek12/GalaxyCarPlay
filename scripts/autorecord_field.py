#!/usr/bin/env python3
"""AutoRecord 现场入口：摘要校验安装、UI 树、截图和资源采样；只走 AutoService ROOT。"""
import argparse
import base64
import gzip
import hashlib
import json
import re
from pathlib import Path
import shlex
import subprocess
import time

from remote_car import Car

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.galaxy.autorecord'
ACTIVITY = PACKAGE + '/.ui.RecordActivity'


class Field:
    def __init__(self, session):
        self.car = Car(json.loads(session.read_text()))

    def shell(self, command):
        result = self.car.job(dict(kind='shell', transport='ROOT', command=command))
        if result.get('error') or result.get('timedOut') or result.get('truncated') or result.get('exitCode') != 0:
            raise RuntimeError('现场命令失败：' + json.dumps(result, ensure_ascii=False))
        return result.get('output', '')

    def digest(self):
        return self.shell('p=$(pm path ' + PACKAGE + ' | cut -d: -f2); '
                          'if [ -n "$p" ]; then sha256sum "$p" | cut -d" " -f1; fi').strip()

    def deploy(self, apk, transport='artifact'):
        payload = apk.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        if transport == 'artifact':
            if self.digest() != digest:
                print(json.dumps(self.car.install(apk, PACKAGE), ensure_ascii=False), flush=True)
            else:
                print(json.dumps(dict(package=PACKAGE, installed_sha256=digest, verified=True), ensure_ascii=False))
            return
        if self.digest() != digest:
            compressed = gzip.compress(payload, compresslevel=9, mtime=0)
            # 带摘要的私有临时路径；按偏移覆盖，同一块重放也不追加重复数据。
            target = '/data/local/tmp/autorecord-' + digest + '.apk.gz'
            quoted = shlex.quote(target)
            size = self.shell('if [ -f ' + quoted + ' ]; then stat -c %s ' + quoted + '; else echo 0; fi').strip()
            offset = int(size)
            if offset > len(compressed):
                raise RuntimeError('远端临时包长度异常，保留现场文件供检查')
            # 续传前验证前缀，不能把上轮损坏数据当成成功块。
            if offset:
                expected = hashlib.sha256(compressed[:offset]).hexdigest()
                actual = self.shell('sha256sum ' + quoted + ' | cut -d" " -f1').strip()
                if expected != actual:
                    raise RuntimeError('远端续传前缀摘要不符')
            for start in range(offset, len(compressed), 2700):
                encoded = base64.b64encode(compressed[start:start + 2700]).decode()
                self.shell("umask 077; printf '%s' '" + encoded + "' | base64 -d | dd of=" + quoted +
                           ' bs=1 seek=' + str(start) + ' conv=notrunc status=none')
                if (start - offset) // 2700 % 20 == 0:
                    print(f'传输 {min(start + 2700, len(compressed))}/{len(compressed)}（gzip）', flush=True)
            actual = self.shell('sha256sum ' + quoted + ' | cut -d" " -f1').strip()
            if actual != hashlib.sha256(compressed).hexdigest():
                raise RuntimeError('安装前摘要不符，未执行安装')
            unpacked = shlex.quote(target[:-3])
            self.shell('umask 077; gzip -dc ' + quoted + ' > ' + unpacked)
            if self.shell('sha256sum ' + unpacked + ' | cut -d" " -f1').strip() != digest:
                raise RuntimeError('解压后 APK 摘要不符，未执行安装')
            print(self.shell('pm install -r ' + unpacked).strip(), flush=True)
            if self.digest() != digest:
                raise RuntimeError('安装后摘要不符')
            self.shell('rm -f ' + quoted + ' ' + unpacked)
        print(json.dumps(dict(package=PACKAGE, installed_sha256=digest, verified=True), ensure_ascii=False))

    def ui(self):
        path = '/data/local/tmp/autorecord-ui.xml'
        return self.shell('uiautomator dump ' + path + ' >/dev/null && cat ' + path)

    def pull(self, remote, local):
        quoted = shlex.quote(remote)
        size = int(self.shell('stat -c %s ' + quoted).strip())
        data = bytearray()
        # 单作业输出上限 64 KiB；base64 后仍留出余量。
        while len(data) < size:
            chunk = min(45000, size - len(data))
            output = self.shell('dd if=' + quoted + ' bs=1 skip=' + str(len(data)) +
                                ' count=' + str(chunk) + ' status=none | base64 -w0')
            decoded = base64.b64decode(output.strip(), validate=True)
            if len(decoded) != chunk:
                raise RuntimeError('文件读取长度不符')
            data.extend(decoded)
        expected = self.shell('sha256sum ' + quoted + ' | cut -d" " -f1').strip()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('文件读取摘要不符')
        local.write_bytes(data)

    def sample(self):
        return self.shell('p=$(pidof ' + PACKAGE + '); if [ -z "$p" ]; then echo APP_NOT_RUNNING; exit 2; fi; '
                          'echo PID=$p && grep -E "VmRSS|Threads" /proc/$p/status && '
                          'echo FD=$(ls /proc/$p/fd | wc -l) && '
                          '(grep -E "^Pss:|^Rss:" /proc/$p/smaps_rollup || echo PSS_UNAVAILABLE); '
                          'dumpsys media.camera | sed \'/== Camera service events log/,$d\' | '
                          'grep -E "Client|PID|Package|com.galaxy.autorecord" || true')

    def presentation(self):
        """只读本应用 SurfaceView 呈现时刻；生产回调不能代替这一统计。"""
        rows = []
        layers = self.shell('dumpsys SurfaceFlinger --list').splitlines()
        pattern = r'^SurfaceView - ' + re.escape(ACTIVITY.replace('/.', '/' + PACKAGE + '.')) + r'#\d+$'
        for layer in layers:
            if not re.fullmatch(pattern, layer):
                continue
            raw = self.shell('dumpsys SurfaceFlinger --latency ' + shlex.quote(layer))
            times = sorted({int(parts[1]) for line in raw.splitlines() if len(parts := line.split()) == 3
                            and parts[1].isdigit() and 0 < int(parts[1]) < 9_000_000_000_000_000_000})
            fps = (len(times) - 1) * 1e9 / (times[-1] - times[0]) if len(times) > 1 else None
            rows.append(dict(layer=layer, samples=len(times), presentedFps=fps))
        return rows

    def benchmark(self, seconds):
        if not 1 <= seconds <= 30:
            raise ValueError('采样窗口须为1–30秒')
        def take():
            raw = self.shell('p=$(pidof ' + PACKAGE + '); test -n "$p" || exit 2; '
                             'cat /proc/uptime; cat /proc/$p/stat; '
                             'grep -E "^Pss:|^Rss:" /proc/$p/smaps_rollup; '
                             'echo FD=$(ls /proc/$p/fd | wc -l)')
            lines = raw.splitlines()
            stat = lines[1]; fields = stat[stat.rfind(")") + 2:].split()
            numbers = dict(re.findall(r'^(Pss|Rss):\s+(\d+)', raw, re.M))
            return dict(uptime=float(lines[0].split()[0]), pid=int(stat.split()[0]),
                        ticks=int(fields[11])+int(fields[12]), threads=int(fields[17]),
                        startTicks=int(fields[19]), pssKiB=int(numbers['Pss']) if 'Pss' in numbers else None,
                        rssKiB=int(numbers['Rss']) if 'Rss' in numbers else None, fd=int(lines[-1].split('=')[1]))
        before = take()
        time.sleep(seconds)  # 电脑端等待；不占住 AutoService ROOT Shell 的读超时。
        after = take()
        same = before['pid'] == after['pid'] and before['startTicks'] == after['startTicks']
        elapsed = after['uptime'] - before['uptime']
        # Android/Linux ABI 的 USER_HZ=100；百分比以一个CPU核为100%，非整机占比。
        cpu = (after['ticks'] - before['ticks']) / elapsed if same and elapsed > 0 else None
        return dict(before=before, after=after, cpuPercentOneCore=cpu, userHz=100, processUnchanged=same)

    def screenshot(self, local):
        remote = '/data/local/tmp/autorecord-screen.png'
        self.shell('screencap -p ' + remote)
        helper = None
        if local.suffix.lower() in ('.jpg', '.jpeg'):
            # 现场 JPEG 预览减小传输量，完整 PNG 仍可用 .png 输出取得。
            work = ROOT / '.work/autorecord-screenshot'
            work.mkdir(parents=True, exist_ok=True)
            sdk = ROOT / '.work/android-sdk'
            jar = sdk / 'platforms/android-37.0/android.jar'
            source = ROOT / 'scripts/field/AutoRecordScreenshot.java'
            subprocess.run(['javac', '-nowarn', '-source', '11', '-target', '11', '-classpath', str(jar),
                            '-d', str(work), str(source)], check=True, capture_output=True)
            subprocess.run([str(sdk / 'build-tools/36.0.0/d8'), '--release', '--min-api', '30', '--lib',
                            str(jar), '--output', str(work), str(work / 'AutoRecordScreenshot.class')],
                           check=True, capture_output=True)
            payload = gzip.compress((work / 'classes.dex').read_bytes(), mtime=0)
            encoded = base64.b64encode(payload).decode()
            if len(encoded) > 3000:
                raise RuntimeError('截图 helper 超出单作业体积上限')
            helper = '/data/local/tmp/autorecord-screenshot.dex'
            self.shell("umask 077; printf '%s' '" + encoded + "' | base64 -d | gzip -dc > " + helper)
            self.shell('CLASSPATH=' + helper + ' app_process /system/bin AutoRecordScreenshot')
            remote = '/data/local/tmp/autorecord-screen.jpg'
        try:
            self.pull(remote, local)
        finally:
            self.shell('rm -f /data/local/tmp/autorecord-screen.png /data/local/tmp/autorecord-screen.jpg' +
                       (' ' + helper if helper else ''))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, default=ROOT / '.local/workbuddy-remote-handoff/remote-session.json')
    parser.add_argument('action', choices=['deploy', 'start', 'ui', 'screenshot', 'sample', 'present', 'benchmark', 'tap', 'back', 'home'])
    parser.add_argument('--apk', type=Path, default=ROOT / 'galaxy/record/build/outputs/apk/field/record-field.apk')
    parser.add_argument('--transport', choices=['artifact', 'shell'], default='artifact', help='常规安装使用修复后的中继产物通道；shell 仅显式诊断')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--x', type=int)
    parser.add_argument('--y', type=int)
    parser.add_argument('--seconds', type=int, default=10)
    args = parser.parse_args()
    field = Field(args.session)
    if args.action == 'deploy':
        field.deploy(args.apk, args.transport)
        return
    if args.action == 'start':
        print(field.shell('am start -W -n ' + ACTIVITY))
    elif args.action == 'ui':
        xml = field.ui()
        if args.out:
            args.out.write_text(xml)
        else:
            import xml.etree.ElementTree as ET
            for node in ET.fromstring(xml).iter('node'):
                if node.get('package') == PACKAGE and (node.get('text') or node.get('content-desc')):
                    print(node.get('text'), node.get('content-desc'), node.get('bounds'), 'enabled=' + node.get('enabled', ''))
    elif args.action == 'screenshot':
        if not args.out:
            parser.error('screenshot 需要 --out（本地私有路径）')
        field.screenshot(args.out)
        print(str(args.out.resolve()))
    elif args.action == 'sample':
        print(field.sample())
    elif args.action in ('present', 'benchmark'):
        result = field.presentation() if args.action == 'present' else field.benchmark(args.seconds)
        output = json.dumps(result, ensure_ascii=False, indent=2)
        if args.out:
            args.out.write_text(output + '\n')
        print(output)
    else:
        # 操作前核验前台，防止误点原厂界面。home/back 也只从本应用发起。
        foreground = field.shell('dumpsys activity activities | grep mResumedActivity')
        if ACTIVITY not in foreground and PACKAGE + '/' not in foreground:
            raise RuntimeError('AutoRecord 不是前台，未执行输入')
        if args.action == 'tap':
            if args.x is None or args.y is None:
                parser.error('tap 需要 --x / --y')
            print(field.shell(f'input tap {args.x} {args.y}'))
        else:
            print(field.shell('input keyevent ' + ('4' if args.action == 'back' else '3')))


if __name__ == '__main__':
    main()
