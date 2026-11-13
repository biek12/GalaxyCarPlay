# A2：AutoClient完整会话重构（一次完成24个公开方法）

2026-11-13。A1-R1业务`7f3b73e`已复审并以`3dc8d2b`集成，Client32例/Service172例/Cluster12例和完整离线门槛通过。本任务完整整理共享AutoClient：连接/重连、输入回调、补发与合并、一次性RPC、FD/Surface和退出，不每拆一个类就交审。

## 1. 工作位置与固定来源

- 目录：`F:\GalaxyPlay\wt-client-session`。
- 分支：`feat/galaxy-client-session-20261113`。
- 业务来源/范围起点：`3dc8d2b59806b5349fcd84662c11aa6a6aad708b`。工具准备与文档同步后记录实际HEAD，不拿业务来源替代开工HEAD。
- 仅写同目录[A2代码白名单](A2-代码白名单-20261113.txt)/`tools/review/galaxy-client-a2-20261113/scope.json`的 **9条Kotlin路径**：原AutoClient、4个具体owner、4组真实测试。
- 不改AIDL/AutoContract/配置合同/Manifest/签名/包名/Gradle依赖/页面/服务端，也不迁歌词提供器或跨App内容算法；本单完成的是整个AutoClient，不宣称client/contracts/lyrics全部整理完成。其他入口按总图后续单独细化。
- 不连车、不安装、不操作HMI、不启模拟器、不打包签名/推送，不使用真实账号/网络/Root，不恢复旧整屏。Mac ZIP只作只读历史资料，现有源码已足够，不开展未知协议探索。
- 脚本、固定API清单和验证环境由Codex准备，Cursor不改。工具故障带真实命令/输出交Codex，不弱化断言或改基线。

先读根/galaxy规范、Android工程规范及重构总图A2。逐行读固定基线AutoClient全部字段/方法，IAutoService/IAutoCallback/IAutoResult、AutoContract/SceneConfiguration/ProjectionPolicy/ClusterDisplayConfiguration只读。实际调用依据包括GalaxyAutoServiceBinding、GalaxyVehicleSpeed、GalaxyL6OemArtwork、GalaxyKanziMusicPublisher、ClusterRenderService/VisualMusicSession、ServicePageSession、AutoServiceRecordFeed、RecordWebService、SentinelRuntime及A1 AutoClientPort。它们只作为依据，不进入写范围。

## 2. 终点、唯一状态与构造关系

AutoClient继续是`com.galaxy.auto.client.AutoClient`，原构造参数**顺序、命名、类型和默认值**全部保留，包括7个回调和两个车辆订阅布尔选项。24个公开方法及默认参数保持，不增接口、不留空实现。Codex已从真实已审class固定32项公开JVM入口（含构造/default入口，排除编译器access$桥），最终实际javap比较；同时编译全部现有调用方。

AutoClient只持app/main、原`AutoClient-$role`一个HandlerThread/io及4个owner，负责装配、24方法薄委托与逐项close顺序。公开同步参数检查可以随具体owner入口执行；Facade不再保存业务缓存、内嵌ServiceConnection/IAutoCallback或写临时文件/拼RPC闭包。

4个类型都进入`com.galaxy.auto.client.ipc`，文件一个顶层类型；专用数据写成所属类嵌套类型，不新增全局Host/Runtime/Manager/Helper/事件总线/DI框架。

| 文件 | 从AutoClient迁入的唯一状态和动作 | 借用与边界 |
| --- | --- | --- |
| ClientConnectionSession.kt | owner、closed、api、connectionLock、bound、generation、retries、retry、ServiceConnection；connect/protocol/attach、call/state/lost/rebind、关闭时原子取旧lease | 借用app/main/io，提供明确只读api/owner/generation/closed访问与原call异常策略；唯有它修改连接状态，不开第二个worker |
| ClientCallbackDispatch.kt | IAutoCallback实现、borrowed和previewSurface、duplicateSurface、旧Surface失效释放与配置epoch解析 | 借用同一Connection和main/io，收到原入站/Surface回调及vehicleFeedOnWorker；onReady仍由Connection在握手完成后发出；不复制generation |
| ClientReplayPublisher.kt | active、feedKeys/interval、lastMedia/Navigation/Artwork/Scene/ElementPlan、elementRevision、media/navigation/art/scene/policy的AtomicReference、flush；configure/publish/订阅、重连补发/sendElements | 借用Connection、CallbackDispatch/main/io/app；同一个flush runnable和同一套待发/保留状态，无第二份latest/replay表 |
| ClientRequests.kt | prepareOemArtwork、Shell/权限/哨兵/Web/分页/单值/媒体命令/缓存读取/scan的一次性请求与结果路由 | 借用Connection/main/io/app，按原方法分别保持错误/静默与票据语义；不保存第二份连接、缓存或订阅 |

