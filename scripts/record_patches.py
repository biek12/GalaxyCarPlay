#!/usr/bin/env python3
"""按当前产品源码重建补丁登记（离线，不联网，不改产品源码）。

用途：每次产品层改动（新增/修改/删除文件）后，把 `tools/upstream/patches.json` 与
`tools/upstream/patches/*.patch` 重新对齐到真实文件状态，避免 `project_checks.py`
报"未登记的核心新增文件"或留下指向已删除文件的假登记。

规则：
1. 已登记且内容未变的文件：差异段原样保留，不产生噪声改动。
2. 已登记但内容已变的文件：按基线重算该文件的差异段（二进制文件只写 Binary 标记）。
3. 新增产品文件：追加到所属补丁组末尾，`baselineSha256` 记为 null（不伪造上游已存在）。
4. 已登记但源码中已删除的文件：从补丁段移除，记入 `deletions`，并在
   `galaxy-import.json` 中把导入动作改为"导入后删除"。

默认 dry-run；加 `--write` 才落盘。
"""
from __future__ import annotations

import argparse
from pathlib import Path
import difflib
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / 'tools/upstream/patches.json'
IMPORT_LOG = ROOT / 'tools/upstream/galaxy-import.json'
LOCK = ROOT / 'tools/upstream/baseline-lock.json'
BASELINE = ROOT / 'references/baselines' / f"DiPlay-{json.loads(LOCK.read_text())['tag']}"
GENERATED_DIRS = {'build', '.gradle', '.cxx', '.externalNativeBuild', '__pycache__'}
MODULE_DIRS = ('common', 'shared', 'galaxy/mobile', 'galaxy/common', 'galaxy/contracts', 'galaxy/client', 'galaxy/service', 'galaxy/cluster')
BINARY_SUFFIXES = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.mp4', '.pcm', '.wav', '.ttf', '.otf', '.so', '.jar'}
HEADER_PATH = re.compile(r'^\+\+\+ b/(.+)$', re.M)
BINARY_PATH = re.compile(r'^Binary files .+ and b/(.+) differ$', re.M)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def decode(data: bytes) -> str | None:
    if b'\x00' in data:
        return None
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError:
        return None


def reverse_product_path(baseline_path: str, path_map: dict[str, str]) -> str:
    """基线路径映射为产品路径（`mobile/x` → `galaxy/mobile/x`）。"""
    for source, target in path_map.items():
        if baseline_path.startswith(source):
            return target + baseline_path[len(source):]
    return baseline_path


def baseline_relative(product: str, path_map: dict[str, str]) -> str:
    """产品路径反查基线路径（`galaxy/mobile/x` → `mobile/x`）。"""
    for source, target in path_map.items():
        if product.startswith(target):
            return source + product[len(target):]
    return product


def render(product: str, before: bytes | None, after: bytes) -> str:
    """生成单个文件的统一差异段，格式与既有补丁一致。"""
    old_text = None if before is None else decode(before)
    new_text = decode(after)
    # 新增文件（基线不存在）不是二进制：仍要输出完整的 + 行差异。
    binary = new_text is None or (before is not None and old_text is None)
    if binary or Path(product).suffix.lower() in BINARY_SUFFIXES:
        source = '/dev/null' if before is None else f'a/{product}'
        return f'Binary files {source} and b/{product} differ\n'
    source = '/dev/null' if before is None else f'a/{product}'
    old_lines = [] if before is None else old_text.splitlines(keepends=True)
    diff = difflib.unified_diff(old_lines, new_text.splitlines(keepends=True),
                                fromfile=source, tofile=f'b/{product}')
    rendered = []
    for line in diff:
        # difflib does not emit Git's no-newline marker. Without it, the next diff
        # header can concatenate to the final content line and corrupt the patch.
        if line.endswith('\n'):
            rendered.append(line)
        else:
            rendered.append(line + '\n\\ No newline at end of file\n')
    return ''.join(rendered)


def product_files() -> list[str]:
    """当前产品层源码/资源路径（与 project_checks 的登记范围一致）。"""
    found: list[str] = ['gradle/libs.versions.toml']
    for directory in MODULE_DIRS:
        base = ROOT / directory
        if not base.is_dir():
            continue
        for path in sorted(base.rglob('*')):
            # 生成目录按模块内相对路径判断：模块根下的 build/、.gradle/ 等产物不是产品源码。
            if path.relative_to(base).parts[0] in GENERATED_DIRS:
                continue
            if path.name != '.DS_Store' and path.is_file() and not path.is_symlink():
                found.append(path.relative_to(ROOT).as_posix())
    return found


