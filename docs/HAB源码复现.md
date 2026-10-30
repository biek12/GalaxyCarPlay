# AutoDisplay v3.6.2 HAB：静态还原与自有源码复现

更新：2026-10-29。用户明确采用现有v3.6.2继续，不等待v4.3.9；最终实现要求为**解析native、在本仓库写源码**，不是注入、加载或打包第三方发送库。

**晚间更新**：已有现场双向HAB/共享区导入释放、自有合成fixture的AUTH/INIT/ACK/首帧协议校验；Screen临时程序崩溃，未证明画面提交。现已补正式自有QNX PMEM/Screen会话源码及主机失败回归，详见[HAB通道修复](HAB通道修复.md)。下文原轮次的“未执行目标代码/未连接车辆”仅指16时的静态复现；本次代码修复没有再次连接设备。

## 1. 本轮实际完成和未完成

- 已删除先前误写的三个JNI/投屏包装文件，并撤销debug源集引入整个APK `lib/` 目录的配置；没有把第三方SO复制到产品目录。
- 已静态还原Android native发送端，不再以“JNI不可见/没有SDK”为由停止。自有C实现包含：HAB驱动请求、共享区布局、AUTH/INIT/FRAME/STOP/ZORDER编码、open/ACK/close状态机、环形帧提交、像素转换、采样hash、资源清理。
- 已通过本机C合同测试和Android API29/arm64交叉编译。**未接入APK/CarPlay会话，未执行Android目标代码，未连接车辆，不是端到端显示验收。**
- 没有复制协议认证密钥。标准HMAC-SHA256算法已由本仓库源码实现并通过RFC4231/独立hashlib向量；32B认证输入由调用方显式提供。没有提供者或签名失败返回错误，绝不填零冒充有效认证。状态机测试使用明确标记的假签名，另有算法真值测试；两者都不能证明已获原版receiver认证。
- AutoDisplay原receiver部署分支来自后端payload；现有APK内只有加密loader，未取得其明文代码/ELF。本仓库现已另写自有QNX后端，不依赖取得原receiver；原厂库只作本机ABI依据。QNX窗口实际post、slot并发消费及原车显示恢复仍需验证。

## 2. 材料与复核入口

样本APK自报3.6.2 / versionCode50，来自固定研究快照 `6821b69482dd5140fa77bcf46bb250524c1a5699`，非未修改官方发布的保证：

| 对象 | SHA-256 |
| --- | --- |
| APK | `39c676b4f1943d2871c4558f4e8934901badf56ec02b72a57d5fafb5a000bc6e` |
| Android发送ELF | `048281c8f0396508c3815c5dfbd003497be435ecf2888b8caff0f3053b1e9aed` |
| APK中的HAB驱动封装ELF | `adec25b10de2d3d19d9afdffe22f84bb17bd3d1abf008b57bf515e8ac248886d` |

[完整发送端规格](<../.local/research/hab-native-sender/协议重实现规格.md>)、[发送端反汇编](<../.local/research/hab-native-sender/native.disasm.txt>)、[驱动封装反汇编](<../.local/research/hab-native-sender/libuhab.disasm.txt>)、[QNX部署静态记录](<../.local/research/hab-qnx-receiver/receiver-contract.md>)保存在被忽略的本机研究目录。地址均为ELF虚拟地址；本页不附原始密钥字节或载荷。

