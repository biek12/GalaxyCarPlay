# -*- coding: utf-8 -*-
"""
Cluster 双模式 D1 设计稿 · 公共绘制 kit

设计系统优先：所有色阶 / 字号 / 间距 / 控件尺寸集中在此，页面脚本只做编排。
仅生成静态 SVG，不接设备、网络或生产接口。

尺寸口径（px，均为设计稿像素）：
  中控 1440x1920，App 资源密度 280dpi => 1dp = 1.75px。
  仪表 1920x532。系统 densityDpi / Insets 由宿主运行时确定，本 kit 不写死。
"""
from html import escape as _esc

W, H = 1440, 1920
CLUSTER_W, CLUSTER_H = 1920, 532
DP = 1.75

FONT = "'PingFang SC','Noto Sans CJK SC','Hiragino Sans GB','Microsoft YaHei',sans-serif"

# ---------------------------------------------------------------------------
# 语义色 token
# 来源：galaxy/mobile/.../values{,-night}/product_ui.xml、
#       galaxy/common/.../values{,-night}/l7_settings_surface.xml、
#       galaxy/service/.../values{,-night}/service_theme.xml
# 汇总对照见 docs/AutoRecord/FlymeAuto样式基线-20261103.md §2（仅取公共色阶）
# 元组 = (hex, alpha)，对应源码里的 #AARRGGBB。
# ---------------------------------------------------------------------------
DAY = {
    "bg_top": "#F6F8FC", "bg_bottom": "#EAF0F6",
    "nav_top": "#EBF1F8", "nav_bottom": "#DDE6F1",
    "surface": "#FFFFFF", "control": "#E5EBF2",
    "border": "#E0E7F0", "divider": "#D5DFEB",
    "text": ("#000000", 0.90), "muted": ("#000000", 0.60),
    "disabled": ("#000000", 0.40),
    "accent": "#1368FB", "accent_soft": "#EAF2FF",
    "selected": ("#1368FB", 0.08), "ripple": ("#1368FB", 0.125),
    "connected": "#247744", "warning": "#A65C00", "danger": "#C83D43",
    "primary_text": "#FFFFFF", "track": "#C3CDD9",
}
NIGHT = {
    "bg_top": "#28313C", "bg_bottom": "#222A34",
    "nav_top": "#1E2732", "nav_bottom": "#161F29",
    "surface": "#303A47", "control": "#15191F",
    "border": "#3B4756", "divider": "#344151",
    "text": ("#FFFFFF", 0.90), "muted": ("#FFFFFF", 0.60),
    "disabled": ("#FFFFFF", 0.40),
    "accent": "#0088FF", "accent_soft": "#263F60",
    "selected": ("#0088FF", 0.14), "ripple": ("#0088FF", 0.20),
    "connected": "#7BD89B", "warning": "#FFB34D", "danger": "#FF747A",
    "primary_text": "#FFFFFF", "track": "#596676",
}
TOKENS = {"day": DAY, "night": NIGHT}

# 文字角色（24/18/20/18/16 sp 等，按 280dpi 换算成设计稿 px）
ROLE = {
    "PAGE_TITLE": (42, 500),       # 24sp / 32sp
    "SECTION_TITLE": (32, 500),    # 18sp / 26sp
    "LABEL": (35, 400),            # 20sp / 28sp
    "VALUE": (32, 400),            # 18sp / 26sp
    "DESCRIPTION": (28, 400),      # 16sp / 24sp
    "FEEDBACK": (28, 400),         # 16sp / 24sp
    "NAV": (33, 500),              # 19sp
    "BADGE": (23, 400),            # 13sp
    "BUTTON": (35, 500),           # 20sp
    "CLUSTER_HINT": (18, 400),     # 仪表缩略图内的辅助字（设计演示用）
}

