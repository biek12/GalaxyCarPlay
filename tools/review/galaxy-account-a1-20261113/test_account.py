"""直接调用A1原检查器核对真实Git、JUnit、Manifest和PNG文件。"""
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

import verify_account as gate


class AccountToolsTest(unittest.TestCase):
    """工具范围/额外失败/真实组件负例不能绕过最终门槛。"""

    def test_real_git_scope_catches_committed_and_untracked_outside(self):
        """在真实临时仓库提交越界文件，原检查器仍必须识别。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()
            git("init", "-q")
            (root / "galaxy").mkdir()
            (root / "galaxy/allowed.kt").write_text("baseline", encoding="utf-8")
            color_paths = [root / f"galaxy/client/src/main/res/{part}/vehicle_account.xml" for part in ("values", "values-night")]
            for path in color_paths:
                path.parent.mkdir(parents=True)
                path.write_text('<resources><color name="text">#FFFFFF</color></resources>', encoding="utf-8")
            git("add", "--", "galaxy")
            git("-c", "user.name=Test", "-c", "user.email=test@local.invalid", "commit", "-qm", "base")
            record = {"scopeBaseline": git("rev-parse", "HEAD"), "sourceBaseline": git("rev-parse", "HEAD"),
                      "writeWhitelist": ["galaxy/allowed.kt"]}
            self.assertEqual([], gate.check_scope(record, root))
            gate.colors(record, root)
            original_colors = color_paths[0].read_text()
            color_paths[0].write_text(original_colors.replace("#FFFFFF", "#000000"), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "色板改变"):
                gate.colors(record, root)
            color_paths[0].write_text(original_colors, encoding="utf-8")
            (root / "galaxy/allowed.kt").write_text("candidate", encoding="utf-8")
            self.assertEqual(["galaxy/allowed.kt"], gate.check_scope(record, root))
            (root / "galaxy/outside.kt").write_text("outside", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "outside.kt"):
                gate.check_scope(record, root)
            git("add", "--", "galaxy/outside.kt")
            git("-c", "user.name=Test", "-c", "user.email=test@local.invalid", "commit", "-qm", "outside")
            with self.assertRaisesRegex(SystemExit, "outside.kt"):
                gate.check_scope(record, root)

    def test_actual_junit_requires_new_suites_and_rejects_extra_failure(self):
        """baseline不要求未来suite；final严格非空且额外失败也拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "galaxy/client/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            def write(name, failures=0):
                (folder / f"TEST-{name}.xml").write_text(
                    f'<testsuite name="{name}" tests="1" failures="{failures}" errors="0" skipped="0"/>', encoding="utf-8")
            record = {"baselineSuites": ["base"], "requiredSuites": ["base", "new"]}
            write("base")
            self.assertEqual(1, gate.junit(record, baseline=True, root=root)["caseCount"])
            with self.assertRaisesRegex(SystemExit, "必测"):
                gate.junit(record, root=root)
            write("new")
            self.assertEqual(2, gate.junit(record, root=root)["caseCount"])
            write("extra", 1)
            with self.assertRaisesRegex(SystemExit, "未达标"):
                gate.junit(record, root=root)

    def test_real_manifests_and_png_contract(self):
        """实际读XML和PNG头；公开共享页面、错误尺寸和重复截图均拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [root / f"galaxy/{module}/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
                     for module in ("service", "record")]
            for path in paths:
                path.parent.mkdir(parents=True)
                path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
                                '<activity android:name="com.galaxy.auto.client.vehicle.VehicleAccountActivity" '
                                'android:exported="false"/></application></manifest>', encoding="utf-8")
            self.assertEqual(2, len(gate.manifests(root)))
            paths[0].write_text(paths[0].read_text().replace('exported="false"', 'exported="true"'), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "组件合同"):
                gate.manifests(root)
            signature = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
            (root / "one.png").write_bytes(signature + struct.pack(">II", 1080, 1800) + bytes(64))
            (root / "two.png").write_bytes((root / "one.png").read_bytes())
            record = {"screenshots": ["one.png", "two.png"]}
            with self.assertRaisesRegex(SystemExit, "重复"):
                gate.screenshots(record, root)
            (root / "two.png").write_bytes(signature + struct.pack(">II", 1, 1800) + bytes(65))
            with self.assertRaisesRegex(SystemExit, "尺寸"):
                gate.screenshots(record, root)
            (root / "two.png").write_bytes(signature + struct.pack(">II", 1080, 1800) + bytes(65))
            self.assertEqual(2, len(gate.screenshots(record, root)))


if __name__ == "__main__":
    unittest.main()
