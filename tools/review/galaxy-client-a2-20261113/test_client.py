"""直接执行A2检查器，使用真实JDK、Git和JUnit文件的正反例。"""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import verify_client as gate


class ClientToolsTest(unittest.TestCase):
    """范围、公开字节码API与额外失败必须可复现拒绝。"""

    def test_actual_javac_javap_detects_api_change(self):
        """先真实编译，再调用生产javap解析器；增加公开方法后不能冒充原API。"""
        javac = shutil.which("javac")
        self.assertIsNotNone(javac)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "AbiFixture.java"
            source.write_text("public class AbiFixture { public void connect() {} private void hidden() {} }", encoding="utf-8")
            subprocess.run([javac, "-d", str(root), str(source)], check=True, capture_output=True)
            expected = gate.api_surface(root, "AbiFixture")
            self.assertTrue(any(row["descriptor"] == "()V" and "connect" in row["declaration"] for row in expected))
            self.assertFalse(any("hidden" in row["declaration"] for row in expected))
            gate.compare_api(expected, gate.api_surface(root, "AbiFixture"))
            source.write_text("public class AbiFixture { public void connect() {} public int added() { return 1; } }", encoding="utf-8")
            subprocess.run([javac, "-d", str(root), str(source)], check=True, capture_output=True)
            with self.assertRaisesRegex(SystemExit, "API"):
                gate.compare_api(expected, gate.api_surface(root, "AbiFixture"))

    def test_before_uses_real_git_and_requires_whole_task(self):
        """真正运行当前范围/文件/公开方法门槛，未完成或提交越界都拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()
            git("init", "-q")
            source = root / "galaxy/AutoClient.kt"
            source.parent.mkdir()
            source.write_text("class AutoClient {\n    fun connect() {}\n}\n", encoding="utf-8")
            git("add", "--", "galaxy/AutoClient.kt")
            git("-c", "user.name=Test", "-c", "user.email=test@local.invalid", "commit", "-qm", "base")
            record = {"scopeBaseline": git("rev-parse", "HEAD"),
                      "writeWhitelist": ["galaxy/AutoClient.kt", "galaxy/Owner.kt"], "publicMethods": ["connect"]}
            folder = root / gate.HERE.relative_to(gate.ROOT)
            folder.mkdir(parents=True)
            (folder / "scope.json").write_text(json.dumps(record), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "尚未完整"):
                gate.before(root)
            (root / "galaxy/Owner.kt").write_text("class Owner", encoding="utf-8")
            gate.before(root)
            source.write_text("class AutoClient {}", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "24方法"):
                gate.before(root)
            source.write_text("class AutoClient {\n    fun connect() {}\n}\n", encoding="utf-8")
            (root / "galaxy/outside.kt").write_text("outside", encoding="utf-8")
            git("add", "--", "galaxy/outside.kt")
            git("-c", "user.name=Test", "-c", "user.email=test@local.invalid", "commit", "-qm", "outside")
            with self.assertRaisesRegex(SystemExit, "outside.kt"):
                gate.before(root)

    def test_actual_all_client_junit_rejects_missing_and_extra_failure(self):
        """复用生产JUnit解析器，最终四组新suite不可被旧基线替代。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "galaxy/client/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            def write(name, failures=0):
                (folder / f"TEST-{name}.xml").write_text(
                    f'<testsuite name="{name}" tests="1" failures="{failures}" errors="0" skipped="0"/>', encoding="utf-8")
            record = {"baselineSuites": ["account"], "requiredSuites": ["account", "connection", "callbacks", "replay", "requests"]}
            write("account")
            self.assertEqual(1, gate.junit(record, baseline=True, root=root)["caseCount"])
            with self.assertRaisesRegex(SystemExit, "缺少必测"):
                gate.junit(record, root=root)
            for name in record["requiredSuites"][1:]:
                write(name)
            self.assertEqual(5, gate.junit(record, root=root)["caseCount"])
            write("extra", 1)
            with self.assertRaisesRegex(SystemExit, "未达标"):
                gate.junit(record, root=root)


if __name__ == "__main__":
    unittest.main()