构造不connect、不调用业务回调、不发送RPC。先创建Connection，callbackFactory与replay回调用明确类型/函数接到后创建的CallbackDispatch/ReplayPublisher，全部装配好之后才允许外部connect；这些构造闭包不得立即执行。只传具体方法引用/类型，不传整个AutoClient作为任意可读写Host，不反射生产私有字段。

Connection只读generation供其他owner按原时点捕获；不要再造lease epoch、closed或owner。其余类不写Connection字段。原Volatile与connectionLock边界保持，不为纯重构引入新的锁顺序、线程或一致性框架。

## 3. 连接握手与恢复：顺序不能靠注释代替

### connect和onServiceConnected

1. connect同步要求main；closed或bound返回。仍同一AutoContract ACTION/固定Service组件，先startForegroundService，再bindService(BIND_AUTO_CREATE)。bind=false发service_unavailable后rebind；异常原DiagnosticEvents binder_bind后发bind:异常类名，再rebind。
2. onServiceConnected：closed直接返回；ticket=++generation；新Binder写owner；retries=0；原Stub.asInterface；创建本代IAutoCallback。
3. 原io队列：检查next.protocolVersion等于AutoContract.VERSION（原protocol_version_mismatch）；connectionLock内再次检查closed/代次再api=next；next.attach(lease,role)；再次检查closed/代次。
4. 顺序交ReplayPublisher：lastElementPlan存在时先sendElements → setActive → 允许自动补发且feed非空才setVehicleFeed → 各last值compareAndSet到对应待发槽 → PREVIEW才setPreviewSurface → flushLatest → main本代仍有效才onReady。
5. 握手异常仍binder_connect（caller/method=attach）诊断与connect:异常类名；不猜测新协议、不升级版本或另造降级连接。

### 三种断开不是同一恢复动作

- onServiceDisconnected只lost(service_disconnected)，沿Android原恢复机制，不擅自再挂页面/客户端重试。
- onNullBinding：lost(binding_rejected)，然后rebind；onBindingDied：lost(binding_died)，然后rebind。
- lost先closed守卫，connectionLock内++generation/api=null；接着原Surface回收回调与onState(reason)直接执行。普通state(value)依旧main.post后closed守卫，不能把二者统一成额外一次post或同步。
- rebind：bound时runCatching unbind后bound=false；未closed且retries<3，先移除同一个retry，再按`1000L shl retries++`排队，即1000/2000/4000ms，不加第四次。成功连接按原时点retries=0。不得以“更稳定”增减重试、改超时、反复start或依赖手动按钮。

## 4. 入站回调与两种Surface

全部IAutoCallback都捕获其握手ticket。不能改成每次回调读取当前代次然后总能通过。

| 回调/动作 | 原线程、守卫与顺序 |
| --- | --- |
| onSnapshot | main.post；未closed且generation相同才设Bundle.classLoader；含command只onCommand，否则原onSnapshot |
| onSurface | main.post；旧代/closed首先release传入Surface后返回；有效时保存old→borrowed=new→onSurface(new)→onConfiguredSurface(new, kind中@后long或0)→old.release |
| onProjectionState | main.post和本代守卫；surface_closed分支先onSurface(null)→onConfiguredSurface(null,0)→borrowed.release/null；之后onState(state) |
| onVehicleFeed | vehicleFeedOnWorker=true投同一个io，否则投main；本代守卫和classLoader后回onVehicleFeed，不一律回main |
| setPreviewSurface | 同步require PREVIEW；closed返回；value=null先清lastScene及scene槽；原Parcel复制Surface；io上closed则释放copy，否则释放旧preview→保存copy→call setPreviewSurface(owner,copy)；投递失败也释放copy |
| lost/close的borrowed释放 | 原次序是onSurface(null)→borrowed.release/null→onConfiguredSurface(null,0)，与surface_closed分支不同，不能统一成改了次序的clearAll |

previewSurface仅io拥有独立复制引用，borrowed仅main拥有接收引用。复制仍Parcel.obtain→writeToParcel→setDataPosition(0)→CREATOR.createFromParcel→finally recycle。不要加一份Surface缓存或在关闭时删除所有io任务，已接收Surface任务必须有机会走closed释放分支。即使旧预览已归档，保留这条兼容API的资源处理，不能恢复输出或直接丢弃对象。

