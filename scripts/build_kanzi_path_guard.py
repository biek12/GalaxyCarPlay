#!/usr/bin/env python3
"""自有QNX路径映射诊断工具；沿既有HAB最小CRT/双LOAD链接，不连接或执行目标。"""
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/hab'))
from elf64 import ELF

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--rootfs',type=Path,required=True);a=ap.parse_args()
    out=ROOT/'.work/kanzi-field/path-guard-build';out.mkdir(parents=True,exist_ok=True)
    llvm=ROOT/'.work/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin'
    required=['_init_libc','__get_errno_ptr','exit','write','read','close','open','strlen','strcmp','pathmgr_symlink','pathmgr_unlink']
    libc=ELF(a.rootfs/'lib64/libc.so.4');available={s['name'] for s in libc.syms if s['defined'] and s['visibility']==0 and s['bind'] in (1,2)}
    if set(required)-available:raise ValueError('libc缺少已核验的函数')
    imports=out/'imports.S';imports.write_text('.text\n'+''.join('.global '+s+'\n.type '+s+',%function\n'+s+':\nret\n' for s in required))
    def run(args):subprocess.run([str(x) for x in args],check=True)
    run([llvm/'clang','--target=aarch64-unknown-none-elf','-c',imports,'-o',out/'imports.o'])
    run([llvm/'ld.lld','-shared','-soname','libc.so.4',out/'imports.o','-o',out/'libc-link-only.so'])
    resource=subprocess.check_output([llvm/'clang','-print-resource-dir'],text=True).strip()
    run([llvm/'clang','--target=aarch64-unknown-nto','-ffreestanding','-nostdinc','-isystem',str(Path(resource)/'include'),'-I',ROOT/'tools/hab/qnx_abi','-O1','-fPIC','-fno-stack-protector','-Wall','-Wextra','-Werror','-c',ROOT/'tools/kanzi_music/path_guard.c','-o',out/'guard.o'])
    run([llvm/'clang','--target=aarch64-unknown-none-elf','-c',ROOT/'tools/hab/qnx_abi/start.S','-o',out/'start.o'])
    binary=ROOT/'.work/kanzi-field/path-guard'
    run([llvm/'ld.lld','-pie','-E','-z','now','-z','norelro','--no-rosegment','-z','separate-loadable-segments','-z','max-page-size=4096','--hash-style=gnu','-dynamic-linker','/usr/lib/ldqnx-64.so.2','-e','_start',out/'start.o',out/'guard.o',out/'libc-link-only.so','-o',binary])
    elf=ELF(binary);assert len([p for p in elf.ph if p['type']==1])==2
    assert {s['name'] for s in elf.syms if s['name'] and not s['defined']}<=set(required)
    print(json.dumps(dict(bytes=binary.stat().st_size,sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),targetExecuted=False)))
if __name__=='__main__':main()
