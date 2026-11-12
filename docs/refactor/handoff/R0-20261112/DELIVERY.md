# GALAXY-ANDROID-R0-20261112 交付记录

整屏投屏归档与 AutoService 停注入。**本批只做离线编码/编译/测试**，未连接或安装车辆、
未操作 HMI、未启动模拟器、未读取认证私密材料、未远程部署、未打包或签名 APK。
不恢复已取消的 C2b，不自动集成，不续派下一批。

## 1. 基线与范围

| 项 | 值 |
| --- | --- |
| 唯一可写副本 | `/Users/anjon/Documents/GalaxyPlay/.work/tasks/galaxy-android-r0-20261112/worktree` |
| 分支 | `feat/galaxy-android-r0-20261112` |
| 起点 HEAD | `572ba0eb07113c8c1859efa9c34cb25a1676eff7` |
| 产品基线 | `083e1ef4be5182def415e6dc56d6baacfa33c3a1` |
| 起点源码摘要 | 父目录 `BASELINE.json`，`sourceSha256` 1072 项，起点工作树干净 |
| 最终提交 | 见本文件末尾「提交绑定」与 `VALIDATION.json` |

产品根与旧任务目录全程只读。模型沿用当前可用配置如实记录，未切换、未纠缠。

## 2. 归档（原字节、源集外）

归档根 `archive/legacy-fullscreen-projection/20261112/`，原相对路径完整保留，
不进 Gradle 源集、未新增指向 archive 的 sourceSets。`manifest.json` 逐文件列
`sourceBaseline`、原路径、归档路径、SHA256、字节数；归档文件是起点提交 blob 的原字节。

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| 专用运行 Kotlin（moved） | 32 | service `projection/` 13 + `platform/` 5；cluster `ThemeActivity` 1 / `ClusterSceneOutput` 1 / `ui/` 12（布局 5 为 XML，单列于下） |
| 布局 XML（moved） | 5 | `activity_theme` / `header_theme_library` / `item_theme_library_row` / `item_theme_library_card` / `page_cluster_themes` |
| 单元测试（moved） | 12 | service projection 6 + platform receiver 2；cluster `ThemeShellBehavior` / `ThemeLibraryPageTest` / `ComponentUiTest` / `ComponentCatalogTest` |
| instrumentation（moved） | 1 | `ProjectionSmokeInstrumentation.kt` |
| moved 合计 | 50 | 逐文件已从活跃源集移除 |
| snapshots | 3 | `AutoService.kt` / `PlatformCoordinator.kt` / `ClusterRenderService.kt` 同源原字节快照，运行版本仍在原位并按 §3 收口 |

归档 README 说明旧产品范围、共享保留例外、以及历史 preflight/bridge 脚本不再用于新版。
被归档测试只作为历史证据保留，不为凑旧例数保留为活跃能力；`VALIDATION.json`
的 `junit.archivedSuitesKeptAsEvidenceOnly` 明确列出，产物核对确认它们不在本轮活跃报告中。

## 3. 运行入口逐文件收口（旧方法 → 新方法）

### service

