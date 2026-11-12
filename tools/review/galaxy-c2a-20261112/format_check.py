#!/usr/bin/env python3
"""固定批次文件集格式检查：默认C2a，后续批次可提供Lead固定清单。"""
import argparse
import hashlib
from pathlib import Path
import subprocess


FILES = [
    "galaxy/cluster/src/main/java/com/galaxy/autocluster/ThemeActivity.kt",
    "galaxy/cluster/src/main/java/com/galaxy/autocluster/ui/ThemeLibraryPage.kt",
    "galaxy/cluster/src/main/java/com/galaxy/autocluster/ui/ThemeLibraryAdapter.kt",
    "galaxy/cluster/src/main/java/com/galaxy/autocluster/ui/ThemeRatioImageView.kt",
    "galaxy/cluster/src/test/java/com/galaxy/autocluster/ui/ThemeLibraryPageTest.kt",
    "galaxy/cluster/src/test/java/com/galaxy/autocluster/ThemeShellBehaviorTest.kt",
]


def main():
    """只操作本批固定六文件，不触已经通过的C1源码。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("worktree", type=Path)
    parser.add_argument("--format", action="store_true")
    parser.add_argument("--files-from", type=Path)
    parser.add_argument("--label", default="C2a")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[3]
    worktree = args.worktree.resolve()
    files = FILES if args.files_from is None else [
        line.strip() for line in args.files_from.read_text().splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if not files or len(set(files)) != len(files):
        raise SystemExit("文件清单为空或重复，拒绝无效检查")
    missing = [path for path in files if not (worktree / path).is_file()]
    if missing:
        raise SystemExit(f"缺少{args.label}必交文件，拒绝空检查: {missing}")
    # 本副本不复制缓存；工具始终来自产品根中已核验的同一jar。
    jar = Path("/Users/anjon/Documents/GalaxyPlay/.work/tools/ktlint-1.4.1/ktlint-cli-1.4.1-all.jar")
    expected = "abff61ead7dabdf1264ae7dce61cb5325e602896f33f3ee2fbc359238adeac70"
    if hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
        raise SystemExit("ktlint固定jar摘要不符")
    config = repository / "format/galaxy/.editorconfig"
    base = ["java", "-jar", str(jar), f"--editorconfig={config}"]

    def run(formatting=False):
        """每文件独立argv，不经shell换行拆分，并拒绝零匹配。"""
        command = base + (["--format"] if formatting else []) + files
        result = subprocess.run(command, cwd=worktree, capture_output=True, text=True)
        output = result.stdout + result.stderr
        print(output, end="")
        if result.returncode or "No files matched" in output:
            raise SystemExit(result.returncode or 2)

    def hashes():
        """固定文件摘要用于幂等和只读检查，不包含私有材料。"""
        return {path: hashlib.sha256((worktree / path).read_bytes()).hexdigest() for path in files}

    print(f"{args.label} ktlint 1.4.1: {len(files)} files, separate argv; format={args.format}", flush=True)
    if args.format:
        run(True)
        first = hashes()
        run(True)
        if hashes() != first:
            raise SystemExit("二次格式化仍有差异")
    before = hashes()
    run()
    if hashes() != before:
        raise SystemExit("只读检查改变了源码")
    print(f"PASS: {len(files)} files checked" + ("; formatting idempotent" if args.format else ""))


if __name__ == "__main__":
    main()
