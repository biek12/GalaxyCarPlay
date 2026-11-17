"""AutoClient当前任务范围、真实Client JUnit/编译类及固定公开JVM API。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools/review/galaxy-account-a1-20261113"))
from verify_account import check_scope, junit  # noqa: E402


def scope(root: Path = ROOT) -> dict:
    """固定业务来源和当前9路径范围，不随着HEAD移动。"""
    return json.loads((root / HERE.relative_to(ROOT) / "scope.json").read_text(encoding="utf-8"))


def api_surface(classes: Path, name: str = "com.galaxy.auto.client.AutoClient") -> list[dict]:
    """沿现有KADB工具的JDK javap读取真实class，保留default参数入口，排除编译器access$桥。"""
    javap = os.environ.get("GALAXY_JAVAP") or shutil.which("javap")
    if not javap:
        raise SystemExit("缺少本机JDK javap")
    result = subprocess.run([javap, "-J-Dfile.encoding=UTF-8", "-public", "-s", "-classpath", str(classes), name],
                            capture_output=True, text=True, encoding="utf-8", check=True)
    entries = []
    declaration = None
    for line in result.stdout.splitlines():
        text = line.strip()
        if text.startswith("public ") and text.endswith(";"):
            declaration = text if "access$" not in text else None
        elif declaration and text.startswith("descriptor:"):
            entries.append({"declaration": declaration, "descriptor": text.split(":", 1)[1].strip()})
            declaration = None
    if not entries:
        raise SystemExit("公开class API为空，不能验收")
    return sorted(entries, key=lambda item: (item["declaration"], item["descriptor"]))


def compare_api(expected: list[dict], actual: list[dict]) -> None:
    """公开构造、方法、default入口和字段必须保持；不让迁移悄悄增删API。"""
    if actual != expected:
        raise SystemExit("AutoClient公开JVM API与已审基线不一致")


def before(root: Path = ROOT) -> None:
    """先拒绝越界与未完成文件，再进入格式/编译；不会将工具准备当作代码交付。"""
    record = scope(root)
    check_scope(record, root)
    missing = [p for p in record["writeWhitelist"] if not (root / p).is_file()]
    if missing:
        raise SystemExit(f"A2尚未完整实施: {missing}")
    text = (root / record["writeWhitelist"][0]).read_text(encoding="utf-8")
    names = re.findall(r"(?m)^    (?:public\s+)?(?:override\s+)?fun\s+(\w+)\s*\(", text)
    if sorted(names) != sorted(record["publicMethods"]):
        raise SystemExit("AutoClient公开24方法遗漏/新增，不能用默认空实现或改名完成迁移")


def inspect(root: Path = ROOT) -> dict:
    """实际全Client报告、5类与公开class API，并由全下游编译覆盖调用兼容。"""
    before(root)
    record = scope(root)
    classes = root / "galaxy/client/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    paths = [classes / (name + ".class") for name in record["productionTypes"]]
    missing = [p.relative_to(root).as_posix() for p in paths if not p.is_file()]
    if missing:
        raise SystemExit(f"A2缺少真实运行类: {missing}")
    expected = json.loads((root / HERE.relative_to(ROOT) / "api-contract.json").read_text(encoding="utf-8"))
    actual = api_surface(classes)
    compare_api(expected["publicApi"], actual)
    return {"businessChangedPaths": check_scope(record, root), "junit": junit(record, root=root),
            "publicApi": actual, "publicApiPreserved": True,
            "classes": [{"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths],
            "manualStateThreadAndOwnershipReviewRequired": True}


def main() -> None:
    """复用原Windows/Shell环境；baseline只证明现有真实套件，始终wholeTaskReady=false。"""
    mode = sys.argv[1] if len(sys.argv) > 1 else "before"
    if mode == "before":
        before()
    elif mode == "baseline":
        record = scope()
        check_scope(record)
        classes = ROOT / "galaxy/client/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
        actual = api_surface(classes)
        expected = json.loads((HERE / "api-contract.json").read_text(encoding="utf-8"))
        compare_api(expected["publicApi"], actual)
        data = {"task": record["task"], "status": "BASELINE_ONLY", "wholeTaskReady": False,
                "junit": junit(record, baseline=True), "publicApi": actual,
                "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "apkBuilt": False, "vehicleValidated": False}
        report = Path(os.environ["GALAXY_R0_REPORT_DIR"])
        (report / "CLIENT_BASELINE.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"A2工具基线: Client {data['junit']['suiteCount']}套{data['junit']['caseCount']}例，JVM API一致；尚非整包交付。")
    else:
        raise SystemExit(f"未知A2模式: {mode}")


if __name__ == "__main__":
    main()
