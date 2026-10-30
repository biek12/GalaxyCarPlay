#!/usr/bin/env python3
"""构建Android正式NDK诊断程序及QNX重建ABI测试候选；不连接或执行目标。"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HAB = ROOT / "galaxy/service/src/main/cpp/hab"
PROBES = ROOT / "tools/hab"
sys.path.insert(0, str(PROBES))
from elf64 import ELF

QNX_IMPORTS = {
    "libc.so.4": ["_init_libc", "__get_errno_ptr", "exit", "malloc", "free", "memset",
                  "memcpy", "memcmp", "strcmp", "strlen", "read", "write", "open", "close", "signal", "msync"],
    "libuhab-qvmhost.so": ["habmm_socket_open", "habmm_socket_recv", "habmm_socket_send",
                            "habmm_socket_close", "habmm_import", "habmm_unimport"],
    "libpmem_client.so": ["pmem_init", "pmem_deinit", "pmem_map_handle", "pmem_unmap_handle"],
    "libscreen.so.1": ["screen_create_context", "screen_destroy_context", "screen_flush_context",
                       "screen_get_context_property_iv", "screen_get_context_property_pv",
                       "screen_get_display_property_iv", "screen_create_window", "screen_destroy_window",
                       "screen_set_window_property_iv", "screen_set_window_property_pv",
                       "screen_create_window_buffers", "screen_get_window_property_iv",
                       "screen_get_window_property_pv", "screen_get_buffer_property_iv",
                       "screen_get_buffer_property_pv", "screen_post_window"],
}


def run(command):
    subprocess.run(list(map(str, command)), cwd=ROOT, check=True)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def describe(path, build_class, interpreter, allowed_needed):
    elf = ELF(path)
    actual = None
    for ph in elf.ph:
        if ph["type"] == 3:
            actual = elf.take(ph["offset"], ph["filesz"]).rstrip(b"\0").decode()
    needed = [elf.string(v) for v in elf.dt.get(1, [])]
    if elf.h[1] != 183 or elf.h[0] != 3 or actual != interpreter or set(needed) != set(allowed_needed):
        raise ValueError(f"目标ELF格式/加载器/依赖不符: {path.name}")
    return dict(name=path.name, sha256=sha(path), bytes=path.stat().st_size,
                build_class=build_class, target="aarch64le", interpreter=actual, needed=needed,
                entry=hex(elf.h[3]), undefined_symbols=sorted(s["name"] for s in elf.syms
                    if s["name"] and not s["defined"]))


def build_android(llvm, output):
    sources = [HAB / name for name in ("galaxy_hab_transport.c", "galaxy_hab_linux.c",
               "galaxy_hab_protocol.c", "galaxy_hab_owned.c", "galaxy_hab_pixels.c", "galaxy_hab_sender.c", "galaxy_hab_hmac.c")]
    binary = output / "android/galaxy_hab_android_probe"
    binary.parent.mkdir(parents=True, exist_ok=True)
    run([llvm / "aarch64-linux-android29-clang", "-std=c11", "-O1", "-g", "-fno-omit-frame-pointer",
         "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-I", HAB, "-I", PROBES,
         *sources, PROBES / "probe_common.c", PROBES / "android_probe.c", "-o", binary])
    return describe(binary, "android-ndk-api29", "/system/bin/linker64", ["libc.so", "libdl.so"])


def build_qnx_candidate(llvm, output, rootfs):
    """本机读取已采集的四个ELF，只生成选定导入符号的链接元数据；不复制原库。"""
    work = output / "qnx-build-only"
    work.mkdir(parents=True, exist_ok=True)
    import_libraries, provenance = [], []
    for name, required in QNX_IMPORTS.items():
        original = rootfs / "lib64" / name
        elf = ELF(original)
        if elf.h[1] != 183:
            raise ValueError(f"非aarch64参考库: {name}")
        symbols = {s["name"]: s for s in elf.syms if s["defined"] and s["bind"] in (1, 2) and s["visibility"] == 0}
        if missing := set(required) - symbols.keys():
            raise ValueError(f"目标库缺少已核验导入: {name}: {sorted(missing)}")
        soname = elf.string(elf.one(14))
        if soname != name:
            raise ValueError(f"SONAME不一致: {name}")
        assembly = work / (name + ".imports.S")
        assembly.write_text(".text\n" + "\n".join(
            f".global {symbol}\n.type {symbol},%function\n{symbol}:\nret" for symbol in required) + "\n")
        obj, link = work / (name + ".o"), work / (name + ".link-only.so")
        run([llvm / "clang", "--target=aarch64-unknown-none-elf", "-c", assembly, "-o", obj])
        run([llvm / "ld.lld", "-shared", "-soname", soname, obj, "-o", link])
        import_libraries.append(link)
        provenance.append(dict(soname=soname, sha256=sha(original),
            imported_symbols={s: hex(symbols[s]["value"]) for s in required}))
    resource = subprocess.check_output([str(llvm / "clang"), "-print-resource-dir"], text=True).strip()
    flags = ["--target=aarch64-unknown-nto", "-DGALAXY_QNX_ABI_CANDIDATE=1",
             "-ffreestanding", "-nostdinc", "-isystem", str(Path(resource) / "include"),
             "-I", str(PROBES / "qnx_abi"), "-I", str(HAB), "-I", str(PROBES),
             "-std=c11", "-O1", "-g", "-fPIC", "-fno-stack-protector", "-fno-omit-frame-pointer",
             "-Wall", "-Wextra", "-Wpedantic", "-Werror"]
    sources = [HAB / name for name in ("galaxy_hab_protocol.c", "galaxy_hab_owned.c", "galaxy_hab_hmac.c",
               "galaxy_hab_receiver.c", "galaxy_hab_qnx.c")]
    sources += [PROBES / "probe_common.c", PROBES / "qnx_probe.c"]
    objects = []
    for source in sources:
        obj = work / (source.stem + ".o")
        run([llvm / "clang", *flags, "-c", source, "-o", obj])
        objects.append(obj)
    startup = work / "start.o"
    run([llvm / "clang", "--target=aarch64-unknown-none-elf", "-g", "-c",
         PROBES / "qnx_abi/start.S", "-o", startup])
    binary = output / "qnx/galaxy_hab_qnx_probe-candidate"
    binary.parent.mkdir(parents=True, exist_ok=True)
    run([llvm / "ld.lld", "-pie", "-E", "-z", "now", "-z", "norelro", "--no-rosegment",
         "-z", "separate-loadable-segments", "-z", "max-page-size=4096", "--hash-style=gnu",
         "-dynamic-linker", "/usr/lib/ldqnx-64.so.2", "-e", "_start", startup, *objects,
         *import_libraries, "-o", binary])
    result = describe(binary, "qnx-reconstructed-abi-candidate-not-sdk", "/usr/lib/ldqnx-64.so.2", QNX_IMPORTS)
    available = {symbol for symbols in QNX_IMPORTS.values() for symbol in symbols}
    if unresolved := set(result["undefined_symbols"]) - available:
        raise ValueError(f"发现未经核验的动态导入: {sorted(unresolved)}")
    result["system_library_evidence"] = provenance
    result["sdk_build_complete"] = False
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=ROOT / ".work/android-sdk/ndk/28.2.13676358")
    parser.add_argument("--output", type=Path, default=ROOT / ".work/hab-probes")
    parser.add_argument("--qnx-rootfs", type=Path, help="已采集目标rootfs，只读四个系统ELF，不复制到包")
    args = parser.parse_args()
    platform = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    llvm = args.ndk.resolve() / "toolchains/llvm/prebuilt" / platform / "bin"
    if not (llvm / "clang").is_file():
        parser.error("未找到已安装NDK LLVM；不自动下载SDK")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    reports = [build_android(llvm, output)]
    if args.qnx_rootfs:
        reports.append(build_qnx_candidate(llvm, output, args.qnx_rootfs.resolve()))
    report = dict(artifacts=reports, qnx_sdk_build_complete=False,
                  target_executed=False, device_connected=False,
                  source_sha256={str(p.relative_to(ROOT)): sha(p) for folder in (HAB, PROBES)
                                 for p in sorted(folder.rglob("*"))
                                 if p.is_file() and p.suffix in {".c", ".h", ".S"}},
                  compiler=subprocess.check_output([str(llvm / "clang"), "--version"], text=True).splitlines()[0])
    (output / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    for artifact in reports:
        print(f"已编译 {artifact['name']} ({artifact['build_class']}), SHA256={artifact['sha256']}")
    print("QNX候选不是正式SDP/qcc产物；未执行目标、未连接设备、未复制OEM库。")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
