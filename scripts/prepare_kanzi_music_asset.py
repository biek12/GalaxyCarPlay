#!/usr/bin/env python3
"""只封装自有组件SO及公开合同；原厂HMI修改候选不得进入APK。"""
import argparse, hashlib, json, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output", type=Path, required=True)
    a = ap.parse_args()
    report = json.loads((ROOT / ".work/kanzi-music/build.json").read_text())
    for path, expected in report["sourceSha256"].items():
        if sha(ROOT / path) != expected:
            raise ValueError("组件源码已变化，先重新构建候选: " + path)
    binary = ROOT / ".work/kanzi-music/libgalaxy_kanzi_music.so"
    if sha(binary) != report["artifacts"][binary.name]["sha256"]:
        raise ValueError("自有组件摘要不符")
    a.output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(binary, a.output / binary.name)
    profile = json.loads(
        (ROOT / "galaxy/common/src/main/cpp/kanzi_music/abi_profile.json").read_text()
    )
    (a.output / "component.json").write_text(
        json.dumps(
            {
                "schema": 1,
                "elfBaseline": report["originalSha256"],
                "wire": profile["wire"],
                "artifact": report["artifacts"][binary.name],
                "fieldValidated": False,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    main()
