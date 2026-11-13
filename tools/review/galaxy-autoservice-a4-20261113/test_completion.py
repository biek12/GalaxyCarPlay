"""直接运行A4原检查器读取真实形状的产物，不复制检查算法。"""
import json
import tempfile
import unittest
from pathlib import Path

import checkpoint
import completion


class CompletionToolsTest(unittest.TestCase):
    """检查中间阶段严格性、实际JUnit负例及真实Manifest合同。"""

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
