# galaxy 代码整理与 WorkBuddy 执行任务书

任务 ID：GALAXY-REFACTOR-B1-20261111。日期：2026-11-11。

用户指定 WorkBuddy 使用 **DeepSeek flash** 编码，Codex 负责审计、规范和后续集成。每批发出后 Codex 结束会话，用户叫开始后才审查。不自动合并候选或续派后续批次。

## 1. 用户报告与目前证据

用户报告：关闭自有 Hook 后出现地图，关闭 AutoService 的仪表投屏后地图才消失。用户要求整理 galaxy 全目录，主要覆盖 AutoService、Mobile/CarPlay、AutoCluster、公共 UI；消除职责混乱和重复建设，补全有效类/方法/块注释，类型调用先 import，适度抽取公共能力，优先已有跑通资料，未知技术先申请其他 Agent 探索，拒绝堆无效 Test。

Lead 只做了入口审计，并未证明所有竞态根因：

- `AutoService.updateProjection()` 当前以 ELEMENTS active 抑制 HAB；ELEMENTS 退出后，仍活跃的 MAP 满足 producesOutput 与旧开关时可能重新激活 HAB。这能解释“关 Hook 后地图出现”的代码条件，需候选通过真实服务入口复现，不得当作已经实车修好。
- `docs/Cluster新版实车初查-20261111.md` 晚间取证记载：Hook 已退出，`clusterElementsRequested=false/state=restored`，旧 CarPlay HAB 因独立需求仍运行。
- `AutoService.publish()` 还承担 NativeMediaSource 创建、Hook 控制器更新、文件发布等副作用，需在台账指出与快照职责混合的边界；不能借此一批重写整个服务。

本批目标：建立全目录整理台账，同时交付显示所有权/关闭链的最小可审修复与相关代码整理候选。禁止把猜测写成 USB 卡顿或 HMI 异常根因。

## 2. 开始前核对

1. 确认工作区为 `/Users/anjon/Documents/GalaxyPlay`，记录实际模型完整名称；界面当前可见 `DeepSeek-V4.1-Flash`。如实际非 DeepSeek flash，停止编码并报告，不换模型、不购买额度。
2. 阅读根 `AGENTS.md`、`galaxy/AGENTS.md` 和本文，以本轮最新边界覆盖旧存储/UI任务的模型和写范围。
3. 记录 `git status --short`、实际分支和本批读取/候选修改源文件的 SHA-256。基线是当前脏工作树，不是 HEAD。禁止 reset/clean/stash/checkout、切分支、提交、推送。
4. 创建本批 STATUS，标记 `IMPLEMENTING`；后续交付 `READY_FOR_REVIEW` 冻结，或未知接入 `NEEDS_EXPLORATION_APPROVAL`、工具阻塞 `BLOCKED`。不因缺少测试环境伪报通过。

## 3. 可读资料与源码

先读普通文档（其中私有目录仅是历史证据地址，不授权打开）：

- `docs/Cluster双模式与原厂多行歌词研究-20261111.md`
- `docs/Cluster实时背景与Hook生命周期-20261111.md`
- `docs/Cluster图片与媒体替换闭环-20261111.md`
- `docs/Cluster新版实车初查-20261111.md`，重点晚间故障及离线修正，不忽略文档中的后续纠正。
- `docs/多应用架构规划.md`、`docs/公共UI组件抽取-20261111.md`、`galaxy/ui/README.md`
- `docs/HAB直连复用优化-20261030.md`、`docs/QNX接收端按需唤起-20261030.md`

只读检索 `galaxy/` 自有源码、资源、清单、测试与模块 build.gradle.kts；必要时只读上游 `common/`、`shared/` 的实际接口以追调用；只读根 settings/build 文件及已有 `scripts/verify_cluster*.sh`、`scripts/verify/lib/`。不改上游核心，不读取 `.private/`、`.local/`、`.work/`、密钥、认证配置、原始车辆日志/图像或其他任务暂存。脚本提及私有文件不授权执行。

重点入口：

- service：`AutoService` 的 Binder/register/active/configure/detach、onStartCommand、features 更新、ensureComposition/updateProjection/publish/onDestroy；`ClientRegistry`、`ClusterHookController`、`KanziMediaPublisher`、`settings/ServiceFeatures`、`projection/GalaxyHabComposition` 与会话启停类。
- cluster：`ClusterRenderService`、`runtime/ClusterSceneOutput`、`runtime/ClusterDisplayStore`、`ClusterActivity`、`runtime/VisualMusicSession`。
- client/contracts：`AutoClient`、AIDL、`AutoContract`、`ClusterDisplayConfiguration`、`ProjectionConfiguration`。
- mobile/common：`GalaxyAutoServiceBinding`、`GalaxyClusterMusicBinding`、`GalaxyNavigationOutput` 及实际 MAP 注册/重放/断线路径；不能误以为 mobile 壳才包含全部 CarPlay 产品逻辑。
- UI：`galaxy/ui` 实际组件与 common `L7*` 兼容入口、ServiceUi/ClusterUi。只建立全局去重台账，本批不改审定页面布局。

