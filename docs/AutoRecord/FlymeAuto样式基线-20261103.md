# AutoRecord FlymeAuto 样式基线

> 2026-11-04 追加：界面资源密度沿CarPlay的 `L7UiDensity.DEFAULT=280`（原生系统160dpi不变），标题24sp、标签20sp、说明16sp随应用密度与系统字体缩放；首页/录像库/设置统一左侧164dp导航。此条覆盖旧的首页三Tab方案，见[离线整改](离线修复与回归-20261104.md)。

日期：2026-11-03。版本：v1.0（可直接实现稿）。依据：[逐页规格](页面功能与设置规格-20261103.md)、[需求与交接](需求与DeepSeek交接-20261103.md)。

**本轮只读源码取 token，未实现、未编译、未安装、未连接车辆、未跑 Lint。** 文中每个数值均标注来源 `file:line`；行号对应**当前工作树（含未提交改动）**，实现时必须重新按符号定位，不得当成固定提交证据。标"**建议**"的条目是本稿提出的新值，不是既有源码事实。

## 1. 结论：独立 App 抄哪一套

**结论：色阶数值抄 `service_theme`（AutoService）一套；组件形状与尺寸口径抄 CarPlay 的 L7 组件口径。** 这不是折中，而是被四个源码事实分别决定的：

| 事实 | 证据 | 后果 |
| --- | --- | --- |
| CarPlay 的设置卡片/导航色阶与 `service_theme` **数值完全相同** | `l7_settings_surface.xml:3-9`（#EBF1F8/#F6F8FC/#FFFFFF/#E0E7F0）对标 `service_theme.xml:5-7`（#EBF1F8/#F6F8FC/#FFFFFF）；夜间 `l7_settings_surface.xml:3-9` 对标 `service_theme.xml:3-7`（#1E2732/#28313C/#303A47/#3B4756） | "抄 service 会丢 CarPlay 风格"不成立，二者是同一套 Flyme 设置色阶 |
| CarPlay 首页 `product_ui_background` 是**投屏底色**（#D3DBE5/#20272E），不是设置页底色 | `galaxy/mobile/.../values/product_ui.xml:4`、`values-night/product_ui.xml:3`；设置页实走 `L7SettingsStyle.content` 渐变 `l7_settings_surface.xml:5-6` | AutoRecord 若照抄 `product_ui_background` 会得到大面积灰蓝底，与既有设置页不一致 |
| L7 组件全部 `internal` 且绑 `com.shilapi.xcertplay.host.R`，且 `config_l7_product_ui` 只在 CarPlay 为 true | `L7Typography.kt:10`、`L7Ui.kt:19`、`L7Components.kt:14`、`L7SettingRow.kt:15`、`L7Header.kt:10` 均 internal 且 import `host.R`；`galaxy/mobile/.../config.xml:3`=true、`galaxy/common/.../config.xml:3`=false | 独立进程**不能引用**这些类，只能按口径重写；先例即 `service_theme.xml:3` 与 `ClusterUi.kt:11` 注释"独立App不依赖CarPlay宿主" |
| 已有两个独立 App 各自落一套 res | `galaxy/service/.../ui/ServiceUi.kt:13-21`、`galaxy/cluster/.../ui/ClusterUi.kt:13-21` | AutoRecord 必须自建 `values/`+`values-night/` 资源，由 `:record` 模块持有 |

语义色（accent/text/muted/disabled/selected/ripple/connected/warning/danger/primaryText）在 CarPlay 里的**实际生效值**来自 `galaxy/mobile`（app 模块覆盖 library 同名项；既有构建产物 `galaxy/mobile/build/.../merged.dir/values/values.xml` 与 `mobile/.../product_ui.xml` 逐项一致）；`galaxy/common/.../l7_style.xml:5-9` 只是回退值（control=surface、danger=#FF6B6B），**不是 CarPlay 显示值，不得当基准抄**。

## 2. 颜色 token（日 / 夜）

