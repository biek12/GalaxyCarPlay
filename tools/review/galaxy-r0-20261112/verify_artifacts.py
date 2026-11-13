#!/usr/bin/env python3
"""R0 真实产物/归档/JUnit/摘要核对（由 verify_projection_retirement.sh 调用）。

只以本轮实际产物为依据：AGP merged manifest、Kotlin class 输出、JUnit XML、
归档文件与基线 git blob、最终源码 SHA256。源码 rg 仅用于共享调用闭包说明，
不冒充编译或运行时行为证据。
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ARCHIVE_ROOT = Path("archive/legacy-fullscreen-projection/20261112")
THEME_ARCHIVE_ROOT = Path("archive/theme-engine/20261112")
THEME_BASELINE = "92cf470af06f4999256f7c83502aa1edd56b7e44"
PREVIEW_CLASSES = [
    "com/galaxy/autocluster/preview/SceneData.class",
    "com/galaxy/autocluster/preview/SceneMedia.class",
    "com/galaxy/autocluster/preview/SceneSignal.class",
]
REPORT = Path(os.environ.get("GALAXY_R0_REPORT_DIR", "").strip() or (ROOT / ".work/r0-validation"))

# 已退役、不得出现在本轮 Kotlin class 输出中的旧专用类。
FORBIDDEN_CLASSES = [
    "ThemeActivity", "ClusterSceneOutput", "ClusterEditorCanvas", "ClusterEditorPage",
    "ComponentGallery", "ComponentPresets", "ComponentPropertyPanel", "ComponentSampleView",
    "ThemeActions", "ThemeAppearanceDialogs", "ThemeLibraryPage", "ThemeLibraryAdapter",
    "ThemeRatioImageView", "ThemePreviewImages",
    "GalaxyEglContext", "GalaxyFrameTimings", "GalaxyGlComposition", "GalaxyHabComposition",
    "GalaxyHabFrames", "GalaxyHabFrameWake", "GalaxyHabKey", "GalaxyHabLayout",
    "GalaxyHabProcess", "GalaxyHabReconnect", "GalaxyHabSessionStart",
    "GalaxyTextureInput", "GalaxyTextureProgram",
    "RootHabSession", "RootHabProcess", "QnxReceiverDeployer", "QnxReceiverRemote",
    "QnxReceiverLifecycle", "ProjectionSmokeInstrumentation",
]
# 必须存在的新运行类与共享保留类。
REQUIRED_CLASSES = [
    "com/ecarx/autoservice/platform/root/RootProjectionArchive.class",
    "com/ecarx/autoservice/platform/qnx/QnxReceiverCleanup.class",
    "com/ecarx/autoservice/platform/hook/PosixChecksum.class",
    "com/ecarx/autoservice/AutoService.class",
    "com/ecarx/autoservice/platform/root/RootServiceMain.class",
    "com/ecarx/autoservice/platform/foundation/PlatformCoordinator.class",
    "com/ecarx/autoservice/platform/hook/QnxClusterHook.class",
    "com/galaxy/autocluster/ClusterRenderService.class",
    "com/galaxy/autocluster/ui/InstrumentMusicPreview.class",
    "com/ecarx/autoservice/platform/adb/AdbCrypto.class",
    "com/ecarx/autoservice/platform/root/RootOperation.class",
    "com/ecarx/autoservice/platform/hook/HookLease.class",
    "com/ecarx/autoservice/platform/hook/HookMaterials.class",
    "com/ecarx/autoservice/platform/hook/QnxClusterRemote.class",
    "com/ecarx/autoservice/ipc/ClientRegistry.class",
    "com/ecarx/autoservice/ipc/ServiceCallerIdentity.class",
    "com/ecarx/autoservice/ipc/ServiceRequestQueue.class",
    "com/ecarx/autoservice/ipc/ServiceSnapshots.class",
    "com/ecarx/autoservice/vehicle/VehicleFeedDispatcher.class",
    *PREVIEW_CLASSES,
]
# 本批必测：JUnit suite 必须真实存在、非空、零失败/错误/跳过。
REQUIRED_SUITES = {
    "cluster": [
        "com.galaxy.autocluster.ClusterAppNavigationTest",
        "com.galaxy.autocluster.ClusterPageBehaviorTest",
        "com.galaxy.autocluster.LegacyProjectionRetirementTest",
    ],
    "service": [
        "com.ecarx.autoservice.platform.root.RootProjectionArchiveTest",
        "com.ecarx.autoservice.platform.root.PlatformExecutionTest",
        "com.ecarx.autoservice.ui.ServiceHomeBehaviorTest",
        "com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest",
        "com.ecarx.autoservice.oem.ClusterElementPipelineTest",
        "com.ecarx.autoservice.platform.hook.ClusterHookLifecycleTest",
        "com.ecarx.autoservice.ipc.ClientRegistryTest",
        "com.ecarx.autoservice.ipc.ServiceCallerIdentityTest",
        "com.ecarx.autoservice.ipc.ServiceRequestQueueTest",
        "com.ecarx.autoservice.ipc.ServiceSnapshotsTest",
        "com.ecarx.autoservice.vehicle.VehicleFeedDispatcherTest",
        "com.ecarx.autoservice.platform.foundation.FoundationRecoveryTest",
        "com.ecarx.autoservice.remote.RemoteConnectionStatusTest",
        "com.ecarx.autoservice.platform.root.RootFramesTest",
        "com.ecarx.autoservice.platform.hook.HookLeaseTest",
        "com.ecarx.autoservice.platform.hook.HookMaterialsTest",
    ],
}
# 归档但保留原证据的测试套件（不参与本轮活跃用例数）。
ARCHIVED_SUITES = [
    "com.galaxy.autocluster.ThemeShellBehaviorTest",
    "com.galaxy.autocluster.ui.ThemeLibraryPageTest",
    "com.galaxy.autocluster.ui.ComponentUiTest",
    "com.galaxy.autocluster.ui.ComponentCatalogTest",
    "com.ecarx.autoservice.projection.GalaxyHabFramesTest",
    "com.ecarx.autoservice.platform.QnxReceiverRemoteTest",
    "com.ecarx.autoservice.platform.QnxReceiverLifecycleTest",
    *[f"com.galaxy.autocluster.theme.{name}" for name in (
        "ClusterSecondRevisionTest", "ClusterVisualRefinementTest", "EditHistoryTest",
        "EditorSessionTest", "SceneAnimationTest", "SceneRuntimeTest",
        "ThemePackageCodecTest", "ThemeStoreTest",
    )],
]


def sha256(data: bytes) -> str:
    """内容 SHA256。"""
    return hashlib.sha256(data).hexdigest()


def rel_posix(path: Path, root: Path = ROOT) -> str:
    """产物和源码相对路径统一为 POSIX，避免 Windows 反斜线改变门槛语义。"""
    return path.relative_to(root).as_posix()


def read_text(path: Path) -> str:
    """按 UTF-8 读取文本。Windows 默认代码页不能用来解释这些源文件。"""
    return path.read_text(encoding="utf-8")


def strip_kts_comments(text: str) -> str:
    """去掉 KTS 行注释和块注释，保留字符串字面量。

    说明文字里的退役词不能当成可执行配置；字符串中的真实路径和声明仍然保留。
    """
    out: list[str] = []
    index = 0
    length = len(text)
    while index < length:
        if text.startswith('"""', index) or text.startswith("'''", index):
            quote = text[index:index + 3]
            end = text.find(quote, index + 3)
            if end < 0:
                out.append(text[index:])
                break
            out.append(text[index:end + 3])
            index = end + 3
            continue
        character = text[index]
        if character in "\"'":
            cursor = index + 1
            while cursor < length:
                if text[cursor] == "\\":
                    cursor += 2
                    continue
                if text[cursor] == character:
                    cursor += 1
                    break
                cursor += 1
            out.append(text[index:cursor])
            index = cursor
            continue
        if text.startswith("//", index):
            newline = text.find("\n", index)
            if newline < 0:
                break
            out.append("\n")
            index = newline + 1
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            if end < 0:
                break
            out.append("\n")
            index = end + 2
            continue
        out.append(character)
        index += 1
    return "".join(out)


