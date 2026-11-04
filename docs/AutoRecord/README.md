# AutoRecord 文档入口与状态台账

- 目录：`docs/AutoRecord/`
- 代码：`galaxy/record`（Gradle 模块 `:record`，applicationId 候选 `com.galaxy.autorecord`）
- 最后整理：2026-11-04

用户已恢复连接并确认驻车保持上电。最新现场结果见[现场复测与协同](现场复测与协同-20261104.md)，前阶段见[六项离线整改](离线修复与回归-20261104.md)与[初轮安装记录](安装测试与修复-20261104.md)。车辆订阅、串行授权和四路 PRIVATE 连续预览已接入；录制引擎、回放解码和导出仍未接入。历史 B2 转发路线已被 Camera2 直连决策覆盖。

本文是这个目录的唯一入口，用来回答三个问题：**有哪些文档、代码落到哪一步、哪些还没验证**。文档结论以各自正文为准，本文只做索引与汇总，不新增结论。

## 1. 文档索引

| 文档 | 类型 | 作用 | 状态 |
| --- | --- | --- | --- |
| [现场复测与协同](现场复测与协同-20261104.md) | 实测 | 中继429部署、签名迁移、权限与车辆数据、连续预览性能及未验项 | 持续补录 |
| [Codex与WorkBuddy协作规范](Codex与WorkBuddy协作规范-20261104.md) | 协作 | Hy3编码分工、写入范围、交接/审查与安全边界 | 用户已要求执行 |
| [WorkBuddy存储基础任务书](WorkBuddy存储基础任务书-20261104.md) | 任务 | WB-STORAGE-01片段事务基础 | 已下发，编码候选独立暂存 |
| [协作审查](协作审查-20261104.md) | 审查 | Lead审查意见与接收状态 | 由Codex维护 |
| [需求与DeepSeek交接](需求与DeepSeek交接-20261103.md) | 需求 | 产品定位、三张截图拆解、功能需求 R01–R13、与 AutoService 的责任分工、EVCam 核验摘要 | v0.2（新增 R13 取证主链路与范围声明） |
| [页面功能与设置规格](页面功能与设置规格-20261103.md) | 规格 | 全局规范、信息架构、P1–P14 逐页定义、设置项总表、状态异常矩阵、每页验收点、§14 关键流程与状态机 | **v1.1（本轮评审修订，以此为准）** |
| [评审与优化](评审与优化-20261103.md) | 评审 | 10 条评审发现与决策、功能/UI 诊断、流程优化对比、后续版本池、风险登记册、RTM | 本轮产出 |
| [FlymeAuto样式基线](FlymeAuto样式基线-20261103.md) | 规格 | 昼夜色 token、文字角色、尺寸间距、组件清单、页面→组件映射、禁用项 | v1.0（可直接实现） |
| [编码与存储技术口径](编码与存储技术口径-20261103.md) | 技术 | 硬/软/未知三态判定、Surface 与 YUV 输入、编码参数、存储卷与写盘恢复、14 项车机待核验 | v1.0 |
| [Camera接入链与契约设计](Camera接入链与契约设计-20261103.md) | 设计 | AutoService 摄像头域、权限身份、原厂环视共存、时间戳、契约形状（路线 A：独立 `IAutoCamera`） | 阶段 A 设计稿，代码未写 |
| [开发接入设计](开发接入设计-20261103.md) | 实施记录 | 模块接入、代码分层、已落地 core 组件、构建验证、B0–B5、§15 摄像头直连接入 | 已落地到 B1.5 + 直连采集 |
| [四向相机远程实测](四向相机远程实测-20261103.md) | 实测 | 车机真机结论：只认 cameraId 0–3、JPEG 仅 1280×800、25fps 上限、6 MB 缓冲需 EOI 截断、四路可并发、方位未标定 | 实测稿（含未验项） |
| [DeepSeek任务书](DeepSeek任务书.md) | 交接 | 可直接复制给外部开发者的任务书（起点、边界、验收） | 历史交接件；B0/B1 已由本仓库执行 |

阅读顺序：需求 → 评审与优化 → 逐页规格 → 样式基线 / 技术口径 → 摄像头设计 → 开发接入设计。

## 2. 代码落地映射

模块结构（`galaxy/record/src/main/java/com/galaxy/autorecord/`）：

| 包 | 内容 | 状态 |
| --- | --- | --- |
| `core` | `RecordSettings`（37 字段 + `PrefsSettingsStore`）、`StorageVolumes`/`AndroidStorageVolumes`、`CodecCapabilities`、`SegmentLayout`、`AtomicFiles`、`RecordingState`、`SegmentManifest` | 已落地 |
| `ui` | `RecordActivity`（壳）、`RecordShell`/`RecordUi`/`RecordLayouts`、`LivePage`、`LibraryPage`、`SettingsIndexPage`、`StorageSettingsPage`、`CodecSettingsPage`、`PlaceholderPage`、`SettingRows`、`RecordDialogs` | 已落地（部分页为占位） |
| `recording` | 四路编码会话、取帧、写盘收尾、事件判定、车辆数据落盘 | 未开始（B2–B4） |
| 相机/权限/车辆（现位于 `core`） | `SurfaceCameraSource`（车辆）/`DirectCameraSource`（模拟器JPEG）、车型适配器、`RecordAccessGate`、`AutoServiceRecordFeed` / `AutoVehicleFeed` | 指定L6已验证四路预览、权限代办与车辆订阅，验收边界见现场记录 |
| `data` | `index.db` 可重建索引、缩略图、删除/锁定/导出 | 未开始（B3） |