**来源路径约定**：表中 `product_ui.xml` 均指 `galaxy/mobile/src/main/res/values/product_ui.xml` 与 `values-night/product_ui.xml`（CarPlay 实际生效值）；`l7_settings_surface.xml` 与 `service_theme.xml` 同理分属 `galaxy/common/src/main/res/values{,-night}/` 与 `galaxy/service/src/main/res/values{,-night}/`。"日 / 夜"两列分别取这两个文件。

| Token | 日 | 夜 | 来源（日 / 夜） | AutoRecord 用法 |
| --- | --- | --- | --- | --- |
| background | `#F6F8FC` | `#28313C` | `l7_settings_surface.xml:5` / `:5`；同 `service_theme.xml:4` / `:3` | 页面内容区底色（渐变顶） |
| backgroundAlt | `#EAF0F6` | `#222A34` | `l7_settings_surface.xml:6` / `:6` | 内容区渐变底（`L7SettingsStyle.kt:12-14`） |
| navigation | `#EBF1F8` | `#1E2732` | `l7_settings_surface.xml:3` / `:3` | 导航栏渐变顶 / `ServiceUi` 渐变顶 |
| navigationAlt | `#DDE6F1` | `#161F29` | `l7_settings_surface.xml:4` / `:4` | 导航栏渐变底 |
| surface（卡片） | `#FFFFFF` | `#303A47` | `l7_settings_surface.xml:7` / `:7` | 分组卡、空态卡、段落卡 |
| control（控件底） | `#E5EBF2` | `#15191F` | `galaxy/mobile/.../product_ui.xml:6` / `:5` | 次按钮底、输入框底、chip 未选中底 |
| border（卡片描边） | `#E0E7F0` | `#3B4756` | `l7_settings_surface.xml:8` / `:8` | 卡片 1dp 描边（`L7SettingsStyle.kt:40`） |
| divider（行分隔） | `#D5DFEB` | `#344151` | `l7_settings_surface.xml:9` / `:9` | 卡内相邻设置行分隔线 |
| text | `#E5000000` | `#E5FFFFFF` | `product_ui.xml:11` / `:10` | 标题、标签、数值、方向字 |
| muted | `#99000000` | `#99FFFFFF` | `product_ui.xml:12` / `:11` | 说明、副标题、未选中导航文字、已播放 |
| disabled | `#66000000` | `#66FFFFFF` | `product_ui.xml:13` / `:12` | 置灰选项文字、不可用原因行的图标 |
| accent | `#1368FB` | `#0088FF` | `product_ui.xml:8` / `:7` | 主按钮底、选中导航、开关选中轨、游标 |
| accentSoft | `#EAF2FF` | `#263F60` | `service_theme.xml:11` / `:10` | 徽标底、图标底、ripple 前景 |
| selected | `#141368FB` | `#240088FF` | `product_ui.xml:9` / `:8` | 选中导航项底、列表选中行 |
| ripple | `#201368FB` | `#330088FF` | `product_ui.xml:14` / `:13` | 行/按钮按压水波（`L7Ui.kt:41-55`） |
| connected | `#247744` | `#7BD89B` | `product_ui.xml:10` / `:9` | 已连接/录制就绪/横向辅助启用 |
| warning | `#A65C00` | `#FFB34D` | `product_ui.xml:15` / `:14` | 缺路、空间不足、等待原厂释放、部分有效 |
| danger | `#C83D43` | `#FF747A` | `product_ui.xml:16` / `:15` | REC、录制失败、不可写、损坏 |
| primaryText | `#FFFFFF` | `#FFFFFF` | `product_ui.xml:17` / `:16` | 主按钮/强调底上的文字 |
| track（开关未选中轨） | `#C3CDD9` | `#596676` | `service_theme.xml:12` / `:11` | 开关未选中轨（见 §5 差异说明） |

**建议**：AutoRecord 把上表前 9 行落为 `record_background / _surface / _border / _divider / _navigation…` 自有命名，后 10 行沿用 `record_text/_muted/...`；**禁止**把 `galaxy/common/.../product_ui.xml:3-10`（#0C111B 深色宿主默认）当夜间值——那是上游宿主外观，不是 Flyme 设置色阶。

