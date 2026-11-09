# CarPlay体验修复与核心升级

2026-11-03后续：用户要求实际整改并核验原车DIM完整视频路线；导航字段、封面HTTP/就绪重发、PCM duck与上行诊断的实施见[本轮记录](ECARX对照与L6定向整改-20261103.md)。下方“本轮只追加事实”属于较早排障阶段，不代表后续仍未修改代码；第三方通话根因仍未确认。

## 2026-11-03 通话上行故障的新复现条件

**用户指定L6实测反馈**：第三方应用通话已经进行时重新连接CarPlay，双方均可听见；先连接CarPlay、随后拨打或接听时，对方听不到用户，用户仍可听到对方。用户随后明确微信通话、抖音通话均有问题，发送语音消息也间歇有声/无声；进一步确认普通电话不受上述顺序影响，均有声音。不能把第三方应用的两种连接顺序外推为每个App都已单独对照。安装版本摘要及两种顺序对应日志尚未绑定。

这说明已有通话重连场景下双向语音链路能够工作；排查重点转向已连接会话进入通话时的启动顺序与状态切换。它尚不能单独排除输入源选择、系统录音静音/竞争、处理链或上行协议状态差异，也不构成早前修复已通过实车验收的证据。

**正常对照**：普通电话作为用户已验证正常的场景保留。优先比较其与第三方通话/语音消息的原始输入请求、流类型、格式、启动时机及平台路由；通用的播放/录音并发本身不足以解释故障为何只出现在第三方应用。`L7FactoryAudioProfile.kt:16`虽把facetime及带输入的default/compatibility映射到平台telephony，但`MicrophoneStart.kt:50`仍按原始audioType区分是否定时启动；平台用途相同不等于整个启动过程相同。实际微信/抖音流类型须从本次运行证据取得，不能按App名称推断。

本轮本地源码核对（不是运行轨迹）：

- `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt:225`：首个下行包先调用`onAudioStarted`，随后触发麦克风启动。`MicrophoneStart.kt:50`还允许非telephony输入流在无下行时定时启动，因此应用通话不能全部按普通电话触发路径概括。
- `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:1970`、`:2019`：播放端启动独立线程，在该线程创建音轨并申请焦点（`:2032`、`:2034`）；调用方不等待这些步骤完成。？
- `shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt:168`：录音先`startRecording()`再通知`onCaptureStarted()`；`AndroidMediaSink.kt:630`将该通知接到通信焦点所有者登记。两端没有建立“电话播放焦点就绪后开始录音”的先后保证。
- `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:656`：通信模式在首次输入启动时设置，重叠流按所有者集合保留；仅有这些代码不能证明系统之后没有切换实际模式。

**当前结论**：存在可核验的并发启动顺序，是否造成本车故障仍待对照运行证据，不将其写成已确认根因。后续将两种顺序分别绑定实际audioType、输入源、通信模式、PHONE焦点、录音实际路由/系统静音状态、采样电平与上行发送计数；统计不保存原始语音、密钥或完整协议载荷。重连仅是诊断对照，不作为产品修复。

**语音消息补充核验**：排查须包含无下行的纯输入场景，不能把“等待电话播放轨道就绪”直接作为全部上行的修复条件。`CarPlayMediaEngine.kt:256`为非telephony输入提供SETUP后500ms的无下行启动；`:690`依据audioType及input/dataPort判断输入请求。`MicrophoneInputPolicy.kt:5`中speechrecognition不请求通信模式，而`MicrophoneUplink.kt:99`在Geely检测成立时可选VOICE_COMMUNICATION输入源（实际仍可能优先选固件源）。这是需与运行日志核对的策略差异，不代表已确定微信或抖音语音消息实际使用speechrecognition，也不证明输入源与通信模式必须一一对应。500ms启动、模式/焦点切换、前一轮录音释放及新一轮路由均纳入对照。

本轮只追加事实与源码定位，未更改音频行为、构建、连接车机或安装；文档验证为本地源码行号及差异空白检查。

## 顺序与证据边界

按用户要求，先将既有AutoService/Cluster工作区提交为 `3cc69f0`，再修复CarPlay体验；修复独立提交后才开始核心迁移。不推远端、不连接车机、不安装、不跑Lint。`.local`研究/设备材料不入库。

当前固定旧基线是官方 **v0.2.21 / 49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2**；实际远端新增发布标签为 **v0.2.22 / 28d6002ba00320f0ba745212160b08759e4f60b9**。用户所写0.2.1/0.2.2按这两个真实发布标签落实，不使用main HEAD。

## 微信通话和语音消息

