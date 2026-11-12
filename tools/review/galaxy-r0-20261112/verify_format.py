#!/usr/bin/env python3
"""R0 固定格式检查：ktlint 1.4.1 逐文件二次幂等 + XML 语义/二次幂等 + 脚本语法。

- Kotlin/KTS：复用 tools/review/galaxy-c2a-20261112/format_check.py（--format 两遍 + check），
  清单为 format-files.txt，覆盖本批全部新增/修改的运行源与测试/KTS，不含原字节 archive。
- XML：xmllint（GALAXY_XMLLINT、/usr/bin/xmllint 或 PATH），XMLLINT_INDENT='    '，逐文件做 parse 良构、
  格式化前后语义等价、二次格式化幂等；不重写文本资源以改变句义。
- Python/Shell：py_compile / bash -n 对应语言必要验证。
结果写 VALIDATION_FORMAT.json（由 verify_projection_retirement.sh 收进证据）。
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "galaxy-c2a-20261112"))
from format_check import ktlint_jar  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
REVIEW = Path("tools/review/galaxy-r0-20261112")
XML_FILES = [
    "galaxy/cluster/src/main/AndroidManifest.xml",
    "galaxy/cluster/src/main/res/layout/activity_cluster.xml",
    "galaxy/cluster/src/main/res/layout/page_cluster_home.xml",
    "galaxy/cluster/src/main/res/values/strings.xml",
    "galaxy/service/src/main/res/layout/include_service_features.xml",
    "galaxy/service/src/main/res/layout/include_service_overview.xml",
    "galaxy/service/src/main/res/values/strings.xml",
]
PYTHON_FILES = [
    str(REVIEW / "archive_projection.py"),
    str(REVIEW / "verify_format.py"),
    str(REVIEW / "verify_artifacts.py"),
    "scripts/verify_projection_retirement.sh",
]


def sha256(data: bytes) -> str:
    """内容摘要，用于幂等比对。"""
    return hashlib.sha256(data).hexdigest()


def kotlin_gate() -> dict:
    """按固定 jar 与 editorconfig 执行 --format 两遍幂等 + 只读 check。"""
    jar = ktlint_jar(ROOT)
    expected = "abff61ead7dabdf1264ae7dce61cb5325e602896f33f3ee2fbc359238adeac70"
    actual = sha256(jar.read_bytes())
    if actual != expected:
        raise SystemExit(f"ktlint jar 摘要不符: {actual}")
    files = [line.strip() for line in (ROOT / REVIEW / "format-files.txt").read_text().splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    if len(files) != len(set(files)):
        raise SystemExit("format-files.txt 存在重复路径")
    missing = [f for f in files if not (ROOT / f).is_file()]
    if missing:
        raise SystemExit(f"格式清单缺少文件: {missing}")
    script = ROOT / "tools/review/galaxy-c2a-20261112/format_check.py"
    commands = [
        [sys.executable, str(script), str(ROOT), "--files-from", str(ROOT / REVIEW / "format-files.txt"),
         "--label", "R0", "--format"],
        [sys.executable, str(script), str(ROOT), "--files-from", str(ROOT / REVIEW / "format-files.txt"),
         "--label", "R0"],
    ]
    logs = []
    for command in commands:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
        logs.append(result.stdout + result.stderr)
        if result.returncode or "No files matched" in (result.stdout + result.stderr):
            raise SystemExit("ktlint 门槛失败:\n" + (result.stdout + result.stderr))
    return {
        "tool": "ktlint 1.4.1",
        "jarSha256": actual,
        "config": "format/galaxy/.editorconfig",
        "fileCount": len(files),
        "files": files,
        "formatTwiceIdempotent": True,
        "readOnlyCheck": True,
        "log": "\n".join(logs)[-8000:],
    }


def xmllint_bin() -> str:
    """优先显式路径，其次本机 /usr/bin/xmllint，最后 PATH。缺二进制则失败。"""
    explicit = os.environ.get("GALAXY_XMLLINT", "").strip()
    if explicit:
        return explicit
    if Path("/usr/bin/xmllint").is_file():
        return "/usr/bin/xmllint"
    return "xmllint"


def xml_gate() -> dict:
    """xmllint 良构 + 语义等价 + 二次幂等，四空格缩进，不重写源文件。"""
    env = dict(os.environ, XMLLINT_INDENT="    ")
    lint = xmllint_bin()
    details = []
    for name in XML_FILES:
        path = ROOT / name
        raw = path.read_bytes()
        # 1) 良构 parse
        ET.fromstring(raw)
        probe = subprocess.run([lint, "--noout", str(path)], cwd=ROOT,
                               capture_output=True, text=True)
        if probe.returncode:
            raise SystemExit(f"xmllint parse 失败: {name}: {probe.stderr}")
        # 2) 格式化一次/两次，比对幂等
        first = subprocess.run([lint, "--format", str(path)], cwd=ROOT, env=env,
                               capture_output=True, check=True).stdout
        second = subprocess.run([lint, "--format", "-"], cwd=ROOT, env=env,
                                input=first, capture_output=True, check=True).stdout
        if first != second:
            raise SystemExit(f"XML 二次格式化不幂等: {name}")
        # 3) 语义等价：元素 tag/属性/文本逐项比对，空白差异不影响句义
        def shape(data: bytes):
            return [(node.tag, sorted(node.attrib.items()), (node.text or "").strip())
                    for node in ET.fromstring(data).iter()]

        if shape(raw) != shape(first):
            raise SystemExit(f"XML 格式化改变语义: {name}")
        details.append({"file": name, "sha256": sha256(raw), "wellFormed": True,
                        "semanticEqual": True, "formatIdempotent": True})
    version = subprocess.run([lint, "--version"], capture_output=True, text=True).stderr.strip()
    return {"tool": version, "indent": "    ", "fileCount": len(details), "files": details}


def script_gate() -> dict:
    """Python 编译检查与 Shell 语法检查。"""
    details = []
    for name in PYTHON_FILES:
        path = ROOT / name
        if not path.is_file():
            raise SystemExit(f"缺少脚本: {name}")
        if name.endswith(".py"):
            result = subprocess.run([sys.executable, "-m", "py_compile", str(path)],
                                    cwd=ROOT, capture_output=True, text=True)
            kind = "py_compile"
        else:
            result = subprocess.run(["bash", "-n", str(path)], cwd=ROOT,
                                    capture_output=True, text=True)
            kind = "bash -n"
        if result.returncode:
            raise SystemExit(f"{kind} 失败: {name}: {result.stderr}")
        details.append({"file": name, "check": kind, "passed": True})
    return {"fileCount": len(details), "files": details}


def main() -> None:
    """执行三项格式门槛并写证据文件。"""
    record = {"kotlin": kotlin_gate(), "xml": xml_gate(), "script": script_gate()}
    report = Path(os.environ.get("GALAXY_R0_REPORT_DIR", "").strip() or (ROOT / ".work/r0-validation"))
    target = report / "format-result.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(f"格式门槛通过: Kotlin {record['kotlin']['fileCount']} / "
          f"XML {record['xml']['fileCount']} / 脚本 {record['script']['fileCount']}")


if __name__ == "__main__":
    main()