## 3. 文字角色

| 角色 | 字号 | 行高 | 字重 | 颜色 | 典型用途 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| PAGE_TITLE | 24 | 32 | medium | text | 64dp 标题栏标题、页面大标题 | `L7Typography.kt:12` |
| SECTION_TITLE | 18 | 26 | medium | muted | 设置分组标题 | `L7Typography.kt:13` |
| LABEL | 20 | 28 | regular | text | 设置行标题（每行 1 个） | `L7Typography.kt:14` |
| VALUE | 18 | 26 | regular | muted | 行内当前值 | `L7Typography.kt:15` |
| DESCRIPTION | 16 | 24 | regular | muted | 行副标题、分组说明 | `L7Typography.kt:16` |
| FEEDBACK | 16 | 24 | regular | muted（error 时 danger） | 错误/结果反馈、卡片页脚 | `L7Typography.kt:17`、`L7SettingRow.kt:47-51` |
| 导航项文字 | 19 | — | medium | selected→accent / 正常→text | 左侧导航 | `L7NavigationRail.kt:85-92` |
| 徽标文字 | 13 | — | regular | 随状态 | REC/存储/连接徽标 | 参照 `ServiceActivity.kt:61-63`（13f+12/7 padding）**建议** |

行高随系统字体缩放（`L7Typography.kt:25`；`includeFontPadding=false` `:24`）。

**禁止用法**：正文/说明用 PAGE_TITLE；一行出现两个 LABEL；用 VALUE 承载错误原因（必须 FEEDBACK+error）；用字号表达"更重要"而绕过 role 表；用 `textScaleX`/自定义字体族替代 `sans-serif`/`sans-serif-medium`（`L7Typography.kt:23`）。

## 4. 尺寸与间距

| 项 | 值 | 来源 | 说明 |
| --- | --- | --- | --- |
| 标题栏高度 | 64dp | `L7Header.kt:21-22` | 左右槽各 64dp（`:26`、`:32`），标题居中 padding 8dp（`:30`） |
| 图标按钮 | 64dp 命中 / 32dp 可见 | `L7Components.kt:22-24`（padding 16、min 64）、`:37`（图标 32dp） | 返回按钮即此规格 |
| 设置行高 | 80dp；含说明/反馈 96dp | `L7SettingRow.kt:31`、`:82`、`:85-90` | 实测文字过高自动增高 |
| 行内 padding | 20dp 左右 / 16dp 上下 | `L7SettingRow.kt:32` | 图标 32dp + 16dp 右边距（`:37`） |
| 右侧配件槽 | 64dp（开关行 82dp） | `L7SettingRow.kt:39`、`L7Components.kt:110` | 开关在文字前（leading） |
| 卡片圆角 / 描边 | 4dp / 1dp | `L7SettingsStyle.kt:39-40`、`L7Ui.kt:118` | 见 §1：取 CarPlay 卡片口径，不取 `ServiceUi.kt:33` 的 6dp |
| 卡片内分隔线 | 1dp，左起 textInset、右至 20dp | `L7SettingsCard.kt:23-33` | 仅行与行之间 |
| 分节外距 | 24dp | `L7SettingsSection.kt:21` | 一级页卡片间 16dp（`GalaxySettingsActivity.kt:553`） |
| 内容区 padding | 16/24dp（窄屏 16） | `GalaxySettingsActivity.kt:380,383` | `screenWidthDp<600` 取 16 |
| 主按钮最小高 | 72dp（紧凑 48dp） | `L7Ui.kt:68` | 文字 20sp；紧凑 13sp（`:65`）、内距 8/0（`:83-84`） |
| 输入框 | 最小高 64dp、圆角 4dp、1dp 描边、内距 16dp | `L7Ui.kt:96-112` | 焦点描边转 accent（`:105`） |
| 开关视觉 | 58×30dp、圆角 3dp、滑块 11×20dp、内缩 5dp；控件高 64dp、`switchMinWidth` 58dp | `galaxy_switch_track.xml:5-7`、`galaxy_switch_thumb.xml:3-8`、`L7Components.kt:105`、`L7SwitchStyle.kt:11` | 命中区是整行 80dp（`L7Components.kt:113`） |
| 导航栏 | 计算宽 164dp（内层 132dp + 两侧 16dp）；项高 90dp；图标 48dp | `L7ProjectionNavigation.kt:24-25`、`L7NavigationRail.kt:53,66`、`l7_ui.xml:12-13` | `l7_nav_width=125dp`（`l7_ui.xml:11`）**当前源码无任何引用**，不得当实际值 |
| 导航项内距/间距 | padding 4/13dp，margin 8/0/8/6dp | `L7NavigationRail.kt:54,94` | 选中底圆角 4dp（`:57-58`） |
| 连接状态点 | 13dp 圆点，2dp 描边，覆于图标右上 | `L7NavigationRail.kt:28-31,83` | 不挤动图标 |
| 反馈提示条 | 最小高 64dp、padding 20/16dp、宽 86%（≤680dp）、距顶 24dp、4.5s 自动消失 | `L7Notice.kt:19-39` | `live region polite`（`:33`） |
| 弹窗按钮 | 64dp 高 | `L7Components.kt:121` | 正/负/中三键统一 |
| 触控最小尺寸 | 独立可点区 ≥64dp；设置行整体 ≥80dp | 同上 | 不引入 <48dp 的独立点击区；行内小图标标 `IMPORTANT_FOR_ACCESSIBILITY_NO`（`L7Components.kt:70`、`L7NavigationRail.kt:79`） |

