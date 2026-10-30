#!/usr/bin/env python3
"""把本轮Android原生组件渲染测试产物复制到离线审阅页；不生成替代示意图。"""
from pathlib import Path
import json
import shutil
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'galaxy/cluster/build/screenshots/catalog'
target=ROOT/'docs/design/cluster-editor-v1/catalog'
styles=json.loads((source/'catalog.json').read_text())
if len(styles)!=72 or len({x['type'] for x in styles})!=24:
    raise SystemExit('缺少完整24类/72个真实组件预览')
target.mkdir(parents=True,exist_ok=True)
for name in ('catalog.json','properties.json',*(x[mode] for x in styles for mode in ('day','night'))):
    if not (source/name).is_file(): raise SystemExit('缺少Android实际渲染图: '+name)
    shutil.copy2(source/name,target/name)
print('已更新72个样式/144张日夜原生绘制图和精确属性表。')