| 文件 | 收口内容 |
| --- | --- |
| `AutoService.kt` | 删 `GalaxyHabComposition` / composition、`surfaces` / `scenes` / `previewTarget`、`settleMapSize` viewport 协商、`ensureComposition` / `updateProjection`、旧 scene/preview/surface 所有权与恢复。`attach` 对 THEME/PREVIEW 在服务边界拒绝（不登记输出租约、不改持久意图、不建 composition/预览，只用既有 `onProjectionState` 回报 `projection_archived`）；`setActive` 删 THEME 的 `themeEnabled` 写入，保留 ELEMENTS 独立意图；`configureScene` / `setPreviewSurface` / `setProjectionPolicy` 只回报归档态、释放到达的 Surface、不写策略。`onStartCommand` 的 cluster action / 旧 `themeEnabled` / START_STICKY 恢复不再拉起旧输出；theme-stop 只撤销本 App 现有增强，不触发 MAP 接管；OEM `clusterElementsEnabled` 恢复按 Root/paused/自身意图保留。`detach` / `onDestroy` 保留共享客户端、车辆、媒体取消与释放。`publish` 恒发布 `qnxReady=false` / `projectionRequested=false`、`MapStreamSize.DEFAULT`（1920×536）、默认 `ProjectionPolicy`、`clusterLyricsRequested` 只取实际 OEM music；MAP 媒体/导航、KanziMediaPublisher、ClusterHookController、图片/车辆按原合同运行 |
| `platform/PlatformCoordinator.kt` | 删 projectionThread/worker、`prepareProjection`、op4 准备/重试/补拉起、foundation 完成重入、`prepare(active)`；保留基础 ADB/Root 恢复、generation、关闭屏障 `previousClose`、`rootLost` / `retry` / `close` |
| `platform/RootServiceMain.kt` | op4 不再 deploy、op6 不再运行 `RootHabSession` / 本地 bridge；真实分发循环接入 `RootProjectionArchive.rejectIfArchived`；op5 改 `QnxReceiverCleanup.stop`；`release()` 去掉 `RootHabSession.close()`；op0/1/2/3/5/7/8/9/10/11/12 职责不变，特别保留 op11 控制与 op12 回执 |
| `platform/RootProjectionArchive.kt`（新） | 唯一小型拒绝器：op4 消费既有长度 JSON 帧并按 `RootWire.write` 回 `error`/`stage=legacy_projection_archived` 后关闭连接；op6 回旧协议 `int -1` + flush 后关闭，不启动进程/线程、不读认证材料；其它 operation 返回「未处理」交回原分发。生产与测试共用同一 `rejectIfArchived` 方法 |
| `platform/QnxReceiverCleanup.kt`（新） | 从归档 Deployer 只迁 `stop` 的既有数字 PID 校验、`kill -0`、`pidin -p <pid> ar` 路径核验、有限 TERM、二次存活确认与 pid 文件清理；`connection` 只归清理职责，不保留 deploy/ensureRunning，op5 显式可用但本轮不连车清理 |
| `platform/PosixChecksum.kt`（新） | 从归档 Deployer 抽出原 POSIX cksum 算法（附长度字节），供 `QnxClusterHook` health 材料校验继续用；`PlatformExecutionTest` 既有系统 `cksum` 对拍用例保留 |
| `settings/ServiceFeatures.kt` | 旧 `projectionEnabled` 键保留兼容记录，`set(PROJECTION,true)` 仍可写历史键，但 `read()` / `enabled()` / `bundle()` 对外恒不可用；vehicle/adb/root、automatic、`disableAll` 行为不变，未用「同时关闭 Root」停旧链 |
| `ui/home/ServiceHomePresenter.kt` / `ServiceHomeState.kt` | 去掉投屏可操作开关、监听、说明与 `projectionReady` / 投屏描述/指标计算；概览首项由 XML 固定为只读「已归档」，不显示准备/可用；保留车辆/ADB/Root 三开关、全部关闭、Wi-Fi、账号、诊断 |
| `res/layout/include_service_features.xml` / `include_service_overview.xml` / `values/strings.xml` | 删 `service_projection` 开关行；概览标签与只读值改用 `service_metric_projection_archived` / `service_projection_archived` |
| `build.gradle.kts` | 不再执行 `buildHab`、不把 `libgalaxy_hab_bridge.so` 加入 main jniLibs、移除已归档 `testInstrumentationRunner`；保留同名无 Exec/无输入输出/无依赖的 `buildHab` 占位以兼容离线脚本 `-x :service:buildHab`；`prepareQnxReceiver` / `prepareClusterHook` 与共享资产链保留；签名、依赖版本、KADB 插桩与其它插件逻辑未改 |

### cluster