# 控件尺寸（dp -> px，1dp = 1.75px）
STATUS_H = 70          # 顶部系统栏【示意高度，运行时 Insets 计算】
DOCK_H = 100           # 底部 FlymeAuto Dock【示意高度】
NAV_W = 287            # 164dp
NAV_ITEM_H = 158       # 90dp
NAV_ICON = 84          # 48dp
TITLE_H = 112          # 64dp
ROW_H = 140            # 80dp
ROW_H_DESC = 168       # 96dp
CARD_R = 7             # 4dp
BORDER_W = 1.75        # 1dp
CARD_GAP = 42          # 24dp
CARD_GAP_TIGHT = 28    # 16dp
CONTENT_PAD = 42       # 24dp
ROW_PAD = 35           # 20dp
BTN_H = 126            # 72dp
BTN_H_COMPACT = 84     # 48dp
INPUT_H = 112          # 64dp
SWITCH_W, SWITCH_H = 102, 53   # 58x30dp
ICON_BTN = 112         # 64dp 命中区

CONTENT_TOP = STATUS_H + TITLE_H
CONTENT_BOTTOM = H - DOCK_H


def esc(s):
    return _esc(str(s), quote=True)


# ---------------------------------------------------------------------------
# 线性图标：24x24 画布、1.7 描边、圆头圆角，自绘；不复制 OEM / 第三方素材
# ---------------------------------------------------------------------------
ICONS = {
    "overview": '<rect x="3.5" y="3.5" width="7" height="7" rx="1.2"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.2"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.2"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.2"/>',
    "oem": '<path d="M12 2.8 21 7.6v8.8L12 21.2 3 16.4V7.6z"/><path d="M3 7.6 12 12.4l9-4.8M12 12.4v8.8"/>',
    "theme": '<circle cx="12" cy="12" r="9"/><circle cx="8" cy="9" r="1.3"/><circle cx="15.5" cy="8.5" r="1.3"/><circle cx="8.5" cy="15.5" r="1.3"/><path d="M12 21a4 4 0 0 0 0-8"/>',
    "settings": '<path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2.2" fill="none"/><circle cx="15" cy="12" r="2.2" fill="none"/><circle cx="8" cy="17" r="2.2" fill="none"/>',
    "back": '<path d="M15 4.5 7.5 12l7.5 7.5"/>',
    "chevron": '<path d="M9.5 5.5 16 12l-6.5 6.5"/>',
    "plus": '<path d="M12 5v14M5 12h14"/>',
    "undo": '<path d="M9 7 4.5 11.5 9 16"/><path d="M4.5 11.5H14a5.5 5.5 0 0 1 0 11"/>',
    "redo": '<path d="M15 7l4.5 4.5L15 16"/><path d="M19.5 11.5H10a5.5 5.5 0 0 0 0 11"/>',
    "layers": '<path d="M12 3 3 8l9 5 9-5z"/><path d="m3 13 9 5 9-5M3 17.5l9 5 9-5"/>',
    "image": '<rect x="3" y="4.5" width="18" height="15" rx="2"/><circle cx="8.5" cy="9.5" r="1.8"/><path d="m4 17 5.5-5 3.5 3 3-2.5 4 4.5"/>',
    "music": '<path d="M9 18V5.5l11-2V16"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
    "map": '<path d="m3 6 6-2 6 2 6-2v14l-6 2-6-2-6 2z"/><path d="M9 4v14M15 6v14"/>',
    "check": '<path d="m4.5 12.5 5 5 10-11"/>',
    "alert": '<path d="M12 3.5 21.5 20h-19z"/><path d="M12 9.5v5.5M12 17.5v.6"/>',
    "eye": '<path d="M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    "restore": '<path d="M4 8.5V4M4 4h4.5"/><path d="M4.4 4.4A9 9 0 1 1 3 12"/>',
    "more": '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
    "grid": '<rect x="3" y="3" width="7.5" height="7.5" rx="1"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1"/>',
    "sun": '<circle cx="12" cy="12" r="4"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M19.1 4.9l-1.8 1.8M6.7 17.3l-1.8 1.8"/>',
    "moon": '<path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z"/>',
    "clock": '<circle cx="12" cy="12" r="9"/><path d="M12 6.5V12l4 2.5"/>',
    "car": '<path d="M4 13.5 6 7.5h12l2 6v4.5h-2.5M4 18H1.5"/><path d="M4 13.5h16"/><circle cx="7.5" cy="18" r="2"/><circle cx="16.5" cy="18" r="2"/>',
    "gauge": '<path d="M3.5 17a9 9 0 1 1 17 0"/><path d="m12 12 5-4"/><circle cx="12" cy="12" r="1.4"/>',
    "play": '<path d="M8 5.5 18 12 8 18.5z"/>',
    "pause": '<path d="M8.5 5.5v13M15.5 5.5v13"/>',
    "edit": '<path d="M4 20h4l11-11-4-4L4 16z"/><path d="m14 6 4 4"/>',
    "save": '<path d="M4 4h13l3 3v13H4z"/><path d="M8 4v6h8V4M8 20v-6h8v6"/>',
    "folder": '<path d="M3 6.5h6l2 2.5H21v11H3z"/>',
    "info": '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.6"/>',
}


