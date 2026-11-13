"""A4最终结构/真实merged Manifest核对；业务行为由真实生产JUnit验证。"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from checkpoint import ROOT, check_scope, format_files, scope

ANDROID = "{http://schemas.android.com/apk/res/android}"


def artwork_provider(root: Path = ROOT) -> dict:
    """核对AGP两份真实Service Manifest，保持authority/exported并迁入media类。"""
    paths = [
        "galaxy/service/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        "galaxy/service/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
    ]
    checked = []
    for relative in paths:
        node = ET.parse(root / relative).getroot()
        providers = [item for item in node.findall("./application/provider")
                     if item.get(ANDROID + "authorities") == "com.ecarx.autoservice.artwork"]
        if len(providers) != 1:
            raise SystemExit(f"封面authority缺失或重复: {relative}")
        provider = providers[0]
        name = provider.get(ANDROID + "name", "")
        if name.startswith("."):
            name = node.get("package", "") + name
        if (name != "com.ecarx.autoservice.media.ArtworkProvider" or
                provider.get(ANDROID + "exported") != "true" or provider.get(ANDROID + "permission")):
            raise SystemExit(f"封面组件合同改变: {relative}")
        checked.append({"path": relative, "class": name, "authority": provider.get(ANDROID + "authorities")})
    return {"manifests": checked, "runtimeSignatureCheckRequiresProductionTest": True}


def inspect(root: Path = ROOT, check_business_scope: bool = True) -> dict:
    """最终必须完整交付；静态所有权检查不代替编译和实际运行用例。"""
    record = scope(root)
    changed = check_scope(record, root) if check_business_scope else []
    files = format_files("C4", record, root)
    auto = (root / "galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt").read_text(encoding="utf-8")
    if re.search(r"object\s*:\s*IAutoService\s*\.\s*Stub", auto):
        raise SystemExit("AutoService仍内嵌Binder实现")
    for name in ("receiveArtwork", "selectMedia", "nativeMusic", "applyDemand", "vehicleKeys", "updateRemote", "readBytesBounded"):
        if re.search(r"\bfun\s+(?:[\w.]+\.)?" + name + r"\s*\(", auto):
            raise SystemExit(f"AutoService仍持有业务方法: {name}")
    for name in ("media", "navigation", "nativeMedia", "visualMedia", "signals"):
        if re.search(r"\bvar\s+" + name + r"\s*=\s*Bundle\s*\(", auto):
            raise SystemExit(f"AutoService仍镜像业务状态: {name}")
    classes = root / "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    leftovers = [p.relative_to(classes).as_posix() for p in classes.rglob("*.class")
                 if any(p.relative_to(classes).as_posix() == f"com/ecarx/autoservice/{name}.class" or
                        p.relative_to(classes).as_posix().startswith(f"com/ecarx/autoservice/{name}$")
                        for name in ("KanziMediaPublisher", "OemArtworkWriter", "ArtworkProvider"))]
    if leftovers:
        raise SystemExit(f"已迁media类型仍有旧根包class: {leftovers}")
    return {"businessChangedPaths": changed, "currentTaskScopeCheckedSeparately": not check_business_scope,
            "checkedFormatFiles": len(files),
            "rootBinderMoved": True, "rootBusinessMethodsMoved": True,
            "artworkProvider": artwork_provider(root), "manualCallOrderReviewStillRequired": True}
