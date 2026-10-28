# DiPlay-ECARX 仪表投射源码对标

**只研究、只记录，不开发业务代码。** 参考固定于 `jiopia/DiPlay-ECARX / b46ff9be5f1674999dd910792a368bfdcd34fcf1`。本页记录它已经怎样实现完整地图投射与配套数据，不把其他fork的逻辑混写进来。

## 1. 先看实拍，再反查源码

![ECARX原项目仪表高德车道级导航实拍](<../references/upstream/DiPlay-ECARX/docs/screenshots/cluster-amap-lane.jpg>)

本轮已直接查看这张原仓照片：可见透视道路/车道、导航车辆、路名、转向图形与231m距离、红圈限速50；这些构成完整导航画面，不是只有箭头/距离卡片。照片还包含原厂速度、挡位、时间等仪表元素，不能把照片里所有原厂元素都归为CarPlay绘制。

- [README:170–190](<../references/upstream/DiPlay-ECARX/README.md#L170-L190>)明确说这是高德原生CarPlay集群地图流，图中地图没有额外叠加，由地图App自己渲染车道级画面；DIM另送HUD/卡片信息。
- [源码EcarxClusterPresentation:43–49](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/EcarxClusterPresentation.kt#L43-L49>)确实仅放一个ClusterVideoTexture，没有加入zmisgod的NativeDashboardCanvas或本地车道绘制。这与实拍和该段README相互对应。
- 同一README[旧限制:283–289](<../references/upstream/DiPlay-ECARX/README.md#L283-L289>)仍残留“不是整幅地图”，与170–190实拍段及实际源码冲突。按实拍段＋当前代码记录其**完整地图投射已经实现**，不能再引用旧一句话将其研究成只有TBT。
- 图片对应原仓路径已锁定于上述提交；实拍是作者提交的实物证据，本轮未新连车复测。

## 2. 三条实际内容链

```text
A 完整导航地图（像素）
手机地图App渲染两路CarPlay视图
  → 独立集群111视频
  → ScreenStream / MediaEngine / AndroidMediaSink
  → EcarxClusterPresentation内的TextureView Surface
  → 原车DIM显示开关把display1接到仪表

B HUD/原车导航卡（结构化信息）
iAP2 RouteGuidance 0x5201/0x5202
  → NavigationOutputs worker
  → EcarxHudBridge状态/映射
  → XUI INaviInteraction（箭头/距离/路名/ETA）

C 原车音乐卡（结构化信息）
iAP2 NowPlaying → CarPlayNowPlaying
  → EcarxMediaBridge worker
  → IMediaInteraction（标题/歌手/专辑/播放状态/进度）
  ＋ 原厂本地phoneprojection源声明
```

A是完整地图投射的核心，不需要Android重建每条车道或逐字段画限速。B/C独立说明，不能用它们是否包含车道图形字段来判断A能否显示车道级地图。

## 3. A链：投射配置、窗口与显示通路

### E01：请求手机生成正确比例的独立集群画面

[CarPlayHostActivity.clusterDisplayConfig:1233–1268](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1233-L1268>)：ECARX分支直接请求1920×720、scale100、物理宽高比8:3；内容来自loadClusterContent，safeArea取集群配置。不是将中控1920×944裁掉或挤压到720高。

[loadClusterContent:920–923](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt#L920-L923>)无保存值时选MAP；[CarPlayClusterDisplay:12–24、79–106](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayClusterDisplay.kt#L12-L106>)生成initialURL、帧率、尺寸与safeArea。默认URL为 `maps:/car/instrumentcluster/map`。

**输入输出**：产品显示配置 → AirPlayConfig.cluster → 手机独立渲染的地图流。原项目display1/1920×720属于它的输出配置，未来L6对标时将输出端适配单列，不因此重写内容取得逻辑。

### E02：主屏与集群是两路，不是屏幕镜像

[启动接线:5409–5458](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L5409-L5458>)创建同一renderer/mediaEngine/controller，注册监听和后台会话后启动。

[attachSurface:6230–6235](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L6230-L6235>)只把中控Surface绑定SCREEN_TYPE_MAIN；111只绑定clusterSurface，不把111再指向中控，以免覆盖主CarPlay页面。

### E03：为什么实拍能有车道级地图和限速

[README:182–190](<../references/upstream/DiPlay-ECARX/README.md#L182-L190>)说明：高德的“3D车道级/线路总览”在中控按钮切换，两路视图随之交换；仪表用车道级时，中控显示线路总览。App接收的111本身就是地图App渲染的相应画面，照片中的车道、道路、限速等不用另建本地Canvas字段。

**证据层次**：图片直接可见车道与限速；双视图切换行为是作者说明；源码显示它接原生111而无额外地图卡绘制。不能把该导航App的实测切换规则升级成所有导航App的通用限制，也不需要因此否定这条已实现的内容路径。

### E04：真实初始化入口与导航驱动窗口

[Host.onCreate:823–831](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L823-L831>)先注册panel owner；[Controller.init:176–182](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L176-L182>)实际调用NavigationOutputs.start，而不是全仓无调用的onAppOpened定义。[NavigationOutputWorker:8–35](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/NavigationOutputWorker.kt#L8-L35>)为独立单worker、队列128，start先clearOutput再initialize；submit使慢DIM调用不阻塞手机读包。队满时停接受并清输出，不将单个增量帧随意丢弃后继续解析。

[NavigationOutputs:89–109](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/NavigationOutputs.kt#L89-L109>)把路线帧投给独立输出worker，并保存Host提供的clusterPanelOwner；回调发生在DIM worker持锁时，只能提交UI请求后返回。

[EcarxHudBridge.pushLocked:500–584](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L500-L584>)确认有效路线，先上报DIM数据；首次guidanceActive时才回调 `clusterPresentationRequested(true)`。没有在这里先关显示开关；真正开通由窗口建立后负责。

[Host.onClusterPanelRequested/applyClusterPanel:1651–1658](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1651-L1658>)只存wanted并post主线程，因此DIM worker不直接操作View。

### E05：关键顺序——先窗口，后接通显示

[showClusterPanel:1676–1702](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1676-L1702>)：

1. 主线程检查宿主未销毁、未shutdown、尚无presentation。
2. DisplayManager取项目配置的display1。
3. 创建EcarxClusterPresentation并保存引用。
4. `presentation.show()`成功后，调用 `requestPresentation(true,"cluster stream")`。
5. 记录窗口与显示开关结果。

这是修掉“导航刚开始闪一下中控镜像”的实质：没有窗口就先开通路，平台会先显示自己的中控镜像。源码没有等首帧回执才开通；实际顺序是show()返回后开通，不能补写成已实现“首帧已显示确认”。

### E06：窗口内确实只有原生视频纹理

[EcarxClusterPresentation:35–58](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/EcarxClusterPresentation.kt#L35-L58>)继承Presentation，onCreate只创建ClusterVideoTexture并MATCH_PARENT设置为内容。没有本地导航卡或音乐卡addView。

[ClusterVideoTexture:15–39](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/ClusterVideoTexture.kt#L15-L39>)：`isOpaque=false`；SurfaceTexture可用时先release旧输出，设buffer1920×720，构造Surface并回调Host。销毁/close先onSurface(null)，再释放Surface wrapper；TextureView自身管理texture。

### E07：Surface接到111解码器

[Host.onClusterSurface:1720–1726](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1720-L1726>)：同一Surface不重复处理；存新引用，若sink已有则先clearSurface(111,previous)，再setSurface(111,new)。sink创建早/晚的情况通过clusterSurface与attachSurface保留接线。

**同一ECARX检出的实际接收/提交点**：

- [AirPlayInfoPlist:190–228](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt#L190-L228>)宣告111的type、ALT_UUID、尺寸、viewAreas与initialURL。
- [AirPlaySession.handleStreams:695–713](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt#L695-L713>)接受手机屏幕SETUP，调用media.onScreen，成功后登记流并返回dataPort。
- [CarPlayMediaEngine.onScreen:128–165](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt#L128-L165>)建立ScreenStream，以(session,type)登记接收器，替换时关闭旧流；111关键帧请求专门带ALT_UUID，不能误请求主屏关键帧。
- [ScreenStream:43–123](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt#L43-L123>)独立TCP线程读128字节header和上限8MiB消息体，拆codec配置、解封压缩视频、长度前缀转AnnexB；[currentScreenListener:50–56](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt#L50-L56>)只让当前流送config/NAL。
- [AndroidMediaSink:459–487](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L459-L487>)按type把codec配置与NAL送decoder；[配置:1361–1364](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L1361-L1364>)最终configure到上述Surface并start，[输出:1633–1637](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L1633-L1637>)以render或目标时间releaseOutputBuffer真正提交解码像素。

至此，参考项目已闭合“手机完整地图→111网络视频→解码→仪表窗口→DIM接通物理显示”的链，而不是只有一个API名称。

### E08：真正接通路的参数及保存状态

[EcarxOutputSettings.requestPresentation:158–160](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxOutputSettings.kt#L158-L160>)确保HudBridge初始化后调用applyPresentation；[桥:397–441](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L397-L441>)保存开关意图并调用API。

[EcarxDimApi:149–164](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxDimApi.kt#L149-L164>)反射方法签名为 `(int,String,String,boolean)`，该fork实际传 `setPresentationToDimSwitch(1,"","",show)`；包名恒空见[OutputSettings:40–50](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxOutputSettings.kt#L40-L50>)。本通路决定display1当前窗口能否到仪表，不是“打开这个开关就一定只能得到中控镜像”。窗口归谁、先后顺序才决定最终内容。

### E09：启动与运行期间的原有watch

[initialize:215–245](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L215-L245>)启动时不恢复保存的on：此时自有窗口还不存在，旧on值会被清回false；导航驱动窗口后再开。不能照该段上方旧注释写“开App自动恢复投屏”。

原项目[时间常量:147–149](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L147-L149>)与[watchProjectionLocked:463–496](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L463-L496>)真实存在定时重申：presentationWanted时，前30次按2秒节奏，之后10秒；导航guidance另按5tick重发。本页忠实记录原逻辑，不能为了套自拟规范把它改写成“只调用一次”；是否如何适配目标L6生命周期，属于未来收到开发指令后的对标差异项。

### E10：结束顺序——先交还显示通路，后撤窗口

[EcarxHudBridge.endLocked:620–652](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L620-L652>)清TBT、箭头/距离/路名/车道数组和状态，guidanceActive转false后回调owner(false)。

[Host.hideClusterPanel:1705–1712](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1705-L1712>)先 `requestPresentation(false)`，再dismiss窗口；Presentation.onStop触发E06的Surface撤销，Host的E07解除111输出。关闭先后不是任意的，否则会留下旧图或露出平台中控镜像。

**视频断流也有实际结束接线**：[MediaEngine:151–159](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt#L151-L159>)在TCP流结束时移除当前流、通知屏幕inactive并关闭会话；[Controller:370–387](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L370-L387>)只在当前activeSession结束时调用endNow，避免旧会话结束误清新会话。其后回到上述清DIM/交还显示/撤窗口顺序。

**离开中控页面也不等于结束投射**：[Host.onStop:1575–1586](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1575-L1586>)有意让controller/USB/iAP2/VPN超出UI生命周期，并不在此hide仪表；[DiPlaySessionService:19–24、55–61](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt#L19-L61>)以前台服务维持显式连接，通知Disconnect调用BackgroundSession.stop，划掉任务则先endNow再stop，返回START_NOT_STICKY。该Service没有onDestroy覆盖，不能虚构它在onDestroy清理。

**会话结束不等于进程全部资源被销毁**：[NavigationOutputs.endNow:112–115](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/NavigationOutputs.kt#L112-L115>)仅让worker停接受/清队列/执行clear；HudBridge的进程级ticker、display listener和句柄未见结束时shutdown/unregister。不能补写成对方已实现全部线程释放或OEM Binder死亡重绑。窗口创建/关闭在主线程，运行中的投屏watch则在HUD ticker线程调用开关，两类调用线程不同。

## 4. B链：地图与DIM箭头数据怎样并行

### E11：同一会话，分开接收像素与导航字段

[Controller.onRouteFrame:824–832](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L824-L832>)将iAP2帧交NavigationOutputs，另外合并NowPlaying并回调。NavigationOutputs只把路线更新/机动更新交worker；视频另外走AirPlay屏幕端口，不从此处拆图。

[pushLocked:515–553](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L515-L553>)发送顺序：aggregate NavigationInfo → 首次notifyTurnByTurnStarted → 下一点距离 → 可用剩余距离/ETA/路名 → 箭头。navigationProjection在当前版本恒false，故不会额外发送carPlayNavigation源声明；仍正常显示原生111地图。

### E11补充：ETA不是直接照搬原整数，路名/距离有具体解析位置

[RouteGuidanceState:105–167](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/RouteGuidanceState.kt#L105-L167>)解析0x5201 TLV：03当前路；05(u64)ETA原值；06(u64)剩余秒；07(u32)剩余米；0a(u32)下一转米；0d首个机动索引。ETA原值>0但低于epoch阈值时按剩余秒加到当前epoch，仅在解析入口转换一次，避免把“还剩41分钟”错误显示成绝对时刻。0x5202在[170–184](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/RouteGuidanceState.kt#L170-L184>)存index/type/转后路名/行车侧，再组合AppleManeuver。

[guidanceFor:661–671](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L661-L671>)只把相应turnId、下一转/目的地距离、arrivalEpoch、路名、active状态、系统昼夜等装入Guidance；remainingSeconds和etaRaw没装入，因此只变这两个字段不必然触发一次立即DIM发送。[离散setter:331–357](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxDimApi.kt#L331-L357>)实际把米/epoch秒的Long转Int调用精确签名，路名为String，停止车道条使用typed空数组；对标时按原类型记录，不把秒/毫秒或米/文本距离混用。

### E12：箭头原有选择次序是id → SVG → Bitmap

[pushArrowLocked:606–617](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L606-L617>)先调用可用id，接受即返回；否则尝试可生成SVG，再Bitmap。文件顶部旧注释的“SVG优先”不是实际执行顺序。

[结束:620–636](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt#L620-L636>)notifyTurnByTurnStopped，箭头0/空字符串，下一点距离0、路名空、lane空数组。不能写成结束又发了一份NavigationInfo(status=0)，该方法体并没有这么做。

**与需求的对应**：DIM链带路名/距离/ETA等原厂卡与HUD数据；车道级道路、限速等实拍图像由手机原生地图画面带来。没有本地lane绘图字段，不影响照片中真实地图的存在。

## 5. C链：音乐信息怎样到原车媒体卡

### E13：曲目快照合并后由媒体worker发送

[CarPlayMediaKeys.attach:75–95](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt#L75-L95>)注册nowPlayingListener，先安排Android媒体状态更新，再立即 `NavigationOutputs.onNowPlaying(update)`，经后者[75行](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/NavigationOutputs.kt#L75>)交给媒体Bridge。这是从手机状态到DIM媒体worker的真实接点，不能仅写两个类名而漏掉中间回调。

[EcarxMediaBridge:151–205](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxMediaBridge.kt#L151-L205>)：onNowPlaying只存wanted，elapsed值变更才重置测量时间；单daemon每秒tick。曲目信息变或15秒保活时推PlaybackInfo，否则更新进度。开关关闭时若曾发布，发送一次空曲目清理，不把原曲目留在卡片上。

[frameFor/sendLocked:236–320](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxMediaBridge.kt#L236-L320>)将title/artist/album/duration/playing转换；首次源6声明→来源列表/静音状态→PlaybackInfo→单项playlist。曲名不是从地图视频OCR，也不是只靠系统蓝牙AVRCP。

### E14：播放进度有明确的本地时间外推

[progressMillis/pushProgressLocked:332–357](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxMediaBridge.kt#L332-L357>)：playing时baseElapsed + (本机elapsedRealtime - progressMeasuredAt)，暂停不加；有duration时夹到0..duration。与lastProgress相同不发送，变化后调用updateCurrentProgress，单位毫秒。

这与zmisgod NativeDashboard直接按快照elapsed画进度条是两种不同现成实现；后续对标时分别保留，不混用描述。

### E15：本地源声明不是传地图视频

[EcarxProjectionChannel:85–111](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxProjectionChannel.kt#L85-L111>)为127.0.0.1:1883、topic `ihu/info/phoneprojection`；[update/flush/plan:254–325](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxProjectionChannel.kt#L254-L325>)合并状态后在单worker发布最新值，导航/媒体两者都没有且之前确有声明时才发noProjection。当前设置只使媒体轴请求carPlayMedia。

本地MQTT在这个fork用于原厂音乐来源选择，不承载H264图像、封面Bitmap或歌词正文，更不是本项目云服务方案。

### E16：封面与歌词在不同出口的实际情况

[CarPlayMediaKeys:125–183](<../references/upstream/DiPlay-ECARX/common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt#L125-L183>)仍接收并解码CarPlay封面，更新Android MediaSession与ClusterMusicState，说明封面数据不是完全没有进入App。

但当前真实仪表窗口是E06的单TextureView，未把ClusterMusicView加进去；[DIM代理:711–730](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxDimApi.kt#L711-L730>)又明确getArtwork/getLyric=null、getCurrentLyricSentence/getLyricContent为空。因此应记为：**完整地图投射有实拍与完整代码链；标题/播放状态/进度有DIM推送链；封面进App有代码，但该DIM出口未发送封面/歌词。**不能从这张地图图推断全部音乐字段已经上屏，也不能反过来用音乐字段尚待接入否定其完整仪表投射。

## 6. 当前设置值及旧注释校正

[当前OutputSettings:66–146](<../references/upstream/DiPlay-ECARX/shared/src/main/java/com/shilapi/xcertplay/hud/EcarxOutputSettings.kt#L66-L146>)：

| 项 | 实际值/默认 | 对调用链的影响 |
| --- | --- | --- |
| 导航数据 | 默认true | 路线数据进入DIM并触发窗口请求 |
| 手动仪表投屏 | 默认false | 不要求用户先开；由窗口驱动运行时开关 |
| 媒体卡 | 默认true | 独立worker与DIM媒体出口 |
| 箭头开关 | 恒true | 虽仍叫Experimental，实际恒启用，并按id→SVG→Bitmap条件选择，前者成功即返回 |
| navigationProjection | 恒false | 不发carPlayNavigation矩形镜像声明 |
| mediaProjection | 恒true | 媒体源声明carPlayMedia |
| naviCardEvent | 恒false | 不发布ongoingNavi附加浮卡事件 |

本页按可执行方法体记录，不把早期注释、已删除选项或未调用的入口当成当前行为。

## 7. 未来L6逐段对标的位置（尚未开发）

1. **内容取得**：完整地图首先对标ECARX的原生111路径；不要求重写地图引擎或在Android重建所有导航图元。
2. **输出适配**：保留内容协商/视频接收/解码边界，L6接既定HAB→QNX；ECARX的display1与具体DIM窗口开关作为原项目实现单列，目标输出方法逐段落实，不把屏号替换当完成全部适配。
3. **配套信息**：参考ECARX的导航状态、箭头/距离/ETA与媒体进度推送顺序，对照L6实际服务契约，记录不同descriptor/参数位置。
4. **音乐展示**：对照ECARX已有元数据/进度链与zmisgod已有封面/本地绘制链，继续研究歌词来源与L6内容提交。未闭合段明确留研究项，保留用户全部内容目标。
5. 当前不写业务代码、不建立产品工程、不构建/安装或连接设备；用户另行明确要求开发后才执行未来对标。
