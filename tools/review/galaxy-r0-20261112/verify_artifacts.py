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
    "com/ecarx/autoservice/platform/RootProjectionArchive.class",
    "com/ecarx/autoservice/platform/QnxReceiverCleanup.class",
    "com/ecarx/autoservice/platform/PosixChecksum.class",
    "com/ecarx/autoservice/AutoService.class",
    "com/ecarx/autoservice/platform/RootServiceMain.class",
    "com/ecarx/autoservice/platform/PlatformCoordinator.class",
    "com/ecarx/autoservice/platform/QnxClusterHook.class",
    "com/galaxy/autocluster/ClusterRenderService.class",
    "com/galaxy/autocluster/ui/InstrumentMusicPreview.class",
    "com/galaxy/autocluster/theme/SceneData.class",
]
# 本批必测：JUnit suite 必须真实存在、非空、零失败/错误/跳过。
REQUIRED_SUITES = {
    "cluster": [
        "com.galaxy.autocluster.ClusterAppNavigationTest",
        "com.galaxy.autocluster.ClusterPageBehaviorTest",
        "com.galaxy.autocluster.LegacyProjectionRetirementTest",
    ],
    "service": [
        "com.ecarx.autoservice.platform.RootProjectionArchiveTest",
        "com.ecarx.autoservice.platform.PlatformExecutionTest",
        "com.ecarx.autoservice.ui.ServiceHomeBehaviorTest",
        "com.ecarx.autoservice.ui.DiagnosticPageBehaviorTest",
        "com.ecarx.autoservice.ClusterElementPipelineTest",
        "com.ecarx.autoservice.ClusterHookLifecycleTest",
        "com.ecarx.autoservice.ClientRegistryTest",
        "com.ecarx.autoservice.platform.FoundationRecoveryTest",
        "com.ecarx.autoservice.remote.RemoteConnectionStatusTest",
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
]


def sha256(data: bytes) -> str:
    """内容 SHA256。"""
    return hashlib.sha256(data).hexdigest()


def git_blob(path: str) -> bytes:
    """读取固定起点提交的原字节。"""
    return subprocess.check_output(["git", "show", f"572ba0eb07113c8c1859efa9c34cb25a1676eff7:{path}"], cwd=ROOT)


def archive_gate() -> dict:
    """逐项三方核对 git blob / 归档文件 / manifest.json，并确认原路径不在活跃源集。"""
    manifest = json.loads((ROOT / ARCHIVE_ROOT / "manifest.json").read_text())
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
        active = ROOT / entry["original"]
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
        text = path.read_text()
        if "ThemeActivity" in text:
            raise SystemExit(f"merged manifest 仍含 ThemeActivity: {rel}")
        files.append({"path": rel, "sha256": sha256(path.read_bytes()),
                      "themeActivityPresent": False})
    cluster_manifest = (ROOT / "galaxy/cluster/src/main/AndroidManifest.xml").read_text()
    if "ThemeActivity" in cluster_manifest:
        raise SystemExit("cluster 源 Manifest 仍声明 ThemeActivity")
    return {"fileCount": len(files), "files": files, "themeActivityPresent": False}


def classes_gate() -> dict:
    """本轮 Kotlin class 输出：无旧专用输出类，新运行类与共享类齐全。"""
    roots = [
        ROOT / "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        ROOT / "galaxy/cluster/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    ]
    for directory in roots:
        if not directory.is_dir():
            raise SystemExit(f"缺少本轮 Kotlin class 输出: {directory}")
    all_classes = sorted(str(p.relative_to(root)) for root in roots for p in root.rglob("*.class"))
    if not all_classes:
        raise SystemExit("本轮 class 输出为空")
    forbidden_hits = []
    for name in FORBIDDEN_CLASSES:
        for item in all_classes:
            leaf = item.rsplit("/", 1)[-1]
            if leaf == f"{name}.class" or leaf.startswith(f"{name}$"):
                forbidden_hits.append(item)
    if forbidden_hits:
        raise SystemExit(f"本轮产物仍含已归档类: {forbidden_hits[:10]}")
    missing = [c for c in REQUIRED_CLASSES if not any((root / c).is_file() for root in roots)]
    if missing:
        raise SystemExit(f"缺少运行类产物: {missing}")
    return {
        "classRoots": [str(r.relative_to(ROOT)) for r in roots],
        "classCount": len(all_classes),
        "forbiddenChecked": len(FORBIDDEN_CLASSES),
        "forbiddenHits": forbidden_hits,
        "requiredChecked": len(REQUIRED_CLASSES),
    }


def junit_gate() -> dict:
    """精确 suite 清单：真实 JUnit XML 非空、零失败/错误/跳过，零匹配即失败。"""
    results = {}
    for module, suites in REQUIRED_SUITES.items():
        directory = ROOT / f"galaxy/{module}/build/test-results/testDebugUnitTest"
        parsed = []
        for suite in suites:
            path = directory / f"TEST-{suite}.xml"
            if not path.is_file():
                raise SystemExit(f"必测 suite 零匹配（缺少真实 JUnit 报告）: {suite}")
            node = ET.parse(path).getroot()
            counts = {key: int(node.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            if counts["tests"] <= 0 or any(counts[k] for k in ("failures", "errors", "skipped")):
                raise SystemExit(f"必测 suite 未达标: {suite}: {counts}")
            parsed.append({"suite": suite, **counts, "report": str(path.relative_to(ROOT)),
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
    }


def closure_gate() -> dict:
    """共享调用闭包与 Gradle 退役依据（源码级审计，不冒充 QNX 运行）。"""
    def read(path: str) -> str:
        return (ROOT / path).read_text()

    hook = read("galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxClusterHook.kt")
    for token in ("receiver-candidate", "hook-health", "PosixChecksum.calculate"):
        if token not in hook:
            raise SystemExit(f"共享 health 链缺失: {token}")
    root_main = read("galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt")
    for token in ("RootProjectionArchive.rejectIfArchived", "QnxReceiverCleanup.stop",
                  "11 -> QnxClusterHook.control", "12 -> QnxClusterHook.receipt"):
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
    start = gradle.index("val buildHab by tasks.registering")
    end = gradle.index("val qnxAssets", start)
    build_hab_block = gradle[start:end]
    for token in ("Exec", "commandLine", "dependsOn", "inputs", "outputs", "libgalaxy_hab_bridge.so"):
        if token in build_hab_block:
            raise SystemExit(f"buildHab 占位仍含可执行/依赖: {token}")
    for token in ("jniLibs.directories", "testInstrumentationRunner", "libgalaxy_hab_bridge.so"):
        if token in gradle:
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
    if "import com.galaxy.autocluster.theme.SceneData" not in preview:
        raise SystemExit("InstrumentMusicPreview 丢失 SceneData 共享依赖")
    return {
        "qnxClusterHookHealthChain": True,
        "rootOp11Op12Preserved": True,
        "rootOp4Op6Rejected": True,
        "prepareQnxReceiverPreserved": True,
        "serviceGradleBridgeRetired": True,
        "instrumentMusicPreviewSceneDataPreserved": True,
        "note": "源码调用闭包审计；不代表 QNX/车辆已运行或实屏通过",
    }


def source_gate() -> dict:
    """绑定最终源码/资源/KTS/归档摘要与工作树 HEAD。"""
    sources = {}
    for path in sorted((ROOT / "galaxy").rglob("*")):
        if path.is_file() and "build" not in path.parts and path.suffix in {".kt", ".kts", ".xml", ".aidl"}:
            sources[str(path.relative_to(ROOT))] = sha256(path.read_bytes())
    archive_hashes = {}
    for path in sorted((ROOT / ARCHIVE_ROOT).rglob("*")):
        if path.is_file():
            archive_hashes[str(path.relative_to(ROOT))] = sha256(path.read_bytes())
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    return {
        "head": head,
        "worktreeStatus": status,
        "activeSourceCount": len(sources),
        "sourceSha256": sources,
        "archiveFileCount": len(archive_hashes),
        "archiveSha256": archive_hashes,
        "apkBuilt": False,
        "vehicleValidated": False,
        "nativeAssetPreparationExcluded": True,
    }


def main() -> None:
    """汇总全部门槛为 VALIDATION.json；任何一项失败立即非零退出。"""
    REPORT.mkdir(parents=True, exist_ok=True)
    fmt = json.loads((REPORT / "format-result.json").read_text())
    record = {
        "task": "GALAXY-ANDROID-R0-20261112",
        "status": "READY_FOR_REVIEW" if len(sys.argv) > 1 and sys.argv[1] == "ready" else "VALIDATED",
        "archive": archive_gate(),
        "manifest": manifest_gate(),
        "classes": classes_gate(),
        "junit": junit_gate(),
        "format": fmt,
        "closure": closure_gate(),
        "source": source_gate(),
    }
    target = REPORT / "VALIDATION.json"
    target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    (REPORT / "validation-result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(f"产物核对通过: 归档 {record['archive']['checkedCount']} / "
          f"manifest {record['manifest']['fileCount']} / class {record['classes']['classCount']} / "
          f"JUnit {record['junit']['requiredSuiteCount']} suites {record['junit']['totalCases']} cases / "
          f"源码 {record['source']['activeSourceCount']}")


if __name__ == "__main__":
    main()
