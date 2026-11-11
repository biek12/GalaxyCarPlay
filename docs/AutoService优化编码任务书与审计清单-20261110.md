# AutoService 优化编码任务书与审计清单

日期：2026-11-10。配套[差异与设计](AutoService对标差异与优化设计-20261110.md)。状态：待用户安排其他 Agents 编码；本Agent本轮未发起编码Agent，后续负责集成差异审计。

## 1. 交接前提

- 阅读根AGENTS、配套设计及对应基线源码。基线为 `eb756396d714b0f386ce318956129f7433004c52`；本轮文档在 `feat/autoservice-optimization-handoff-20261110`，没有业务实现提交。
- 本车QNX镜像和提取rootfs是可用的固件证据，必须据此继续原厂接口/数据结构还原。参考样本私有载荷未取得不能成为暂停本车分析的理由。
- 源码引用行号、镜像摘要和样本指纹见[证据清单](AutoService优化证据索引-20261110.json)。先核输入指纹；业务代码若被其他人修改，按新diff调整任务，不覆盖其他人的工作。
- 不连接车辆、不运行模拟器、不安装APK、不改签名、不操作原厂HMI/启动项/车辆控制，不推送远端。不要把现有背景/SR Hook研究文件混入本次优化。
- 阶段A/B保持公共v4接口和完整订阅子集语义；100ms是请求能力，保持现有确认周期，不默认改成“只发变化”或降低采集频率。

## 2. 批次、写范围与依赖

以下为供后续分配的任务，不代表这些Agents已启动。**共享文件由集成负责人唯一写入**：`AutoService.kt`、`ClientRegistry.kt`、`VehicleCollector.kt`、`EcarxVehicleSource.kt`、构建与公共契约。任务Agent提交候选补丁/新增独立类；负责人顺序合入共享文件。不可让多个Agent同时改同一文件再整文件覆盖。

| 批次 | 对应问题 | 交付及主要写范围 | 依赖 |
| --- | --- | --- | --- |
| T00 | 证据与观测 | 仅新增指标模型/fixture、扩展性能脚本及测试；真实读取与排程计数分开 | 首先固定基线；贯穿后续 |
| T01 | F01、F02 | 需求生命周期/代次模型、VehicleCollector/源/helper释放候选及测试 | T00；公共接线交集成负责人 |
| T02 | F03 | `ClientDispatcher`等职责类与测试；ClientRegistry/AutoService接线补丁 | T01代次字段稳定后接线 |
| T03 | F05、F06、F07 | 物理键索引、不可变内部Batch、需求差异更新、目录/值缓存与freshness模型 | T01；与T02共享文件顺序集成 |
| T04 | F08 | web目录内生命周期重构/测试；AutoService.updateRemote接线补丁 | T01取消模型；不改服务端协议 |
| T05 | 缺字段/相机发现 | 固件字段对照表、只读目录候选；相机能力发现合同与互斥设计 | T03；相机公共接口单独成批 |
| T06 | QNX静态还原与只读数据源 | `tools/research`新增独立分析工具；本机输出写`.local/research`；随后自有QNX数据适配源码/fixture | 可先开展静态还原；接入依赖T03缓存模型 |
| T07 | F04事件通路 | 协议/客户端/AutoRecord事件批次及fixture；VHAL按本车配置事件订阅 | T02/T03；先定协议，再编码消费者 |
| T08 | 集成与审计 | 单命令验证、源码/产物绑定、最终diff、故障与性能证据 | 逐批完成；Codex最终审计 |

实现顺序：T00→T01→T02→T03/T04→T05；T06静态分析同时准备，独立交付；T07作为明确协议变更批次。只做完一部分时如实标记，不用暂时关闭能力伪装全部完成。

## 3. 任务细则与完成条件

### T00：真实计数与可比较测量

新增/细化指标：`physicalGetAttempts/Success/Failure`、`eventReceived/StateReplaced`、注册/注销数、每物理键实际读取、周期overrun、序列gap、各队列pending/highWater/merged/overflow、序列化字节及耗时。原`readTotal += due.size`保留时改名`dueKeys`或明确旧含义，不把它当真实HAL读取。

每个耗时明确起止：source收到→采集批完成→主线程接受→Binder提交→客户端入口→伴录写入。内部用单调时钟；原厂sourceTimestamp保留独立clockDomain。QNX与Android跨域只在时钟同步/偏差估计后给跨域数字。

修订 `scripts/profile_vehicle_frequency.py`、`compare_vehicle_frequency.py`：

