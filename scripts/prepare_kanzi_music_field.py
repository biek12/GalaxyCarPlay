#!/usr/bin/env python3
"""离线生成摘要绑定的现场HMI试验/恢复脚本；不连接、不部署、不激活。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def checksum(path):
    return ' '.join(subprocess.check_output(['cksum', str(path)], text=True).split()[:2])

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT/'.work/kanzi-field')
    args=parser.parse_args()
    report=json.loads((ROOT/'.work/kanzi-music/build.json').read_text())
    candidate=ROOT/'.work/kanzi-music/cluster-hmi-music-candidate'
    guard=ROOT/'.work/kanzi-field/path-guard'
    if digest(args.original)!=report['originalSha256'] or digest(candidate)!=report['artifacts'][candidate.name]['sha256']:
        raise ValueError('现场原件/候选摘要不符')
    if ROOT/'.work' not in args.output.resolve().parents:
        raise ValueError('现场输出仅允许忽略的.work目录')
    text=(ROOT/'tools/kanzi_music/hmi-trial.sh').read_text().replace('@ORIGINAL_CKSUM@', checksum(args.original)).replace('@CANDIDATE_CKSUM@', checksum(candidate)).replace('@GUARD_CKSUM@', checksum(guard))
    args.output.mkdir(parents=True, exist_ok=True)
    script=args.output/'hmi-trial.sh'
    script.write_text(text)
    subprocess.run(['sh','-n',str(script)], check=True)
    manifest=dict(originalSha256=digest(args.original), candidateSha256=digest(candidate),guardSha256=digest(guard),scriptSha256=digest(script),activation='qnx-single-file-memory-alias',oemDiskWritten=False,activated=False,requiresExplicitOemHmiAuthorization=True)
    (args.output/'hmi-trial-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('现场脚本已生成/语法检查；未部署或激活')

if __name__=='__main__': main()