公开交叉证据：[Qualcomm HAB Linux UAPI头](https://github.com/LineageOS/android_kernel_qcom_sm8150/blob/285869032633b3de3c6bdefd8f3b553f988c11da/include/uapi/linux/hab_ioctl.h)与样本恢复的六项结构/请求相符；这个公开内核不是目标车固件，不能用来宣称车端ABI/权限已验收。产品结构为按接口事实自行声明，未导入原厂库或反编译业务代码。

### 已修正的旧结论

- `libautodisplaycamera`名称含camera，但其导出/指令也实现raw-frame HAB发送。不能仅凭库名认定只负责摄像头，也无需因此加载整个库。
- `nativeOpen`四个数组是display IDs、widths、heights、**zorders**，之后是**screenCount、slots、timeout**；不是fps入参。fps在Java取帧循环节流。证据：[数组与调用](<../.local/decoded/AutoDisplay-v3.6.2/jadx/sources/com/simplerbit/autodisplay/stream/HabRawFrameStreamService.java#L410-L529>)。
- native与当前Java分支最多2流，非报告中的3流。`virtual_cluster`为display1、1920×532；`virtual_hud`为display3、800×480。[配置](<../.local/decoded/AutoDisplay-v3.6.2/jadx/sources/defpackage/kt.java#L17-L83>)。这是样本车型配置，不是新工程实车验收。
- 先前把第四数组称为不明boolean flag并限制0/1不正确；自有[样本profile](<../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyL6HabFrameFormat.kt>)已改为`zOrder`，撤销错误的boolean限制。

## 3. 别人的实际发送逻辑

### 3.1 内存和生命周期

native入口：open `0x155a0`；frame `0x1693c`；z-order `0x17d04`；close `0x17e38`；清理实现 `0x16244`。

```text
校验1/2流和2..8 slots
  → socket_open(MMID=0x580259, timeout=10000, flags=0)
  → posix_memalign(4096,totalBytes)，整块清零
  → export(用户VA,totalBytes)，不是dma-buf fd
  → 184B AUTH → 64B INIT → 一次64B ACK
  → 工作：像素转换 → 内存屏障 → 64B FRAME通知
  → 结束：签名STOP → unexport → close VC → close driver fd → free
```

每流：`stride=width*4`；`slotBytes=alignUp(stride*height,4096)`；`base[0]=0`、`base[1]=slotBytes[0]*slots`；`total=sum(slotBytes*slots)`。样本按32位算大小，自有实现用64位中间值检查溢出。

全局u32 sequence在转帧前递增，首帧为1，slot=`sequence % slots`，所以首帧不是slot0。每帧只通知，不重新export。原实现无内部锁，Java owner负责线程/handle所有权；自有C会话同样要求串行owner。

### 3.2 64字节消息

全部为u32 little-endian。magic数值`0x48465231`，实际字节`31 52 46 48`，不能直接写反向ASCII字符串。

| offset | INIT(type1) | FRAME(type2) | STOP(type3) / ZORDER(type5) |
| --- | --- | --- | --- |
| 00/04/08 | magic / 1 / type | 同左 | 同左 |
| 0c | 0 | globalSequence | currentSequence |
| 10 | exportId | exportId | exportId |
| 14/18/1c | 流0宽/高/stride | 当前流宽/高/stride | 0 |
| 20/24/28 | totalBytes / format1 / flags0 | 同左 | 0 |
| 2c | 全共享区hash | 每30序号的当前slot hash，其余0 | 控制MAC截断4B |
| 30/34/38 | slots / 0 / slotBytes | slots / sequence%slots / slotBytes | 0 / (STOP=0，ZORDER=streamIndex) / 0 |
| 3c | session低32位 | streamIndex | STOP=session低32位；ZORDER=zorder |

依据：INIT `0x15cfc–0x15db8`，FRAME `0x16f04–0x16ffc`，STOP `0x16280–0x16314`，ZORDER `0x17d54–0x17df4`。

ACK只检查长度64、magic、type4、status(+0c)=0。样本没有检查其version/export/session，不能猜其余字段。该HAB封装的recv超时形参实际上被忽略，驱动结构没有timeout，**不得宣称ACK等待10秒必定结束**。无逐帧ACK，send返回0不等于对端已消费或屏上已显示。

### 3.3 认证和hash

AUTH共184B：magic `0x48464131`、version1、type6、length184；+10 session64、+18 nonce64、+20 wallclock微秒；+28 exportId；+2c..50为首流宽高、stride、total、format1、flags0、slots、slotBytes、display及保留字段；+54 streamCount。+58/+60/+68/+70/+78/+80/+88/+90分别为两项width/height/stride/slotBytes/slots/base/display/zorder数组，属于**SoA**，不是两个连续stream结构。

+98..b7为HMAC-SHA256。签名输入为全部184B，且末32B清零；不是只签前152B。STOP/ZORDER的签名输入是36B LE32数组：`magic,version,type,sequence,exportId,word34,word3c,sessionLow,sessionHigh`，只取摘要前4B。STOP中sessionLow会重复出现，不能去重。

采样hash为FNV式u32：初值811c9dc5，逐采样字节`(h XOR byte)*01000193`；size>65536时步长97，否则1，最后`(h XOR size)*01000193`。不是CRC/HMAC。

### 3.4 像素与提交

源为ImageReader plane0的buffer、rowStride、pixelStride；常规RGBA输入逐像素变为`B,G,R,255`，目标stride=width*4。样本通用pixelStride分支同样已核；自有转换额外检查容量/溢出/重叠，不复刻原实现的边界漏洞。复制完成后`dmb ish`，再发FRAME；没有帧ACK或消费fence，按模覆盖slot不能被描述为有所有权回执的队列。

### 3.5 六项驱动UAPI

实际设备fd与OPEN输出的VCID是两种资源，不可互换。请求结构自有声明在[transport头](<../galaxy/common/src/main/cpp/hab/galaxy_hab_transport.h>)。

| 操作 | ioctl | size |
| --- | --- | --- |
| OPEN | c0100a04 | 16 |
| CLOSE | 40080a05 | 8 |
| SEND | 40180a02 | 24 |
| RECV | c0180a03 | 24 |
| EXPORT | c0180a06 | 24 |
| UNEXPORT | 400c0a08 | 12 |

依据：APK驱动封装 `0x20e4/0x237c/0x2500/0x2630/0x27c4/0x2e0c`。EXPORT是页对齐用户VA和页对齐字节数；send返回0成功，不返回bytes。

## 4. 本仓库自有实现

| 文件 | 职责 |
| --- | --- |
| [transport](<../galaxy/common/src/main/cpp/hab/galaxy_hab_transport.c>) | 六项ABI、单会话fd/VC/export所有权、I/O注入和清理 |
| [Linux后端](<../galaxy/common/src/main/cpp/hab/galaxy_hab_linux.c>) | 直接open/ioctl/close；无dlopen/dlsym/构造器 |
| [protocol](<../galaxy/common/src/main/cpp/hab/galaxy_hab_protocol.c>) | 布局、LE编码、AUTH与控制签名输入、INIT/FRAME/ACK及hash |
| [pixels](<../galaxy/common/src/main/cpp/hab/galaxy_hab_pixels.c>) | stride安全的BGRA转换，强制不透明 |
| [HMAC](<../galaxy/common/src/main/cpp/hab/galaxy_hab_hmac.c>) | 自有标准SHA256/HMAC实现，显式32B key输入，无样本key或默认key |
| [sender](<../galaxy/common/src/main/cpp/hab/galaxy_hab_sender.c>) | 自有open→AUTH/INIT/ACK→ring frame→STOP/close生命周期，不调用私有native函数 |
| [receiver核心](<../galaxy/common/src/main/cpp/hab/galaxy_hab_receiver.c>) | 自有AUTH/INIT/帧/控制校验和私有slot复制，不负责平台映射 |
| [QNX后端](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c>) | PMEM import/map、Screen可写buffer、ACK准备顺序、post及统一释放；主机合同已测，修复后目标未测 |
| [CMake](<../galaxy/common/src/main/cpp/hab/CMakeLists.txt>) | 自有静态库及独立QNX目标；不进入Gradle/APK，不自动启用设备访问 |

认证提供者为空时不会打开设备。签名回调输出的正确性/授权、跨域缓存一致性、驱动阻塞取消及原receiver实际检查仍未实机验证。本轮source sender不是可用APK交付，不以一个编译通过的库替代完整链路。

## 5. 可复现验证

主机测试已加入[统一验证脚本](<../scripts/verify.sh>)的unittest discovery；独立运行或只交叉编译自有代码：

```sh
python3 scripts/verify_hab_native.py
python3 scripts/verify_hab_native.py --android
GALAXY_HAB_TEST_CFLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/verify_hab_native.py
scripts/verify.sh --quick
```

[验证入口](<../scripts/verify_hab_native.py>)只使用已安装NDK，支持`--ndk`/`GALAXY_ANDROID_NDK`指定，不下载SDK。交叉编译输出为被忽略的`.work`静态库，不加载或运行。

2026-10-29本轮：5组C合同测试（含ASan/UBSan）及1组私有库接线守卫通过、Android29/arm64六个C源编译为自有静态库通过；最终回归命令与时间见[源码核验](<源码核验.md>)。测试覆盖单/双流SoA、字节序、AUTH全184B/控制36B签名范围、无签名/签名失败、独立hash golden、像素步长1..8/行padding/容量/溢出/alpha、完整假驱动握手、跨流全局序号/首slot1/30帧hash、无逐帧ACK、各open阶段失败、非法ACK、清理和重复关闭。另补不同slotBytes、stream1的30号hash、slots8、u32回绕和STOP签名/发送/VC关闭失败顺序；标准HMAC以RFC4231及36/55/56/63/64/65/152/184/百万字节的独立hashlib向量校验。状态机签名与I/O都是自有fixture，未用真实密钥、驱动或QNX库。

## 6. 继续工作的位置

1. 将完整111地图与独立音乐画布的输出接入自有sender，需要明确图像生产者的会话代次、输出尺寸和退出所有权，不再新增私有库加载包装。
2. 验证新增自有QNX接收端：import、格式解释、slot消费、Screen窗口及显示恢复。现场已有部分链路实测，新增后端的主机合同不替代修复后目标运行与可视验收。
3. 认证机制已恢复结构与算法，但没有复制样本固定认证常量；源码接口不自动绕过认证，也没有声称能同现有原receiver互通。
4. 目标固件driver ABI、权限、内存缓存、性能和最终地图/音乐可视输出另做明确授权的台架/实车验收；本轮没有设备操作。


后续现场验收更新：已在指定首验车完成6+35帧人工画面、截图及退出释放，用户确认看到测试帧；不是CarPlay内容合成或正式SDK构建验收，见[HAB实车验证](HAB实车验证-20261029.md)。
