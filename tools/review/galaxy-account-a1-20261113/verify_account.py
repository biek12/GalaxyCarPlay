"""共享车辆账号页：真实Git范围、Client JUnit、AGP类/Manifest与页面截图。"""
from __future__ import annotations

import hashlib
import json
import os
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


def scope(root: Path = ROOT) -> dict:
    """范围比较起点与业务来源分别固定，不追随候选HEAD移动。"""
    return json.loads((root / HERE.relative_to(ROOT) / "scope.json").read_text(encoding="utf-8"))


def check_scope(record: dict, root: Path = ROOT) -> list[str]:
    """核对全部已提交/未提交及未跟踪galaxy差异，包含删除和改名两端。"""
    files = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", record["scopeBaseline"], "--", "galaxy"],
        cwd=root, text=True, encoding="utf-8").splitlines()
    files += subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "galaxy"],
        cwd=root, text=True, encoding="utf-8").splitlines()
    outside = sorted(set(files) - set(record["writeWhitelist"]))
    if outside:
        raise SystemExit(f"A1业务越界: {outside}")
    return sorted(set(files))


def junit(record: dict, baseline: bool = False, root: Path = ROOT) -> dict:
    """读取本轮全部Client真实JUnit；未来必测非空，任一额外失败/跳过同样拒绝。"""
    rows = []
    for path in sorted((root / "galaxy/client/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
        node = ET.parse(path).getroot()
        row = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        name = node.get("name", "")
        if (not name or path.name != f"TEST-{name}.xml" or row["tests"] <= 0 or
                any(row[key] for key in ("failures", "errors", "skipped"))):
            raise SystemExit(f"A1 JUnit未达标: {path.name}: {row}")
        rows.append({"suite": name, **row, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    missing = set(record["baselineSuites" if baseline else "requiredSuites"]) - {row["suite"] for row in rows}
    if not rows or missing:
        raise SystemExit(f"A1缺少必测suite: {sorted(missing)}")
    return {"suiteCount": len(rows), "caseCount": sum(row["tests"] for row in rows), "suites": rows}


def manifests(root: Path = ROOT) -> list[dict]:
    """读取Service与Record实际合并的Manifest，不改共享页面组件身份或公开边界。"""
    rows = []
    for module in ("service", "record"):
        path = root / f"galaxy/{module}/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
        node = ET.parse(path).getroot()
        activities = [item for item in node.findall("./application/activity")
                      if item.get(ANDROID + "name") == "com.galaxy.auto.client.vehicle.VehicleAccountActivity"]
        if len(activities) != 1 or activities[0].get(ANDROID + "exported") != "false":
            raise SystemExit(f"共享账号组件合同改变: {path}")
        rows.append({"path": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return rows


def screenshots(record: dict, folder: Path) -> list[dict]:
    """检查测试实际绘制的PNG及尺寸/摘要；来源仍须审查真实Activity测试调用。"""
    rows = []
    for name in record["screenshots"]:
        path = folder / name
        data = path.read_bytes()
        if len(data) < 64 or data[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
            raise SystemExit(f"页面截图不是有效PNG: {path}")
        if struct.unpack(">II", data[16:24]) != (1080, 1800):
            raise SystemExit(f"页面截图尺寸不符: {path}")
        rows.append({"file": name, "sha256": hashlib.sha256(data).hexdigest(), "width": 1080, "height": 1800})
    if len({row["sha256"] for row in rows}) != len(rows):
        raise SystemExit("不同页面截图字节重复，须核对是否实际渲染了对应状态")
    return rows


def before(root: Path = ROOT) -> None:
    """最终模式严格要求18项全部存在；不把工具准备当业务交付。"""
    record = scope(root)
    check_scope(record, root)
    missing = [p for p in record["writeWhitelist"] if not (root / p).is_file()]
    if missing:
        raise SystemExit(f"A1尚未完整实施: {missing}")


def colors(record: dict, root: Path = ROOT) -> None:
    """真实Git资源与当前XML比较，允许格式整理但不改已确认日夜颜色。"""
    for relative in ("galaxy/client/src/main/res/values/vehicle_account.xml",
                     "galaxy/client/src/main/res/values-night/vehicle_account.xml"):
        original = subprocess.check_output(["git", "show", f"{record['sourceBaseline']}:{relative}"], cwd=root)
        before_colors = {n.get("name"): n.text for n in ET.fromstring(original).findall("color")}
        after_colors = {n.get("name"): n.text for n in ET.parse(root / relative).getroot().findall("color")}
        if before_colors != after_colors:
            raise SystemExit(f"A1既有日夜色板改变: {relative}")


def inspect(root: Path = ROOT) -> dict:
    """真实编译类/双宿主Manifest/全Client套件；静态检查不代替行为用例。"""
    before(root)
    record = scope(root)
    build = root / "galaxy/client/build/intermediates"
    kotlin = build / "built_in_kotlinc/debug/compileDebugKotlin/classes"
    java = build / "javac/debug/compileDebugJavaWithJavac/classes"
    required = [kotlin / (name + ".class") for name in record["productionTypes"]]
    required += [java / f"com/galaxy/auto/client/databinding/{name}.class" for name in (
        "ActivityVehicleAccountBinding", "ItemVehicleBindingRequestBinding")]
    missing = [str(path.relative_to(root)) for path in required if not path.is_file()]
    if missing:
        raise SystemExit(f"A1缺少真实运行类/ViewBinding: {missing}")
    colors(record, root)
    report = Path(os.environ.get("GALAXY_R0_REPORT_DIR", str(root / ".work/a1-validation")))
    return {"businessChangedPaths": check_scope(record, root), "junit": junit(record, root=root),
            "classes": [{"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in required],
            "manifests": manifests(root), "screenshots": screenshots(record, report / "screenshots"),
            "manualUiAndRequestOrderReviewRequired": True}


def main() -> None:
    """同一原Shell入口调用；baseline只检查现有真实套件并显式标记尚非整体交付。"""
    mode = sys.argv[1] if len(sys.argv) > 1 else "before"
    if mode == "before":
        before()
    elif mode == "baseline":
        record = scope()
        check_scope(record)
        report = Path(os.environ["GALAXY_R0_REPORT_DIR"])
        data = {"task": record["task"], "status": "BASELINE_ONLY", "wholeTaskReady": False,
                "junit": junit(record, baseline=True), "manifests": manifests(),
                "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "apkBuilt": False, "vehicleValidated": False}
        (report / "ACCOUNT_BASELINE.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"A1工具基线: Client {data['junit']['suiteCount']}套{data['junit']['caseCount']}例；尚非任务交付。")
    else:
        raise SystemExit(f"未知A1模式: {mode}")


if __name__ == "__main__":
    main()
