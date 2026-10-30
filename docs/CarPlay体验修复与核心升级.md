# CarPlay体验修复与核心升级

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
