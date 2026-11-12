# 旧整屏覆盖投屏：归档说明（2026-11-12）

本目录保存 GALAXY-ANDROID-R0-20261112 归档的**旧整屏覆盖/遮罩投屏**专用源码原字节。
用户决定「只归档整屏投屏，原厂元素增强保留」：旧链退出新版功能，代码保留但不提供
实际入口、不提供注入投屏、不再做结构重构；本批不做任何车辆/HMI/模拟器操作。

## 归档范围

- 起点提交 `572ba0eb07113c8c1859efa9c34cb25a1676eff7`，产品基线 `083e1ef…`。
- 归档根 `archive/legacy-fullscreen-projection/20261112/`，原相对路径完整保留
  （例如 `galaxy/service/src/main/java/com/ecarx/autoservice/projection/…`）。
- `manifest.json` 逐文件列 `sourceBaseline`、原路径、归档路径、SHA256、字节数；
  归档文件是起点提交 blob 的原字节，未格式化、未改注释。
- `snapshots/` 保存三个**混合职责**文件的同源原字节快照：
  `AutoService.kt`、`PlatformCoordinator.kt`、`ClusterRenderService.kt`。
  运行版本仍在原位置并按任务书 §3 收口，快照只作审计证据，不参与编译，不产生重复类。

按任务书 §5 归档的内容：`projection/` 13 个 Kotlin 生产文件；`platform/` 的
`RootHabSession` / `RootHabProcess` / `QnxReceiverDeployer` / `QnxReceiverRemote` /
`QnxReceiverLifecycle`；service 6 个 projection 测试、2 个 receiver 测试、
`ProjectionSmokeInstrumentation`；cluster `ThemeActivity`、`ClusterSceneOutput`、
12 个主题编辑器/组件库 UI 文件、5 个主题布局、4 个主题测试。

## 明确保留（禁止按文件名整包删除）

1. `galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxClusterHook.kt`
   仍从 `assets/qnx/receiver-candidate` 取二进制作为 `health`，并执行
   `health hook-health`；使用 `PosixChecksum`。因此 `prepareQnxReceiver` Gradle 任务、
   `scripts/prepare_auto_qnx_asset.py`、共享 C/QNX 源码与 `tools/hab` 全部原地保留，
   本批不改其实现、不拆分 health 编译。停用的是服务 op4/op6 与自动部署/serve 链。
2. `ClusterHookController` 的 op11/op12、`KanziMediaPublisher`、`QnxCredentials`、
   `QnxTelnet` 与 OEM 资产继续保留；不改厂商 ABI/HMI 协议。
3. `galaxy/cluster/.../ui/InstrumentMusicPreview.kt` 仍消费 `theme.SceneData`，
   因此 `theme/` 数据模型、渲染器与内置主题包**整包保留**，本批不搬 theme、不调整
   预览 Canvas 算法。`BackgroundImagePicker` 继续供 OEM 背景使用。
4. `AutoContract` 旧 role/action 常量与 AIDL、`RootWire` 协议版本保留二进制/源码兼容，
   本批只在服务边界拒绝旧请求；`RootServiceClient` 通用 request 不升契约、不缩权限。
5. CarPlay 主屏会话、MAP 媒体/导航数据生产、车辆订阅、基础 ADB/Root 身份与自动运行、
   AutoRecord 全部保留。

`PosixChecksum` 算法与 receiver `stop` 的自有 PID/path 核验、有限 TERM 逻辑分别从
归档的 `QnxReceiverDeployer.kt` 抽出为运行文件 `platform/PosixChecksum.kt` 与
`platform/QnxReceiverCleanup.kt`（op5 显式清理仍可用，本轮不连车清理）。
`RootHabSession`/`RootHabProcess`/`GalaxyHabComposition` 的运行能力不再提供。

## 不再用于新版的历史脚本

下列脚本服务旧整屏投屏/主题编辑/bridge 部署，属历史证据，本批不改造、不纳入新的
发布流程，离线门槛只使用 `scripts/verify_projection_retirement.sh`：
`cluster_preflight.py`、`verify_cluster_preflight.sh`、`verify_projection.sh`、
`benchmark_projection.py`、`verify_cluster_editor.cjs`、`verify_theme_proposal.py`、
`package_hab_transfer_fix.cjs`、`build_hab_bridge.py`（bridge 构建）、
`cluster_device_check.py` / `test_remote_record_fixes_emulator.sh` 中引用
`ProjectionSmokeInstrumentation` 的旧 instrumentation 入口。
`build_hab_bridge.py`、`src/main/cpp/hab/`、`tools/hab/` 作为共享 health 资产来源
保留原位置，但新版不再执行 `buildHab`、不再打包 `libgalaxy_hab_bridge.so`。

## 未验事实

本批只离线编码/编译/测试。归档不能证明车机上一版接收端已退出、原厂窗口已恢复、
或新 Hook 实屏通过；QNX 资产只是保留，未重新编译、未上车。
