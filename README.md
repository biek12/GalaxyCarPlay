# GalaxyPlay

2026-11-04：[AutoRecord现场复测](docs/AutoRecord/现场复测与协同-20261104.md)：用户恢复连接并确认驻车上电，中继429修复已部署、原签名/数据迁移完成，四路PRIVATE→SurfaceView呈现约25fps，数据改为250ms与即时刷新，88项回归通过。按[协作规范](docs/AutoRecord/Codex与WorkBuddy协作规范-20261104.md)，WorkBuddy在GalaxyPlay项目用Hy3实现独立暂存的存储候选，Codex负责调试/审查/集成；完整录像仍未接入。

2026-11-04：[AutoRecord六项离线整改](docs/AutoRecord/离线修复与回归-20261104.md)：按用户后续要求停止车辆连接，修复中继429/包白名单、缺路角标、统一左侧菜单与280dpi、车辆订阅及串行授权；增加车型/模拟器适配器，修正存储原子写入、配额、编码预检和15fps预设持久化。离线回归及模拟器独立编码/封装/解码通过，最终包和服务端改动尚未部署实车/公网。

2026-11-04：[AutoRecord安装测试与修复](docs/AutoRecord/安装测试与修复-20261104.md)：接手 work buddy 初版并安装，四路真实预览已取得；发现并修复预检跨线程更新 UI 崩溃、缺路角标不刷新及方向标定无法修改等问题。74 项测试及 Debug/Field 构建通过；远程通道随后停止领取作业，修复版安装及真机复测待恢复，不能视为整体验收通过。

2026-11-03：[AutoRecord界面与功能全量落地](docs/AutoRecord/开发接入设计-20261103.md)：P1–P14 全部实现（三Tab内容优先、任务导向筛选、事件轨道回放、片段面板与导出弹层、设置全量、诊断、引导），新增core数据层；`./scripts/build_autorecord.sh`实测54项单测通过、assembleDebug成功。摄像头/车辆/引擎/解码/导出仍为未接入实现，替换点收敛在接口边界；未连车、未安装。

2026-11-03：[AutoRecord功能与UI评审修订](docs/AutoRecord/评审与优化-20261103.md)：核对需求与页面规格，采纳10条修订——补取证主链路（一键锁定2段+事件保护+导出并入P4）、量化保留窗口（32GB≈4.3小时）、录制启动改两阶段预检、补存储收敛决策树；UI 侧实时页改内容优先、数据条折1行、筛选任务化、增设画质预设、撤销长按改操作条并加行驶中只读。规格升 v1.1（新增§14关键流程），需求稿升 v0.2（R13）。

2026-11-03：[AutoRecord子工程整理](docs/AutoRecord/README.md)：七份AutoRecord文档归集到`docs/AutoRecord/`并新增入口台账（文档索引、页面/代码映射、B0–B5进度、红线、待确认项）；`:record`模块已落地core与P1/P2/P5/P6/P7页面，`./scripts/build_autorecord.sh`实测15项单测通过、assembleDebug成功；摄像头契约与录制引擎未开始，未安装、未连车。

2026-11-03：[AutoRecord四向记录仪需求与交接](docs/AutoRecord/需求与DeepSeek交接-20261103.md)：按三张截图整理四路录制、录像库、历史车辆数据同步回放与事件时间轴；摄像头及车辆实时数据统一来自AutoService。已核对EVCam固定源码与本地契约缺口，仅规划、未开发；另附[DeepSeek任务书](docs/AutoRecord/DeepSeek任务书.md)。同日按用户确认补[逐页功能与设置规格](docs/AutoRecord/页面功能与设置规格-20261103.md)（UI 继续继承 FlymeAuto，设置含存储位置/编码格式/硬软编码）、[样式基线](docs/AutoRecord/FlymeAuto样式基线-20261103.md)与[编码与存储技术口径](docs/AutoRecord/编码与存储技术口径-20261103.md)。