## 4. 写入范围与交付格式

**唯一可写根：`.workbuddy/tasks/galaxy-refactor-b1-20261111/`。** 不直接改 `galaxy/`、docs、scripts、根规范或其他任务，不改 Gradle sourceSets。

交付：

- `STATUS.md`：任务/模型/阶段、读取来源、实际完成、未完成、未执行验证及原因、接口请求。
- `BASELINE.json`：源文件相对路径、SHA-256、当前分支、采集时间；不包含凭据、绝对私人材料地址或车辆原始载荷。
- `INVENTORY.md`：全 galaxy 模块清单、现职责/依赖/状态源/线程/资源；重复逻辑、缺注释、完整类名直用、巨型职责混合，逐项列路径/符号/行号、影响和建议批次。明确已盘点/未盘点，不只凭文件名判完成。
- `CHANNEL_AUDIT.md`：实际调用链、当前/目标状态转换表、MAP/THEME/ELEMENTS/PREVIEW 所有权、关闭/恢复/重连顺序、可复现的最小触发序列。事实与推断分开。
- `PLAN.md`：候选改动清单、每项依据、复用点、为何无需新抽象、兼容性；后续 B2–B5 精确职责建议和依赖，但不得自行执行下一批。
- `candidate/<仓库相对路径>`：仅本批改动文件完整候选；`changes.patch` 是相对当前工作树原件生成的差异，不从 HEAD 生成。新文件列明新增，删除/移动只列 manifest，不能删除源件。
- `VALIDATION.md`：每项对应缺陷、生产入口、输入/可观察输出、替身边界、命令、真实结果；没执行明确未执行。可选 `tests/` 与 `tools/` 只放必要候选，不加进正式构建。

候选可修改白名单仅为：service 的 `AutoService.kt`、`ClientRegistry.kt`、`ClusterHookController.kt`；cluster 的 `ClusterRenderService.kt`、`runtime/ClusterSceneOutput.kt`、`runtime/ClusterDisplayStore.kt`；client 的 `AutoClient.kt`；common 的 `GalaxyAutoServiceBinding.kt`。路径均取上方现有类实际位置，不另创同名类。必要新增协调类仅允许落在 service 原包下，必须一个具体职责、有真实调用方，不做框架。相关现有单测的候选可配套提交。

若修复确实需要改 AIDL/合同、Native/QNX、其他生产文件、设置语义或未探索入口，先在 STATUS 写明最小依赖请求和依据，不擅自扩大白名单。可以交付已经完成的审计与不依赖项，不用猜测补齐。

## 5. 本批必须解决/保留的行为

1. 区分数据源在线与显示所有者。只生产 CarPlay MAP 数据不等同于用户请求整屏接管。
2. 复现“MAP已在线且旧投屏允许→元素增强生效→关闭/退出增强→旧整屏地图被动启动”，消除由元素撤销触发的隐式地图接管。不要仅把旧开关强制关掉或删除 MAP 功能；保留用户显式发起旧遮罩/地图的既有合法入口。若需区分显式开启与数据源自动重连，要追实际意图来源，不能臆造新的持久产品模式。
3. 退出与切换：旧输出停止/释放后新所有者才提交；没有明确新输出意图时保持原厂。单独关闭背景保留音乐，单独关闭音乐保留背景；全部撤销、客户端死亡/重连不复活被撤销输出。正常服务恢复读取真实工作意图，不把数据连接状态当作工作意图。
4. `features.projection=false` 只控制旧路线，不成为独立 Hook 启动前提；Root/automatic/全局暂停边界保持。不能顺带重做基础授权、自动恢复或性能策略。
5. 服务发布请求、租约、实际消费者/窗口、真实挂接回执分别可追踪；不以 rootReady/文件存在/requested 代 active。模式决策集中于一个合适职责，避免多个层各写互相不一致的 if 条件。
6. 本批所改职责补齐类/方法/块注释与 import；保持普通排版，不压缩多动作同一行。纯整理与行为修复在差异说明分开。
7. 保留原始 NowPlaying→原车媒体/DIM 的透传，网络歌词可视需求、111/110分流、已有 HAB 握手/资源释放均不能被顺手改变。

