# AutoService 车辆数据订阅链路与采集资源优化方案（已按 B 落地）

2026-10-31。§0–§9 是选择前的现状定位与方案准备，原文保留不改；**用户已选择方案 B（订阅驱动采集完整版）并已实施**，落地结果、实际改动与验证状态见文末 §10。全程未连接车机、未安装 APK。

数字分三类标注：**【事实】**＝可从代码逐行数出；**【推导】**＝按代码公式算出；**【估算】**＝需假设，待实车实测。

---

## 0 结论摘要

现状的问题不是"订阅链路本身低效"，而是**采集与发布两侧都不看订阅者**：

1. **采集端**每 250ms 无条件构建全部 78 个目录键（83 个子 Bundle），无论有没有人读；
2. **发布端** `onSnapshot` 通道**没有任何按键裁剪**——裁剪只存在于 `onVehicleFeed` 通道（[VehicleFeed.kt:43](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleFeed.kt#L43)）。每个客户端每一拍都收全部 83 个车辆子 Bundle；
3. 现存 **7 个消费点里 5 个一个车辆键都不读**；真正需要车辆数据的 2 个消费点去重只用 **11 个键**；唯一走 feed 的消费点（CarPlay 车速）需要 **1Hz** 却每拍拿 **4Hz**；
4. 78 个目录键中有 **48 个当前零消费者**（但主题可绑定任意 `vehicle.<key>`，所以不能删除，只能改成"按声明采集"）。

所以用户提出的三条设想——**统一分频拉取 / AutoService 侧缓存对外供给 / 只拉已订阅数据**——方向全部正确，且这是本链路收益最大的部分。我建议把它与"按需发布 + 拉取 API"合并为**一次契约升级（v4）**，分两步落地（见 §6）。

---

## 1 现状链路

### 1.1 两条并行通道（都经同一个 IAutoCallback Binder）

| 通道 | 触发 | 内容 | 按键裁剪 |
| --- | --- | --- | --- |
| `onSnapshot(Bundle)` | `publish()`，17 个调用点共用；其中只有 [AutoService.kt:248](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L248) 是车辆采集驱动 | 25 个顶层键 + `vehicle` 子 Bundle（**83** 个二级子 Bundle）+ media/navigation/features/policy | **无**。`:435` 无条件 `putBundle("vehicle", Bundle(signals))`，[AutoService.kt:60](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L60) 全租约广播 |
| `onVehicleFeed(Bundle)` | 客户端 `setVehicleFeed(keys)` 后，每个采集拍 | 只含订阅键（≤32，实测车速只订 2 个）+ `at`/`sequence` | **有**：[VehicleFeed.kt:43](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleFeed.kt#L43) 按 keys 取并用 `deepCopy()` |

即：**v3 的"按需订阅"只解决了 feed 通道，快照通道仍是全量**。

### 1.2 采集节拍

- 固定 250ms 自排队（[VehicleCollector.kt:98](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L98)）；断连时 3000ms。
- 每拍无条件调用 `feed(bundle, now)`（[VehicleCollector.kt:87](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L87)），订阅者判断发生在主线程 `publishFeed` 之后（[AutoService.kt:251](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L251)）——**零订阅者时仍是每拍一次跨线程 Message + 两次列表分配**。
- 快照节流为"签名变化 **或** ≥1000ms"（[VehicleCollector.kt:89](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L89)）→ **1Hz 下界、行驶中 4Hz**。签名含 `epoch`（[VehicleCollector.kt:74](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L74)），换代必翻转。

### 1.3 消费点盘点（关键：谁真的读车辆键）

| 消费点 | 文件 | 通道 | 实际使用键数 | 收到键数 | 真实需要频率 |
| --- | --- | --- | --- | --- | --- |
| Cluster THEME | [ClusterSceneOutput.kt:64](../galaxy/cluster/src/main/java/com/galaxy/autocluster/runtime/ClusterSceneOutput.kt#L64) | snapshot | **10**（默认 GT 主题） | 83 | ≥1Hz，指针插值目标 160ms |
| Cluster PREVIEW | [ClusterActivity.kt:88](../galaxy/cluster/src/main/java/com/galaxy/autocluster/ClusterActivity.kt#L88) | snapshot | 随草稿主题 | 83 | 编辑期 |
| ClusterActivity OBSERVER | [ClusterActivity.kt:52](../galaxy/cluster/src/main/java/com/galaxy/autocluster/ClusterActivity.kt#L52) | snapshot | **0**（只用 qnxReady/status） | 83 | 事件驱动 |
| CarPlay MAP | [GalaxyAutoServiceBinding.kt:23](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyAutoServiceBinding.kt#L23) | snapshot | **0**（连 `onSnapshot` 都未传） | 83 | **0Hz** |
| CarPlay 车速 OBSERVER | [GalaxyVehicleSpeed.kt:60](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyVehicleSpeed.kt#L60) | **feed + snapshot** | **2**（displaySpeed/gear） | feed 2 + snapshot 83 | **1Hz**（iAP2 控制循环 1s） |
| ServiceActivity OBSERVER | [ServiceActivity.kt:35](../galaxy/service/src/main/java/com/ecarx/autoservice/ServiceActivity.kt#L35) | snapshot | **0**（只用顶层计数） | 83 | 事件驱动 |
| DiagnosticActivity OBSERVER | [DiagnosticActivity.kt:75](../galaxy/service/src/main/java/com/ecarx/autoservice/DiagnosticActivity.kt#L75) | snapshot | **0** | 83 | **0Hz**（另走分页读） |

结论：**5 个消费点读 0 个车辆键却每拍收全套 83 个**。

---

## 2 现状成本（数字）

### 2.1 每拍（250ms，APP 模式，N=78）【事实/推导】

| 项 | 数值 | 依据 |
| --- | --- | --- |
| `getProperty` Binder 调用 | **平均 43 次/拍，峰值 110 次** | 语义 78 键 maxAge=500ms ⇒ 78×250/500=39；轮转 32 键 maxAge=2000ms ⇒ 4。公式见 [EcarxVehicleSource.kt:196](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt#L196) |
| org.json 对象 | **≈189 个 JSONObject + 3 个 JSONArray** | 78 aliases + ≈110 raw + 顶层；`:234` `:251` `:264` |
| Bundle 构造 | **84 个**（78 信号 + 4 兼容别名 + 1 安全带），外层 83 键 | [VehicleCollector.kt:64-84](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L64-L84) |
| 签名 StringBuilder | ≈2.4K 字符，扩容累计 ≈18KB | `:65` `:74` `:88` |
| 主线程 post | 2 次 | [VehicleCollector.kt:91](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L91)、[AutoService.kt:253](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L253) |
| 分配总量【估算】 | **≈139KB/拍**（JSON 43%、Bundle 19%、集合/反射/装箱 19%、签名 13%） | 假设见 §9 |

### 2.2 每秒【推导/估算】

| 指标 | 数值 |
| --- | --- |
| Binder `getProperty` | **172 次/s**（峰值对齐拍可到 440） |
| org.json 对象 | **≈756 个/s** |
| 堆分配 | **≈0.55MB/s**（APP）/ **≈1.3MB/s**（ROOT，helper 与 App 两侧合计） |
| 快照投递 | 每次投递每客户端 **89 个 Bundle 头、≈22–30KB**；`P×N×83` ⇒ 2 客户端 1Hz 稳态 **166 次/s**，4Hz 上界 **664 次/s** |

### 2.3 ROOT 模式额外成本【推导】

helper 常驻同一 `EcarxVehicleSource` 实例，**IPC 次数不增加**；增加的是每请求 **2 次 JSON 序列化（≈39KB）＋ 1 次跨进程拷贝**，外加 1 个 UID0 进程、1 个常开 socket FD、helper 内 6 线程池。

---

## 3 浪费点定位（W1–W5）

- **W1 全量构建**：每拍把 78 个目录键全部转成 Bundle，无论是否有人读。占采集侧分配 ~45%。
- **W2 全量广播**：`onSnapshot` 无裁剪 + 17 个 `publish()` 调用点中 16 个与车辆无关却整包重发。**这是绝对值最大的浪费**。
- **W3 节拍错配**：采集 4Hz、feed 4Hz、快照 1–4Hz，全部同频；消费者需求从 0Hz 到 1Hz 不等。
- **W4 JSON 自循环**（仅 APP 模式）：`EcarxVehicleSource` 造 ~189 个 JSONObject → `VehicleCollector` 立刻 `optString/optLong` 读回做 Bundle。同进程内做了一次"值→字符串→再解析"。占采集侧分配 ~43%。
- **W5 无订阅仍常驻**：`AutoService-vehicle` 线程在 `onCreate` 无条件创建（[AutoService.kt:247](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L247)），`features.vehicle` 关闭时线程仍空占；`VehicleInventory` 无淘汰且 `validCount` 永不过期（[VehicleInventory.kt:20-21](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleInventory.kt#L20-L21)）。

---

## 4 对用户三条设想的逐条判定

| 设想 | 判定 | 依据与前提 |
| --- | --- | --- |
| **① 分频率统一拉取** | **可行，收益明确** | 采集/发布节拍当前是硬编码 250ms 常量，与消费者无关。契约加 `minIntervalMillis`（建议档位 250/500/1000/变化触发）即可分桶；车速应降到 1000ms |
| **② 其他 App 从 AutoService 缓存拉取** | **可行，且已有基础** | 信号缓存已存在（`EcarxVehicleSource.samples` + maxAge 500/2000ms，`:29` `:196`），只是**没有作为对外服务暴露**。新增 `getVehicleValues(keys)` 读缓存即可，**不触发采集**。feed 推送可保留给"需要实时"的少数消费者 |
| **③ 先只拉已订阅，未订阅不拉** | **可行，且与 OEM 设计一致，但有一处必须实车验证** | 原厂 `getProperty` 读的是"注册后刷新的缓存"，代码注释明确（[EcarxVehicleSource.kt:118](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt#L118)）。当前 `registerSignals` 注册了**全部可读可映射**的 ID（`:161`）。改为"只注册订阅 ID"即天然满足"只拉订阅"。**风险前置条件**：若某属性从不产生变化事件，只注册不主动补读会永远无值 → 必须"订阅即注册 + 到期补读预算"，且需实车验证事件覆盖率 |

**与既有约束的兼容**：AGENTS.md 要求"车辆数据应读尽读…不再以用户举例字段限定范围"与"车辆全量可读范围、时效保持"。本方案**不删目录、不删键、不缩减可读范围**——目录枚举与按需扫描能力保留（改成显式可取消的低速扫描任务），优化的是**常驻轮询的采样集合**。二者不冲突，但需要在文档里写清边界，避免被读成"砍能力"。

---

## 5 方案对比

### 方案 A｜缓存化 + 按订阅裁剪 + 分频（不动 OEM 注册范围）

**做法**
1. 采集侧引入版本化的 `SignalCache`（每键：值/status/receivedAt/version），不再每拍构建全量 Bundle；
2. 只在"有客户端声明需要某键"时才把该键组装成 Bundle；
3. 快照拆包：`vehicle` 全量 → 「元数据包（低频）」+「按订阅裁剪的车辆包」；未声明的客户端只拿摘要；
4. feed 与采集支持 `minIntervalMillis` 分档（250/500/1000/变化触发）；
5. 零有效订阅时采集降频到 1Hz 或暂停；`AutoService-vehicle` 线程懒建。

**改动面**：`VehicleCollector`、`AutoService.publish/publishFeed`、`ClientRegistry.Lease`、契约 v4 加"声明键集合 + 频率"。OEM 注册与轮转逻辑不动。
**收益【估算】**：快照通道 IPC 与主线程负载降 **80–90%**；采集侧分配降 **30–50%**（W1/W3/W5）。
**风险**：低—中。破坏性契约变更（三 APK 需同版本）。
**验证成本**：中（离线回归 + 一次实车采样）。

### 方案 B｜订阅驱动采集（用户设想完整版）

**做法**
1. 采集集合 ＝ 当前全部订阅键的并集（主题 sources 需回传，见 §7-13）；
2. `registerSignals` 只注册订阅 ID，取消订阅时增量注销；
3. 每拍只读"到期键"，无到期键则空转极轻；
4. 全量目录诊断改为**显式任务**（限速、可取消、默认关），保留"读尽读"能力；
5. 新增 `getVehicleValues(keys)` 拉取 API（读缓存，零采集触发）；
6. 事件优先、轮询兜底（兜底周期按键的 `minInterval`）。

**改动面**：A 的全部 + `EcarxVehicleSource.connect/cycle` 的注册与采样集合、ROOT helper 协议（`op1` 需带 keys）。
**收益【估算】**：采集侧 IPC 从 172 次/s 降到**约 8–16 次/s**（2 键 × 1Hz + 少量兜底），分配再降 **60–80%**；ROOT 模式每请求 JSON 从 39KB 降到 <1KB。
**风险**：中—高。**关键未验证事实**：原厂事件覆盖率（哪些属性只可读、不发事件）。需要"订阅即注册 + 到期补读"双保险 + 实车长时验证。
**验证成本**：高（需实车跑多场景，比对"全量模式 vs 订阅模式"同键同值）。

### 方案 C｜事件优先 + 兜底，取消固定轮询

在 B 之上进一步：完全取消 250ms 固定节拍，事件到达即发布，5s 兜底补读。
**收益最大**，但**依赖原厂事件覆盖率已被证明**，且改动触及时序语义（`sequence`/`at` 语义、STALE 判定、测试锁定的"每轮 getProperty 次数"）。**建议作为 B 落地并实测后的第二阶段，不单独选。**

### 方案 D｜结构级：共享内存快照

用 ashmem/共享内存放车辆快照，客户端直接映射读，绕开 Parcel。
**不推荐**：Binder Parcel 在 8155 上已是内存拷贝，收益远小于 A/B 的"根本别发"；改动大、跨进程生命周期复杂。

### 对比表

| | A | B | C | D |
| --- | --- | --- | --- | --- |
| 治 W1 全量构建 | 部分 | **是** | 是 | 部分 |
| 治 W2 全量广播 | **是** | 是 | 是 | 是 |
| 治 W3 节拍错配 | **是** | 是 | 是 | 否 |
| 治 W4 JSON 自循环 | 否 | **是** | 是 | 否 |
| 治 W5 常驻空转 | 部分 | **是** | 是 | 否 |
| 离线可验证 | 是 | 部分 | 否 | 否 |
| 需实车验证的关键前提 | 无 | **事件覆盖率** | 事件覆盖率 + 时序 | — |
| 实施风险 | 低—中 | 中—高 | 高 | 高 |

---

## 6 推荐路线：A+B 合并设计、分两步落地

**为什么合并**：A 与 B 都要动契约，分两次就是两次破坏性变更、两次三包联调。建议**一次设计 v4，分两步实现**：A 先上（低风险、立刻见效、可离线回归），B 紧随（依赖 A 的 SignalCache 与声明机制）。

### 契约 v4 草案

```
// 保留 v3 语义（向后兼容读取，但方法签名变更需三包同版本）
void setVehicleFeed(IBinder owner, in String[] keys, int minIntervalMillis);   // 分频订阅
Bundle getVehicleValues(in String[] keys);                                     // 读缓存，不触发采集
void setVehicleInterest(IBinder owner, in String[] keys);                      // 快照裁剪声明（含主题 sources）
```

- `minIntervalMillis` 档位：250 / 500 / 1000 / 0（仅变化触发）；服务端按桶调度。
- 未知键仍整体拒绝（沿用 v3 语义，`unknown_signal:<key>`），不静默半订阅。
- 缺键＝无值、不补 0 / 默认值（沿用 v3）。

### 第一步（A）：预计改动集中、可离线回归

1. `SignalCache`（版本化）+ 按订阅裁剪组装；
2. 快照拆包：元数据（低频）+ 车辆（按声明裁剪）；
3. `minIntervalMillis` 分频；车速订阅改 1000ms；
4. 零订阅降频 / `AutoService-vehicle` 懒建；
5. **P0 零风险项**（§7 的 1/2/3/5/6/7）顺手一起做。

### 第二步（B）：需实车验证

6. 订阅驱动 `registerSignals` + 增量注销；
7. 到期键批读 + 兜底补读预算；
8. 目录诊断改为显式任务；
9. `getVehicleValues` 拉取 API 切流；
10. ROOT helper 协议瘦身（二进制/增量）。

### 每步验收门槛（不可跳过）

- 离线：`scripts/verify.sh --no-lint`（保持三个 APK 同版本）；
- 实测：`scripts/measure_autoservice.py` 的 `idle` / `telemetry` / `static` / `map-media` 四场景，**整改前后对比 CPU / 内存 / 线程数 / FD / 数据延迟**（AGENTS.md 硬要求）；
- 正确性：48 个零消费者键不得消失——通过"诊断任务"仍可读到全量目录；主题绑定任意 `vehicle.<key>` 仍能取到值。

---

## 7 额外的降 CPU / 内存项（超出用户设想，按收益排序）

| # | 项 | 位置 | 收益 | 风险 | 归属 |
| --- | --- | --- | --- | --- | --- |
| 1 | **CarPlay MAP 客户端根本不读车辆数据**（`onSnapshot` 未传），改为"不订阅快照"，服务端对该租约跳过整包序列化 | [GalaxyAutoServiceBinding.kt:23](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyAutoServiceBinding.kt#L23) | 直接除掉 1/N 的全量序列化 | **零** | P0 |
| 2 | 3 个只读顶层的消费点（Service/Diagnostic/ClusterActivity OBSERVER）改为"元数据-only" | 同上表 | 再除 3/N | **零** | P0 |
| 3 | 17 个 `publish()` 调用点里 16 个与车辆无关却整包重发 → 拆成元数据/车辆/媒体三类包 | [AutoService.kt:426](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L426) | 消除绝大部分冗余序列化 | 低 | A |
| 4 | 主题 `scene.sources` 回传 AutoService（当前服务端不知道主题要哪些键） | Cluster → 契约 | 是 B 精确采集的前提 | 低 | A/B |
| 5 | APP 模式去掉 JSON 自循环，改类型化结果（ROOT 保留 JSON 协议） | [EcarxVehicleSource.kt:234-268](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt#L234-L268) → [VehicleCollector.kt:61-74](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L61-L74) | 采集侧分配 **-43%** | 低（内部 API） | P0 |
| 6 | 签名比较改 long 版本号/哈希，替代 2.4K 字符串拼接 | [VehicleCollector.kt:65](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L65) `:74` `:88` | -18KB/拍 | 低 | P0 |
| 7 | 每拍新建的 `mapOf` / `setOf` 字面量提成常量 | [VehicleCollector.kt:77](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L77) `:98` | 小 | **零** | P0 |
| 8 | `VehicleInventory` 无淘汰 + `validCount` 永不过期（正确性 bug，不只是性能） | [VehicleInventory.kt:20-21](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleInventory.kt#L20-L21) | 内存有界 + 计数正确 | 低 | A |
| 9 | `VehicleSignal` 的 `maxAgeMillis` 硬编码 2000ms 与轮转重读周期（2.0–2.25s）打架，导致**静止时仍周期性 VALID↔STALE 翻转**，每次翻转都翻转签名 → 白白触发 deliver | [VehicleSignal.kt:18](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/VehicleSignal.kt#L18)、[VehicleCollector.kt:68](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L68) | 静止场景少发 | 低 | A |
| 10 | `deepCopy` 按 keyset 指纹分组，多租约共享一次 `select` | [VehicleFeed.kt:43](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleFeed.kt#L43) | 小（当前订阅者少） | 低 | A |
| 11 | `publish()` 移出主线程 + 16ms 去抖合并 | [AutoService.kt:426](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L426) | 主线程卡顿消除 | 中（线程归属） | A |
| 12 | ROOT helper 每请求 39KB×2 JSON → 二进制/增量帧 | [RootServiceMain.kt:85](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt#L85)、[RootWire.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootWire.kt) | ROOT 模式分配 -60% | 中（协议） | B |

> 第 1、2 项合计即可去掉当前 **5/7 消费点的全部车辆载荷**，且不改任何既有语义——建议无论选哪个方案都先做。

---

## 8 度量与验收

**已有工具**：[measure_autoservice.py](../scripts/measure_autoservice.py) 已支持 `idle`/`telemetry`/`static`/`map-media`/`stopped` 五场景，采 CPU ticks、VmRSS、Pss、Threads、FD 与 `hab=` 指标。

**需补充**：把内部每拍计数暴露到 `dump()`（当前 `dump` 只有 `feeds=` 与 `clients=`）：
```
vehicleTickReads=… vehicleTickBundles=… vehicleTickJson=… feedPushesPerSec=… snapshotPushesPerSec=… snapshotBundleHeads=…
```
这样"优化前/后"的数字可复现、可写进验收记录，而不是只给一个"感觉更省"。

**离线回归要改的断言（有意变更，需在验收中写明）**：
- [VehicleEventsTest.kt:86](../galaxy/service/src/test/java/com/ecarx/autoservice/vehicle/VehicleEventsTest.kt#L86) 当前锁定"每轮 getProperty 次数 = 属性数"；B 方案下语义变为"每轮只读订阅键 + 到期键"，该断言需改写而不是"保持通过"。
- [VehicleCatalogTest.kt:10](../galaxy/service/src/test/java/com/ecarx/autoservice/vehicle/VehicleCatalogTest.kt#L10) 锁定 78 条目录——**必须保持**（不砍能力）。
- `VehicleSourceUnavailableTest` 锁定断连时仍发 78 条 UNAVAILABLE——A 方案裁剪后需明确改为"按订阅返回 UNAVAILABLE"。

---

## 9 证据边界（未实测项）

1. 全部 CPU / 内存 / 线程 / FD 数字为**静态推导与估算**，`≈139KB/拍`含 §9 假设（UTF-16 String、Bundle ArrayMap 尺寸、无逃逸分析）；**未在车机上采样**。
2. **原厂事件覆盖率未知**：哪些属性只可读不发事件，直接决定 B 方案的兜底预算；本仓库无此数据，必须实车验证。
3. `ecarxcar_service` 的 `getProperty` 实现不在本仓库（仅通过 `PathClassLoader` 反射 `/system/priv-app/ECarXCarService/…`），其 IPC 真实耗时与锁竞争**未知**。
4. `publish()` 的真实频率取决于真实客户端数 P 与车辆变化率，目前无统计出口，只能由 `dump` 的 `clients=`/`feeds=` 推算。
5. 主题目标输入率没有标定常量（`GaugePainter` 的 160ms 是插值跟随，不是数据需求频率）。
---

## 10 最终选择与落地结果（2026-10-31 实施）

### 10.1 用户选择

- 路线：**只做 B（订阅驱动采集完整版）**，不采用 A+B 合并中的 A 作为前置步骤。
- 未订阅属性的处置：**订阅驱动 + 显式诊断扫描（默认关，手动触发）**。即正常运行时只采订阅键；全目录“读尽读”能力保留为默认关闭的显式开关，不删目录键、不删能力。

### 10.2 契约（v3 → v4，破坏性变更）

| 能力 | 位置 |
| --- | --- |
| `VERSION = 4` | [AutoContract.kt:7](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/AutoContract.kt#L7) |
| 订阅间隔常量与校验 `MIN/MAX/DEFAULT_FEED_INTERVAL_MS`、`feedIntervalValid` | [AutoContract.kt:36](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/AutoContract.kt#L36)、[:39](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/AutoContract.kt#L39) |
| `setVehicleFeed(owner, keys, minIntervalMillis)` | [IAutoService.aidl:31](../galaxy/contracts/src/main/aidl/com/galaxy/auto/contract/IAutoService.aidl#L31) |
| `getVehicleValues(keys)`（纯拉取，不触发车机读取） | [IAutoService.aidl:33](../galaxy/contracts/src/main/aidl/com/galaxy/auto/contract/IAutoService.aidl#L33) |
| `setVehicleScan(enabled)`（仅本包 UID 可用的诊断扫描开关） | [IAutoService.aidl:35](../galaxy/contracts/src/main/aidl/com/galaxy/auto/contract/IAutoService.aidl#L35) |

签名语义保持不变的部分（v3 已定，本次不放宽）：键必须是当前车型目录别名；未知键整体拒绝 `unknown_signal:<key>`；空数组＝退订；缺键＝本轮无值，绝不填 0/默认值；状态只有 VALID/STALE/UNAVAILABLE/REJECTED。

### 10.3 采集侧：只采订阅键、只读到期的键

- [VehicleDemand.kt:12](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleDemand.kt#L12) 新增：把“每个租约的（键集合, 间隔）”合并成“键 → 最小间隔”（[:64](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleDemand.kt#L64)），并按各键自己的间隔算出本拍到期集合（[:46](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleDemand.kt#L46)）。同键取**最紧**间隔，不取平均；退订再订阅会作废旧“刚读过”记录，避免新订阅者白等一个周期。
- [EcarxVehicleSource.cycle:282](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt#L282) 改为 `(specs, now, model, wanted, due)`：只为 `wanted` 组装样本，只为 `due` 发起 `getProperty`；未到期的键复用上一轮缓存。原厂 `registerSignals` 只在订阅集合变化时增删（[applyFilter:197](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt#L197)），菜单键事件始终注册、始终排空。
- [scanCycle:330](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt#L330) 是唯一的“读未订阅属性”入口：每拍32项轮转整个目录，默认关闭。
- [VehicleCollector.setDemand:68](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L68) / [poll:105](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L105)：无人订阅且扫描关闭时自排队停止，线程按需创建；节拍取 `demand.periodMillis`，扫描开启时才回到 250ms。采集失败（断连）时**不计已读**，否则恢复会被放大到一整个订阅周期。

### 10.4 发布侧：快照不再背车辆值

- `publish()` 不再 `putBundle("vehicle", …)`，只发**键名清单** `vehicleKeys` 与 `vehicleScanning`（[AutoService.kt:485](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L485)）。这是本次改动里对 `onSnapshot` 成本影响最大的一条：原来每次投递都给**每个**客户端 83 个车辆子 Bundle。
- 车辆值只走 `onVehicleFeed`，并按租约自身的间隔节流（[publishFeed:439](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L439)）。
- 租约新增 `feedIntervalMillis`/`lastFeedAt`；`applyDemand()` 在挂接、退订、功能开关、车型切换、服务停止后重算全局需求（[AutoService.kt:458](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L458)）。

### 10.4.1 旧公共别名仍可订阅（能力不缩减）

`fuelConsumption` / `electricConsumption` / `parkingBrake` / `positionLights` / `driverSeatbeltWarning` 这 5 个键**不在** 78 条目录里（它们是基础属性的派生别名），但主题与旧客户端在用。v4 的处理：

- [VehicleCatalog.LEGACY_ALIASES](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCatalog.kt#L17) 作为唯一别名表，`baseKey()` 做别名→基础键映射；
- `VehicleFeed.validate` 接受别名（不再按未知键拒绝），[applyDemand](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L458) 把租约订阅翻译成基础键后才进采集需求，租约自己的 `feed` 仍保留别名用于裁剪下发；
- 采集侧按基础键读一次，再派生同名字子Bundle（安全带仍需取反换算）；
- 快照的 `vehicleKeys` 同时包含目录键与别名，客户端据此知道哪些键可以订阅。

不这样处理的话，这些键会从“快照里有值”变成“永远没有值”——那是能力缩减而不是优化。

### 10.5 消费侧

| 消费点 | 变更 |
| --- | --- |
| CarPlay 轮速（`GalaxyVehicleSpeed`） | 订阅 `displaySpeed`/`gear`，间隔 **1000ms**（iAP2 位置控制循环是 1Hz，原来按 250ms 订阅等于多读 3 倍） |
| AutoCluster 运行时 | [ClusterSceneOutput:154](../galaxy/cluster/src/main/java/com/galaxy/autocluster/runtime/ClusterSceneOutput.kt#L154) 把编译主题里实际引用的 `vehicle.*` 源裁剪成订阅键（先用快照的 `vehicleKeys` 过滤未知键，否则服务端会整次拒绝）；[onVehicleFeed:78](../galaxy/cluster/src/main/java/com/galaxy/autocluster/runtime/ClusterSceneOutput.kt#L78) 收到值后与最近一份完整快照合并重建 `SceneData`——保留快照是为了不让媒体/导航/菜单字段被订阅通道冲掉 |
| AutoCluster 编辑器字段下拉 | 改用快照的 `vehicleKeys`（`snapshot.getBundle("vehicle")` 已不存在） |
| 诊断页 | 新增“全目录诊断扫描：关/开”开关，回显服务端 `vehicleScanning` 真实状态；开启时说明成本 |
| `dump --cluster-fields` | 快照已无车辆值，诊断时显式把当前订阅快照并进 `vehicle` 子 Bundle，保持同一份数据口径 |

### 10.6 度量出口

`dump()` 新增 `vehicleDemand`（键@间隔）与 `vehicleStats`（`ticks/reads/bundles/feeds/scan/active/keys` 累计值，[VehicleCollector.stats:87](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L87)）。累计值只增不减，由 [measure_autoservice.py](../scripts/measure_autoservice.py) 前后两次采样相减即得“每次/每秒”读数，不需要服务内部自采样计时。

### 10.7 离线验证（本机，2026-10-31）

命令：`scripts/verify.sh --no-lint`（离线源码契约 → 工具回归 → 各模块单测 → 三个 APK assembleDebug）。

- `scripts/project_checks.py`：通过（1420 份锁定文件、709 份已登记差异、42 份已登记删除）。本次产品层改动已用 `scripts/record_patches.py --write` 重新登记（3 新增＋18 修改），未手工改补丁。
- 单测：`:auto-contracts`、`:auto-client`、`:cluster`、`:service` 全绿；`:service` 82 项。
- 新增/改写的断言（有意变更）：
  - [VehicleEventsTest](../galaxy/service/src/test/java/com/ecarx/autoservice/vehicle/VehicleEventsTest.kt) 从“每轮读全部属性”改为显式传 `wanted`/`due`，并新增 `unsubscribedCatalogKeysAreNeverRead` 锁定“未订阅键一次都不读”——退回全量轮询会立刻失败。
  - [VehicleSourceUnavailableTest](../galaxy/service/src/test/java/com/ecarx/autoservice/vehicle/VehicleSourceUnavailableTest.kt) 改为“断连时只报订阅键 UNAVAILABLE”，并新增“显式请求全目录仍逐条给状态”，保证读尽读能力没被砍掉。
  - 新增 [VehicleDemandTest](../galaxy/service/src/test/java/com/ecarx/autoservice/vehicle/VehicleDemandTest.kt)：空订阅不采集、同键取最紧间隔、间隔夹取、到期判定、退订重订立即读、时钟回拨宁可多读。
  - [AutoContractTest](../galaxy/contracts/src/test/java/com/galaxy/auto/contract/AutoContractTest.kt) 锁定 v4 与间隔校验边界。
- 期间发现并修掉两处**与本方案无关的既有测试脚手架失效**（不涉及产品行为）：
  - 缺 `com.ecarx.car.hal.HalPropertyIdTransformation` 替身，导致 `EcarxVehicleSource.connect()` 在 Robolectric 下必然抛异常，整车采集链路退化成 `not_connected`（新增 [HalPropertyIdTransformation.kt](../galaxy/service/src/test/java/ecarx/car/hal/HalPropertyIdTransformation.kt)）。
  - [PlatformExecutionTest](../galaxy/service/src/test/java/com/ecarx/autoservice/platform/PlatformExecutionTest.kt) 的QNX替身服务端有两处过期：命令标记实际是 `as_`（替身写死大写 `AS_`）、`pidin user` 回包缺 UID 列使 `login` 必然判 `qnx_root_identity_rejected`。已按产品侧真实协议修正替身，并补一条“非 UID0 必须拒绝”的反向用例。
  - `scripts/verify.sh` 中 `$status，` 这类**变量名后紧跟全角字符**的写法在非 UTF-8 locale 下会被当成变量名的一部分而报 `unbound variable`，已改为 `${status}` 等显式花括号（共3处）。

### 10.8 仍未验证（不得写成已达成）

1. **CPU/内存/线程/FD 的前后对比尚未在车机上采样**。本次只给出代码级结论与度量出口；§2 的 `≈139KB/拍`、`172次IPC/s` 等仍是推导/估算。
2. **原厂事件覆盖率仍未知**：哪些属性只可读、不发 `SignalFilter` 回调，决定“只靠事件＋到期补读”是否够用。若某属性既无事件又没被订阅，现在**不会**有任何值——这是 B 的语义，不是缺陷；需要实车确认消费端是否接受按订阅取数。
3. 主题侧只订阅“当前编译主题引用到的键”，其覆盖面等于主题真实引用面；主题编辑器中新绑定的键在重新编译生效前拿不到值。
4. 契约 v4 是破坏性变更：AutoService / AutoCluster / CarPlay 三个 APK 必须同版本一起更新，不能单独升一个。
