# AutoService 整改方案：先打通 Root / ADB，再恢复常驻能力

2026-10-30。本文初版为整改设计与源码核验，当时未修改运行代码或构建APK。**用户随后要求开始整改，已落入运行源码并产出新APK，最新状态见[整改实施](AutoService整改实施-20261030.md)**。下文缺陷源码位置与初版来源指纹对应整改前状态；不要把历史方案/初版文档核验当作当前实现状态。当前仍未完成新APK的目标固件实车验收。

**后续用户纠正（本版已纳入）**：星枢是用户自有App，车机与连接授权也属于用户；认证方式可以复用，QNX授权方案和凭据取得方法应内置。先前把“凭据不进入公开资料”扩大成“App不能内置认证、用户必须外部导入”是错误约束。正常路径应自动取得并使用本车授权材料，手工配置仅保留为诊断入口；个人构建内置凭据也不是被禁止的选项。

## 1. 已确定的整改方向

- AutoService 是后台基础服务，正常使用不依赖打开界面或手点多个准备按钮。先完成一次初始化，此后开机自动运行、异常退出后恢复已保存的工作状态。
- 首先闭合本车的无线 ADB / RootService 链路，再接本 App 权限恢复、QNX 和业务服务。开发验收按这一顺序推进，不用仪表 UI 或 APK 编译掩盖底座缺口。
- 调试界面收敛成状态、自动运行开关和一个“诊断”入口。Root/ADB 分步操作、Shell、属性分页、QNX 配置留在按需打开的诊断页。
- 全量可读车辆数据继续采集；通过事件、分层刷新、索引、增量发布和按需资源降低 CPU/内存，不能通过删字段、停必要服务或降低现有地图输出质量完成“优化”。

## 2. 星枢与车机逆向实际怎么做

以下来源只读。星枢没有可用固定发布提交，车机逆向也有工作区改动，不能用参考仓库 HEAD 代替所读代码。此次具体文件的 SHA256、行数与读取时间见[来源指纹](../tools/evidence/autoservice-remediation-sources-20261030.json)。优先复用用户自有星枢的认证实现；自有身份/授权材料可按用户授权由程序迁移或协同使用，具体绑定由代码适配，不把私密内容抄进公开文档。第三方Demo的native库不因认证流程复用而自动成为产品依赖。

### 2.1 ADB 与 Root 的调用顺序

1. 星枢 [`WirelessAdbStartupActivator.enable():13–45`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/WirelessAdbStartupActivator.kt:13>)先检查本 App 是否持有 `WRITE_SECURE_SETTINGS`，只恢复 `adb_enabled` / `adb_wifi_enabled`，最多 20 次、间隔 500ms 检查开关与 `service.adb.tls.port`。开关写入成功与端点就绪分别判断。
2. [`LocalAdbEndpointResolver.resolve():21–47`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbEndpoint.kt:21>)默认读取实际 TLS 端口；有界发现还可检查本机监听候选，逐个确认 CNXN/STLS，最多 64 个候选，**不是默认连接 5555**。其中偏好里的 5555 仅用于显式直连配置。
3. [`AdbConnection.connect():23–57、createClient():120–136`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbConnection.kt:23>)使用本 App 独立 RSA 身份、STLS gate 与 KADB 完成 AUTH/TLS，强制验证 `shell_v2`；[`AdbStlsTunnel:46–66`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbStlsTunnel.kt:46>)核验的是实际连接收到的 STLS，不能只靠一次端口探测认定安全连接。
4. [`AdbRootNegotiation.request():24–35`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbRootNegotiation.kt:24>)先读取 ADB UID；已经为 0 直接通过，否则请求 Root。Root 造成 adbd 断开后重新建立连接并读取 UID；`awaitRoot():77–91`有总截止时间和有限次数。响应文字、EOF 或命令退出码都不能代替新连接的 UID。
5. VPWD 只按已证实的型号分支或固件明确响应处理；[`AdbRootAuthPath:12–21`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbRootAuthPath.kt:12>)还保留 Android 11 E245 例外。星枢 [`AdbRootControlAdapter:77–84`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbRootControlAdapter.kt:77>)没有打包 EVCC native getter；[既有 G733 记录:82–86](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/docs/analysis/evcc-root-adb-flow.md:82>)证明当次没有可见 VPWD 仍取得 UID 0。不得把“缺 VPWD”设成 L6 默认障碍。
6. [`startRootService():87–97`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbRootControlAdapter.kt:87>)先认证现存 helper，不可用才在已确认 UID 0 的 ADB 上启动 `app_process` 并等待就绪。客户端通过内核 peer UID/PID、APK 路径、实例/nonce 及拥有者身份绑定响应。
7. [`RootAdbUseCases.initializeRootService():106–120`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/domain/usecase/RootAdbUseCases.kt:106>)区分交互与开机：交互可检查已有 su 授权；开机跳过可能弹授权窗的 su，走 ADB。不能把电脑 root Telnet 能用、系统存在 su 文件，推断为普通 APK 能运行 `su -c`。

