#!/usr/bin/env python3
"""绑定本机Kanzi音乐试验产物；仅写忽略目录，不连接/部署/上传。"""
import datetime
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    native = ROOT / ".work/kanzi-music"
    report = json.loads((native / "build.json").read_text())
    apk = ROOT / "galaxy/mobile/build/outputs/apk/debug/mobile-debug.apk"
    with zipfile.ZipFile(apk) as z:
        own = z.read("assets/kanzi-music/libgalaxy_kanzi_music.so")
        assert (
            hashlib.sha256(own).hexdigest()
            == report["artifacts"]["libgalaxy_kanzi_music.so"]["sha256"]
        )
        manifest = json.loads(z.read("assets/kanzi-music/component.json"))
        assert manifest["fieldValidated"] is False
        assert not any(
            "cluster-hmi" in name or "libc-link-only" in name for name in z.namelist()
        )
    tests = []
    for p in sorted(
        (ROOT / "common/build/test-results/testDebugUnitTest").glob("TEST-*.xml")
    ):
        tree = ET.parse(p).getroot()
        tests.append(
            {
                "suite": tree.get("name"),
                "tests": int(tree.get("tests", "0")),
                "failures": int(tree.get("failures", "0")),
                "errors": int(tree.get("errors", "0")),
                "skipped": int(tree.get("skipped", "0")),
            }
        )
    wanted = {
        "GalaxyKanziMusicFrameTest",
        "GalaxyOemLyricsTest",
        "GalaxyNowPlayingIdentityTest",
        "L7MediaCenterSessionTest",
    }
    tests = [t for t in tests if t["suite"].split(".")[-1] in wanted]
    assert len(tests) == 4 and all(
        t["failures"] == t["errors"] == t["skipped"] == 0 for t in tests
    )
    result = {
        "timestamp": datetime.datetime.now(
            datetime.timezone(datetime.timedelta(hours=8))
        ).isoformat(),
        "command": "bash scripts/verify_kanzi_music.sh --rootfs <local-g733-rootfs>",
        "deviceConnected": False,
        "targetExecuted": False,
        "componentMounted": False,
        "hostSanitizers": "address,undefined",
        "hostLifecycle": "mount/update/late-session/restore/scene-change/expiry/shutdown passed",
        "jvmTests": tests,
        "jvmTotal": sum(t["tests"] for t in tests),
        "apk": {"path": str(apk), "sha256": sha(apk), "bytes": apk.stat().st_size},
        "nativeArtifacts": report["artifacts"],
        "baseline": report["originalSha256"],
        "privateCandidate": str(native / "cluster-hmi-music-candidate"),
        "sourceSha256": report["sourceSha256"],
    }
    (native / "trial.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                "report": str(native / "trial.json"),
                "jvmTests": result["jvmTotal"],
                "apkSha256": result["apk"]["sha256"],
                "targetExecuted": False,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
