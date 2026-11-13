# A4：AutoService主体结构收官（单一完整任务）

2026-11-13。用户明确要求一次下发大任务，同时保留此前小任务的文件、方法、顺序和验收细度。本任务一次完成AutoService剩余主体拆分，不再每迁一个类就等下一张任务单。内部C1–C4是同一任务的检查点，允许分别提交，检查通过后连续推进，整个A4完成才统一交审。

## 1. 起点、终点与执行权

- 工作目录：`F:\GalaxyPlay\wt-autoservice-completion`；分支：`feat/galaxy-autoservice-completion-20261113`。
- 固定业务来源：S3 `665b9f888f3374e43d02efbc4484ab3e68d7cfb5`，已审集成`78feda1e088a1a98ee25f21c762d467a1e65ea09`。开工记录同步任务/工具后的实际HEAD、干净状态；不拿来源提交替代实际开工HEAD。
- 终点：AutoService只持有生命周期状态、原有线程/资源的装配与释放、前台通知、Intent生命周期路由、onBind和dump委托；不再内嵌IAutoService.Stub，不再保存媒体、图片、车辆目录/采集/推送缓存、远程连接状态，不再拼业务快照或处理FD/图像/Shell。
- 沿S1–S3已经审过的ClientRegistry、ServiceCallerIdentity、ServiceRequestQueue、ServiceSnapshots、VehicleFeedDispatcher继续接线，不再造一份同名状态或第二条通道。
- Cursor实施业务代码；Codex已经准备验证脚本、阶段格式集、产物路径和测试套件映射。脚本/环境问题交Codex处理，不让Cursor改工具放行。
- 不连接/安装车辆、不操作HMI、不启模拟器、不执行设备probe、不打包/签名APK、不推远端。仍只离线。没有新车辆协议/显示路线探索，也不引入第三方架构框架、Gradle模块或依赖升级。

### 必读源码依据

1. 根与galaxy AGENTS；Android工程规范；service-cluster两份规则及正文；本任务；同目录33条代码白名单。
2. 基线AutoService的全部字段、binder、onCreate/onStartCommand/onDestroy、publish、dump，以及receiveArtwork/selectMedia/nativeMusic/applyDemand/vehicleKeys/updateRemote。
3. `contracts/.../IAutoService.aidl`和AutoContract；只读，不修改版本、顺序或签名。
4. 已有media.NativeMediaSource、根包KanziMediaPublisher/OemArtworkWriter/ArtworkProvider、oem.ClusterHookController；vehicle的Collector/Inventory/Demand/Feed；remote.RemoteBridge、web.VehicleWebBridge、sentinel.SentinelCoordinator。
5. 本次工具目录`tools/review/galaxy-autoservice-a4-20261113/`的scope.json、snapshot-contract.json；它们固定来源、文件、检查点和快照字段，不能由编码者修改。

开工说明逐组列“旧字段/方法 → 新owner → 线程和必须保留的顺序”，不是重新设计方案。后文已给定owner，不把拆分问题退给用户。

## 2. 精确写范围与保留边界

完整33条路径见 [代码白名单](A4-代码白名单-20261113.txt)，机器版见scope.json。包括AutoService、12个新职责类型、9份行为测试、三份media类迁包的旧/新路径、NativeMediaSource/独立MediaAccessListener、一个现有测试的import、Service Manifest及strings。只stage白名单，不git add .，不reset/clean/stash，不自行切分支或合并。

Manifest只把ArtworkProvider实现从`.ArtworkProvider`迁到`.media.ArtworkProvider`并按固定XML格式整理。authority仍`com.ecarx.autoservice.artwork`，exported/runtime签名校验及`/current`只读合同不变；AutoService、MediaAccessListener的组件名、权限/intent-filter不变。

strings只补已有通知三段文字的资源（平台与仪表服务、AutoService、车辆数据与仪表投射）；两个既有中文结果若迁资源，默认文本仍逐字相同（媒体上传不可用、AutoService 自动运行已关闭）。协议error/stage、Shell/QNX命令、状态与键名不得本地化/改名，不丢已有语言。不新增页面，不改已审UI。

本任务不迁跨App背景内容算法或GKM1字段合同：ClusterBackgroundPng、ClusterBackgroundStore、KzbBackgroundPatch、KanziMediaFrame及其编码测试仍在原处，明确保留为后续跨App内容归属工作，不能把它们搬进hook/oem冒充连接代码。只迁KanziMediaPublisher运输者，不迁/改KanziMediaFrame。旧整屏/主题归档保持原字节，绝不恢复入口/输出，OEM背景/音乐/Hook及CarPlay数据继续保留。