class Svg:
    def __init__(self, w=W, h=H, theme="day", label=""):
        self.w, self.h, self.theme = w, h, theme
        self.t = TOKENS[theme]
        self.label = label
        self.defs = []
        self.body = []
        self._gid = 0

    # ---- 基础 ----
    def col(self, v):
        if isinstance(v, tuple):
            return v
        if isinstance(v, str):
            if v.startswith("#"):
                # 本设计稿的 8 位色值全部抄自 Android 资源，格式为 #AARRGGBB
                # （与 CSS 的 #RRGGBBAA 相反）。拆成 6 位 hex + fill-opacity，
                # 否则 #99FFFFFF 会被浏览器误读成青绿色、#99000000 变成全透明。
                if len(v) == 9:
                    return ("#" + v[3:9], round(int(v[1:3], 16) / 255.0, 4))
                return (v, 1.0)
            if v.startswith("url(") or v == "none":
                return (v, 1.0)
        t = self.t[v]
        return t if isinstance(t, tuple) else (t, 1.0)

    def _fill(self, v, alpha=1.0):
        hexv, a = self.col(v)
        a = round(a * alpha, 4)
        if a >= 0.999:
            return f'fill="{hexv}"'
        return f'fill="{hexv}" fill-opacity="{a}"'

    def add(self, s):
        self.body.append(s)
        return self

    def uid(self, p="g"):
        self._gid += 1
        return f"{p}{self._gid}"

    def gradient(self, c1, c2, vertical=True):
        gid = self.uid("grad")
        x2, y2 = (0, 1) if vertical else (1, 0)
        self.defs.append(
            f'<linearGradient id="{gid}" x1="0" y1="0" x2="{x2}" y2="{y2}">'
            f'<stop offset="0" stop-color="{self.col(c1)[0]}"/>'
            f'<stop offset="1" stop-color="{self.col(c2)[0]}"/></linearGradient>')
        return f"url(#{gid})"

    def clip(self, x, y, w, h, r=0):
        cid = self.uid("clip")
        self.defs.append(f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}"/></clipPath>')
        return f"url(#{cid})"

    def rect(self, x, y, w, h, fill, r=0, stroke=None, sw=BORDER_W, opacity=1.0, extra=""):
        s = [f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}"']
        if r:
            s.append(f'rx="{r}" ry="{r}"')
        s.append(self._fill(fill, opacity))
        if stroke:
            hexv, a = self.col(stroke)
            s.append(f'stroke="{hexv}" stroke-width="{sw}"')
            if a < 0.999:
                s.append(f'stroke-opacity="{a}"')
        if extra:
            s.append(extra)
        s.append("/>")
        return self.add(" ".join(s))

    def line(self, x1, y1, x2, y2, stroke, sw=BORDER_W, dash=None, opacity=1.0):
        hexv, a = self.col(stroke)
        d = f' stroke-dasharray="{dash}"' if dash else ""
        return self.add(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
                        f'stroke="{hexv}" stroke-width="{sw}" stroke-opacity="{round(a*opacity,3)}"{d}/>')

    def circle(self, cx, cy, r, fill, stroke=None, sw=BORDER_W, opacity=1.0):
        hexv, a = self.col(fill)
        s = f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r:.1f}" {self._fill(fill, opacity)}'
        if stroke:
            h2, a2 = self.col(stroke)
            s += f' stroke="{h2}" stroke-width="{sw}" stroke-opacity="{a2}"'
        return self.add(s + "/>")

    def path(self, d, stroke=None, sw=2, fill="none", opacity=1.0, extra=""):
        hexv, a = self.col(stroke) if stroke else ("none", 1)
        f = self._fill(fill) if fill != "none" else 'fill="none"'
        return self.add(f'<path d="{d}" {f} stroke="{hexv}" stroke-width="{sw}" '
                        f'stroke-opacity="{round(a*opacity,3)}" stroke-linecap="round" '
                        f'stroke-linejoin="round" {extra}/>')

    def text(self, x, y, s, size=None, role="LABEL", color="text", anchor="start",
             alpha=1.0, extra=""):
        """y 为文字基线。"""
        sz, weight = ROLE[role] if size is None else (size, 400)
        hexv, a = self.col(color)
        return self.add(
            f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{sz}" '
            f'font-weight="{weight}" text-anchor="{anchor}" {self._fill((hexv, a*alpha))} '
            f'xml:space="preserve" {extra}>{esc(s)}</text>')

    def tc(self, x, cy, s, size=None, role="LABEL", **kw):
        """垂直居中文字（cy 为行中心）。"""
        sz = ROLE[role][0] if size is None else size
        return self.text(x, cy + sz * 0.36, s, size, role, **kw)

    def icon(self, name, x, y, size=56, color="text", alpha=1.0):
        hexv, a = self.col(color)
        scale = size / 24.0
        return self.add(
            f'<g transform="translate({x:.1f},{y:.1f}) scale({scale:.4f})" '
            f'fill="none" stroke="{hexv}" stroke-opacity="{round(a*alpha,3)}" '
            f'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">'
            f'{ICONS[name]}</g>')

    def finish(self):
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}" role="img" aria-label="{esc(self.label)}">')
        meta = (f'<title>{esc(self.label)}</title><desc>AutoCluster 双模式 D1 静态设计稿；'
                f'中控画布 1440x1920，仪表画布 1920x532。仅设计演示。</desc>')
        defs = f"<defs>{''.join(self.defs)}</defs>" if self.defs else ""
        return head + meta + defs + "".join(self.body) + "</svg>\n"


