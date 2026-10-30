#!/usr/bin/env python3
"""核对三个APK身份/公钥证书/投射归属和当前单测结果；不安装、不连接。"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile
ROOT = Path(__file__).resolve().parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    sdk = ROOT/'.work/android-sdk'
    signer = sorted(sdk.glob('build-tools/*/apksigner'))[-1]
    aapt = signer.with_name('aapt2')
    apps = {}
    for module, package in [('mobile','com.ecarx.carplay'),('service','com.ecarx.autoservice'),('cluster','com.galaxy.autocluster')]:
        path = ROOT/f'galaxy/{module}/build/outputs/apk/debug/{module}-debug.apk'
        badging = subprocess.check_output([str(aapt),'dump','badging',str(path)], text=True)
        assert f"package: name='{package}'" in badging
        cert = subprocess.check_output([str(signer),'verify','--print-certs',str(path)], text=True)
        fingerprint = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', cert).group(1)
        with ZipFile(path) as archive:
            owned = 'lib/arm64-v8a/libgalaxy_hab_bridge.so' in archive.namelist()
            assert owned == (module == 'service'), 'HAB输出必须只归AutoService'
            assert not any('libSecShell' in name or 'libautodisplaycamera' in name for name in archive.namelist())
            if module == 'service':
                binary = archive.read('assets/qnx/receiver-candidate')
                metadata = json.loads(archive.read('assets/qnx/build.json'))
                assert hashlib.sha256(binary).hexdigest() == metadata['sha256']
        apps[module] = dict(package=package,sha256=sha(path),bytes=path.stat().st_size,certificate_sha256=fingerprint)
    assert len({x['certificate_sha256'] for x in apps.values()}) == 1, '同签名IPC契约不成立'
    tests=[]
    for folder in ['galaxy/contracts','galaxy/service','galaxy/cluster','common']:
        for file in sorted((ROOT/folder/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
            result=ET.parse(file).getroot()
            assert int(result.get('failures',0)) == 0 and int(result.get('errors',0)) == 0, file
            tests.append(dict(name=result.get('name'),tests=int(result.get('tests',0)),skipped=int(result.get('skipped',0))))
    assert tests
    report=dict(recorded_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),apps=apps,
        apk_sha256=apps['mobile']['sha256'], tests=tests,total_tests=sum(x['tests'] for x in tests),
        lint_run=False,installed=False,device_tested=False,qnx_sdk_build_complete=False,
        qnx_candidate_bundled=True,validation_command='scripts/verify_auto_platform.sh',
        source_sha256={str(p.relative_to(ROOT)):sha(p) for d in ['galaxy/contracts/src','galaxy/client/src','galaxy/service/src','galaxy/cluster/src'] for p in sorted((ROOT/d).rglob('*')) if p.is_file()})
    for name in ['validation-auto-platform.json','validation-carplay-hab.json']:
        (ROOT/'.work/logs'/name).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(f"三个APK包名/同签名/投射归属通过；{report['total_tests']}项当前JVM回归通过；未运行Lint、未安装或实车验收。")
if __name__ == '__main__': main()