## 3. 确定文件、状态与方法归属

所有新类型根包为`com.ecarx.autoservice`。表中相对包/文件即确定目标；每文件一个顶层类型，专用IO边界可用该类嵌套类型，禁止额外万能Manager/Runtime/Utils。

| 新文件 | 从AutoService迁入的状态/动作 | 必须提供的明确能力 |
| --- | --- | --- |
| ipc/AutoServiceBinder.kt | 整个IAutoService.Stub、uid/enqueue入口委托、同步鉴权/参数净化/排队 | 覆盖原29个AIDL方法；在调用线程捕获UID/包名，向下表具体owner调用 |
| ipc/ServiceClientSessions.kt | attach/detach/setActive、旧场景/策略/预览归档回执、mediaCommand路由 | 借用同一个ClientRegistry；登记/移除/角色处理；PROJECTION_ARCHIVED常量仅留一处 |
| vehicle/ServiceVehicleSession.kt | inventory、collector、discovery/count、catalogKeys/model缓存、inputEpoch/menu计数、applyDemand和S3 dispatcher装配 | model/keys/page/value/select、setFeed、scan、start/stop/clear/recalculateDemand、stats；不重写Collector/Demand/Feed |
| media/ServiceMediaSession.kt | media/navigation/carplayMedia/nativeMedia/visualMedia、nativeArtwork、artworkVersion/Generation、nativeSource/oemArtwork/kanziMedia | publishMedia/Visual/Navigation、selectMedia/nativeMusic/receiveArtwork、prepareOemArtwork、媒体角色消失、native启用、Kanzi输出与分步释放 |
| oem/ClusterElementsSession.kt | clusterImageGeneration、clusterElementRevision、clusterConfigurationStatus、clusterHook；图片配置异步流程 | setActive副作用、configureElements、configureImage、停用意图、更新/装饰快照、published(gen,flags)、closeHook |
| remote/ServiceRemoteSession.kt | webVehicle、remote、remoteGeneration、remoteState、updateRemote、授权/撤销及Web请求/上传异步流程 | update/authorize/disable、request/upload、web可用状态、closeWeb和closeRemote分开 |
| sentinel/ServiceSentinelRequests.kt | configureSentinel/reportSentinelIdle的业务主体与结果组装 | 复用原SentinelCoordinator，不做第二份哨兵状态/电源/流；开启约束与关闭放行保留 |
| platform/foundation/ServiceShellRequests.kt | shellBusy和executeShell排队后的执行/结果组装/迟到回执检查 | 借用原shell worker与同一个shellAccess，保留busy及Lease对象身份判断 |
| platform/foundation/ClientPermissionRequests.kt | requestClientPermissions的授权、overlay处理、ROOT执行、回读与结果 | 复用ClientPermissionPolicy、shell worker与shellAccess；不改策略/目标包/UID规则 |
| ipc/ServiceSnapshotComposer.kt | publish里的Bundle字段组装及sequence | build只读Input → 新Bundle；不启停业务，不登录、不做Shell、不过渡业务状态 |
| ipc/ServicePublication.kt | publish的跨owner顺序与ServiceSnapshots发布 | 仅串接媒体/哨兵/远程 → Composer → OEM → Kanzi → Snapshots，不存另一份业务状态 |
| diagnostics/ServiceDiagnostics.kt | dump的完整分支和文本、缓存诊断拼装 | 只读现有owner；无扫描、无额外采集、无ROOT/HTTP，不改外部脚本使用的前缀 |

既有类迁移：根KanziMediaPublisher、OemArtworkWriter、ArtworkProvider分别搬到media同名文件。只改包/import、需要的中文契约和固定格式，运输、签名、图片/文件/时钟/命令逻辑不改。NativeMediaSource留media原包，把同文件MediaAccessListener移到media/MediaAccessListener.kt，公开类名不变；原监听器的添加/移除、来源优先级与排除包不改。

NativeMediaSource原压缩enabled里的if块存在ktlint不能自动修正的if-else-wrapping：按原短路与语句顺序手动展开，不能关规则、加Suppress洗过关或改业务。已有ClusterElementPipelineTest只更新KanziMediaPublisher import，断言不弱化。

### 依赖和构造（不是另一套状态总线）