# ---------------------------------------------------------------------------
# 组合组件（FlymeAuto 设置口径）
# ---------------------------------------------------------------------------
def page_chrome(s, theme, with_nav=True, nav_items=None, selected=0,
                title="", back=False, right=None, brand=True):
    """铺底 + 系统栏/Dock 示意 + 左侧导航 + 64dp 标题栏。返回正文可用区域。"""
    t = TOKENS[theme]
    bg = s.gradient("bg_top", "bg_bottom")
    s.rect(0, 0, W, H, bg)

    # 标题栏右侧动作
    if right is None:
        right = []

    # 左侧导航（一级页）
    if with_nav:
        ng = s.gradient("nav_top", "nav_bottom")
        s.rect(0, STATUS_H, NAV_W, H - STATUS_H - DOCK_H, ng)
        s.line(NAV_W - 1, STATUS_H, NAV_W - 1, H - DOCK_H, "border", BORDER_W)
        if brand:
            s.icon("gauge", 34, STATUS_H + 34, 52, "accent")
            s.text(96, STATUS_H + 63, "仪表", 40, "PAGE_TITLE", "text")
            s.text(96, STATUS_H + 97, "AutoCluster", 24, "DESCRIPTION", "muted")
            s.line(28, STATUS_H + 134, NAV_W - 28, STATUS_H + 134, "divider", BORDER_W)
        items = nav_items or []
        for i, (ic, label) in enumerate(items):
            y = STATUS_H + 160 + i * (NAV_ITEM_H + 12)
            active = i == selected
            if active:
                s.rect(16, y, NAV_W - 32, NAV_ITEM_H, "selected", CARD_R)
                s.rect(16, y, 6, NAV_ITEM_H, "accent", 3)
            s.icon(ic, 16 + (NAV_W - 32 - NAV_ICON) / 2, y + 18, NAV_ICON,
                   "accent" if active else "text", 1.0 if active else 0.72)
            s.tc(NAV_W / 2, y + NAV_ITEM_H - 32, label, role="NAV",
                 color="accent" if active else "text", anchor="middle",
                 alpha=1.0 if active else 0.72)

    # 64dp 标题栏
    x0 = NAV_W if with_nav else 0
    s.rect(x0, STATUS_H, W - x0, TITLE_H, bg)
    s.line(x0, STATUS_H + TITLE_H, W, STATUS_H + TITLE_H, "divider", BORDER_W)
    if back:
        s.icon("back", x0 + 34, STATUS_H + 28, 56, "text")
        s.text(x0 + 106, STATUS_H + 72, title, role="PAGE_TITLE", color="text")
    else:
        s.text(x0 + CONTENT_PAD, STATUS_H + 72, title, role="PAGE_TITLE", color="text")
    for i, (ic, label) in enumerate(right):
        rx = W - CONTENT_PAD - (len(right) - i) * 160
        s.icon(ic, rx + 26, STATUS_H + 28, 52, "text", 0.8)
        s.tc(rx + 52, STATUS_H + TITLE_H - 24, label, role="DESCRIPTION",
             color="muted", anchor="middle")

    # 系统栏 / Dock（示意，运行时 Insets 计算，不写死高度）
    _status_bar(s, theme)
    _dock(s, theme)
    return (x0 + CONTENT_PAD, CONTENT_TOP + 24, W - CONTENT_PAD, H - DOCK_H - 24)


