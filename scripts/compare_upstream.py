#!/usr/bin/env python3
"""按固定发布提交生成三方差异候选；只写.work，不覆盖产品/基线，不联网。"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from project_checks import product_path
from prepare_upstream import validate_tag

ROOT = Path(__file__).resolve().parents[1]


def archive(repo: Path, commit: str, selection: list[str]) -> dict[str, tuple[bytes, int]]:
    data = subprocess.check_output(['git', '-C', str(repo), 'archive', commit, *selection])
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as source:
        for member in source.getmembers():
            if member.isdir():
                continue
            name = Path(member.name)
            if not member.isfile() or name.is_absolute() or '..' in name.parts:
                raise ValueError('归档必须仅含安全的普通文件')
            result[member.name] = (source.extractfile(member).read(), member.mode)
    return result


def sha(data: bytes | None):
    return None if data is None else hashlib.sha256(data).hexdigest()


def compare(repo: Path, tag: str):
    validate_tag(tag)
    lock = json.loads((ROOT / 'tools/upstream/baseline-lock.json').read_text())
    registry = json.loads((ROOT / 'tools/upstream/patches.json').read_text())
    commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', '--verify',
        f'refs/tags/{tag}^{{commit}}'], text=True).strip()
    base = archive(repo, lock['commit'], lock['selection'])
    latest = archive(repo, commit, lock['selection'])
    assert {p: sha(v[0]) for p, v in base.items()} == lock['files'], '旧基线与锁文件不一致'
    local_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    changes = subprocess.check_output(['git', 'status', '--porcelain', '--', 'common', 'shared', 'galaxy',
        'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'], cwd=ROOT, text=True)
    if changes.strip():
        raise ValueError('产品源码尚未提交，先提交后再生成可复现三方候选')
    parent = ROOT / '.work/upstream-candidates'
    if (ROOT / '.work').is_symlink() or parent.is_symlink():
        raise ValueError('候选父目录不能是符号链接')
    output = parent / f'{tag}-{commit[:12]}-three-way-{local_commit[:12]}'
    output.mkdir(parents=True, exist_ok=False)
    rows = []
    with tempfile.TemporaryDirectory(dir=output) as tmp:
        tmp = Path(tmp)
        for source in sorted(base.keys() | latest.keys()):
            target = product_path(source, registry.get('pathMap', {}))
            path = ROOT / target
            if path.is_symlink():
                raise ValueError(f'拒绝产品符号链接: {target}')
            old = base.get(source, (None, 0))[0]
            new, mode = latest.get(source, (None, 0))
            local = path.read_bytes() if path.is_file() else None
            merged = local
            if local == new:
                kind = 'unchanged' if old == new else 'same-change'
            elif local == old:
                kind, merged = 'upstream-only', new
            elif new == old:
                kind = 'adaptation-only'
            elif local is None or new is None:
                kind = 'delete-modify-conflict'
            elif b'\0' in local or b'\0' in new or old is not None and b'\0' in old:
                kind = 'binary-conflict'
            else:
                for name, data in [('local', local), ('base', old or b''), ('upstream', new)]:
                    (tmp / name).write_bytes(data)
                result = subprocess.run(['git', 'merge-file', '-p', '-L', 'Galaxy', '-L', lock['tag'],
                    '-L', tag, str(tmp/'local'), str(tmp/'base'), str(tmp/'upstream')], capture_output=True)
                if result.returncode < 0 or result.returncode > 127:
                    raise ValueError(f'Git合并失败: {target}')
                merged = result.stdout
                kind = 'merged' if result.returncode == 0 else 'text-conflict'
            if merged is not None:
                dest = output / 'product' / target
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(merged)
                dest.chmod((path.stat().st_mode if path.is_file() else mode) & 0o777)
            rows.append(dict(source=source, path=target, kind=kind,
                baselineSha256=sha(old), adaptationSha256=sha(local), upstreamSha256=sha(new), candidateSha256=sha(merged)))
    # galaxy下产品类不参与覆盖；检查新核心文件与其同包同文件名碰撞。
    collisions = []
    for row in rows:
        if row['source'] in base or not row['path'].endswith('.kt'):
            continue
        candidate = output/'product'/row['path']
        if not candidate.is_file():
            continue
        package = re.search(r'^package\s+([^\s;]+)', candidate.read_text(), re.M)
        if not package:
            continue
        for custom in (ROOT/'galaxy').glob(f'*/src/main/java/{package[1].replace(".", "/")}/{candidate.name}'):
            if custom.relative_to(ROOT).as_posix() != row['path']:
                collisions.append(dict(upstream=row['path'], product=custom.relative_to(ROOT).as_posix()))
    report = dict(schemaVersion=1, baselineTag=lock['tag'], baselineCommit=lock['commit'],
        localCommit=local_commit,
        targetTag=tag, targetCommit=commit, counts=dict(Counter(r['kind'] for r in rows)),
        collisions=collisions, files=rows,
        status='只读三方候选；产品、基线、签名与参考HEAD均未更改；冲突需逐项审查')
    (output/'three-way-review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k != 'files'}, ensure_ascii=False, indent=2))
    print('候选:', output)
    print('冲突:', '\n'.join(r['path'] for r in rows if 'conflict' in r['kind']))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--repo', type=Path, default=ROOT/'references/upstream/DiPlay')
    args = parser.parse_args()
    compare(args.repo.resolve(), args.tag)
