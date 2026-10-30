#!/usr/bin/env python3
"""自有HAB源码验证：主机合同测试，可选Android静态库/QNX接收进程编译；不运行目标代码。"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "galaxy/service/src/main/cpp/hab"
SOURCES = (
    "galaxy_hab_transport.c", "galaxy_hab_linux.c", "galaxy_hab_pixels.c",
    "galaxy_hab_protocol.c", "galaxy_hab_sender.c", "galaxy_hab_hmac.c", "galaxy_hab_receiver.c",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", action="store_true", help="另编译Android API29/arm64静态库，不执行/安装")
    parser.add_argument("--qnx", action="store_true", help="另用已配置QNX SDK编译接收进程，不部署/执行")
    parser.add_argument("--qnx-compiler", default=os.environ.get("GALAXY_QNX_CC", "qcc"),
                        help="QNX SDK qcc路径，默认qcc；不使用Android libc或占位库替代")
    parser.add_argument("--ndk", type=Path, default=Path(os.environ.get(
        "GALAXY_ANDROID_NDK", str(ROOT / ".work/android-sdk/ndk/28.2.13676358"))),
        help="已安装NDK；不自动下载或安装")
    args = parser.parse_args()
    subprocess.run([sys.executable, "-m", "unittest", "scripts.tests.test_hab_native", "-v"],
                   cwd=ROOT, check=True)
    if args.android:
        build_android(args, parser)
    if args.qnx:
        compiler = shutil.which(args.qnx_compiler)
        if not compiler:
            parser.error("缺少已配置QNX SDK qcc；主机合同测试已执行，不冒充QNX目标编译")
        output = ROOT / ".work/hab-native/qnx-aarch64le"
        output.mkdir(parents=True, exist_ok=True)
        sources = ["galaxy_hab_protocol.c", "galaxy_hab_hmac.c", "galaxy_hab_receiver.c",
                   "galaxy_hab_qnx.c", "galaxy_hab_qnx_main.c"]
        subprocess.run([compiler, "-Vgcc_ntoaarch64le", "-std=c11", "-Wall", "-Wextra",
                        "-Wpedantic", "-Werror", "-I", str(NATIVE),
                        *[str(NATIVE / source) for source in sources],
                        "-lscreen", "-luhab-qvmhost", "-lpmem_client",
                        "-o", str(output / "galaxy_hab_qnx_receiver")], check=True)
        print(f"QNX自有接收进程编译通过: {output.relative_to(ROOT)}/galaxy_hab_qnx_receiver")
        print("未复制OEM库、未读取认证文件、未部署或执行。")


def build_android(args, parser):
    platform = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    tools = args.ndk.resolve() / "toolchains/llvm/prebuilt" / platform / "bin"
    compiler, archiver = tools / "aarch64-linux-android29-clang", tools / "llvm-ar"
    if not compiler.is_file() or not archiver.is_file():
        parser.error(f"缺少已安装NDK工具链: {tools}；本脚本不自动安装")
    output = ROOT / ".work/hab-native/arm64"
    output.mkdir(parents=True, exist_ok=True)
    objects = []
    for source in SOURCES:
        target = output / (Path(source).stem + ".o")
        subprocess.run([str(compiler), "-std=c11", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
                        "-fPIC", "-I", str(NATIVE), "-c", str(NATIVE / source), "-o", str(target)], check=True)
        objects.append(target)
    archive = output / "libgalaxy_hab.a"
    subprocess.run([str(archiver), "rcs", str(archive), *map(str, objects)], check=True)
    print(f"Android arm64自有源码静态库编译通过: {archive.relative_to(ROOT)}")
    print("未加载第三方SO、未执行目标ELF、未打包APK、未连接设备。")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