def _status_bar(s, theme):
    t = TOKENS[theme]
    s.rect(0, 0, W, STATUS_H, "nav_top", opacity=0.96)
    s.line(0, STATUS_H, W, STATUS_H, "border", BORDER_W)
    s.text(40, 46, "12:41", 28, "DESCRIPTION", "text")
    s.icon("car", 160, 22, 30, "muted")
    s.text(202, 46, "24°C", 26, "DESCRIPTION", "muted")
    # 右侧一组状态图标
    for i, name in enumerate(("music", "map", "info")):
        s.icon(name, W - 250 + i * 62, 22, 28, "muted", 0.85)
    s.tc(W / 2, STATUS_H / 2, "系统状态栏 · Insets 区（示意，运行时计算）",
         size=20, role="DESCRIPTION", color="disabled", anchor="middle")


def _dock(s, theme):
    y = H - DOCK_H
    s.rect(0, y, W, DOCK_H, "nav_top", opacity=0.97)
    s.line(0, y, W, y, "border", BORDER_W)
    # 六个等距示意槽（不复制原厂图标）
    labels = ("桌面", "媒体", "空调", "导航", "应用", "车辆")
    names = ("grid", "music", "sun", "map", "overview", "car")
    slot = W / 6
    for i, (lb, ic) in enumerate(zip(labels, names)):
        cx = slot * i + slot / 2
        s.icon(ic, cx - 22, y + 20, 44, "text", 0.72)
        s.tc(cx, y + 76, lb, size=20, role="DESCRIPTION", color="muted", anchor="middle")


def card(s, x, y, w, h, theme):
    return s.rect(x, y, w, h, "surface", CARD_R, stroke="border", sw=BORDER_W)


def section_title(s, x, y, text, note=None):
    s.text(x, y + 34, text, role="SECTION_TITLE", color="muted")
    if note:
        s.text(x + 340, y + 34, note, role="DESCRIPTION", color="disabled")


def setting_row(s, x, y, w, label, value=None, desc=None, icon=None,
                h=ROW_H, accent_value=False, status=None, last=False, divider=True):
    """Flyme 设置行：LABEL + 可选 DESCRIPTION + 右侧 VALUE + 箭头。

    D1 关键页统一按 80dp(140px) 行高排布：280dpi 下 LABEL 35px +
    DESCRIPTION 28px 两行实际约占 91px，140px 内有约 49px 余量。
    源码「实测文字过高自动增高到 96dp(168px)」的规则保留，触发条件
    （系统字体放大 / 长中文换行）的增高态在 D2 单独出稿，见 DESIGN.md。
    """
    cy = y + h / 2
    tx = x + ROW_PAD
    if icon:
        s.icon(icon, x + ROW_PAD, cy - 28, 56, "text", 0.72)
        tx += 56 + 28
    if desc:
        s.text(tx, cy - 12, label, role="LABEL", color="text")
        s.text(tx, cy + 30, desc, role="DESCRIPTION", color="muted")
    else:
        s.tc(tx, cy, label, role="LABEL", color="text")
    if value:
        vx = x + w - ROW_PAD - (250 if status else 48)
        s.tc(vx, cy, value, role="VALUE",
             color="accent" if accent_value else "muted", anchor="end")
    if icon is None and value and status is None:
        s.icon("chevron", x + w - ROW_PAD - 34, cy - 18, 36, "disabled", 0.9)
    if status:
        badge(s, x + w - ROW_PAD - 200, cy - 24, status[0], status[1], 200)
    if divider and not last:
        s.line(x + ROW_PAD, y + h, x + w - ROW_PAD, y + h, "divider", BORDER_W)
    return h


