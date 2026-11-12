"""E0 回归：直接调用 archive_gate、closure_gate、classes_gate。

不另写类名匹配或注释过滤模型。失败场景必须让原函数非零退出。
"""
from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_artifacts as gates  # noqa: E402


REQUIRED_CLASS_ROOTS = (
    "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    "galaxy/cluster/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
)


def write(root: Path, relative: str, text: str) -> None:
    """写入 fixture 文本。"""
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def legal_closure_tree(root: Path) -> None:
    """构造能通过其他闭包检查的最小树。退役词只出现在注释里。"""
    write(root, "galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxClusterHook.kt",
          "receiver-candidate\nhook-health\nPosixChecksum.calculate\n")
    write(root, "galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt",
          "RootProjectionArchive.rejectIfArchived\nQnxReceiverCleanup.stop\n"
          "11 -> QnxClusterHook.control\n12 -> QnxClusterHook.receipt\n")
    write(root, "galaxy/service/build.gradle.kts", """
// 说明：不再声明 testInstrumentationRunner，也不打包 libgalaxy_hab_bridge.so，不使用 jniLibs.directories。
val buildHab by tasks.registering {
    // 归档占位，无 Exec、无 commandLine、无 dependsOn、无 inputs、无 outputs。
}
val qnxAssets = "kept"
val prepareQnxReceiver = "prepareQnxReceiver"
val prepareClusterHook = "prepareClusterHook"
tasks.named("preBuild") { dependsOn(prepareQnxReceiver, prepareClusterHook) }
""")
    write(root, "galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt",
          'putBundle("mapStreamSize", MapStreamSize.DEFAULT.bundle())\n'
          'putBoolean("clusterLyricsRequested", !paused && elements?.music == true)\n'
          'putBoolean("qnxReady", false)\n'
          'putBoolean("projectionRequested", false)\n')
    write(root, "galaxy/cluster/src/main/java/com/galaxy/autocluster/ui/InstrumentMusicPreview.kt",
          "import com.galaxy.autocluster.theme.SceneData\n")


class ArtifactGateTest(unittest.TestCase):
    """覆盖 E01 原字节、E03 注释误判和 E04 Windows 类路径。"""

    def test_archive_matches_git_blob_and_rejects_one_byte(self) -> None:
        """真实归档 53 项三方相等；改一个字节必须失败，且不能改 manifest 摘要。"""
        passed = gates.archive_gate()
        self.assertEqual(53, passed["checkedCount"])
        manifest_path = gates.ROOT / gates.ARCHIVE_ROOT / "manifest.json"
        manifest_before = manifest_path.read_bytes()
        entry = gates.json.loads(manifest_path.read_text(encoding="utf-8"))["entries"][0]
        archived = gates.ROOT / entry["archived"]
        original = archived.read_bytes()
        self.assertEqual(entry["sha256"], hashlib.sha256(original).hexdigest())
        try:
            mutated = bytearray(original)
            mutated[-1] ^= 0x01
            archived.write_bytes(mutated)
            with self.assertRaises(SystemExit) as caught:
                gates.archive_gate()
            self.assertIn("归档摘要不一致", str(caught.exception))
            self.assertEqual(manifest_before, manifest_path.read_bytes())
        finally:
            archived.write_bytes(original)

    def test_closure_ignores_comments_and_rejects_executable_bridge(self) -> None:
        """注释中的退役词通过；可执行 runner / bridge 声明仍然失败。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legal_closure_tree(root)
            passed = gates.closure_gate(root)
            self.assertTrue(passed["serviceGradleBridgeRetired"])
            gradle = root / "galaxy/service/build.gradle.kts"
            original = gradle.read_text(encoding="utf-8")
            gradle.write_text(original + '\ntestInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"\n',
                              encoding="utf-8", newline="\n")
            with self.assertRaises(SystemExit):
                gates.closure_gate(root)
            gradle.write_text(original + '\njniLibs.directories.add(file("libgalaxy_hab_bridge.so"))\n',
                              encoding="utf-8", newline="\n")
            with self.assertRaises(SystemExit):
                gates.closure_gate(root)
            gradle.write_text(original.replace(
                "val buildHab by tasks.registering {\n",
                "val buildHab by tasks.registering {\n    commandLine(\"echo\", \"bridge\")\n",
            ), encoding="utf-8", newline="\n")
            with self.assertRaises(SystemExit):
                gates.closure_gate(root)

    def test_classes_gate_uses_posix_names(self) -> None:
        """必需类齐全才通过；任一禁止类及其嵌套类都拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in REQUIRED_CLASS_ROOTS:
                (root / relative).mkdir(parents=True)
            service_classes = root / REQUIRED_CLASS_ROOTS[0]
            for required in gates.REQUIRED_CLASSES:
                target = service_classes / required
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"class")
            passed = gates.classes_gate(root)
            self.assertEqual(len(gates.REQUIRED_CLASSES), passed["classCount"])
            self.assertTrue(all("/" in item or item.endswith(".class") for item in passed["classRoots"]))
            forbidden = service_classes / "com/example/ThemeActivity.class"
            forbidden.parent.mkdir(parents=True, exist_ok=True)
            forbidden.write_bytes(b"class")
            with self.assertRaises(SystemExit):
                gates.classes_gate(root)
            forbidden.unlink()
            nested = service_classes / "com/example/ThemeActivity$Inner.class"
            nested.write_bytes(b"class")
            with self.assertRaises(SystemExit):
                gates.classes_gate(root)


if __name__ == "__main__":
    unittest.main()