## 5. 24个公开方法的完整迁移表

| 方法 | 具体归属及保持 |
| --- | --- |
| connect | Connection，§3完整行为 |
| setActive | ReplayPublisher先写active，再io用原call/setActive当前owner；重连保留 |
| configureClusterElements | require ELEMENTS；value.bundle，调用线程++elementRevision，保存configuration to File；io只有revision仍相同才sendElements；不新增输出 |
| publishMedia | sanitizeMedia；track变化先清lastArtwork/art；保存lastMedia及media槽，scheduleFlush |
| publishVisualMedia | require VISUAL_MUSIC；sanitize后io原call，**不新增重连保留缓存** |
| publishNavigation | sanitize并保存lastNavigation/navigation槽，scheduleFlush |
| publishArtwork | 同步<=2MiB、bytes.copyOf；默认track仍取当时lastMedia.track或空串；保存lastArtwork/art，scheduleFlush |
| configureScene | require THEME/PREVIEW；value.bundle保存lastScene/scene，scheduleFlush；只保留兼容调用，服务端仍归档 |
| setProjectionPolicy | value.bundle写policy槽并flush；原本没有lastPolicy，不自行新增重连重放 |
| setPreviewSurface | CallbackDispatch，按§4并调用Publisher清scene的具体方法 |
| prepareOemArtwork | Requests，§7原临时文件/票据/错误合同 |
| executeShell | Requests，默认timeout=10000；调用时捕获generation，io原call；结果main本代才result，不新加同步传输/命令校验 |
| requestClientPermissions | require非空且<=8；调用时ticket；io api为空也走原异常回执/诊断；不扩权限 |
| reportSentinelIdle | io原call，只传powerSequence；无新缓存/重放 |
| configureSentinel | ticket；io try/checkNotNull api并传enabled；main同代结果；异常accepted=false/error=异常类名 |
| vehicleWebUpload | ticket；io打开原File只读FD/use，clipId/direction/length原样；异常固定「媒体传输失败」 |
| vehicleWebRequest | ticket；io checkNotNull(api)原service_unavailable；action/payload原样；异常error.message或service_unavailable |
| vehiclePage | io原call；offset和limit32，catalog决定原API；结果main仅!closed（原来没有generation守卫） |
| vehicleValue | io原call；propertyId/areaId/offset原样；结果main仅!closed，不擅自补另一种票据语义 |
| mediaCommand | io原call原command，没有客户端新增命令白名单；服务端校验保持 |
| setVehicleFeed | require main；distinct.sorted；原feedKeysValid/feedIntervalValid及消息；先保存keys/interval，再io读当前字段发订阅，不捕获旧next替代原合并语义 |
| getVehicleValues | distinct.sorted及原keys校验；io call，结果main仅!closed并设classLoader；不触发采集、不新加generation过滤 |
| setVehicleScan | io原call；原服务端本UID限制保持，不把诊断扫描自动开启 |
| close | §8唯一终态及逐项顺序 |

默认参数、Collection/Bundle/ByteArray/Surface/回调的公开类型及命名保持；caller的浅/深拷贝范围不改变，不能给全部请求统一深拷贝或统一错误Bundle来“简化”。AutoContract、Scene/Projection/ClusterDisplayConfiguration只消费，不改键/版本/角色/方法顺序。

## 6. 重放、合并与元素请求

scheduleFlush仍对同一个io flush runnable先remove再post，只合并现有场景/策略/媒体/导航/封面槽。flush一开始api为空直接返回（不能getAndSet丢缓存），closed返回；仍按scene→policy→media→navigation→art顺序getAndSet(null)并调用。一次原call包住这一轮，异常后的后续槽按原异常传播处理，不能改为每项吞错或全部预先清空。

原flush进入时捕获remote；原call自身仍读取api并做closed/异常处理。保留调用时与执行时的读取位置，不借迁移统一“原子快照”而改变跨重连时机。重连compareAndSet(null,last)不能覆盖已经排队的更新值，不能把所有保留值统一清空后再补发。

sendElements：非IMAGE_BACKGROUND直接configureClusterElements；图片时先check File.isFile（background_image_missing），此处捕获generation，再打开只读FD/use并configureClusterImage。onResult回main，必须未closed、本generation且elementRevision相同并accepted=false，才发background_import_failed。File引用和Bundle(configuration)的位置保持；成功不额外发ready，不让旧图片回执覆盖新方案。

