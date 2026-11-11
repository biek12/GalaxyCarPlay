# 实时背景与 Hook 生命周期接入记录

2026-11-11。本轮只做本机源码、宿主回归和固定固件交叉编译；没有连接车辆、安装、运行目标候选、修改原厂 HMI 或启动模拟器。**目前完成原生候选与服务租约发布，尚未完成整个产品闭环。**

## 已实现

- `kanzi_background/background_frame.h` 固定 GBF1 完整帧合同：1920×532、紧密 RGBA8、首行在上、来源、代次、64位帧序号、到期时间及精确长度。GHL1 将背景与音乐的工作意图分别编码，携带代次和5秒服务租约；不接收任意路径或 Shell。
- `KanziMediaPublisher` 在自有 OTA 桥原子发布 `hook.bin`。背景单独启用也会续租，音乐 GKM1 的 enabled 则仍只由音乐选择决定。配置变化提升代次；停止发布禁用状态，崩溃后由到期时间失效。当前没有因此自动调用 HMI 启动命令。
- `galaxy_hab_qnx` 新增 prepare/acquire/present/close 消费接口。消费者须先准备成功才发 ACK；接收线程仍执行 PMEM cache 维护、私有快照和完整帧校验。原 Screen 窗口消费者保留。纹理消费者不创建窗口，也不操作原厂窗口层级。
- `qnx_texture_consumer.h` 在有效 CarPlay 背景租约存在时选择纹理消费者。把经校验的 BGRA 转成 RGBA，完整写入 QNX RAM 文件后原子 rename。停止、失败和代次变化释放自有像素与帧文件。`handed_frames` 表示交接完成；原 `posted_frames` 只计 Screen 提交。GLC2 POSTED 在纹理模式只表示发送方的槽位已可复用，不能代验 Kanzi/EGL 或实屏显示。
- `background_runtime.h` 在原厂 UI 回调按100ms节拍消费最新完整帧；未变化帧仅读头部。复用已有、经固定固件绑定的 `OptionalFormat`、`Texture::create`、`Texture::setData`、`Image2D::create` ABI，不猜枚举或 CreateInfo 内存布局。保存 `Contents2D/module_BG/BG` 的原子节点强引用，挂接自有 Image2D；换根、槽位冲突、过期、短帧、切源、代次变化及正常退出均撤销。没有修改原节点字体、主题绑定或日夜纹理对象。
- `runtime` 场景模式只在 live 构建启用；音乐和背景共用同一组原厂 UI 生命周期槽位。它没有9秒试验期限，是否工作取决于服务租约。旧驻车诊断模式仍保留期限。
- EGL 回调每秒写独立 UI 心跳；它证明 UI/EGL 回调在运行，不等于背景已挂接或新帧已显示。
- `g733_hook_runtime.sh` 提供显式激活、独立守护和停止。启动前沿既有固定材料校验；先启动守护再结束旧进程。服务租约或 UI 心跳失效时，仅结束映射本次 SO 的进程，并检查原厂进程恢复。没有改原厂磁盘文件、挂载、监控清单或启动项。该脚本只准备，未执行；不应把 shell 语法检查写成恢复时限已实测。

## 产品闭环仍缺什么

1. AutoService 尚未装配该 SO／控制脚本的私有资产部署、激活／撤销调度和运行回执消费；AutoCluster 仍不能凭该候选获得真实运行成功回执。不能以 rootReady 或请求布尔值替代运行回执。
2. 独立元素模式的 CarPlay 地图需求尚未串联成「先发布租约并确认消费者准备，再申请111 Surface／启动对应 HAB 会话」的顺序。当前纹理消费者只会在 HAB 新会话打开时选择；不能在已有 Screen 会话内热切换。为避免租约尚未发布时误走 Screen，本轮没有将元素地图直接绑到旧 projectionRequested。
3. 本机地图的真实生产 Surface／厂商服务合同仍未接入；本地图片向 GBF1 的交付也未接入。界面来源选择不等于有帧。
4. 异常守护与 App 自动恢复调度尚无端到端宿主注入测试；QNX 原生纹理、长时运行、实屏恢复、帧延迟、CPU／内存／线程／FD均未实车验收。RAM 文件存在一次 BGRA 转换和完整帧复制，不是零拷贝，未测量带宽和 UI 线程耗时。

因此三项请求目前不能整体标记为完成；上面的待接装配是编码缺口，不是要求用户再次证明车型可行性或提供原本可由源码定位的参数。

## 可复现验证

```sh
bash scripts/verify_cluster_runtime.sh
bash scripts/verify_cluster_refactor.sh
```

首条覆盖 ASan/UBSan 背景挂接／原节点还原／序号与代次／坏帧测试、纹理消费者像素与释放测试，以及11项 HAB 回归。不连接设备。宿主帧内容为明确构造的测试数据，不是手机视频。

既有两处旧断言曾要求 GLC2 协商后释放 scratch，但当前接收端为修复实车共享槽一致性已保留私有快照；本轮同步断言。另补 QNX cache 的宿主 msync 替身，核对 flags，避免拿 macOS 对 malloc 内存调用 msync 代验 QNX。

QNX 接收端通过 `scripts/build_hab_probes.py --qnx-rootfs <本机固定固件目录>` 生成；这是重建 ABI 候选，不是 SDP/qcc 正式 SDK 产物。固定 G733 live Hook 通过 `scripts/build_g733_background_hook.py --rootfs <本机固定固件目录> --candidate <本地背景候选> --output <新的.local目录> --music-probe --live-music` 生成。摘要绑定覆盖新增背景合同与运行时代码。构建产物与原始固件保持在忽略目录，不上传。

本轮最终结果：`verify_cluster_runtime.sh` 的两组 ASan/UBSan 新增测试与11项 HAB 回归通过；`verify_cluster_refactor.sh` 通过，83项测试、失败／错误0，Android相关编译成功。固定固件 live Hook 与 QNX 重建 ABI 接收候选均编译成功。`git diff --check` 与长期脚本 shell 语法检查通过。以上均为离线结果，未执行长期脚本或验证实车恢复。

## 后续产品装配

用户随后要求优先闭合图片和媒体区域，已补上图片FD导入／解码／跨系统静态帧交付、Hook资产打包部署、长期控制器及实际回执消费。该两项的最新事实见[图片与媒体替换闭环](Cluster图片与媒体替换闭环-20261111.md)。本文“产品闭环仍缺什么”保留为上轮状态；其中地图生产者和独立地图会话顺序仍未计为本轮完成项。