def git_blob(path: str) -> bytes:
    """读取固定起点提交的原字节。"""
    return subprocess.check_output(
        ["git", "show", f"572ba0eb07113c8c1859efa9c34cb25a1676eff7:{path}"],
        cwd=ROOT,
    )


def archive_gate() -> dict:
    """逐项三方核对 git blob / 归档文件 / manifest.json，并确认原路径不在活跃源集。"""
    manifest = json.loads(read_text(ROOT / ARCHIVE_ROOT / "manifest.json"))
    if manifest["sourceBaseline"] != "572ba0eb07113c8c1859efa9c34cb25a1676eff7":
        raise SystemExit("归档 sourceBaseline 不符")
    checked = []
    for entry in manifest["entries"]:
        blob = git_blob(entry["original"])
        archived = (ROOT / entry["archived"]).read_bytes()
        if sha256(blob) != entry["sha256"] or sha256(archived) != entry["sha256"]:
            raise SystemExit(f"归档摘要不一致: {entry['original']}")
        if len(archived) != entry["bytes"]:
            raise SystemExit(f"归档字节数不一致: {entry['original']}")
        runtime_path = {
            "galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformCoordinator.kt":
                "galaxy/service/src/main/java/com/ecarx/autoservice/platform/foundation/PlatformCoordinator.kt",
        }.get(entry["original"], entry["original"])
        active = ROOT / runtime_path
        if entry["kind"] == "moved":
            if active.exists():
                raise SystemExit(f"归档文件仍在活跃源集: {entry['original']}")
            if not any(part in {"main", "test", "androidTest"} for part in Path(entry["original"]).parts):
                raise SystemExit(f"归档原路径不在源集目录: {entry['original']}")
        else:
            # snapshot：运行版本允许同时存在且已按 §3 修改，不能与快照字节相同。
            if not active.is_file():
                raise SystemExit(f"snapshot 运行版本缺失: {entry['original']}")
            if active.read_bytes() == blob:
                raise SystemExit(f"snapshot 运行版本未按任务书收口: {entry['original']}")
        checked.append({"original": entry["original"], "archived": entry["archived"],
                        "kind": entry["kind"], "sha256": entry["sha256"]})
    return {
        "sourceBaseline": manifest["sourceBaseline"],
        "movedCount": manifest["movedCount"],
        "snapshotCount": manifest["snapshotCount"],
        "checkedCount": len(checked),
        "entries": checked,
    }


