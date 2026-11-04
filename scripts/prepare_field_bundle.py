#!/usr/bin/env python3
"""快速现场包：构建四APK并核验身份/同签名/摘要，明确不代表完整离线预检通过。"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
from zipfile import ZipFile
from cluster_preflight import source_hashes

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    started_sources = source_hashes()
    cache = Path(os.environ.get('GALAXY_VERIFY_CACHE', ROOT / '.work'))
    sdk = Path(os.environ.get('GALAXY_ANDROID_SDK', cache / 'android-sdk'))
    env = os.environ | {'ANDROID_HOME': str(sdk), 'ANDROID_SDK_ROOT': str(sdk),
                        'GRADLE_USER_HOME': os.environ.get('GRADLE_USER_HOME', str(cache / 'gradle-home'))}
    subprocess.run([str(ROOT / 'gradlew'), '--offline', '--no-daemon', '--console=plain',
                    '-Pandroid.builder.sdkDownload=false', ':mobile:assembleDebug', ':service:assembleDebug',
                    ':cluster:assembleDebug', ':service:assembleDebugAndroidTest'], cwd=ROOT, env=env, check=True)
    signer = sorted(sdk.glob('build-tools/*/apksigner'))[-1]
    aapt = signer.with_name('aapt2')
    records = {}
    paths = {}
    for module, label, package in [('mobile', 'CarPlay', 'com.ecarx.carplay'),
                                    ('service', 'AutoService', 'com.ecarx.autoservice'),
                                    ('cluster', 'AutoCluster', 'com.galaxy.autocluster'),
                                    ('service', 'ProjectionSmoke', 'com.ecarx.autoservice.test')]:
        relative = 'tests/ProjectionSmoke-test.apk' if label == 'ProjectionSmoke' else f'app/{label}-debug.apk'
        path = ROOT / (f'galaxy/{module}/build/outputs/apk/androidTest/debug/service-debug-androidTest.apk'
                       if label == 'ProjectionSmoke' else f'galaxy/{module}/build/outputs/apk/debug/{module}-debug.apk')
        badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(path)], text=True)
        if f"package: name='{package}'" not in badging:
            raise ValueError('APK身份不符')
        cert = subprocess.check_output([str(signer), 'verify', '--print-certs', str(path)], text=True)
        fingerprint = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', cert).group(1)
        records[label] = {'package': package, 'sha256': sha(path), 'certificate_sha256': fingerprint}
        paths[relative] = path
    if len({r['certificate_sha256'] for r in records.values()}) != 1:
        raise ValueError('四APK不同签名')
    if source_hashes() != started_sources:
        raise ValueError('构建期间源码变化，请重新运行')
    stamp = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S')
    target = ROOT / '.work/vehicle-test' / ('field-v4-' + stamp)
    target.mkdir(parents=True, exist_ok=False)
    for relative, path in paths.items():
        dest = target / relative; dest.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, dest)
    def copy(path, relative):
        dest = target / relative; dest.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / path, dest)
    for name in ('capture_navigation_state.py','check_lyrics_cache.py','remote_car.py','remote_field_transport.py','install_field_http.py','bootstrap_remote_bridge.py','measure_autoservice.py', 'capture_cluster_fields.py', 'cluster_device_check.py',
                 'cluster_field_session.py', 'prepare_autoservice_adb.py', 'field_telnet_transport.py',
                 'field_telnet_install.py'):
        copy('scripts/' + name, 'scripts/' + name)
    copy('tools/hab/telnet_session.py', 'diagnostics/telnet_session.py')
    copy('tools/hab/verify_bundle.py', 'verify_bundle.py')
    copy('docs/design/cluster-acceptance-template.json', 'acceptance-template.json')
    for name in ('实车测试执行卡-20261101.md', '提交审计待修问题-20261101.md', '远程服务与歌词车辆数据整改-20261102.md'):
        copy('docs/' + name, name)
    preflight = ROOT / '.work/logs/cluster-preflight.json'
    if preflight.is_file(): copy('.work/logs/cluster-preflight.json', 'validation/full-preflight-attempt.json')
    with ZipFile(paths['app/AutoService-debug.apk']) as archive:
        receiver = target / 'qnx/receiver-candidate'; receiver.parent.mkdir()
        receiver.write_bytes(archive.read('assets/qnx/receiver-candidate'))
        metadata = json.loads(archive.read('assets/qnx/build.json'))
        if sha(receiver) != metadata['sha256']: raise ValueError('APK内接收端摘要不符')
    manifest = {'created_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'ipc_version': 4,
                'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'local_preflight_passed': False, 'package_kind': 'fast-field-build',
                'build_passed': True, 'gpu_smoke_built': True, 'gpu_smoke_executed': False,
                'installed': False, 'vehicle_tested': False, 'qnx_sdk_build_complete': False,
                'apps': records, 'source_sha256': started_sources,
                'files': {p.relative_to(target).as_posix(): {'sha256': sha(p), 'bytes': p.stat().st_size}
                          for p in sorted(target.rglob('*')) if p.is_file()}}
    (target / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    subprocess.run([sys.executable, str(target / 'verify_bundle.py'), str(target)], check=True)
    pointer = ROOT / '.work/vehicle-test/cluster-current.txt'
    pending = pointer.with_suffix('.tmp'); pending.write_text(str(target) + '\n'); pending.replace(pointer)
    print(f'快速现场包：{target}；完整预检未通过，未连接或安装。')


if __name__ == '__main__':
    main()
