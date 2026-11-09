#!/usr/bin/env python3
"""经AutoService ROOT核对本车VHAL配置；--read只读源码固定的踏板/转角清单。"""
import argparse
import base64
import gzip
import hashlib
from pathlib import Path
import subprocess
import sys

from autorecord_field import Field, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, default=ROOT / '.local/remote-session.json')
    parser.add_argument('--read', action='store_true')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    work = ROOT / '.work/autorecord-hal-catalog'
    work.mkdir(parents=True, exist_ok=True)
    sdk = ROOT / '.work/android-sdk'
    jar = sdk / 'platforms/android-37.0/android.jar'
    subprocess.run(['javac', '-nowarn', '-source', '11', '-target', '11', '-classpath', str(jar),
                    '-d', str(work), str(ROOT / 'scripts/field/AutoRecordHalCatalog.java')], check=True, capture_output=True)
    subprocess.run([str(sdk / 'build-tools/36.0.0/d8'), '--release', '--min-api', '30', '--lib', str(jar),
                    '--output', str(work), str(work / 'AutoRecordHalCatalog.class')], check=True, capture_output=True)
    payload = (work / 'classes.dex').read_bytes()
    data = gzip.compress(payload, mtime=0)
    target = '/data/local/tmp/autorecord-hal-' + hashlib.sha256(payload).hexdigest()[:16] + '.dex'
    field = Field(args.session)
    try:
        # 明确偏移写入；同一块重复执行不追加污染，远程结果超时仍不自动重发命令。
        for start in range(0, len(data), 1800):
            block = base64.b64encode(data[start:start + 1800]).decode()
            field.shell("umask 077; printf '%s' '" + block + "' | base64 -d | dd of=" + target +
                        '.gz bs=1 seek=' + str(start) + ' conv=notrunc status=none')
        field.shell('gzip -dc ' + target + '.gz > ' + target)
        digest = field.shell('sha256sum ' + target).split()[0]
        if digest != hashlib.sha256(payload).hexdigest():
            raise RuntimeError('探针摘要不符，未运行')
        output = field.shell('CLASSPATH=' + target + ':/system/framework/ecarx.car.jar app_process /system/bin '
                             'AutoRecordHalCatalog' + (' --read' if args.read else ''))
        args.out.write_text(output)
        print(output)
    finally:
        active_error = sys.exc_info()[0]
        try:
            field.shell('rm -f ' + target + ' ' + target + '.gz')
        except Exception:
            if active_error is None:
                raise
            print('探针临时文件清理未确认；保留原始失败原因。', file=sys.stderr)


if __name__ == '__main__':
    main()
