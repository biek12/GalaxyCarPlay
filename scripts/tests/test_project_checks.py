"""用合成目录验证基线漂移检查，不触碰产品或参考源码。"""
from pathlib import Path
import hashlib
import json
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import project_checks as checks
from prepare_upstream import candidate_directory, validate_tag


class CoreChecksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = {'common/build.gradle.kts': b'common\n',
                      'shared/build.gradle': b'shared\n',
                      'mobile/build.gradle.kts': b'mobile\n'}
        for name, data in self.files.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.lock = {'files': {name: hashlib.sha256(data).hexdigest() for name, data in self.files.items()}}
        self.registry = {'patches': []}
        self.save()

    def save(self):
        directory = self.root / 'tools/upstream'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'baseline-lock.json').write_text(json.dumps(self.lock))
        (directory / 'patches.json').write_text(json.dumps(self.registry))

    def test_clean_baseline_passes(self):
        self.assertEqual((3, 0), checks.check_core(self.root))

    def test_unregistered_change_fails(self):
        (self.root / 'shared/build.gradle').write_text('changed')
        with self.assertRaisesRegex(AssertionError, '核心漂移'):
            checks.check_core(self.root)

    def test_missing_file_fails(self):
        self.lock['files']['common/missing.kt'] = '0' * 64
        self.save()
        with self.assertRaisesRegex(AssertionError, '文件丢失'):
            checks.check_core(self.root)

    def test_unregistered_addition_fails(self):
        (self.root / 'common/new.kt').write_text('new')
        with self.assertRaisesRegex(AssertionError, '新增文件'):
            checks.check_core(self.root)

    def register_patch(self):
        original = self.lock['files']['shared/build.gradle']
        (self.root / 'shared/build.gradle').write_text('changed\n')
        patch = self.root / 'tools/upstream/change.patch'
        patch.write_text('synthetic patch\n')
        self.registry['patches'] = [{'patch': 'tools/upstream/change.patch',
            'sha256': checks.digest(patch), 'files': {'shared/build.gradle': {
                'baselineSha256': original, 'productSha256': checks.digest(self.root / 'shared/build.gradle')}}}]
        self.save()

    def test_registered_change_passes(self):
        self.register_patch()
        self.assertEqual((3, 1), checks.check_core(self.root))

    def test_registered_product_addition_passes(self):
        self.register_patch()
        source = self.root / 'common/Product.kt'
        source.write_text('product\n')
        self.registry['patches'][0]['files']['common/Product.kt'] = {
            'baselineSha256': None, 'productSha256': checks.digest(source),
        }
        self.save()
        self.assertEqual((3, 2), checks.check_core(self.root))

    def test_modified_patch_fails(self):
        self.register_patch()
        (self.root / 'tools/upstream/change.patch').write_text('tampered\n')
        with self.assertRaisesRegex(AssertionError, '补丁摘要'):
            checks.check_core(self.root)

    def test_duplicate_registration_fails(self):
        self.register_patch()
        self.registry['patches'].append(self.registry['patches'][0])
        self.save()
        with self.assertRaisesRegex(AssertionError, '重复登记'):
            checks.check_core(self.root)

    def test_build_named_source_package_is_not_ignored(self):
        source = self.root / 'common/src/main/java/example/build/New.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package example.build\nclass New\n')
        with self.assertRaisesRegex(AssertionError, '新增文件'):
            checks.check_core(self.root)

    def test_build_named_asset_directory_cannot_hide_credentials(self):
        source = self.root / 'common/src/main/assets/build/probe.pk8'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'synthetic-test-marker-not-a-key')
        self.assertIn(source, list(checks.source_files(self.root / 'common')))
        self.assertIn(source.suffix, checks.CREDENTIAL_SUFFIXES)
        with self.assertRaisesRegex(AssertionError, '新增文件'):
            checks.check_core(self.root)

    def test_generated_output_does_not_count_as_core_source(self):
        (self.root / 'common/build').mkdir()
        (self.root / 'common/build/generated.kt').write_text('generated')
        self.assertEqual((3, 0), checks.check_core(self.root))

    def test_registered_deletion_passes_only_when_file_is_gone(self):
        self.registry['deletions'] = [{'path': 'common/gone.kt', 'reason': '合成删除'}]
        self.save()
        self.assertEqual((3, 0), checks.check_core(self.root))

    def test_registered_deletion_fails_when_file_still_exists(self):
        (self.root / 'common/alive.kt').write_text('still here\n')
        self.registry['deletions'] = [{'path': 'common/alive.kt', 'reason': '合成删除'}]
        self.save()
        with self.assertRaisesRegex(AssertionError, '已登记删除的文件仍存在'):
            checks.check_core(self.root)

    def test_deletion_cannot_overlap_a_registered_change(self):
        self.register_patch()
        self.registry['deletions'] = [{'path': 'shared/build.gradle', 'reason': '合成删除'}]
        self.save()
        with self.assertRaisesRegex(AssertionError, '已登记删除的文件仍存在'):
            checks.check_core(self.root)