**dp 与 280dpi**：CarPlay 用 `L7UiDensity` 覆盖**自身界面**密度，预设 240/280/320、默认 280（`L7UiDensity.kt:11-12`），且只在 `config_l7_product_ui=true` 时生效（`:26`）。此时 1dp = dpi/160 px = 280/160 = **1.75px**（80dp≈140px、64dp≈112px、12dp≈21px）。这只是资源换算：源码注释明确"系统密度、视频像素与物理屏幕参数保持独立"（`L7UiDensity.kt:9`），本文件**不给出任何屏幕物理像素值**。AutoService 与 AutoCluster 均未做密度覆盖（`galaxy/service`、`galaxy/cluster` 中无 `densityDpi`/`applyOverrideConfiguration`）。**建议** AutoRecord 同独立 App 先例不做覆盖、直接用系统密度；若后续为对齐 CarPlay 视觉需引入 280，按 `L7UiDensity.kt:34-43` 口径换算 dp 宽高，且视频格尺寸不受影响。

## 5. 组件清单与状态

| 组件 | 口径 | 来源 | AutoRecord 复用位置 | 禁用项 |
| --- | --- | --- | --- | --- |
| 分组卡 | 4dp 圆角 + 1dp border 描边，`clipToOutline` | `L7SettingsCard.kt:14-19`、`L7SettingsStyle.kt:38-42` | P5 六行、P6–P12 每组设置 | 不加 elevation/阴影/模糊；不改 6dp |
| 设置行（只读行） | 80dp、LABEL+DESCRIPTION，无箭头 | `L7SettingRow.kt:31-43`、`L7Components.kt:48-55` | P4 信息行、P11 诊断只读行 | 只读行不得装开关 |
| valueRow | 行 + 右侧 VALUE + 箭头，整行可点 | `L7Components.kt:77-78`、`:65-75` | 存储位置、编码格式、分辨率、帧率、码率、倍速、日夜 | 值同步更新必须走 `setValue`，不重建行 |
| choice 选择器 | 行 + 单选弹窗，确认才提交，取消不保存 | `L7Components.kt:80-94,128-158` | 所有枚举型设置（S-STOR/S-ENC/S-VEH/S-VIEW） | 不允许下拉框另立风格；未变化时"确定"禁用（`:146`） |
| switch 行 | 开关在文字前、整行点按、槽 82dp | `L7Components.kt:100-116` | P8 自动录制/后台/息屏、P10 轨道展开 | 服务回填必须走 `setSwitchChecked`（`L7SettingRow.kt:66-70`）避免回环 |
| 主/次按钮 | 主=accent 底+primaryText；次=control 底+text；72dp/48dp | `L7Ui.kt:57-92`、`L7Components.kt:33-46` | 开始/停止录制、清理、重新探测、跳转 | 不用字符/emoji 当按钮图标（`L7Icons.kt:9` 注释） |
| 输入框 | 4dp 圆角填充 + 1dp 焦点描边 | `L7Ui.kt:95-114` | P6 配额、P9 阈值自定义输入 | 不用 Material 下划线样式 |
| note（常规/警告） | 16sp muted，padding 4/12dp；warning 转 danger 色 | `L7Components.kt:57-60` | "下一段生效"、规则说明、许可声明 | 现有 note **只有 danger**，没有 warning 差异，见 §7 建议 |
| 行内提示 | FEEDBACK 角色 + `live region polite` | `L7SettingRow.kt:47-51` | 设置校验失败、写入自检结果 | 错误必须就地显示，不弹 Toast 代替 |
| 反馈提示条 | 顶部 86% 宽浮层，4.5s | `L7Notice.kt:14-40` | 已保存 N 段、探测完成 | 长错误不靠它，改就地错误行 |
| 空态 | 卡片 + 说明 + 主/次按钮（结构同 `L7SettingsSection.actions`） | `L7SettingsSection.kt:26-33` | P2 无录像/无匹配/介质不可读/索引重建 | 不得用空态冒充索引重建（规格 §5 P2 交互 1） |
| 加载/进度 | 行内 FEEDBACK 文案 + 主按钮 `disabled` | `L7Ui.kt:74-77`（禁用文字色）、`L7SettingRow.kt:72-77`（配件 alpha 0.4） | 索引重建 M/N、seek 定位中、导出进度 | 不新增转圈组件，不改既有布局高度 |
| 错误行 | FEEDBACK + danger + 复制到诊断入口 | `L7SettingRow.kt:47-51` | 编码器创建失败、介质不可写 | 错误必须带来源（前/后/左/右、介质卷名、编码器名） |
| 折叠明细 | valueRow 切换 + 行集合显隐 | `L7SettingRow.kt:79-83`（空文本即 GONE，行高回落 80dp） | P1 数据条两行/一行、P3 事件轨道展开 | 折叠不得改变录制范围（规格 P1 交互 1） |
| 图标 | 24px 画布、1.7px 描边、圆头圆角，自绘 | `ServiceUi.kt:64-70`（`strokeWidth=1.7f`、`Cap.ROUND`、`Join.ROUND`、`scale(width/24f)`） | 导航、工具栏、轨道图例、方向标 | 不复制 OEM 素材/截图图标；不用第三方图标库 |

