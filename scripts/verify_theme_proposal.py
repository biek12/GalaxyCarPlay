#!/usr/bin/env python3
"""本机核验主题设计草案、引用与静态证据；不加载主题、不构建APK、不连接设备。"""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOCUMENTS = [ROOT / 'docs/多投屏主题系统方案-20261030.md', ROOT / 'docs/EVCC3.3.3流畅度与主题补充-20261030.md']
EVIDENCES = [ROOT / 'tools/evidence/cluster-theme-research-20261030.json', ROOT / 'tools/evidence/evcc333-theme-pacing-20261030.json']
TYPES = {'map-viewport', 'needle-gauge', 'value-text', 'track-info', 'lyrics', 'media-progress', 'artwork', 'card', 'text', 'bar', 'ring'}
FIELDS = {'navigation.map', 'vehicle.displaySpeed', 'vehicle.gear', 'vehicle.soc', 'media.track', 'media.lyrics', 'media.playback', 'media.artwork'}
PRESETS = {'needle-sweep', 'follow-value', 'lyrics-scroll', 'crossfade'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_xml(path):
    text = path.read_text()
    require('<!DOCTYPE' not in text and '<!ENTITY' not in text, f'{path.name}: 不应有外部声明')
    theme = ET.fromstring(text)
    require(theme.tag == 'cluster-theme' and theme.get('status') == 'draft', f'{path.name}: 须标记草案')
    require(theme.get('schemaVersion') == '1', f'{path.name}: 版本不符')
    width, height = int(theme.attrib['designWidth']), int(theme.attrib['designHeight'])
    require((width, height) == (1920, 532), f'{path.name}: 非当前设计尺寸')
    all_ids = [n.get('id') for n in theme.iter() if n.get('id')]
    require(len(all_ids) == len(set(all_ids)), f'{path.name}: ID重复')
    animations = {n.attrib['id'] for n in theme.findall('animations/animation')}
    styles = {n.attrib['id'] for n in theme.findall('styles/component-style')}
    palettes = {n.attrib['id'] for n in theme.findall('styles/palette')}
    scene = theme.find('scene')
    require(scene is not None, f'{path.name}: 缺少scene')
    appearance = theme.find('appearance')
    require(appearance is not None and appearance.get('mode') in {'auto', 'day', 'night'}, f'{path.name}: 日夜选择无效')
    require(appearance.get('day') in palettes and appearance.get('night') in palettes, f'{path.name}: 日夜引用无效')
    require(appearance.get('shareDayNight') in {'true', 'false'}, f'{path.name}: shareDayNight无效')
    policy = theme.find('render-policy')
    require(policy is not None and policy.get('mode') in {'fixed-cap', 'content-aware'}, f'{path.name}: 帧策略无效')
    require(1 <= int(policy.attrib['animationFps']) <= int(policy.attrib['maxFps']) <= 60, f'{path.name}: 帧率范围无效')
    require(policy.get('mapFps') == 'source' and policy.get('static') == 'on-change' and
            int(policy.attrib['keepAliveMs']) == 1000, f'{path.name}: 草案刷新策略无效')
    components = {n.attrib['id'] for n in scene.findall('component')}
    for override in theme.findall('styles/override'):
        require(override.get('component') in components and override.get('mode') in {'day', 'night'},
                f'{path.name}: 组件覆盖引用无效')
        require(set(override.attrib) <= {'component', 'mode', 'foreground', 'strokeWidth', 'fontScale'},
                f'{path.name}: 不支持的覆盖属性')
        if override.get('foreground') is not None:
            require(re.fullmatch(r'#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?', override.attrib['foreground']) is not None,
                    f'{path.name}: 覆盖颜色无效')
    for animation in theme.findall('animations/animation'):
        require(animation.get('preset') in PRESETS and int(animation.attrib['durationMs']) > 0,
                f'{path.name}: 动画预设/时长无效')
        keys = [float(k.attrib['at']) for k in animation.findall('keyframe')]
        require(not keys or (keys == sorted(set(keys)) and keys[0] == 0 and keys[-1] == 1),
                f'{path.name}: 关键帧顺序/端点无效')
    for node in scene.findall('component'):
        require(node.get('type') in TYPES, f'{path.name}: 未定义的草案组件')
        x, y, w, h = [float(node.attrib[k]) for k in ('x', 'y', 'width', 'height')]
        require(x >= 0 and y >= 0 and w > 0 and h > 0 and x + w <= width and y + h <= height,
                f'{path.name}: {node.get("id")}越界')
        if node.get('type') == 'card':
            require(node.get('bind') is None, f'{path.name}: card不应读取字段')
        elif node.get('type') == 'text' and node.get('text') is not None:
            require(node.get('bind') is None, f'{path.name}: 静态text不应同时绑定字段')
        else:
            require(node.get('bind', node.get('source')) in FIELDS, f'{path.name}: 字段引用无效')
        for value in node.attrib.values():
            if value.startswith('@'):
                require(all(value[1:] in palette.attrib for palette in theme.findall('styles/palette')),
                        f'{path.name}: 日夜token缺失 {value}')
        if node.get('type') in {'bar', 'ring', 'needle-gauge'}:
            require(float(node.attrib['min']) < float(node.attrib['max']), f'{path.name}: 数值范围无效')
        for key, choices in [('style', styles), ('valueAnimation', animations), ('transition', animations)]:
            require(node.get(key) is None or node.get(key) in choices, f'{path.name}: {key}引用无效')
        for event in node.findall('on'):
            require(event.get('animation') in animations, f'{path.name}: 事件动画引用无效')


def main():
    examples = sorted((ROOT / 'docs/examples').glob('cluster-theme-*-v1.xml'))
    require(len(examples) == 4, '应有四份主题草案')
    for path in examples:
        verify_xml(path)
    link_count = file_count = comparisons = 0
    for document in DOCUMENTS:
        links = re.findall(r'\]\((<[^>]+>|[^)\n]+)\)', document.read_text())
        link_count += len(links)
        for raw in links:
            target = raw.strip('<>')
            if target.startswith(('https://', 'http://')):
                continue
            name, _, anchor = target.partition('#')
            path = document.parent / name
            require(path.is_file(), f'文档引用缺失: {name}')
            if anchor.startswith('L'):
                require(1 <= int(anchor[1:]) <= len(path.read_text().splitlines()), f'引用行号越界: {target}')
    for source in EVIDENCES:
        evidence = json.loads(source.read_text())
        file_count += len(evidence['files'])
        for item in evidence['files']:
            path = ROOT / item['path']
            data = path.read_bytes()
            require(hashlib.sha256(data).hexdigest() == item['sha256'], f'证据文件已变化: {path.name}')
            lines = data.decode().splitlines()
            for anchor in item['anchors']:
                require(anchor['symbol_or_key'] in lines[anchor['line'] - 1], f'证据锚点错位: {path.name}')
        for comparison in evidence.get('comparisons', []):
            comparisons += 1
            old, new = [(ROOT / comparison[k]).read_text() for k in ('old', 'new')]
            old, new = [re.sub(r'/\*.*?\*/', '', text, flags=re.S) for text in (old, new)]
            if comparison['kind'].startswith('ordered numeric'):
                pattern = r'"(?:\\.|[^"\\])*"|(?<![\w.])-?\d+(?:\.\d+)?[fFdDlL]?(?![\w.])'
                old, new = [re.sub(r'//[^\n]*', '', text) for text in (old, new)]
                left, right = re.findall(pattern, old), re.findall(pattern, new)
                equal = left == right
                digest = hashlib.sha256(json.dumps(left, ensure_ascii=False).encode()).hexdigest()
                require(digest == comparison['sequence_sha256'], '主题字面量序列已变化')
            else:
                left, right = [re.findall(r'"(?:\\.|[^"\\])*"', text) for text in (old, new)]
                equal = set(left) == set(right)
            require(len(left) == comparison['old_count'] and len(right) == comparison['new_count'] and
                    equal == comparison['equal'], '版本静态对照结果已变化')
    print(f'主题设计草案验证通过：{len(examples)}份XML、{link_count}处文档引用、{file_count}份静态源文件摘要与锚点、{comparisons}组版本对照。')
    print('仅为格式/证据核验；没有主题运行时、APK构建或实车验证。')


if __name__ == '__main__':
    main()