- AutoService继续持有main、callbacks/artwork/shell/backgroundImages四个SerialWorker和真实生命周期flags：features、closed、paused、rootReady、platformGeneration、status；原platform/shellAccess及SentinelCoordinator按原时点创建/释放。
- ClientRegistry只有一份，由Service装配并在原时点close；ServiceClientSessions/Vehicle/OEM/其他请求借用同一份。死亡仍是Registry内部main投递 → 外侧main投递 → sessions.detach，不合并投递，不新建注册表。
- owner只收Context、既有Handler/worker、明确的真实类型/函数边界和必要生命周期只读getter。禁止传整个AutoService作为“可读写任意私有状态的Host”、反射字段、Map<String,Any>事件总线、全局可变测试钩子。
- changed回调仍在原线程/时点执行：有些原本直接publish，有些先main.post；不能统一再包一层post或一律同步。构造不得调用changed或启动资源，避免publication/其他owner尚未接好就回调。
- IO测试边界限定为已有实际API：VehicleSession可嵌套CollectorPort（start/stop/close/setDemand/setScan/scanning/stats），默认适配真实Collector；RemoteSession可嵌套WebPort（start/close/request/upload）与CommandPort（start/close/revoke）；MediaSession可嵌套SourcePort（enabled/close）。工厂/适配只隔离真实资源，不另写状态机或协议。Kanzi沿用原write边界，Hook沿用原具名控制/回执边界，Shell/权限用真实execute/ensureRoot方法引用。不要为每个纯getter再造接口。
- 对外只读视图不成为第二份持久状态：缓存/计数/代次只在表中owner更新。S1身份/队列、S2快照通道、S3推送类保持原合同，不把它们改成并行实现。

## 4. Binder全部方法的迁移合同

下表是29个原方法，必须全部接通。`uid`表示先在Binder线程执行ServiceCallerIdentity.requireUid；`enqueue`必须先捕获source再交原ServiceRequestQueue，闭包真正运行时再检查closed。表中“main”表示原直接main.post路径，不能改成会跳过FD/Surface释放的enqueue。

| 方法 | Binder线程先做 | 交给的owner/结果 |
| --- | --- | --- |
| getProtocolVersion | uid | 原AutoContract.VERSION |
| getSnapshot | uid | S2 snapshots.current，仍同一缓存 |
| getVehicleCatalog / getVehicleProperties | 各自uid | vehicle.inventory.page(offset,limit,true/false)，分页/权限保持 |
| getVehicleValue | uid | 原inventory.value(propertyId,areaId,characterOffset) |
| attach | uid、roleValid、MAP包CarPlay；THEME/PREVIEW/ELEMENTS/VISUAL_MUSIC包Cluster | enqueue → sessions；THEME/PREVIEW只回归档态，不登记Lease；其他登记后publish |
| detach | uid | enqueue → require同UID Lease → remove → 重算需求 → 清理对应媒体角色 → publish |
| setActive | uid | enqueue → require Lease → sessions/OEM副作用 → publish；THEME/PREVIEW仍直接返回 |
| configureClusterElements | uid、ClusterDisplayConfiguration.from | enqueue → require ELEMENTS；配置相等不动作；否则OEM代次/版本/ready、写Lease、publish |
| configureClusterImage | uid、Cluster包、safe配置、IMAGE_BACKGROUND、fstat普通有界文件/dup | 原main路径 → OEM图片流程（下节）；不改输入FD所有权 |
| publishMedia | uid、sanitizeMedia | enqueue → MAP Lease → media写CarPlay候选；无原车媒体才select → publish |
| publishVisualMedia | uid、sanitizeMedia | enqueue → VISUAL_MUSIC Lease；track相同才更新可视歌词并publish |
| publishNavigation | uid、sanitizeNavigation | enqueue → MAP Lease → navigation → publish |
| publishArtwork / publishTrackArtwork | 各自uid | media.receiveArtwork(owner,calling,fd,null/track)，保持同步dup/关闭与异步处理 |
| prepareOemArtwork | uid、CarPlay包、普通文件dup，finally关原fd | 原main → 有效Lease/未closed/paused/root守卫 → 原writer；失败artwork_unavailable |
| executeShell | uid、ADMIN签名权限、ROOT/ADB、命令1..8192且无NUL、100..30000ms | enqueue → require Lease → ServiceShellRequests；不提前在Binder线程执行IO |
| mediaCommand | uid、command仅0/1/2 | enqueue → 非MAP调用Lease → 把command快照回送当前MAP callback |
| configureScene | uid | enqueue → require Lease → 原归档态；不保存scene |
| setPreviewSurface | uid | 原main：先surface.release，再closed判断，再require/归档回执；永不输出 |
| setProjectionPolicy | uid、Cluster包 | enqueue → require Lease → 原归档态；不写prefs/尺寸 |
| setVehicleFeed | uid、feedKeysValid、feedIntervalValid及原消息 | enqueue → require Lease → 车型validate → feed/interval/lastFeedAt=0 → demand → 非空replay |
| getVehicleValues | uid、feedKeysValid | 同步车型validate+dispatcher.select；runCatching失败空Bundle；不触发采集 |
| setVehicleScan | uid且必须本进程，原local_only | 原collector.setScan，默认仍关闭，不增加外部入口 |
| requestClientPermissions | uid、去重、ClientPermissionPolicy.allowed、target/user | enqueue → require Lease → ClientPermissionRequests；实际回读后回复 |
| reportSentinelIdle | uid、AutoRecord包 | enqueue → require Lease → 原sentinel.idle(powerSequence) |
| configureSentinel | uid、AutoRecord包及sentinel_client_rejected | enqueue → require Lease → SentinelRequests；开启受原平台条件约束，关闭继续放行 |
| vehicleWebUpload | uid、AutoRecord包、clipId正则、原direction白名单、length范围、dup | enqueue → require Lease → RemoteSession.upload；保留成功/异常的关闭与迟到回执条件 |
| vehicleWebRequest | uid、自身或AutoRecord、action白名单、record-*额外包限制、json长度 | enqueue → require Lease → RemoteSession.request；桥空的原错误，成功main确认Lease后publish再回结果 |

