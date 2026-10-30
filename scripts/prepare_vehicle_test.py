#!/usr/bin/env python3
"""准备本机上车测试包：只assembleDebug、原生目标编译、打包和摘要；不连接设备。"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROBES = ROOT / "tools/hab"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def runtime_tar(package, platform):
    target = package / (platform + "-runtime.tar")
    selected = sorted((package / platform).rglob("*")) + [package / "test-only-auth.bin"]
    with tarfile.open(target, "w", format=tarfile.USTAR_FORMAT) as archive:
        for source in selected:
            if not source.is_file():
                continue
            info = archive.gettarinfo(str(source), package.name + "/" + source.relative_to(package).as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mode = 0o600 if source.name.endswith(".bin") else 0o700
            with source.open("rb") as stream:
                archive.addfile(info, stream)


def finalize(package, evidence_path=None):
    package = package.resolve()
    previous = package / "manifest.json"
    manifest = json.loads(previous.read_text()) if previous.is_file() else {}
    if evidence_path:
        evidence = json.loads(evidence_path.read_text())
        for platform, name in (("android", "galaxy_hab_android_probe"), ("qnx", "galaxy_hab_qnx_probe-candidate")):
            if sha(package / platform / name) != evidence["artifact_sha256"][name]:
                raise ValueError("现场证据与包内二进制摘要不一致")
        for name in ("target-validation.json", "android-transport.log", "qnx-transport.log",
                     "android-frames.log", "qnx-frames.log", "android-ring.log", "qnx-ring.log",
                     "oem-before.txt", "oem-after.txt", "test-pattern.png"):
            source = evidence_path.parent / name
            if source.is_file():
                copy(source, package / "evidence" / name)
        manifest.update(target_executed=True, device_connected=True, target_validation=evidence)
    copy(PROBES / "verify_bundle.py", package / "verify_bundle.py")
    copy(ROOT / "docs/AutoService实施与测试.md", package / "README.md")
    for name in ("CarPlay-HAB与微信语音修复.md", "HAB连接稳定性修复-20261030.md", "仪表布局快照-20261030.md"):
        copy(ROOT / "docs" / name, package / name)
    copy(ROOT / "LICENSE", package / "LICENSE")
    copy(ROOT / "docs/AutoService实施与测试.md", package / "AutoService实施与测试.md")
    report = ROOT / ".work/logs/validation-auto-platform.json"
    if report.is_file(): copy(report, package / "validation/validation-auto-platform.json")
    for relative in ("galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyAutoServiceBinding.kt",
                     "galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyHostAdapter.kt",
                     "settings.gradle.kts", "gradle/libs.versions.toml", "common/build.gradle.kts",
                     "scripts/verify_auto_platform.sh", "scripts/record_auto_platform_validation.py",
                     "scripts/prepare_auto_qnx_asset.py"):
        copy(ROOT / relative, package / "sources" / relative)
    for preview in (ROOT / "galaxy/cluster/build/screenshots").glob("*.png"):
        copy(preview, package / "previews" / preview.name)
    for folder in ("galaxy/contracts", "galaxy/client", "galaxy/service", "galaxy/cluster"):
        for source in (ROOT / folder).rglob("*"):
            if "build" not in source.relative_to(ROOT / folder).parts and source.is_file() and source.suffix in {".kt", ".kts", ".aidl", ".xml", ".c", ".h", ".txt", ".json"}:
                copy(source, package / "sources" / source.relative_to(ROOT))
    copy(ROOT / "scripts/prepare_vehicle_test.py", package / "sources/scripts/prepare_vehicle_test.py")
    for source in sorted(PROBES.rglob("*")):
        if source.is_file() and "__pycache__" not in source.parts and source.name != ".DS_Store":
            copy(source, package / "sources/tools/hab" / source.relative_to(PROBES))
    for platform in ("android", "qnx"):
        for name in ("run", "stop", "snapshot"):
            copy(PROBES / (name + "-" + platform + ".sh"), package / platform / (name + ".sh"))
        runtime_tar(package, platform)
    copy(PROBES / "audio-snapshot-android.sh", package / "android/audio-snapshot.sh")
    runtime_tar(package, "android")
    files = {p.relative_to(package).as_posix(): dict(sha256=sha(p), bytes=p.stat().st_size)
             for p in sorted(package.rglob("*")) if p.is_file()
             and p.relative_to(package).as_posix() not in ("manifest.json", "SHA256SUMS")}
    manifest["files"] = files
    previous.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (package / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {name}\n" for name, item in files.items()) +
                                      f"{sha(previous)}  manifest.json\n")
    subprocess.run([sys.executable, str(PROBES / "verify_bundle.py"), str(package)], check=True)
    zipped = package.with_suffix(".zip")
    with zipfile.ZipFile(zipped, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for source in sorted(package.rglob("*")):
            if source.is_file():
                output.write(source, package.name + "/" + source.relative_to(package).as_posix())
    zipped.with_suffix(".zip.sha256").write_text(f"{sha(zipped)}  {zipped.name}\n")
    print(f"测试包ZIP: {zipped}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qnx-rootfs", type=Path,
                        help="已采集目标rootfs，只用于静态核验系统库导入，不将OEM库打包")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--finalize-existing", type=Path, help="补入已采集现场证据并重算摘要，不重新构建APK")
    parser.add_argument("--target-evidence", type=Path, help="按二进制摘要绑定的人工帧现场验证JSON")
    args = parser.parse_args()
    if args.finalize_existing:
        finalize(args.finalize_existing, args.target_evidence)
        return
    if not args.qnx_rootfs:
        parser.error("构建需要--qnx-rootfs；仅整理证据可使用--finalize-existing")
    stamp = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d-%H%M%S")
    package = (args.output or ROOT / ".work/vehicle-test" / ("galaxy-hab-test-" + stamp)).resolve()
    if package.exists():
        parser.error("输出目录已存在；选择新的目录，不覆盖旧测试包")
    # 用户明确要求快速产APK：这里仅assembleDebug，绝不调用lint/整套单测。
    subprocess.run([sys.executable, str(ROOT / "scripts/build_hab_probes.py"),
                    "--qnx-rootfs", str(args.qnx_rootfs.resolve())], cwd=ROOT, check=True)
    subprocess.run(["bash", str(ROOT / "scripts/build_apk_fast.sh")], cwd=ROOT, check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/record_auto_platform_validation.py")], cwd=ROOT, check=True)
    build = ROOT / ".work/hab-probes"
    package.mkdir(parents=True)
    copy(ROOT / "galaxy/mobile/build/outputs/apk/debug/mobile-debug.apk", package / "app/CarPlay-debug.apk")
    for module, label in (("service", "AutoService"), ("cluster", "AutoCluster")):
        copy(ROOT / f"galaxy/{module}/build/outputs/apk/debug/{module}-debug.apk", package / f"app/{label}-debug.apk")
    copy(build / "android/galaxy_hab_android_probe", package / "android/galaxy_hab_android_probe")
    copy(build / "qnx/galaxy_hab_qnx_probe-candidate", package / "qnx/galaxy_hab_qnx_probe-candidate")
    with zipfile.ZipFile(package / "app/AutoService-debug.apk") as apk:
        if apk.read("assets/qnx/receiver-candidate") != (package / "qnx/galaxy_hab_qnx_probe-candidate").read_bytes():
            raise ValueError("AutoService内QNX资产与测试包接收程序不是同一构建")
    for platform in ("android", "qnx"):
        for name in ("run", "stop", "snapshot"):
            copy(PROBES / (name + "-" + platform + ".sh"), package / platform / (name + ".sh"))
    for platform, binary in (("android", "galaxy_hab_android_probe"), ("qnx", "galaxy_hab_qnx_probe-candidate")):
        copy(package / platform / binary, package / "symbols" / (binary + ".elf"))
    archive = ROOT / ".work/hab-native/arm64/libgalaxy_hab.a"
    if archive.is_file():
        copy(archive, package / "lib/android-arm64/libgalaxy_hab.a")
    copy(build / "build-report.json", package / "build-report.json")
    copy(ROOT / ".work/logs/build-apk-fast.log", package / "build-apk-fast.log")
    bridge = ROOT / "galaxy/service/build/generated/hab/jniLibs/arm64-v8a/libgalaxy_hab_bridge.so"
    if bridge.is_file():
        copy(bridge, package / "symbols/galaxy_hab_bridge.elf")
    for name in ("verify-carplay-hab-native.log", "verify-carplay-hab-gradle.log", "validation-carplay-hab.json"):
        source = ROOT / ".work/logs" / name
        if source.is_file():
            copy(source, package / "validation" / name)
    copy(ROOT / "docs/AutoService实施与测试.md", package / "README.md")
    copy(ROOT / "docs/CarPlay-HAB与微信语音修复.md", package / "CarPlay-HAB与微信语音修复.md")
    for name in ("HAB连接稳定性修复-20261030.md", "仪表布局快照-20261030.md"):
        copy(ROOT / "docs" / name, package / name)
    copy(PROBES / "verify_bundle.py", package / "verify_bundle.py")
    # 明确公开合成fixture；未读取任何用户认证目录或实际key。只适用于本项目诊断端点。
    (package / "test-only-auth.bin").write_bytes(bytes(range(32)))
    (package / "test-only-auth.bin").chmod(0o600)
    for source in sorted((ROOT / "galaxy/service/src/main/cpp/hab").glob("*")):
        if source.is_file():
            copy(source, package / "sources/galaxy/service/src/main/cpp/hab" / source.name)
    for source in sorted(PROBES.rglob("*")):
        if source.is_file() and "__pycache__" not in source.parts and source.name != ".DS_Store":
            copy(source, package / "sources/tools/hab" / source.relative_to(PROBES))
    copy(ROOT / "scripts/build_hab_probes.py", package / "sources/scripts/build_hab_probes.py")
    for name in ("build_hab_bridge.py", "verify_carplay_hab.sh", "record_carplay_hab_validation.py"):
        copy(ROOT / "scripts" / name, package / "sources/scripts" / name)
    # 产品合成与核心接缝也随测试包提供，供后续离线定位；不遍历身份或本机私有目录。
    for folder in ("galaxy/service/src/main/java", "galaxy/service/src/test/java"):
        for source in (ROOT / folder).rglob("GalaxyHab*.kt"):
            copy(source, package / "sources" / source.relative_to(ROOT))
    for name in ("GalaxyHostAdapter.kt",):
        source = ROOT / "galaxy/common/src/main/java/com/shilapi/xcertplay" / name
        copy(source, package / "sources" / source.relative_to(ROOT))
    for path in ("common/src/main/java/com/shilapi/xcertplay/CarPlayHostProduct.kt",
                 "common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
                 "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
                 "shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt",
                 "shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneInputPolicy.kt",
                 "shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt"):
        copy(ROOT / path, package / "sources" / path)
    runtime_tar(package, "android")
    runtime_tar(package, "qnx")
    # 只允许自有源码/产物；导入元数据与OEM SO不得混入可分发目录。
    if list(package.rglob("*.so*")) or list(package.rglob("*.o")):
        raise ValueError("测试包意外包含SO/中间对象，已停止")
    files = {p.relative_to(package).as_posix(): dict(sha256=sha(p), bytes=p.stat().st_size)
             for p in sorted(package.rglob("*")) if p.is_file()}
    manifest = dict(created_at=datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
                    qnx_sdk_build_complete=False, qnx_candidate_built=True,
                    target_executed=False, device_connected=False, installed=False,
                    apk_tasks=[":mobile:assembleDebug", ":service:assembleDebug", ":cluster:assembleDebug"], lint_run=False, unit_tests_run=False,
                    carplay_hab_pipeline_implemented=True, carplay_frames_connected_to_hab=False,
                    authentication="synthetic-fixture-for-diagnostics; app-generates-private-owner-key-for-serve",
                    git_head=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    files=files)
    validation = ROOT / ".work/logs/validation-carplay-hab.json"
    if validation.is_file():
        tested = json.loads(validation.read_text())
        if tested.get("apk_sha256") == sha(package / "app/CarPlay-debug.apk"):
            manifest["previous_local_validation"] = dict(recorded_at=tested["recorded_at"],
                total_tests=tested["total_tests"], apk_sha256=tested["apk_sha256"], lint_run=False)
    (package / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"测试包目录: {package}")
    finalize(package, args.target_evidence)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
