#!/usr/bin/env python3
"""离线检查核心漂移、产品源集和身份边界；不执行Gradle、不访问网络或设备。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
CREDENTIAL_SUFFIXES = {'.pk8', '.p7b', '.key', '.pem', '.p12', '.pfx', '.jks', '.keystore'}
GENERATED_DIRS = {'build', '.gradle', '.cxx', '.externalNativeBuild', '__pycache__'}
MODULE_DIRS = ('common', 'shared', 'galaxy/mobile', 'galaxy/common', 'galaxy/contracts', 'galaxy/client', 'galaxy/service', 'galaxy/cluster')


def product_path(path, mapping):
    for source, target in mapping.items():
        if path.startswith(source):
            return target + path[len(source):]
    return path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_files(root):
    for path in root.rglob('*'):
        # 仅排除模块根输出目录；src中的同名包/资产目录仍是真实输入，不能绕过审查。
        relative = path.relative_to(root)
        if relative.parts[0] not in GENERATED_DIRS and path.name != '.DS_Store' and path.is_file():
            yield path


def check_core(root):
    lock = json.loads((root / 'tools/upstream/baseline-lock.json').read_text())
    registry = json.loads((root / 'tools/upstream/patches.json').read_text())
    expected = {product_path(path, registry.get('pathMap', {})): sha for path, sha in lock['files'].items()}
    # 删除项必须真的不在源码里，且不能再同时出现在某组补丁的修改清单中。
    deleted = set()
    for entry in registry.get('deletions', []):
        path = entry['path']
        assert path not in deleted, f'删除项重复登记: {path}'
        assert not (root / path).exists(), f'已登记删除的文件仍存在: {path}'
        deleted.add(path)
    registered = set()
    for patch in registry['patches']:
        patch_file = root / patch['patch']
        assert patch_file.is_file(), f"补丁不存在: {patch['patch']}"
        assert digest(patch_file) == patch['sha256'], f"补丁摘要不符: {patch['patch']}"
        parsed = subprocess.run(['git', 'apply', '--stat', str(patch_file)], cwd=root,
                                capture_output=True, text=True)
        assert parsed.returncode == 0, f"补丁格式无效: {patch['patch']}: {parsed.stderr.strip()}"
        for path, entry in patch['files'].items():
            assert path not in registered, f'同一核心文件重复登记: {path}'
            # 新增产品文件以null原始摘要登记，不伪造上游已存在该文件。
            assert expected.get(path) == entry['baselineSha256'], f'基线摘要不符: {path}'
            assert path not in deleted, f'同一文件既登记修改又登记删除: {path}'
            registered.add(path)
            expected[path] = entry['productSha256']
    for path, sha in expected.items():
        assert (root / path).is_file(), f'核心文件丢失: {path}'
        assert digest(root / path) == sha, f'未登记的核心漂移: {path}'
    # 新增核心文件也必须被审查，不能只检查已有锁文件。
    for directory in (*MODULE_DIRS, 'mobile'):
        for path in source_files(root / directory):
            relative = path.relative_to(root).as_posix()
            assert relative in expected, f'未登记的核心新增文件: {relative}'
    return len(lock['files']), len(registered)


def check_product(root):
    settings = (root / 'settings.gradle.kts').read_text()
    assert 'rootProject.name = "GalaxyPlay"' in settings
    assert set(re.findall(r'":([a-z-]+)"', settings)) == {'shared', 'common', 'mobile', 'auto-contracts', 'auto-client', 'service', 'cluster'}
    build = (root / 'galaxy/mobile/build.gradle.kts').read_text()
    assert 'applicationId = "com.ecarx.carplay"' in build
    assert not re.search(r'^\s*applicationIdSuffix\s*=', build, re.M)
    assert not (root / 'mobile').exists(), '上游根mobile壳必须剔除'
    assert 'project(":mobile").projectDir = file("galaxy/mobile")' in settings
    common = (root / 'common/build.gradle.kts').read_text()
    assert 'galaxy/common/src/main/java' in common and 'galaxy/common/src/test/java' in common
    assert 'setRoot' not in common, '保留上游测试，不覆盖核心源集'
    assert 'sourceSets {' not in build, '应用自身使用标准目录，不再建立第二入口'
    assert (root / 'galaxy/mobile/src/main/java/com/ecarx/carplay/GalaxyPlayApplication.kt').is_file()
    assert (root / 'galaxy/common/src/main/java/com/ecarx/carplay/product/GalaxyStartupDefaults.kt').is_file()
    assert (root / 'galaxy/common/src/test/java/com/ecarx/carplay/product/GalaxyStartupDefaultsTest.kt').is_file()
    manifest = ET.parse(root / 'galaxy/mobile/src/main/AndroidManifest.xml').getroot()
    application = manifest.find('application')
    assert application.get(ANDROID + 'name') == 'com.ecarx.carplay.GalaxyPlayApplication'
    assert application.get(ANDROID + 'label') == '@string/galaxy_app_name'
    resources = ET.parse(root / 'galaxy/mobile/src/main/res/values/product.xml').getroot()
    label = resources.find("string[@name='galaxy_app_name']")
    assert label.text == 'CarPlay' and label.get('translatable') == 'false'
    controller = (root / 'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt').read_text()
    assert 'com.apple.syslog_relay' not in controller
    assert 'carplay-lab-phone-diagnostics' not in controller
    for directories in (('common', 'galaxy/common'), ('shared',), ('galaxy/mobile',), ('galaxy/contracts',), ('galaxy/client',), ('galaxy/service',), ('galaxy/cluster',)):
        declarations = set()
        for directory in directories:
            for path in (root / directory / 'src/main/java').rglob('*'):
                if path.suffix in {'.java', '.kt'}:
                    package = re.search(r'^package\s+([\w.]+)', path.read_text(), re.M)
                    if package:
                        name = package[1] + '.' + path.stem
                        assert name not in declarations, f'模块中同路径同名源码: {name}'
                        declarations.add(name)
    version = dict(line.split('=', 1) for line in (root / 'gradle/galaxyplay-version.properties').read_text().splitlines()
                   if line and not line.startswith('#'))
    lock = json.loads((root / 'tools/upstream/baseline-lock.json').read_text())
    assert version['diplayCoreCommit'] == lock['commit']
    assert 'v' + version['diplayCoreVersion'] == lock['tag']
    assert (root / 'common/src/test/java').is_dir() and (root / 'shared/src/test/java').is_dir()
    for module in MODULE_DIRS:
        for path in source_files(root / module):
            assert path.suffix.lower() not in CREDENTIAL_SUFFIXES, f'源码目录含凭据: {path.name}'


def main():
    try:
        files, patches = check_core(ROOT)
        check_product(ROOT)
    except (AssertionError, KeyError, OSError, ValueError) as error:
        print(f'检查失败: {error}', file=sys.stderr)
        return 1
    deletions = len(json.loads((ROOT / 'tools/upstream/patches.json').read_text()).get('deletions', []))
    print(f'检查通过：{files}份锁定文件，{patches}份已登记差异，{deletions}份已登记删除；产品身份、源集和无凭据边界通过。')
    print('这是离线源码契约检查，不代表Android编译、手机连接或实车验收通过。')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
