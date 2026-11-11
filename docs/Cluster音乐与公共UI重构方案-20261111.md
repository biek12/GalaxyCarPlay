# AutoCluster 音乐与界面重构方案

2026-11-11。紧凑头部已由用户选定；本稿的架构、导航与反馈呈现提交用户确认后实施。没有继续连接车辆、安装或启动模拟器。工作区已有的双模式合同、Hook 和界面候选不视为本稿已获确认。

## 1. 固定媒体入口

建议链路：CarPlay 原始 NowPlaying／原车媒体适配器 → AutoService 当前媒体仓库 → AutoCluster 可视媒体与歌词时间轴 → 遮罩或原厂替换适配器。AutoService 是固定读取点，不能让 AutoCluster 直接耦合某个音乐 App 或监听 CarPlay 私有缓存。

- CarPlay 保留原车媒体注册、音频、按键及 iPhone 原始歌词透传；不拿网络歌词改写原厂原始上报。
- 原车音乐由 AutoService 的车型媒体适配器采集，按实际媒体中心焦点与播放会话确定当前源。暂停不立即被其他来源抢占；切源、断开和失焦产生新代次。不能把 CarPlay 自己注册的 Android MediaSession 回采为另一音乐源，形成循环。
- AutoCluster 统一消费版本化媒体快照，负责可视歌词查询、缓存、四行选取和布局。服务端歌词 provider 根据来源与真实歌曲 ID 查询；客户端只有一个请求入口，不自行散落各平台接口。
- 原厂音乐替换也持有独立歌词需求，不依赖完整遮罩／旧仪表投屏开关；两个出口共用一次查询和时间轴，无可视需求即停止查询。保持现有成功结果有界缓存、过期和断网有限重试。

建议快照：schemaVersion、sourceKind、sourceApp、sourcePackage（可空）、sourceTrackId（可空）、trackIdentity、sourceGeneration、revision、title、artist、album、durationMs、positionMs、sampledAtElapsedMs、receivedAtElapsedMs、playing、playbackRate、artworkRef/version、rawLyrics、freshness。不同设备的单调时钟不可直接比较；QNX 接收端使用接收时刻与租约维护新鲜度。封面和歌词异步结果必须同时匹配来源代次与曲目身份；进度暂停冻结，seek 重定位，旧请求不得回写新曲。

## 2. 来源 App 和歌曲 ID：实际证据

| 接点 | 当前事实 | 本稿变更 |
| --- | --- | --- |
| `shared/.../media/CarPlayPlaybackStatus.kt` 的 `CarPlayNowPlaying`、`acceptUpdate()` | 已接收 `sourceApp`；当前模型没有服务商歌曲 ID | 保留原字段，来源缺失标未知，不把 App 显示名当唯一包名 |
| `galaxy/common/.../GalaxyAutoServiceBinding.kt` 的 `stopMusic` | 发布曲目、进度与歌词，未发布 sourceApp；track 混入 artworkTransferId | 增补来源；封面传输号不作为稳定歌曲 ID |
| `galaxy/contracts/.../AutoContract.kt` 的 `sanitizeMedia()` | 白名单过滤快照 | 字段必须同步升级 sanitize、客户端与消费者，不能只在发布端塞字段 |
| `galaxy/service/.../AutoService.kt` 的 `publishMedia()` | 只接受 MAP 角色（CarPlay），尚无原车音乐仲裁 | 服务内部增加原车源适配与仲裁，不放开任意 App 发布 |
| `GalaxyClusterMusicBinding.kt` | 查询与缓存仍在 CarPlay，需求由投屏驱动 | 迁出可视歌词职责至 AutoCluster；保留原始媒体透传 |

歌词查询顺序建议：来源已提供的完整带时间歌词 → 已核验 provider + 真实 sourceTrackId → 来源 + 曲名/歌手/专辑/时长匹配。没有 ID 仍可匹配，但返回 provider、providerTrackId、匹配方式与置信状态；不得把搜索首条当作确定同曲。当前没有证明 iPhone 会发送各 App 的服务商 ID，不承诺此字段必定可得。各 provider 的实际接口、授权和可用性在接入时逐项核验，本稿没有声称已打通。

原车媒体的准确回调、包名、焦点字段、歌词／歌曲 ID 可用性仍须沿本车媒体中心 SDK 与原厂 App 材料追踪；现有 CarPlay 的 registerMusic 是发布入口，不能当作全局读取入口。此项属于实现前必须闭合的源码研究，不向用户索要技术证明。

## 3. 四行与长句

采用80px封面紧凑头部、当前句第二行，上1／下2、短进度条。只有当前句超出可用宽度时水平滚动；测量实际字体宽度，不按字符数判断。建议停留800ms后，以约28px/s移动至句尾并停留；句内时间不足时允许显示未滚完部分，切句立即复位，不加速追赶。暂停冻结，seek／切源复位；其他三行保持静态裁剪。循环与速度属于待确认视觉行为，原型先展示单次滚动。

Kanzi 与 Android 共享滚动状态计算（文本宽、容器宽、播放时钟、句开始／结束），各自执行裁剪和平移；只在活动长句期间请求动画帧，不在每帧重建文本或 Texture。无完整歌词显示可用当前句；无歌词保留曲目信息、封面、进度与明确空态。