`galaxy/shared/.../media/L7FactoryAudioProfile.kt:16`统一平台用途：facetime，以及确有有效上行配置的default/compatibility，选择telephony。核心保留原始流ID、编解码、采样率、RTP时钟和密钥，只传递`AudioFormat.inputRequested`这一协议事实。`AndroidMediaSink`通过中性平台接口为这些流选择PHONE下行、相同的原厂PHONE_NB/WB/SWB/FB输入源、通信模式、焦点与回声参考；停止仍按原流ID释放。Siri不变，无输入的导航提示不进入电话通道。

旧版只为facetime补通信模式，仍可能优先选择FACETIME输入源，下行也未完整按PHONE处理。本轮改为与电话相同的平台路径。源码、离线回归和编译不等于对方实听；微信通话、按住说话、挂断后音乐/导航/Siri恢复待指定L6实测。

## 原车歌词与封面

`GalaxyMediaKeys`按本代真实会话观察已有歌词状态，完整LRC仅在集合变化时编码，250ms时钟检查只在当前句变化时触发上报。`L7MediaCenterSession`合并并去重发布，切歌拒绝旧歌词，重新注册重发，退出取消观察/时钟。`L7ReflectiveMediaCenter`提供原厂 `getLyric` URI、`getLyricContent`、`getCurrentLyricSentence`和 `updateCurrentLyric(token, sentence)`；来源仍为已核验L6在线源6，原车按键回送保留。独立仪表的歌词查找继续共用，不另建网络查询。

封面和歌词URI增加 `com.geely.dimservice`、原车音乐App的显式读取授权及包可见性。文件使用本App私有缓存/只读FileProvider，保留有界历史，退出撤销授权并清理；不暴露整个缓存目录。

原厂证据根为 `/Users/anjon/Documents/ChatGPT/车机逆向/references/materials/artifacts/jadx/g733-20261024/`：

- `AutoMusic/sources/com/ecarx/eas/sdk/mediacenter/MusicPlaybackInfo.java:36/61/66`提供当前句、歌词URI、完整文本；`IMediaCenterAPI.java:122`及`MediaCenterProxy.java:523`提供逐句更新。
- `XSFMediaCenter/sources/ecarx/xsf/mediacenter/media/EasMediaMainApiImpl.java:849`把逐句更新转交EAS会话；`media/holder/MediaInfoHolder.java:822`读取完整歌词。
- `oneOS_DimService/sources/com/geely/dimservice/untils/image/ImageDownloader.java:39/51`会在DIM自身进程读取content URI，所以仅给媒体中心授权不足。
- **实际限制**：同一DIM材料的`module/media/MediaServiceEntry.java:322/337/342`向原厂下游返回空歌词，`api/media/MediaServiceApiWrapper.java:67`默认忽略逐句通知。不能仅凭本App上报成功承诺原厂仪表歌词已显示；本轮不修改原厂服务。封面路径可读性、原厂卡片/仪表消费结果分别实测。

## CarPlay内部智能显示缩放