没有AIDL改动，所以编译必须强制AutoServiceBinder实现这29个真实方法，不接受只接一部分的默认空实现。角色/签名校验不能只挪成一段注释；同步拒绝仍在投递前发生，主线程车型/Lease校验仍在原闭包里。

### Shell、权限、Web、哨兵的数值和顺序

- Shell同一个AtomicBoolean busy：compareAndSet失败回shell_busy；成功shell worker执行。执行前`!closed && !paused && automatic`，原transport关闭约束仍在PlatformAccess。输出仍含output/exitCode/timedOut/truncated和原error。先busy=false，再main判断当前仍有同一Lease，最后callbacks回结果。不吞掉旧异常或上传Shell正文。
- 权限仍只允许原RECORD_PACKAGE及允许列表。user=calling/100000；OVERLAY经原AppOps，其他经checkPermission。只对missing权限准备Root，执行原cmd appops/pm grant（10000ms），exitCode/timedOut/error检查后再次回读全部requested。granted/error消息和late Lease过滤原样，不放宽授权、不改变本轮离线设备边界。
- Web action完整集合仍为state/enable/code/confirm/unbind/disable/record-publish/record-poll/record-result/record-telemetry/record-signals/record-live-status。json上限384KiB；上传length 12..268435456，direction为原9项；只拆代码，不新增URL/Shell透传。
- Web请求成功：回调 → main → 非closed且原Lease仍在 → publish → callbacks回结果。上传异常关闭copy、回原“媒体上传不可用”；桥空回原“AutoService 自动运行已关闭”，包括该分支原异常边界，不能顺手增删runCatching。
- 哨兵enabled=true仍要求未paused、features.root/vehicle和automatic；false不受这些开启条件挡住。仍用原Coordinator.configure/idle/platform/accept/demands，不改20%退出/手动关闭、电源轮次或流。

## 5. 媒体、封面与FD的细合同

媒体状态仅MediaSession拥有。原车媒体非null优先于CarPlay候选；原车消失退回CarPlay；不能把网络歌词或可视歌词写进原始媒体。track变化先增加artworkGeneration、清visualMedia、删除cache/artwork.png、增加artworkVersion；原始lyrics非空且lyricTimes长度匹配也清visualMedia。sanitize后receivedAt仍取1..now内sampledAt，否则now。

nativeMusic仍比较当前track与(value ?: carplayMedia).track；相同Bitmap引用不重复生成封面；缩放最长512，若缩放返回原Bitmap则copy出独立owned位图。artwork worker编码、main同generation才AtomicFile提交/版本/publish，finally回收owned；不回收借来的系统Bitmap。

| 入口 | 原FD/对象 | duplicate及异步顺序 |
| --- | --- | --- |
| receiveArtwork | fstat普通文件，dup在try，原fd在finally关闭 | main先closed/expectedTrack/MAP Lease；无效关dup；有效记录generation → artwork worker有界2MiB/尺寸1..4096 → main同ticket才原子提交；失败路径仍关闭，不新增缓存覆盖 |
| prepareOemArtwork | 普通文件检查/dup，finally关原fd | main原平台/Lease守卫；失败关dup并回artwork_unavailable；成功原OemArtworkWriter接管，writer限制/路径/Root结果不改 |
| configureClusterImage | 原输入image没有新增的显式entry-close；只dup | main拒绝/相同配置/投递失败均关闭dup；background worker交原Store.import，完成后关闭；不能统一到另一套FD接收器 |
| vehicleWebUpload | 保持原输入file所有权，只dup copy | 原WebBridge.upload接管copy；异常路径关闭copy。不要因其他入口finally关原fd而机械给本入口加一套不同语义 |
| setPreviewSurface | Surface不是FD | main首先release，服务已关闭也必须释放；不能换成closed跳过整个任务的通用enqueue |

