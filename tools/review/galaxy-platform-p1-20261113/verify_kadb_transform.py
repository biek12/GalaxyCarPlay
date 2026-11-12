"""读取真实 AGP ASM 产物，核验 KADB 两个 RSA 调用和实际目标类；不执行应用。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OWNER = "com/ecarx/autoservice/platform/adb/AdbCrypto"
CERT_CLASS = "com/flyfishxu/kadb/cert/CertUtils.class"
METHODS = {"parsePrivateKeyPem", "derivePublicKey"}


def inspect_artifacts(root: Path = ROOT) -> dict:
    """读取本轮 AGP debug 仪器化 artifact view，核验实际调用及已编译的局部 Provider。"""
    version = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    if not re.search(r'^kadb\s*=\s*"2\.1\.1"\s*$', version, re.M):
        raise SystemExit("KADB 固定版本不符，必须重新核验 ASM 接点")
    report = Path(os.environ.get("GALAXY_R0_REPORT_DIR", str(root / ".work/p1-validation")))
    inventory = json.loads((report / "kadb-transform-inputs.json").read_text(encoding="utf-8"))
    if (Path(inventory["projectRoot"]).resolve() != root.resolve() or
            inventory["task"] != ":service:transformDebugClassesWithAsm" or
            inventory.get("consumerTask") != ":service:testDebugUnitTest"):
        raise SystemExit("KADB transformer 文件清单不属于当前工作树/任务")
    matches = []
    for jar in sorted({Path(path) for path in inventory["instrumentedArtifacts"] if path.endswith(".jar")}):
        with zipfile.ZipFile(jar) as archive:
            if CERT_CLASS in archive.namelist():
                matches.append(jar)
    javap = os.environ.get("GALAXY_JAVAP") or shutil.which("javap")
    if not javap:
        raise SystemExit("缺少本机 JDK javap")
    instrumented = []
    for jar in matches:
        result = subprocess.run(
            [javap, "-J-Dfile.encoding=UTF-8", "-c", "-p", "-classpath", str(jar),
             "com.flyfishxu.kadb.cert.CertUtils"],
            cwd=root, capture_output=True, text=True, encoding="utf-8", check=True,
        )
        current_method = ""
        calls = []
        unpatched = []
        for line in result.stdout.splitlines():
            declaration = re.search(r"\b([A-Za-z_$][\w$]*)\([^;]*\).*;\s*$", line)
            if declaration and not re.match(r"\s*\d+:", line):
                current_method = declaration.group(1)
            call = re.search(r"invokestatic\s+.*// Method ([\w/$]+)\.keyFactory:\(Ljava/lang/String;\)Ljava/security/KeyFactory;", line)
            if call:
                calls.append({"method": current_method, "owner": call.group(1)})
            if current_method in METHODS and "// Method java/security/KeyFactory.getInstance:" in line:
                unpatched.append(current_method)
        # 只按是否发生过真实补丁识别，不按期望 owner 挑选，也不扫描其他历史缓存。
        if calls:
            instrumented.append((jar, calls, unpatched))
    if len(instrumented) != 1:
        raise SystemExit(f"当前 AGP 消费路径中仪器化 CertUtils 零匹配或重复: {len(instrumented)}")
    jar, calls, unpatched = instrumented[0]
    if len(calls) != 2 or {call["method"] for call in calls} != METHODS or any(call["owner"] != OWNER for call in calls) or unpatched:
        raise SystemExit(f"KADB 真实 ASM 调用未正确链接: {json.dumps(calls, ensure_ascii=False)}; 未替换={unpatched}")
    classes = root / "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    target = classes / f"{OWNER}.class"
    if not target.is_file() or (classes / "com/ecarx/autoservice/platform/AdbCrypto.class").exists():
        raise SystemExit("新 AdbCrypto 类缺失或旧包类仍残留")
    target_view = subprocess.run(
        [javap, "-J-Dfile.encoding=UTF-8", "-p", "-classpath", str(classes), OWNER.replace("/", ".")],
        cwd=root, capture_output=True, text=True, encoding="utf-8", check=True,
    ).stdout
    if not re.search(r"public static .*java\.security\.KeyFactory keyFactory\(java\.lang\.String\)", target_view):
        raise SystemExit("AdbCrypto 缺少兼容的 static keyFactory(String) 接点")
    return {
        "kadbVersion": "2.1.1", "calls": calls,
        "transformedJar": jar.as_posix(),
        "transformedJarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "targetClass": target.relative_to(root).as_posix(),
        "targetClassSha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "sourceHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "apkBuilt": False, "vehicleValidated": False,
    }


def main() -> None:
    """将真实产物核验写入当前任务报告，不以源码字符串存在代替 ASM 输出。"""
    record = inspect_artifacts()
    report = Path(os.environ.get("GALAXY_R0_REPORT_DIR", str(ROOT / ".work/p1-validation")))
    report.mkdir(parents=True, exist_ok=True)
    (report / "kadb-transform.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("KADB ASM 产物通过：两个 RSA 接点均调用新包 AdbCrypto，实际 static 目标类存在。")


if __name__ == "__main__":
    main()
