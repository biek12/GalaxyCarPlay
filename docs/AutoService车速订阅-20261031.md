# AutoService 车速订阅接入 iPhone 轮速（$PASCD）

2026-10-31。本轮把 CarPlay 发给 iPhone 的车速/挡位改为经 `AutoService` 的车辆信号订阅取得，不再由各 App 自行实现 ADB/Root 或反射厂商 SDK。这是合并上游 v0.2.22 时暴露的问题：上游新增的轮速实现按另一车型（Monjaro/KX11）的假设编写，且以“装了 ECARX SDK”代替车型核验。

## 1. 上游 v0.2.22 到底改了什么（只读核对）

引入者是单提交 `459e049be3bcc4a1afb1b04b4c4c534561bc6163`（"Send the ECARX wheel speed and gear to the iPhone ($PASCD)"）。

| 事实 | 位置 |
| --- | --- |
| 新增 `EcarxWheelSpeed`：反射 `com.ecarx.xui.adaptapi.car.*`，`SENSOR_TYPE_CAR_SPEED=1048832`、`GEAR=2097664`，250ms 轮询，**原始值直接当 m/s** | [EcarxWheelSpeed.kt:25-47](https://github.com/shihabal3amri/DiPlay/blob/28d6002ba00320f0ba745212160b08759e4f60b9/common/src/main/java/com/shilapi/xcertplay/EcarxWheelSpeed.kt#L25-L47)、`:37`（`MAX_METERS_PER_SECOND=90.0`） |
| 新增 `EcarxWheelSpeed.NoPosition`：`start()` 返回 false、`latestNmea()` 返回 null，用来**顶替位置源** | 同上 `:55-61` |
| `vehicleSpeedEnabled` 由“BYD 开关”改为 `|| EcarxWheelSpeed.present()`；`locationProvider` 增“SDK 在但开关未开 → 用 NoPosition”分支 | 上游 `CarPlayHostActivity.kt:213-214`、`:5638-5655`；本地合并后 [CarPlayHostActivity.kt:223](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L223) |
| `$PASCD` 编码、`speedRequested` 语义、`VehicleSpeedSource` 接口 **v0.2.21 已存在**，v0.2.22 只加诊断（`status()`、一分钟无车速时打印原因） | [VehicleSpeedNmea.kt:23](../shared/src/main/java/com/shilapi/xcertplay/transport/VehicleSpeedNmea.kt#L23)、`:107` |

即：v0.2.22 的新增不是“能不能发 PASCD”，而是“**自动开启并放弃 GPS**”。这两条都不适用于未做 L6 核验的情况：本仓库没有任何材料证明 L6 车机无 GPS 接收机，也没有证明该反射读出的原始值是 m/s。

`$PASCD` 的实际触发链（与车型无关，保留原样）：

1. identification 里 `vehicleSpeedEnabled → void(20)`，向 iPhone 声明能力 20 —— [Iap2IdentificationClient.kt:60](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt#L60)、`:387`。
2. iPhone 看到能力位后发 `0xFFFA StartLocationInformation`，参数含 `VEHICLE_SPEED_DATA=4` —— [Iap2LocationClient.kt:226](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt#L226)。
3. 收到后置 `speedRequested` 并启动车速源 —— [VehicleSpeedNmea.kt:72](../shared/src/main/java/com/shilapi/xcertplay/transport/VehicleSpeedNmea.kt#L72)。
4. iAP2 控制循环每秒调一次 `latestNmea()`，把 GGA/RMC 与 `$PASCD` 拼成一条 —— [Iap2LocationClient.kt:178](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LocationClient.kt#L178)、[VehicleSpeedNmea.kt:88](../shared/src/main/java/com/shilapi/xcertplay/transport/VehicleSpeedNmea.kt#L88)。

## 2. 本项目的取舍

| 问题 | 上游做法 | 本项目做法 |
| --- | --- | --- |
| 车速从哪来 | App 内反射 ECARX `ISensor` | **经 `AutoService` 的 v3 车辆信号订阅**，只订阅 `displaySpeed` 与 `gear` |
| 单位/量程依据 | 按 Monjaro 假定原始值即 m/s，上限 90 | 用本车属性目录的声明单位 `km/h`，客户端再次校验 `unit=="km/h"` 与 `0..300`，换算 `/3.6` |
| 位置来源 | 用 `NoPosition` 顶掉位置 | **保留** `AndroidCarPlayLocationProvider`（[Galaxy 已核验的 L6 定位路径](../shared/src/main/java/com/shilapi/xcertplay/location/AndroidCarPlayLocationProvider.kt#L29)，按可用 provider 选择，不断言车机有无 GPS） |
| 何时启用 | SDK 存在即启用 | 产品开关（默认开）+ “向 iPhone 报告位置”总开关；改开关改变 iAP2 能力位，**下次连接生效** |

这样做的直接收益正是本轮要求：CarPlay 进程不再需要 ADB、Root 或 ECARX SDK 认证，也不需要在每个 App 里重复实现同一套读取；采集口径与仪表/后续应用共用 `AutoService` 的同一份目录与新鲜度。

## 3. 契约 v3

`AutoContract.VERSION` 由 2 升到 3（[AutoContract.kt:7](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/AutoContract.kt#L7)）。

- [IAutoService.aidl:27](../galaxy/contracts/src/main/aidl/com/galaxy/auto/contract/IAutoService.aidl#L27) `void setVehicleFeed(IBinder owner, in String[] keys);` —— 空数组＝取消订阅。
- [IAutoCallback.aidl:10](../galaxy/contracts/src/main/aidl/com/galaxy/auto/contract/IAutoCallback.aidl#L10) `void onVehicleFeed(in Bundle values);` —— oneway，每轮一次。
- 键是属性目录别名，最多 32 个，格式 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`（`AutoContract.MAX_FEED_KEYS` / `FEED_KEY` / `feedKeysValid`，[AutoContract.kt:21](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/AutoContract.kt#L21)）。
- 本轮常量：`SIGNAL_DISPLAY_SPEED="displaySpeed"`、`SIGNAL_GEAR="gear"`。

feed 的形状（与快照里的 `vehicle` 子 Bundle 单信号同构）：

```
Bundle {
  "displaySpeed" -> Bundle(key,value,unit,source,propertyId,receivedAt,epoch,status,maxAgeMillis)
  "gear"         -> Bundle(同上)
  "at"       -> Long  // 本轮采集的 elapsedRealtime
  "sequence" -> Long  // 只保证单调递增，可跳号（见 §7）
}
```

**键缺失＝本轮无值**，绝不用 0 或默认状态填充；`status` 只报 VALID/STALE/UNAVAILABLE/REJECTED 的真实值，STALE 不会伪装成 VALID（[VehicleSignal.kt:20](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/VehicleSignal.kt#L20)、`parse` 在 `:33`）。

## 4. 实现映射

| 层 | 文件与行为 |
| --- | --- |
| 契约数据 | [VehicleSignal.kt:22](../galaxy/contracts/src/main/java/com/galaxy/auto/contract/VehicleSignal.kt#L22) `bundle()` 增写 `maxAgeMillis`；`:33` 新增 `parse()`，缺键/非法 status 返回 null |
| 服务端校验 | [VehicleFeed.kt:23](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleFeed.kt#L23) `validate()` 按**当前车型目录**校验，未知键整体拒绝（`unknown_signal:<key>`），去重后字典序稳定；`:43` `select()` 只取订阅键并 `deepCopy()`（`Bundle(Bundle)` 只是浅拷贝，直接用会与快照共享子 Bundle） |
| 租约 | [ClientRegistry.kt:10](../galaxy/service/src/main/java/com/ecarx/autoservice/ClientRegistry.kt#L10) `Lease.feed` 随租约消失 |
| 入口 | [AutoService.kt:225](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L225) `setVehicleFeed`：签名/包名已在 `attach` 阶段校验，这里只做键校验与登记；订阅当下补发一次当前值 |
| 采集回灌 | [VehicleCollector.kt:87](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCollector.kt#L87) 每轮 `feed(bundle, now)`，**在每个 poll 之后、deliver 节流之前**调用，因此节拍稳定在 250ms，不受“变则发”影响 |
| 发布 | [AutoService.kt:407](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L407) `publishFeed`：服务暂停/停止/车辆数据关闭/无订阅者时零开销返回；`:419` `sendFeed` 用 `select` 裁剪后投递，空结果不回调 |
| 清空 | [AutoService.kt:290](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L290) 停止服务时清空所有租约订阅；`:314` 切换车型时同样清空（旧键可能不属于新目录） |
| 客户端 API | [AutoClient.kt:181](../galaxy/client/src/main/java/com/galaxy/auto/client/AutoClient.kt#L181) `setVehicleFeed(Collection<String>)`：主线程校验、去重排序、非法键直接失败；`:88` 重连后在同一批补发里**重放订阅**，断线恢复不会静默失去订阅；`:226` `close()` 清空订阅意图 |
| Galaxy 车速源 | [GalaxyVehicleSpeed.kt:29](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyVehicleSpeed.kt#L29) 以 `OBSERVER` 角色建第二个 `AutoClient`（与 MAP 的投屏客户端互不影响），只订阅两项 |
| 产品接缝 | [GalaxyHostAdapter.kt:82](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyHostAdapter.kt#L82) 覆写 `vehicleSpeedToIphone()` / `vehicleSpeedSource()`；核心接缝在 [CarPlayHostProduct.kt:75](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostProduct.kt#L75)、[CarPlayHostActivity.kt:223](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L223) 与 `:5701` |
| 设置 | [GalaxyVehicleOutputSettings.kt:13](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyVehicleOutputSettings.kt#L13) 默认开；设置页行在 [L7VehicleSettings.kt:31](../galaxy/common/src/main/java/com/shilapi/xcertplay/L7VehicleSettings.kt#L31)，文案在 `values/galaxy_vehicle_speed.xml` 与 `values-en/` |

## 5. 数据判定（客户端，[GalaxyVehicleSpeed.kt:90](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyVehicleSpeed.kt#L90)）

- **只接受 VALID 且仍新鲜**：`status==VALID` 之外一律不用；再用字段自带 `maxAgeMillis` 重算一次（发送端 VALID、消费端已过期时就地丢），不自编超时、不用“收到过就有效”。
- **单位必须 `km/h`**：`m/s`、`KM/H`、空串都不换算不猜。
- **量程 0..300 km/h**：超量程与 `NaN`/非数字整轮丢弃，不截断成边界值（边界值本身可用）。
- **挡位严格映射**：目录侧已把 `0/1/2/3` 映射为 `P/R/N/D`（[VehicleCatalog.kt:33](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCatalog.kt#L33)），客户端只认这四个字母；其它值把挡位标为未知并作废该轮，绝不把旧挡位配上新样本。
- **样本队列有界 32**：iAP2 读取端（1 Hz）停顿后丢弃最旧样本，不无限增长；`drain()` 一次性取走。

## 6. 失败与生命周期

| 情形 | 行为 |
| --- | --- |
| AutoService 未运行/被用户关闭/车辆数据开关关闭 | 无 feed → 无 `$PASCD`；位置报文照常（`latestNmea` 是“有车速才追加”） |
| 断线重连 | `AutoClient` 在 `attach/setActive` 之后重放订阅键 |
| 服务暂停/停止、切换车型 | 服务端清空所有租约订阅，不再回调 |
| 值过期/状态非 VALID/未知挡位 | 该轮不产生样本；不携带陈旧挡位 |
| 进程内重复 start/stop | `AutoClient` 关闭幂等，`GalaxyVehicleSpeed` 的 start/stop 全部投递到主线程串行执行 |
| 一分钟没有车速 | 沿用上游诊断：`VehicleSpeedLocationProvider` 把 `status()` 拼进日志（[VehicleSpeedNmea.kt:107](../shared/src/main/java/com/shilapi/xcertplay/transport/VehicleSpeedNmea.kt#L107)） |

## 7. 已知边界

1. **`sequence` 只保证单调，可能跳号**：`feedSequence` 是轮次计数器，某一轮没有可用值（`select` 为空）时也会推进。客户端目前不依赖 `sequence` 判丢包；若将来要用，需先改语义或改计数器位置。
2. **未知键的拒绝到不了调用方**：`setVehicleFeed` 的键校验在 `enqueue{}` 内执行，异常被转成服务状态串（`request:IllegalArgumentException`），Binder 调用本身正常返回。因此客户端只在两处兜底：`AutoClient.setVehicleFeed` 先用 `feedKeysValid` 拒绝格式非法键；键名本身是契约常量，不随用户输入变化。
3. **`galaxy/client` 没有测试源集**：`AutoClient` 的订阅存储/重放目前只有静态核对，无单测。契约（键格式/版本/信号往返）与判定逻辑分别由 `:auto-contracts`、`:service`、`:common` 覆盖。
4. **`Bundle.deepCopy()`** 依赖 API 26+（本项目 minSdk 29）。
5. **一批样本只带一个挡位**：`$PASCD` 每行只有一个挡位字段，若 1 秒窗口内挡位发生切换，该批样本按最新已知挡位发出（与上游格式一致）；挡位变为未知时整轮作废，不用旧挡位顶替。

## 8. 验证

离线，不含设备操作（与 `scripts/verify_auto_platform.sh` 同一组任务，已把本轮新增测试类加入该脚本）：

```bash
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/robolectric-offline.init.gradle \
  :auto-contracts:testDebugUnitTest :service:testDebugUnitTest \
  :common:testDebugUnitTest :mobile:assembleDebug :service:assembleDebug :cluster:assembleDebug
```

2026-10-31 12:04–12:30 实跑结果（JDK 21.0.11，离线缓存，`--continue` 一次跑完全部任务）：

| 任务 | 结果 |
| --- | --- |
| `:auto-contracts:testDebugUnitTest` | 10 项全通过（`AutoContractTest` 5 项，含 v3 版本/键格式/信号新鲜度往返） |
| `:service:testDebugUnitTest` | `VehicleFeedTest` 7 项通过；其余全通过，仅 `VehicleEventsTest`(3) 与 `PlatformExecutionTest`(1) 失败 |
| `:common:testDebugUnitTest` | 1892 项中 1891 通过；`GalaxyVehicleSpeedTest` 9、`GalaxyHostAdapterTest` 4、`CarPlayLocationSelectionTest` 4 全通过 |
| `:mobile:assembleDebug` / `:service:assembleDebug` / `:cluster:assembleDebug` | 三个 APK 均产出（61,988,170 / 14,071,337 / 3,576,220 字节，12:30） |

上表两处失败与本轮无关，均已单独核实：

- `VehicleEventsTest`(3) 与 `PlatformExecutionTest`(1)：**把本轮 `galaxy/service` 改动整体回退后，同环境复跑仍然同样失败**（`VehicleEventsTest` 3/3 失败），属该 Robolectric/JDK 组合下的既有环境问题（`EcarxVehicleSource` 的连接桩与 loopback socket），不是订阅实现引入。
- `:common` 唯一失败项 `CarPlayHostSettingsTest.productKeepsManualAsDefaultBeforeAndroid10` 是上一轮未提交用例自身的两处错误：断言前没有清掉 `setUp` 写入的 `WIFI_P2P` 偏好、以及按整页文本找 `LocalOnlyHotspot`（实际上它出现在 Android 9 兼容说明里，选项本身已归一为 MANUAL）。已就地修正，单类复跑 70/70 通过。

其余未跑：Lint（本轮明确不跑）、真机/模拟器安装、任何设备操作。

新增/更新用例：

- `:auto-contracts` `AutoContractTest`：`VERSION==3`、订阅键格式与上限、`VehicleSignal` 自带新鲜度往返。
- `:service` `VehicleFeedTest`（7 例）：键校验/未知键拒绝/去重排序/空即取消/只取订阅键/深拷贝/缺值不填默认。
- `:common` `GalaxyVehicleSpeedTest`（9 例）：km/h→m/s、四挡映射、非 VALID/错单位/超量程/非数字丢弃、发送 VALID 但已过期仍丢、未知挡位不配旧样本、队列有界、设置默认与读写。
- `:common` `GalaxyHostAdapterTest#productSpeedComesFromItsOwnSettingNotAnotherModelsSdk`：在 `EcarxWheelSpeed.present()` 恒真的 shadow 下，关掉产品开关后 `vehicleSpeedEnabled` 必须为 false —— 证明不再由“SDK 存在”推导，且 `locationInformationEnabled` 两种状态都为 true。

## 9. 尚未验证（不得写成通过）

- **行驶中的非零车速**：现场只实读过 P 挡 + 0 km/h（[现场修复:19](AutoService车辆读取与投屏现场修复-20261030.md#L19)，[当前跑通状态:19](当前跑通状态-20261030.md#L19) 注明“仅证明当时状态，未逐挡、逐模式或行驶对照”）。R/N/D 与行驶值从未采集。
- **单位**：`displaySpeed → SIVehSpdIndcd(557874774)`、`km/h` 来自原厂符号名与目录声明（[车辆属性目录:9](AutoService车辆属性目录.md#L9)、[VehicleCatalog.kt:31](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/VehicleCatalog.kt#L31)），不是实测量程。
- **是仪表显示车速而非真实车速**：字段名 `Indcd`(indicated) 与需求（[多应用架构规划:76](多应用架构规划.md#L76)）都要求区分二者；本地无偏差实测。别车型（FX11，ID `557874773`）材料也只是同结论（[FX11_SPEED_AND_OVERLAY_V24.md:5](../references/upstream/DiPlay-DisplayAuto/docs/FX11_SPEED_AND_OVERLAY_V24.md#L5)）。
- **iPhone 侧是否真的采用 `$PASCD`**：需要 iPhone + CarPlay 连接才能验证（隧道/地库丢星时的表现才算有效对照）。
- **L6 是否有 GPS**：本轮无结论。代码只是“哪条 provider 可用用哪条”，未断言有无。
- 未安装、未连接车辆；本轮不跑 Lint。

## 10. 发布与回滚

- 契约 v2→v3 是**破坏性**变更：CarPlay / AutoService / AutoCluster 三个 APK 必须同版本一起更新。旧客户端连新服务会得到 `protocol_version_mismatch`（`AutoClient.connect()` 明确抛错），表现为“没有车速与订阅数据”，不会静默用错协议。
- 回滚：`AutoContract.VERSION` 与两个 AIDL 方法一起回退，三个 APK 同步回退；产品侧只需把“向 iPhone 提供车速与挡位”关掉即可在旧核心上退出该能力（下次连接生效）。