不要以抽公共接收器统一这几条有差异的所有权。readBytesBounded私有实现随receiveArtwork迁入MediaSession，大小/错误和AtomicFile.failWrite保留。OemArtworkWriter/KanziMediaPublisher的内部worker、PlatformAccess、输出协议沿原实现。

## 6. OEM元素、图片及音乐输出

- setActive(ELEMENTS,false)原有++imageGeneration、configurationStatus=ready，然后保存clusterElementsEnabled，再publish；其他角色不触发该代次。configureElements相等直接返回，否则++imageGeneration、++revision、ready、写同一Lease.elements、publish。
- configureImage的fstat保持`1..16*1024*1024`及background_requires_bounded_file；safe配置为IMAGE_BACKGROUND。main先closed及同UID/ELEMENTS Lease；配置已相同，关dup并回accepted=true。
- 新图片：ticket=++imageGeneration → pending → publish → backgroundImages worker原Store.import → 记录原错误/关dup → main判断`!closed && ticket相等 && 当前ELEMENTS Lease === 原lease`。仅accepted时改ready/failed；仅成功时++revision/写lease.elements；再publish。callbacks回accepted及原background_import_failed。迟到失败不能覆盖下一代配置。
- `theme-stop`和Binder setActive(false)不是同一动作：原theme-stop只写clusterElementsEnabled=false、把ELEMENTS Lease.active置false、停止ClusterRenderService，不擅自多加一轮imageGeneration/ready变化，不使MAP接管。
- OEM更新在Composer生成packet之后：读取packet.clusterElements/clusterElementsRequested，elementsWanted仍加`!closed`；需要且hook为null才创建，生产传RootServiceClient::hookControl/hookReceipt；原hook.update、runtime与configuration/status字段顺序保留。snapshot两次读取不改成另一套状态缓存。
- musicWanted仍是elementsWanted && rootReady，不能因为变量叫music就额外要求elements.music；原Publisher还承担共同租约/背景链。MediaSession只在wanted且publisher=null时创建Kanzi；published回调调用OEM owner当前hook.published。随后按原顺序Kanzi.update(packet,musicWanted)。不改Hook命令、租约、重试或共享health。

## 7. 车辆完整owner与远程生命周期

### VehicleSession

同一个Inventory和Collector迁入；S3 dispatcher只装配一次。modelPrefs每次读原platform/model（默认L6），catalogKeys只在车型变化时按目录键+旧别名重建；不把型号枚举或属性目录缩水。inputEpoch仍每个服务实例只生成一次，menuSequence/menuReceivedAt在原menu回调推进，不随快照/换车型重建。

Collector构造回调原样：metadata更新discovery/count后publish；rootLost仅在platform已初始化时调用原platform.rootLost；menu按原SystemClock时间/序号后publish；feed从采集线程仍main.post到S3 dispatcher。需求仍在collector存在时计算：租约feed经baseKey映射、间隔取紧，追加sentinel.demands.keys to 500，再原VehicleDemand.merge；没人订阅/无哨兵需求不读。

停止/换车型/功能切换的collector.stop、dispatcher.clear、retainFeeds、start、applyDemand三条顺序必须照原生命周期表执行。setFeed即时补发、getValues、目录分页和scan沿上表，不能以owner搬家更改UID/触发扫描。constructor不得提前start采集。

### RemoteSession

update仍串行同步，web与限时诊断连接分别判断。webEnabled=!closed&&!paused；远程enabled还需原RemoteSettings.enabled。关闭web先close再null；关闭remote有实例才++generation，close/null/state=disabled。开启remote先++generation记ticket，创建回调 → main → 非closed、代次相同、实例仍存在且state不同，才改state并publish。

remote-authorize原顺序：++generation → closeWeb/null → closeRemote/null → RemoteSettings.authorize(hours，默认12)。remote-disable：++generation → remote.revoke → settings.disable → remote=null。不得把revoke换close，不把车辆绑定有效期改成诊断授权窗口，不让旧回调复活连接。

## 8. 快照组装、发布与诊断