active、feedKeys/interval、elementPlan/revision的唯一权威是Publisher。回放与业务调用用同一字段，不能复制一份给Connection。S3服务端仍订阅驱动、无订阅不采集；本任务不改100ms/1000ms节拍或采集性能承诺。

## 7. FD、临时文件与一次性结果的差异

| 路径 | 真实所有权与错误策略 |
| --- | --- |
| flush Artwork | 私有cache创建auto-art-*.bin，写已复制bytes→open只读FD/use→publishTrackArtwork(owner,track,fd)，finally删除临时文件；不改pipe，不把外部路径发给服务端 |
| prepareOemArtwork | <=2MiB，原调用时ticket；io创建oem-artwork-*.bin、写入/open/use、remote不存在service_unavailable；结果和异常main本代才回复，error为异常类名；finally删临时文件。原创建/try边界按源码迁移，不与flush catch混成新策略 |
| vehicleWebUpload | 文件属于调用方，只打开本次FD并use关闭；不能删除用户传入的录像文件。结果ticket守卫，异常固定中文错误，不把文件或认证内容写诊断 |
| 图片配置 | File属于调用方，只use关闭这次FD；不能复制到另一持久目录或删除输入文件；回执双generation/revision守卫 |
| 其他结果 | permissions/Web/sentinel/executeShell按表内调用时ticket；page/value/getValues原来只有closed。不要全部套同一回调包装器改变语义 |

Connection.call保持api为空静默返回，未closed才执行；异常原DiagnosticEvents binder_call与ipc:异常类名。对本来有独立try的请求保留它自己的回执，不强行改成call静默或统一错误：permissions仍记录client_permission的caller/method/count，granted=false/error类名；WebRequest使用message，Sentinel使用accepted=false，不能彼此代验。

临时文件、读取/写入、FD关闭都在原io上。测试Stub只替换远端接口，可读取FD，但不接真实服务/车辆。生产日志只留原脱敏阶段与异常，不记录Shell正文、绑定载荷、凭据、原始车辆数据或封面/录像内容。

## 8. close与线程生命周期逐项合同

同步require main；connectionLock内如closed直接返回，否则closed=true，捕获(api to owner)且api=null。之后按原顺序：

1. ++generation；++elementRevision，lastElementPlan=null。
2. main.removeCallbacks(retry)。
3. onSurface(null)；borrowed.release/null；onConfiguredSurface(null,0)。
4. bound时unbind并bound=false。
5. io.removeCallbacks(flush)；清media/navigation/art/scene/policy待发槽和lastScene；清lastMedia/Navigation/Artwork。
6. feedKeys=emptyList，feedInterval=AutoContract.DEFAULT_FEED_INTERVAL_MS。
7. io.post：对捕获的旧api/owner runCatching detach→previewSurface.release/null→thread.quitSafely。

因此Connection需提供原子beginClose/推进代次/移除retry/unbind等明确动作，Publisher提供失效元素/清待发保留/清feed，CallbackDispatch分别处理borrowed与io preview。Facade按表装配顺序，不让某一个closeAll提前吞掉其他owner职责。只开原来一个AutoClient worker，不能每owner开线程、清io全部任务或强制停线程。

已排队Surface任务应在closed时释放独立copy；旧Callback ticket一律失效；普通请求仍按其原closed或ticket语义退出。旧lease detach使用关闭时捕获的对象，不能错误解绑重连后的新owner。线程必须由生产quitSafely退出，测试不能先强制quit再声明关闭通过。

## 9. 四组真实标准用例

新增suite全部通过真实AutoClient公开API、真实Handler/ServiceConnection、IAutoService.Stub.asInterface与记录型本地Stub。只替换系统绑定/远端IO；owner单测可用于补充状态细节，不能只测另一份连接/重放模型或字符串存在。