**开关轨色差异（必须选一个）**：CarPlay 未选中轨用 `product_ui_border` 半透明（`galaxy_switch_track.xml:13-15`），AutoService 用实体 `service_track`（`ServiceUi.kt:59`）。几何完全一致（58×30、圆角 3、滑块 11×20 内缩 5）。**建议 AutoRecord 取 `service_track` 实体灰**：AutoRecord 卡片底色与 service 同级，半透明轨在深色卡上对比不足。

## 6. 页面骨架

**一级页（P1/P2/P5）**：`root=Horizontal`，左侧导航面板 164dp（内层 132dp + 两侧 16dp padding；`L7ProjectionNavigation.kt:24-25,109-117`），面板背景为 navigation 渐变 + 右缘 1dp divider（`L7SettingsStyle.kt:16-28`）；正文以 `marginStart=164dp` 起（`GalaxySettingsActivity.kt:457-461`），正文容器套 content 渐变（`L7SettingsStyle.kt:12-14`），内层顺序为：标题区 padding(16,24,16,8) 内含 64dp 标题栏 → `ScrollView` 内容 padding(16,16,16,24)（`GalaxySettingsActivity.kt:435-456`）。一级页**不创建返回按钮**，左槽留空（`L7Header.kt:9-10,23-26`）。