车机逆向 Demo 的 [`initializeRootService():85–95`](</Users/anjon/Documents/ChatGPT/车机逆向/apps/l6-nexus/android/root-adb-demo/app/src/main/java/com/ecarx/l6nexus/rootdemo/DemoAdbRootController.kt:85>)也是 su / ADB → UID 0 → helper，且 [`verifyRootService():145–158`](</Users/anjon/Documents/ChatGPT/车机逆向/apps/l6-nexus/android/root-adb-demo/app/src/main/java/com/ecarx/l6nexus/rootdemo/DemoAdbRootController.kt:145>)在 socket 身份核验后，再通过 helper 执行 `id -u` 读回 0。其 [`RootServiceStartupService:27–49`](</Users/anjon/Documents/ChatGPT/车机逆向/apps/l6-nexus/android/root-adb-demo/app/src/main/java/com/ecarx/l6nexus/rootdemo/RootServiceStartupService.kt:27>)只是一次性初始化器，成功/失败均退出。

### 2.2 首次授权与后续恢复是两个流程

星枢[冷启动与实车记录:112–125](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/docs/analysis/evcc-root-adb-flow.md:112>)记录了实际依赖：先经既有 Android root 调试通道授予星枢 `WRITE_SECURE_SETTINGS`，随后 APK 才能在下次开机恢复无线 ADB。系统权限按包名授予；迁移时由程序通过已有授权通道完成AutoService的授权和读回，这不妨碍认证方案复用，也不意味着让用户再手动执行一次命令。

[`AdbAuthorizationRecovery:21–55`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/AdbAuthorizationRecovery.kt:21>)只有在现存、已认证的 RootService 可用时，才能登记**本 App 公钥**、重启 adbd 并重发现端口；这不是首次无权限启动的提权入口。

[2026-10-19 实车记录:143–149](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/docs/progress/root-adb-debug-log.md:143>)分别记录公钥恢复、RootService `id -u=0`、ADB Shell `uid=2000`。同一时间 ADB Shell 为 2000 与 RootService 为 0 可以同时成立。历史记录证明了参考路线，不能代验当前 AutoService APK。

### 2.3 开机、自恢复与资源所有权