| suite | 必须观察的真实行为 |
| --- | --- |
| AutoClientConnectionTest | 同步main/role边界；完整version→attach→elements→active→feed→flush→ready顺序，新owner与代次；不匹配版本不ready；bind=false/异常/null/died原状态与1/2/4秒三次恢复、success重置；service_disconnected与null/died差异；close时旧lease detach、幂等、无后续重连/回调、自有worker真实退出 |
| AutoClientCallbacksTest | snapshot与command分支、Bundle classLoader、Surface有效换代及old/closed传入Surface释放、kind@epoch解析、surface_closed与lost/close两种顺序；两个vehicleFeedOnWorker值真实执行线程；PREVIEW复制/替换/null/投递失败/close后释放，不调用任何显示后端 |
| AutoClientReplayTest | 未连接保留待发、合并后scene/policy/media/navigation/art顺序与真实track/byte复制；重连不覆盖更新槽，active/feed/元素按原顺序重放；replayVehicleFeedOnConnect=false不自动重放；已订阅取消/间隔参数保留；图片真实临时FD、旧generation/revision错误回执丢弃、MAP/VISUAL_MUSIC/元素/归档角色边界；policy/visual不新建last缓存 |
| AutoClientRequestsTest | 原一次性RPC全部动作/参数/默认值、权限/Sentinel/Web缺服务和异常各自错误形状与回调线程；ticket类结果旧代次/关闭丢弃，page/value/getValues按原仅closed；真实FD在调用期间可读、返回后关闭、临时文件删除而用户文件保留；OEM/上传异常清理，Shell正文不进入诊断；scan不自动开启 |

可用少量参数化场景，不追求数量。Context绑定边界只注册本地Stub、记录start/bind/unbind和回放结果，不能真正启动AutoService、HTTP或Root。诊断上传关闭且无enrollment，使用虚构样本。异步用有界条件/Looper推进/真实线程观察；线程强制清理只能在断言之后finally，不能成为通过前提。业务回调异常的原传播/记录策略保持，不用runCatching包住全部断言。

原Client7套（含A1-R1真实QR时效/线程用例）、Service/Cluster/U1、原KADB与归档全部保留。尤其A1 Activity测试继续走真实AutoClient，不为了新owner抽取而改为纯Presenter模型；这些旧测试不在写范围。

## 10. 格式、工具和整包交付

Codex已准备9路径、24方法清单和真实class的32项公开JVM API固定合同。纯协议和UI文件不需为本单凑修改数量。新/迁移类、方法及职责块有效中文KDoc，先import、无星号/全限定类型调用、一文件一顶层类型。原压缩if/多个语句按原短路/顺序展开，固定ktlint1.4.1与既有editorconfig，不Suppress、不改依赖/规则。

最终同一个原入口，不带Checkpoint、不带--tests过滤：

```powershell
python -m unittest discover -s tools/review/galaxy-r0-20261112 -p 'test_*.py'
python -m unittest discover -s tools/review/galaxy-autoservice-a4-20261113 -p 'test_*.py'
python -m unittest discover -s tools/review/galaxy-account-a1-20261113 -p 'test_*.py'
python -m unittest discover -s tools/review/galaxy-client-a2-20261113 -p 'test_*.py'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify_projection_retirement_windows.ps1 -CacheRoot F:\GalaxyPlay\galaxycarplay\.work -ReportDir F:\GalaxyPlay\wt-client-session\.work\a2-delivery
```

格式154项Kotlin/KTS、XML16、脚本语法15、工具8+4+3+3=18例。原全下游/U1/全部Service/Cluster/Client、Service AndroidTest只编译、双宿主账号Manifest/7类/4张真实PNG、5个Client运行类、公开JVM API、97项归档/实际KADB和最终SHA全部通过。Client最终强制原7+新4=11个suite非空，额外suite同样不得失败/跳过；不要把A1与A2两处汇总的同一批Client报告重复相加。

新A2固定范围禁止修改已审A1/A4等文件；它替代的是当前业务范围比较，不移除A1的真实页面/资源/套件/产物检查。`A2-baseline`只供Codex工具自验，BASELINE_ONLY/wholeTaskReady=false不能当最终交付。旧A1/C1–C4检查点不用来交本单。

内部可按“Connection/Callbacks→Publisher→Requests/Facade→整体回归”分提交，真实编译/测试/格式通过后直接继续，不逐点等新任务。所有24方法/状态/释放完整接通后统一交审；只迁一部分不能READY。发现真实工具故障保留输出交Codex，不改脚本/API合同或加排除。

只stage9白名单实际代码，验证后Git提交并保持干净；同一ReportDir执行原`verify_artifacts.py ready`重读实际产物绑定最终HEAD。STATUS/DELIVERY列旧字段方法→新owner、构造/线程、握手/回放/关闭顺序、每项用例生产入口及断言、工具/源码/产物SHA、命令退出码/日志/JUnit、提交和未验。全绿才READY_FOR_REVIEW并冻结等用户叫审计，不自行合并/推送或续做下一业务。离线结果不写成实车重连/性能/显示验收。
