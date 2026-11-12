"""直接执行主题归档、产物及 JUnit 生产闸门；只在临时文件树构造负例。"""
from __future__ import annotations

import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import verify_artifacts as gates


class ThemeRetirementGateTest(unittest.TestCase):
    """归档须可追溯，旧引擎不得回到编译产物，全部实际测试必须合格。"""

    def test_archive_rejects_byte_change_and_active_original(self) -> None:
        """校验固定 Git 原字节；负例只改临时副本，不接触真实归档。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(gates.ROOT / gates.THEME_ARCHIVE_ROOT, root / gates.THEME_ARCHIVE_ROOT)
            self.assertEqual(44, gates.theme_archive_gate(root)["checkedCount"])
            manifest = gates.json.loads((root / gates.THEME_ARCHIVE_ROOT / "manifest.json").read_text(encoding="utf-8"))
            entry = manifest["entries"][0]
            archived = root / entry["archived"]
            original = archived.read_bytes()
            archived.write_bytes(original + b" ")
            with self.assertRaisesRegex(SystemExit, "原字节不一致"):
                gates.theme_archive_gate(root)
            archived.write_bytes(original)
            active = root / entry["original"]
            active.parent.mkdir(parents=True)
            active.write_bytes(original)
            with self.assertRaisesRegex(SystemExit, "仍在活跃源集"):
                gates.theme_archive_gate(root)

    def test_classes_reject_unlisted_theme_type_and_require_preview_models(self) -> None:
        """检查整个主题命名空间，而非仅识别旧类型名清单。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            service = root / "galaxy/service/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
            cluster = root / "galaxy/cluster/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
            service.mkdir(parents=True)
            cluster.mkdir(parents=True)
            for name in gates.REQUIRED_CLASSES:
                target = (cluster if name.startswith("com/galaxy/autocluster/") else service) / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"class")
            self.assertTrue(gates.classes_gate(root)["themeNamespaceAbsent"])
            for name in ("com/galaxy/autocluster/theme/UnlistedType.class",
                         "com/galaxy/autocluster/runtime/ScenePreparation$Nested.class"):
                target = cluster / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"class")
                with self.assertRaisesRegex(SystemExit, "仍含已归档类"):
                    gates.classes_gate(root)
                target.unlink()
            (cluster / gates.PREVIEW_CLASSES[-1]).unlink()
            with self.assertRaisesRegex(SystemExit, "缺少运行类"):
                gates.classes_gate(root)

    def test_junit_rejects_archived_suites_and_failures_outside_required_list(self) -> None:
        """实际报告里即使是额外套件也必须零失败；归档套件不能重新参加运行。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module, suites in gates.REQUIRED_SUITES.items():
                folder = root / f"galaxy/{module}/build/test-results/testDebugUnitTest"
                folder.mkdir(parents=True)
                for suite in suites:
                    ET.ElementTree(ET.Element("testsuite", name=suite, tests="1", failures="0",
                                              errors="0", skipped="0")).write(folder / f"TEST-{suite}.xml")
            self.assertEqual(12, gates.junit_gate(root)["requiredSuiteCount"])
            folder = root / "galaxy/cluster/build/test-results/testDebugUnitTest"
            retired = "com.galaxy.autocluster.theme.ThemeStoreTest"
            path = folder / f"TEST-{retired}.xml"
            ET.ElementTree(ET.Element("testsuite", name=retired, tests="1", failures="0",
                                      errors="0", skipped="0")).write(path)
            with self.assertRaisesRegex(SystemExit, "归档测试"):
                gates.junit_gate(root)
            path.unlink()
            ET.ElementTree(ET.Element("testsuite", name="extra", tests="1", failures="1",
                                      errors="0", skipped="0")).write(folder / "TEST-extra.xml")
            with self.assertRaisesRegex(SystemExit, "实际 suite 未达标"):
                gates.junit_gate(root)


if __name__ == "__main__":
    unittest.main()
