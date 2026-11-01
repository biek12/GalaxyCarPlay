#!/usr/bin/env python3
"""只读核对测试包文件摘要；不连接设备、不执行ELF或APK。"""
from pathlib import Path
import hashlib
import json
import sys


def verify(root):
    root = Path(root).resolve()
    manifest = json.loads((root / "manifest.json").read_text())
    for name, expected in manifest["files"].items():
        path = root / name
        if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file():
            raise ValueError(f"包文件不存在或路径无效: {name}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected["sha256"]:
            raise ValueError(f"摘要不符: {name}")
    print(f"通过: {len(manifest['files'])}个文件摘要；QNX SDK正式编译={manifest['qnx_sdk_build_complete']}；现场证据={bool(manifest.get('target_validation'))}。")


if __name__ == "__main__":
    try:
        verify(sys.argv[1] if len(sys.argv) == 2 else Path(__file__).resolve().parent)
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit(str(error)) from error
