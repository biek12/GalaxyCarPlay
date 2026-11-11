#!/usr/bin/env python3
"""Bind private fixed-firmware Hook assets to current source; no target execution."""
import argparse, hashlib, json, os, shutil, subprocess, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/hab'))
from elf64 import ELF

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def checksum(p):
    # POSIX cksum also binds length; available on QNX and macOS.
    return ' '.join(subprocess.check_output(['cksum',str(p)],text=True).split()[:2])

def runtime_hmi(binding,evidence):
    """离线符号镜像与已采集实车原件分别绑定，绝不将另一份原件的CRC用于部署。"""
    if not binding.get('runtimeHmi'):
        return evidence['hmiSha256'],binding['checksums']['hmi']
    reference=Path(binding['referenceHmi']);runtime=Path(binding['runtimeHmi'])
    if sha(reference)!=evidence['hmiSha256'] or (sha(reference),sha(runtime))!=(
        '385d8fc526936a32fa08cf8dfd51db731a58c8a3c86fd43709a6269bdf080e64',
        '5ceff21b4ac5b651b1b629095aa6da0bcb84e0d7ea6b4a569b6a1913c0c1f94d'):
        raise ValueError('实车HMI不是已核验原件')
    elf=ELF(reference);other=ELF(runtime)
    if elf.h!=other.h or elf.ph!=other.ph or elf.dynamic!=other.dynamic or elf.syms!=other.syms or elf.relocs!=other.relocs:
        raise ValueError('实车HMI装载或调用布局不一致')
    sections=[elf.unpack('<IIQQQQIIQQ',elf.h[5]+i*elf.h[10]) for i in range(elf.h[11])]
    names=elf.take(sections[elf.h[12]][4],sections[elf.h[12]][5]);allowed=[]
    for section in sections:
        name=names[section[0]:names.index(0,section[0])].decode()
        if name in ('.note.gnu.build-id','.rodata'):allowed.append((section[4],section[4]+section[5]))
    differences=[i for i,(a,b) in enumerate(zip(elf.b,other.b)) if a!=b]
    if len(elf.b)!=len(other.b) or len(differences)!=25 or any(not any(start<=i<end for start,end in allowed) for i in differences):
        raise ValueError('实车HMI差异超出既有逐字节核验范围')
    return sha(runtime),checksum(runtime)
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    private=ROOT/'.local/research/cluster-product-assets'
    profile=private/'profile.json'
    previous=json.loads(profile.read_text()) if profile.exists() else {}
    rootfs=os.environ.get('GALAXY_QNX_ROOTFS')
    if rootfs:
        rootfs=Path(rootfs);hmi=rootfs/'apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI'
        candidate=Path(os.environ.get('GALAXY_BACKGROUND_CANDIDATE',str(ROOT/'.local/research/g733-generated-background-20261111/kzb-final/background-probe.kzb')))
        output=private/str(time.time_ns())
        subprocess.run([sys.executable,str(ROOT/'scripts/build_g733_background_hook.py'),'--rootfs',str(rootfs),'--candidate',str(candidate),'--output',str(output),'--music-probe','--live-music'],check=True,stdout=subprocess.DEVNULL)
        private.mkdir(parents=True,exist_ok=True)
        binding={'build':str(output.relative_to(ROOT)),'checksums':{k:checksum(p) for k,p in {'hmi':hmi/'cluster-hmi','libc':rootfs/'lib64/libc.so.4','background':hmi/'background.kzb','rightmenu':hmi/'rightmenu.kzb'}.items()},'referenceHmi':str((hmi/'cluster-hmi').resolve())}
        if previous.get('runtimeHmi'):binding['runtimeHmi']=previous['runtimeHmi']
        profile.write_text(json.dumps(binding,indent=2)+'\n')
    if not profile.exists():raise ValueError('Hook资产未准备；设置GALAXY_QNX_ROOTFS进行本机固定固件构建')
    binding=json.loads(profile.read_text());build=ROOT/binding['build'];evidence=json.loads((build/'evidence.json').read_text())
    if os.environ.get('GALAXY_QNX_RUNTIME_HMI'):
        binding['runtimeHmi']=str(Path(os.environ['GALAXY_QNX_RUNTIME_HMI']).resolve())
        profile.write_text(json.dumps(binding,indent=2)+'\n')
    runtime_sha,runtime_checksum=runtime_hmi(binding,evidence)
    if any(sha(ROOT/p)!=digest for p,digest in evidence['sourceSha256'].items()):raise ValueError('Hook源码已变化，必须重新生成资产')
    binary=build/'libgalaxy_g733_background_probe.so'
    if sha(binary)!=evidence['sha256']:raise ValueError('Hook产物摘要不匹配')
    args.output.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(binary,args.output/'libgalaxy_g733_cluster.so')
    for key,value in {**binding['checksums'],'hmi':runtime_checksum,'hook':checksum(binary)}.items():(args.output/f'{key}.cksum').write_text(value+'\n')
    shutil.copyfile(ROOT/'tools/research/g733_hook_runtime.sh',args.output/'g733_hook_runtime.sh')
    (args.output/'build.json').write_text(json.dumps({'hmiSha256':runtime_sha,'abiHmiSha256':evidence['hmiSha256'],'hookSha256':sha(binary),'sources':evidence['sourceSha256'],'controllerSha256':sha(ROOT/'tools/research/g733_hook_runtime.sh'),'executed':False},indent=2)+'\n')
if __name__=='__main__':main()
