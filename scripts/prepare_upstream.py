#!/usr/bin/env python3
"""只导出本地官方发布标签到新候选目录；不fetch、不覆盖产品或切换参考HEAD。"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import io
import json
import re
import subprocess
import tarfile
from project_checks import product_path

ROOT = Path(__file__).resolve().parents[1]


def validate_tag(tag):
    if not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
        raise ValueError('必须指定正式发布标签，例如v0.2.21；不接受main、HEAD或任意SHA')
    return tag


def candidate_directory(root, tag, commit):
    """纯路径预检；拒绝候选父目录的符号链接，不创建或删除任何目录。"""
    validate_tag(tag)
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('发布提交必须是完整40位SHA')
    root = root.resolve()
    work = root / '.work'
    parent = work / 'upstream-candidates'
    if work.is_symlink() or parent.is_symlink():
        raise ValueError('候选父目录不能是符号链接')
    candidate = parent / f'{tag}-{commit[:12]}'
    if not candidate.resolve().is_relative_to(root):
        raise ValueError('候选路径不能逃出项目目录')
    if candidate.exists() or candidate.is_symlink():
        raise ValueError('候选已存在，请审查已有候选；工具不会自动覆盖或删除')
    return candidate


def export_candidate(repo, tag):
    validate_tag(tag)
    commit = subprocess.check_output(
        ['git', '-C', str(repo), 'rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}'], text=True,
    ).strip()
    lock = json.loads((ROOT / 'tools/upstream/baseline-lock.json').read_text())
    registry = json.loads((ROOT / 'tools/upstream/patches.json').read_text())
    archive = subprocess.check_output(['git', '-C', str(repo), 'archive', commit, *lock['selection']])
    # 目标只在本项目.work内，存在即拒绝；不清理、移动或复用先前候选。
    candidate = candidate_directory(ROOT, tag, commit)
    entries = []
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for entry in source.getmembers():
            if entry.isdir():
                continue
            path = PurePosixPath(entry.name)
            if not entry.isfile() or path.is_absolute() or '..' in path.parts:
                raise ValueError('发布归档包含不安全路径或非普通文件')
            if path.suffix.lower() in {'.pk8', '.p7b', '.key', '.pem', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aar', '.so'}:
                raise ValueError('候选包含需单独审查的凭据或二进制')
            if any(part in {'.private', 'offline-mfi'} for part in path.parts):
                raise ValueError('候选包含私有材料目录')
            entries.append((entry, source.extractfile(entry).read()))
    candidate.mkdir(parents=True)
    hashes = {}
    for entry, data in entries:
        target = candidate / product_path(entry.name, registry.get('pathMap', {}))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(entry.mode & 0o777)
        hashes[entry.name] = hashlib.sha256(data).hexdigest()
    # 只检查旧补丁能否应用；不擅自把适配或锁文件升级到新版本。
    patches = registry['patches']
    checks = []
    for patch in patches:
        result = subprocess.run(
            ['git', 'apply', '--check', str(ROOT / patch['patch'])],
            cwd=candidate, capture_output=True, text=True,
        )
        checks.append({'id': patch['id'], 'applies': result.returncode == 0})
    result = {'tag': tag, 'commit': commit, 'files': hashes, 'pathMap': registry.get('pathMap', {}), 'patchChecks': checks,
              'status': '只读候选；未修改产品、版本锁定或参考HEAD；未构建、未作回归'}
    (candidate / 'candidate-review.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'已生成独立候选: {candidate.relative_to(ROOT)}')
    print(f'发布提交: {commit}；{len(entries)}份文件；补丁适用性: {checks}')
    print('先审查差异、许可、源集冲突与回归，再单独迁移；本工具不更新产品。')
    return candidate


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--repo', type=Path, default=ROOT / 'references/upstream/DiPlay')
    args = parser.parse_args()
    try:
        export_candidate(args.repo.resolve(), args.tag)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'候选生成失败: {error}\n')
