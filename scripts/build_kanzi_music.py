#!/usr/bin/env python3
"""离线构建自有音乐组件及固定G733 HMI私有候选；不执行目标，不部署，不分发OEM原件。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import lief

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools/hab"))
from elf64 import ELF

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "galaxy/common/src/main/cpp/kanzi_music"


def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def run(args):
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)


def initialized_bytes(elf, address, size):
    """BSS没有文件内容，必须比较装载时的零值，不能把其后的调试字节当成属性初值。"""
    for segment in elf.ph:
        start = address - segment['vaddr']
        if segment['type'] == 1 and start >= 0 and start + size <= segment['memsz']:
            backed = min(size, max(0, segment['filesz'] - start))
            return elf.take(segment['offset'] + start, backed) + bytes(size - backed)
    raise ValueError('未映射的API地址')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--rootfs", type=Path, required=True)
    ap.add_argument("--hmi", type=Path, help="经逐段核验的实车HMI私有副本；不写原件")
    ap.add_argument("--output", type=Path, default=ROOT / ".work/kanzi-music")
    ap.add_argument(
        "--ndk", type=Path, default=ROOT / ".work/android-sdk/ndk/28.2.13676358"
    )
    a = ap.parse_args()
    profile = json.loads((SOURCE / "abi_profile.json").read_text())
    original = a.hmi or (
        a.rootfs / "apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi"
    )
    if digest(original) not in {profile["elfSha256"], *profile.get("compatibleElfSha256", [])}:
        raise ValueError("原厂HMI摘要与已核验G733基线不符")
    out = a.output.resolve()
    if a.rootfs.resolve() == out or a.rootfs.resolve() in out.parents:
        raise ValueError("不能向原始rootfs写输出")
    # OEM修改候选仅允许生成到本仓库忽略的.work，不能误写入公开源码/发布目录。
    if ROOT / ".work" not in out.parents:
        raise ValueError("候选输出必须位于本仓库.work目录内")
    out.mkdir(parents=True, exist_ok=True)
    llvm = (
        a.ndk
        / "toolchains/llvm/prebuilt"
        / ("darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64")
        / "bin"
    )
    resource = subprocess.check_output(
        [str(llvm / "clang"), "-print-resource-dir"], text=True
    ).strip()
    flags = [
        "--target=aarch64-unknown-nto",
        "-ffreestanding",
        "-nostdinc",
        "-isystem",
        str(Path(resource) / "include"),
        "-I",
        str(ROOT / "tools/hab/qnx_abi"),
        "-I",
        str(SOURCE),
        "-O1",
        "-g",
        "-fPIC",
        "-fno-stack-protector",
        "-fno-omit-frame-pointer",
        "-mno-outline-atomics",
        "-Wall",
        "-Wextra",
        "-Werror",
    ]
    run(
        [
            llvm / "clang",
            *flags,
            "-std=c11",
            "-c",
            SOURCE / "music_frame.c",
            "-o",
            out / "music_frame.o",
        ]
    )
    run(
        [
            llvm / "clang++",
            *flags,
            "-std=c++11",
            "-nostdinc++",
            "-fno-exceptions",
            "-fno-rtti",
            "-c",
            SOURCE / "music_component.cpp",
            "-o",
            out / "music_component.o",
        ]
    )
    # 只生成链接元数据，不复制QNX libc。系统符号必须在本车原件实际导出。
    required = [
        "malloc",
        "free",
        "open",
        "read",
        "close",
        "time",
        "memcmp",
        "memcpy",
        "memset",
        "strlen",
        "strcmp",
        "fopen",
        "fwrite",
        "fclose",
        "getpid",
        "getuid",
    ]
    libc = a.rootfs / "lib64/libc.so.4"
    e = ELF(libc)
    available = {
        s["name"]
        for s in e.syms
        if s["defined"] and s["visibility"] == 0 and s["bind"] in (1, 2)
    }
    if set(required) - available:
        raise ValueError("目标libc缺少导入: " + str(set(required) - available))
    (out / "libc-imports.S").write_text(
        ".text\n"
        + "".join(f".global {s}\n.type {s},%function\n{s}:\nret\n" for s in required)
    )
    run(
        [
            llvm / "clang",
            "--target=aarch64-unknown-none-elf",
            "-c",
            out / "libc-imports.S",
            "-o",
            out / "libc-imports.o",
        ]
    )
    run(
        [
            llvm / "ld.lld",
            "-shared",
            "-soname",
            "libc.so.4",
            out / "libc-imports.o",
            "-o",
            out / "libc-link-only.so",
        ]
    )
    addon = out / "libgalaxy_kanzi_music.so"
    run(
        [
            llvm / "ld.lld",
            "-shared",
            "-soname",
            addon.name,
            "--hash-style=gnu",
            "-z", "now", "-z", "norelro", "--no-rosegment",
            "-z", "separate-loadable-segments",
            "-z",
            "max-page-size=4096",
            out / "music_frame.o",
            out / "music_component.o",
            out / "libc-link-only.so",
            "-o",
            addon,
        ]
    )
    sys.path.insert(0, str(ROOT / "tools/kanzi_music"))
    from patch_hmi import patch
    patched = out / "cluster-hmi-music-candidate"
    imported, changes, relative_count, moved_debug = patch(
        original, addon, patched, dict(profile, libcImports=required)
    )
    addon_elf = ELF(addon)
    if len([p for p in addon_elf.ph if p['type'] == 1]) != 2:
        raise ValueError('自有SO须沿已实车HAB链接参数保持两个LOAD段')
    checked = lief.ELF.parse(str(patched))
    baseline = lief.ELF.parse(str(original))
    checked_elf, baseline_elf = ELF(patched), ELF(original)
    address_delta = 0
    if bytes(checked.get_section('.text').content) != bytes(baseline.get_section('.text').content):
        raise ValueError('原厂完整机器码发生变化')
    for entry in changes:
        rs = [r for r in checked.relocations if r.has_symbol and r.symbol.name == entry['new']]
        if len(rs) != 1 or rs[0].address != int(entry['address'], 16) or rs[0].type != lief.ELF.Relocation.TYPE.AARCH64_ABS64:
            raise ValueError('回调重定位核对失败')
    for item in imported:
        symbol = checked.get_dynamic_symbol(item['name'])
        if symbol is None or symbol.value != int(item['address'], 16):
            raise ValueError('原厂API地址改变: ' + item['name'])
        if initialized_bytes(checked_elf, symbol.value, symbol.size) != initialized_bytes(baseline_elf, symbol.value, symbol.size):
            raise ValueError('API机器码发生变化')
    report = {
        "buildClass": "qnx-reconstructed-abi-candidate-not-sdk",
        "targetExecuted": False,
        "deviceConnected": False,
        "oemOriginalModified": False,
        "componentMounted": False,
        "originalSha256": digest(original),
        "libcSha256": digest(libc),
        "artifacts": {
            p.name: {"sha256": digest(p), "bytes": p.stat().st_size}
            for p in [addon, patched]
        },
        "hooks": changes,
        "hostImports": imported,
        "elfAddressDelta": hex(address_delta),
        "relativeRelocationPrefix": relative_count,
        "hmiLoadSegments": 2,
        "movedDebugSections": moved_debug,
        "componentLoadSegments": 2,
        "sourceSha256": {
            str(p.relative_to(ROOT)): digest(p)
            for p in sorted([*SOURCE.iterdir(), ROOT / "scripts/build_kanzi_music.py", ROOT / "tools/kanzi_music/patch_hmi.py"])
            if p.is_file()
        },
    }
    (out / "build.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                "output": str(out),
                "hostImports": len(imported),
                "hooks": len(changes),
                "targetExecuted": False,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
