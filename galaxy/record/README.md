# `:record` — AutoRecord 四向行车记录仪

独立应用模块（Gradle 模块 `:record`）。获得 CAMERA 权限后在本进程直连 Camera2；车辆数据仍只经 AutoService 按需订阅。本模块不做 ADB/Root、不引用 CarPlay 的 `:common` 资源。

2026-11-04现场更新：车辆预览使用`SurfaceCameraSource`的PRIVATE连续SurfaceView，指定L6四路实际呈现约25fps；模拟器保留JPEG适配器。车辆值随服务回调刷新，默认250ms。见[现场记录](../../docs/AutoRecord/现场复测与协同-20261104.md)。WorkBuddy/Hy3的存储基础候选先放独立任务目录，按[协作规范](../../docs/AutoRecord/Codex与WorkBuddy协作规范-20261104.md)由Codex审查后接入；录像引擎仍未完成。

- 文档入口：[docs/AutoRecord/README.md](../../docs/AutoRecord/README.md)（索引 + 状态台账）
- 逐页规格：[页面功能与设置规格](../../docs/AutoRecord/页面功能与设置规格-20261103.md)
- 视觉基线：[FlymeAuto 样式基线](../../docs/AutoRecord/FlymeAuto样式基线-20261103.md)

## 构建与验证

```bash
./scripts/build_autorecord.sh          # :record:testDebugUnitTest + :record:assembleDebug
```

本机验证配置（离线、Gradle/签名目录限制在 `.work/`、in-process Kotlin）；不执行 Lint、不安装、不连接车辆。

## 结构

```
src/main/java/com/galaxy/autorecord/
  core/  设置持久化、存储卷探测、编码能力探测、段目录/清单/原子落盘、状态与错误码
  ui/    应用壳（164dp 导航栏 + 64dp 标题栏）与页面（原生 View 自绘）
src/test/  Robolectric 单元测试（core）
```

直连采集在 `core/DirectCameraSource.kt`；段扫描与索引标记已在 core。权限准备与车辆订阅已接入并通过离线回归；尚未接入：录制引擎、回放解码、导出执行。

## 当前状态

| 项 | 状态 |
| --- | --- |
| 应用壳、FlymeAuto 昼夜主题 | 已落地 |
| P1 实时画面 / P2 录像库 / P5 设置 / P6 存储 / P7 编码 | 已落地；P1 Camera2 直连候选，P2 扫描真实段目录 |
| P8–P12 / P13 | 设置、诊断、关于与首次引导已落地 |
| P3/P4/P14 | 回放 UI、片段面板、导出选项已落地；解码与导出执行未接入 |
| 车辆订阅与权限准备 | 已实现；缺 CAMERA 经服务申请回调与本地复核后才开启订阅/相机；已有权限可直接预览 |
| 录制引擎 | 未接入 |

## 最新离线验证

```bash
./scripts/verify_autorecord_offline.sh
./scripts/verify_autorecord_offline.sh --emulator
```

全链记录见[六项离线整改](../../docs/AutoRecord/离线修复与回归-20261104.md)。不连接车机、不部署公网服务。模拟器仅为独立测试，不代表四路录制或真实画面验收。

## 现场入口（本轮禁止执行，后续须按用户新的现场安排）

```bash
./scripts/deploy_autorecord_field.sh all
python3 scripts/autorecord_field.py sample
python3 scripts/autorecord_field.py screenshot --out .work/autorecord-screen.png
```

默认使用 `.local/workbuddy-remote-handoff/remote-session.json`，可通过 `GALAXY_REMOTE_SESSION`（Shell 入口）或 `--session`（Python 入口）指定。只走 AutoService 的 ROOT 会话，不建立电脑 ADB 连接。常规使用中继 artifact/install 通路；显式 shell 诊断支持压缩分块、续传前缀与完整包摘要核对、`pm install -r` 保留数据、安装后摘要回读均由仓库脚本实现；不清系统日志，不依赖 `.work` 临时执行代码。

## 约束（写在代码里的产品规则）

- 编码器硬/软/未知三态只由 `isHardwareAccelerated()`/`isSoftwareOnly()` 判定，不按名称前缀猜。
- 探测不通过的组合置灰并说明原因，**禁止静默回退**；找不到编码器/卷不可写时拒绝并保持原值。
- 配额只有一处实现（`RecordSettings.effectiveQuotaBytes()`：32 GB 与介质 50% 取小）。
- `SegmentManifest.isComplete` 要求四路均 complete，不用其它方向顶替缺失方向。
- 界面不伪造状态：未接摄像头写未接入，未录制不显示"录制中"。
