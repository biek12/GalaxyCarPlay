#!/usr/bin/env python3
"""构建APK内隔离HAB发送进程；仅使用本仓库源码和已安装NDK，不下载或连接设备。"""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HAB = ROOT / "galaxy/service/src/main/cpp/hab"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    platform = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    compiler = args.ndk / "toolchains/llvm/prebuilt" / platform / "bin/aarch64-linux-android29-clang"
    if not compiler.is_file():
        parser.error("NDK 28.2.13676358未安装；不自动下载")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    sources = [HAB / f"galaxy_hab_{name}.c" for name in
               ("transport", "linux", "pixels", "protocol", "owned", "sender", "hmac", "bridge")]
    subprocess.run([str(compiler), "-std=c11", "-O2", "-g", "-fPIE", "-pie",
                    "-fno-omit-frame-pointer", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
                    "-I", str(HAB), *map(str, sources), "-o", str(args.output)], check=True)

if __name__ == "__main__":
    main()