def theme_archive_gate(root: Path | None = None) -> dict:
    """核对已授权归档的完整文件集和原字节；fixture 可替换磁盘树，Git 来源始终固定。"""
    root = root or ROOT
    manifest = json.loads(read_text(root / THEME_ARCHIVE_ROOT / "manifest.json"))
    if manifest.get("sourceBaseline") != THEME_BASELINE or manifest.get("movedCount") != 44:
        raise SystemExit("主题归档基线或文件数不符")
    source_paths = [
        "galaxy/cluster/src/main/java/com/galaxy/autocluster/theme",
        "galaxy/cluster/src/main/java/com/galaxy/autocluster/runtime/ScenePreparation.kt",
        "galaxy/cluster/src/test/java/com/galaxy/autocluster/theme",
    ]
    baseline_paths = subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", THEME_BASELINE, "--", *source_paths],
        cwd=ROOT, text=True, encoding="utf-8",
    ).splitlines()
    expected = set(baseline_paths) - {
        "galaxy/cluster/src/main/java/com/galaxy/autocluster/theme/SceneData.kt",
    }
    entries = manifest["entries"]
    originals = [entry["original"] for entry in entries]
    if len(expected) != 44 or len(originals) != 44 or set(originals) != expected:
        raise SystemExit("主题归档清单与固定基线文件集不符")
    checked = []
    for entry in entries:
        original = entry["original"]
        archived_path = THEME_ARCHIVE_ROOT / original
        if entry["archived"] != archived_path.as_posix():
            raise SystemExit(f"主题归档目标路径不符: {original}")
        archived = (root / archived_path).read_bytes()
        blob = subprocess.check_output(["git", "show", f"{THEME_BASELINE}:{original}"], cwd=ROOT)
        if archived != blob or sha256(blob) != entry["sha256"] or len(blob) != entry["bytes"]:
            raise SystemExit(f"主题归档原字节不一致: {original}")
        if (root / original).exists():
            raise SystemExit(f"主题归档原路径仍在活跃源集: {original}")
        checked.append({"original": original, "archived": entry["archived"], "sha256": entry["sha256"]})
    expected_files = {entry["archived"] for entry in entries} | {
        (THEME_ARCHIVE_ROOT / name).as_posix() for name in ("manifest.json", "README.md")
    }
    actual_files = {rel_posix(path, root) for path in (root / THEME_ARCHIVE_ROOT).rglob("*") if path.is_file()}
    if actual_files != expected_files:
        raise SystemExit("主题归档目录存在清单外文件或缺项")
    return {"sourceBaseline": THEME_BASELINE, "checkedCount": len(checked), "entries": checked}


