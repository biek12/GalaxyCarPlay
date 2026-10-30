# AutoService、CarPlay与独立仪表：实施与复车

**后续整改已实施**：用户要求开始整改后，已替换Root/TLS/恢复与QNX取得链，精简界面并优化资源；最新源码、47项JVM回归、APK摘要与未测项见[整改实施记录](AutoService整改实施-20261030.md)。下文及“本轮仅更新方案”等表述属于早前轮次，不能用于判断最新APK。

**2026-10-30后续纠正**：用户要求优先参考星枢与车机逆向跑通Root/ADB，收敛UI、补开机/崩溃恢复并优化资源。源码复核确认：本页旧流程的固定5555、简单Root请求/短等待、只收命令返回码，以及投屏采样触发的KeepAlive不构成完整底座。[整改方案](AutoService整改方案-20261030.md)为当前执行顺序；下文是整改前源码/构建记录，尤其“打开页面逐个准备”的复车步骤不再作为目标正常使用方式。QNX部署在Android UID0 helper内执行的方向保留，实际连接/远端UID仍需分别验收。本轮仅更新方案，未构建或部署整改版本。

2026-10-30。用户授权建立service工程、迁出投射、完整采集可取得的车辆数据，并补齐KeepAlive、RootService、Shell、自动权限配置与QNX自有接收程序部署。当前车机已断开；本轮交付本机源码、回归、三个APK及配套程序，不把离线验证写成实车验收。

## 工程与运行职责

| 模块 / 目录 | 应用身份或职责 |
| --- | --- |
| `:service` / `galaxy/service` | **AutoService / com.ecarx.autoservice**，车辆源、Root/ADB、权限与保活、合成、唯一HAB输出owner |
| `:cluster` / `galaxy/cluster` | **AutoCluster / com.galaxy.autocluster**，独立主题/预览、音乐歌词、胎压、踏板页；名称暂定 |
| `:auto-contracts` / `galaxy/contracts` | AIDL v1，Surface、快照、车辆目录/分页、媒体发布、结果回调 |
| `:auto-client` / `galaxy/client` | 同一套客户端连接、代次、有限重连、死亡清理、媒体重放和封面只读FD |
| `:mobile` / `galaxy/mobile` | **CarPlay / com.ecarx.carplay**，保留官方核心、手机协议、音频/通话、来源6注册、方控；为仪表提供111完整地图及音乐数据 |

CarPlay的`GalaxyAutoServiceBinding`发布媒体/封面/歌词/导航状态并接收地图Surface；`GalaxyHostAdapter`保留中性核心接点。服务创建两个独占生产端：地图1920×536、主题1920×532。独立仪表绘透明主题；AutoService把地图放在主题下层，合成1920×532，ImageReader→三缓冲最新帧邮箱→自有HAB发送→QNX。没有将完整地图缩成TBT。HAB C源码与native回归已全部迁入service，CarPlay APK不再包含HAB发送程序。

独立主题依据星枢原图恢复速度环、状态栏、导航信息区、续航条、音乐/五行歌词，并增加车辆数据页；未知/过期读数显示`--`。没有把原图中的109km/h等示例放进产品，没有复制参考App的SO/SDK或加固壳。当前主题是首版实现，精确布局和真实地图交叠效果仍需复车调整。AutoDesktop和AI的界面属于后续工作，本轮提供它们将来使用的基础契约。

## 车辆数据应读尽读

`EcarxVehicleSource.kt:25`沿目标`ecarxcar_service → IECarXCar → getCarService("car_signal") → IECarXCarProperty`接入；读取`getPropertyList()`及本车SDK的`SigPropertyIdString.sigIdToStringMap`，合并已核验FS12/FX12映射。不是扫描猜测的整数ID范围，也不以72项为采集上限。

- 运行时目录提供access/area/type时按其区域读取；不可读项保留REJECTED，绝不调用setProperty。
- 目录为空的情况已由星枢本车记录证实，固件符号声明与车型映射是明确来源，不将它们标成“实车已支持”。每项实际getProperty结果分别记录。
- 每轮更新72项语义字段并推进32个通用属性；读取串行在worker，完成后250ms再采。所有区域分别保存，原始类型和值不丢给仪表去猜。
- `getVehicleCatalog/getVehicleProperties(offset,limit)`每页最多32项，长值可`getVehicleValue(propertyId,areaId,characterOffset)`分段读取完整内容；不将所有原始属性塞进高频Binder快照。
- 标准字段保留单位、属性ID、来源、独立接收时钟、会话代次和VALID/UNAVAILABLE/STALE/REJECTED。部分已知全局信号area1→0的归一化沿星枢白名单，其余不猜。