ServiceSnapshotComposer持有原sequence，build每次只增加一次；clock用已有SystemClock.elapsedRealtime，测试可注入时钟。它的嵌套只读Input只暴露下列快照数据/必要生命周期getter，不持有可写Service。生产Input适配器在ServicePublication中从具体owner读取，getter在原put调用位置求值，不提前做一整包输入缓存。

- vehicleDiscovery/propertyCount/sampledCount/validCount、keys/scanning/model/mode、inputEpoch/menu计数：VehicleSession；sentinel快照：原Coordinator。
- rootReady/features/paused/closed/status、foundationEvidence：原生命周期权威值，只读函数；automatic仍读AutoStartup；vehicleWebEnabled仍是未paused且原vehicle-web enabled配置。
- elementDemand取同一Registry.elementDemand，再原paused/features.root过滤；revision/configuration状态来自OEM；媒体、navigation、visualMedia、封面文件存在/版本来自MediaSession；remoteState来自RemoteSession。
- 固定字段/值见snapshot-contract.json：40项必需顶层字段；clusterElements和visualMedia条件出现。version不变，sentinelVersion=1，qnxReady/projectionRequested恒false，MapStreamSize.DEFAULT及ProjectionPolicy默认保持。不得把车辆values塞回大快照。
- 40项合同是最终Publication输出；clusterElementRuntime、clusterConfigurationStatus、clusterElementStatus三项仍由后续OEM装饰，不要求Composer提前放占位值，不能因此改变Hook/Kanzi观察到的时点。
- media/navigation仍各Bundle浅拷贝；visualMedia只有非null track且与当前media.track一致才合并，底为Bundle(media)，仅替换lyrics以及原lyricTimes存在/移除规则。不深拷贝所有数据，也不额外清空有效原始歌词。
- artworkUri仍`content://com.ecarx.autoservice.artwork/current`；字段名字、大小写、类型、默认和条件不改。

ServicePublication的publish顺序固定为：

1. 未closed且nativeSource=null才创建原Source；执行原nativeSource.enabled条件。
2. 原sentinel.platform条件（closed/paused/root/vehicle/automatic）。
3. RemoteSession.update。
4. Composer按原字段顺序build并推进sequence/receivedAt。
5. OEM按packet更新Hook，并补runtime/configuration/status。
6. MediaSession按原musicWanted更新Kanzi（published回连OEM）。
7. S2 snapshots.publish(packet, 当前clients.all().map callback)。

没有第二份latest/recipients/cache，也不把启停塞进Composer。Root允许保留一行publish委托供既有回调使用，但不保留原Bundle组装。

Diagnostics完整迁移dump：foundation-check早退仍在读缓存之前；record-properties、record-fields、cluster-fields和普通文本分支的顺序、前缀、vehicleStats/feeds/vehicleDemand、hab=archived等逐字保持。仅用S3缓存副本补vehicle，绝不扫描/采集；不把认证文件内容、Shell正文或原始车辆载荷写入诊断。

## 9. 初始化、Intent与释放顺序（不可通过分组close改变）

### 初始化

根Service仍负责super.onCreate、原诊断配置/kick、features读取、原SentinelCoordinator创建、vehicleMode持久设置、同一个shellAccess、VehicleSession/Collector及其他owners接线、paused、原前台通知、platform/start/vehicle.start、AutoStartup.schedule、remote更新与首次publish。工厂和changed在构造期不得执行，publication要在任何业务start之前接好。所有原worker仍按需起线程，不因任务大就提前启动。

前台channel仍platform、通知ID71、原importance、intent与flags；固定文案迁资源不改字面值。onBind仍只有AutoContract.ACTION返回真实AutoServiceBinder，其他action为null。

### onStartCommand与功能设置

- ProcessRecovery.accepts拒绝时保持原START_STICKY/NOT_STICKY，不触发业务。
- remote两action按上一节先处理；stop判断仍是原stop或非start且automatic关闭。
- stop顺序：sentinel.preparePlatformStop → 保存automatic=false/读features → stop Cluster服务 → paused=true → ++platformGeneration → remote.update → rootReady=false → 回surface_closed → retainFeeds → collector.stop → dispatcher.clear → discovery/count → platform.close → shellAccess.cancel → status/publish → stopForeground/stopSelf/NOT_STICKY。owner方法按此拆开调用，不能一次close打乱顺序。
- theme-stop只撤销原厂意图，start保存automatic=true；applyFeatureSettings原连接变化判断不改。
- paused恢复：paused=false → newPlatform/start → features.vehicle时collector.start再applyDemand → 前台通知。prepare仍shellAccess.cancel后platform.retry。
- model：原L6/L7合法值才commit → retainFeeds(新keys) → clear旧缓存 → collector.stop → generation/platform关闭重建/start → features.vehicle时collector.start再applyDemand。不能先算需求后stop把需求清掉。
- features切换：原哨兵prepare条件、features赋值、连接变化generation/cancel/rootReady/platform.close(releaseRoot)/vehicleMode及重建保持；车辆/连接变化时stop→clear→discovery/count→条件start/demand；最后status/publish。旧projection配置只兼容保留，不能恢复输出。
- 最后原OEM服务恢复条件与ProcessRecovery.arm条件不变，返回原START_STICKY。