页面映射：

| 编号 | 页面 | 实现 | 状态 |
| --- | --- | --- | --- |
| P1 / P1a | 实时画面 / 单路放大 | `LivePage` | **已落地**（统一左导航、数据条折叠、预检失败弹层、可回溯估算）；四路PRIVATE连续预览已实测，实际录制仍未接入 |
| P2 | 录像库 | `LibraryPage` | **已落地**（真实扫描、任务导向筛选、日期分组双列、角标 ≤2+`+N`、多选批量操作、四类空态） |
| P3 | 同步回放 | `PlaybackPage` | **已落地**（时间轴/事件轨道/事件摘要/跨段）；画面解码未接入（B4） |
| P4 / P14 | 片段面板 / 导出 | `SegmentActions` | **已落地**（信息/播放/锁定/删除/导出弹层）；导出执行未接入（B5） |
| P5 | 设置 | `SettingsIndexPage` | 已落地，含行驶中只读 |
| P6 | 存储与保存 | `StorageSettingsPage` | 已落地，含可回溯时长与 <2h 警示 |
| P7 | 编码与画质 | `CodecSettingsPage` | 已落地，含三档画质预设与"当前生效组合" |
| P8–P12 | 录制与后台 / 车辆数据与事件 / 回放与显示 / 诊断 / 关于 | 各自页面类 | **已落地** |
| P13 | 首次使用引导 | `OnboardingDialog` | 已落地（一次、可跳过） |

## 3. 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| B0 | 模块建立、权限与 `queries`、应用壳与主题 | 已完成 |
| B1 | P1/P2/P5/P6/P7 页面与设置持久化 | 已完成 |
| B1.5 | v1.1 界面与功能全量（本页所有页面 + core 数据层 + 54 项单测） | **已完成** |
| B2 | Camera2 直连、串行授权、车辆订阅、模拟器适配 | 已实现；离线与模拟器通路验证完成，真实画质与最终车辆复测未验收 |
| B3 | 四路录制引擎 + 段收尾 + 索引写入 | 未开始 |
| B4 | 画面解码回放 + 车辆数据落盘 | 未开始 |
| B5 | 导出拼接与跨段 PTS 重排 + 诊断实测 | 未开始 |

## 4. 不可越过的红线

- 摄像头获 CAMERA 权限后由本应用直连 Camera2；AutoService 负责需要时的授权辅助与能力扫描，车辆数据仍只经 AutoService；本 App 不做 ADB/Root。本条覆盖早期摄像头转发限制。
- 探测不通过就置灰并写明原因，**禁止静默回退**到另一种实现或另一个介质；找不到编码器/卷不可写时拒绝并保持原值。
- 硬/软/未知三态只由 `isHardwareAccelerated()`/`isSoftwareOnly()` 判定，不按编码器名前缀猜。
- PTS 用相机时间戳映射到统一单调时钟，**禁止**用"收到帧的时刻"或"帧序号 × 固定时长"顶替。
- 界面只展示真实状态：未接摄像头就写未接入，未录制就不显示"录制中"。
- 影响编码/写盘的设置"下一片段生效、当前片段不中断"。

## 5. 待用户确认（实现按规格默认值执行，**默认值不等于已确认**）

录音、抓拍、导出、锁定、自动录制默认值、去畸变及 applicationId（当前候选 `com.galaxy.autorecord`）仍待用户确认；现有入口/默认实现不代表已确认。硬件并发、高档码率、HEVC 与受保护配额需实测，默认值不是实测结论。

## 6. 未验证项（不得宣称已完成）

- 指定 L6 的页面、持续预览、切换与释放结果见[最新现场记录](安装测试与修复-20261104.md)，不得用旧探针的单帧结果代验。
- 未跑 Lint；未实测编码器（硬件并发路数、软编可用路数、HEVC 存在性、吞吐）。
- 录制引擎尚未接入：P1 的"开始录制"给出明确提示，不会假装开始。

## 7. 单命令验证（本机，不进入 APK）

最新全链本机验证为 `./scripts/verify_autorecord_offline.sh`；含隔离模拟器为 `./scripts/verify_autorecord_offline.sh --emulator`。下方旧入口仍只跑 record 单测与 Debug 构建。

```bash
./scripts/build_autorecord.sh
```

固定 `--offline`、`GRADLE_USER_HOME`/`ANDROID_PREFS_ROOT` 落在 `.work/`、Robolectric 离线 init；执行 `:record:testDebugUnitTest` + `:record:assembleDebug`。

## 8. 整理说明

- 2026-11-03（第三轮）：界面与功能全量落地，单命令验证 `:record:testDebugUnitTest` 54 项通过 + `assembleDebug` 成功；摄像头/车辆/引擎/解码/导出仍为"未接入"实现，替换点收敛在接口边界。
- 2026-11-03：7 份文档由 `docs/` 根目录归集到 `docs/AutoRecord/`，去掉文件名里重复的 `AutoRecord` 前缀；`AGENTS.md`、`README.md`、`docs/项目规划.md`、`docs/决策记录.md` 与文档间互链已同步更新。仅改路径与链接，未改动任何文档结论。
