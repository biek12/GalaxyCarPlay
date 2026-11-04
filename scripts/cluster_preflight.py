#!/usr/bin/env python3
"""上车前离线回归与证据绑定；任何失败都不给出可上车测试包。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / '.work/logs/cluster-preflight.json'
SOURCE_DIRS = ('galaxy/contracts', 'galaxy/client', 'galaxy/service', 'galaxy/cluster', 'galaxy/common',
               'galaxy/mobile', 'common/src', 'shared/src', 'scripts', 'tools/hab', 'tools/upstream', 'server/galaxyd')
SUFFIXES = {'.kt','.java','.aidl','.xml','.c','.h','.kts','.gradle','.py','.sh','.cjs','.json','.txt','.toml','.patch','.S','.go','.mod','.sum','.service'}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source_hashes():
    result = {}
    for directory in SOURCE_DIRS:
        for path in sorted((ROOT / directory).rglob('*')):
            relative = path.relative_to(ROOT / directory)
            if path.is_file() and path.suffix in SUFFIXES and not {'build','__pycache__','.git'} & set(relative.parts):
                result[path.relative_to(ROOT).as_posix()] = sha(path)
    for name in ('settings.gradle.kts','gradle/libs.versions.toml','gradle.properties','build.gradle.kts',
                 'docs/Cluster上车验收-20261030.md', 'docs/上车两小时执行卡-20261030.md',
                 'docs/AutoService-ADB一键授权-20261030.md',
                 'docs/design/cluster-acceptance-template.json'):
        result[name] = sha(ROOT / name)
    return result

def check_sources(report):
    if report.get('source_sha256') != source_hashes():
        raise ValueError('源码/测试/构建脚本已变化，必须重新运行上车前验证')

def test_inventory():
    results = []
    for folder in ('galaxy/contracts','galaxy/service','galaxy/cluster'):
        expected = {p.stem for p in (ROOT / folder / 'src/test').rglob('*Test.kt')}
        files = sorted((ROOT / folder / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        found = set()
        for path in files:
            tree = ET.parse(path).getroot()
            if int(tree.get('tests','0')) <= 0 or any(int(tree.get(key,'0')) for key in ('errors','failures','skipped')):
                raise ValueError(f'测试未完整通过: {path.relative_to(ROOT)}')
            found.add(tree.get('name','').split('.')[-1])
            results.append({'suite':tree.get('name'), 'tests':int(tree.get('tests')),'sha256':sha(path)})
        if expected - found:
            raise ValueError(f'缺少本轮测试结果: {sorted(expected-found)}')
    for name in ('ClusterMusicTest','GalaxyL6HabFrameFormatTest'):
        matches = list((ROOT / 'common/build/test-results/testDebugUnitTest').glob(f'TEST-*.{name}.xml'))
        if len(matches) != 1:
            raise ValueError(f'缺少CarPlay接缝回归: {name}')
        tree = ET.parse(matches[0]).getroot()
        if any(int(tree.get(key,'0')) for key in ('errors','failures','skipped')):
            raise ValueError(f'CarPlay接缝回归失败: {name}')
        results.append({'suite':tree.get('name'),'tests':int(tree.get('tests')),'sha256':sha(matches[0])})
    return results

def run(command, log, env):
    print('运行：'+' '.join(map(str,command)), flush=True)
    with log.open('w') as output:
        result = subprocess.run(command,cwd=ROOT,env=env,stdout=output,stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text()[-16000:], file=sys.stderr)
        raise ValueError(f'检查失败，完整日志: {log.relative_to(ROOT)}')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--qnx-rootfs',type=Path,help='本机已采集rootfs，仅核验动态导入，不复制OEM库')
    parser.add_argument('--package',action='store_true',help='全部通过后生成同摘要测试包')
    args=parser.parse_args()
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    report={'started_at':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),'passed':False,
            'device_connected':False,'installed':False,'lint_run':False}
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    env=os.environ.copy()
    checks=[]
    try:
        if args.qnx_rootfs:
            run([sys.executable,'scripts/build_hab_probes.py','--qnx-rootfs',str(args.qnx_rootfs.resolve())],REPORT.parent/'preflight-qnx.log',env)
            checks.append('qnx-target-build')
        initial_sources=source_hashes()
        tasks=[('platform',['bash','scripts/verify_auto_platform.sh']),
               ('tools',[sys.executable,'-m','unittest','discover','-s','scripts/tests']),
               ('source-contracts',[sys.executable,'scripts/project_checks.py']),
               ('review-assets',[sys.executable,'scripts/update_cluster_review.py']),
               ('browser-review',['node','scripts/verify_cluster_editor.cjs']),
               ('theme-evidence',[sys.executable,'scripts/verify_theme_proposal.py']),
               ('copy-benchmark',[sys.executable,'scripts/benchmark_projection.py'])]
        for name,command in tasks:
            run(command,REPORT.parent/f'preflight-{name}.log',env);checks.append(name)
        sanitizer_env=env | {'GALAXY_HAB_TEST_CFLAGS':'-fsanitize=address,undefined -fno-omit-frame-pointer -g'}
        run([sys.executable,'-m','unittest','scripts.tests.test_hab_native'],REPORT.parent/'preflight-native-sanitizers.log',sanitizer_env)
        checks.append('native-address-undefined-sanitizers')
        suites=test_inventory()
        validation=json.loads((REPORT.parent/'validation-auto-platform.json').read_text())
        if not validation.get('gpu_test'): raise ValueError('缺少GPU测试APK身份/签名记录')
        check_sources({'source_sha256':initial_sources})
        report.update(passed=True,completed_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),checks=checks,
            tests=suites,jvm_test_count=sum(s['tests'] for s in suites),apps=validation['apps'],gpu_test=validation['gpu_test'],
            source_sha256=initial_sources,qnx_receiver_sha256=sha(ROOT/'.work/hab-probes/qnx/galaxy_hab_qnx_probe-candidate'),
            not_verified=['Android real EGL/SurfaceTexture/ImageReader driver execution','8155 CPU/PSS/threads/FD and measured display FPS',
                          'CarPlay native111 live content and QNX Screen visible output','OEM image transmission warning root cause/absence'])
        check_sources(report)
        print(f"上车前离线检查通过：{report['jvm_test_count']}项JVM；工具/native/ASan/UBSan/证据/三个APK。未连接车辆。")
    except (ValueError,OSError,subprocess.SubprocessError) as error:
        report.update(failed_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),error=str(error),checks=checks)
        raise
    finally:
        REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    if args.package:
        subprocess.run([sys.executable,'scripts/prepare_cluster_vehicle_test.py','--report',str(REPORT)],cwd=ROOT,check=True)

if __name__=='__main__':
    try: main()
    except (ValueError,OSError,subprocess.SubprocessError) as error: raise SystemExit(str(error))