def group_for(path: str, groups: dict[str, dict]) -> str:
    """新增文件归组：同模块前缀已有的多数归属，缺省归 P005（迁移组）。"""
    prefix = path.split('/', 1)[0] + '/'
    if path.startswith('galaxy/'):
        prefix = '/'.join(path.split('/')[:2]) + '/'
    tally: dict[str, int] = {}
    for group_id, group in groups.items():
        for registered in group['files']:
            registered_prefix = '/'.join(registered.split('/')[:2]) + '/' if registered.startswith('galaxy/') else registered.split('/', 1)[0] + '/'
            if registered_prefix == prefix:
                tally[group_id] = tally.get(group_id, 0) + 1
    if tally:
        return max(sorted(tally), key=lambda group_id: tally[group_id])
    return 'P005' if path.startswith('galaxy/') else 'P002'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='落盘；缺省为 dry-run')
    parser.add_argument('--reason', default='产品源码已删除', help='删除项记录的理由')
    args = parser.parse_args()

    registry = json.loads(REGISTRY.read_text())
    path_map = registry.get('pathMap', {})
    groups = {patch['id']: patch for patch in registry['patches']}
    registered = {path: group_id for group_id, group in groups.items() for path in group['files']}
    present = product_files()
    present_set = set(present)

    # 基线锁定里"未被产品改动"的文件不进入补丁登记；只有新增或摘要变化的才登记。
    lock = json.loads(LOCK.read_text())
    baseline_hashes = {reverse_product_path(path, path_map): sha for path, sha in lock['files'].items()}
    additions = [path for path in present
                 if path not in registered
                 and (path not in baseline_hashes or digest((ROOT / path).read_bytes()) != baseline_hashes[path])]
    # 删除项以磁盘真实存在性判断（settings.gradle.kts 等根级登记文件不在模块扫描范围内）。
    removals = [path for path in registered if not (ROOT / path).is_file()]
    changes: list[str] = []
    report: list[str] = []

    # 1) 逐组按登记顺序重算差异段：未变的文件重算结果与既有段落逐字节一致，
    #    只有真正改动的文件才会改变补丁内容。删除项不再出现在补丁里。
    new_blocks: dict[str, list[tuple[str, str]]] = {}
    for group_id, group in groups.items():
        kept: list[tuple[str, str]] = []
        for path, entry in group['files'].items():
            if path in removals:
                continue
            product = ROOT / path
            current = digest(product.read_bytes())
            if current != entry['productSha256']:
                changes.append(path)
            before = baseline_relative(path, path_map)
            source = BASELINE / before
            kept.append((path, render(path, source.read_bytes() if source.is_file() else None, product.read_bytes())))
        new_blocks[group_id] = kept

    # 2) 新增文件追加到所属组末尾。
    for path in sorted(additions):
        target = group_for(path, {gid: {**groups[gid], 'files': dict(new_blocks[gid])} for gid in groups})
        before = baseline_relative(path, path_map)
        source = BASELINE / before
        new_blocks[target].append((path, render(path, source.read_bytes() if source.is_file() else None,
                                                (ROOT / path).read_bytes())))

    # 3) 更新登记表。
    for group_id, group in groups.items():
        hashes = {}
        for path, _ in new_blocks[group_id]:
            before = baseline_relative(path, path_map)
            source = BASELINE / before
            hashes[path] = {
                'baselineSha256': digest(source.read_bytes()) if source.is_file() else None,
                'productSha256': digest((ROOT / path).read_bytes()),
            }
        group['files'] = hashes
        text = ''.join(block for _, block in new_blocks[group_id])
        group['sha256'] = digest(text.encode())
        if args.write:
            (ROOT / group['patch']).write_text(text)

    deletions = [entry for entry in registry.get('deletions', []) if entry.get('path') not in present_set]
    known = {entry['path'] for entry in deletions}
    for path in removals:
        if path in known:
            continue
        deployments = groups[registered[path]]['files'].get(path, {})
        deletions.append({'path': path, 'group': registered[path],
                          'baselineSha256': deployments.get('baselineSha256'),
                          'removedProductSha256': deployments.get('productSha256'),
                          'reason': args.reason})
    registry['deletions'] = sorted(deletions, key=lambda entry: entry['path'])
    if args.write:
        REGISTRY.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + '\n')

    # 4) 导入来源清单同样不能再把已删除文件记为"导入"：以磁盘存在性为准，
    #    即使该文件从未进入补丁登记（如与上游同名资源去重删除）。
    if IMPORT_LOG.is_file():
        imported = json.loads(IMPORT_LOG.read_text())
        vanished = {entry['path'] for entry in imported.get('files', []) if not (ROOT / entry['path']).is_file()}
        touched = 0
        for entry in imported.get('files', []):
            if entry['path'] in vanished and not entry.get('action', '').startswith('导入后删除'):
                entry['action'] = f'导入后删除（{args.reason}）'
                touched += 1
        if touched and args.write:
            IMPORT_LOG.write_text(json.dumps(imported, ensure_ascii=False, indent=2) + '\n')
        report.append(f'导入清单标注删除: {touched} 份')

    report.insert(0, f'产品文件 {len(present)} 份；新增 {len(additions)}；删除 {len(removals)}；内容变化 {len(changes)}')
    for path in additions:
        report.append(f'  + {path}')
    for path in removals:
        report.append(f'  - {path}')
    for path in changes:
        report.append(f'  ~ {path}')
    report.append('dry-run：未写盘（加 --write 落盘）' if not args.write else '已写盘：补丁登记与差异段已更新')
    print('\n'.join(report))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, OSError, ValueError) as error:
        print(f'登记失败: {error}', file=sys.stderr)
        raise SystemExit(1)
