# AutoService 整改实施与本机验证

后续用户提出HAB先直连优化，已进一步取消投屏对ADB/Root/QNX准备的串行门控，正常结束保留接收端有限监听。**最新构建为54项JVM回归，当前源码/新APK摘要见[直连复用优化](HAB直连复用优化-20261030.md)**；本文47项与APK表是前一整改构建的历史记录。

2026-10-30。用户已要求开始整改，依据[整改方案](AutoService整改方案-20261030.md)和随后确认的认证复用/自动取得方向实施。本轮修改运行源码、测试和本机脚本；未连接车机、未安装或进行车辆动作，不将本机通过写成实车通过。

本轮56项源码新增/修改/删除的前后摘要、验证时间与APK身份见[实施证据](../tools/evidence/autoservice-remediation-implementation-20261030.json)。参考仓库保持原样，本次未更换签名、提交或推送远端。

## 已落入的正常流程

### Root / ADB 与首次自动准备

- [`PlatformAccess.ensureRoot():55`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformAccess.kt:55)先认证本代自有helper；需要建立时恢复无线ADB，读取本机TLS动态端口，完成STLS/AUTH、请求Root并在新连接读回UID0，然后启动/认证helper。明确配置的旧su选项仍可读取；默认无人值守路径不发起新的su授权弹窗。
- [`AdbConnection:16`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AdbConnection.kt:16)使用星枢的STLS gate和KADB；授权失效且自有helper仍可用时登记本App公钥，有限重试并重新发现端口。不是固定连5555后睡眠700ms。
- 首次没有AutoService权限/公钥时，[`AndroidRootBootstrap`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AndroidRootBootstrap.kt:12)内置既有Android root调试入口的取得流程：连接**本机IPv6 loopback的TCP/23**，先独立验证`Linux`与UID0，仅在通过后启动自有helper。再自动登记本App公钥、配置/回读权限、恢复无线ADB并验证ADB UID0。只访问车机自己，不扫描车内其它主机。
- 此入口依据车机逆向[既有IPv6/TCP23 root记录](</Users/anjon/Documents/ChatGPT/车机逆向/docs/research/live-root-debug-2026-09-17.md:7>)；历史记录是电脑到车机的连通证据，**本轮没有验证普通APK到本机`::1:23`的实际可达性**。本地fixture已验证终端回显、身份先后顺序及非0拒绝，不能冒称本车首次自动初始化已验收。
- 没有复制星枢的真实私钥或让用户重新提供秘密。复用的是其认证/Root/连接实现；AutoService自动生成并登记自己集中管理的ADB身份。普通包的权限由程序适配和回读，正常流程没有手工配对、两行文件或逐条赋权步骤。

### Helper身份、恢复与停止

[`RootOwner`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootOwner.kt:8)、[`RootWire`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootWire.kt:11)及[`RootServiceMain`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:17)采用私有协议v2，沿星枢绑定App UID/PID/起始时间、内核peer、APK路径和helper实例。签名摘要挑战用于兼容校验，摘要本身不是秘密；权限边界还依赖内核peer与owner验证。新App进程不继承旧PID的helper。车辆通道复用连接；控制请求与HAB分开，连接与子进程有界，owner消失会关闭自身连接和HAB子进程。

[`AutoBootReceiver`](../galaxy/service/src/main/java/com/ecarx/autoservice/startup/AutoBootReceiver.kt:9)响应开机、包更新；[`AutoStartup`](../galaxy/service/src/main/java/com/ecarx/autoservice/startup/AutoStartup.kt:10)持久化自动运行选择并去重系统任务；[`AutoRecoveryJob`](../galaxy/service/src/main/java/com/ecarx/autoservice/startup/AutoRecoveryJob.kt:9)为目标Android11提供系统调度的恢复机会。常驻服务使用START_STICKY，读取原功能选择；正常维护60秒、失败30秒至5分钟退避，见[`FoundationRecovery`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/FoundationRecovery.kt:4)。这不是15分钟准时重启或强行停止后必定恢复的保证。

[`PlatformCoordinator`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformCoordinator.kt:9)独立维护基础连接，QNX按投屏需求准备。用户主动停止先持久化关闭并取消Job，失效旧回调、停止自有投屏与helper；快速重启等待前一代清理完成，避免旧清理杀掉新helper。停止独立仪表会清除主题自动恢复选择；普通异常退出保留恢复选择。旧的采样驱动`KeepAlivePolicy`已删除。

[`AppPermissionSetup`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AppPermissionSetup.kt:11)通过Binder先读权限、AppOps及电池白名单，只对缺项执行Shell并回读。健康轮次不运行Shell/dumpsys充当心跳。

### QNX认证自动取得与幂等部署

[`QnxOemCredentials.load():15`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxOemCredentials.kt:15)在UID0 helper中通过包管理信息定位本车原厂`com.flyme.auto.logreport` APK，核验已知来源SHA256后解析DEX登录数据。逻辑迁自已有[`authorized_qnx_credentials`](../tools/hab/telnet_session.py:76)，不执行样本的PCM/删除命令，不打包原厂APK。账号/口令只留Root进程内，日志和返回状态没有真实值；同版本使用内存缓存。手工文件仅作为诊断覆盖。

[`QnxReceiverDeployer`](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxReceiverDeployer.kt:10)自动使用取得的信息，在Android UID0中发起连接，另外验证远端QNX/UID0。相同版本、owner key和自有PID均匹配时复用receiver；需要部署才走已确认NFS桥，不在每次准备都复制/杀进程。Telnet控制命令置于子Shell内，命令中的`exit`不会提前结束控制会话而丢失返回码。监听、HAB握手、帧提交与最终可见仍是不同证据。

## UI、职责与资源开销