| 文件 | 收口内容 |
| --- | --- |
| `ClusterRenderService.kt` | 改为只履行 OEM_ELEMENTS：删 `ClusterSceneOutput`、`ThemeStore` / `BuiltinThemes`、apply/reload/sync-energy/panel 旧整屏分支；旧/未知模式、旧 `enabled=true` OVERLAY、空 Intent 恢复旧模式一律不建 client/输出并 `START_NOT_STICKY`；真实已启用 OEM 才配置/连接 ELEMENTS client，保留 `VisualMusicSession` 与 client 释放；通知与渠道文案改为真实 OEM 含义并资源化 |
| `runtime/ClusterDisplayStore.kt` | 保留旧 prefs/草稿/主题文件与 `read()` 的历史 OVERLAY 缺省；新增 `oemEnabled`（`enabled && mode == OEM_ELEMENTS`），不作静默转换；`restoreEnabledOutputIfNeeded` / `reloadDisplay` 只对真实已启用 OEM 有效 |
| `runtime/ClusterDisplayStatus.kt` | 旧模式（含历史 `enabled=true`）显示只读归档态，不再消费 `qnxReady` / MAP 在线展示「主题通道已连接/等待」；OEM 状态判定与恢复回执顺序不变 |
| `ui/ClusterDisplayActions.kt` | 删 `openThemes`；保留图片导入、原厂确认、方案 revision 回执、外观存储与提交 |
| `ui/navigation/ClusterPage.kt` / `ui/ClusterAppPresenter.kt` / `ui/pages/ClusterFlowPages.kt` / `ClusterHomePage.kt` | 移除 THEMES 枚举与 `ThemeEntryPage`、`openThemes` 回调/绑定、`cluster_nav_themes` 导航；旧保存 `page=themes` 按既有未知值规则回 HOME；首页/原厂/音乐/背景/设置/恢复/结果页继续可达 |
| `res/layout/activity_cluster.xml` / `page_cluster_home.xml` / `AndroidManifest.xml` / `values/strings.xml` | 移除 `cluster_nav_themes` 与 `home_themes_card`，删除 `.ThemeActivity` 组件；新增只读归档提示与 OEM 服务文案 strings；共用主题/样式/颜色未删 |

## 4. 明确保留（禁止误归档）

- `QnxClusterHook` 仍从 `assets/qnx/receiver-candidate` 取二进制作为 `health`、执行
  `health hook-health`，并使用 `PosixChecksum`；`prepareQnxReceiver` 任务、
  `scripts/prepare_auto_qnx_asset.py`、共享 C/QNX 源码与 `tools/hab` 全部原地保留原字节。
- `ClusterHookController` op11/op12、`KanziMediaPublisher`、`QnxCredentials` / `QnxTelnet` 与 OEM 资产。
- `InstrumentMusicPreview` 仍 `import com.galaxy.autocluster.theme.SceneData`；`theme/` 数据模型与
  渲染器、`DemoContent`、`ScenePreparation`、`BackgroundImagePicker` 整包保留，禁止整包搬 theme。
- `AutoContract` 旧 role/action 常量与 AIDL、`RootWire` v2 保持二进制/源码兼容；`RootServiceClient`
  通用 request 不升契约、不缩权限。
- CarPlay 主屏会话、MAP 媒体/导航数据、车辆订阅、基础 ADB/Root 身份、AutoRecord。
- Root helper 既有握手（VERSION / ownerPid / ownerStarted / apk / 实例）未改。

## 5. 用例（旧合同 → 新合同，仍覆盖的行为）