- 通过既有AutoService Root远程通道只读采样，不使用电脑远程ADB。此轮只准备和用合成fixture验证，不执行车端采样。
- 覆盖AutoService、UID0 helper、AutoRecord、参与场景的CarPlay/Cluster及自有QNX数据进程；App合计与辅助进程合计同时报告，原厂共享进程单列。
- 获取实际CLK_TCK；识别 `(pid,startTicks)`、重启、缺样、计数回绕。缺少helper数据时明确“系统总成本不完整”，不能输出总收益。
- 比较完整`key→interval`、车型/固件、app/root、scan、录像/预览/投屏/网页开关及观测时长。不同频率实验另外命名。
- 新增延迟直方图/分位数、队列高水位和实际get/s；日志不得带原始车辆载荷或身份/密钥。

完成：有能检出“相同键但周期不同”“App降而helper升”“进程中途换代”“UNKNOWN时钟域”的fixture，比较器给出不可归因原因。

### T01：需求、清理与跨线程代次

将批次和需求生命周期状态显式化：`RUNNING / IDLE / STOPPING / STOPPED`等可采用等价模型；所有状态变化在同一串行域决定，跨线程只发带代次的不可变结果。新建worker、退出worker及迟到清理不能错删下一代。

内部需求包含vehicle feeds、哨兵、显式scan及菜单事件。菜单需求必须能独立存在/退出，不强迫外部订阅一项无关车辆键。Root其他功能不因车辆空闲被结束；车辆专用源/连接应注销并收口。最后需求释放时等待或记录清理结果，不能只有`cancel poll`。

完成条件：

1. 无需求不发get、不保留该需求的OEM/VHAL注册；无菜单需求时对应回调也退出。显式scan关闭后立即撤其注册，不等下一业务需求。
2. 阻塞一次旧读取，stop→start后再放行，旧批不能进入signals/客户端。切L6→L7、root→app也相同。
3. 停服期间新订阅意图保留；恢复首批立即读；主动关闭automatic、车辆或Root后，旧回调不触发自动重开。
4. 并发`close/start/setDemand`不产生第二条poll链、悬挂socket或旧worker清掉新handler。

### T02：有界分发，按数据语义处理积压

不要全局修改SerialWorker的丢弃策略：artwork、Shell、权限结果等任务携带FD/返回语义，与状态覆盖不同。新分发器只接收明确分类的数据。

| 类别 | 推荐待发策略 | 生命周期 |
| --- | --- | --- |
| `onSnapshot`状态 | 每租约最新1份 | 新状态替换旧状态，记录合并数 |
| v4车辆状态 | 每租约最新完整订阅子集1份 | 仍遵守该租约周期；替换不把Bundle当增量合并 |
| Surface/关闭/命令结果 | 独立有序、有明确容量与错误返回 | 丢弃/取消必须释放自己持有的Surface/FD；命令不能悄悄漏执行 |
| 后续事件历史 | 与T07的cursor/gap/流控合同一致 | 不可当最新状态覆盖 |

默认最多16个活动租约lane；线程惰性创建，无活跃任务后可回收。此为资源边界方案，不是性能达标承诺。若采用固定共享池替代，必须证明一个阻塞本地callback不会饿死全部其他客户端；不可仅用“池有界”证明隔离。

投递前核对lease身份、订阅代次、service代次；detach/死亡/退订清掉自己的未交付状态。Binder调用在锁外；单个失败不能清全部客户端。oneway只表示调用合同，不代表客户端处理完成，不能据提交成功伪造ACK。

完成：一个client回调阻塞，另一个仍取得最新值；100Hz合成生产压入1秒后状态待发仍≤1；退订后不投旧包；同owner重挂时旧death不能删新owner；内存/线程/FD上界可由fixture检查。

### T03：类型化、索引与目录，不改v4语义

建议新职责：`PhysicalSignalKey(backend,id,area)`、`SourceSample`、`VehicleBatch`、`VehicleSourceIndex`、`VehicleFreshness`。名称可调整，但禁止增加另一个万能Manager。

解析due语义键→物理键去重→每物理键一次get→派生多个语义值。`specByKey`与反向索引随车型/源换代；不能把L6 VHAL与同ID的car_signal合并，不能吞area。长数组/byte[]必须复制后跨线程，不能依赖原厂复用的可变包装。

ECARX事件和补读比较样本序号/epoch，保留“读取期间更新事件优先”的现规则。值不变仍可更新**真实来源确认时间**，不能用分发/缓存读取时刻刷新sourceTimestamp。原始范围不明确时保留raw与UNKNOWN，不推断踏板百分比、功率正负或枚举。

先用内部类型化消除app模式JSON中转；Root通道首批保持现有v2 framing。外部v4仍返回字符串VehicleSignal Bundle，状态/maxAge/sourceTimestamp/epoch/旧别名兼容。完整子集周期推送不改为delta；重复需求不重置全表。