def manifest_gate() -> dict:
    """本轮 AGP merged manifest 中不得有 ThemeActivity 组件。"""
    files = []
    # 只认本轮 AGP 精确产物路径；不 glob，避免读到被隔离的 .stale-* 旧产物。
    exact = [
        "galaxy/cluster/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        "galaxy/cluster/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
        "galaxy/service/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        "galaxy/service/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
    ]
    for rel in exact:
        path = ROOT / rel
        if not path.is_file():
            raise SystemExit(f"缺少本轮 merged manifest: {rel}")
        text = read_text(path)
        if "ThemeActivity" in text:
            raise SystemExit(f"merged manifest 仍含 ThemeActivity: {rel}")
        files.append({"path": rel, "sha256": sha256(path.read_bytes()),
                      "themeActivityPresent": False})
    cluster_manifest = read_text(ROOT / "galaxy/cluster/src/main/AndroidManifest.xml")
    if "ThemeActivity" in cluster_manifest:
        raise SystemExit("cluster 源 Manifest 仍声明 ThemeActivity")
    return {"fileCount": len(files), "files": files, "themeActivityPresent": False}


def classes_gate(root: Path | None = None) -> dict:
    """本轮 Kotlin class 输出：无旧专用输出类，新运行类与共享类齐全。"""
    root = root or ROOT
    roots = [
        root / "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        root / "galaxy/cluster/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    ]
    for directory in roots:
        if not directory.is_dir():
            raise SystemExit(f"缺少本轮 Kotlin class 输出: {directory}")
    all_classes = sorted(path.relative_to(directory).as_posix() for directory in roots for path in directory.rglob("*.class"))
    if not all_classes:
        raise SystemExit("本轮 class 输出为空")
    forbidden_hits = []
    for name in FORBIDDEN_CLASSES:
        for item in all_classes:
            leaf = item.rsplit("/", 1)[-1]
            if leaf == f"{name}.class" or leaf.startswith(f"{name}$"):
                forbidden_hits.append(item)
    for item in all_classes:
        if (item == "com/ecarx/autoservice/ClientRegistry.class" or
                item.startswith("com/ecarx/autoservice/ClientRegistry$")):
            raise SystemExit(f"本轮产物仍含迁移前的根包租约类: {item}")
        if (item.startswith("com/galaxy/autocluster/theme/") or
                item == "com/galaxy/autocluster/runtime/ScenePreparation.class" or
                item.startswith("com/galaxy/autocluster/runtime/ScenePreparation$")):
            forbidden_hits.append(item)
    if forbidden_hits:
        raise SystemExit(f"本轮产物仍含已归档类: {forbidden_hits[:10]}")
    missing = [c for c in REQUIRED_CLASSES if not any((root / c).is_file() for root in roots)]
    if missing:
        raise SystemExit(f"缺少运行类产物: {missing}")
    missing_preview = [name for name in PREVIEW_CLASSES if not (roots[1] / name).is_file()]
    if missing_preview:
        raise SystemExit(f"Cluster 缺少共享预览模型: {missing_preview}")
    return {
        "classRoots": [rel_posix(directory, root) for directory in roots],
        "classCount": len(all_classes),
        "forbiddenChecked": len(FORBIDDEN_CLASSES),
        "forbiddenHits": forbidden_hits,
        "requiredChecked": len(REQUIRED_CLASSES),
        "themeNamespaceAbsent": True,
        "previewModelsInCluster": PREVIEW_CLASSES,
    }