class ProductChecksTest(unittest.TestCase):
    def test_mobile_path_is_mapped_without_changing_core_paths(self):
        mapping = {'mobile/': 'galaxy/mobile/'}
        self.assertEqual('galaxy/mobile/src/main/AndroidManifest.xml',
                         checks.product_path('mobile/src/main/AndroidManifest.xml', mapping))
        self.assertEqual('shared/build.gradle', checks.product_path('shared/build.gradle', mapping))

    def test_actual_product_contract(self):
        checks.check_product(checks.ROOT)

    def test_candidate_rejects_symlink_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.work').mkdir()
            (root / 'elsewhere').mkdir()
            (root / '.work/upstream-candidates').symlink_to(root / 'elsewhere', target_is_directory=True)
            with self.assertRaisesRegex(ValueError, '符号链接'):
                candidate_directory(root, 'v0.2.21', 'a' * 40)

    def test_candidate_is_new_directory_inside_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = candidate_directory(root, 'v0.2.21', 'a' * 40)
            self.assertEqual(root.resolve() / '.work/upstream-candidates/v0.2.21-aaaaaaaaaaaa', candidate)
            candidate.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, '已存在'):
                candidate_directory(root, 'v0.2.21', 'a' * 40)

    def test_only_release_tags_accepted(self):
        self.assertEqual('v0.2.21', validate_tag('v0.2.21'))
        for value in ('HEAD', 'main', '49bf3bc', '../v0.2.21', 'v0.2.21-beta'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_tag(value)


if __name__ == '__main__':
    unittest.main()


class RecordPatchesTest(unittest.TestCase):
    """补丁重建工具的差异生成规则；不读写真实产品与基线。"""

    def setUp(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import record_patches
        self.record = record_patches

    def test_new_text_file_becomes_full_addition(self):
        text = self.record.render('galaxy/common/src/main/java/A.kt', None, b'package a\nclass A\n')
        self.assertTrue(text.startswith('--- /dev/null\n+++ b/galaxy/common/src/main/java/A.kt\n@@ -0,0 +1,2 @@\n'))
        self.assertIn('+class A\n', text)

    def test_unchanged_content_has_no_diff(self):
        self.assertEqual('', self.record.render('common/A.kt', b'same\n', b'same\n'))

    def test_binary_suffix_and_non_utf8_use_marker(self):
        self.assertEqual('Binary files /dev/null and b/common/icon.png differ\n',
                         self.record.render('common/icon.png', None, b'\x89PNG\r\n\x1a\n'))
        self.assertEqual('Binary files a/common/blob.bin and b/common/blob.bin differ\n',
                         self.record.render('common/blob.bin', b'\x00\x01', b'\x00\x02'))

    def test_new_files_join_the_group_of_their_module(self):
        groups = {'P005': {'files': {'galaxy/common/src/main/java/A.kt': {}}},
                  'P002': {'files': {'galaxy/mobile/src/main/AndroidManifest.xml': {}}}}
        self.assertEqual('P005', self.record.group_for('galaxy/common/src/main/java/B.kt', groups))
        self.assertEqual('P002', self.record.group_for('galaxy/mobile/src/main/res/values/x.xml', groups))