- [`BootReceiver:14–39`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/infrastructure/receiver/BootReceiver.kt:14>)处理 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`，复核自启状态后启动协调，不在广播里做网络等待。
- [`KeepAliveService:32–75、78–91`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/infrastructure/service/KeepAliveService.kt:32>)并发去重、处理 Wi-Fi 未就绪、执行初始化，结束后停止自身。它明确不是永久守护进程。
- [`NexusxRuntimeService:45–64`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/infrastructure/service/NexusxRuntimeService.kt:45>)才持有常驻恢复：`START_STICKY` 重建读取持久选择；[`StartupRecoveryLoop:14–38`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/infrastructure/startup/StartupRecoveryLoop.kt:14>)成功后 60 秒检查，失败从 30 秒退避、最高 5 分钟，停止会使旧回调失效。
- [`WifiAdbRecoveryScheduler:18–62`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/infrastructure/service/WifiAdbRecoveryScheduler.kt:18>)提供网络就绪任务与持久 15 分钟周期任务，并按 jobId 去重/取消；周期任务只是系统调度的恢复机会，不能写成 15 分钟准时或即时重启保证。
- [`RootPeerOwner:6–14`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/root/RootPeerOwner.kt:6>)以 UID、PID、进程起始时间绑定 helper；[`RootServiceMain:65–85`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/root/RootServiceMain.kt:65>)在 owner 死亡/空闲退出。App 新进程建立自己的 helper，不继承旧 PID 的会话。
- [`RootServiceClient.activate():152–158`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/RootServiceClient.kt:152>)以无命令载荷续期，避免运行 Shell 保活。签名摘要挑战是兼容性校验，其摘要为公开信息，不能当秘密；内核 peer 与进程所有权校验必须一起保留。

## 3. 当前 AutoService 的具体偏差

| 当前源码 | 核验到的问题 | 整改 |
| --- | --- | --- |
| [`PlatformAccess:13–29`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformAccess.kt:13) | 默认固定 5555，直接 Kadb.create；没有开关恢复、TLS 端点发现与完整授权恢复 | 替换为目标固件的本机 TLS 启动/解析/连接接入 |
| [`PlatformCoordinator:55–68`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformCoordinator.kt:55) | 文本 contains 判断 UID；Root 后睡眠 700ms；helper 仅三次 250ms 等待 | 类型化 UID 与总截止时间；Root 重连后重新发现端口；helper 认证/读回 |
| [`AndroidManifest:1–25`](../galaxy/service/src/main/AndroidManifest.xml:1) | 无 RECEIVE_BOOT_COMPLETED、BootReceiver 或 JobService | 正常系统启动/更新入口，持久恢复任务 |
| 旧`KeepAlivePolicy:3–17`（整改后已删除）、整改前`AutoService:225–228` | 仅投屏请求存在时，依靠车辆采样掉线触发最多三次 Root 重试 | 已由[`FoundationRecovery`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/FoundationRecovery.kt:4)独立维护基础连接；不依赖车辆采样和投屏 |
| [`RootServiceMain:17–35`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:17)、[`RootServiceClient:13–36`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceClient.kt:13) | helper 只绑定 App UID；socket 名不区分 App 进程代次，缺少完整 hello/owner 校验 | 增加 PID/起始时间/实例绑定和版本化握手，旧 owner 退出配套清理 |
| [`PlatformCoordinator:31–39`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformCoordinator.kt:31) | 批量 Shell 只收返回码，没有权限/AppOp/白名单实际状态回读 | 只补所需差异，执行后验证；首次赋权前置单独处理 |
| [`AutoService.onCreate():176–186`](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:176) | 服务创建就绑定 Root/权限/QNX 准备，基础能力和投屏寿命耦合 | 基础连接先独立就绪；QNX 按投屏需求准备 |
| [`ServiceActivity:24–123`](../galaxy/service/src/main/java/com/ecarx/autoservice/ServiceActivity.kt:24) | 多步骤控制台成为默认入口，Activity 自建 PlatformAccess 操作连接 | UI 只发请求/观察服务状态，连接只有一个后台 owner |

当前 [`PlatformExecutionTest`](../galaxy/service/src/test/java/com/ecarx/autoservice/platform/PlatformExecutionTest.kt:14)覆盖重试策略、Shell、校验和与 fake QNX 正向登录，未覆盖完整 ADB 启动链、开机与进程死亡恢复。这些离线测试不能作为当前底座正确的证明。

## 4. 整改后的正常启动链

```text
首次部署（一次性）
  复用星枢已有认证实现 / 已授权通道，选定由AutoService集中管理的ADB身份
  → 自动完成所需身份迁移或公钥登记、本 App权限配置
  → 回读权限、ADB 开关、TLS/AUTH、ADB UID、RootService UID
  → 保存自动运行和已选功能