| 用例 | 变更 |
| --- | --- |
| `ServiceHomeBehaviorTest.featureRowsDeclaredInXmlResolveTitleAndIcon` | 旧：断言投屏行标题/图标 + 车辆行标题/图标（四项）。新：断言 `service_projection` id 不再声明、车辆行标题/图标仍成立，并补 ADB/Root 两行断言。**仍覆盖** XML 声明行的标题/图标解析与三项现行开关 |
| `ServiceHomeBehaviorTest.settingRowsUsePlatformDefaultStyleForLayoutAttributes` / `switchesReflectSettingsAndSnapshotWithoutDispatchingCommands` | 旧：用投屏开关回填与 `service_metric_waiting_projection` 指标。新：改用车辆/ADB/Root 真实开关回填，概览首项断言只读「已归档」且不随快照变成准备/可用。**仍覆盖** 样式来源、回填不发命令、快照渲染不发命令 |
| `ServiceHomeBehaviorTest.userActionsPersistBeforeDispatchAndDisableAllCoversEverything` | 旧：点投屏开关验证先落盘再通知。新：点车辆开关验证 `vehicleEnabled` + `ACTION_CHANGED`，保留 `disableAll` 对 `projectionEnabled` 兼容键写 false。**仍覆盖** 用户动作顺序、全部关闭 |
| `ClusterAppNavigationTest.appSeparatesHomeMusicAndBackgroundAndKeepsServiceSettingsOutside` | 旧：点「主题」→「我的主题」→ 断言启动 `ThemeActivity`。新：断言主导航无「主题」按钮，其余首页/原厂/音乐/背景/设置/恢复/结果页与「无服务设置串入」断言全部保留。**仍覆盖** 页面分离与 inflate |
| `LegacyProjectionRetirementTest`（新，R0-01） | 真实 Store/Actions/RenderService/Activity XML/Presenter：旧 OVERLAY + `enabled=true` 不启动任何输出；空 Intent 与旧 apply/reload/sync-energy/panel 命令返回 `START_NOT_STICKY`；`page=themes` 回 HOME 且无主题按钮；原 prefs/草稿/主题文件保持；OEM 显式提交仍启动 ELEMENTS |
| `RootProjectionArchiveTest`（新，R0-02） | 以真实 `DataInput`/`DataOutputStream` + `RootWire` 帧构造 op4 部署帧与 op6 请求：op4 得 `legacy_projection_archived` 且消费帧、关闭连接；op6 得 `int -1` 且无子进程；op0/1/2/3/5/7/8/9/10/11/12 返回未处理交回原分发；`ServiceFeatures` 旧键 set(true) 不重开、vehicle/adb/root 照常 |
| R0-03 保留回归 | `ClusterPageBehaviorTest`、`DiagnosticPageBehaviorTest`、`ClusterElementPipelineTest`、`ClientRegistryTest`、`FoundationRecoveryTest`、`PlatformExecutionTest`（含 `PosixChecksum` 与系统 `cksum` 对拍）、`RemoteConnectionStatusTest` 全部保留并通过；**`ClusterHookLifecycleTest` 4 例中 3 例失败**，为本批未闭合的残留问题，详见 §9。此处不写成「全部通过」 |

真实服务 binder THEME/PREVIEW 的拒绝由 `AutoService` 调用闭包、真实编译产物与
`RootProjectionArchive` 单元测试共同说明；未把单个拒绝函数测试冒充所有 binder 行为已动态执行。
未为凑旧例数重新开放退役功能，未以源码字符串存在或自写布尔模型当关闭已验证。

## 6. 验证门槛（单命令）

```sh
cd /Users/anjon/Documents/GalaxyPlay/.work/tasks/galaxy-android-r0-20261112/worktree
GALAXY_REFACTOR_CACHE=/Users/anjon/Documents/GalaxyPlay/.work bash scripts/verify_projection_retirement.sh
```

脚本依次执行：

1. 只隔离本任务自身 service/cluster 模块的 Kotlin class、merged manifest、test-results
   旧产物（重命名到 build 下带 `.stale-*` 后缀，均被 `.gitignore` 忽略），保证本轮产物不混入
   过期 class；不删共享缓存、不动其他工作树。
2. `tools/review/galaxy-r0-20261112/verify_format.py`：固定 ktlint 1.4.1
   （jar SHA-256 `abff61ea…deac70`）+ `format/galaxy/.editorconfig`，22 个新增/修改 Kotlin/KTS
   逐文件 argv，`--format` 两遍幂等后只读 check；XML 用 `/usr/bin/xmllint` libxml 20913、
   四空格，7 个 XML 做 parse/语义等价/二次幂等；Python `py_compile` + Shell `bash -n`。
3. 复用 `scripts/verify_cluster_pages.sh`（不带 `--baseline`）：全下游编译与资源处理、
   U1 门槛、当前全部 Cluster 测试。
4. 同一离线环境执行本批 service R0 用例与受影响既有回归，精确 `--tests` suite 清单，
   零匹配由 Gradle 直接失败。