2026-11-03：[Kanzi音乐卡片现场调试](docs/CarPlay原厂Kanzi音乐卡片原型-20261103.md#追加2026-11-03现场连接与加载调试)：指定L6已连接、原始手机歌词/状态文件与双LOAD候选依赖解析已核验，CarPlay同包更新。原厂HMI未替换，实际挂接/恢复待单独授权及驻车试验。

2026-11-03：[ECARX对照与L6定向整改](docs/ECARX对照与L6定向整改-20261103.md)：补L6 DIM箭头/距离/剩余时间、原车媒体回环HTTP封面与异步重发、手机dB驱动的PCM音乐压低；普通电话正常，第三方通话上行和完整视频DIM替代路线仍未确认修复。未安装或实车验收。

2026-10-31：[车辆数据经AutoService订阅（轮速接入）](docs/AutoService车速订阅-20261031.md)：契约升v3，新增按需车辆信号订阅（`setVehicleFeed`/`onVehicleFeed`）；CarPlay 只订阅 `displaySpeed`/`gear` 生成 iAP2 `$PASCD`，不再反射厂商SDK、不需要ADB/Root；位置仍走本机provider。离线回归与三APK编译见记录；L6行驶车速、`km/h`单位、有无GPS及iPhone侧是否采用仍需实车验证。

2026-10-31：[DiPlay v0.2.22三方迁移](docs/DiPlay-v0.2.22核心迁移.md)：保留Galaxy车型、微信电话通道、原车媒体、HAB和独立应用适配，整合官方固定发布核心。冲突处理、发布SHA、回归及APK见记录；智能显示缩放回退和原厂DIM歌词消费仍待实车定位。

2026-10-30：[AutoService界面与功能开关整改](docs/AutoService界面与功能开关整改-20261030.md)：Flyme原生日夜卡片、运行概览、自动运行及投屏/车辆数据/本机ADB/Root独立开关、全部关闭；设置接实际启停与持久化。assembleDebug与原签名核验通过，已在隔离Android11模拟器安装并取得日夜/关闭/诊断截图；全部关闭后重开App保持设置。尚未车机安装或实车验收。

当前总览：[哪些已跑通、哪些未跑通（2026-10-30）](docs/当前跑通状态-20261030.md)。统一区分指定L6实车成功、出图但不稳定、已实现待实测与未完成；历史记录保留各自轮次边界。

2026-10-30：[仪表传输断流修复候选包](docs/仪表传输断流修复包-20261030.md)：针对现场QNX帧校验`-77`，加入导入缓存同步、私有快照、GLC2 v2逐帧采样校验与有限重传。AutoService assembleDebug和包内产物绑定完成；用户已离车，未安装复测，原车告警不声明已解决。

2026-10-30：[ECARX按键事件解构](docs/ECARX按键事件解构-20261030.md)：已取得材料中18份有效APK、40个DEX完成命名空间扫描，整理5种按键广播、现车InputService的31个注册键码及TRIP原始VHAL路线；源码定位与实车捕获分别标注。本轮只做研究，未接入或安装。

2026-10-30：[车辆读取与投屏现场修复](docs/AutoService车辆读取与投屏现场修复-20261030.md)：已修Root helper主Looper崩溃、固件声明/映射与PA编号、公共客户端快照回调，三个App已同包更新；车辆目录5912项、现场3986项有效，主页面已显示自身ADB/Root状态。后续QNX部署/画面已确认，但仍有断流和原车告警，不能写成稳定性验收通过。

2026-10-30：[AutoService ADB/Root已实车闭环](docs/AutoService-ADB实车闭环-20261030.md)。已更新同签名App，自身TLS/ADB UID0、RootService UID0、首次权限/公钥恢复及新进程恢复均通过；本轮后续按用户要求直接调试App，不再运行单元测试或Python验证循环。

已实现[AutoCluster可视化主题编辑器](docs/Cluster可视化主题编辑器实施方案-20261030.md)：拖拽/缩放/图层/分组，主题保存修改切换、背景/导入导出，24类组件与72个三列只读样式预览，日夜及组件专属属性。二轮增加[巡航三态、四档回收与五行歌词/菜单四页组件主题](docs/Cluster二轮需求-巡航回收与音乐分区-20261030.md)。完整111地图与主题由AutoService统一EGL合成，刷新上限可配置并按内容调度，自有HAB消费确认避免共享槽位提前覆写。实车帧率/负载和原车告警仍待目标L6验收。

[上车前完整准备与现场步骤](docs/Cluster上车验收-20261030.md)：`bash scripts/verify_cluster_preflight.sh --package`；全部离线检查通过才打包三个同签名APK、配套接收程序、GPU测试APK、日夜预览和可复现报告。未连接、安装或代替实车验收。

本次现场按[两小时执行卡](docs/上车两小时执行卡-20261030.md)：先连接/GPU/原车告警与恢复，再性能和字段标定；统一命令与验收记录已准备，最后20分钟预留定位、退出和保存。

最新[歌词/巡航样式校正与原厂告警素材进展](docs/Cluster样式校正与原厂素材-20261030.md)：歌词324px、四行（1已播/1当前/2待播）、220px进度及右上档位/驾驶模式，巡航差值弧、双向kW显示；124项JVM离线回归通过，原厂告警与功率方向待实测。

开始[投屏帧优化与原车告警核验](docs/投屏帧优化与原车告警-20261030.md)：主题改GPU Canvas、新帧唤醒发送、QNX少一份整帧中转，并补阶段计时和原车窗口快照。原车提示已记录为独立待解决问题；尚未验证告警根因、8155实车收益或安全共存，不停用原车图传。

有投屏需求时现已支持[自动唤起QNX接收端](docs/QNX接收端按需唤起-20261030.md)：退出或QNX重启后自动启动已安装程序，首次或校验不匹配才部署；无需手动进入诊断。64项JVM与15项HAB/配置工具测试、三个APK编译通过，尚未实车验收。

HAB启动已按用户建议进一步优化为[先直连、后准备](docs/HAB直连复用优化-20261030.md)：现有接收端可连时，成功的普通App会话直接送帧，不等待ADB/Root或QNX登录；只有失败才进入自有helper/接收端准备。接收端保持原有有限监听，不改原车启动项；本轮仍待复车。

AutoService整改已落入源码并产出APK：复用星枢TLS/Root流程，内置首次自动准备与QNX认证取得，补开机/异常恢复，精简界面和资源分配；47项JVM、15项HAB/配置工具及28项项目/采样脚本测试通过，不跑Lint。实现、APK及实车未测项见[整改实施记录](docs/AutoService整改实施-20261030.md)；[方案](docs/AutoService整改方案-20261030.md)保留研究与设计依据。尚未安装或完成本轮实车验收。

新车机 CarPlay 接收端项目。应用显示名 **CarPlay**，包名 **com.ecarx.carplay**。首验为 **银河 L6 2023 年 Max / FlymeAuto 2.3.1**，L7 随后适配。

当前已进入**多应用实施阶段**：CarPlay采用官方DiPlay v0.2.22固定发布核心与Galaxy适配；`galaxy/service`建立独立AutoService（`com.ecarx.autoservice`），集中车辆数据、Root/ADB、保活与HAB合成；`galaxy/cluster`为独立仪表，应用通过公共契约通信。新三个APK编译通过；本轮不跑Lint，实车断开后未安装或验收这次拆分。当前实现、回归结果和测试包流程见[AutoService实施与测试](docs/AutoService实施与测试.md)。此前CarPlay/人工帧的现场结果按各自记录保留，不能代验新三App。


## 项目文档

2026-10-30新阶段：[多应用架构规划](docs/多应用架构规划.md)明确AutoService、AutoDesktop、CarPlay与独立仪表/未来AI的分工；[新仪表设计还原](docs/新仪表设计还原.md)追到星枢原图和现有预览；[临时图层方案](docs/仪表临时图层方案.md)区分窗口层级、图内合成与透明度。已进入实施，新增 `galaxy/service`（`com.ecarx.autoservice`）、公共契约/客户端和独立仪表。实现及复车步骤见[AutoService实施](docs/AutoService实施与测试.md)，当前未连接车辆。

2026-10-30：[地图/音乐HAB合成与微信语音通话修复](docs/CarPlay-HAB与微信语音修复.md)已接入、编译通过并按用户授权安装，功能待用户实测；本轮未跑Lint。[HAB人工帧实车验证](docs/HAB实车验证-20261029.md)的6+35帧是旧轮次证据，不能代替本次APK验收；[测试包和调试步骤](docs/上车测试准备.md)已更新。

用户随后已确认地图/音乐画面可见；触发不稳定的[修复与复车步骤](docs/HAB连接稳定性修复-20261030.md)已准备，新版编译/72项回归通过，网络断开后待安装复车。[当前布局](docs/仪表布局快照-20261030.md)仅保存为后续重做依据。

- [工程基础](docs/工程基础.md)：源码布局、固定基线、补丁、验证命令与本轮实际结果。
- [项目规划](docs/项目规划.md)：接入架构、上游跟随、开发阶段和验收门槛。
- [决策记录](docs/决策记录.md)：D01 固定核心已确认；D02 为 L6 2023 Max / FlymeAuto 2.3.1。
- [参考实现对标笔记](docs/参考实现对标.md)：记录别人实际的方法顺序、数据、绘制和提交逻辑，供用户明确要求开发后逐段对标。
- [ECARX 仪表投射对标](docs/ECARX仪表投射对标.md)：从原仓实拍反查完整地图流、解码、仪表窗口、DIM接通与音乐信息链。
- [源码核验](docs/源码核验.md)：来源、实现证据、适用边界及与用户已验证路线的版本差异。
- [HAB源码复现](<docs/HAB源码复现.md>)：v3.6.2 native协议/驱动/像素/生命周期静态还原、自有C sender与离线测试；不加载第三方SO。
- [HAB通道修复](<docs/HAB通道修复.md>)：依据现场已通的收发/共享区链加入QNX PMEM/Screen后端，修正可写buffer与ACK顺序；修复后尚未部署复测，CarPlay帧合成尚未接入。
- [协作规范](AGENTS.md)：后续 Agent 必须遵守的工作边界。
- [来源锁定](references/来源锁定.json)：本轮检出 SHA、本地快照与来源状态。

## 材料与当前边界

已拉取 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[zmisgod/DiPlay](https://github.com/zmisgod/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX)，并制作用户指定本地 GalaxyPlay 的已跟踪源码快照。

“车机逆向”与“星枢”原目录保持只读；按用户授权复用自有认证实现并内置本车授权取得流程，真实私密材料不进入公开资料、日志或诊断。参考材料不自动整包打入产品。

用户已明确要求开始建立项目基础，本轮据此解除该范围的只研究限制。保留参考实现对标记录；不把基础落入当作所有功能已迁移。根Git按D10初始化：`main` 只收已验证状态，改动走 `feat/*`/`fix/*` 短分支，不推远端。当前只做本机准备；后续按既有授权范围连接与部署，不替换签名或推送发版。

## 源码目录

- [galaxy/mobile](galaxy/mobile/build.gradle.kts)：CarPlay唯一应用模块，CarPlay入口、清单、资源和构建配置只放这一处。
- [galaxy/common](galaxy/common/src/main/java/com/ecarx/carplay/product/GalaxyProduct.kt)：产品配置及后续Flyme/车型/OEM适配，由[common](common/build.gradle.kts)编译。
- [galaxy/service](galaxy/service/build.gradle.kts)：独立AutoService，车辆采集、ADB/Root、前台保活、画面合成与HAB输出。
- [galaxy/cluster](galaxy/cluster/build.gradle.kts)：独立仪表，主题与预览。
- [galaxy/contracts](galaxy/contracts/build.gradle.kts) / [galaxy/client](galaxy/client/build.gradle.kts)：版本化AIDL契约和客户端。
- [shared](shared/build.gradle)：CarPlay协议、传输和音视频核心。

根mobile已剔除；`:mobile`仅为映射到galaxy/mobile的Gradle逻辑名，不存在第二套应用。

## 基础检查

```sh
scripts/verify.sh          # 历史基础全量入口含Lint；当前整改不使用此入口
scripts/verify.sh --quick  # 只跑离线源码检查（秒级）
scripts/build_apk_fast.sh  # 快速编三个APK，不执行lint/单测
scripts/verify_auto_platform.sh # 本轮契约/native/主题回归和三个APK，不执行Lint
```

Android构建沿用上游固定工具链，不沿用旧工程Docker约定；签名材料仍外置，认证自动取得见[整改实施](docs/AutoService整改实施-20261030.md)。详见[工程基础](docs/工程基础.md)和[安全约定](SECURITY.md)。