**二级页（P3/P6–P12/P14）**：无导航栏，正文占满；64dp 标题栏（左返回 + 居中标题 + 右操作）→ 分组卡，卡间 24dp（`L7SettingsSection.kt:21`），分组结构 = SECTION_TITLE + 可选 DESCRIPTION + 卡 + 可选页脚 FEEDBACK（`:7-23`）。P3 沉浸态按规格 §3 收起导航，用同一 64dp 标题栏返回。

**竖屏 P1 布局与色阶一致性**：2×2 视频格（16:9、格间 12dp，规格 §2.1）→ 数据条（两行，可折一行）→ 操作区（主/次按钮）。保持同一色阶的做法是**只用既有 token 做"框"，不改画面**：格子外框与相邻格分隔用 1dp `divider`/`border`；格内叠加的方向标签、状态字、缺路角标自带中性底 `#99000000` + `#FFFFFF` 文字（**建议**新增，见 §7），因为叠加层下面是不可控亮度的视频，既有 `text/muted` 在亮画面上不可读；标题栏内的 REC/存储/连接徽标用 `accentSoft` 底 + 语义色文字（`ServiceActivity.kt:61-63,188-189` 同法），日夜各自成立且完全不覆盖画面。**禁止**给视频加圆角裁切以外的形态变换、色彩滤镜、模糊背景或暗角。

## 7. AutoRecord 需新增的语义色（均为**建议**）

原则：**不新增第二套调色板**，只允许"既有语义色 + 形状/透明度"组合，除下表明确标注的 1 个中性叠加色外不引入新色相。

| 名称 | 日 | 夜 | 与既有关系 | 用途 |
| --- | --- | --- | --- | --- |
| `record_rec` | `#C83D43` | `#FF747A` | **等于** danger | 录制中红点（直径 10dp）与"录制中"文字；全 App 唯一红点用途 |
| `record_missing` | `#A65C00` | `#FFB34D` | **等于** warning | 缺路角标 `缺路 mm:ss–mm:ss`、部分有效 |
| `record_locked` | `#1368FB` | `#0088FF` | **等于** accent | 已锁定（锁≠错误，不得用 danger） |
| `record_unplayed` | `#E5000000` | `#E5FFFFFF` | **等于** text | 未播放（强） |
| `record_played` | `#99000000` | `#99FFFFFF` | **等于** muted | 已播放（降权） |
| `record_broken` | `#C83D43` | `#FF747A` | 等于 danger | 损坏、不可写、录制失败 |
| 轨：刹车 | danger | danger | 实线 12dp 条带 | P3 事件轨 1 |
| 轨：油门 | accent | accent | 实线条带（高度随踏板值，规格 §R07-3） | P3 事件轨 2 |
| 轨：横向辅助启用 | connected | connected | **实心**圆点/短条 | P3 事件轨 3 |
| 轨：横向辅助退出 | connected | connected | 同色 **空心**点（2dp 描边、无填充） | P3 事件轨 4 |
| 轨：巡航启用 | warning | warning | **实心**菱形（规格 §R07-1 的离散标记） | P3 事件轨 5 |
| 轨：巡航退出 | warning | warning | 同色 **空心**菱形 | P3 事件轨 6 |
| `overlay_scrim` | `#99000000` | `#99000000` | 新增中性色；`product_ui_border` 日间为 `#33000000`，作为文字底太浅不可复用 | 视频格内文字/角标的底；日夜同值，因为它压在不透明视频上 |
| `overlay_label` | `#FFFFFF` | `#FFFFFF` | **等于** primaryText | `overlay_scrim` 上的文字 |

**事件轨道编码纪律**：6 类只用 3 个色相（danger/accent×2 + connected/warning），启用与退出**同色不同形状**——这样既满足"轨道有名称、颜色与清晰选中态"（规格 §R07-2），又不会出现 6 套新颜色。轨道选中态用 `selected` 叠色，不新增"选中轨道色"。禁止用红/绿表达"启用/退出"以外的含义（例如不要用绿表示"油门轻踩"）。