[72项语义映射及车型差异](AutoService车辆属性目录.md)；[外部参考指纹](../tools/evidence/auto-platform-sources.json)。全目录实际多少项、哪些成功，必须以本车返回为准，不能在离线环境宣称“全部ECU数据已取得”。原始值只在内存与授权界面/IPC中使用，不写诊断日志。

## KeepAlive、RootService与Shell

`AutoService`为前台服务，使用START_STICKY；客户端绑定死亡/更新后有限重连，Root掉线恢复由`KeepAlivePolicy`限次控制。自动配置仅针对本App：WRITE_SECURE_SETTINGS、READ_LOGS、后台AppOps及deviceidle白名单，逐项回报命令返回码。未获得系统Root时不能伪称“自行变成Root”，也不把普通appId当原厂签名。

自有`platform/RootServiceMain.kt`通过已配置ADB Root或su启动，socket校验客户端UID，客户端反校验UID0。车辆、Shell和HAB使用独立操作码；连接并发有界。RootService拥有打开HAB设备的自有native子进程；不会为了设备访问把原车设备节点chmod成全局可写。App退出或空闲后清理自有助手，不更换原车进程。

`executeShell`支持ROOT/ADB，命令≤8192字符、超时100–30000ms、输出≤64KiB，返回exitCode/timedOut/truncated。IPC同时要求同签名CLIENT和ADMIN权限、有效owner租约；外部App只凭包名不能执行Root。Shell命令和输出不进日志；AutoService页面有本地命令入口。今后消费者必须明确申请ADMIN，而不是读取数据就自动取得Shell。

参考AutoService0.1.0中的`libSecShell.so/libSecShell-x86.so`与清单`com.SecShell.SecShell.AW/AP/CP`对应，是加固壳材料，不是已证明可调用的Shell SDK；本工程不复制它们。

## QNX自动部署与生命周期

`PlatformCoordinator → RootServiceClient → QnxReceiverDeployer`按下列顺序准备，再开放Android HAB会话：

1. 检查/启动所选Root通道与自有RootService；按明确配置的ADB地址检查UID，必要时请求adbd root后重连，不扫描车内网络。
2. 配置本App权限与保活，逐项保留返回码。
3. 从本APK资产取自有QNX候选，经已确认的Android `/data/vendor/nfs/nfs_ota` → QNX `/otaupdate`桥传输。目录随机命名，临时认证文件仅owner可读。
4. 登录内网QNX `192.168.118.2`，要求独立`uname -s=QNX`、`id -u=0`结果；复制到自有`/var/data/autoservice-hab`，核对POSIX cksum、self-test、display ID2/1920×532。
5. 只在PID与本服务程序路径匹配时停止旧自有receiver，启动新版本`serve ... 2 1920 532 4`。子进程忽略控制终端HUP，TERM用于正常释放。输出状态区分receiver_listening、HAB READY/ACK、submitted frames；监听不等于物理屏可见。
6. 客户端退出先停Android输出，再TERM自有接收端并释放窗口/PMEM/VC。Receiver的有限会话恢复保留；不改原车z-order、HMI或启动项。