开机 / App 更新 / 系统重建
  BootReceiver 或系统恢复入口 → AutoService 前台运行时
  → 合并重复启动请求，读取持久状态，建立本代次
  → 认证本代 helper；需要时恢复无线 ADB、解析 TLS 端口
  → AUTH / shell_v2 → 读取 ADB UID → 必要时请求 Root
  → adbd 重连 / 端口重发现 → UID 0
  → 启动本代 app_process helper → peer/owner/hello 校验 → helper id -u=0
  → 核对并修复本 App 权限 → 基础服务可用
  → 有投屏需求时，由 Android UID 0 helper 连接 QNX
  → QNX 身份核验 → 自有 receiver → HAB READY/ACK → 内容提交
```

首次准备不能设计成“先有 RootService，才能给 ADB 授权；先有 ADB 授权，才能启动第一个 RootService”的死循环。优先利用用户自有星枢的已有认证实现和已授权通道；跨App调用需要的包名/UID/owner适配由程序完成，不直接假设旧helper接受任意新App。已有授权身份可经授权迁移给集中连接层，或者自动生成并登记新公钥，均不要求用户找密钥/手动配对；权限针对 `com.ecarx.autoservice` 自动配置并回读。已有授权可用则直接检查通过，不反复重启 adbd。

前后台调用共用一个启动状态机。无授权的 su 不在无人值守路径触发；本机 TLS 为本车默认，保留明确配置并验证过的接入选项。设备需求分开判断，不给 loopback ADB 和自有 QNX 内网连接机械套用“必须 Wi-Fi 已连接且有互联网”的统一门控；参考项目的 Wi-Fi 等待只用于它实际依赖的无线端点准备。

## 5. QNX：分别验证连接者与远端执行身份

“普通用户能不能执行 Shell”必须拆成 Android 普通 App 执行本地命令、普通 App 到 QNX 的网络可达性、QNX 登录后的权限三件事。不能由 Android UID 0 推断 QNX 也是 0，也不能由某次网络连接失败推断是 UID 原因。

现有 [`RootServiceMain:66–69`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:66)已把部署/停止放在 UID 0 helper 中；[`QnxReceiverDeployer:14–17`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxReceiverDeployer.kt:14)在该进程创建连接；[`QnxTelnet.login():49–55`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxTelnet.kt:49)要求远端独立返回 `uname -s=QNX` 与 `id -u=0`。保留这个方向，先修好上游 Root，不能重复造一套 Activity 普通 UID 部署器。

复车时先做有界只读核验：记录发起端 Android UID/SELinux 域和连接阶段，确认 RootService 到既定 QNX 地址的连通、登录和只读命令结果。普通 App 路径如需对照，只执行同样的只读探测，用结果决定是否存在 UID/域限制，不让它承担部署。QNX 自身非 0 登录是否具备 Screen/HAB 权限目前没有 AutoService 实车证据；当前部署契约仍按已采用的 QNX UID 0 路线执行，不能凭空用 `su` 或 Android UID 替代远端登录。

QNX 准备按“自动取得认证材料 → 连接 → 身份 → 本服务程序版本/状态 → 必要的部署 → 监听 → HAB 握手 → 帧提交”独立记阶段。相同版本且自有 receiver 健康时复用，异常或升级才更换，不在每次准备时复制、停止、重启。一次部署复用一个短时 Telnet 会话，空闲关闭；帧数据继续走 HAB。只管理自有程序/窗口/PMEM、正常 TERM 释放的边界不变。

### 5.1 凭据取得方法内置，取消正常流程的手工导入

这里不是未知路线：[`authorized_qnx_credentials():76–101`](../tools/hab/telnet_session.py:76)已能核验既有OEM APK的SHA256，遍历DEX字符串表，找到两处对应QNX登录的流程并验证账号/口令一致；[`provision_autoservice_qnx():121–133`](../tools/hab/telnet_session.py:121)再把结果配置到App私有目录。现工具是在电脑上读取指定APK，尚不是App自动取得。当前 [`QnxCredentials.load():25–28`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxCredentials.kt:25)只读私有文件，正是需要替换的人工前置。

已读取本车样本Manifest，包名为 `com.flyme.auto.logreport`。实施时内置专用来源解析器，由UID0 helper定位该**已安装原厂包的真实APK路径**，核验已知来源/profile，按已有DEX方法只解析所需登录信息，再直接交给QNX登录。定位使用包管理信息，不能硬编码电脑的样本目录或猜测安装路径。取得流程不执行原厂字符串中的PCM录音、删除等命令，也不要求把原厂APK重新打包进AutoService。

解析只在首次需要或来源版本变化时执行，后续使用私有缓存/存活会话；来源版本、解析状态和登录结果分开记录，日志不输出真实认证内容。固件升级使来源变化时，由Agent根据新原厂版本更新适配，不把错误转成“请用户自己找密钥”。已授权的本机样本可用于个人构建自动预配置；如果选用个人构建内置真实凭据，也在用户此次授权范围，构建输入与真实值不进入公开源码或诊断产物。默认选择内置取得方法，是为了跟随本车来源自动更新，不能再表述成“凭据技术上不能内置”。

正常用户流程为启动/开机 → 自动准备Root → 自动取得QNX认证 → 自动登录与准备receiver。外部两行文件、手动配对或手工赋权不作为常规步骤；诊断页保留手工覆盖能力即可。

## 6. 常驻、自恢复与主动停止

| 事件 | 应有行为与验收证据 |
| --- | --- |
| 开机 / 包更新 | 自动运行状态启用时恢复基础能力，无需打开 Activity；记录启动来源、阶段与本代次 |
| 普通进程崩溃 / 系统回收 | 前台服务 START_STICKY 重建后恢复持久选择；系统 Job 提供后续恢复机会；验证新 PID、新 helper、业务订阅重建 |
| Root helper 退出 / adbd 重启 | 独立连接状态触发有限短重试，再进入低频退避；网络/端点就绪事件可重新触发；车辆页和仪表关闭不应阻止基础连接恢复 |
| QNX receiver 退出 | 仅投屏会话恢复自有 receiver/HAB，不连带重授全部 Android 权限或重启 adbd |
| 用户关闭自动运行 / 主动停止 | 先持久化停止意图，再取消任务/失效旧回调，停止自身投屏与辅助资源；不得被残留 Job 再拉起 |

借鉴星枢正常 60 秒、失败 30 秒至 5 分钟的维护周期，作为初始设计参数；断连通知先触发有界恢复，正常有业务通信时不额外发 Shell 心跳。不是永久三次耗尽后再也不恢复，也不是每 250ms 持续重启。

helper 继续采用 App 会话所有权，不改成脱离 App 的永久 root 守护。连接断开、owner 失效时关闭自有 HAB 子进程；QNX 侧利用会话退出/超时清理自有窗口并准备下一会话。App 崩溃不会执行 `onDestroy()`，恢复和释放必须覆盖 EOF、owner 死亡及目标进程退出，不能只靠正常关闭路径。

强行停止与主动停止、普通崩溃分别记录。系统强行停止可能阻断广播/任务，不能承诺任何场景都立即重启；整改验收要记录实际恢复时长，不用循环拉起或修改原车 init/HMI 规避。

## 7. UI 与代码职责收敛

默认页仅显示服务总状态、Root/ADB 与 QNX 简要阶段、自动运行开关，以及“诊断”入口。初始化所需配置在首次准备时完成，成功后不反复出现。退出页面只解除 UI 观察，不停止后台。主动停止仍可在诊断入口/通知中到达。

诊断页按需打开连接详情、单次重新初始化、Shell、车辆目录与 QNX 配置；默认不持续刷整份属性、不持有连接 worker。移除首页的 Root/ADB/su 多步骤按钮、车型会话按钮、仪表启动按钮和 Shell 输入框。车型配置保留在明确的配置入口，L6 为首验默认；独立仪表的用户操作由 AutoCluster 承担。

| 现有类 | 整改后职责 |
| --- | --- |
| AutoService | 前台生命周期、装配公共服务、Binder 委托；车辆、Shell、封面与恢复逻辑逐项抽出，不继续扩大类职责 |
| PlatformAccess | 拆出 ADB 端点/授权连接与 Root 建立，所有调用共用后台实例；保留所需 Shell 能力 |
| PlatformCoordinator | 收敛为基础连接/权限状态机；QNX 部署属于投屏会话，移除“一次 prepare 全做完”的耦合 |
| RootServiceClient / RootServiceMain | 版本化握手与 owner 生命周期；车辆读取、命令执行、HAB 通道分派到单独处理器 |
| KeepAlivePolicy | 替换采样驱动恢复为独立基础恢复策略；启动、健康维护、异常恢复分清事件 |
| ServiceActivity | 只显示状态和发送用户意图；诊断页按需创建，使用同一服务接口 |

沿现有模块和类职责修改，不再套一层万能 Manager、并排保留两套 bootstrap，或整包迁入星枢终端/自动化/车控平台。每批只解决明确依赖，抽提不混入无关命名迁移。

## 8. CPU / 内存整改清单与测量

以下是源码确认的开销点，不是已经测得的 CPU 百分比或内存峰值。

| 源码证据 | 整改措施 |
| --- | --- |
| [`EcarxVehicleSource:73–80`](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt:73)忽略变化回调；`:103–110`每轮固定读语义字段与32项目录 | 使用本车回调更新缓存。目标 SDK [`onChangeEvent/onErrorEvent:35–38、199–207`](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/telemetry-live-20261025/sdk-sources/sources/ecarx/car/hardware/property/ECarXCarPropertyManagerBase.java:35>)确实有值/属性区域输入；首次目录全量轮读保留，事件不足的属性按实际新鲜度预算补读，逐项保留状态和区域 |
| [`AutoService:213–218`](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:213)为每个语义字段重建并搜索整个 JSONArray | 每批只遍历一次建 key 索引；缓存车型映射、反射 Method，减少每轮容器与字符串分配 |
| [`VehicleInventory:9–25、43–45`](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleInventory.kt:9)字符串格式化键、全量计数与线性查找 | 使用 propertyId/areaId 复合键、增量计数；分页顺序单独维护；完整原始值仍可按页/片段取得 |
| [`AutoService:295–306`](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:295)每次 publish 重建完整 Bundle 并通知全部客户端 | 缓存稳定目录与不变段；车辆/媒体/连接状态按变化发布，按角色/订阅兴趣合并限频，时效到期也发状态变化；公共契约演进保持版本兼容 |
| [`RootServiceClient.read():23–35`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceClient.kt:23)每轮新建 socket + JSON | Root 稳态只保留必要数据通道，采用有界帧与批次/增量；控制请求和大帧不共用阻塞队列；连接代次失效立即关闭 |
| [`AutoService:18–25、179`](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:18)无任务即创建多个 worker；[`AdbShellExecutor:13–16`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AdbShellExecutor.kt:13)每条命令新建计时 executor | 按功能延迟创建/空闲回收线程，复用一个有界截止时间调度器；车辆读取、长命令与帧输出保持阻塞隔离 |
| [`RootServiceMain:27–31`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:27)15秒空闲退出、每5秒检查，与低频维护不协调 | 以明确租约和 owner 生命周期持有必要 helper；只在存活会话续期，关闭时释放，不用高频采样维持存活 |
| [`GalaxyHabFrames:4–5`](../galaxy/service/src/main/java/com/ecarx/autoservice/projection/GalaxyHabFrames.kt:4)固定三份 RGBA；[`GalaxyHabComposition:51–62、110–134`](../galaxy/service/src/main/java/com/ecarx/autoservice/projection/GalaxyHabComposition.kt:51)Surface/ImageReader 和 CPU 拷贝 | 已有最新帧三缓冲继续保留；仅投屏需要时分配，结束后释放；没有新帧不重复构造/发送。先统计各拷贝阶段，再决定 native/FD 传输优化，不未经驱动合同就宣称零拷贝 |
| [`ClusterRenderService:24–33`](../galaxy/cluster/src/main/java/com/galaxy/autocluster/ClusterRenderService.kt:24)即使无 Surface 也每100ms唤醒 | 无有效 Surface 取消 draw；静态页面按事件绘制，时间/进度/动画只在必要节拍绘制；保持地图会话正常帧率 |

三份 `1920×532×4` 的 Java 像素数组本身为 **12,257,280 字节，约11.69 MiB**，不含 ImageReader/图形缓冲、native scratch 和封面。这是尺寸计算，不是 PSS 测量。现有输出间隔 [`GalaxyHabLayout:11`](../galaxy/service/src/main/java/com/ecarx/autoservice/projection/GalaxyHabLayout.kt:11)为100ms；此次优化不把进一步降帧当成收益。

实车测量固化为仓库内脚本，默认采集进程 CPU 时间、PSS/RSS/Java/native/graphics、线程/FD、每秒属性读取/回调/IPC次数、帧生产/丢弃/提交与恢复耗时。App、UID 0 helper、自有 HAB 子进程、AutoCluster 分开列，也给合计，防止把开销移到另一个进程后宣称优化。QNX 自有进程有可用计数器时单列，不把 Android PSS 说成整车开销。

固定同一 APK/固件/场景，预热60秒、观察5分钟，比较整改前后：基础服务空闲、全目录采集、仪表静态、完整地图+音乐、会话停止后、20轮连接/退出。输出CPU均值/峰值、内存稳定值与趋势、线程/FD回落、数据新鲜度和帧延迟。目标是减少无效唤醒/分配且无持续内存增长；具体 CPU/PSS 上限依据首个实测基线确定，不能现在编造“<1%”或固定 MB 达标。

## 9. 实施批次与验证门槛

| 批次 | 具体改动 | 完成证据 |
| --- | --- | --- |
| P0 Root/ADB 底座 | 复用星枢认证实现与已有授权通道、自动身份/权限准备、无线开关/TLS发现、授权/Root重连、helper认证与owner清理；替换旧固定5555流程 | 离线覆盖端点变化、AUTH失败/恢复、2000→0、重启EOF、截止/取消、旧owner拒绝；L6依次读回ADB和helper UID，无需用户重复完成认证操作 |
| P1 自动运行与恢复 | Boot/包更新入口、持久状态、START_STICKY恢复、系统任务、独立恢复循环、权限差异回读 | 并发启动只建一代；空Intent恢复；关闭后迟到回调不重启；复车记录真实开机、App/helper单独退出与恢复时长 |
| P2 QNX闭合 | 内置原厂包定位/凭据解析、UID0连接/身份分层诊断、版本检查、幂等部署、只恢复自有receiver/HAB | 不导入文件即可取得授权并登录；QNX uname/UID、校验和、监听、READY/ACK、提交和物理可视分别记录；反复连接/退出可恢复原车显示 |
| P3 UI收敛 | 状态/自动运行/诊断；移除Activity连接owner及首页重复步骤 | 关闭界面仍正常工作；只打开诊断才分配界面资源 |
| P4 热路径优化 | 回调缓存、补读预算、索引与增量发布、按需线程/图形资源和绘制 | 全目录/各区域仍覆盖；补读/STALE正确；前后性能报告，静态/地图质量与原采集能力不缩减 |

每批完成本机相关逻辑/契约测试和 `assembleDebug`，不跑Lint；下一批可做离线准备，设备能力不能跨阶段冒称已验收。已有 `scripts/verify_auto_platform.sh` 是现行入口，实施时把新增验证纳入该脚本，首次准备/只读性能采样另设明确脚本；本文不把尚未创建的命令写成可运行交付。

P0/P1 不以 QNX 配置、地图连接或打开属性页作为启动前提；P2 失败按实际阶段报告，不能重置已就绪的基础层。当前请求交付的是经源码核对的整改方案，运行实现与实车结果后续按批次记录。

## 10. 本轮文档核验

2026-10-30，本轮新增方案的55处本地链接/行号均能解析，53份来源指纹与当前所读文件一致；`git diff --check -- AGENTS.md README.md docs/决策记录.md docs/项目规划.md`通过，新增方案/指纹与尚未跟踪的实施记录另核对无行尾空白。

`python3 scripts/project_checks.py` **未通过**，报“未登记的核心漂移: galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt”。该文件属于本轮开始前已有工作区内容，本轮未修改；不为通过文档检查而重登记代码摘要或覆盖其它改动。实施前需核对这项既有源码与补丁登记差异。本轮未运行Gradle、Lint或设备验证。
