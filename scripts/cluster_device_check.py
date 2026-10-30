#!/usr/bin/env python3
"""已连接目标的只读版本核对。显式--gpu-smoke才运行已安装的自有GPU测试；不安装/不准备Root/QNX。"""
import argparse
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True,help='已连接设备，仅用于选择，不写入报告')
    parser.add_argument('--adb',default='adb')
    parser.add_argument('--bundle',type=Path,required=True)
    parser.add_argument('--gpu-smoke',action='store_true')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    manifest=json.loads((args.bundle/'manifest.json').read_text())
    for relative,item in manifest['files'].items():
        path=args.bundle/relative
        if path.is_symlink() or not path.resolve().is_relative_to(args.bundle.resolve()): raise ValueError('测试包路径无效')
        if hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']: raise ValueError('测试包文件摘要不符')
    def adb(*command):
        result=subprocess.run([args.adb,'-s',args.serial,*command],capture_output=True,text=True,timeout=45)
        if result.returncode: raise ValueError('ADB只读核对失败；不保存原始输出')
        return result.stdout
    report={'recorded_at':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),'installed_by_script':False,'apps':{}}
    try:
        for label,package in [('CarPlay','com.ecarx.carplay'),('AutoService','com.ecarx.autoservice'),('AutoCluster','com.galaxy.autocluster')]+([('ProjectionSmoke','com.ecarx.autoservice.test')] if args.gpu_smoke else []):
            path=adb('shell','pm','path',package).strip()
            if not re.fullmatch(r'package:/[A-Za-z0-9_./=+~-]+\.apk',path): raise ValueError(f'{label}安装路径缺失或非单APK')
            with tempfile.TemporaryDirectory(prefix='cluster-installed-check-') as directory:
                pulled=Path(directory)/'installed.apk';adb('pull',path[len('package:'):],str(pulled))
                actual=hashlib.sha256(pulled.read_bytes()).hexdigest()
            relative='tests/ProjectionSmoke-test.apk' if label=='ProjectionSmoke' else f'app/{label}-debug.apk'
            expected=manifest['files'][relative]['sha256']
            report['apps'][label]={'matches_bundle':actual==expected,'apk_sha256':actual}
            if actual!=expected: raise ValueError(f'{label}与本轮测试包不一致；三个应用需整套更新，不能混用IPC版本')
        if args.gpu_smoke:
            raw=adb('shell','am','instrument','-w','com.ecarx.autoservice.test/com.ecarx.autoservice.projection.ProjectionSmokeInstrumentation')
            report['gpu_smoke_passed']=bool(re.search(r'INSTRUMENTATION_RESULT: status=PASS\b',raw))
            stage=re.search(r'^INSTRUMENTATION_RESULT: stage=([^\r\n]{1,160})',raw,re.M)
            if stage: report['gpu_smoke_stage']=stage.group(1)
            if not report['gpu_smoke_passed']: raise ValueError('真实GPU冒烟测试未通过；保留测试屏幕阶段结果供定位')
        report['passed']=True
    except (ValueError,OSError,subprocess.SubprocessError) as error:
        report.update(passed=False,error=str(error));raise
    finally:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('目标APK摘要一致；'+('真实GPU冒烟通过。'if args.gpu_smoke else '未执行GPU测试。')+'未安装/连接新地址/改变Root或QNX。')
if __name__=='__main__': main()