def junit_gate(root: Path | None = None) -> dict:
    """精确 suite 清单：真实 JUnit XML 非空、零失败/错误/跳过，零匹配即失败。"""
    root = root or ROOT
    results = {}
    observed = []
    for module, suites in REQUIRED_SUITES.items():
        directory = root / f"galaxy/{module}/build/test-results/testDebugUnitTest"
        for path in sorted(directory.glob("TEST-*.xml")):
            node = ET.parse(path).getroot()
            counts = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            if counts["tests"] <= 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
                raise SystemExit(f"本轮实际 suite 未达标: {path.name}: {counts}")
            if node.get("name") in ARCHIVED_SUITES:
                raise SystemExit(f"归档测试仍在本轮活跃报告: {node.get('name')}")
            observed.append({"suite": node.get("name"), **counts, "report": rel_posix(path, root)})
        parsed = []
        for suite in suites:
            path = directory / f"TEST-{suite}.xml"
            if not path.is_file():
                raise SystemExit(f"必测 suite 零匹配（缺少真实 JUnit 报告）: {suite}")
            node = ET.parse(path).getroot()
            counts = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            if counts["tests"] <= 0 or any(counts[k] for k in ("failures", "errors", "skipped")):
                raise SystemExit(f"必测 suite 未达标: {suite}: {counts}")
            if node.get("name") != suite:
                raise SystemExit(f"JUnit 文件与 suite 名称不符: {path.name}")
            parsed.append({"suite": suite, **counts, "report": rel_posix(path, root),
                           "sha256": sha256(path.read_bytes())})
        # 归档套件不得作为本轮活跃报告出现（历史证据只在 archive 中）。
        active_archived = [s for s in ARCHIVED_SUITES if (directory / f"TEST-{s}.xml").is_file()]
        if active_archived:
            raise SystemExit(f"归档测试仍在本轮活跃报告: {active_archived}")
        results[module] = parsed
    all_suites = results["cluster"] + results["service"]
    return {
        "requiredSuiteCount": len(all_suites),
        "totalCases": sum(item["tests"] for item in all_suites),
        "failures": sum(item["failures"] for item in all_suites),
        "errors": sum(item["errors"] for item in all_suites),
        "skipped": sum(item["skipped"] for item in all_suites),
        "suites": all_suites,
        "archivedSuitesKeptAsEvidenceOnly": ARCHIVED_SUITES,
        "observedSuites": observed,
    }


