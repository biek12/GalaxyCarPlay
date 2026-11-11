# AutoDisplay 4.6.5 仪表背景与资源核验

AutoDisplay 4.6.5 对银河 L6 有专用的 QNX 仪表背景 Hook。APK 中的控制器临时重启原厂 `cluster-hmi`，通过 `LD_PRELOAD` 加载 Hook，再按节点遮蔽结果决定保留或恢复。另一条路径把壁纸和主题应用到 AutoDisplay 自己的叠加组件。独立 SR 服务还有自己的 Kanzi 运行包、资源清单和窗口。

## 样本与证据

| APK | 清单包名与版本 | APK SHA-256 |
| --- | --- | --- |
| AutoDisplay | `com.simplerbit.autodisplay` 4.6.5 (165) | `be6e23ddc33f05d833cfa5c42187f858a275eab8dd869783dc274e211f21dc26` |
| AutoRecord | `com.simplerbit.autorecord` 1.3.0 (30) | `a7adf02294c554001d6d85d99d6ca6ab02f78add7b20d1d13a1c56ac5ec31239` |
| AutoService | `com.simplerbit.autoservice` 1.4.1 (41) | `362f8f7c62ada2c895dc495e4b70b817db67130dd02d65e04dc784073d78d357` |

用 ZIP 清单核对资产，用 JADX 1.5.6 解码清单、资源和 DEX。三个 APK 的入口都是 `com.sagittarius.v6.StubApplication`。采用公开分析所描述的 Baidu/Sagittarius 格式离线解包，按元数据核对解压长度与 DEX 文件头。AutoDisplay 主业务 DEX 的 SHA-256 为 `8bd9e47dd52e6eb136466e0c22bc7a504d8cc563f09436534817878aca2bf974`。文中的 Java 行号是本地反编译文件行号。JADX 对部分方法有恢复错误，以下调用链也以 DEX 字符串和其中的完整 Shell 字面量交叉核对。研究产物保留于忽略目录 `.local/decoded/AutoFamily-20261110/`。本轮为静态核验和公开网络探测，未运行样本、连接车辆或变更产品代码。[离线解包格式](https://bbs.kanxue.com/thread-291471.htm)

## 原车仪表背景与中间 SR

1. **入口和模式。** 设置页把 `none`、`background`、`sr`、`both` 持久化在 `lab_cluster_background`。专用 `galaxy_l6` 对应 `cluster_background_hook_l6`，另有 `galaxy_l6_l7` 对应 `cluster_background_hook_l6_l7`。手动执行可选 V2 下载或本地调试 SO，统一进入串行执行器。本地调试校验 ELF64 小端、AArch64、ET_DYN、64 B 至 4 MiB 并计算 SHA-256。[模式与车型 sc0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/sc0.java#L92) · [分派 eb0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/eb0.java#L28) · [载荷映射 os.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/os.java#L24) · [本地校验 kt0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/kt0.java#L73)
2. **定位与投送。** 控制器经 QNX SSH 执行。它要求原车仅有一个 `cluster-hmi` 实例，通过 `pidin ... fds` 找当前进程已经打开的 `clusterfunction.kzb`，找不到再查 `background.kzb`。以该文件的父目录定位真正的 `cluster-hmi`，同时读取原进程的调度分区。两个 KZB 在这里用于定位，脚本没有把图片或修改后的 KZB 写回原厂目录。SO 先经 stdin 写入 QNX `/tmp/*.so.upload`，长度校验后设置权限并重命名。[目标发现 kt0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/kt0.java#L24) · [投送 kt0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/kt0.java#L219)
3. **启动与验收。** 控制器结束旧实例，从发现的原厂目录启动同一可执行程序，设置 `LD_PRELOAD` 和 `AUTODISPLAY_CLUSTER_HIDE_MODE`，并保留能识别的原调度分区。至多等 25 秒，要求在新 PID 的内存映射中看到 Hook，且日志报告对应模式的 `ready=1` 和 `background_mask`、`sr_mask`。背景单模式兼容旧版非零 mask 回执。成功后删除临时 SO 和启动日志。日志分支能区分虚表不可用、项目符号或槽位缺失、根节点和树扫描 API 不可用、目标节点没找到。由此可知 Hook 需要匹配 HMI 的 ABI 和节点树，确切节点和透明合成方式仍须检查 SO 本体。[启动与检查 kt0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/kt0.java#L35)
4. **恢复与常驻。** 失败分支结束注入实例，等待原厂实例回到进程表且不带 Hook 映射，最后返回失败原因。显式恢复要求恰好一个实例、PID 与原注入 PID 不同且没有 Hook 映射，再删除临时产物。常驻检查仅在识别为 `ORIGINAL` 时重注入，实例冲突、未启动或检查失败时只重查。这里的回执证明的是进程与映射检查，物理屏幕上的告警、挡位和画面仍需单独验收。[失败恢复 kt0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/kt0.java#L36) · [状态与手动恢复 vb0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/vb0.java#L180) · [常驻回调 zn0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/zn0.java#L295)

## 独立 SR 的 Kanzi 资源

AutoDisplay 另有 `QnxSrCommandsImpl`。网络模式取 `sr` 载荷并校验 `AQNX3`，将 APK 内 `AQNX2` loader 与载荷串接后经 SSH 投送。其工作目录为 `/var/backup/autodisplay_sr_runtime/`，发布 `qnx_sr_kanzi_engine`、`qnx_sr_kanzi_launcher`、模板配置与 `qnx_sr_resources.kzb.cfg`，生成位置和尺寸配置，另起 renderer，再按进程定位它的 QNX Screen 窗口并设置层级、可见性。这个 launcher 分支被客户端标为 `system`。通用命令生成器另外支持把私有 `resource.kzb`、`adas.kzb` 路径写入自己的清单，也支持直接引用从原车 ClusterHMI 目录扫描到的两个 KZB。私有资源分支不能据此视为 4.6.5 网络按钮的默认路径。[当前入口 au0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/au0.java#L97) · [资源列表与启动](../.local/decoded/AutoFamily-20261110/display/jadx/sources/com/simplerbit/autodisplay/runtime/core/QnxSrCommandsImpl.java#L110) · [窗口提交](../.local/decoded/AutoFamily-20261110/display/jadx/sources/com/simplerbit/autodisplay/runtime/core/QnxSrCommandsImpl.java#L214)

## 自定义主题与壁纸

在线目录按车型和资源类别列出仪表布局、HUD 布局、静态或动态壁纸与字体。布局和主题位于 `/sdcard/other/autodisplay/config/` 下，壁纸在 `backsources/`。应用壁纸时，将相对路径写入启用了 `BACKGROUND_IMAGE` 的各屏 `OverlayPlacement.imageName`，再保存并刷新。普通 `.adtheme` 是 ZIP，至少包含 `manifest.json`、`layout/screen.config`、`preview/cover.png`，可以携带配套素材。授权主题另有 `.adtheme.enc` 和 `theme-artifacts` 下载路径。APK 自带两份可读的 Mercedes Blue 表盘配置、配套盘面和指针，另有两个 JSON 布局样本。[文件位置 tn0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/tn0.java#L49) · [壁纸应用 a21.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/a21.java#L2512) · [主题 ZIP 校验 zp0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/zp0.java#L660) · [内置速度表配置](../.local/decoded/AutoFamily-20261110/display/jadx/resources/assets/gauges/speed/mercedes_blue/theme.gauge.json)

## 服务端资源与响应

客户端默认 AutoManager 主机为 `automanager.simplerbit.com`，设置中另有 `beijing.simplerbit.com:8443` 预设。V2 使用已登记的设备密钥打开会话，对 POST 请求签名。QNX 二进制下载还核验响应签名、目标、大小、SHA-256 和有效期。L6 Hook 客户端接收的是 ELF64/AArch64 SO，独立 SR 下载是 `AQNX3`，APK 随附的 `AQNX2` loader 与这两项应分别记账。[默认主机 vp0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/vp0.java#L2067) · [载荷请求 zp0.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/zp0.java#L542) · [签名验证 pa1.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/pa1.java#L322)

| V2 POST 路径 | 2026-11-10 匿名响应 |
| --- | --- |
| `/api/client/v2/qnx-payloads/cluster_background_hook_l6/download` | 两主机均为 HTTP 401 `V2_AUTH_REQUIRED` |
| `/api/client/v2/qnx-payloads/cluster_background_hook_l6_l7/download` | 北京预设 HTTP 401 `V2_AUTH_REQUIRED` |
| `/api/client/v2/qnx-payloads/sr/download` | 两主机均为 HTTP 401 `V2_AUTH_REQUIRED` |
| `/api/client/v2/contents` 和 `/api/client/v2/vehicle-models` | 两主机均为 HTTP 401 `V2_AUTH_REQUIRED` |

具体主题下载使用 `contents/{id}/download`、`theme-artifacts/{id}/download`，配套壁纸也可用 `wallpaper-assets/{id}/download`。匿名目录无资源 ID，未取得在线文件。401 只能证实鉴权入口拦截，不能证实当前后端仍保存每个型号的文件。正常取得 L6 SO 或 SR 运行包需要已登记且具有对应下载权限的 AutoDisplay 会话，或由供应方提供文件。[目录与下载 r6.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/r6.java#L126) · [配套壁纸 a21.java](../.local/decoded/AutoFamily-20261110/display/business/sources/defpackage/a21.java#L1233) · [L6 匿名响应原件](../.local/decoded/AutoFamily-20261110/server/v2-alt-hook-l6.body)

## 与本机银河 L6 材料的对照

- 在星枢 G733 离线镜像的 `/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/` 下找到了 `cluster-hmi`、`background.kzb`、`clusterfunction.kzb`、`resource.kzb` 和 `adas.kzb`，与 APK 的定位及 SR 资源查找条件吻合。原厂 `clusterfunction.kzb.cfg` 也明确列出这几份 KZB。[原厂资源清单](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/g733-offline-environment-20261025/rootfs/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/clusterfunction.kzb.cfg:1>)
- 原厂资源索引给出 `Prefabs/BG`、`Prefabs/BGMask` 及 `module_BackGround` 候选，同一个 `background.kzb` 里也有 `hands_off_warning_level_1/2`。AutoDisplay 自己的 Hook 命中了哪些节点、如何处理透明、是否影响这些资源消费者，需以实际 SO 为准。我们的本车 `ClusterFunction` 虚表挂接已有独立的音乐卡片记录，可用于对照符号和 ABI。[原厂背景索引](</Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/g733-offline-environment-20261025/rootfs/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/background.kzb.txt:76>) · [本项目既有挂接记录](CarPlay原厂Kanzi音乐卡片原型-20261103.md#追加2026-11-03现场连接与加载调试)
- AutoRecord 1.3.0 与 AutoDisplay 内置的 `AQNX2` loader 字节一致，AutoRecord 的网络载荷是 `autorecord_recorder`。AutoService 1.4.1 的车端入口为 `CarPropertyService`，并携带自己的 `autoservice_data_loader` 和车型信号表。在这两份样本中没有定位到上述背景控制器或 L6 Hook。[AutoRecord 下载入口](../.local/decoded/AutoFamily-20261110/record/business/sources/com/simplerbit/autorecord/runtime/V2SecurityClient.java#L271) · [AutoService 服务](../.local/decoded/AutoFamily-20261110/service/business/sources/com/simplerbit/autoservice/service/CarPropertyService.java#L55) · [AutoService QNX 部署](../.local/decoded/AutoFamily-20261110/service/business/sources/defpackage/dk.java#L168)
