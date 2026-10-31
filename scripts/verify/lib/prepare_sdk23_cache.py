#!/usr/bin/env python3
"""补齐固定Robolectric API23离线依赖；只写本机缓存，不修改构建/APK。"""
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
NAME = 'android-all-instrumented-6.0.1_r3-robolectric-r1-i7.jar'
URL = 'https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/6.0.1_r3-robolectric-r1-i7/' + NAME
SHA512 = 'bb6de4f27740e6fd604b2c6672a759c6b52d440ebce5d3de2cbbf2797b8b5128608d3a695676af2a9c795cca66c99f5eae8ecbf22b41d15fde071106eade6b6e'
path = Path(os.environ.get('GALAXY_VERIFY_CACHE', ROOT/'.work'))/'robolectric-deps'/NAME
if path.is_file() and hashlib.sha512(path.read_bytes()).hexdigest() == SHA512:
    print('API23测试缓存已核验')
else:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.download')
    digest = hashlib.sha512()
    try:
        subprocess.run(['curl', '-fLsS', '--retry', '2', '--connect-timeout', '20',
            '--max-time', '180', '-o', str(temp), URL], check=True)
        with temp.open('rb') as downloaded:
            while chunk := downloaded.read(1024*1024):
                digest.update(chunk)
        if digest.hexdigest() != SHA512:
            raise ValueError('Robolectric缓存摘要不符')
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)
    print('已准备并校验API23测试缓存')
