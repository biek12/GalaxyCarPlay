"""核验固定Robolectric多SDK离线缓存；显式--download才从Maven Central补包。"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[3]
PINS = ROOT / "tools/review/galaxy-settings-m1-20261117/robolectric-cache.json"


def valid(path: Path, sha512: str) -> bool:
    """真实发布包须同时满足固定摘要、合理体积和Android实现类，空ZIP不算就绪。"""
    if not path.is_file() or path.stat().st_size < 10 * 1024 * 1024:
        return False
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha512").hexdigest() != sha512:
            return False
    with zipfile.ZipFile(path) as jar:
        return all(n in jar.namelist() for n in ("android/app/Activity.class", "android/content/res/Configuration.class"))


def prepare(item: dict, folder: Path, download: bool) -> dict:
    """不覆盖失败证据：旧无效文件先留作本地备份，下载校验后才提交缓存。"""
    path = folder / item["name"]
    if path.resolve().parent != folder.resolve():
        raise ValueError("拒绝缓存目录外的目标")
    if valid(path, item["sha512"]):
        return {"name": path.name, "status": "verified", "bytes": path.stat().st_size}
    if not download:
        raise SystemExit(f"缺少/无效的真实Robolectric离线包: {path}，交Codex补齐")
    temp = path.with_suffix(".download")
    try:
        subprocess.run(["curl", "-fLsS", "--retry", "2", "--connect-timeout", "20", "--max-time", "600",
                        "-o", str(temp), item["url"]], check=True)
        if not valid(temp, item["sha512"]):
            raise ValueError(f"Robolectric包摘要/结构错误: {path.name}")
        if path.exists():
            old_sha = hashlib.sha256(path.read_bytes()).hexdigest()
            backup = path.with_name(path.name + ".invalid-" + old_sha[:12])
            if backup.exists():
                if hashlib.sha256(backup.read_bytes()).hexdigest() != old_sha:
                    raise ValueError("无效缓存备份冲突")
                path.unlink()
            else:
                path.replace(backup)
        temp.replace(path)
        return {"name": path.name, "status": "downloaded-verified", "bytes": path.stat().st_size}
    finally:
        temp.unlink(missing_ok=True)


def main() -> None:
    """下载仅由Codex准备环境时调用；正式离线入口只执行检查。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-root", type=Path, default=Path(os.environ.get("GALAXY_VERIFY_CACHE", str(ROOT / ".work"))))
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    folder = args.cache_root.resolve() / "robolectric-deps"
    folder.mkdir(parents=True, exist_ok=True)
    items = json.loads(PINS.read_text(encoding="utf-8"))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        rows = list(pool.map(lambda item: prepare(item, folder, args.download), items))
    report = args.cache_root.resolve() / "common-robolectric-cache-result.json"
    report.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    print(f"Common离线SDK缓存已核验: {len(rows)}个真实固定包；不进入APK。")


if __name__ == "__main__":
    main()
