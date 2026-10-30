# CarPlay HAB合成与微信语音通话修复（2026-10-30）

**后续更新**：用户已确认实际收到地图/音乐，当前布局另存[快照](仪表布局快照-20261030.md)，后续重新设计。本轮查明旧接收端取消等待后仍ACK、单会话退出及发送失败无恢复的问题，已完成[稳定性修复](HAB连接稳定性修复-20261030.md)和72项定向回归/编译；更新时车机断网，用户确认稍后复车。新版`496c615...`尚未确认安装，不能混用下方旧`776325...`安装记录。

**00:27实车状态更新：尚未保持HAB连接，仪表未变化。** 用户确认重启并连接CarPlay；应用00:24:00.919记录`HAB: ready`，00:24:01.054随即`result=-19 cleanup=-19`，接着pipe IOException。本次导出时Android发送子进程已退出，QNX接收进程仍在，当前轮QNX日志仅有BUILD，未见本轮ACK/帧计数。此前应用UID独立握手/STOP曾得到Android cleanup0与QNX ACK0/close0，这不等于实际App持续帧成功。按用户要求只导出、留待稍后分析，未继续修复或重启。日志保存在本机`.local/research/hab-app-disconnect-20261030-002717.zip`，含两端筛选日志、进程状态、安装/编译摘要及SHA256SUMS；无key、原始语音或完整协议载荷。

本轮按用户确认的“微信语音**通话**对方听不到”修复上行；同时把完整地图视频及独立音乐可视层接到已验证的HAB通道。用户随后明确授权直接安装，功能由用户自行实测。

## 源码与边界

- `galaxy/common/.../GalaxyHabLayout.kt:8`：指定L6固件已验证的QNX输出为display2、1920×532、z4；Android私有VirtualDisplay不使用该屏号。地图111请求1408×528，完整画面居左、不裁切；右侧512×532放音乐。协议物理毫米尺寸没有实测数据，不填写推测值。
- `galaxy/common/.../GalaxyHabComposition.kt:27`：111经独立TextureView解码，音乐来自`GalaxyClusterMusicBinding`→`ClusterMusicState`→`ClusterMusicView`的封面、标题、进度与歌词；已有iAP2导航信息经`ClusterTurnCardView`补充转向/道路/ETA/剩余距离。地图内容由手机实际提供，不宣称所有地图App都提供车道或限速画面，也不以TBT代替地图。
- 同类的`captureFrame`持续排空RGBA ImageReader；3缓冲邮箱`GalaxyHabFrames`只保留最新帧，采集和pipe发送分线程。111协商30fps，当前合成采集/提交上限10fps。音乐静态帧也保持本会话心跳，停止111不关闭独立音乐层。
- `GalaxyHabProcess.kt:16`执行APK安装目录中自有arm64 PIE `libgalaxy_hab_bridge.so`；`galaxy_hab_bridge.c:34`串行AUTH/INIT/ACK、RGBA→BGRA、6槽提交、STOP和释放。不加载AutoDisplay SO。驱动阻塞隔离于子进程，退出先请求正常释放，2秒后仅强制回收自己的阻塞子进程。
- 应用自身的32B随机key保存在私有`no_backup/hab/owner.key`，自有QNX接收端显式配置同一份。没有将公开色块fixture或OEM key内置进APK。实际安装时由应用UID初始化了该私有文件。
- `CarPlayMediaEngine.kt:670`把已有原厂FACETIME输入源对应的`facetime`纳入麦克风请求；`MicrophoneInputPolicy.kt:4`及`AndroidMediaSink.kt:574`让Galaxy已请求上行的`default/compatibility`使用通信录音源、PHONE输入路由、通信模式及焦点所有权。普通导航播放不因此打开录音。重叠通话按流持有模式，迟到的旧停止事件不会提前恢复另一通话的模式。普通电话和Siri路径有回归覆盖。

上述是源码和离线验证结论；本轮微信实际audioType、非零采样、对方可闻，以及地图/音乐在物理仪表可见，均由安装后的指定固件实测确认。

## 构建与已安装版本

```sh
bash scripts/verify_carplay_hab.sh  # 本轮定向回归及assembleDebug，不含Lint
scripts/build_apk_fast.sh          # 用户要求的快速编译，只assembleDebug
```

2026-10-30 00:06：70项Kotlin定向回归通过；自有HAB原生10组合同测试通过。最终快速编译18秒通过，APK包含自有arm64 HAB发送程序，合并清单确认`extractNativeLibs=true`及`com.ecarx.carplay`。最终APK SHA256：`7763250a1e1699a0e6a5cf74ba5dc7e3bf01d70dab9c776f3bfb838d62f27a47`。

2026-10-30：Android root通道传输后核对同一APK SHA256，执行`pm install -r`返回`Success`；包管理器确认`versionCode=1`、`versionName=0.1.0-dev`、minSdk29、targetSdk37。未卸载或清除数据，未更换签名，未跑Lint。安装记录为本地`.work/logs/carplay-hab-install-20261030.json`。

## 配套接收端与用户测试

QNX仍无SDP/qcc；接收程序是已验证ABI路线的本次重编译候选，不能称正式SDK编译。本次增加`serve`会话模式，解除诊断120事件限制；等待连接10分钟，建立会话后5秒无帧/断线会关闭自己的窗口并释放资源，正常STOP也会退出。没有改动原车HMI或启动项。

已部署本次独立目录`/var/data/galaxy-hab-app-20261030`，内有`galaxy_hab_qnx_probe-candidate`和应用自有`app-owner.key`。重新启动本次接收端的命令：

```sh
/var/data/galaxy-hab-app-20261030/galaxy_hab_qnx_probe-candidate serve /var/data/galaxy-hab-app-20261030/app-owner.key 2 1920 532 4
```

1. 重新打开CarPlay并连接手机。测试微信语音通话上行、挂断后的音乐恢复，再对比普通电话和Siri；由用户自行操作通话。
2. 打开手机提供仪表流的地图并启动导航，同时播放音乐；查看地图、转向/ETA、封面、曲名、进度及当前可取得的歌词。若某地图不提供111内容，记录具体App，不拿本地TBT卡片代替验收。
3. 断开CarPlay后确认原车画面恢复。接收端一会话结束即退出，下一轮重连前按上方命令重新启动；不以无限循环抢占窗口。
4. Android可运行包内`android/audio-snapshot.sh`提取有限音频状态、录音源、RMS/零采样比例、发送包数和模式恢复；不录制原始语音。应用日志中的`HAB: ready`/`submitted`及QNX日志用于区分握手、内容和显示阶段。

旧色块现场证据仅属于2026-10-29旧摘要程序；本次APK及接收端不沿用旧现场通过结论。Android私有VirtualDisplay/应用域`/dev/hab`权限、实际内容和微信对方可闻仍待用户实测。

API依据：[Android私有VirtualDisplay](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[ImageReader最新帧和关闭约束](https://developer.android.com/reference/android/media/ImageReader)。