def badge(s, x, y, label, kind="accent", w=None, h=48):
    colors = {
        "accent": ("accent_soft", "accent"),
        "connected": ("accent_soft", "connected"),
        "warning": ("accent_soft", "warning"),
        "danger": ("accent_soft", "danger"),
        "muted": ("control", "muted"),
    }
    bg, fg = colors.get(kind, colors["accent"])
    tw = w or max(110, len(label) * 25 + 44)
    s.rect(x, y, tw, h, bg, CARD_R)
    s.tc(x + tw / 2, y + h / 2, label, role="BADGE", color=fg, anchor="middle")
    return tw


def switch(s, x, y, on=True, enabled=True):
    s.rect(x, y, SWITCH_W, SWITCH_H, "accent" if on else "track", 5,
           opacity=1.0 if enabled else 0.4)
    kx = x + SWITCH_W - 10 - 38 if on else x + 10
    s.rect(kx, y + 7, 38, SWITCH_H - 14, "primary_text", 3,
           opacity=1.0 if enabled else 0.5)


def button(s, x, y, w, h, label, primary=False, theme="day", danger=False,
           icon=None, disabled=False, compact=False):
    bg = "accent" if primary else "control"
    fg = "primary_text" if primary else ("danger" if danger else "text")
    alpha = 0.45 if disabled else 1.0
    s.rect(x, y, w, h, bg, CARD_R, opacity=alpha)
    size = ROLE["BUTTON"][0] if not compact else 28
    tx = x + w / 2
    if icon:
        s.icon(icon, x + 26, y + (h - 50) / 2, 50, fg, alpha)
    s.tc(tx, y + h / 2, label, size=size, role="BUTTON", color=fg,
         anchor="middle", alpha=alpha)


def chip(s, x, y, label, active=False, w=None, h=76):
    tw = w or max(150, len(label) * 33 + 56)
    s.rect(x, y, tw, h, "selected" if active else "control", CARD_R,
           stroke="accent" if active else None, sw=BORDER_W)
    s.tc(x + tw / 2, y + h / 2, label, role="VALUE",
         color="accent" if active else "muted", anchor="middle")
    return tw


def segmented(s, x, y, labels, active=0, w=520, h=84):
    seg = w / len(labels)
    s.rect(x, y, w, h, "control", CARD_R)
    if active >= 0:
        s.rect(x + active * seg + 4, y + 4, seg - 8, h - 8, "surface", CARD_R,
               stroke="accent", sw=BORDER_W)
    for i, lb in enumerate(labels):
        s.tc(x + seg * i + seg / 2, y + h / 2, lb, role="VALUE",
             color="accent" if i == active else "muted", anchor="middle")
    return w


def notice(s, x, y, w, text, kind="warning", h=96):
    fg = {"warning": "warning", "danger": "danger", "info": "muted",
          "connected": "connected"}.get(kind, "muted")
    s.rect(x, y, w, h, "accent_soft", CARD_R)
    s.icon("info" if kind == "info" else "alert", x + 26, y + (h - 48) / 2, 48, fg)
    s.tc(x + 92, y + h / 2, text, role="FEEDBACK", color=fg)