### onDestroy逐项顺序

`sentinel.close/null → closeWeb/null → closeRemote/null → ProcessRecovery.release → closed=true → ++platformGeneration → platform.close → shellAccess.close → shell.close → clients.close → collector.close → callbacks.close → oemArtwork.close/null → nativeSource.close/null → clusterHook.close/null → backgroundImages.close → kanziMedia.close/null → artwork.close → super.onDestroy`。

因此MediaSession必须分别提供closeOemArtwork/closeNativeSource/closeKanzi，OEM单独closeHook，Remote分别closeWeb/closeRemote；不能把它们收成一个提前执行的closeAll。借用的worker由根在原时点关闭，不由多个owner重复close。原SerialWorker.quitSafely保留，使已接收任务完成finally。不得为了让单元测试容易而提前closed或清空所有Handler任务。

## 10. 内部检查点：同一个任务连续推进

| 检查点 | 完成的文件/业务 | 必测新增suite |
| --- | --- | --- |
| C1 | 三个media既有类迁包/Provider组件、Native监听器拆文件；VehicleSession、ShellRequests、PermissionRequests真实接线，旧Binder暂时调用新owner | ServiceVehicleSessionTest、ServicePlatformRequestsTest |
| C2 | MediaSession、OEM、Remote、SentinelRequests、ClientSessions迁入全部状态与业务，旧Binder主体变为对owner调用；不留双份字段 | ServiceMediaSessionTest、ClusterElementsSessionTest、ServiceRemoteSessionTest |
| C3 | Composer/Publication/Diagnostics完整迁移；Root不再组装快照/诊断业务 | ServicePublicationTest、ServiceDiagnosticsTest |
| C4 | 真实29方法AutoServiceBinder迁出；Root生命周期/资源顺序、通知资源与最终图接线收官 | AutoServiceBinderTest、ServiceCompletionBehaviorTest |

每点实际编译、全Service测试、AndroidTest只编译、KADB实际ASM、当前累计格式通过后本地提交，然后**直接继续下一点，不等用户或Codex再发任务/批准**。允许中间保留只委托真实owner的兼容方法/只读getter帮助编译，C4必须移除业务别名、旧实现、临时适配、TODO/空实现，不接受只完成C1就报A4完成。不得以模型名或旧“小批交审”条款停在中间。