5. `tools/review/galaxy-r0-20261112/verify_artifacts.py`：逐项三方核对
   起点 git blob / 归档文件 / `manifest.json` SHA256，确认 moved 原路径不在活跃源集、
   snapshot 运行版本已按 §3 改动；检查本轮 AGP 真实 merged manifest 无 `ThemeActivity`、
   Kotlin class 输出无 31 个旧专用类且 10 个运行/共享类齐全；检查必测 JUnit suite
   非空、零失败/错误/跳过、归档套件不出现在活跃报告；核对 Root 分发、共享 health 链、
   `InstrumentMusicPreview.SceneData`、service Gradle 退役与共享资产准备链；最后绑定
   工作树 HEAD、源码/资源/KTS/归档 SHA256。
6. 保留实际 JUnit XML、`archive-manifest.json`、`format-result.json` 与全部日志到父目录 `logs/`。

缓存固定 `.work/android-sdk`、`.work/gradle-home`，使用既有镜像与严格离线 Robolectric
init/AAPT2；`GALAXY_VERIFY_CACHE` 与 `GALAXY_REFACTOR_CACHE` 同时指向 `.work`。
排除的仅未改 Native/QNX 资产准备，不排除 Kotlin、resources 或 test。

**本轮实际执行结果**：第 1/2 步格式与全下游编译/Cluster 测试通过（`verify_cluster_pages.log`
`BUILD SUCCESSFUL`，102 actionable tasks）；第 3 步 `:service:testDebugUnitTest` 因
`ClusterHookLifecycleTest` 3 项失败而 `BUILD FAILED`（`48 tests completed, 3 failed`）。
`set -e` 使第 4/5/6 步（产物核对、证据复制、通过回显）**未执行**，脚本末尾的
「R0 门槛通过」回显并未出现。`logs/verify-full.log` 是原始输出，详见 §9。

## 7. 未验事实与边界

- 离线验证不能证明车机上一版接收端已退出、原厂窗口已恢复、或新 Hook 实屏通过；
  共享 health 资产只是保留，未重新编译、未上车。
- 本批不打 APK/签名、不运行 Native/QNX/车辆/模拟器；`VALIDATION.json` 中
  `apkBuilt=false`、`vehicleValidated=false`、`nativeAssetPreparationExcluded=true`。
- 归档只清除有效运行入口，不是仅隐藏按钮、改名或切一个可被旧恢复任务绕过的 UI 开关；
  原厂增强不依赖旧 `features.projection`，仍遵守 Root 关闭、全局停止与自身持久意图。
- 未改 Git/签名、依赖版本、上游核心、后台上报或服务器；未 push/merge/reset/clean/stash；
  未横向重构 common/mobile/client/contracts/ui/lyrics/record。

## 8. 提交绑定

| 项 | 值 |
| --- | --- |
| 分支 | `feat/galaxy-android-r0-20261112` |
| 起点 HEAD | `572ba0eb07113c8c1859efa9c34cb25a1676eff7` |
| 最终 commit | `b70482099b2dc2dfd418389ce3d8e5752a28cceb`（父提交 `572ba0eb07113c8c1859efa9c34cb25a1676eff7`；89 files changed, 4686 insertions(+), 1024 deletions(-)，50 个归档被识别为 R100 原字节重命名；提交后工作树干净） |
| 暂存方式 | 显式 `logs/commit-whitelist.txt` 139 项路径（50 删除 / 24 修改 / 65 新增），不用 `git add -A` |
| 状态 | **`BLOCKED_VALIDATION`**（不是 `READY_FOR_REVIEW`），见 `STATUS.md` |

按用户 2026-11-12 18:24 指令：不再继续编码，如实记录残留问题后提交并冻结，交由新的 AI 完善。
未 push / merge / reset / clean / stash；未自动集成、未续派、未恢复 C2b；
离线编译与部分测试通过不等于车端已恢复。

## 9. 残留问题与交接（交给下一 AI）

### 9.1 判定依据

**BLOCKED_VALIDATION**。单命令门槛第 3 步 `:service:testDebugUnitTest` 有 3 个必测失败，
`set -e` 终止，第 4/5/6 步未执行。按 HANDOFF「编译/必测失败或未执行必须 BLOCKED_VALIDATION，
不能 READY」如实判定；代码虽按用户指令提交，但状态不改写为通过。