# ---------------------------------------------------------------------------
# 仪表缩略图 / 仪表效果图共用绘制（严格 1920:532 比例）
# ---------------------------------------------------------------------------
def cluster_base(s, x, y, w, theme="night", variant="music"):
    """在给定 x,y,w（高按 1920:532 推算）内画仪表版式示意。返回 (h, scale)。"""
    h = w * CLUSTER_H / CLUSTER_W
    k = w / CLUSTER_W
    cid = s.clip(x, y, w, h, 6)
    s.add(f'<g clip-path="{cid}">')
    # 底色
    if variant == "image":
        g = s.gradient("#101C2E", "#1C3350")
        s.rect(x, y, w, h, g)
        # 极光山峦（纯 SVG 示意，不引用任何真实图片资源）
        s.path(f"M{x} {y+h*0.72} Q{x+w*0.18} {y+h*0.36} {x+w*0.36} {y+h*0.66} "
               f"T{x+w*0.72} {y+h*0.58} T{x+w} {y+h*0.68} L{x+w} {y+h} L{x} {y+h} Z",
               fill="#223C5C", stroke=None, sw=0)
        s.path(f"M{x} {y+h*0.86} Q{x+w*0.22} {y+h*0.62} {x+w*0.45} {y+h*0.84} "
               f"T{x+w} {y+h*0.8} L{x+w} {y+h} L{x} {y+h} Z",
               fill="#0E1D30", stroke=None, sw=0)
        for i in range(6):
            s.path(f"M{x+w*(0.06+i*0.15)} {y+h*0.12} q{w*0.05} {h*0.12} 0 {h*0.3}",
                   stroke="#3E7BB6", sw=2.5*k+1.2, opacity=0.35)
    elif variant == "map":
        s.rect(x, y, w, h, "#0C1622")
        # 示意路网（明确标注非真实车道级视频）
        for i in range(7):
            yy = y + h * (0.12 + i * 0.14)
            s.line(x, yy, x + w, yy, "#1D344A", 1.4)
        for i in range(11):
            xx = x + w * (0.04 + i * 0.092)
            s.line(xx, y, xx, y + h, "#1D344A", 1.4)
        s.path(f"M{x+w*0.18} {y+h*0.86} L{x+w*0.38} {y+h*0.55} "
               f"L{x+w*0.55} {y+h*0.55} Q{x+w*0.62} {y+h*0.55} {x+w*0.66} {y+h*0.3} "
               f"L{x+w*0.82} {y+h*0.16}",
               stroke="#1368FB", sw=9*k+2, opacity=0.95)
        s.circle(x + w * 0.38, y + h * 0.55, 7*k+2, "#FFFFFF")
    else:
        g = s.gradient("#0B121B", "#111C28")
        s.rect(x, y, w, h, g)
        # 中部地图淡底（完整自定义主题）
        if variant == "custom":
            s.rect(x + w * 0.30, y + h * 0.06, w * 0.40, h * 0.88, "#12283C", 4)
            for i in range(4):
                s.line(x + w * 0.30, y + h * (0.22 + i * 0.18),
                       x + w * 0.70, y + h * (0.22 + i * 0.18), "#1D3B56", 1.2)

    # ---- 左：速度表（抽象几何版式示意，不冒充原厂 GT 表素材）----
    cx, cy = x + w * 0.115, y + h * 0.52
    r = h * 0.34
    s.path(f"M{cx-r*0.92:.1f} {cy+r*0.38:.1f} A{r:.1f} {r:.1f} 0 0 1 {cx+r*0.92:.1f} {cy+r*0.38:.1f}",
           stroke="#33506B", sw=3.5*k+1.4)
    for i in range(9):
        import math
        a = math.pi * (1.16 - i * 0.085)
        x1, y1 = cx + r * 0.86 * math.cos(a), cy - r * 0.86 * math.sin(a)
        x2, y2 = cx + r * math.cos(a), cy - r * math.sin(a)
        s.line(x1, y1, x2, y2, "#4A6A88", 2*k+1)
    s.text(cx, cy + h * 0.02, "68", size=74*k+14, role="PAGE_TITLE",
           color="#E5FFFFFF", anchor="middle")
    s.text(cx, cy + h * 0.15, "km/h", size=20*k+7, role="DESCRIPTION",
           color="#99FFFFFF", anchor="middle")
    s.text(cx, cy + h * 0.30, "D", size=26*k+8, role="VALUE",
           color="#7BD89B", anchor="middle")

    # ---- 中：驾驶信息条（示意）----
    s.text(x + w * 0.30, y + h * 0.16, "巡航 100", size=18*k+7, role="DESCRIPTION",
           color="#99FFFFFF")
    s.text(x + w * 0.30, y + h * 0.90, "回收 · 中", size=18*k+7, role="DESCRIPTION",
           color="#99FFFFFF")
    s.line(x + w * 0.30, y + h * 0.84, x + w * 0.44, y + h * 0.84, "#7BD89B", 4*k+1.5)

    # ---- 右：音乐区（原厂 Media_Text：X=1440 / 宽 280 / 容器高 532）----
    # 逐行沿 140px 候选面板坐标表（面板 Y=318–458）排布，坐标与字号随 k 缩放。
    # 依据 docs/Cluster双模式与原厂多行歌词研究-20261111.md:21-31；未实车验收。
    mx = x + w * (1440 / 1920)
    mw = w * (280 / 1920)
    tx = mx + mw * 0.04
    s.line(mx, y, mx, y + h, "#2A3E52", 1.4, dash="6 6")
    s.line(mx, y + 318*k, mx + mw, y + 318*k, "#3A5570", 1, dash="4 6")
    s.line(mx, y + 458*k, mx + mw, y + 458*k, "#3A5570", 1, dash="4 6")
    rows = [
        ("晴天",               318, 22, "#E5FFFFFF"),  # 标题
        ("周杰伦",             344, 16, "#99FFFFFF"),  # 歌手
        ("故事的小黄花",        364, 14, "#99FFFFFF"),  # 上 1 行 · 已播放
        ("从出生那年就飘着",    382, 22, "#FFFFFF"),    # 当前句 · 固定第 2 行
        ("童年的荡秋千",        408, 14, "#99FFFFFF"),  # 下 1 行
        ("随记忆一直晃到现在",  426, 12, "#80FFFFFF"),  # 下 2 行
    ]
    for txt, ry, fs, col in rows:
        sz = max(fs * k, 9)
        s.text(tx, y + (ry + fs * 0.88) * k, txt, size=sz,
               role="DESCRIPTION", color=col)
    # 当前句标记（蓝色小点，不占用文字宽度）
    s.circle(mx + mw * 0.02, y + 394*k, max(3*k, 2), "#1368FB")
    # 短进度条（面板内 Y=124 → 绝对 442）
    s.rect(tx, y + 442*k, mw * 0.55, max(4*k, 2), "#1368FB", 2)
    s.rect(tx + mw * 0.55, y + 442*k, mw * 0.37, max(4*k, 2), "#33465C", 2)

    # 封面保留区（示意边界，原厂封面与外层容器不替换）
    csize = mw * 0.45
    s.rect(tx, y + 150*k, csize, csize, "#1B2C3E", 4,
           stroke="#3A5570", sw=1.4)
    s.icon("image", tx + csize * 0.28, y + 150*k + csize * 0.28,
           csize * 0.44, "#4A6A88")

    # 右侧原厂保留区（示意）
    s.line(x + w * (1720 / 1920), y, x + w * (1720 / 1920), y + h, "#2A3E52", 1.4, dash="6 6")
    s.text(x + w * (1740 / 1920), y + h * 0.18, "胎压 / 能耗", size=18*k+6,
           role="DESCRIPTION", color="#99FFFFFF")
    s.text(x + w * (1740 / 1920), y + h * 0.28, "驾驶模式", size=18*k+6,
           role="DESCRIPTION", color="#99FFFFFF")
    s.text(x + w * (1740 / 1920), y + h * 0.38, "原厂保留", size=18*k+6,
           role="DESCRIPTION", color="#7BD89B")

    # 角标：明确示意，不冒充原厂素材
    s.rect(x + 12, y + 10, 250*k+90, 34*k+16, "#99000000", 4)
    s.tc(x + 12 + (250*k+90)/2, y + 10 + (34*k+16)/2, "版式示意 · 非原厂素材",
         size=13*k+7, role="DESCRIPTION", color="#FFFFFF", anchor="middle")
    s.add("</g>")
    s.rect(x, y, w, h, "none", 6, stroke="border", sw=BORDER_W)
    return h, k