检查点命令（C1依次换C2/C3/C4；同一原验证入口，非第二套构建流程）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify_projection_retirement_windows.ps1 -CacheRoot F:\GalaxyPlay\galaxycarplay\.work -ReportDir F:\GalaxyPlay\wt-autoservice-completion\.work\a4-C1 -Checkpoint C1
```

脚本只要求当前点及此前文件，不要求未来文件；CHECKPOINT_PASSED不是整个A4的READY。baseline选项是Codex自验工具用，不能用它冒充任务交付。最终命令不带Checkpoint，入口会清除遗留阶段环境，强制135项Kotlin/KTS、XML8、全部下游/Service/Cluster、完整新产物与33条代码范围。

## 11. 九组真实测试与失败标准

测试入口必须是本次生产类/实际Binder；系统边界才可替身，不复制状态机/帧/快照组装模型。不为数量写重复测试，下面场景可参数化，但每组suite必须非空且实际执行。

| suite | 必须观察的生产行为 |
| --- | --- |
| ServiceVehicleSessionTest | 生产Registry订阅并集/别名baseKey/最紧间隔、哨兵500ms、无人订阅空需求；停服/换车型保意图且clear缓存，restart后才设需求；扫描仅委托原API，缓存get不读Source；CollectorPort只记录真实调用 |
| ServicePlatformRequestsTest | Shell busy/平台关闭/返回字段/旧Lease迟到不回；权限missing才ensureRoot、OVERLAY/pm grant真实命令参数与回读失败；只替换execute/系统权限查询，不写另一套policy |
| ServiceMediaSessionTest | 原车优先和回退CarPlay、track换代/原始动态歌词优先、可视歌词track匹配；临时真实图片/FD验证边界/关闭/AtomicFile与旧代次不覆盖；Bitmap仅owned回收，source工厂不接系统会话；真实迁包ArtworkProvider按相同authority读取同一cache文件，异签名、非/current或非r仍拒绝（仅系统签名查询可替换） |
| ClusterElementsSessionTest | 生产Lease+真实Store临时PNG导入成功/失败/相同配置/lease换代；pending→ready/failed→accepted顺序，关闭/迟到不改当前配置；Hook边界只记录具名调用，不执行QNX |
| ServiceRemoteSessionTest | Web与限时Remote独立开关、授权/disable的close/revoke顺序、代次旧回调不复活；真实请求/上传入口的缺桥/异常/Lease检查和copy关闭；WebPort/CommandPort只隔离HTTP资源 |
| ServicePublicationTest | 调用真实Composer和Publication；参数化检查40项必需键/条件键、false归档位/defaultMap、只发vehicleKeys、原始/可视歌词合并与时钟/sequence；记录真实边界验证native→sentinel→remote→build→Hook→Kanzi→S2投递顺序，不仅查源码字符串 |
| ServiceDiagnosticsTest | 调用生产dump，捕获StringWriter实际输出/分支优先级/固定前缀，核对只读owner被调用而Source/scan/Root/HTTP未被触发；车辆诊断仍同一缓存 |
| AutoServiceBinderTest | 真实IAutoService.Stub.asInterface入口和真实owners；同步签名/包/ADMIN拒绝发生在排队前，main Lease校验、角色路由、归档回执/Surface释放、FD差异、scan本UID、web/哨兵边界；不测另写的路由表模型 |
| ServiceCompletionBehaviorTest | Robolectric实际AutoService create→bind→snapshot/dump→destroy；准备automatic=false、diagnostics/uploadEnabled=false且无enrollment，隔离所有IO，不启动Root/车辆/HTTP；onBind action、通知ID/channel、归档字段、无旧输出与关闭顺序/借用worker行为保持。释放序列必须调用生产路径，不能测另一份release模型 |

既有Service全套（含S1–S3）、Cluster/U1、Root归档/Hook生命周期/KADB/车辆/哨兵回归均保留，尤其现有KanziMediaFrameTest和ClusterElementPipelineTest不弱化。若组件级Robolectric暴露新真实问题，按实际源码修复本写范围，不以空报告/忽略异常/改SDK/删断言过关。

## 12. 最终统一交付门槛

```powershell
$env:GALAXY_REFACTOR_CACHE='F:\GalaxyPlay\galaxycarplay\.work'
python -m unittest discover -s tools/review/galaxy-r0-20261112 -p 'test_*.py'
python -m unittest discover -s tools/review/galaxy-autoservice-a4-20261113 -p 'test_*.py'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify_projection_retirement_windows.ps1 -CacheRoot F:\GalaxyPlay\galaxycarplay\.work -ReportDir F:\GalaxyPlay\wt-autoservice-completion\.work\a4-delivery
```

固定ktlint1.4.1和editorconfig，对135个最终完整Kotlin/KTS二次幂等和只读检查；XML8及脚本语法、10项工具回归，原全下游/资源/U1/Cluster，全Service（9个新增suite强制）、AndroidTest只编译，实际KADB两调用、97项归档、真实Manifest/class、最终结构与源码摘要全部通过。原三项Native准备排除不变，不增加编译/测试排除，不执行assemble/install。

所有新/迁移方法和职责块有有效中文KDoc、显式import；本任务文件完整格式，不格式全仓第三方。Formatter对Native等遗留的不可自动修问题按原语义手动展开。Provider实际merged Manifest和旧根包class退出必须通过，不只改import让编译碰巧成功。

最后只提交33条白名单中的代码，工作树干净；设同一GALAXY_R0_REPORT_DIR后再用既有verify_artifacts.py ready重读真实产物绑定最终HEAD，不手改JSON。交付`.work/a4-delivery/STATUS.md`、DELIVERY.md、C1–C4提交/命令/退出码/日志/JUnit、旧字段方法→新owner完整映射、线程/资源/构造与释放、固定格式结果、最终源码/产物SHA、未验项。

整体全绿才READY_FOR_REVIEW，冻结整个分支等待用户叫Codex审计；不自行合回Leader、不推送。内部一点完成不算整体完成。发生真实工具故障保留命令/退出码/实际产物交Codex，不改工具或断言；可继续不依赖该故障的既定部分。新协议/产品范围若出现才按既有规则提出具体探索申请，不用这条阻止本任务已给定的迁移。离线结果不写成实车连接、显示/恢复或性能验收。
