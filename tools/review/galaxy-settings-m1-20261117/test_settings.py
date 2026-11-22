"""实际调用M1范围/JUnit/缓存检查，不复制业务流程或验证算法。"""
from pathlib import Path
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("m1_verify_under_test", HERE / "verify_settings.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SettingsToolTests(unittest.TestCase):
    def fixture(self, root):
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        (root / "galaxy/common").mkdir(parents=True)
        allowed = "galaxy/common/page.kt"
        (root / allowed).write_text("baseline\n")
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        subprocess.run(["git", "-c", "user.name=ToolTest", "-c", "user.email=test@local.invalid",
                        "commit", "-qm", "baseline"], cwd=root, check=True)
        baseline = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
        record = {"scopeBaseline": baseline, "writeWhitelist": [allowed], "preparedBuildSha256": None,
                  "baselineSuites": ["old"], "requiredSuites": ["new"]}
        folder = root / module.REL
        folder.mkdir(parents=True)
        (folder / "scope.json").write_text(json.dumps(record), encoding="utf-8")
        return allowed

    def test_scope_checks_real_git_diff_and_other_apps(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            allowed = self.fixture(root)
            (root / allowed).write_text("changed\n")
            self.assertEqual([allowed], module.check_scope(root))
            forbidden = root / "galaxy/service/other.kt"
            forbidden.parent.mkdir()
            forbidden.write_text("outside")
            with self.assertRaises(SystemExit):
                module.check_scope(root)

    def test_junit_reads_actual_xml_and_rejects_failures_and_missing_suites(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            folder = root / "common/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            for name in ["old", "new"]:
                (folder / f"TEST-{name}.xml").write_text(f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(2, module.junit(root)["caseCount"])
            (folder / "TEST-new.xml").write_text('<testsuite name="new" tests="1" failures="1"/>')
            with self.assertRaises(SystemExit):
                module.junit(root)
            (folder / "TEST-new.xml").unlink()
            with self.assertRaises(SystemExit):
                module.junit(root)

    def test_incomplete_candidate_cannot_pass_before(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            allowed = self.fixture(root)
            (root / allowed).unlink()
            with self.assertRaises(SystemExit):
                module.before(root)

    def test_cache_checker_refuses_missing_cache_without_network_download(self):
        root = HERE.parents[2]
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run([sys.executable, str(root / "scripts/verify/lib/prepare_common_robolectric_cache.py"),
                                     "--cache-root", tmp], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Robolectric".encode(), result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
