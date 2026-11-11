# AutoService 对标差异与优化设计

日期：2026-11-10。状态：**源码与固件静态核验、待其他 Agents 编码，未实施本方案**。

本轮按用户要求，参考[AutoFamily 功能与性能核验](AutoFamily-AutoRecord与AutoService功能及性能核验-20261110.md)，比较同功能的实现优劣，补齐我方缺项，并提供[编码任务书与审计清单](AutoService优化编码任务书与审计清单-20261110.md)。本轮只写文档及本地证据摘要，不修改业务代码、不构建 APK、不启动模拟器、不连接车辆、不部署服务器。

## 1. 基线与结论

| 对象 | 本轮固定依据 | 能证明什么 |
| --- | --- | --- |
| GalaxyPlay | Git `eb756396d714b0f386ce318956129f7433004c52`；`galaxy/service`、公共契约/客户端及相关消费者 | 当前源码行为。此前其他轮次的实测不代验本方案 |
| AutoFamily AutoService | 1.4.1 / 41，APK SHA-256 `362f8f7c62ada2c895dc495e4b70b817db67130dd02d65e04dc784073d78d357`；本地恢复 DEX 的 JADX 副本 | 参考实现的可见调用链；反编译伪代码存在恢复误差，关键异常分支需再核 DEX |
| 本车 QNX 固件 | 星枢本地 `g733-offline-environment-20261025`：完整分区镜像、提取 rootfs、清单及采集证据 | **本车真实固件证据**，可继续还原本车原厂订阅接口、消息结构、字段与生命周期 |
| 参考样本在线采集载荷 | 未取得 | 仅限制断言其私有采集器内部实现，不限制依据本车固件自有实现 |