目录与值分离：未采样项可出现在有来源说明的支持目录，但必须标`not_read`；运行时支持、固件声明、车型映射、实际可读状态分开。原始值page/value/语义值统一freshness，显式诊断继续完整扫描。

完成：同一物理键两个别名get仅1次；两个area仍2次；100/250ms精确到期；重复订阅不额外读；缓存get零硬件调用；数组后续修改不污染已发布批；5000ms订阅与2000ms新鲜度的关系明确，不偷偷把TTL延长成永远VALID。

### T04：网页按需资源

拆本地绑定状态存储与网络运行时。`vehicle-web.enabled=false`且无有效在线任务时，不创建web主/媒体HandlerThread，不每10秒tick。关闭时取消自有网络/Range和借入FD；正在执行结果以代次判失效。重新开启不清永久归属，不把临时码过期等同解绑，不依赖远程诊断12/24h窗口。

完成：关闭态重复打开状态页不增加线程/连接；开关10次资源回到基线；在Range传输中关闭能结束自己的FD/请求；录制继续；不会因为页面读取状态重新联网。

### T05：字段与相机发现

18个候选ID按配套设计逐个核验：先在本车Android材料或QNX镜像找到生产者/映射与转换，再加**只读**语义键；把同功能、不同来源的重复项先合并说明，不拿缺normalizedId认定完全无此能力。driveMode以已确认PA合同为准。

相机发现完成的定义必须分层：listed、可查询characteristics、open成功、首帧成功、独占冲突、超时/拒绝。枚举≠可用，不能硬编码0–3叫自动扫描。AutoService可编排权限与探测结果，持续视频留在AutoRecord；与其已有会话协商后才试开，取消要关capture/session/device/reader，不改原厂相机服务。

相机公共API有版本影响，先交合同和离线backend fixture；不要在保持v4的纯内部优化里偷增AIDL事务并声称旧客户端兼容。

### T06：用本车QNX镜像还原只读订阅链

本任务已有真实固件输入，必须完成静态研究和可审查源码候选；**不能仅返回“需要固件/需要第三方载荷”**。

输入rootfs见配套设计。工具只读ELF、配置、原始镜像与符号，输出到忽略目录；不得运行其中原厂二进制。先用该镜像`cluster-hmi`的 `FunctionGaugeInfoManager::init 0x134ad0`、构造器 `0x1352c0`、`GaugeSpeedNotify::Notify 0x134cb0`、`GaugeRPMNotify 0x1345c8`、`GaugeFuelNotify 0x1346c0`、`EtcPowerMeterNotify 0x134fc0`、Warning管理器及 `libMidProxy.so`，继续追到MsgProxy实现所在库。

本轮已经由构造器＋GOT/vtable确认消息1=车速、3=RPM、7=燃油、8=燃油警告、5=水温、10=功率表、9=电量回调，详见配套设计的七项表与`gauge-message-map.txt`。后续从这些已核验锚点继续payload/生产者/释放链，不重复把消息对应关系列为完全未知。

静态交付逐项包括：

1. 连接服务名/传输基础设施、函数/虚表/构造析构地址、依赖与本车库摘要；`Connect→RegNotify→StartNotify→Stop/Unreg/Disconnect`实际顺序。
2. 从注册对象成员/vtable确认消息ID→Notify类；payload大小、对齐、字段offset/有符号性、availability及枚举/缩放。XML与Notify写入路径交叉核验；不按名字/注册顺序猜。
3. 排除控制动作：区分纯订阅和带状态写回的Proxy方法；特别不调用Telltale自检、设置CArea或SafetyApps写回来取数。
4. 自有QNX采集进程最小依赖方案、只读适配源码、宿主字节fixture和取消/重连/退订fixture。所需ABI由本车库和消息生产/消费双向证据得出，未知字段保留raw/UNKNOWN。
5. 与AutoService的版本化数据通道、来源键命名、时钟域、完整快照/事件、代次与长度上限。复用Root/QNX认证及自有部署所有权；数据ready与HAB receiver ready独立。

优先独立消息订阅，继续追本车上游结构；原厂HMI内Hook不是默认采集入口。实际车端注入/重启/安装不在本次授权内；能够静态核验的结构、源码与本地fixture应先全部完成。若某具体结构仍未还原，列已查函数/地址、未解字段及下一条镜像追踪路径，而不是泛称无证据。

完成：原厂消息字节→自有值转换可复现，伪造短包/坏状态/错误固件被拒绝；不存在调用控制方法的路径；本车源码候选可审查。目标QNX运行、实时更新率和实屏对照分别留待实车轮次，不虚报离线=上车。

### T07：完整事件历史与协议演进