## 4. 封面与写入权限

事实：`GalaxyL6OemArtwork.flush()` 已经通过 AutoClient.executeShell(ROOT) 写共享目录。把相同命令再放到 AutoService 并不能自动修复。AutoService 本来已有 publishArtwork → 有界校验／串行缓存 → ArtworkProvider 的 Android 消费链。

上一轮本地留存只读结果 `optimization-cover-paths.json`：Android `/data/vendor/nfs/shared/images` 属主 system:system、0700；`optimization-qnx-cover-paths.json`：QNX 有 `/shared/images`（default:default、0700），没有 `/images`。当前 `GalaxyL6OemArtwork.IMAGE` 回传 `/images/galaxy-carplay-artwork.jpg`。这些记录说明普通 App 无目录访问权限且路径不吻合；不能只据此断言 UID0 一定能写，仍须分别核验挂载只读、NFS身份映射/root squash与SELinux拒绝。

建议 AutoService 提供语义化 publishArtwork／资源租约，接收有界 FD、解码格式、曲目代次与摘要；自身私有目录原子落盘。跨 QNX 复用已经核验的 `/data/vendor/nfs/nfs_ota` ↔ `/otaupdate` 自有暂存通路，再在 QNX 自有目录发布，或交接有界 RGBA 给 Hook。最终以 Kanzi 实际读取和 Texture 提交为准；原厂 DIM path消费者是否接受该自有路径另验。禁止回传“写入成功”即视为封面显示成功。

权限分层：普通 Android 缓存／Provider 不需存储广泛授权；受保护共享目录需有效执行身份与对应挂载／SELinux权限；QNX 落盘需 QNX 的目录访问权。推荐集中写入业务，不对 CarPlay／AutoCluster 提供任意 chmod、目录授权或任意目标路径。Root 关闭时明确反馈基础能力关闭，遵守既有选择。资源仅封面变化时转换，旧代次拒绝，文件有界、原子提交，恢复时释放自有资源。

## 5. AutoCluster 独立 App 重构（待确认）

| 模块 | 职责 |
| --- | --- |
| AutoService MediaRepository／原车适配器 | 当前源、原始媒体、封面资源、版本化订阅 |
| AutoCluster VisualMusicSession | 按需歌词、匹配状态、缓存和时间轴 |
| AutoCluster DisplayPlan | 遮罩／原厂增强、背景来源、音乐与日夜配置；草稿与已应用分开 |
| AutoService ElementSession | 身份、部署、租约、应用／撤销回执；Hook协调音乐和背景 |
| Surface／Kanzi适配器 | 共用视觉模型，各自绘制与恢复；不把Android绘制API放进QNX |
| auto-ui（独立工程任务） | 文本、列表、按钮、弹窗、选择切换、开关等纯UI组件；系统设置跳转不属于UI组件 |

背景独立选择：原厂、本地图片、CarPlay完整地图、本机地图。保留完整遮罩、旧主题和编辑器。地图等待来源／首帧／中断分别反馈，不暗换静态图片。本机地图生产Surface仍待源码闭合，不将空方法当作已实现。

AutoCluster 是独立 App，提议主导航“仪表／主题／设置”。仪表页只有当前运行状态及“原厂增强／自定义仪表”两个明确入口；进入原厂增强后仅配置音乐和背景，自定义主题的编辑和组件库属于主题分区。原型中的旧五入口设计作废。

音乐卡片属于仪表显示内容；仅在 AutoCluster 的音乐配置页预览，其他页面不常驻卡片、不把它作为 App 播放器。背景配置页只处理背景，应用与恢复通过当前流程呈现。AutoService 的 Wi-Fi、ADB、Root 设置不出现在 AutoCluster 原型中。

逐项确认顺序：①独立 App 的导航和仪表首页 ②原厂增强页 ③音乐配置及仪表卡片预览 ④背景配置 ⑤应用／恢复反馈。主题编辑器等全部细节另页展开，当前原型明确标注未展开部分，不将说明占位视为设计完成。

## 6. 独立任务与进度

- AutoService Wi-Fi 帮助单独实现与验证，见[连接帮助记录](AutoService原生WiFi连接帮助-20261111.md)。原生跳转已从auto-ui移回service/settings。
- 公共 UI 是独立组件抽取任务，见[公共UI抽取清单](公共UI组件抽取-20261111.md)。现有色阶／文字度量／基础控件只是首轮候选，列表、弹窗、选择切换、开关没有完成抽取，不把功能SDK算作完成量。
- AutoCluster 首版混合原型已作废；新稿见[独立App原型](design/cluster-refactor-20261111/index.html)及[修订范围](design/cluster-refactor-20261111/REVIEW.md)。没有新增Android业务页或设备部署。

## 7. 验收与实施批次

确认架构／原型后依次：公共合同与媒体仲裁→歌词迁移→封面资源闭环→背景和音乐双出口→App全页面。每批保留可复现验证。重点场景：CarPlay／本机音乐切换、同名不同源、暂停／seek、快速换曲旧封面回执、无歌词／断网、长句、关闭Root、单项撤销、换根、租约过期与异常恢复。性能分别记录查询数、纹理更新数和CPU/内存/线程/FD；离线通过不替代QNX显示与原厂恢复实测。