整改前实现：QNX登录信息由外部两行文件或电脑端`tools/hab/telnet_session.py`的`configure_autoservice_qnx_from`读取已授权OEM样本后配置到App私有noBackup，没有配置时报qnx_credentials_missing。**用户后续已纠正该正常使用方式**：星枢认证可复用，QNX授权方案/取得方法应内置，不能要求用户找口令、反复导入。当前目标改为UID0 helper自动定位本车 `com.flyme.auto.logreport` APK并按已有解析方法取得登录信息，直接登录；手动导入只留诊断覆盖。个人构建内置授权材料也在用户此次授权范围，不在公开源码/日志/诊断中泄露真实值。详见[整改方案5.1](AutoService整改方案-20261030.md#51-凭据取得方法内置取消正常流程的手工导入)；App内解析尚未实施，不把现有电脑工具算作已经自动完成。

本机仍没有QNX SDP。包内QNX程序为**重建ABI的aarch64le候选**，不是SDP/qcc正式产物；源代码/符号/导入核验齐备。构建拒绝把与当前C源码摘要不一致的旧候选塞进APK。

## 构建、回归与产物

```sh
scripts/build_apk_fast.sh        # 仅三个assembleDebug，不跑Lint/单测
scripts/verify_auto_platform.sh  # 契约/车辆/生命周期/Shell/主题/native回归及三个APK，不跑Lint
python3 scripts/project_checks.py
```

新增KADB2.1.1及其声明的公开依赖；首次依赖预热可使用相同Gradle任务去掉`--offline`，此后离线运行。Robolectric仍通过仓库内init配置，未放进APK。QNX候选重建：

```sh
python3 scripts/build_hab_probes.py --qnx-rootfs /absolute/path/to/captured/rootfs
# 修改C源码后也可设置GALAXY_QNX_ROOTFS，让service资产任务重新构建候选
python3 scripts/prepare_vehicle_test.py --qnx-rootfs /absolute/path/to/captured/rootfs
```

APK在`galaxy/{service,cluster,mobile}/build/outputs/apk/debug/`。测试包由上述脚本统一包含三个APK、QNX/Android诊断程序、符号、自有源码、日志/摘要和公开诊断fixture；不包含OEM库、登录材料或本App真实owner key。`validation-auto-platform.json`记录当前APK摘要、公共签名证书摘要和每项测试，核对HAB仅在AutoService、QNX资产与报告一致。结果不冒充已安装。

## 复车操作

1. 安装顺序AutoService → AutoCluster → CarPlay，均`pm install -r`保留数据和现有签名。三个APK本机证书一致；不卸载CarPlay。
2. 打开AutoService，确认L6和ADB端点（默认本机127.0.0.1:5555；若选择su则保存为SU）。沿上面的既有外部来源配置QNX登录，点击准备；检查Root/权限/receiver阶段。
3. 启动AutoCluster，再连接CarPlay。检查完整地图在主题下、音乐封面/进度/歌词和真实车辆字段；全部属性按钮分页查看声明数与实际成功状态。不要以0当未知读数。
4. 重复连接/断开、前后台、停止/重新启动仪表；检查服务代次、receiver ACK与持续帧。停止后原车画面恢复，且没有第二个HAB owner。旧CarPlay自有receiver若仍运行，先按旧包stop脚本核对其PID后退出，不能killall。
5. 分别验证电话/微信语音上行（此前修复保留）；本轮投射拆分不改变原车音频来源6或电话路由。

目前未进行新三App的指定固件实车验收、自动权限实授、Root设备权限、QNX自动登录/部署及Surface跨进程物理显示验证。已通过的离线结果附后，复车后另写真实结果。

## 本机验证结果

结果时间：2026-10-30T02:13:16.349797+08:00。

| 验证 | 结果 |
| --- | --- |
| `scripts/verify_auto_platform.sh` | 三个assembleDebug通过；35项JVM回归与15项HAB/配置工具回归通过；未跑Lint |
| 最后补充 `:service:testDebugUnitTest`（同仓库init脚本） | 原厂Binder缺失时72字段均UNAVAILABLE且无伪造0；与原结果合计**35项JVM测试通过** |
| `python3 -m unittest scripts.tests.test_project_checks` | 26项通过 |
| `python3 scripts/project_checks.py` | 锁定上游与新增模块登记、身份、源集、无凭据边界通过 |
| `scripts/build_apk_fast.sh` | 最后封装前再次assembleDebug通过；包名、同证书和HAB只归AutoService已核验 |
| QNX候选与APK资产 | 字节一致，POSIX CRC算法合同与重建ABI导入核验通过；没有SDP/实车验收 |

- `com.ecarx.autoservice` APK SHA256：`053edd76a80cd376d992f89f059e3daab42555560b820f62cf460a29dbf3b773`。
- `com.galaxy.autocluster` APK SHA256：`a9096fcd3962ec87060f74f0efc2dedfe718614b1d8307e94ac9f9645f5eff77`。
- `com.ecarx.carplay` APK SHA256：`efbbbf774af0a0ea926ce192c580326c3f5d2f9e3b76b4fb118fd737299bd011`。