在编码前提交公共v5或等价新版本的完整合同：保留状态订阅入口，事件订阅单独声明键/节拍/批大小/保留窗口；事件记录和批次携带sourceSession、连续sequence、时间/clockDomain、状态和valueType。定义cursor与客户端确认/拉取方式，使服务知道已消费位置；溢出/换代用明确gap/reset，不能静默覆盖或无限重发。

具体队列容量、字节上限、批大小先在fixture压测后作为命名常量写回合同，不直接抄2048或16。慢客户端不能阻塞HAL线程；事件保留只限订阅范围，退订立即停止该键的新增记录。事件ring和分发队列分别有界；既不能用线程池有界证明历史有界，也不能用外层有界证明Binder内核无积压。

VHAL订阅只对本车配置支持项实现，按changeMode/min/maxSampleRate核对；事件速度快于推送节拍时保留真实中间变化。若本车某属性不提供事件，则保留既有到期get并报告可观测频率，不重复缓存造10Hz。

AutoRecord使用源事件时间写sidecar和检测短踩，回放/曲线按同一时轴；保持旧段可读。客户端重连重放订阅，cursor按sourceSession处理；不能把其他源的相同seq连续拼接。协议版本变更成套构建所有消费者，不只更新AutoService。

完成：两拍间多个合成变化都按序进入伴录；状态消费仍只看到最新快照；溢出有可定位gap；源重启不倒序；错误时钟域不参与视频精确对齐；旧录像仍可回放。

## 4. 验证入口与报告模板

文档阶段未运行这些构建/测试。编码Agent按实际修改范围扩展仓库内脚本，再执行一次必要回归，不跑Lint、不启动模拟器：

```sh
# 现有入口：服务/Cluster测试与三个既有APK构建；不会连接车辆
scripts/verify_autoservice_remediation.sh

# 既有平台全检查入口，涉及契约/native/投屏时使用
scripts/verify_auto_platform.sh
```

现有入口尚未完整覆盖auto-client、AutoRecord及新事件合同。T08必须补统一脚本（建议 `scripts/verify_autoservice_optimization.sh`）：按批次调用contracts/client/service的单测，必要时加record/cluster/CarPlay回归，协议批次构建所有消费者。脚本只在本机验证，不进入APK；签名缓存缺失即停止，不生成新签名。

每个Agent交付以下同一结构：

| 字段 | 必填内容 |
| --- | --- |
| 基线/最终diff | SHA、未提交差异、任务ID、唯一写范围、依赖 |
| 行为变化 | 具体触发前后行为；保留的公共合同/产品设置 |
| 证据 | `file:line`、QNX ELF地址＋SHA；不贴凭据/原始车辆值 |
| 测试 | 时间、完整命令、结果/失败数、产物位置；不能复用历史测试当本次结果 |
| 指标 | 已测数字、条件、比较有效性；未实测明确写未测 |
| 未完项 | 具体函数/字段/接口、已经检查的范围、下一动作 |
| 审计提示 | 并发交错、资源所有权、兼容风险、需复核的设计取舍 |

## 5. Codex最终审计关卡

审计时逐项核查最终代码，而不是只看任务书复述。以下任一项不满足不能标整体完成：

- [ ] 最后需求退出真实注销，菜单事件需求独立，Root其他业务保持正常。
- [ ] 采集→主线程→服务缓存→租约队列→客户端每一步代次可追；旧批/旧death不能越代。
- [ ] 状态/事件/命令分开，有界策略与FD/Surface释放完整；阻塞一个客户端不会阻断其他客户端。
- [ ] 物理键去重不吞backend/area；同值确认、源时间、新鲜度和UNKNOWN无伪造。
- [ ] 按键、短踩等事件不被最新状态合并吞掉；未观测的物理事件没有被宣称补回。
- [ ] 重复需求不全表reset；100/250/1000ms混合保持节拍，scan不挤掉业务读且保留全目录能力。
- [ ] 公共v4语义未被暗改；新增协议有全消费者迁移、重连和旧录像兼容证据。
- [ ] QNX依据本车镜像完成实际消息/字段追踪；没有把XML默认值、符号存在或host测试冒充实车返回。
- [ ] 只读QNX数据源不调用控制/自检/原厂写回；没有擅自Hook或改原厂启动项。
- [ ] 网页关闭没有后台空转；永久绑定、临时码和诊断窗口独立。
- [ ] 资源/性能含helper与QNX采集器，完整场景相同；缺样、跨时钟、不同周期明确标出。
- [ ] 本次命令/日志/hash可复现；不把未执行测试写为通过，不泄露私有部署信息/认证/签名。

审计输出格式：`[优先级] 问题 → 最终file:line → 触发/影响 → 修复建议 → 已跑验证/仍未测`。无问题也列出审查范围和真实验证边界。用户安排编码交回后，再执行本审计；本次不提前声称审计通过。
