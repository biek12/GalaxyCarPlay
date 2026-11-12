#!/usr/bin/env python3
"""C1固定改动集格式门槛：真实逐文件argv，拒绝空匹配；可显式格式化候选副本。"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys


def main():
    """只处理C1起点之后的Cluster Kotlin/KTS，保留固定工具和配置。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("worktree", type=Path)
    parser.add_argument("--format", action="store_true")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[3]
    worktree = args.worktree.resolve()
    paths = subprocess.check_output(
        ["git", "diff", "--name-only", "--diff-filter=ACMR", "f6e6288", "HEAD", "--", "galaxy/cluster"],
        cwd=worktree, text=True,
    ).splitlines()
    paths = [path for path in paths if Path(path).suffix in {".kt", ".kts"}]
    if not paths or not all((worktree / path).is_file() for path in paths):
        raise SystemExit("没有有效C1 Kotlin/KTS文件，拒绝空检查")
    jar = repository / ".work/tools/ktlint-1.4.1/ktlint-cli-1.4.1-all.jar"
    expected = "abff61ead7dabdf1264ae7dce61cb5325e602896f33f3ee2fbc359238adeac70"
    if hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
        raise SystemExit("ktlint固定jar摘要不符")
    base = ["java", "-jar", str(jar), "--editorconfig=format/galaxy/.editorconfig"]

    def run(formatting=False):
        """每个文件单独作为一个argv元素，不通过shell的换行/单词拆分。"""
        command = base + (["--format"] if formatting else []) + paths
        result = subprocess.run(command, cwd=worktree, capture_output=True, text=True)
        output = result.stdout + result.stderr
        print(output, end="")
        if result.returncode or "No files matched" in output:
            raise SystemExit(result.returncode or 2)

    def hashes():
        """计算实际固定文件集摘要，证明二次格式化未改变内容。"""
        return {path: hashlib.sha256((worktree / path).read_bytes()).hexdigest() for path in paths}

    print(f"C1 ktlint 1.4.1: {len(paths)} files, separate argv; format={args.format}", flush=True)
    if args.format:
        run(True)
        first = hashes()
        run(True)
        if hashes() != first:
            raise SystemExit("二次格式化仍有差异")
    before = hashes()
    run()
    if hashes() != before:
        raise SystemExit("只读格式检查改变了源码")
    print(f"PASS: {len(paths)} files checked" + ("; formatting idempotent" if args.format else ""))


if __name__ == "__main__":
    main()
