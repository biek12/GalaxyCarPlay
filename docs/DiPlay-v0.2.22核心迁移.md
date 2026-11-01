# DiPlay v0.2.22核心迁移

## 范围与来源

2026-10-31，按用户后续要求解决三方候选冲突并继续升级，优先保留本项目适配，同时整合上游新特性。此前AutoService/Cluster工作区已提交为`3cc69f0`，体验修复独立提交为`c4d8db7`、`beb19cb`，只读候选工具为`f7f782d`。本轮不连接、不安装、不跑Lint、不推远端。

| 输入 | 固定版本 |
| --- | --- |
| 旧官方基线 | v0.2.21 / `49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2` |
| 本地适配输入 | `beb19cb1416a5835fc5d6c539efda1c8e0723e55`，含已提交的微信/OEM/尺寸接缝 |
| 新官方基线 | [v0.2.22发布提交](https://github.com/shihabal3amri/DiPlay/tree/28d6002ba00320f0ba745212160b08759e4f60b9)，完整SHA `28d6002ba00320f0ba745212160b08759e4f60b9` |

未使用main HEAD。旧锁、旧源码和补丁保留在Git；旧/本地/新逐文件SHA256及初始合并分类见[三方清单](../tools/upstream/reviews/v0.2.22-three-way.json)。1420份文件：1131未变、21仅适配、248仅上游、9自动合并、11文本冲突；产品galaxy专属文件不参加整包覆盖。最终人工处理结果见[解决登记](../tools/upstream/reviews/v0.2.22-resolution.json)，当前新基线全量摘要见[核心锁](../tools/upstream/baseline-lock.json)，与该基线的全部实际适配差异见[补丁登记](../tools/upstream/patches.json)。

## 11份冲突的取舍

| 文件 | 最终实现与理由 |
| --- | --- |
| `AsyncDiagnosticLog.kt` | 保留我方上下文/文件所有权/清理代次及入队前脱敏；在已有后台线程调用`appendNow`，避免新增双层队列。 |
| `SessionLogFile.kt` | 合入上游有界异步写盘；保留自定义归档名、配置头和严格UTF-8字节上限。队列捕获文件、配置、清理/reset代次，清理与新会话不会被旧行重新污染；关闭后拒绝新记录，已接受行按上游有限flush处理。 |
| `DiagnosticRedactor.kt` | 保留我方结构化`CORE_TRACE`、控制字符与原始转储拒绝规则、700字符上限，加入上游UDID格式过滤。纯字母无关键字状态文本走等价快速检查，避免将上游原始正文入队策略带入产品。 |
| `CarPlayHostActivity.kt` | 保留产品进入/装配/原车媒体、固定面板毫米、111→AutoService/HAB接点、产品连接UI和菜单；合入同任务屏幕转发、偶数尺寸、触摸释放、蓝牙启用立即重试、首帧诊断、上游导航焦点参数。删除自动合并重新带入的内嵌`CarPlayBackgroundSession`，只保留galaxy现有抽提类。 |
| `ClusterMusicState.kt` | 保留产品歌词、进度时钟与位图所有权/回收；兼容上游借用封面在元数据补发时保留的行为。产品自有封面仍按同一曲目校验，切歌不沿用已回收旧图。 |
| `ClusterMusicView.kt` | 合入上游封面/曲目信息过渡、背景取色等绘制；保留产品常驻歌词、进度与完整信息。不采用上游8秒后隐藏说明文字的行为，避免遮断我方歌词能力；交叉渐变不重复绘制旧歌词。 |
| `AndroidMediaSink.kt` | 保留电话通道与原厂输入源、车型属性/路由/焦点注入、多流通信模式持有；合入导航提示焦点。最终释放改为撤销本App模式请求，避免把别的进程临时模式固定下来，保留最后一个流释放才撤销的所有权规则。 |
| `MicrophoneUplink.kt` | 保留已核验的原厂候选输入源、效果器/路由、捕获开始与释放回调；合入上游静音/竞争诊断，记录实际选中的源。既有有限候选顺序不被上游通用源覆盖，诊断不保存语音或编码包。 |
| `CarPlayRuntimeConfig.kt` | 产品冻结P2P信道集合与上游existingWifiInterface字段共存，继续防止可变集合污染会话。 |
| `galaxy/mobile/build.gradle.kts` | 保留唯一galaxy/mobile应用壳、显示名CarPlay、`com.ecarx.carplay`、产品版本属性及既有签名路径。仅更新独立核心版本属性，不继承上游应用身份/发布序号。 |
| `TelephonyMicrophoneTest.kt` | 保留我方三类App上行、效果器和多流释放测试；合入上游外部进程模式测试，确认不把其他App模式固化为本App请求。 |

## 接点与上游特性

- 电话路径保留在[产品音频策略:16](../galaxy/shared/src/main/java/com/shilapi/xcertplay/media/L7FactoryAudioProfile.kt#L16)、[上行映射:600](../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L600)、[原厂源选择:128](../shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt#L128)。协议流ID、编解码与上行配置不被改写。
- 上游同任务屏幕转发保留[CarPlayScreen:23](../common/src/main/java/com/shilapi/xcertplay/CarPlayScreen.kt#L23)，并在[Galaxy显示设置:15](../galaxy/common/src/main/java/com/shilapi/xcertplay/L7DisplaySettings.kt#L15)接入实际开关；上游导航焦点与通话采样率选项接入Galaxy音频设置，原有默认值与配置键兼容。上游触摸槽位、时钟修正、协议边界及USB权限/重试改动随固定发布一起迁入。
- 保留[GalaxyHostAdapter:46](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyHostAdapter.kt#L46)实际输出接点；上游FX11普通Display/DIM实现留在核心，但不替换L6的HAB→QNX。AutoService/Cluster/公共契约没有被上游壳覆盖，完整地图、音乐融合和主题仍归原独立应用。
- 上游ECARX轮速实现的参数来自Monjaro，原逻辑只要SDK存在就启用，并认为车机无GPS。本项目不采用该自动判定：Galaxy 的车速/挡位改由[AutoService v3按需订阅](AutoService车速订阅-20261031.md)取得（`displaySpeed`/`gear`），位置仍走原有Android provider；[配置接点:223](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L223) 只让无产品注入的通用宿主沿用上游自动判定，上游实现及其独立测试继续存在。L6的行驶车速、`km/h`单位与有无GPS均未实车核验，不写成已通过。
- 抽提的[后台会话:11](../galaxy/common/src/main/java/com/shilapi/xcertplay/CarPlayBackgroundSession.kt#L11)增加上游热点就绪快照，并在新控制器/清理时重置，继续沿用本项目所有权规则。
- 日志接点：[有界文件写入:46](../common/src/main/java/com/shilapi/xcertplay/SessionLogFile.kt#L46)、[已脱敏队列:28](../common/src/main/java/com/shilapi/xcertplay/AsyncDiagnosticLog.kt#L28)、[敏感内容过滤:31](../common/src/main/java/com/shilapi/xcertplay/DiagnosticRedactor.kt#L31)。

## 可复现检查

缓存按[工程基础](工程基础.md)准备。上游新增API23测试使用固定Robolectric镜像，已有本机缓存时不下载；需要时单独运行带SHA512校验的本机准备命令：

```sh
python3 scripts/verify/lib/prepare_sdk23_cache.py
```

正常验证单命令：

```sh
bash scripts/verify_upstream_core.sh
```

运行离线产品身份/差异登记检查、shared/common完整单元回归，以及CarPlay/AutoService/AutoCluster的assembleDebug。不运行Lint或设备命令。报告路径为`.work/logs/verify-upstream-core.log`和各模块`build/reports/tests/testDebugUnitTest`。离线init脚本仅作用于测试JVM，不进入APK；缺省缓存根也已修正为Gradle当前工程目录。

首轮真实失败及修正：音频反射测试补齐非合成构造器产品参数；补发封面/背景色恢复借用位图语义；常驻歌词与MANUAL默认等产品差异调整对应断言；纯字母诊断快速路径保留脱敏边界并满足上游调用延迟检查；新增直接文件队列清理/reset与SDK存在时不改写产品定位合同的回归。缺失API23镜像通过固定摘要准备，不降级测试或联网临时解析。

最终验证结果与APK摘要在完成后追加。本轮不把编译或离线测试当作指定L6实车验收。

## 仍需实车定位

微信通话/按住说话的对方实听，原车封面URI读取及歌词消费，和iPhone 16 Pro Max / iOS 27.2的CarPlay内部“智能显示缩放”开关回退仍分别待验。已知原厂DIM材料存在歌词空转发，本次没有修改原厂服务；固定面板尺寸接缝是候选修复，未证明缩放根因。核心升级并不自动解决这些运行问题。原车仪表图传告警、HAB稳定性及8155实测性能仍沿原验收记录继续。