## 6. 验证只对真实问题负责

- 使用实际生产入口/类复现上面的事件序列，对实际的 start/stop、Surface 所有者、client 需求、请求/回执观察断言；系统边界可用可记录调用的替身，不能重写另一套路由算法再证明自身正确。
- 少量场景覆盖：报告序列及显式重新启动、元素单项撤销/全停、Binder死亡/重连与迟到回调、旧开关关闭时独立增强仍可请求。优先扩现有参数化用例，不逐分支堆 Test，不设随意通过阈值。
- 本批不涉及生产 shell 行为，禁止顺带新增大量 shell/字符串检查。若提出必须修改生产脚本，先交接口请求；后续验证应运行脚本本身并比对实际产物/命令/退出码。
- 不运行全工程 Gradle/R8/Lint，不启动模拟器、摄像头、服务器或车辆远程工具，不安装/构建 APK，不操作原厂 HMI。可以在暂存根运行无网络、无设备、无生产写入的必要局部验证；Android 候选需要集成编译时由 Lead 后续集中执行，WorkBuddy写未执行。
- 不运行会改源树或自动套补丁的命令。缺环境不自行装依赖、不重签、不伪造结果。

### 6.1 用户补充：所有代码必须规范格式化

用户在首批派发后明确追加：所有代码都需要注重代码格式化，可以用工具自动执行。本条属于当前 B1 的补充要求，后续批次也适用。

- 遵守 `galaxy/AGENTS.md` 的“代码格式化”：缩进、空格、换行、括号、空行、import、尾随空白和文件末换行统一，不把多个独立动作挤在同一行。
- Lead 对受版本管理/普通配置目录的检索尚未找到现有 EditorConfig、ktlint/Spotless、clang-format 或 Prettier 配置；此为检索结果，不保证本机没有可用工具。先核对已有工具和配置，优先复用，不自行下载或安装依赖。
- 本批只格式化暂存候选副本。若缺工具，提交固定工具版本/配置及可复现命令建议，标明尚未执行；不得声称手排或 diff --check 等于自动格式化通过。所需配置只放本批暂存目录，根配置由后续审计决定。
- `VALIDATION.md` 补格式化工具版本、配置、命令、覆盖文件和实际结果；再次检查/格式化应无差异。格式变更与业务修复分别交代，不增加无意义 Test，不改字符串/脚本有效空白。
- 不直接格式化产品树，不扩大文件白名单、不集成候选，也不启动下一批。

## 7. 后续批次路线（本次不执行）

- B2 AutoService：按 B1 证据收口业务协调/快照发布、线程/资源所有权，既有权限、车辆订阅和基础恢复能力保持。
- B3 Mobile/common/client/contracts：CarPlay 适配入口、地图/媒体生产和生命周期，消除重复桥接与类型直用，保留上游升级边界。
- B4 Cluster：配置/草稿/工作意图/回执/渲染职责，主题与编辑器不删能力，注释与共享方法整理。
- B5 公共 UI 及全目录余项：从实际复用点抽取，L7兼容入口委托统一实现，页面样式/交互保持，确认所有模块的未整理项。

每批由 Codex 根据上批审计另发详尽任务，不能靠本文提前授权整树改写。

## 8. 停止条件与审查门槛

交付后状态 `READY_FOR_REVIEW` 并冻结。报告模型、所有文件、原件摘要、关键行为差异、实际验证和未验项；不要将未集成候选称为已修复，更不能称实车通过。遇到未知接入提交探索申请，不自行逆向或试车。等待用户叫 Codex 开始审计；禁止自集成或续写 B2。

## 9. 派发回执（Codex维护）

2026-11-11 20:13（Asia/Shanghai）已通过 WorkBuddy 原生窗口发送至 GalaxyPlay 独立任务，角色为“资深安卓工程师 · 方案架构与疑难攻坚”。发送前后界面均显示 `DeepSeek-V4.1-Flash`；任务消息已出现在会话记录，界面显示“已处理 4s 思考中”和停止按钮，证明任务已被接收并开始处理，不代表候选已完成或通过审计。

Codex 在确认派发后结束本轮；未修改产品源码、未连接车辆、未集成候选。等用户叫开始后再读取交付及审计。

格式化补充已写入本文 §6.1、根规范及 `galaxy/AGENTS.md`。尝试向当前 WorkBuddy 会话追加提醒时，电脑工具先报告界面被用户改变，后续输入和重新获取窗口均超时；未观察到补充消息成功发送。不能将文档更新写成消息已送达，后续审计须核对该项是否落实。