## 8. 页面 → 组件/Token 映射

| 页面 | 复用组件与 token | 缺什么 |
| --- | --- | --- |
| P1 实时画面 | 64dp 标题栏 + valueRow(折叠) + 主/次按钮 + 分组卡(数据条) + `divider` 描边 + `overlay_scrim/label` | 视频格容器（既有代码无 16:9 四格布局）；标题栏右槽 64dp 装不下 3 个徽标（`L7Header.kt:32`）→ 需扩展为内容自适应宽，高度仍 64dp |
| P1a 单路放大 | 四段切换（`choice` 的行内版或 segment，用 `selected`/`accent`）+ 主按钮 | 无 segment 控件先例，建议用 4 个紧凑次按钮（`L7Ui.kt:65,68` compact） |
| P2 录像库 | sectionTitle 组头 + 双列卡片（`ThemeLibraryPage.kt:21,63-72` 口径：卡 padding 12dp、标题 18sp、副文 13sp、48dp 菜单键、dividerHeight 14dp）+ 角标（§7）+ 空态（`L7SettingsSection.actions`） | 筛选 chip 行无先例（建议：`control` 底 + 选中 `accent`/`selected`，高 44~48dp）；卡片缩略图容器 |
| P3 同步回放 | 64dp 标题栏（返回 + `⋯`）+ 五段切换 + 数据条 + 播放控件（紧凑次按钮组）+ 事件轨（§7）+ `FEEDBACK` 定位中 | 进度条/游标：既有代码无滑块组件（`L7SwitchStyle` 只是开关）→ 需自绘 12dp 轨 + 24dp 拖点，色用 `track`+`accent` |
| P4 片段操作面板 | 底部卡片（`surface` + 4dp 圆角 + 1dp `border`）+ 只读行（`L7SettingRow`）+ valueRow/按钮 + 危险行用 `danger` 文字 | 底部 sheet 无先例（建议：`RippleDrawable` scrim `#99000000` + 卡从底部升起的静态布局，不加动画库） |
| P5 设置一级 | 164dp 导航（3 项；`L7NavigationRail.kt:53-95` 口径）+ 64dp 标题 + 6 张 `categoryRow` 卡（`L7Components.kt:97-98`）+ 行尾 `VALUE` 摘要 + 页脚"关于" | `L7NavigationRail` 的 items 是硬编码 5 项（`L7NavigationRail.kt:42-48`）→ AutoRecord 需自建 3 项 rail，保留 90/48/19sp/选中底 4dp 口径 |
| P6 存储与保存 | `choice`(介质/配额/分段) + `switchRow`(循环覆盖) + `valueRow` + `note` + 错误行 | 卷列表弹窗需带"刷新"动作（`select` 的 `optionEnabled` 可用，`L7Components.kt:130`）；介质不可读的行需 danger 状态 |
| P7 编码与画质 | `choice` 三组 + `note`(软编码代价) + 只读 `valueRow`(实际编码器) + 次按钮"重新探测" + 置灰 `choice`（`optionEnabled=false`） | 置灰原因需要独立 DESCRIPTION 行（`L7SettingRow` 只有 description/feedback 两个可写位，够用但需约定：原因写 feedback） |
| P8 录制与后台 | `switchRow` ×5 + 只读镜像行 + 次按钮"跳转 AutoService 设置" | 只读镜像不能是开关（会误触）→ 用只读行 + `VALUE` |
| P9 车辆数据与事件 | `choice`(间隔) + 锁定只读行（最小集）+ `switchRow`(扩展项) + 输入框(阈值) + `note`(历史不回算) | 锁定行需"锁"图标 + `disabled` 语感，但文字必须保持 text 色可读（`L7SettingRow.kt:72-77` 只减控件 alpha，符合要求） |
| P10 回放与显示 | `choice` ×5 + `switchRow` ×2 | 日夜模式"跟随系统/浅/深"要在 `:record` 自己的 `values`+`values-night` 上都成立（不依赖系统 `uiMode` 硬判断，参照 `ClusterUi.kt:13-21` 先例） |
| P11 诊断 | `L7SettingsSection` 多个分组卡 + 只读行 + 触发按钮（`actionButton`）+ 可折叠明细 + 日志导出次按钮 | 需要"只读表格"（能力清单 6 列）：`L7ProbeTable` 属 CarPlay 模块、不可跨用，建议分组卡内每行 = 左 LABEL + 右 VALUE，长值换行走 `SettingText` 的 inline 判定（`L7SettingRow.kt:116-117`） |
| P12 关于 | `L7SettingsSection` + 只读行 + `note` | 无 |
| P13 首启引导 | 全屏 `surface` 卡 + PAGE_TITLE + DESCRIPTION + 主/次按钮 | 无分页指示先例（建议：3 个 8dp 圆点，选中 `accent`） |
| P14 导出 | `choice` + `switchRow`(叠加数据条) + 进度行 + 主/次按钮 | 系统文件选择器是平台能力，不自绘 |

