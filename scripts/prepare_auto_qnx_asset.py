#!/usr/bin/env python3
"""只封装与当前自有C源码一致的QNX候选，不复制OEM库、不内置登录凭据。"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import os
import sys
ROOT = Path(__file__).resolve().parents[1]
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report_path = ROOT / '.work/hab-probes/build-report.json'
    rootfs = os.environ.get('GALAXY_QNX_ROOTFS')
    if rootfs:
        subprocess.run([sys.executable, str(ROOT/'scripts/build_hab_probes.py'), '--qnx-rootfs', rootfs], check=True)
    report = json.loads(report_path.read_text())
    sources = report.get('source_sha256', {})
    if not sources or any(hashlib.sha256((ROOT/path).read_bytes()).hexdigest() != sha for path, sha in sources.items()):
        raise ValueError('QNX候选与当前源码不一致；设置GALAXY_QNX_ROOTFS重新构建，不封装旧程序')
    binary = ROOT / '.work/hab-probes/qnx/galaxy_hab_qnx_probe-candidate'
    metadata = next(x for x in report['artifacts'] if x['name'] == binary.name)
    if hashlib.sha256(binary.read_bytes()).hexdigest() != metadata['sha256']:
        raise ValueError('QNX候选摘要不符')
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(binary, args.output/'receiver-candidate')
    (args.output/'build.json').write_text(json.dumps(metadata, indent=2)+'\n')
if __name__ == '__main__':
    main()