def closure_gate(root: Path | None = None) -> dict:
    """共享调用闭包与 Gradle 退役依据（源码级审计，不冒充 QNX 运行）。"""
    root = root or ROOT

    def read(path: str) -> str:
        return read_text(root / path)

    hook = read("galaxy/service/src/main/java/com/ecarx/autoservice/platform/hook/QnxClusterHook.kt")
    materials = read("galaxy/service/src/main/java/com/ecarx/autoservice/platform/hook/HookMaterials.kt")
    remote = read("galaxy/service/src/main/java/com/ecarx/autoservice/platform/hook/QnxClusterRemote.kt")
    for token, owner in (("receiver-candidate", materials), ("hook-health", remote),
                         ("PosixChecksum.calculate", remote), ("HookMaterials.read", hook),
                         ("HookMaterials.version", hook), ("lease.active", hook)):
        if token not in owner:
            raise SystemExit(f"共享 health/租约调用链缺失: {token}")
    root_main = read("galaxy/service/src/main/java/com/ecarx/autoservice/platform/root/RootServiceMain.kt")
    for token in ("RootProjectionArchive.rejectIfArchived", "QnxReceiverCleanup.stop",
                  "RootOperation.HOOK_CONTROL to", "QnxClusterHook.control",
                  "RootOperation.HOOK_RECEIPT to", "QnxClusterHook.receipt", "unknown_operation"):
        if token not in root_main:
            raise SystemExit(f"Root 分发链缺失: {token}")
    for token in ("RootHabSession.run", "QnxReceiverDeployer.deploy", "4 -> QnxReceiverDeployer"):
        if token in root_main:
            raise SystemExit(f"Root 仍含退役启动调用: {token}")
    gradle = read("galaxy/service/build.gradle.kts")
    for token in ("prepareQnxReceiver", "prepareClusterHook"):
        if token not in gradle:
            raise SystemExit(f"共享资产准备链缺失: {token}")
    # buildHab 必须是无动作占位（无 Exec、无 commandLine、无输入/输出、无依赖）。
    # 只扫描去掉注释后的代码，说明文字不构成配置；字符串里的真实声明仍然拒绝。
    start = gradle.index("val buildHab by tasks.registering")
    end = gradle.index("val qnxAssets", start)
    build_hab_block = strip_kts_comments(gradle[start:end])
    for token in ("Exec", "commandLine", "dependsOn", "inputs", "outputs", "libgalaxy_hab_bridge.so"):
        if token in build_hab_block:
            raise SystemExit(f"buildHab 占位仍含可执行/依赖: {token}")
    gradle_code = strip_kts_comments(gradle)
    for token in ("jniLibs.directories", "testInstrumentationRunner", "libgalaxy_hab_bridge.so"):
        if token in gradle_code:
            raise SystemExit(f"service Gradle 仍含旧 bridge 打包/烟测: {token}")
    if "dependsOn(prepareQnxReceiver, prepareClusterHook)" not in gradle:
        raise SystemExit("preBuild 未保留共享资产准备链")
    auto = read("galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt")
    for token in ("ensureComposition", "updateProjection", "GalaxyHabComposition"):
        if token in auto:
            raise SystemExit(f"AutoService 仍含旧输出链: {token}")
    if "putBundle(\"mapStreamSize\", MapStreamSize.DEFAULT.bundle())" not in auto:
        raise SystemExit("AutoService 未兼容发布 MapStreamSize.DEFAULT")
    if "putBoolean(\"clusterLyricsRequested\", !paused && elements?.music == true)" not in auto:
        raise SystemExit("AutoService 的歌词需求未收敛为实际 OEM music")
    if "putBoolean(\"qnxReady\", false)" not in auto or "putBoolean(\"projectionRequested\", false)" not in auto:
        raise SystemExit("AutoService 快照未恒为归档态")
    preview = read("galaxy/cluster/src/main/java/com/galaxy/autocluster/ui/InstrumentMusicPreview.kt")
    if "import com.galaxy.autocluster.preview.SceneData" not in preview:
        raise SystemExit("InstrumentMusicPreview 丢失 SceneData 共享依赖")
    for path in (root / "galaxy/cluster/src/main").rglob("*.kt"):
        text = read_text(path)
        if ("import com.galaxy.autocluster.theme." in text or
                "package com.galaxy.autocluster.theme" in text or
                "import com.galaxy.autocluster.runtime.ScenePreparation" in text):
            raise SystemExit(f"活跃源码仍引用主题引擎: {rel_posix(path, root)}")
    return {
        "qnxClusterHookHealthChain": True,
        "rootOp11Op12Preserved": True,
        "rootOp4Op6Rejected": True,
        "prepareQnxReceiverPreserved": True,
        "serviceGradleBridgeRetired": True,
        "instrumentMusicPreviewSceneDataPreserved": True,
        "activeThemeReferencesAbsent": True,
        "note": "源码调用闭包审计；不代表 QNX/车辆已运行或实屏通过",
    }


