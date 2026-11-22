"""M1固定范围、Common相关真实JUnit、运行类、View Binding与Activity截图门槛。"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
REL = Path("tools/review/galaxy-settings-m1-20261117")


def scope(root: Path = ROOT) -> dict:
    """任务范围固定到已审A2来源，不随工作HEAD移动。"""
    return json.loads((root / REL / "scope.json").read_text(encoding="utf-8"))


def git_paths(root: Path, args: list[str]) -> set[str]:
    """使用NUL分隔避免中文路径转义漏检，也覆盖删除和未跟踪文件。"""
    return set(subprocess.check_output(["git", *args], cwd=root).decode("utf-8").strip("\0").split("\0")) - {""}


def check_scope(root: Path = ROOT) -> list[str]:
    """所有 App 与上游源码只允许清单内路径；准备好的构建配置只读。"""
    record = scope(root)
    roots = ["galaxy", "common/src", "shared", "archive", "gradle", "build.gradle.kts", "settings.gradle.kts"]
    changed = git_paths(root, ["diff", "--name-only", "--no-renames", "-z", record["scopeBaseline"], "--", *roots])
    changed |= git_paths(root, ["ls-files", "--others", "--exclude-standard", "-z", "--", *roots])
    outside = changed - set(record["writeWhitelist"])
    if outside:
        raise SystemExit(f"M1业务越界: {sorted(outside)}")
    build = root / "common/build.gradle.kts"
    if record.get("preparedBuildSha256") and hashlib.sha256(build.read_text(encoding="utf-8").encode()).hexdigest() != record["preparedBuildSha256"]:
        raise SystemExit("M1准备后的common构建配置被改动，须交Codex处理")
    return sorted(changed)


def before(root: Path = ROOT) -> None:
    """业务全部文件齐备才进入正式门槛，工具baseline不能代替完成。"""
    check_scope(root)
    missing = [name for name in scope(root)["writeWhitelist"] if not (root / name).is_file()]
    if missing:
        raise SystemExit(f"M1尚未完整实施: {missing}")


def junit(root: Path = ROOT, baseline: bool = False) -> dict:
    """读取M1固定相关suite。所有本次报告必须非空且零失败、错误、跳过。"""
    rows = []
    for p in sorted((root / "common/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
        node = ET.parse(p).getroot()
        counts = {k: int(node.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")}
        name = node.get("name")
        if not name or p.name != f"TEST-{name}.xml" or counts["tests"] <= 0 or any(counts[k] for k in ("failures", "errors", "skipped")):
            raise SystemExit(f"Common JUnit未达标: {p.name}: {counts}")
        rows.append({"suite": name, **counts, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()})
    record = scope(root)
    required = set(record["baselineSuites"])
    if not baseline:
        required.update(record["requiredSuites"])
    missing = required - {r["suite"] for r in rows}
    if not rows or missing:
        raise SystemExit(f"Common缺少必测suite: {sorted(missing)}")
    extra = {r["suite"] for r in rows} - required
    if extra:
        raise SystemExit(f"Common混入非本入口报告: {sorted(extra)}")
    return {"suiteCount": len(rows), "caseCount": sum(r["tests"] for r in rows), "suites": rows}


def inspect(root: Path = ROOT) -> dict:
    """核对真实class/Binding/截图，UI顺序与连接行为仍须人工审查。"""
    before(root)
    record = scope(root)
    kotlin = root / "common/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    java = root / "common/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    paths = [kotlin / (n + ".class") for n in record["productionTypes"]]
    for name in record["bindings"]:
        candidates = [p / "com/shilapi/xcertplay/host/databinding" / (name + ".class") for p in (java, kotlin)]
        paths.append(next((p for p in candidates if p.is_file()), candidates[0]))
    missing = [p.relative_to(root).as_posix() for p in paths if not p.is_file()]
    if missing:
        raise SystemExit(f"M1缺少真实运行类/Binding: {missing}")
    sys.path.insert(0, str(ROOT / "tools/review/galaxy-client-a2-20261113"))
    from verify_client import api_surface
    old = json.loads((root / REL / "api-contract.json").read_text(encoding="utf-8"))
    for name, expected in old.items():
        actual = api_surface(kotlin, name)
        if not all(entry in actual for entry in expected):
            raise SystemExit(f"原共享组件JVM入口被删除或改签名: {name}")
    sys.path.insert(0, str(ROOT / "tools/review/galaxy-account-a1-20261113"))
    from verify_account import screenshots
    report = Path(os.environ.get("GALAXY_R0_REPORT_DIR", str(root / ".work/m1-delivery")))
    return {"businessChangedPaths": check_scope(root), "junit": junit(root),
            "classes": [{"path": p.relative_to(root).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths],
            "screenshots": screenshots(record, report / "screenshots"), "sharedComponentApiPreserved": True,
            "manualUiLifecycleAndConnectionOrderReviewRequired": True}


def main() -> None:
    """baseline只记录现有相关Common真实用例，wholeTaskReady始终false。"""
    mode = sys.argv[1] if len(sys.argv) > 1 else "before"
    if mode == "before":
        before()
    elif mode == "suites":
        record = scope()
        suites = record["baselineSuites"] + (record["requiredSuites"] if sys.argv[2] == "final" else [])
        sys.stdout.buffer.write(("\n".join(suites) + "\n").encode("ascii"))
    elif mode == "baseline":
        check_scope()
        report = Path(os.environ["GALAXY_R0_REPORT_DIR"])
        report.mkdir(parents=True, exist_ok=True)
        record = {"status": "BASELINE_ONLY", "wholeTaskReady": False, "junit": junit(baseline=True),
                  "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  "apkBuilt": False, "vehicleValidated": False}
        (report / "M1_BASELINE.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"M1工具基线: Common {record['junit']['suiteCount']}套{record['junit']['caseCount']}例；尚非业务交付。")
    else:
        raise SystemExit(f"未知M1模式: {mode}")


if __name__ == "__main__":
    main()