### 9.2 已通过、可直接复用的部分

| 门槛 | 结果 |
| --- | --- |
| 全下游编译 / 资源 | `logs/verify_cluster_pages.log`：`BUILD SUCCESSFUL`，102 actionable tasks |
| 固定格式 | ktlint 1.4.1（jar `abff61ea…deac70`）22 个 Kotlin/KTS `--format` 两遍幂等 + 只读 check 通过；`/usr/bin/xmllint` libxml 20913 对 7 个 XML 做 parse / 语义等价 / 二次幂等通过；4 个脚本 `py_compile` / `bash -n` 通过（`logs/format-result.json`） |
| Cluster JUnit | 11 suites / 52 cases，0 失败 0 错误 0 跳过（含新增 `LegacyProjectionRetirementTest`） |
| service JUnit | 9 suites / 48 cases，45 通过 / 3 失败；新增 `RootProjectionArchiveTest` 4/4、`ServiceHomeBehaviorTest` 10/10、`DiagnosticPageBehaviorTest` 12/12、`PlatformExecutionTest` 4/4（含 `PosixChecksum` 与系统 `cksum` 对拍）全通过 |
| 归档 | 50 moved + 3 snapshots，`manifest.json` 53 条，逐文件 SHA256 与起点 git blob 一致；源集外，未进 Gradle |

### 9.3 失败的 3 个用例（原始证据）

| 用例 | 位置 | 现象 |
| --- | --- | --- |
| `matchingLiveProcessReusesWithoutRestartOrStage` | `ClusterHookLifecycleTest.kt:18` | `IllegalStateException: cluster_restore_unconfirmed` |
| `firstStartStagesVerifiesAndOnlyThenActivates` | `ClusterHookLifecycleTest.kt:28` | 同上 |
| `fixedFirmwareMismatchCannotActivate` | `ClusterHookLifecycleTest.kt:51` | `AssertionError`（`Assert.assertFalse(launched)`） |

前两例堆栈相同：`QnxClusterRemote.stop (QnxClusterHook.kt:126)` ←
`QnxClusterRemote.ensure (QnxClusterHook.kt:139)` ← 测试方法。
该 suite 4 例耗时 60.526s，与 `stop()` 内 `repeat(15) { … Thread.sleep(1000) }` 的
15 秒重试 × 3 例一致。原始输出见 `logs/service-tests.log` 与
`logs/junit/service-TEST-com.ecarx.autoservice.ClusterHookLifecycleTest.xml`。

### 9.4 根因：事实与推断分开

**可验证事实**

1. `ClusterHookLifecycleTest.kt`（SHA-256 `62d6d3f94bb9f93a…`）与
   `QnxClusterHook.kt`（`f811f76f6cee4fa6…`）本批**零改动**：
   `git show HEAD:<path> | shasum -a 256` 与工作树文件逐字节一致，`git diff` 为空。
2. 失败路径只经过 `QnxClusterRemote.ensure / stop / installed / filesMatch`，
   不经过本批任何新增或修改的类；`PosixChecksum` 仅参与 `checks` 命令串构造，
   编译通过且 `PlatformExecutionTest` 的 `cksum` 对拍通过。
3. `docs/refactor/文件台账-20261111.tsv:883` 把该测试标注为「待重构，未验收」，
   且登记的 SHA-256 正是当前的 `62d6d3f9…`；既有 `scripts/verify_android_refactor.sh`
   的 `SERVICE_TESTS` 清单**不含**该 suite，历史门槛从未执行过它。
   本批把它列入必测清单后才暴露。
4. 协议 token 不同步：测试 fake 返回 `AS_CLUSTER_INSTALLED` /
   `G733_RUNTIME_MATERIALS_MATCH`；生产 `QnxClusterRemote` 期望
   `AS_CLUSTER_VERSION_MATCH` / `AS_CLUSTER_FILE_MATCH` / `AS_CLUSTER_FILE_MISMATCH` /
   `G733_RUNTIME_OWNED` / `AS_CLUSTER_LIVE` / `AS_CLUSTER_RESTORED`。

