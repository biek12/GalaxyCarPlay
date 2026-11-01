#!/usr/bin/env python3
"""封装已通过离线验证的原样产物，不重新编译、不连接设备、不读取认证材料。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from cluster_preflight import ROOT, sha, check_sources


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=ROOT/'.work/logs/cluster-preflight.json')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    report=json.loads(args.report.read_text())
    if report.get('passed') is not True:
        raise ValueError('上车前完整离线检查未通过')
    check_sources(report)
    stamp=datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S')
    target=(args.output or ROOT/'.work/vehicle-test'/f'cluster-editor-{stamp}').resolve()
    if target.exists(): raise ValueError('输出目录已存在，不覆盖历史测试包')
    for module,item in report['apps'].items():
        path=ROOT/f'galaxy/{module}/build/outputs/apk/debug/{module}-debug.apk'
        if sha(path)!=item['sha256']: raise ValueError('APK已变化，请重新验证')
    qnx=ROOT/'.work/hab-probes/qnx/galaxy_hab_qnx_probe-candidate'
    if sha(qnx)!=report['qnx_receiver_sha256']: raise ValueError('接收程序已变化，请重新验证')
    target.mkdir(parents=True)
    def copy(source,destination):
        path=target/destination;path.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,path)
    labels={'mobile':'CarPlay','service':'AutoService','cluster':'AutoCluster'}
    for module,label in labels.items(): copy(ROOT/f'galaxy/{module}/build/outputs/apk/debug/{module}-debug.apk',f'app/{label}-debug.apk')
    gpu_test=ROOT/'galaxy/service/build/outputs/apk/androidTest/debug/service-debug-androidTest.apk'
    if sha(gpu_test)!=report['gpu_test']['sha256']: raise ValueError('GPU测试APK已变化，请重新验证')
    copy(gpu_test,'tests/ProjectionSmoke-test.apk')
    copy(qnx,'qnx/receiver-candidate')
    with zipfile.ZipFile(target/'app/AutoService-debug.apk') as archive:
        if archive.read('assets/qnx/receiver-candidate')!=qnx.read_bytes(): raise ValueError('APK内外QNX接收程序不一致')
    for name in ('cluster-preflight.json','validation-auto-platform.json','projection-copy-benchmark.json'):
        copy(ROOT/'.work/logs'/name,'validation/'+name)
    for log in (ROOT/'.work/logs').glob('preflight-*.log'): copy(log,'validation/'+log.name)
    for image in (ROOT/'galaxy/cluster/build/screenshots').rglob('*.png'):
        copy(image,'previews/'+str(image.relative_to(ROOT/'galaxy/cluster/build/screenshots')))
    for theme in (ROOT/'galaxy/cluster/build/test-themes').glob('*.gctheme'): copy(theme,'themes/'+theme.name)
    copy(ROOT/'docs/Cluster上车验收-20261030.md','README.md')
    copy(ROOT/'docs/上车两小时执行卡-20261030.md','上车两小时执行卡.md')
    copy(ROOT/'docs/AutoService-ADB一键授权-20261030.md','AutoService-ADB一键授权.md')
    copy(ROOT/'docs/Cluster组件属性与GT对标-20261030.md','组件与GT对标.md')
    copy(ROOT/'docs/Cluster二轮需求-巡航回收与音乐分区-20261030.md','二轮确认与待标定项.md')
    copy(ROOT/'docs/Cluster样式校正与原厂素材-20261030.md','Cluster样式校正与原厂素材-20261030.md')
    copy(ROOT/'docs/Cluster二轮需求-巡航回收与音乐分区-20261030.md','Cluster二轮需求-巡航回收与音乐分区-20261030.md')
    copy(ROOT/'LICENSE','LICENSE')
    copy(ROOT/'docs/design/cluster-acceptance-template.json','acceptance-template.json')
    for name in ('snapshot-qnx.sh','snapshot-android.sh','audio-snapshot-android.sh','telnet_session.py'):
        copy(ROOT/'tools/hab'/name,'diagnostics/'+name)
    for name in ('measure_autoservice.py','capture_cluster_fields.py','cluster_device_check.py','cluster_field_session.py','prepare_autoservice_adb.py','field_telnet_transport.py','field_telnet_install.py','field_ready.sh','cluster_preflight.py','prepare_cluster_vehicle_test.py'):
        copy(ROOT/'scripts'/name,'scripts/'+name)
    copy(ROOT/'docs/实车测试执行卡-20261101.md','实车测试执行卡-20261101.md')
    copy(ROOT/'docs/提交审计待修问题-20261101.md','提交审计待修问题-20261101.md')
    for path in report['source_sha256']:
        copy(ROOT/path,'sources/'+path)
    # 不扫描用户目录、旧日志、认证或签名，只允许上面显式选取的自有材料。
    manifest={'created_at':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),'ipc_version':4,
              'local_preflight_passed':True,'installed':False,'vehicle_tested':False,
              'gpu_smoke_built':True,'gpu_smoke_executed':False,'qnx_sdk_build_complete':False,
              'files':{p.relative_to(target).as_posix():{'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(target.rglob('*')) if p.is_file()}}
    (target/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    verifier=ROOT/'tools/hab/verify_bundle.py'
    copy(verifier,'verify_bundle.py')
    manifest['files']['verify_bundle.py']={'sha256':sha(target/'verify_bundle.py'),'bytes':(target/'verify_bundle.py').stat().st_size}
    (target/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    (target/'SHA256SUMS').write_text(''.join(f"{sha(p)}  {p.relative_to(target).as_posix()}\n" for p in sorted(target.rglob('*')) if p.is_file() and p.name!='SHA256SUMS'))
    subprocess.run([sys.executable,str(verifier),str(target)],check=True)
    zipped=target.with_suffix('.zip')
    with zipfile.ZipFile(zipped,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(target.rglob('*')):
            if path.is_file(): archive.write(path,target.name+'/'+path.relative_to(target).as_posix())
    zipped.with_suffix('.zip.sha256').write_text(f'{sha(zipped)}  {zipped.name}\n')
    # 仅在全部打包/摘要校验成功后更新本机现场入口；不覆盖历史包。
    pointer=ROOT/'.work/vehicle-test/cluster-current.txt'
    pending=pointer.with_suffix('.tmp')
    pending.write_text(str(target)+'\n')
    pending.replace(pointer)
    print(f'已验证上车测试包：{target}\nZIP：{zipped}\n未安装、未连接车辆；真实GPU测试APK已编译，尚未执行。')

if __name__=='__main__':
    try: main()
    except (ValueError,OSError,subprocess.SubprocessError) as error: raise SystemExit(str(error))
