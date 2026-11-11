# -*- coding: utf-8 -*-
"""
生成 Cluster 双模式 D1 关键页静态稿（SVG）与画册数据清单。

用法：python3 tools/generate_pages.py
仅写本设计目录下的 pages/，不触碰 galaxy/、不编译、不连设备。
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PAGES_DIR = ROOT / "pages"
sys.path.insert(0, str(HERE))

from svg_kit import (  # noqa: E402
    W, H, CLUSTER_W, CLUSTER_H, DP, Svg, TOKENS,
    STATUS_H, TITLE_H, DOCK_H, NAV_W, CONTENT_PAD, ROW_H, CARD_R,
    BTN_H, BTN_H_COMPACT, INPUT_H, BORDER_W,
    page_chrome, card, section_title, setting_row, badge, button, chip,
    segmented, notice, cluster_base,
)

PAGES = []


def anno(x, y, title, text):
    return {"x": x, "y": y, "title": title, "text": text}


def register(pid, title, group, theme, fn, annotations, specs,
             canvas="console", size=None):
    PAGES.append({
        "id": pid, "title": title, "group": group, "theme": theme,
        "file": f"pages/{pid}-{theme}.svg", "canvas": canvas, "size": size,
        "annotations": annotations, "specs": specs, "fn": fn,
    })


# ---------------------------------------------------------------------------
# 辅助绘制
# ---------------------------------------------------------------------------
def input_box(s, x, y, w, label, value, h=INPUT_H):
    # label 基线保持在卡片内（不压到卡片上边缘）
    s.text(x, y - 12, label, role="DESCRIPTION", color="muted")
    s.rect(x, y, w, h, "control", CARD_R)
    s.tc(x + 26, y + h / 2, value, role="VALUE", color="text")


def tab_bar(s, y, labels, active=0, h=100):
    s.rect(0, y, W, h, "control")
    seg = W / len(labels)
    for i, lb in enumerate(labels):
        if i == active:
            s.rect(i * seg + 24, y + 12, seg - 48, h - 24, "accent", CARD_R)
        s.tc(i * seg + seg / 2, y + h / 2, lb, role="VALUE",
             color="primary_text" if i == active else "muted", anchor="middle")
    s.line(0, y + h, W, y + h, "divider", BORDER_W)


def mode_bar(s, y, current, entries, active=-1, h=100):
    s.rect(0, y, W, h, "surface")
    s.line(0, y + h, W, y + h, "divider", BORDER_W)
    s.tc(42, y + h / 2, "当前模式", role="DESCRIPTION", color="muted")
    s.tc(190, y + h / 2, current, role="LABEL", color="accent")
    x = W - 42
    for i, lb in reversed(list(enumerate(entries))):
        tw = max(190, len(lb) * 33 + 56)
        x -= tw
        chip(s, x, y + 12, lb, active=(i == active), w=tw, h=h - 24)
        x -= 16


def selection_frame(s, x, y, w, h):
    s.rect(x, y, w, h, "none", 3, stroke="accent", sw=3, extra='stroke-dasharray="10 6"')
    for px, py in ((x, y), (x + w, y), (x, y + h), (x + w, y + h)):
        s.rect(px - 7, py - 7, 14, 14, "accent", 2)


def region_tags(s, x, y, tags):
    cx = x
    for lb, kind in tags:
        cx += badge(s, cx, y, lb, kind, max(190, len(lb) * 25 + 44), 46) + 16


# ---------------------------------------------------------------------------
# A 方案 · 四入口（总览 / 原厂增强 / 主题 / 设置）—— Codex 推荐
# ---------------------------------------------------------------------------
NAV_A = [("overview", "总览"), ("oem", "原厂增强"),
         ("theme", "主题"), ("settings", "设置")]


def a_overview(s, theme):
    x, top, right, bottom = page_chrome(
        s, theme, with_nav=True, nav_items=NAV_A, selected=0,
        title="总览", right=[("info", "诊断")])
    w = right - x
    cy = top

    # 主状态卡
    card(s, x, cy, w, 700, theme)
    s.text(x + 35, cy + 52, "当前显示", role="SECTION_TITLE", color="muted")
    badge(s, right - 35 - 160, cy + 20, "运行中", "connected", 160)
    s.text(x + 35, cy + 112, "夜间极光 · 原厂增强", role="PAGE_TITLE", color="text")
    s.text(x + 35, cy + 158,
           "模式：原厂增强 · 已应用 revision 12 · 端回执 12:41:03",
           role="DESCRIPTION", color="muted")
    pw = w - 70
    ph, _ = cluster_base(s, x + 35, cy + 180, pw, "night", "music")
    sy = cy + 180 + ph + 30
    s.circle(x + 48, sy, 12, "connected")
    s.tc(x + 74, sy, "仪表已显示 · 来自目标端回执", role="FEEDBACK", color="connected")
    s.tc(right - 35 - 300, sy, "1920 × 532 等比预览", role="DESCRIPTION",
         color="disabled", anchor="end")
    badge(s, right - 35 - 270, sy - 24, "草稿未应用", "warning", 270)
    by = cy + 700 - 28 - BTN_H
    bw = (w - 2 * 28)
    button(s, x, by, 400, BTN_H, "应用到仪表", True, theme, icon="play")
    button(s, x + 428, by, 360, BTN_H, "恢复原厂显示", False, theme,
           danger=True, icon="restore")
    button(s, x + 816, by, bw - 816, BTN_H, "停止", False, theme)

    # 当前方案
    section_title(s, x, cy + 726, "当前方案", "保存 ≠ 已应用")
    y2 = cy + 780
    card(s, x, y2, w, 3 * ROW_H, theme)
    yy = y2
    yy += setting_row(s, x, yy, w, "显示模式", "原厂增强",
                      "完整自定义 / 原厂增强，切换需先撤销旧输出", accent_value=True)
    yy += setting_row(s, x, yy, w, "音乐", "四行歌词",
                      "原厂媒体链 · iPhone 原始 NowPlaying")
    setting_row(s, x, yy, w, "背景", "本地图片",
                "与原厂 / CarPlay 地图 / 本机地图分别保存", last=True)

    # 元素状态
    section_title(s, x, y2 + 3 * ROW_H + 26, "元素状态", "逐项来自端回执")
    y3 = y2 + 3 * ROW_H + 78
    card(s, x, y3, w, 2 * ROW_H, theme)
    yy = y3
    yy += setting_row(s, x, yy, w, "音乐节点", None,
                      "原对象已保留 · 撤销时可还原", icon="music",
                      status=("已提交", "connected"))
    setting_row(s, x, yy, w, "背景纹理", None,
                "等待首帧 · 不自动切换其他来源", icon="image",
                status=("等待来源", "warning"), last=True)


def a_oem(s, theme):
    x, top, right, bottom = page_chrome(
        s, theme, with_nav=False, back=True, title="原厂增强",
        right=[("restore", "全部恢复"), ("save", "应用")])
    w = right - x

    notice(s, x, top, w,
           "预览为演示数据；实际状态只认目标端回执，不从开关值推导“已显示”。",
           "info", 96)

    y1 = top + 120
    card(s, x, y1, w, 440, theme)
    s.text(x + 35, y1 + 46, "可修改区域 · 1920 × 532", role="SECTION_TITLE", color="muted")
    region_tags(s, right - 35 - 3 * 240 - 32, y1 + 16,
                [("音乐 280px", "accent"), ("背景层", "accent"), ("原厂保留", "muted")])
    cw = w - 70
    ch, _ = cluster_base(s, x + 35, y1 + 70, cw, "night", "music")

    section_title(s, x, y1 + 466, "元素", "音乐与背景分别启停 / 分别恢复")
    y2 = y1 + 518
    card(s, x, y2, w, 3 * ROW_H, theme)
    yy = y2
    yy += setting_row(s, x, yy, w, "音乐 · 四行歌词", "已启用",
                      "当前句固定第 2 行 · 原厂封面与外层容器保留",
                      status=("已提交", "connected"))
    yy += setting_row(s, x, yy, w, "背景 · 本地图片", "已就绪",
                      "1920∶532 完整保留 · 未强制裁切 / 拉伸",
                      status=("等待端回执", "warning"))
    setting_row(s, x, yy, w, "原厂保留区域", "不修改",
                "GT 表 / 巡航三态 / 四档回收 / 胎压 / 挡位 / 驾驶模式",
                accent_value=False, last=True)

    section_title(s, x, y2 + 3 * ROW_H + 26, "来源与能力", "诊断页才有技术明细")
    y3 = y2 + 3 * ROW_H + 78
    card(s, x, y3, w, 2 * ROW_H, theme)
    yy = y3
    yy += setting_row(s, x, yy, w, "iPhone 原始 NowPlaying", "已连接",
                      "原厂媒体链 · 不被网络歌词或固定歌名覆盖",
                      status=("原始链路", "connected"))
    setting_row(s, x, yy, w, "AutoService 租约 / 原厂 Hook", "generation 7",
                "Root 由用户关闭时不重连，不改写基础能力选择",
                status=("有效", "connected"), last=True)

    by = y3 + 2 * ROW_H + 34
    button(s, x, by, 460, BTN_H, "应用到仪表", True, theme, icon="play")
    button(s, x + 488, by, 380, BTN_H, "恢复音乐原样", False, theme, icon="restore")
    button(s, x + 896, by, w - 896, BTN_H, "恢复背景原样", False, theme, icon="restore")


def a_editor(s, theme):
    x, top, right, bottom = page_chrome(
        s, theme, with_nav=False, back=True, title="编辑主题 · 简境 Simple Drive",
        right=[("save", "保存"), ("play", "应用")])
    w = right - x

    # 工具栏
    segmented(s, x, top, ["日间", "夜间"], 1 if theme == "night" else 0, 300, 84)
    cx = x + 328
    for lb in ("撤销", "重做", "参考线"):
        cx += chip(s, cx, top + 4, lb, False, 150, 76) + 20
    badge(s, right - 35 - 230, top + 4, "草稿 · 演示数据", "warning", 230, 76)

    # 固定比例画布
    y1 = top + 104
    card(s, x, y1, w, 460, theme)
    s.text(x + 35, y1 + 46, "固定比例画布 · 1920 × 532", role="SECTION_TITLE", color="muted")
    badge(s, right - 35 - 240, y1 + 16, "选中：数字读数", "accent", 240)
    cw = w - 70
    ch, _ = cluster_base(s, x + 35, y1 + 70, cw, "night", "custom")
    selection_frame(s, x + 35 + cw * 0.055, y1 + 70 + ch * 0.30,
                    cw * 0.115, ch * 0.30)

    # 工具按钮
    by1 = y1 + 480
    bw = (w - 3 * 25) / 4
    for i, (lb, ic, pri) in enumerate((("添加组件", "plus", True),
                                       ("图层", "layers", False),
                                       ("更换背景", "image", False),
                                       ("重命名", "edit", False))):
        button(s, x + i * (bw + 25), by1, bw, BTN_H, lb, pri, theme, icon=ic)

    # 属性面板
    section_title(s, x, by1 + BTN_H + 26, "属性 · 数字读数", "未覆盖属性继承主题")
    y2 = by1 + BTN_H + 78
    card(s, x, y2, w, 320, theme)
    iw = (w - 70 - 3 * 24) / 4
    for i, (lb, val) in enumerate((("X 位置", "70"), ("Y 位置", "100"),
                                   ("宽度", "400"), ("高度", "250"))):
        input_box(s, x + 35 + i * (iw + 24), y2 + 44, iw, lb, val, 112)
    s.tc(x + 35, y2 + 200, "日间 · 此组件样式", role="DESCRIPTION", color="muted")
    for i, col in enumerate(("#1368FB", "#5BA8FF", "#3DBE7A", "#F5B544",
                             "#FFFFFF", "#1B2733")):
        s.circle(x + 300 + i * 62, y2 + 200, 22, col,
                 stroke="accent" if i == 0 else "border", sw=3)
    button(s, right - 35 - 260, y2 + 152, 260, BTN_H_COMPACT, "恢复继承",
           False, theme, compact=True)
    s.line(x + 35, y2 + 240, right - 35, y2 + 240, "divider", BORDER_W)
    s.tc(x + 35, y2 + 292, "数据来源", role="LABEL", color="text")
    s.tc(x + 230, y2 + 292, "显示车速 · km/h", role="VALUE", color="muted")
    s.tc(x + 700, y2 + 292, "透明度", role="LABEL", color="text")
    s.rect(x + 850, y2 + 284, right - 35 - 850, 12, "track", 6)
    s.rect(x + 850, y2 + 284, (right - 35 - 850) * 0.86, 12, "accent", 6)
    s.circle(x + 850 + (right - 35 - 850) * 0.86, y2 + 290, 16, "primary_text",
             stroke="accent", sw=3)

    section_title(s, x, y2 + 320 + 26, "日夜与组件覆盖", "歌词字体等可独立设置")
    y3 = y2 + 320 + 78
    card(s, x, y3, w, 2 * ROW_H, theme)
    yy = y3
    yy += setting_row(s, x, yy, w, "组件级覆盖", "3 项",
                      "颜色 / 字号 / 可见性按日夜分别保存")
    setting_row(s, x, yy, w, "适用模式", "两种出口",
                "原厂节点可改属性以 ABI / 布局支持为限，需逐项标注",
                last=True)

    notice(s, x, y3 + 2 * ROW_H + 20, w,
           "保存只保留草稿；“应用到仪表”收到目标端回执后才更新正在显示的主题。",
           "warning", 84)


# ---------------------------------------------------------------------------
# B 方案 · 保留主题三 Tab，模式 / 原厂增强 / 设置放独立区域
# ---------------------------------------------------------------------------
def b_frame(s, theme, active_tab, mode, mode_active, title="仪表"):
    page_chrome(s, theme, with_nav=False, title=title,
                right=[("settings", "设置")])
    mode_bar(s, STATUS_H + TITLE_H, mode,
             ["切换模式", "原厂增强", "设置"], mode_active)
    tab_bar(s, STATUS_H + TITLE_H + 100,
            ["我的主题", "编辑主题", "组件库"], active_tab)
    return 42, STATUS_H + TITLE_H + 200 + 18, W - 42, H - DOCK_H - 24


def b_overview(s, theme):
    x, top, right, bottom = b_frame(s, theme, 0, "完整自定义", -1)
    w = right - x
    y = top
    card(s, x, y, w, 236, theme)
    s.text(x + 35, y + 56, "当前显示", role="SECTION_TITLE", color="muted")
    badge(s, right - 35 - 160, y + 24, "运行中", "connected", 160)
    s.text(x + 35, y + 116, "简境 · Simple Drive", role="PAGE_TITLE", color="text")
    s.text(x + 35, y + 162,
           "完整自定义主题 · 遮罩输出 · 已应用 revision 8",
           role="DESCRIPTION", color="muted")
    s.tc(right - 35, y + 162, "端回执 12:38", role="DESCRIPTION",
         color="connected", anchor="end")

    section_title(s, x, y + 262, "我的主题", "内置 / 自定义")
    gy = y + 314
    gw = (w - 28) / 2
    for i, (name, sub, tag) in enumerate((
            ("简境 · Simple Drive", "当前已应用 · 草稿 2 处", "已应用"),
            ("夜航 · Night Run", "内置主题 · 不可原位覆盖", "内置"),
            ("极简 GT", "自定义 · 2026-11-08", "草稿"),
            ("星轨 · Startrail", "自定义 · 2026-11-05", "—"))):
        cx = x + (i % 2) * (gw + 28)
        cyy = gy + (i // 2) * (340 + 28)
        card(s, cx, cyy, gw, 340, theme)
        ph, _ = cluster_base(s, cx + 18, cyy + 18, gw - 36, "night",
                             "custom" if i % 2 == 0 else "image")
        s.text(cx + 24, cyy + 18 + ph + 40, name, role="LABEL", color="text")
        s.text(cx + 24, cyy + 18 + ph + 78, sub, role="DESCRIPTION", color="muted")
        badge(s, cx + gw - 24 - 150, cyy + 18 + ph + 20, tag,
              "connected" if tag == "已应用" else "muted", 150, 44)

    by = gy + 2 * (340 + 28) + 12
    button(s, x, by, 400, BTN_H_COMPACT, "新建主题", True, theme, icon="plus",
           compact=True)
    button(s, x + 428, by, 320, BTN_H_COMPACT, "导入 / 导出", False, theme,
           icon="folder", compact=True)
    notice(s, x + 776, by, w - 776, "三 Tab 常驻 100px，模式入口在顶部。",
           "info", BTN_H_COMPACT)


def b_oem(s, theme):
    x, top, right, bottom = b_frame(s, theme, -1, "完整自定义", 1)
    w = right - x
    y = top
    notice(s, x, y, w,
           "三 Tab 与当前任务无关但仍占据顶部；模式入口是顶部小控件。", "warning", 84)
    y1 = y + 96
    card(s, x, y1, w, 420, theme)
    s.text(x + 35, y1 + 42, "可修改区域 · 1920 × 532", role="SECTION_TITLE", color="muted")
    cw = w - 70
    cluster_base(s, x + 35, y1 + 56, cw, "night", "music")
    section_title(s, x, y1 + 442, "元素", "音乐与背景分别配置")
    y2 = y1 + 494
    card(s, x, y2, w, 2 * ROW_H, theme)
    yy = y2
    yy += setting_row(s, x, yy, w, "音乐 · 四行歌词", "已启用",
                      "当前句固定第 2 行 · 原厂封面保留区不改",
                      status=("已提交", "connected"))
    setting_row(s, x, yy, w, "背景 · 本地图片", "已就绪",
                "1920∶532 完整保留", status=("等待回执", "warning"), last=True)
    section_title(s, x, y2 + 2 * ROW_H + 26, "来源与能力")
    y3 = y2 + 2 * ROW_H + 78
    card(s, x, y3, w, 2 * ROW_H, theme)
    yy = y3
    yy += setting_row(s, x, yy, w, "iPhone 原始 NowPlaying", "已连接",
                      "原厂媒体链不被网络歌词覆盖")
    setting_row(s, x, yy, w, "AutoService 租约", "generation 7",
                "Root 由用户关闭时不重连", last=True)
    by = y3 + 2 * ROW_H + 30
    button(s, x, by, 460, BTN_H, "应用到仪表", True, theme, icon="play")
    button(s, x + 488, by, w - 488, BTN_H, "恢复原厂显示", False, theme,
           danger=True, icon="restore")


def b_editor(s, theme):
    x, top, right, bottom = b_frame(s, theme, 1, "完整自定义", -1)
    w = right - x
    y = top
    segmented(s, x, y, ["日间", "夜间"], 0, 300, 76)
    cx = x + 328
    for lb in ("撤销", "重做", "参考线"):
        cx += chip(s, cx, y, lb, False, 150, 76) + 20
    badge(s, right - 35 - 230, y, "草稿 · 演示数据", "warning", 230, 76)
    y1 = y + 100
    card(s, x, y1, w, 420, theme)
    s.text(x + 35, y1 + 42, "固定比例画布 · 1920 × 532", role="SECTION_TITLE", color="muted")
    cw = w - 70
    ch, _ = cluster_base(s, x + 35, y1 + 58, cw, "night", "custom")
    selection_frame(s, x + 35 + cw * 0.055, y1 + 58 + ch * 0.30,
                    cw * 0.115, ch * 0.30)
    by1 = y1 + 440
    bw = (w - 3 * 20) / 4
    for i, (lb, ic, pri) in enumerate((("添加组件", "plus", True),
                                       ("图层", "layers", False),
                                       ("更换背景", "image", False),
                                       ("重命名", "edit", False))):
        button(s, x + i * (bw + 20), by1, bw, BTN_H_COMPACT, lb, pri, theme,
               icon=ic, compact=True)
    section_title(s, x, by1 + BTN_H_COMPACT + 26, "属性 · 数字读数")
    y2 = by1 + BTN_H_COMPACT + 78
    card(s, x, y2, w, 300, theme)
    iw = (w - 70 - 3 * 24) / 4
    for i, (lb, val) in enumerate((("X 位置", "70"), ("Y 位置", "100"),
                                   ("宽度", "400"), ("高度", "250"))):
        input_box(s, x + 35 + i * (iw + 24), y2 + 44, iw, lb, val, 96)
    s.tc(x + 35, y2 + 186, "数据来源", role="LABEL", color="text")
    s.tc(x + 230, y2 + 186, "显示车速 · km/h", role="VALUE", color="muted")
    s.tc(x + 700, y2 + 186, "透明度", role="LABEL", color="text")
    s.rect(x + 850, y2 + 178, right - 35 - 850, 12, "track", 6)
    s.rect(x + 850, y2 + 178, (right - 35 - 850) * 0.86, 12, "accent", 6)
    s.circle(x + 850 + (right - 35 - 850) * 0.86, y2 + 184, 16,
             "primary_text", stroke="accent", sw=3)
    s.line(x + 35, y2 + 228, right - 35, y2 + 228, "divider", BORDER_W)
    s.tc(x + 35, y2 + 266, "组件级覆盖", role="LABEL", color="text")
    s.tc(right - 35, y2 + 266, "3 项 · 日夜分别保存", role="VALUE",
         color="muted", anchor="end")
    notice(s, x, y2 + 300 + 22, w,
           "保存只保留草稿；应用到仪表需等待目标端回执。", "warning", 84)


# ---------------------------------------------------------------------------
# 仪表效果图（1920 × 532）
# ---------------------------------------------------------------------------
def cluster_effect(s, theme, variant, title, subtitle, tags):
    # 说明放在右上 / 左下空白处：不遮挡歌词四行、短进度条与原厂保留区。
    # 尺寸（1920×532 / 280px / 140px）由画册规格面板与标注气泡给出，静态稿不被标尺压住。
    cluster_base(s, 0, 0, CLUSTER_W, "night", variant)
    s.rect(CLUSTER_W - 790, 10, 766, 48, "#99000000", 4)
    s.tc(CLUSTER_W - 38, 34, f"{title} · {subtitle}", size=22,
         role="DESCRIPTION", color="#FFFFFF", anchor="end")
    s.rect(24, CLUSTER_H - 48, 560, 38, "#99000000", 4)
    s.tc(40, CLUSTER_H - 29, "设计示意 · 非原厂素材 / 非实屏截图", size=18,
         role="DESCRIPTION", color="#FFB34D")


def music_detail(s, theme):
    """原厂音乐区 280 × 140 候选面板 3× 放大稿（1000 × 600）。

    逐行坐标来自 docs/Cluster双模式与原厂多行歌词研究-20261111.md:21-31 的
    「面板内 Y / 高度 / 字号」表；只做版式示意，字体与实屏效果未验收。
    """
    s.rect(0, 0, 1000, 600, "#0B121B")
    s.text(40, 46, "原厂音乐区 · 280 × 140 候选面板（3× 放大）",
           size=26, role="VALUE", color="#E5FFFFFF")
    s.text(40, 76, "面板绝对位置 Y = 318–458，原 Media_Text 容器宽 280 / 高 532 保持不变",
           size=17, role="DESCRIPTION", color="#99FFFFFF")
    s.text(40, 98, "候选布局，未实车验收；不得套用旧遮罩 324px 歌词卡片 · 非原厂素材 / 非实屏截图",
           size=16, role="DESCRIPTION", color="#FFB34D")

    px, py, k = 100, 118, 3
    s.rect(px, py, 280 * k, 140 * k, "#101B27", 6, stroke="#3A5570", sw=2)
    rows = [
        ("晴天",               0,   22, "#E5FFFFFF", "标题"),
        ("周杰伦",             26,  16, "#99FFFFFF", "歌手"),
        ("故事的小黄花",        46,  14, "#99FFFFFF", "已播放"),
        ("从出生那年就飘着",    64,  22, "#FFFFFF",   "当前句 · 第 2 行"),
        ("童年的荡秋千",        90,  14, "#99FFFFFF", "待播放 1"),
        ("随记忆一直晃到现在",  108, 12, "#80FFFFFF", "待播放 2"),
    ]
    for txt, ry, fs, col, tag in rows:
        base = py + (ry + fs * 0.88) * k
        s.text(px + 12 * k, base, txt, size=fs * k,
               role="DESCRIPTION", color=col)
        s.text(px + 660, base, tag, size=19, role="DESCRIPTION", color="#6FA8D6")
    # 当前句强调（蓝色竖条）
    s.rect(px + 3 * k, py + 64 * k, 4 * k, 22 * k, "#1368FB", 2)
    # 短进度条（面板内 Y=124，长度缩短，右侧留给角色标注）
    s.rect(px + 12 * k, py + 124 * k, 110 * k, 4 * k, "#1368FB", 2)
    s.rect(px + 122 * k, py + 124 * k, 90 * k, 4 * k, "#33465C", 2)
    s.text(px + 660, py + 128 * k, "短进度", size=19,
           role="DESCRIPTION", color="#6FA8D6")

    # 尺寸标注
    s.line(px, py + 140 * k + 16, px + 280 * k, py + 140 * k + 16, "#FFB34D", 2)
    s.line(px, py + 140 * k + 8, px, py + 140 * k + 24, "#FFB34D", 2)
    s.line(px + 280 * k, py + 140 * k + 8, px + 280 * k, py + 140 * k + 24,
           "#FFB34D", 2)
    s.tc(px + 140 * k, py + 140 * k + 40, "280 px（本固件实车读回值）",
         size=20, role="DESCRIPTION", color="#FFB34D", anchor="middle")
    s.line(px - 22, py, px - 22, py + 140 * k, "#FFB34D", 2)
    s.tc(px - 48, py + 70 * k, "140", size=20, role="DESCRIPTION",
         color="#FFB34D", anchor="middle")
    s.tc(px - 48, py + 70 * k + 30, "px", size=20, role="DESCRIPTION",
         color="#FFB34D", anchor="middle")


# ---------------------------------------------------------------------------
# 注册
# ---------------------------------------------------------------------------
A_SPECS = [
    "中控画布 1440 × 1920 px（3:4 竖屏）",
    "App 资源密度 280dpi → 1dp = 1.75px（约 823 × 1097 dp）",
    "左侧导航 164dp = 287px；标题栏 64dp = 112px；设置行 80dp = 140px",
    "卡片 4dp 圆角 + 1dp 描边；主按钮 72dp = 126px",
    "系统 densityDpi / Insets 运行时确定，未写死",
]
B_SPECS = [
    "同一 FlymeAuto 色阶 / 文字角色 / 触控尺度，仅导航组织不同",
    "顶部三 Tab 常占 100px；模式与设置挤在独立区域",
    "无独立“总览 / 元素状态”一级入口",
]

for th in ("day", "night"):
    register("A-P01", "总览", "A · 四入口（推荐）", th, a_overview, [
        anno(11, 14, "四入口导航", "164dp = 287px 左导航。总览 / 原厂增强 / 主题 / 设置把日常启停、原厂配置、主题制作、基础设置分开。"),
        anno(48, 26, "等比仪表预览", "严格 1920:532，不拉成长条装饰卡。缩略图为版式示意，不是实屏截图。"),
        anno(71, 39, "实际状态 vs 配置", "“运行中”绿点只在收到目标端回执后出现；“草稿未应用”单独标注，服务已绑定不等于仪表已显示。"),
        anno(38, 49, "三个动作语义分离", "应用到仪表 / 恢复原厂显示 / 停止各自独立，不是一个大开关同时改模式、背景、Root 和投屏。"),
        anno(48, 76, "元素状态逐项列出", "音乐与背景分别报告提交 / 等待结果，恢复其中一项不影响另一项。"),
    ], A_SPECS)

    register("A-P03", "原厂增强", "A · 四入口（推荐）", th, a_oem, [
        anno(50, 13, "预览声明", "页面显著位置声明预览为演示数据，实际状态只认端回执。"),
        anno(50, 30, "可修改区域标记", "仪表示意按 1920×532 等比；音乐 280px 文字容器、背景层、原厂保留区分别标注。"),
        anno(36, 60, "音乐 / 背景独立", "两项分别启停、分别恢复；原厂封面与外层容器保留，不开放任意拖动安全图标。"),
        anno(60, 84, "来源与能力", "iPhone 原始 NowPlaying、AutoService 租约逐项可见；技术明细下沉诊断页。"),
        anno(24, 94, "单项恢复", "恢复音乐 / 恢复背景 / 全部恢复是三个不同动作，互不误停。"),
    ], A_SPECS)

    register("A-P09", "主题编辑器", "A · 四入口（推荐）", th, a_editor, [
        anno(14, 12, "编辑态工具栏", "日夜切换、撤销 / 重做、参考线；演示数据显著标识。"),
        anno(50, 30, "固定比例画布", "1920×532 深色仪表画面，不把仪表内容区做成中控设置页。选中组件有蓝色选框与四角控制点。"),
        anno(35, 46, "编辑工具", "添加组件 / 图层 / 更换背景 / 重命名；组件库只浏览，添加只在编辑器。"),
        anno(40, 66, "属性面板", "可读单位输入框、样式色点、数据来源、透明度；不让用户输入裸 JSON / ABI / Shell。"),
        anno(50, 94, "保存 ≠ 应用", "保存保留草稿；收到目标端回执后才更新正在显示的主题。"),
    ], A_SPECS)

register("B-P01", "我的主题（对照总览）", "B · 三 Tab + 独立入口", "day", b_overview, [
    anno(50, 12, "独立区域", "模式、原厂增强、设置挤在标题栏下方一条 100px 控件区，不是四个一级入口。"),
    anno(50, 20, "三 Tab 常驻", "我的主题 / 编辑主题 / 组件库常占 100px；进入原厂配置时仍占据顶部。"),
    anno(30, 42, "状态卡变薄", "运行状态与方案信息压在一张 236px 卡里，元素状态逐项列出没有独立位置。"),
    anno(50, 70, "主题网格", "双列主题预览保持 1920:532 等比，内置主题不原位覆盖。"),
    anno(80, 92, "可见性差异", "日常原厂配置入口离常用操作更远，模式切换与主题制作混在同一层。"),
], B_SPECS)

register("B-P03", "原厂增强配置", "B · 三 Tab + 独立入口", "day", b_oem, [
    anno(50, 12, "混层", "当前任务是原厂增强配置，顶部三 Tab 仍与任务无关地占据 100px。"),
    anno(50, 23, "模式入口小", "模式切换只是顶部一个 chip，与 A 的四入口导航相比可见性低。"),
    anno(50, 48, "元素与来源", "内容与 A 保持一致，差别只在导航组织，不另造第二套品牌风格。"),
    anno(40, 93, "应用与恢复", "仍保留应用 / 恢复分离，不因导航方案改变语义。"),
], B_SPECS)

register("B-P09", "编辑主题", "B · 三 Tab + 独立入口", "day", b_editor, [
    anno(50, 20, "Tab 即入口", "编辑器是三个主题 Tab 之一，模式与设置不在本层。"),
    anno(50, 43, "画布", "同为 1920×532 固定比例，组件与属性口径与 A 完全一致。"),
    anno(50, 72, "属性面板", "空间被顶部两条常驻条压缩，日夜覆盖只能收成一行。"),
], B_SPECS)

CLUSTER_SPECS = [
    "仪表画布 1920 × 532 px（QNX display ID2）",
    "原厂 Media_Text 实车读回：X=1440，宽 280px，容器高 532px",
    "四行歌词候选面板高 140px（Y=318–458），未实车验收",
    "禁止套用旧遮罩模式 324px 歌词卡片",
    "速度表 / 地图 / 图标均为版式示意，原厂素材缺口另登记",
]

register("C-01", "仪表 · 原厂音乐增强", "仪表效果（1920 × 532）", "night",
         lambda s, th: cluster_effect(s, th, "music", "原厂音乐增强",
                                      "四行歌词 · 原厂封面与容器保留",
                                      []), [
             anno(77, 55, "四行歌词", "上 1 行已播放、第 2 行当前（高亮）、下 2 行待播放；短进度条；只有当前句时如实显示，不编造上下句。"),
             anno(77, 20, "封面保留区", "原厂封面与外层容器不替换；280px 是实车读回的文字容器宽度，不是卡片宽。"),
             anno(12, 50, "原厂速度区", "版式示意，非原厂 GT 表素材；巡航 / 回收 / 档位等保留既有能力，不搬进音乐卡片。"),
             anno(92, 25, "原厂保留区", "胎压 / 能耗 / 驾驶模式等保持原厂，不用近似素材冒充。"),
         ], CLUSTER_SPECS, canvas="cluster")

register("C-02", "仪表 · 本地图片背景", "仪表效果（1920 × 532）", "night",
         lambda s, th: cluster_effect(s, th, "image", "本地图片背景",
                                      "1920∶532 完整保留 · 用户主动取景",
                                      []), [
             anno(45, 40, "背景层", "纯 SVG 绘制的极光示意，不引用任何真实图片或私有资源；切回原厂时恢复原纹理与属性来源。"),
             anno(77, 55, "音乐区仍在", "恢复背景后音乐保持；两类元素分别有所有权与恢复记录。"),
             anno(50, 95, "取景口径", "不强制拉伸、不默认裁掉内容；完整保留或用户主动裁切二选一。"),
         ], CLUSTER_SPECS, canvas="cluster")

register("C-03", "仪表 · CarPlay 地图背景（示意）", "仪表效果（1920 × 532）", "night",
         lambda s, th: cluster_effect(s, th, "map", "CarPlay 地图背景",
                                      "示意路网 · 非真实车道级视频",
                                      []), [
             anno(45, 40, "示意路网", "明确标注为示意，不能称为真实车道级视频；真实 111 车道级内容需单独实车验收。"),
             anno(50, 95, "无源状态", "未连接或等待首帧时明确等待，不自动换成其他背景来源。"),
             anno(77, 55, "来源标识", "本机地图 / CarPlay 地图分别标识，边缘虚化 / 范围 / 圆角参数可在 P07 调整。"),
         ], CLUSTER_SPECS, canvas="cluster")

register("C-04", "仪表 · 完整自定义主题", "仪表效果（1920 × 532）", "night",
         lambda s, th: cluster_effect(s, th, "custom", "完整自定义主题",
                                      "遮罩输出 · 组件拼装",
                                      []), [
             anno(12, 50, "GT 细针主表", "保留既有 GT 表、巡航三态与差值弧、四档回收等能力。"),
             anno(50, 40, "完整地图背景", "CarPlay 源的边缘虚化背景；与中控 110 视频独立。"),
             anno(77, 55, "四行歌词", "遮罩模式沿用 324px 歌词区口径，与原厂增强的 280px 容器不混用。"),
             anno(92, 25, "档位 / 驾驶模式", "独立组件，驾驶模式读取已核验的 G733 PA 状态合同。"),
         ], CLUSTER_SPECS, canvas="cluster")

register("C-05", "仪表 · 日间样例", "仪表效果（1920 × 532）", "day",
         lambda s, th: cluster_effect(s, th, "music", "原厂音乐增强 · 日间",
                                      "日夜分别预览 · 版式示意",
                                      []), [
             anno(50, 50, "日夜", "仪表日夜效果分别预览；中控页日夜由 FlymeAuto 色阶切换，两者不互相替代。"),
             anno(77, 55, "文字层级", "日间需复核长中文句、超长曲目与对比度；本稿未做实屏验收。"),
         ], CLUSTER_SPECS, canvas="cluster")

register("C-06", "仪表 · 音乐区局部放大（280 × 140）", "仪表效果（1920 × 532）", "night",
         music_detail, [
             anno(30, 22, "候选面板坐标", "面板绝对 Y=318–458，逐行按已核实的「面板内 Y / 高度 / 字号」表排布；140px 是本轮候选边界，未实车验收。"),
             anno(30, 52, "四行层次", "上 1 行已播放、当前句固定第 2 行（白色高亮 + 蓝色竖条）、下 2 行待播放；标题与歌手沿用原厂标题 / 歌手样式。"),
             anno(72, 52, "角色标注", "右侧逐行标注语义，便于 Codex 对照节点属性；仅当前句时如实显示，不编造上下句。"),
             anno(35, 84, "短进度条", "进度条位于面板内 Y=124，长度缩短；封面与外层容器保持原厂，不放入本面板。"),
             anno(50, 96, "尺寸口径", "280px 是本固件实车读回的文字容器宽度，不是跨固件常量；禁止套旧遮罩 324px 卡片。"),
         ], CLUSTER_SPECS, canvas="cluster", size=(1000, 600))


# ---------------------------------------------------------------------------
def main():
    PAGES_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    for p in PAGES:
        is_cluster = p["canvas"] == "cluster"
        cw, ch = p["size"] or ((CLUSTER_W, CLUSTER_H) if is_cluster else (W, H))
        s = Svg(cw, ch, p["theme"], f'{p["id"]} {p["title"]} ({p["theme"]})')
        p["fn"](s, p["theme"])
        out = PAGES_DIR / f'{p["id"]}-{p["theme"]}.svg'
        out.write_text(s.finish(), encoding="utf-8")
        manifest.append({k: v for k, v in p.items() if k != "fn"})
        print(f"  {out.relative_to(ROOT)}  {out.stat().st_size:>7} B")
    js = ("/* 由 tools/generate_pages.py 生成；设计画册的单一数据源。 */\n"
          "window.CLUSTER_PAGES = " +
          json.dumps(manifest, ensure_ascii=False, indent=2) + ";\n")
    (PAGES_DIR / "manifest.js").write_text(js, encoding="utf-8")
    print(f"  pages/manifest.js  ({len(manifest)} pages)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
