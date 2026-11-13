"""A4任务内检查点：原格式器、真实Service编译/JUnit和ASM；不替代最终累计门槛。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def scope(root: Path = ROOT) -> dict:
    """读取Lead固定的源码基线、阶段文件和代码白名单。"""
    return json.loads((root / HERE.relative_to(ROOT) / "scope.json").read_text(encoding="utf-8"))


def stages_through(name: str, record: dict) -> list[dict]:
    """baseline不要求未来代码；其他阶段累计要求已完成部分。"""
    if name == "baseline":
        return []
    names = [stage["name"] for stage in record["phases"]]
    if name not in names:
        raise SystemExit(f"未知A4检查点: {name}")
    return record["phases"][:names.index(name) + 1]


def check_scope(record: dict, root: Path = ROOT) -> list[str]:
    """从固定交接提交检查真实Git差异；源码来源仍保留独立的sourceBaseline。"""
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", record["scopeBaseline"], "--", "galaxy"],
        cwd=root, text=True, encoding="utf-8",
    ).splitlines()
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "galaxy"],
        cwd=root, text=True, encoding="utf-8",
    ).splitlines()
    files = sorted(set(changed + untracked))
    outside = sorted(set(files) - set(record["writeWhitelist"]))
    if outside:
        raise SystemExit(f"A4业务代码越界: {outside}")
    return files


def format_files(name: str, record: dict, root: Path = ROOT) -> list[str]:
    """阶段必须文件严格存在；尚未实施的后续文件不妨碍当前检查。"""
    baseline = (root / HERE.relative_to(ROOT) / "baseline-format-files.txt").read_text(encoding="utf-8").splitlines()
    required = set(baseline)
    deleted = set()
    for phase in stages_through(name, record):
        required.update(phase["files"])
        deleted.update(phase["deleted"])
    required.difference_update(deleted)
    missing = sorted(path for path in required if not (root / path).is_file())
    leftovers = sorted(path for path in deleted if (root / path).exists())
    if missing or leftovers:
        raise SystemExit(f"A4阶段文件缺失={missing}; 已迁旧路径残留={leftovers}")
    # 后续阶段的现有遗留文件不提前重写；到所属阶段及最终累计门槛再完整检查。
    return sorted(required)


def inspect_junit(name: str, record: dict, root: Path = ROOT) -> dict:
    """直接解析本轮真实JUnit；额外套件同样要求非空且零失败/错误/跳过。"""
    required = set(record["baselineServiceSuites"])
    for phase in stages_through(name, record):
        required.update("com.ecarx.autoservice." + suite.replace("/", ".") for suite in phase["testTypes"])
    folder = root / "galaxy/service/build/test-results/testDebugUnitTest"
    rows = []
    for path in sorted(folder.glob("TEST-*.xml")):
        node = ET.parse(path).getroot()
        counts = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        suite = node.get("name")
        if not suite or path.name != f"TEST-{suite}.xml" or counts["tests"] <= 0 or any(counts[k] for k in ("failures", "errors", "skipped")):
            raise SystemExit(f"A4检查点JUnit未达标: {path.name}: {counts}")
        rows.append({"suite": suite, **counts, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    missing = sorted(required - {row["suite"] for row in rows})
    if not rows or missing:
        raise SystemExit(f"A4检查点缺少必测套件: {missing}")
    return {"suites": rows, "suiteCount": len(rows), "totalCases": sum(row["tests"] for row in rows)}


def prepare(name: str, record: dict, report: Path) -> None:
    """执行原固定格式器，并隔离本树Service旧class/JUnit，绝不清共享缓存。"""
    check_scope(record)
    files = format_files(name, record)
    report.mkdir(parents=True, exist_ok=True)
    listing = report / "checkpoint-format-files.txt"
    listing.write_text("\n".join(files) + "\n", encoding="utf-8")
    subprocess.run(
        [sys.executable, str(ROOT / "tools/review/galaxy-c2a-20261112/format_check.py"), str(ROOT),
         "--files-from", str(listing), "--label", f"A4-{name}", "--format"], cwd=ROOT, check=True,
    )
    build = (ROOT / "galaxy/service/build").resolve()
    if not build.is_relative_to(ROOT.resolve()):
        raise SystemExit("拒绝隔离工作树之外的Service输出")
    stamp = time.time_ns()
    for relative in ("intermediates/built_in_kotlinc", "test-results"):
        path = build / relative
        if path.is_dir():
            if not path.resolve().is_relative_to(build):
                raise SystemExit(f"拒绝隔离外部输出: {path}")
            path.rename(path.with_name(f"{path.name}.stale-{stamp}"))


def finish(name: str, record: dict, report: Path) -> None:
    """绑定检查点实际输出；此状态不能当作A4整体READY_FOR_REVIEW。"""
    check_scope(record)
    files = format_files(name, record)
    sys.path.insert(0, str(ROOT / "tools/review/galaxy-platform-p1-20261113"))
    from verify_kadb_transform import inspect_artifacts
    data = {"task": record["task"], "checkpoint": name, "status": "CHECKPOINT_PASSED",
            "wholeTaskReady": False, "junit": inspect_junit(name, record), "kadb": inspect_artifacts(ROOT),
            "sourceHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "worktreeStatus": subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True),
            "formattedFiles": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in files},
            "nativeExclusions": record["nativeExclusions"], "apkBuilt": False, "vehicleValidated": False}
    completed = stages_through(name, record)
    types = [kind for phase in completed for kind in phase["productionTypes"]]
    if completed:
        types += ["media/" + kind for kind in ("KanziMediaPublisher", "OemArtworkWriter", "ArtworkProvider", "NativeMediaSource", "MediaAccessListener")]
        from completion import artwork_provider
        data["artworkProvider"] = artwork_provider(ROOT)
    classes = ROOT / "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    missing = [kind for kind in types if not (classes / f"com/ecarx/autoservice/{kind}.class").is_file()]
    if missing:
        raise SystemExit(f"A4检查点缺少真实运行类: {missing}")
    data["checkedProductionTypes"] = types
    (report / "CHECKPOINT.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"A4 {name}检查点通过: Service {data['junit']['suiteCount']}套 {data['junit']['totalCases']}例；尚非整体交付。")


def main() -> None:
    """由同一Shell门槛在真实Gradle前后调用，环境和任务参数不复制成第二套。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("before", "after"))
    parser.add_argument("checkpoint", choices=("baseline", "C1", "C2", "C3", "C4"))
    args = parser.parse_args()
    report = Path(os.environ.get("GALAXY_R0_REPORT_DIR", str(ROOT / ".work/a4-checkpoint")))
    if args.operation == "before":
        prepare(args.checkpoint, scope(), report)
    else:
        finish(args.checkpoint, scope(), report)


if __name__ == "__main__":
    main()