本地镜像说明记录 QNX 7.0.X / AArch64、固件 2026.24.1、Kanzi 3.6.9；Android 首验标识仍为银河 L6 2023 Max / FlymeAuto 2.3.1，两组版本字段分别记录，不互相替换。[Q01](#8-源码与固件索引)

**推荐保留我方按需采集、L6 参数、Root/ADB 生命周期和多应用分工；借鉴对方类型化缓存、订阅索引及客户端隔离；重新设计事件历史和过载处理；依本车镜像落实 QNX 只读采集。** 不能得出任何一方整套性能更优的结论：目前没有同车、同负载的双方性能实测。

## 2. 同功能逐项比较与取舍

表内“更优”是对应目标下的架构判断，不是实测跑分。G 为我方源码，A 为样本，Q 为本车镜像；精确位置见文末。

| 功能 | 我方实际实现 | 参考样本实际实现 | 取舍与理由 |
| --- | --- | --- | --- |
| 按需采集 | 按租约合并键与最小间隔，到期键才读；100–10000ms；无人需求不排下一拍 | Binder 按属性订阅，没有周期参数；VHAL/ECARX 底层按车型可读表启动注册 | **保留我方**，符合无人订阅不采集的既有契约。但我方空闲时未完全注销源，见 F01。[G01](#8-源码与固件索引)[G02](#8-源码与固件索引)[A01](#8-源码与固件索引)[A02](#8-源码与固件索引) |
| ECARX 事件缓存 | 已有厂商回调、最新值缓存、epoch、事件/补读竞态保护；到期键仍主动补读 | 注册回调、类型转换、统一缓存；特定 KX11_21 分支另有轮询 | **保留我方车型链，借鉴统一层**。不能把“改成事件缓存”写成从零新增；也不把其他车型轮询配置套给 L6。[G03](#8-源码与固件索引)[G04](#8-源码与固件索引)[A02](#8-源码与固件索引) |
| VHAL | L6 踏板/转角使用 UID0 helper 内同步 get/config，核对 area、状态、类型、范围、源时间 | HIDL 事件订阅、信号索引及批量去重，初始缓存逐项 get | **事件能力参考对方，身份/解码保留我方**。后续按本车配置 changeMode/rate 核验订阅，不抄全表常驻和 sampleRate=0；先做不变语义优化，再单列事件模式。[G05](#8-源码与固件索引)[A01](#8-源码与固件索引) |
| 缓存读取 | `getVehicleValues` 纯缓存、按键深拷贝；外部值是字符串 Bundle | `CarPropertyValue` 类型化 Parcelable，读直接返回缓存副本 | **内部类型化参考对方，对外先保留 v4**。我方单调时钟与新鲜度合同更适合伴录，不能一并替换。[G06](#8-源码与固件索引)[G07](#8-源码与固件索引)[A03](#8-源码与固件索引) |
| 变化去重 | 每拍为全部 wanted 构造 JSON/Bundle，再按每租约周期发完整子集；没有值变化去重 | 按 ID/状态/类型/值去重，有订阅才分发 | **借鉴对方的内部 revision/dirty 索引**。v4 仍按约定节拍发送完整订阅子集；不能直接只发变化值造成消费者失配。[G08](#8-源码与固件索引)[G09](#8-源码与固件索引)[A04](#8-源码与固件索引) |
| 慢客户端 | 一条共享 callbacks worker；feed 每包 post；IAutoCallback 为 oneway；无显式容量限制 | 每 callback 独立线程、2048 FIFO、最多32，失败/满队列撤销订阅；其回调为同步 transact | **对方隔离职责更完整，我方异步 IPC 有优势**。采用每租约有界分发，区别状态与事件；不照抄大 FIFO 或把 oneway 当无限吞吐保证。[G10](#8-源码与固件索引)[G11](#8-源码与固件索引)[A05](#8-源码与固件索引)[A06](#8-源码与固件索引) |
| 退订/死亡/迟到 | owner death 清理与身份检查已有；已排 feed 没有重新验证租约或订阅代次 | death 清理、退订令已排任务失去资格 | **借鉴对方投递前资格检查**，保留我方 role/UID 约束。[G10](#8-源码与固件索引)[G12](#8-源码与固件索引)[A05](#8-源码与固件索引) |
| 采集→主线程交付 | `main.post { publishFeed(values, at) }` 不带采集代次 | QNX 队列 reset 递增代次，应用端校验 | **借鉴对方跨线程全程代次**，见 F02；不能仅依赖客户端连接 generation。[G13](#8-源码与固件索引)[A07](#8-源码与固件索引) |
| 类型/无效值 | VHAL 空向量不补0，未知/拒绝/过期有状态，ECARX 补读不覆盖更新事件 | 支持更多基础类型；部分 VHAL 标量空向量转换默认0 | **保留我方有效性规则**，只学类型模型，不复制默认0。[G04](#8-源码与固件索引)[G05](#8-源码与固件索引)[G07](#8-源码与固件索引)[A08](#8-源码与固件索引) |
| 原始目录/扫描 | 固件声明＋运行时目录＋L6/L7映射，显式32项/250ms诊断扫描；原始值分页 | 产品资源主属性90、QNX109；按车型映射建立支持表 | **双层目录比二选一更好**：保留我方完整探索，补静态能力元数据与缺少的语义字段；不能拿已采行数当全部支持数。[G14](#8-源码与固件索引)[A09](#8-源码与固件索引) |
| 启动/恢复 | automatic、独立功能开关、持久 Job、进程恢复、代次/取消、自有 helper owner | 前台服务、开机/换包、心跳、授权门控与常驻检查 | **保留我方产品语义**，不加20秒心跳或第二套轮询“保活”。对方商业授权不是我方需求。[G15](#8-源码与固件索引)[G16](#8-源码与固件索引)[A10](#8-源码与固件索引) |
| QNX 生命周期 | 已有投屏 receiver 的检查、匹配复用、按需唤起、所有权及释放 | 有数据采集 loader、实例检查/车型核验、SSE 接收恢复 | **职责分开再共用基础设施**：receiver 是画面端，数据采集器是另一任务，不能把 qnxReady 当数据 ready。[G17](#8-源码与固件索引)[A07](#8-源码与固件索引)[A11](#8-源码与固件索引) |
| QNX 车况 | 车辆目录源中未定位 QNX 数据桥；现有 QNX 主要服务 HAB 画面 | 109个只读定义，完整快照/revision/车型校验，接收与应用分线程，队列16 | **补我方能力，以本车固件为准**。借鉴快照/代次/校验，底层从原厂 MsgProxy 订阅链实现，不依赖其私有 AQNX3 包。[Q02](#8-源码与固件索引)[Q03](#8-源码与固件索引)[A07](#8-源码与固件索引)[A09](#8-源码与固件索引) |
| 权限/车控 | 同签名 Binder、owner/role、CAMERA/悬浮有限代授权；车辆源只读 | 包名＋证书，读写分权及24个可写定义 | **保留我方范围**。补更多只读字段不等于增加 setProperty、车窗/空调等控制接口。[G18](#8-源码与固件索引)[A12](#8-源码与固件索引) |
| 相机能力 | AutoRecord 自采；AutoService 有代授权，但 service/contracts/client 未定位 cameraId 探测 API | 本版本未定位独立相机扫描/代授权 API | **这是我方既有要求的缺项，不是参考样本已做功能**。补按需能力探测，画面不绕 AutoService；探测需与录像会话协调。[G18](#8-源码与固件索引)[G19](#8-源码与固件索引) |
| 远程/网页/诊断 | 独立远程会话、公共车辆绑定、媒体 Range、自动错误上报 | 该 AutoService 样本重点是车况/授权/QNX；未定位对应直播中继和通用 Root 服务 | **保留我方能力**。先减少关闭功能的空闲资源，不因样本没有而删除；WebRTC 视频仍由 AutoRecord 编码。[G20](#8-源码与固件索引)[G21](#8-源码与固件索引) |
| 仪表合成 | 独立多应用、Surface/EGL、HAB、3缓冲最新帧邮箱及按内容调度 | 该 AutoService APK 未定位对应完整画面输出 | **保留我方**。不是把 AutoDisplay 的画面逻辑误算成此 AutoService 的优势；本轮不凭静态比较改整个图形后端。[G22](#8-源码与固件索引) |

## 3. 现有链路优先整改

### F01 / P1：无人订阅时只停轮询，源注册和线程仍可能保留

`setDemand(empty)` 进入 `idle()` 仅取消 PollSchedule；没有 `source.close()`，也不向 helper 发 op8。厂商注销在 `EcarxVehicleSource.close()`；helper 因长期 vehicle socket 尚在 peers 中，也不能仅靠空闲退出收口。`VehicleCollector` 的线程只在 close 时退出。[G01](#8-源码与固件索引)[G03](#8-源码与固件索引)[G23](#8-源码与固件索引)

这能确认“没有继续主动 get”，**不能确认底层回调、厂商订阅和线程已释放**。即使仍需原厂菜单事件，也应把它列为明确的内部事件需求，不得暗中保留全套车辆注册。

设计：采集 worker 串行执行需求转换；区分外部车辆需求、哨兵需求、显式扫描和菜单事件需求。最后一个需求退出，先撤销对应源注册，再关闭车辆专用连接，取消定时任务；只有需要菜单时保留其独立事件订阅。保留租约工作意图与 Root 的其他功能。重新有需求时先重建源，再读首批。线程回收必须防止旧退出任务清掉新 worker；“不空转”优先于盲目增删线程。

### F02 / P1：采集代次没有贯穿交付与分发

采集器 poll 内检查 generation，但交给 AutoService 后只排普通 `main.post`。如果 A 批已入主队列，期间执行停止→重启或切车型，A 的闭包在恢复后可能通过 paused/features 检查，把旧车/旧源数据写回 `signals`。已排 `sendFeed` 也不核对退订、换键或 detach 后的旧 lease。[G13](#8-源码与固件索引)[G08](#8-源码与固件索引)[G10](#8-源码与固件索引)

这是有明确交错条件的静态风险，本轮未做运行复现。设计：批次携带 service/collector generation、model、source session；主线程接收时校验；每租约有 subscription generation，投递前再核对 identity/generation/键集。stop、切源、切车型、关闭车辆功能均使旧批失效。原有客户端连接 generation 继续保留，不能替代服务端检查。

### F03 / P1：共享回调队列没有显式边界

`SerialWorker.post` 直接送 Handler；`sendFeed` 每次创建深拷贝 Bundle 并排队，所有车辆、快照、Surface通知及部分操作结果共享 callbacks。snapshot notify 做了合并，feed 没有。慢本地 callback、Binder 提交压力或队列拥塞可能放大延迟；本轮没有证明已发生 ANR 或内存泄漏。[G09](#8-源码与固件索引)[G10](#8-源码与固件索引)[G11](#8-源码与固件索引)

设计：每活动租约一个惰性分发 lane，数量沿用现有最多16租约，空闲可退出。状态快照使用“最新待发1份”；v4车辆数据同样只保留最新**完整订阅子集**，并记录合并数。控制结果/Surface生命周期保持有序且明确所有权，不能被状态替换。一个 lane 内不得持锁调用远程 Binder。故障撤销只影响该租约，不能终止采集。不能声称该队列也限制了 Binder 内核缓冲；后续事件可靠交付需要额外流控合同。

对方 per-client 职责可借鉴，但2048积压会送很旧的数据；对方同步回调与我方 oneway 不可按“线程数”简单比较。[A05](#8-源码与固件索引)[A06](#8-源码与固件索引)

### F04 / P1：普通信号最新值不等于完整事件历史

ECARX `samples`/`dirty` 每个 `(id,area)` 保存最新 Sample；只有按键事件另有128条队列。即使厂商在两拍之间发出“松→踩→松”，普通信号可能只留下最后一份。AutoRecord 自身512条伴录队列位于收到 feed 之后，不能恢复服务端已覆盖的变化。L6 踏板当前走 VHAL get，100ms get 之间的物理变化也不能事后凭空恢复。[G03](#8-源码与固件索引)[G04](#8-源码与固件索引)[G24](#8-源码与固件索引)

设计分两层：v4继续做最新状态快照；新增有明确声明的事件历史通路，记录**实际收到**的源事件，不以缓存重复采样伪造事件。按 demand 记录，包含单调时间、source时间/时钟域、sourceSession、sequence、status、property/area、类型和值。事件批量发送必须保留顺序、gap及过载计数，并让 AutoRecord 按事件时刻对齐视频。该项涉及 contracts/client/record，作为独立协议批次，不混入纯 v4 内部优化。

先明确 VHAL 是否支持本车这些属性的事件订阅和更新率；镜像/配置可完成静态合同，实际传感器更新率仍另测。不能承诺捕捉所有短踩，也不能把100ms请求写成100ms端到端或力度量纲验收。

### F05 / P2：索引、物理键去重和内部类型化

`cycle` 对 due 键反复 `specs.firstOrNull`；构建别名时再遍历 specs；同物理源的多个语义键可重复 read。每拍链条是 Sample→JSONObject/JSONArray→Root JSON帧→JSON解析→VehicleSignal→Bundle→每租约深拷贝，app直连也走 JSON 中转。[G04](#8-源码与固件索引)[G08](#8-源码与固件索引)[G23](#8-源码与固件索引)

设计：连接/车型变化时建立 `specByKey`、`semantic→(backend,id,area,converter)`、反向物理键索引及注册集合。先对到期语义键解析，再按物理键只读一次、派生多语义结果；保留不同 area/backend。用不可变内部 Sample/Batch，数值/字节类型不先转字符串；仅外部 v4 边界格式化。第一批保留 RootWire v2 JSON framing，先消除 app模式中转及重复转换；更换私有线格式必须另升 RootWire 版本并验证旧 helper 不被错误复用。

不得用“一律事件取代到期 get”来改变已确认采集周期。ECARX 缓存读取只能证明本次确认时间，不能证明底层传感器重新采样；新增 `readCompletedAt` 等内部字段时与 sourceTimestamp 分开，未知源时间保持未知。[G04](#8-源码与固件索引)[G05](#8-源码与固件索引)

### F06 / P2：需求更新不应重置所有键

`VehicleDemand.assign` 已保留旧 readAt，但 `VehicleCollector.setDemand` 只要 next非空就 `demand.reset()`。同一订阅重复提交、其他租约变化会让全体键再次立即到期。[G01](#8-源码与固件索引)[G02](#8-源码与固件索引)

设计：相同合并需求直接返回；新增键立即读，缩短周期按现有 readAt 判断，变慢保留上次采集时刻；换源/重启才整代 reset。即时补发缓存与“再读一次硬件”分开。扫描有独立预算，不因每次100ms订阅更新重新扫描。

### F07 / P2：静态支持目录与采样结果应分离

`VehicleInventory` rows 由已读取 properties 填充；`getVehicleCatalog` 同样对 rows分页。因此尚未订阅/尚未扫到的属性不在该分页结果；它不能单独代表全部运行时支持目录。`value()`直接返回保存的status，`page()`会重新判STALE，两条读口的新鲜度处理不一致。[G14](#8-源码与固件索引)

设计：目录保存固件声明、运行时支持、车型映射、backend、area、类型与只读访问属性，不为建目录主动读全量值；值缓存单独保存。三类来源和可读实测状态分别呈现。抽公共 freshness判定供原始分页、单值及语义值使用；validCount也明确是“最近一次读为VALID”还是“当前新鲜有效”。完整值仍可分块读取，不删扫描能力。

### F08 / P2：未开启网页功能仍创建常驻资源

`updateRemote` 对 webEnabled 的条件只有 !closed&&!paused，未检查 vehicle-web.enabled；VehicleWebBridge 构造立即启动两个 HandlerThread，start每10秒调度tick，即使网页设置关闭，tick仍定时检查。Range worker虽按需启动，但不改变上述事实。[G20](#8-源码与固件索引)[G21](#8-源码与固件索引)

设计：网页功能仅状态查询不创建网络运行时；enable/实际请求时创建，disable时关闭自有HTTP、线程、FD和Range，旧代响应失效。读取本地绑定状态不要求联网；永久归属、临时码、远程12/24h窗口继续分开。此项只改资源生命周期，不替换WebRTC、不删除网页能力。

## 4. 缺项：以本车镜像推进 QNX 数据源

### 4.1 已有固件事实

根目录：`/Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/g733-offline-environment-20261025/`。原镜像保持只读；后续工具输出写 GalaxyPlay 的忽略目录 `.local/research/`。本轮重新计算以下指纹：

| 文件（相对 rootfs） | SHA-256 |
| --- | --- |
| `apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi` | `385d8fc526936a32fa08cf8dfd51db731a58c8a3c86fd43709a6269bdf080e64` |
| 同目录 `ClusterDataSource.xml` | `9a38b5a5332be8424bf5f754354dc03c055e31a6fd4cb27cfb5301bd736cc682` |
| `apps/cluster/FX11_HEV/lib/libMidProxy.so` | 见配套指纹清单 |
| `usr/lib64/libascdatanotify.so.1` | `785f75e068c264971615793efbea64dde26e6399749550fc679ea6b6cfdd721b` |

本轮直接用仓库NDK的llvm-nm/llvm-objdump核对，而非仅重复旧报告：

| 位置 | 已确认事实 | 仍需继续还原的具体内容 |
| --- | --- | --- |
| HMI `FunctionGaugeInfoManager::init` `0x134ad0–0x134b8c` | `MsgProxy::Connect`，依次 `RegNotify` 消息1/3/7/8/5/10/9，最后 `StartNotify` | 本轮已用构造器＋GOT重定位＋vtable闭合消息对应，见下表；payload长度/状态仍沿Notify及生产端核验 |
| HMI `FunctionGaugeInfoManager::updateMessage` `0x1345c0`；Warning init/update `0x1354c0/0x1354c8` | 有独立仪表值和告警管理入口 | 拷贝长度、布局、availability、类型、缩放及注册/注销路径 |
| HMI `LogicAdapter::SelfLaneNotify::NotifyHandle` `0x151098` | 消费 `ADAS_PA_DANSTNAVAI`，经LogicManager的DataProxyListener比较并写入属性 | ABI及字段来源继续沿原厂实现追踪；这是原厂实现，不等于参考样本私有采集器同样实现 |
| `libMidProxy.so` GaugeProxy构造 `0x289a70`；WarningProxy `0x28f680`；TelltaleProxy构造 `0x28f758` | 本车Proxy及消息基础设施真实存在 | Connect服务名、传输地址、回调/析构、是否启动时附带写命令，逐个确认 |
| `ClusterDataSource.xml:26–51` | Gauge速度/有效性/RPM/油量/水温/功率值、单位及可用标志分别声明 | XML默认0不是实测；功率字符串、百分比与实际kW不能混同 |
| 同XML `157–180`、`367–387` | 门/安全带/告警、SelfLane、巡航目标速度/状态及限速字段声明 | 与消息映射、枚举及原厂更新条件一一闭合 |

构造器 `0x1352c0` 的成员赋值、GOT重定位与vtable名称已交叉核验，七项不是按注册顺序推测：

| 消息ID | 回调对象 | 管理器内成员偏移 |
| --- | --- | --- |
| 1 | GaugeSpeedNotify | `+0x158` |
| 3 | GaugeRPMNotify | `+0x168` |
| 7 | GaugeFuelNotify | `+0x178` |
| 8 | GaugeFuelWarnLampNotify | `+0x198` |
| 5 | GaugeWaterTempNotify | `+0x188` |
| 10 | EtcPowerMeterNotify | `+0x1a8` |
| 9 | EtcBatLevel | `+0x1b8` |

[重定位与消息映射证据](../.local/research/autoservice-optimization-20261110/gauge-message-map.txt) · [构造器](../.local/research/autoservice-optimization-20261110/gauge-constructor.txt) · [车速处理](../.local/research/autoservice-optimization-20261110/gauge-speed.txt) · [析构](../.local/research/autoservice-optimization-20261110/gauge-destroy.txt)。车速处理 `0x134d08–0x134d18` 可见payload偏移0/4/8/12的读取，并分别写速度、文本、有效性、单位等DataProxy字段；这能约束解码结构，仍不能仅凭这些load证明消息完整尺寸或全部枚举。析构 `0x135390` 进入GaugeProxy析构，必须继续确认Proxy注销行为，不能假定存在名字相似的Unregister函数。

证据落点见[Q02](#8-源码与固件索引)[Q03](#8-源码与固件索引)[Q04](#8-源码与固件索引)及本轮 `.local/research/autoservice-optimization-20261110/`。本车镜像已足以让 Agent 继续落实上述链路；**不得把“第三方在线loader后面的数据包未知”写成“没有本车固件”或停止研究的理由**。

### 4.2 推荐接入结构与优先顺序

```text
本车 QNX 原厂消息服务
  → 自有只读订阅适配器（优先独立进程，按原厂 MsgProxy/消息合同）
  → 自有有界状态缓存 / 有序事件缓存
  → 版本化数据通道
  → AutoService QnxVehicleSource
  → 与ECARX/VHAL共用类型化缓存、目录、订阅与客户端分发
```

优先独立读订阅，尽量不依赖原厂HMI内存布局、UI帧或Kanzi节点。确认原厂Proxy是否会发送控制/确认/自检后，再提取只读必要子集；不能为了取灯态直接调用 `TelltaleProxy::SelfCheck`、`SetCAreaStatus` 或SafetyApps写回。若某字段只存在HMI内部，则继续从镜像还原其上游消息，不能直接把屏幕抓图或改节点当数据采集。确需原厂进程内只读接点时，单独列出理由、调用所有权和恢复方案；本次不授权实际注入/重启HMI。

生命周期共用AutoService的Root认证、取消和部署所有权，数据collector与HAB receiver独立状态。仅有QNX来源订阅或显式诊断才启动数据任务，无人需求注销/退出；控制连接与数据流分离。每字段配置固定来源和固件合同，禁止“谁有值用谁”的静默回退；保留 `qnx.*` 命名空间，不覆盖已核验的 `displaySpeed`、driveMode等原字段。

传输可借鉴样本的SSE，**SSE不是已确认最优或原厂协议**。先核我方既有连接能否承担数据，比较长连接二进制帧与HTTP/SSE的实现/解析/恢复成本，选一种自有版本化通道；公共Binder消费者不感知QNX传输细节。若选SSE，需明确完整快照/增量、revision、profile、epoch、heartbeat、最大行/帧长度和断流失效。完整快照允许最新覆盖，事件不可用“删最旧快照”代替交付保证。

首批字段按 **水温/有效性、实际功率及其单位/有效性、巡航目标/状态、门/安全带、原厂灯态** 追到生产者。原厂XML、处理函数、payload、缩放、状态到公共键必须一一有证据。原厂名带Power不表示已知正负kW，Gauge显示等级也不直接等于物理单位。

## 5. 字段差异：不能按目录数量判定能力

本轮静态计数：我方 `VehicleCatalog` 91个显式SignalSpec、78个显式normalizedId；另有5个旧公共别名。参考FS12资源89个readSignalId、24个writeSignalId。对normalizedId集合做交集为71，对方有18个ID未在我方显式normalizedId中出现。**这是定义集合比较，不是实车覆盖率**；我方运行时原始目录仍可能包含这些底层信号。[G25](#8-源码与固件索引)[A09](#8-源码与固件索引)

| 对方ID | 缺少的显式语义项 | 建议 |
| --- | --- | --- |
| 10063 | 电机功率 | 本车QNX Gauge与Android VHAL两端核验单位/正负/availability；我方已有electricPowerRaw等不同字段，不算完全没有功率 |
| 10064、10065 | 50km/本次平均油耗 | 固件映射及缩放核验后加只读键，不混入已有100km油耗 |
| 10066 | 驾驶模式值 | 我方已有独立且已确认的PA driveMode，先比枚举/来源，不覆盖为样本padrivemodevalue |
| 30012、30013 | 天窗/遮阳帘位置 | 仅补只读状态，保留原始枚举和量程 |
| 30014–30017 | 主副驾温度、循环、风速 | 保留设定档位与真实温度区别，不添加写接口 |
| 30018–30021 | 后视镜折叠设置、吹风模式、前后除霜 | 我方已有部分镜状态，先分清状态与设置，不造重复别名 |
| 30022–30025 | 左右座椅通风/加热设置 | 只读；按原厂availability、area与级别确认 |

参考样本109个QNX属性含原始快照/分组/65灯态，并非109个互不重叠传感器；我方原始目录计数也不能与其产品属性数相除。逐字段表及来源指纹见配套JSON。

## 6. 分期与全局约束

1. **阶段A：现有链路正确性。** F01/F02/F03，保持公共v4、当前周期/完整快照语义与产品范围。先避免旧批回流、空闲残留与无界积压。
2. **阶段B：不变语义降开销。** F05/F06/F07/F08；建立可观测计数再比较，不能以少读订阅键或降低频率换漂亮CPU。
3. **阶段C：事件与QNX能力。** F04及本车原厂订阅适配。QNX静态还原可与A/B独立开展；公共协议变更须成套升级所有使用AutoClient的APK，含AutoRecord。源码校验通过继续做本地fixture，不等第三方私有包。
4. 相机发现作为既有缺项独立任务，不在AutoService引入持续视频转发，不扫摄像头时抢占正在录制/原厂使用中的相机。

保留完整目录/扫描、地图/音乐、CarPlay1000ms轮速、100ms请求能力、独立总开关、同签名、自有helper所有权、网页永久归属与车端确认。无模拟器、无电脑远程ADB、无车辆写动作。拆类为职责边界服务，不先做全工程重命名或把现有AutoService拆出第二套运行时。

## 7. 验证口径与后续审计

此文没有性能实测结论。编码后必须分别报告：静态事实、JVM/宿主fixture、编译、目标QNX运行、实车可视/数据与性能。单调时间跨Android/QNX不可直接相减；先定义时钟域与偏差/往返估计，否则只报告同域阶段延迟。

固定场景：无任何需求、仅CarPlay车速/档位1000ms、AutoRecord固定键100ms、100/250/1000ms混合、显式全目录扫描、单个慢客户端、退订/换车型/断线、网页关闭与开启、QNX需求启动/退出。每个场景记录键集、周期、模式、APK/源码/hash、固件、投屏/录像状态；整改前后保持一致，100ms对250ms属于频率代价实验，不能冒充同条件优化收益。

指标至少包括：真实get/注册/注销次数（不是due.size代替）、源事件/覆盖/gap、JSON/Bundle/IPC字节、各段p50/p95/p99与最大延迟、队列高水位、过载、App＋UID0 helper＋自有QNX采集器CPU/PSS/RSS/线程/FD。禁止只把负载转入helper后宣称App优化成功。

现有 `profile_vehicle_frequency.py` 只采两个App，CPU假定USER_HZ=100；`compare_vehicle_frequency.py`跨样本仅比较键集合，未严格比较周期。因此它们需要先扩展，不能直接作为完整验收工具。旧 `measure_autoservice.py`走电脑ADB，不作为本任务车辆采样入口。[G26](#8-源码与固件索引)

编码交付按配套任务书提交可复现命令、失败用例和前后证据；本Agent随后按最终diff审计，不以编码Agent“测试通过”替代审查。审计结果使用“问题/证据/影响/修复/验证/未验”逐项记账。

## 8. 源码与固件索引

行号固定于本轮基线；编码后行号变化时保留基线SHA并附新位置。样本路径为本地反编译证据，不是可直接迁入的开源SDK；仅据逻辑自有实现，不复制私有二进制或授权系统。

| 索引 | 精确位置 |
| --- | --- |
| G01 | [VehicleCollector.kt:75](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt:75>) |
| G02 | [VehicleDemand.kt:21](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleDemand.kt:21>) |
| G03 | [EcarxVehicleSource.kt:34](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt:34>) |
| G04 | [EcarxVehicleSource.kt:239](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt:239>) |
| G05 | [VhalNumericSource.kt:18](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VhalNumericSource.kt:18>) |
| G06 | [AutoService.kt:269](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:269>) |
| G07 | [VehicleSignal.kt:7](</Users/anjon/Documents/GalaxyPlay/galaxy/contracts/src/main/java/com/galaxy/auto/contract/VehicleSignal.kt:7>) |
| G08 | [VehicleCollector.kt:160](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt:160>) |
| G09 | [AutoService.kt:603](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:603>) |
| G10 | [AutoService.kt:631](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:631>) |
| G11 | [SerialWorker.kt:8](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/SerialWorker.kt:8>) |
| G12 | [ClientRegistry.kt:16](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/ClientRegistry.kt:16>) |
| G13 | [AutoService.kt:400](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:400>) |
| G14 | [VehicleInventory.kt:13](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleInventory.kt:13>) |
| G15 | [AutoStartup.kt:10](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/startup/AutoStartup.kt:10>) |
| G16 | [FoundationRecovery.kt:14](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/platform/FoundationRecovery.kt:14>) |
| G17 | [QnxReceiverLifecycle.kt:14](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/platform/QnxReceiverLifecycle.kt:14>) |
| G18 | [ClientPermissionPolicy.kt:3](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/platform/ClientPermissionPolicy.kt:3>) |
| G19 | [CameraProfile.kt:1](</Users/anjon/Documents/GalaxyPlay/galaxy/record/src/main/java/com/galaxy/autorecord/core/CameraProfile.kt:1>) |
| G20 | [AutoService.kt:77](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt:77>) |
| G21 | [VehicleWebBridge.kt:11](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/web/VehicleWebBridge.kt:11>) |
| G22 | [GalaxyHabFrames.kt:3](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/projection/GalaxyHabFrames.kt:3>) |
| G23 | [RootServiceMain.kt:48](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:48>) |
| G24 | [AutoServiceRecordFeed.kt:77](</Users/anjon/Documents/GalaxyPlay/galaxy/record/src/main/java/com/galaxy/autorecord/core/AutoServiceRecordFeed.kt:77>) |
| G25 | [VehicleCatalog.kt:18](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCatalog.kt:18>) |
| G26 | [profile_vehicle_frequency.py:11](</Users/anjon/Documents/GalaxyPlay/scripts/profile_vehicle_frequency.py:11>) |
| A01 | [nq.java:444](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/nq.java:444>) |
| A02 | [d9.java:324](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/d9.java:324>) |
| A03 | [CarPropertyValue.java:23](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/com/simplerbit/autoservice/contract/CarPropertyValue.java:23>) |
| A04 | [q4.java:545](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/q4.java:545>) |
| A05 | [i6.java:91](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/i6.java:91>) |
| A06 | [ICarPropertyCallback.java:58](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/com/simplerbit/autoservice/contract/ICarPropertyCallback.java:58>) |
| A07 | [ok.java:12](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/ok.java:12>) |
| A08 | [nq.java:355](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/nq.java:355>) |
| A09 | [qnx_properties.json:1](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/jadx/resources/assets/qnx_properties.json:1>) |
| A10 | [f5.java:96](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/f5.java:96>) |
| A11 | [dk.java:105](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/dk.java:105>) |
| A12 | [i6.java:48](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/i6.java:48>) |
| Q01 | [README.md:3](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/g733-offline-environment-20261025/README.md:3>) |
| Q02 | [ClusterDataSource.xml:26](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/g733-offline-environment-20261025/rootfs/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/ClusterDataSource.xml:26>) |
| Q03 | [gauge-init.txt:1](</Users/anjon/Documents/GalaxyPlay/.local/research/autoservice-optimization-20261110/gauge-init.txt:1>) |
| Q04 | [qnx-symbols.txt:1](</Users/anjon/Documents/GalaxyPlay/.local/research/autoservice-optimization-20261110/qnx-symbols.txt:1>) |

补充核对：[EcarxVehicleSource.kt:405](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt:405>) · [RootServiceMain.kt:97](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:97>) · [RootServiceClient.kt:31](</Users/anjon/Documents/GalaxyPlay/galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceClient.kt:31>) · [IAutoCallback.aidl:1](</Users/anjon/Documents/GalaxyPlay/galaxy/contracts/src/main/aidl/com/galaxy/auto/contract/IAutoCallback.aidl:1>) · [AutoClient.kt:59](</Users/anjon/Documents/GalaxyPlay/galaxy/client/src/main/java/com/galaxy/auto/client/AutoClient.kt:59>) · [xm.java:7](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/xm.java:7>) · [ta.java:66](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/ta.java:66>) · [lq.java:12](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/lq.java:12>) · [t4.java:319](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/t4.java:319>) · [i9.java:200](</Users/anjon/Documents/GalaxyPlay/.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/i9.java:200>) · [compare_vehicle_frequency.py:14](</Users/anjon/Documents/GalaxyPlay/scripts/compare_vehicle_frequency.py:14>) · [measure_autoservice.py:44](</Users/anjon/Documents/GalaxyPlay/scripts/measure_autoservice.py:44>)。

[完整输入指纹与18项差异清单](AutoService优化证据索引-20261110.json)；该清单不包含原厂二进制或认证材料。
