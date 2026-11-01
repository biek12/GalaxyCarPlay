"""上车前门禁必须拒绝缺结果、跳过结果或源码变化；不连接设备。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('cluster_preflight',Path(__file__).resolve().parents[1]/'cluster_preflight.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ClusterPreflightTest(unittest.TestCase):
    def fixture(self,root):
        for folder in ('galaxy/contracts','galaxy/service','galaxy/cluster'):
            source=root/folder/'src/test/FooTest.kt';source.parent.mkdir(parents=True);source.write_text('class FooTest')
            result=root/folder/'build/test-results/testDebugUnitTest/TEST-example.FooTest.xml';result.parent.mkdir(parents=True)
            result.write_text('<testsuite name="example.FooTest" tests="2" failures="0" errors="0" skipped="0"/>')
        for name in ('ClusterMusicTest','GalaxyL6HabFrameFormatTest'):
            result=root/f'common/build/test-results/testDebugUnitTest/TEST-example.{name}.xml';result.parent.mkdir(parents=True,exist_ok=True)
            result.write_text(f'<testsuite name="example.{name}" tests="1" failures="0" errors="0" skipped="0"/>')
    def test_missing_suite_and_skipped_tests_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root)
            with patch.object(module,'ROOT',root):
                self.assertEqual(5,len(module.test_inventory()))
                result=root/'galaxy/cluster/build/test-results/testDebugUnitTest/TEST-example.FooTest.xml'
                result.write_text('<testsuite name="example.FooTest" tests="2" failures="0" errors="0" skipped="1"/>')
                with self.assertRaises(ValueError):module.test_inventory()
                result.unlink()
                with self.assertRaises(ValueError):module.test_inventory()
    def test_changed_sources_require_a_new_report(self):
        with patch.object(module,'source_hashes',return_value={'one.kt':'new'}):
            with self.assertRaises(ValueError):module.check_sources({'source_sha256':{'one.kt':'old'}})
            module.check_sources({'source_sha256':{'one.kt':'new'}})