用户已确认是连接后的CarPlay「设置」内选项。Apple说明该选项由iOS按屏幕配置决定，不能拿本机PiP自适应偏好替代：[WWDC25原文](https://developer.apple.com/videos/play/wwdc2025/216/)。

发现并修复一处产品迁移遗漏：已有`L7DisplayGeometry`按L6/L7共用1440×1920/13.2英寸面板计算当前视口毫米值，但主屏协商仍用了通用用户宽度/历史最大窗口。新增`CarPlayHostProduct.physicalSize`接点，由`GalaxyHostAdapter`注入已确定面板算法，编码清晰度/图标缩放不改占屏毫米尺寸。

**这是尺寸协商的修复候选，尚未证明就是开关自动回退的根因，也未证明开关已经可保持。** 不添加猜测的缩放协议字段，不强写手机设置。需要同一手机/固件记录开关前后、重连前后的真实表现；若仍回退，继续从实际协商日志定位，不宣称完成。

## 可复现验证

```sh
bash scripts/verify_carplay_experience.sh
```

只运行相关音频/媒体/装配回归和三个APK的assembleDebug，不执行Lint、连接或安装。首轮78项通过（shared23、common55）；最终结果见本节追加记录。

## 核心更新

修复提交完成后，按旧基线/本项目/新标签三方比较。上游原样文件直接取新版本，产品专属文件保留；双方修改文件由Git三方合并，冲突逐项审查。新锁文件、适配差异、冲突处理与验证必须独立记录。

最终验证：2026-10-31T00:08:16+08:00，`bash scripts/verify_carplay_experience.sh`通过；common 55项/0失败、shared 23项/0失败；三个APK assembleDebug通过。CarPlay APK SHA256 `6e28475813677e5df88a32a86492d35add7c822e6c1305a9523865fbbecf849e`，保留于本机`.work/apks/carplay-experience-v0.2.21/CarPlay-debug.apk`。未安装，未作实车功能通过声明。

后续收口：歌词IPC拒绝改为每份内容/每代注册最多3次，独立于曲目/封面发布，避免异常阻断已有信息；缓存加入活动目录保护和进程遗留清理。补充真实AudioRecord路径回归，default/facetime/compatibility均验证电话效果器、停止后模式与录音器/效果器释放。2026-10-31T00:15:10+08:00执行同一命令通过，common 56项/0失败、shared 68项/0失败，三个APK编译通过。最终CarPlay APK SHA256 `a3aec59bb74041c27f4a03745519f2e7cda5a9bf8fb65b9c9e39b7f02febe7a0`。

用户补充复现条件：iPhone 16 Pro Max / iOS 27.2，只有开关回退，没有黑屏或重连。该信息是用户实测描述；本轮没有连接该手机或车机。参考[iOS符号差异](https://github.com/blacktop/ipsw-diffs/blob/8af66bf3a924930979237d896ee447f0f2684ac8/iOS/18_5_22F76__vs_26_0_23A5260n/DYLIBS/CarKit.md)可见CRVehicle.displayScaleMode、CRDisplayScaleInfo与物理尺寸/像素/视口算法，但这是旧版本符号证据，不是iOS27.2运行轨迹或已找到根因。

**只读审查当时的状态：第3项仍待定位/实测，当时未覆盖核心；后续用户明确要求解决冲突并继续，现已进入下述迁移。** 先独立保存三方审查候选，见下方后续记录；这不是核心升级完成声明。


### 已完成的只读核心三方审查

命令：`python3 scripts/compare_upstream.py --tag v0.2.22`。工具只读取本地标签和已提交产品源码，不fetch、不切换参考HEAD、不写当前模块/版本锁。工作区源码有未提交修改时拒绝生成，候选存在时拒绝覆盖。完整逐文件旧/本地/新/候选SHA256见[审查登记](../tools/upstream/reviews/v0.2.22-three-way.json)，本次本地输入是`beb19cb1416a5835fc5d6c539efda1c8e0723e55`。

1420份所选上游文件中：1131份未变、21份仅本地适配、248份仅上游变化、9份双方变化可Git自动合并、11份文本冲突。没有发现新核心文件与galaxy同包同文件名的碰撞。独立galaxy产品文件不属于覆盖输入。候选保存在`.work/upstream-candidates/v0.2.22-28d6002ba003-three-way-beb19cb1416a/product/`，不是可直接安装/已编译的升级版。

| 冲突文件 | 必须保留和整合的内容 |
| --- | --- |
| AsyncDiagnosticLog / SessionLogFile / DiagnosticRedactor | 产品脱敏上下文、代次/字节轮转，与上游异步日志优化；不可选一侧整文件覆盖 |
| CarPlayHostActivity | 产品装配、固定面板/111/HAB接点，与上游屏幕转发、偶数尺寸等改动 |
| ClusterMusicState / ClusterMusicView | 产品歌词、位图所有权与进度时钟，与上游曲目/封面更新和动画 |
| AndroidMediaSink / MicrophoneUplink | 产品音频/输入源/焦点/通信模式多流所有权，与上游导航焦点、静音诊断等改动 |
| CarPlayRuntimeConfig | 产品冻结信道集合与上游已有网络接口覆盖字段并存 |
| galaxy/mobile/build.gradle.kts | 保留CarPlay包名、产品版本属性和原签名；核心版本单独更新，不使用上游应用版本号覆盖产品版本 |
| TelephonyMicrophoneTest | 保留本轮应用录音走电话及多流释放回归，并加入上游新增场景 |

`record_patches.py`已取消固定写死v0.2.21目录，改从baseline-lock.tag取得对应只读基线。真正升级时仍需解决上述11份冲突、核验上游新行为、更新版本锁和重建补丁，再编译回归。本轮没有执行这些产品覆盖步骤。


### 用户后续要求：解决冲突并继续

用户明确“优先保障我方自行的更改，随后是新加的新特性”，并要求继续处理。现已完成三方冲突整合，当前核心锁更新到固定v0.2.22，具体逐项取舍、回归、APK与实车未测边界见[迁移记录](DiPlay-v0.2.22核心迁移.md)。上面的只读候选和未覆盖声明属于前一轮证据；原厂歌词空转发、微信实听及智能缩放回退仍分别待验，不把升级当作问题已消失。