## 9. 禁用项汇总

1. 不复制 OEM 素材、EVCam 图标、三张截图里的厂牌 Logo/第三方图标；图标按 24px 画布 1.7px 描边自绘（`ServiceUi.kt:64-70`）。
2. 不加大阴影、模糊背景、elevation 动效；`L7Ui.surface`/`L7SettingsStyle` 均只绘制静态背景（`L7SettingsStyle.kt:10` 注释），仅提示条有 12dp elevation（`L7Notice.kt:24`）。
3. 不引入现有代码未用的 UI 框架（无 Material Components、无 Compose、无动画库）；全部原生 `View` + `GradientDrawable`/`RippleDrawable`（`L7Ui.kt:8-10`、`ServiceUi.kt:5`）。
4. 不给视频加滤镜、模糊、暗角、非等比拉伸、圆角以外的变形裁切。
5. 不新建第二套调色板：新增语义色只能落在 §7 表内，且除 `overlay_scrim/label` 外必须是既有色相等值。
6. 不用红/绿表示非状态含义；锁、未播放、已播放用 accent/text/muted 而非 danger。
7. 不把 `galaxy/common/.../l7_style.xml:5-9` 的回退值当基准（CarPlay 实际值被 `galaxy/mobile` 覆盖）。
8. 不改系统状态栏/导航栏以外的窗口装饰：状态栏/导航栏色跟 background（`galaxy/mobile/.../themes.xml:14-16`）**建议**照此，避免与内容区割裂。

## 10. 证据边界

| 类别 | 内容 |
| --- | --- |
| 源码存在（本轮逐行读取） | §2/§3/§4 全部数值与 §5 组件口径；`L7NavigationRail` 5 项硬编码；`l7_nav_width=125dp` 无引用；`config_l7_product_ui` 两模块取值；`ServiceUi`/`ClusterUi` 两处独立 App 先例；`galaxy/mobile` 合并后 day 值（既有构建产物，非本轮构建） |
| 本轮未验证 | 未编译、未安装、未跑 Lint、未连接车辆；`galaxy/mobile` 的 `values-night` 合并产物在既有构建目录中不存在，夜间生效值仅以源码为准；未在 8155 竖屏实机上核对任何 dp 视觉效果；未核对 280dpi 覆盖下 AutoRecord 的实际观感 |
| 视觉推断（非实测） | §6 竖屏 P1 的空间分配比例；`overlay_scrim` 0x99 的具体不透明度；chip/进度条/底部面板三段"无先例"组件的取值；§7 轨道形状区分在 12dp 高度下的可辨识度 |
| 需 Lead/实现者裁决 | 标题栏右槽 64dp 与规格 §2.2 三徽标的冲突；`L7NavigationRail` 只能自建；开关轨色在 `border` 与 `service_track` 间取后者；建议新增 `:record` 自有 `values`/`values-night` 与是否引入 280dpi 覆盖 |