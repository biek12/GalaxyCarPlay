"""直接运行A4原检查器读取真实形状的产物，不复制检查算法。"""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import checkpoint
import completion


class CompletionToolsTest(unittest.TestCase):
    """检查中间阶段严格性、实际JUnit负例及真实Manifest合同。"""

    def test_scope_uses_fixed_handoff_and_rejects_later_outside_changes(self):
        """调用原范围检查器：交接规范允许存在，后续规范/源码越界及未跟踪代码仍拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()
            git("init", "-q")
            galaxy = root / "galaxy"
            galaxy.mkdir()
            rules = galaxy / "AGENTS.md"
            rules.write_text("source rules\n", encoding="utf-8")
            code = galaxy / "allowed.kt"
            code.write_text("source code\n", encoding="utf-8")
            git("add", "--", "galaxy")
            git("-c", "user.name=Test", "-c", "user.email=test@local.invalid", "commit", "-qm", "source")
            source = git("rev-parse", "HEAD")
            rules.write_text("authorized handoff rules\n", encoding="utf-8")
            git("add", "--", "galaxy/AGENTS.md")
            git("-c", "user.name=Test", "-c", "user.email=test@local.invalid", "commit", "-qm", "handoff")
            record = {"sourceBaseline": source, "scopeBaseline": git("rev-parse", "HEAD"),
                      "writeWhitelist": ["galaxy/allowed.kt"]}
            code.write_text("candidate code\n", encoding="utf-8")
            self.assertEqual(["galaxy/allowed.kt"], checkpoint.check_scope(record, root))
            rules.write_text("unauthorized later rules\n", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "galaxy/AGENTS.md"):
                checkpoint.check_scope(record, root)
            rules.write_text("authorized handoff rules\n", encoding="utf-8")
            extra = galaxy / "outside.kt"
            extra.write_text("outside\n", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "galaxy/outside.kt"):
                checkpoint.check_scope(record, root)

    def test_required_stage_suites_and_extra_failure(self):
        record = {"baselineServiceSuites": ["base"], "phases": [
            {"name": "C1", "testTypes": ["ipc/ActualTest"]},
        ]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "galaxy/service/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            def write(name, failures=0):
                (folder / f"TEST-{name}.xml").write_text(
                    f'<testsuite name="{name}" tests="1" failures="{failures}" errors="0" skipped="0"/>', encoding="utf-8")
            write("base")
            self.assertEqual(1, checkpoint.inspect_junit("baseline", record, root)["totalCases"])
            with self.assertRaisesRegex(SystemExit, "缺少必测"):
                checkpoint.inspect_junit("C1", record, root)
            write("com.ecarx.autoservice.ipc.ActualTest")
            self.assertEqual(2, checkpoint.inspect_junit("C1", record, root)["totalCases"])
            write("extra", 1)
            with self.assertRaisesRegex(SystemExit, "JUnit未达标"):
                checkpoint.inspect_junit("C1", record, root)

    def test_actual_merged_provider_class_and_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [root / "galaxy/service/build/intermediates" / part / "AndroidManifest.xml" for part in (
                "merged_manifest/debug/processDebugMainManifest", "merged_manifests/debug/processDebugManifest")]
            def write(name):
                for p in paths:
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.ecarx.autoservice">'
                                 '<application><provider android:name="' + name + '" android:authorities="com.ecarx.autoservice.artwork" '
                                 'android:exported="true"/></application></manifest>', encoding="utf-8")
            write(".media.ArtworkProvider")
            self.assertEqual(2, len(completion.artwork_provider(root)["manifests"]))
            write(".ArtworkProvider")
            with self.assertRaisesRegex(SystemExit, "合同改变"):
                completion.artwork_provider(root)

    def test_phase_files_do_not_require_future_phase_but_completed_files_are_mandatory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / checkpoint.HERE.relative_to(checkpoint.ROOT)
            folder.mkdir(parents=True)
            (folder / "baseline-format-files.txt").write_text("old.kt\n", encoding="utf-8")
            (root / "old.kt").write_text("old", encoding="utf-8")
            record = {"phases": [{"name": "C1", "files": ["new.kt"], "deleted": ["old.kt"]},
                                  {"name": "C2", "files": ["future.kt"], "deleted": []}],
                      "finalFormatFiles": ["new.kt", "future.kt"]}
            self.assertEqual(["old.kt"], checkpoint.format_files("baseline", record, root))
            with self.assertRaisesRegex(SystemExit, "阶段文件缺失"):
                checkpoint.format_files("C1", record, root)
            (root / "old.kt").unlink()
            (root / "new.kt").write_text("new", encoding="utf-8")
            self.assertEqual(["new.kt"], checkpoint.format_files("C1", record, root))
            with self.assertRaisesRegex(SystemExit, "阶段文件缺失"):
                checkpoint.format_files("C2", record, root)


if __name__ == "__main__":
    unittest.main()
