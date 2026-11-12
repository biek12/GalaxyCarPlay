#!/usr/bin/env python3
"""R0 归档：把旧整屏覆盖投屏专用源码原字节移到 Gradle 源集之外。

清单固定来自 R0-整屏投屏归档任务书 §5，不自行增删运行版本文件。
混合职责文件另存同源原字节快照到 snapshots/，运行版本由编码按 §3 收口。
输出 manifest.json 供验证脚本逐项核对：源提交 blob / 归档文件 / SHA256。
"""
from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ARCHIVE_ROOT = Path("archive/legacy-fullscreen-projection/20261112")
SOURCE_BASELINE = "572ba0eb07113c8c1859efa9c34cb25a1676eff7"

SERVICE_MAIN = "galaxy/service/src/main/java/com/ecarx/autoservice"
SERVICE_TEST = "galaxy/service/src/test/java/com/ecarx/autoservice"
SERVICE_ANDROID_TEST = "galaxy/service/src/androidTest/java/com/ecarx/autoservice"
CLUSTER_MAIN = "galaxy/cluster/src/main/java/com/galaxy/autocluster"
CLUSTER_LAYOUT = "galaxy/cluster/src/main/res/layout"
CLUSTER_TEST = "galaxy/cluster/src/test/java/com/galaxy/autocluster"

# 旧整屏覆盖投屏专用源码：整目录/整文件归档，运行版本不再保留这些类。
MOVED: list[str] = [
    *[f"{SERVICE_MAIN}/projection/{name}.kt" for name in (
        "GalaxyEglContext", "GalaxyFrameTimings", "GalaxyGlComposition",
        "GalaxyHabComposition", "GalaxyHabFrames", "GalaxyHabFrameWake",
        "GalaxyHabKey", "GalaxyHabLayout", "GalaxyHabProcess",
        "GalaxyHabReconnect", "GalaxyHabSessionStart", "GalaxyTextureInput",
        "GalaxyTextureProgram",
    )],
    *[f"{SERVICE_MAIN}/platform/{name}.kt" for name in (
        "RootHabSession", "RootHabProcess", "QnxReceiverDeployer",
        "QnxReceiverRemote", "QnxReceiverLifecycle",
    )],
    *[f"{SERVICE_TEST}/projection/{name}.kt" for name in (
        "GalaxyFrameTimingsTest", "GalaxyHabFramesTest", "GalaxyHabFrameWakeTest",
        "GalaxyHabProcessTest", "GalaxyHabReconnectTest", "GalaxyHabSessionStartTest",
    )],
    f"{SERVICE_TEST}/platform/QnxReceiverRemoteTest.kt",
    f"{SERVICE_TEST}/platform/QnxReceiverLifecycleTest.kt",
    f"{SERVICE_ANDROID_TEST}/projection/ProjectionSmokeInstrumentation.kt",
    f"{CLUSTER_MAIN}/ThemeActivity.kt",
    f"{CLUSTER_MAIN}/runtime/ClusterSceneOutput.kt",
    *[f"{CLUSTER_MAIN}/ui/{name}.kt" for name in (
        "ClusterEditorCanvas", "ClusterEditorPage", "ComponentGallery",
        "ComponentPresets", "ComponentPropertyPanel", "ComponentSampleView",
        "ThemeActions", "ThemeAppearanceDialogs", "ThemeLibraryPage",
        "ThemeLibraryAdapter", "ThemeRatioImageView", "ThemePreviewImages",
    )],
    *[f"{CLUSTER_LAYOUT}/{name}.xml" for name in (
        "activity_theme", "header_theme_library", "item_theme_library_row",
        "item_theme_library_card", "page_cluster_themes",
    )],
    f"{CLUSTER_TEST}/ThemeShellBehaviorTest.kt",
    f"{CLUSTER_TEST}/ui/ThemeLibraryPageTest.kt",
    f"{CLUSTER_TEST}/ui/ComponentUiTest.kt",
    f"{CLUSTER_TEST}/ui/ComponentCatalogTest.kt",
]

# 混合职责文件：只存原字节快照，运行版本保留并按 §3 收口，避免重复类。
SNAPSHOTS: list[str] = [
    f"{SERVICE_MAIN}/AutoService.kt",
    f"{SERVICE_MAIN}/platform/PlatformCoordinator.kt",
    f"{CLUSTER_MAIN}/ClusterRenderService.kt",
]


def git_blob(path: str) -> bytes:
    """读取固定起点提交里的原字节，不依赖当前工作树状态。"""
    return subprocess.check_output(
        ["git", "show", f"{SOURCE_BASELINE}:{path}"], cwd=ROOT
    )


def sha256(data: bytes) -> str:
    """计算内容 SHA256，用于逐文件三方核对。"""
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    """执行归档并写清单；文件不存在或字节不符立即失败，不静默跳过。"""
    if len(set(MOVED)) != len(MOVED) or len(set(SNAPSHOTS)) != len(SNAPSHOTS):
        raise SystemExit("归档清单重复")
    entries: list[dict[str, str | int | bool]] = []
    for kind, paths in (("moved", MOVED), ("snapshot", SNAPSHOTS)):
        for original in paths:
            source = ROOT / original
            blob = git_blob(original)
            if kind == "snapshot":
                archived = str(ARCHIVE_ROOT / "snapshots" / original)
            else:
                archived = str(ARCHIVE_ROOT / original)
            target = ROOT / archived
            # 幂等续跑：已归档且字节一致时不再要求源文件仍存在。
            if source.is_file():
                if source.read_bytes() != blob:
                    raise SystemExit(f"起点源码与提交 blob 不一致: {original}")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(blob)
                if kind == "moved":
                    source.unlink()
            elif not (target.is_file() and target.read_bytes() == blob):
                raise SystemExit(f"源文件与归档均缺失或字节不符: {original}")
            entries.append({
                "kind": kind,
                "original": original,
                "archived": archived,
                "sha256": sha256(blob),
                "bytes": len(blob),
                "snapshot": kind == "snapshot",
            })
    manifest = {
        "task": "GALAXY-ANDROID-R0-20261112",
        "sourceBaseline": SOURCE_BASELINE,
        "archiveRoot": str(ARCHIVE_ROOT),
        "movedCount": len(MOVED),
        "snapshotCount": len(SNAPSHOTS),
        "entries": entries,
    }
    (ROOT / ARCHIVE_ROOT / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )
    print(f"归档 {len(MOVED)} 文件 + {len(SNAPSHOTS)} 快照，摘要已写 manifest.json")


if __name__ == "__main__":
    main()