**推断（未在独立基线 worktree 上实跑复验，标注为推断）**

`installed()` 因 `AS_CLUSTER_INSTALLED` ≠ `AS_CLUSTER_VERSION_MATCH` 恒为 false，
`filesMatch()` 因 `G733_RUNTIME_MATERIALS_MATCH` ≠ `AS_CLUSTER_FILE_MATCH` 恒为 false，
`ensure()` 跳过「复用现有进程」分支并落入 `stop()`；fake 对 `pids` 命令返回非数字串，
`processes` 为空，15 次重试后 `error("cluster_restore_unconfirmed")`。
第 3 例中 `inspect` 分支抛错让 `assertThrows` 成立，但 `filesMatch()` 等其它命令命中
fake 的 `else -> { launched = true; "" }`，导致 `assertFalse(launched)` 失败。

### 9.5 复现命令

```sh
cd /Users/anjon/Documents/GalaxyPlay/.work/tasks/galaxy-android-r0-20261112/worktree
GALAXY_REFACTOR_CACHE=/Users/anjon/Documents/GalaxyPlay/.work bash scripts/verify_projection_retirement.sh
```

基线独立复验（可选，只读，不触碰当前 worktree）：

```sh
git worktree add --detach /tmp/r0-baseline-check 572ba0eb07113c8c1859efa9c34cb25a1676eff7
# 在 /tmp/r0-baseline-check 内用同一套离线 Gradle 参数只跑
# :service:testDebugUnitTest --tests com.ecarx.autoservice.ClusterHookLifecycleTest
# 完成后 git worktree remove /tmp/r0-baseline-check
```

### 9.6 建议修复方向（均未在本次实施或验证，交由下一 AI 判断）

1. 以当前 `QnxClusterRemote` 协议为准重写 `ClusterHookLifecycleTest` 的 fake executor，
   让 token 与 `installed / filesMatch / status / stop` 的实际命令匹配，同时保留 4 个用例
   原本要证明的语义：复用不重启、首次先校验后激活、租约取消不得激活、固件不匹配不得激活。
2. 若判定该用例属于旧版 Hook 遗留、不对应现有 OEM 行为，由 Lead 决定按新协议另写用例；
   **不得**直接删除、跳过或从必测清单移除该 suite 来凑通过。
3. `stop()` 含真实 `Thread.sleep(1000)` × 15，测试 fake 应能在首次循环返回 `restored`，
   否则单 suite 会稳定耗时约 60 秒。

**约束**：不得放宽 `verify_artifacts.py` 的「必测零失败/错误/跳过」判定；不得改动生产
`QnxClusterHook` 的 OEM Hook 行为去迎合旧测试（本批要求保留 op11/op12、health/hook-health
与 `receiver-candidate` 资产链）；不得重新开放任何已归档的整屏投屏入口。

### 9.7 其它未完成项（交接待办）

- `verify_artifacts.py ready` **未执行**：真实 merged Manifest（无 `ThemeActivity`）、
  Kotlin class 输出（无 31 个旧专用类 / 10 个运行共享类齐全）、起点 blob ↔ 归档 ↔
  `manifest.json` 三方 SHA256、`InstrumentMusicPreview.SceneData` 等闭包审计，
  尚未由脚本逐项核对。`VALIDATION.json` 是交付阶段手工汇总，非脚本产物，
  其 `artifactsCheckExecuted=false` 明确标注。
- 脚本第 5 步的 JUnit XML / `archive-manifest.json` / `format-result.json` 副本由交付阶段
  手工复制到 `logs/`（20 个 XML 齐全，内容真实），非脚本流程产出。
- 单命令门槛整体 `BUILD FAILED`；`logs/verify-full.log` 为原始输出。
- 需补跑完整 `scripts/verify_projection_retirement.sh` 至全绿后，才能把状态从
  `BLOCKED_VALIDATION` 改为 `READY_FOR_REVIEW`。
- 未做车端 / Native / HMI / 模拟器任何验证；离线通过不等于车端已恢复。