def kadb_transform_gate() -> dict:
    """直接核验生产 Gradle transformer 的最终输出，并把产物摘要纳入交付。"""
    sys.path.insert(0, str(ROOT / "tools/review/galaxy-platform-p1-20261113"))
    from verify_kadb_transform import inspect_artifacts
    return inspect_artifacts(ROOT)


def source_gate() -> dict:
    """绑定最终源码/资源/KTS/归档摘要与工作树 HEAD。"""
    sources = {}
    for path in sorted((ROOT / "galaxy").rglob("*")):
        if path.is_file() and "build" not in path.parts and path.suffix in {".kt", ".kts", ".xml", ".aidl"}:
            sources[rel_posix(path)] = sha256(path.read_bytes())
    archive_hashes = {}
    for archive in (ARCHIVE_ROOT, THEME_ARCHIVE_ROOT):
        for path in sorted((ROOT / archive).rglob("*")):
            if path.is_file():
                archive_hashes[rel_posix(path)] = sha256(path.read_bytes())
    validation_paths = set((ROOT / "tools/review/galaxy-r0-20261112").rglob("*.py"))
    validation_paths.update((ROOT / "tools/review/galaxy-platform-p1-20261113").rglob("*.py"))
    validation_paths.update((ROOT / "scripts/verify").rglob("*.py"))
    validation_paths.update((ROOT / "scripts/verify").rglob("*.gradle"))
    validation_paths.update(ROOT / name for name in (
        ".gitattributes", "format/galaxy/.editorconfig",
        "tools/review/galaxy-r0-20261112/format-files.txt",
        "tools/review/galaxy-c2a-20261112/format_check.py",
        "scripts/verify_projection_retirement.sh", "scripts/verify_projection_retirement_windows.ps1",
        "scripts/verify_cluster_pages.sh", "scripts/verify_android_refactor.sh",
    ))
    validation_hashes = {rel_posix(path): sha256(path.read_bytes()) for path in sorted(validation_paths)}
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, encoding="utf-8").strip()
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True, encoding="utf-8")
    return {
        "head": head,
        "worktreeStatus": status,
        "activeSourceCount": len(sources),
        "sourceSha256": sources,
        "archiveFileCount": len(archive_hashes),
        "archiveSha256": archive_hashes,
        "validationSourceSha256": validation_hashes,
        "apkBuilt": False,
        "vehicleValidated": False,
        "nativeAssetPreparationExcluded": True,
    }


def main() -> None:
    """汇总全部门槛为 VALIDATION.json；任何一项失败立即非零退出。"""
    REPORT.mkdir(parents=True, exist_ok=True)
    fmt = json.loads(read_text(REPORT / "format-result.json"))
    record = {
        "task": "GALAXY-SERVICE-VEHICLE-FEED-S3-20261113",
        "status": "READY_FOR_REVIEW" if len(sys.argv) > 1 and sys.argv[1] == "ready" else "VALIDATED",
        "archive": archive_gate(),
        "themeArchive": theme_archive_gate(),
        "manifest": manifest_gate(),
        "classes": classes_gate(),
        "kadbTransform": kadb_transform_gate(),
        "junit": junit_gate(),
        "format": fmt,
        "closure": closure_gate(),
        "source": source_gate(),
    }
    target = REPORT / "VALIDATION.json"
    target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    (REPORT / "validation-result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"产物核对通过: 旧整屏归档 {record['archive']['checkedCount']} / 主题归档 {record['themeArchive']['checkedCount']} / "
          f"manifest {record['manifest']['fileCount']} / class {record['classes']['classCount']} / "
          f"JUnit {record['junit']['requiredSuiteCount']} suites {record['junit']['totalCases']} cases / "
          f"源码 {record['source']['activeSourceCount']}")


if __name__ == "__main__":
    main()
