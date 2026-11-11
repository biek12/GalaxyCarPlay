#!/usr/bin/env python3
"""取本机Gradle缓存中与锁定AGP配套的AAPT2；不联网、不替换产品依赖。"""
from pathlib import Path
import hashlib
import os
import platform
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[3]
version = re.search(r'^agp\s*=\s*"([^"]+)"', (root / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
classifier = {'Darwin': 'osx', 'Linux': 'linux', 'Windows': 'windows'}.get(platform.system())
if classifier is None:
    raise SystemExit('当前主机平台没有已配置的AAPT2缓存分类')
cache = Path(os.environ.get('GRADLE_USER_HOME', str(root / '.work/gradle-home')))
artifacts = cache / 'caches/modules-2/files-2.1/com.android.tools.build/aapt2'
jars = list(artifacts.glob(f'{version}-*/*/*-{classifier}.jar'))
if len(jars) != 1:
    raise SystemExit('缺少或存在多个配套AAPT2缓存；可通过GALAXY_AAPT2显式指定已核验的本机工具')
jar = jars[0]
if hashlib.sha1(jar.read_bytes()).hexdigest() != jar.parent.name:
    raise SystemExit('AAPT2缓存摘要与Gradle内容地址不一致')
with zipfile.ZipFile(jar) as archive:
    data = archive.read('aapt2.exe' if classifier == 'windows' else 'aapt2')
output = root / '.work/tooling' / f'{jar.parent.parent.name}-{classifier}' / ('aapt2.exe' if classifier == 'windows' else 'aapt2')
output.parent.mkdir(parents=True, exist_ok=True)
if not output.is_file() or hashlib.sha256(output.read_bytes()).digest() != hashlib.sha256(data).digest():
    temporary = output.with_name(output.name + f'.{os.getpid()}.tmp')
    temporary.write_bytes(data)
    temporary.chmod(0o755)
    temporary.replace(output)
print(output)