- [`ServiceActivity`](../galaxy/service/src/main/java/com/ecarx/autoservice/ServiceActivity.kt:12)只显示状态、自动运行和诊断入口。ADB/Root连接不再由Activity另建；Shell、分页车辆目录、车型和QNX覆盖移至[`DiagnosticActivity`](../galaxy/service/src/main/java/com/ecarx/autoservice/DiagnosticActivity.kt:12)。关闭页面只解除观察。
- 车辆采集迁到[`VehicleCollector`](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt:14)，每批只建一次字段索引；语义映射缓存，目录/各区域继续轮读，稳定快照合并到1秒、值/状态变化按采样批次立即发布。主动停止不再250ms空转。
- [`EcarxVehicleSource`](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt:30)实际消费OEM变化/错误回调，缓存带会话代次和提交顺序；新事件在读取期间到达时保留事件值，旧读取不能覆盖。缺少新事件时按时效补读，完整目录和区域不删减；反射Method缓存复用。
- [`VehicleInventory`](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleInventory.kt:9)改为属性/区域复合键，直接查找与增量计数，保留分页和长原始值分片。
- 回调、Shell和封面worker按首次任务创建；截止时间共用一个调度器。已排队且借入FD的任务仍完成finally后退出，不能为省线程丢掉资源释放。
- [`GalaxyHabFrames`](../galaxy/service/src/main/java/com/ecarx/autoservice/projection/GalaxyHabFrames.kt:4)按需分配最多三份像素缓冲，不再创建对象就分配全部约11.69MiB。合成仅在活动消费者存在时创建；未开始输出时保留首帧、排空其余Image而不持续复制。
- 运动画面仍按100ms提交；没有新帧时1秒维持一次现有HAB会话，低于QNX的5秒接收超时。不是删除心跳或降运动帧率。独立仪表无Surface时不调度绘制，播放进度需要时才保留100ms节拍。

这些是已实现、可由源码确认的开销收敛；本轮没有车机CPU/PSS实测，不能据此报告具体节省百分比或内存峰值达标。

## 可复现验证与产物

```sh
scripts/verify_auto_platform.sh          # JVM、HAB/native/配置工具回归与三个assembleDebug；不跑Lint
python3 -m unittest scripts.tests.test_autoservice_measure scripts.tests.test_project_checks
python3 scripts/project_checks.py
```

定向入口为`scripts/verify_autoservice_remediation.sh`，其`--compile`仅编译AutoService；不连接或安装设备。

结果时间 **2026-10-30T10:43:09.628343+08:00**，本轮最终源码与[验证报告](../.work/logs/validation-auto-platform.json)的摘要逐一一致：

| 检查 | 结果 |
| --- | --- |
| `scripts/verify_auto_platform.sh` | **47项JVM通过**（其中AutoService32项、AutoCluster1项），三个APK编译通过；15项HAB/配置工具回归通过 |
| Android native | 自有arm64静态库编译通过；11项native工具测试包含在上述HAB范围内，不重复累加 |
| 项目/采样脚本 | **28项Python测试通过**；源码登记/身份/源集检查通过 |
| APK身份 | 三包原包名、同一签名证书、HAB发送程序仅在AutoService，QNX资产与构建摘要一致 |
| 设备/性能 | 未连接、未安装；首次本机Root入口、真实开机/崩溃恢复、QNX自动登录、物理显示和CPU/PSS均未验收 |

| APK | SHA256 |
| --- | --- |
| [AutoService](../galaxy/service/build/outputs/apk/debug/service-debug.apk) | `e2f9207ca741508c3edac4e710160bfc1acd55e7c6cd577b8814671ba6fe3cb5` |
| [AutoCluster](../galaxy/cluster/build/outputs/apk/debug/cluster-debug.apk) | `a3b7d8f0f83e5ea5cc625853474dadf8f93104be9a92f44edadf162b03c7521f` |
| [CarPlay](../galaxy/mobile/build/outputs/apk/debug/mobile-debug.apk) | `efbbbf774af0a0ea926ce192c580326c3f5d2f9e3b76b4fb118fd737299bd011` |

QNX接收程序仍是此前已记录的重建ABI候选，本轮未改变C后端或声称取得正式SDP构建。原CarPlay音频/协议APK字节不变。

## 复车验收

复车安装顺序为AutoService、AutoCluster、CarPlay，使用`pm install -r`保留数据与签名。正常使用只开启自动运行，再由独立仪表/CarPlay产生投屏需求；不导入认证文件、不手点ADB/Root/QNX分步按钮。

逐项记录：本机`::1:23`实际可达/UID、首次自动登记、TLS/AUTH和ADB UID0、helper UID0、权限回读、真实开机、App/helper异常退出与新PID恢复、主动停止不复活、QNX来源解析/远端UID、完整地图和音乐、退出恢复。首次入口若目标固件实际不允许loopback，需要据本车监听/访问证据调整实现，不能把电脑到车机的IPv6证据当作已验证本机入口，也不让用户自行找密钥。

只读性能采样已固化为[`scripts/measure_autoservice.py`](../scripts/measure_autoservice.py)，只选择已连接的显式ADB设备，不自动连接新地址、安装或改变场景：

```sh
python3 scripts/measure_autoservice.py --serial <已连接设备> --scenario idle
# telemetry / static / map-media / stopped 分别在设置好对应场景后采样
```

默认预热60秒、采样5分钟，CPU以单核100%计；App、Root helper、自有HAB、AutoCluster分别报告及合计，记录CPU时间、PSS/RSS、线程和FD。缺失计数保持未知，不填0伪装低占用；报告不保存设备标识、凭据、车辆原始值或完整Shell输出。另用服务只读dump里的HAB计数对照新帧/邮箱替换；实车测量前不宣称CPU、内存优化已完成验收。
