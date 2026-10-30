# 2026-10-29 HAB通道修复

本轮按用户“根据当前打通HAB的通道进行本仓库的代码修复”实施，沿用当前 `feat/l6-cluster-hab-qnx` 分支，保留已有未提交改动。只修改本机源码与文档，没有重新连接设备、安装、部署或更改原车启动配置。

## 已有现场证据与边界

| 证据阶段 | 已完成 | 不能由此推出 |
| --- | --- | --- |
| 20:52记录 | Android11/aarch64 ↔ QNX7.0.X/aarch64le，MMID `0x580259` 双向64B收发；24,526,848B共享区导入并读到首尾图样；配套释放返回0 | 系统habtest不校验本项目AUTH，也不提交Screen帧 |
| 随后自有协议测试 | 临时receiver接受公开合成fixture的AUTH/HMAC，完成INIT/ACK和首帧协议校验；Android发送三帧并正常关闭 | 不证明AutoDisplay原receiver认证兼容，也不证明连续帧消费或可视输出 |
| Screen测试 | 枚举到QNX显示端；display ID2，1920×532；创建窗口及buffer成功，读到buffer属性 | 最终运行在取buffer pointer后发生signal11，没有`PIXEL_COPY_DONE`或`SCREEN_FRAME_POSTED`，不能写显示成功 |

来源：[现场记录:7–21](<../.local/research/hab-live-20261029/调试记录.md#L7-L21>)、[最终临时QNX运行](<../.local/research/hab-screen-validation/qnx-fourth-run.txt>)、[发送端退出](<../.local/research/hab-screen-validation/android-fourth-run.txt>)、[signal11记录](<../.local/research/hab-screen-validation/window-debug.txt>)。这些材料继续留在忽略目录，不复制认证材料、OEM库、远端地址或设备标识。

原AutoDisplay v3.6.2的`virtual_cluster → 1`仍是样本输入证据；`GalaxyL6HabSampleTarget.autoDisplayV362Sample()`不是本车QNX运行配置。本车现场的QNX `SCREEN_PROPERTY_ID=2`必须按ID枚举，不按Android display ID、枚举数组下标或对端VCID选屏。现场原车窗口order3，临时测试请求order4；order4尚未证明最终可视效果。

## 源码修复

平台后端集中于[galaxy_hab_qnx.c:154](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c#L171>)，与Android ioctl后端分开编译。没有复制临时程序或第三方receiver实现，复用本仓库已有协议核心。

1. **PMEM所有权**：[open:188](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c#L208>)先`pmem_init`，再`habmm_import(flags=0x10000)`取得opaque handle，最后`pmem_map_handle`取得独立VA；保存本次VC/export/handle，不把handle当Linux fd，也不将对端VCID写死。
2. **可写buffer**：[窗口准备:113](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c#L127>)显式使用`SCREEN_USAGE_WRITE`，修正临时程序的`usage=2`（READ）。CPU写权限定义见[QNX usage flags](https://www.qnx.com/developers/docs/7.1/com.qnx.doc.screen/topic/screen_8h_1Screen_Usage_Flag_Types.html)。这是一处可独立确定的错误；现有日志尚不足以把signal11唯一归因于它。
3. **像素解释与范围**：[buffer校验:85](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c#L96>)核查非空pointer、format、WRITE usage、像素尺寸、stride及字节容量。`SCREEN_PROPERTY_SIZE`对buffer是**单int字节数**，`BUFFER_SIZE`才是**两int像素宽高**，不能误报原程序这里发生双int覆写；见[QNX属性定义](https://qnx.com/developers/docs/7.1/com.qnx.doc.screen/topic/screen_8h_1Screen_Property_Types.html)。HAB内存B,G,R,255对应QNX little-endian `RGBX8888`，没有仅凭“BGRA”名称改为QNX格式21；格式对应参见[Khronos QNX格式映射表](https://registry.khronos.org/VulkanSC/specs/1.0-extensions/pdf/vkspec.pdf)。每帧重新取得可写render buffer，按真实stride逐行复制，不覆盖padding或复用已post的指针。
4. **ACK顺序**：[open:195](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c#L217>)核验INIT、准备全部隐藏窗口和可写buffer后才发送一次ACK；任一准备失败直接清理，不先告诉sender会话已就绪。首帧复制完成才请求显示并post，没有逐帧ACK或消费fence。
5. **正常退出与失败**：[close:48](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx.c#L56>)隐藏并flush本会话窗口，destroy窗口/context，再释放协议scratch、私有像素、PMEM映射、HAB import、VC及PMEM环境。只操作自身窗口，不调整原车窗口层级。记录首个错误并继续其他释放；重复close幂等。真实显示恢复仍待目标验证。
6. **sender缓存**：[z_order:115](<../galaxy/common/src/main/cpp/hab/galaxy_hab_sender.c#L115>)只在签名与发送成功后更新本地z-order；失败保持原值。发送成功仍不代表对端已应用，因为协议没有该控制的回执。

[独立进程入口](<../galaxy/common/src/main/cpp/hab/galaxy_hab_qnx_main.c#L25>)使用QNX正常CRT/libc，显式接收外部32B key文件和QNX display/宽/高/z-order；没有默认key、fixture、登录口令或启动服务配置。信号只请求停止；驱动阻塞recv是否响应EINTR未验证，不虚构可立即取消的保证。库owner支持1/2流；当前CLI只装配单流仪表，不将原协议第二流支持删除。

## 可复现验证

```sh
scripts/verify.sh --quick
GALAXY_HAB_TEST_CFLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/verify_hab_native.py --android
```

[QNX后端合同测试](<../galaxy/common/src/test/cpp/hab/test_qnx.c>)直接编译正式后端，链接主机fake HAB/PMEM/Screen API。覆盖单/双流、实际ID查找、AUTH/车型配置不匹配、open每个外部调用失败、frame每个外部调用失败、空pointer、短stride/容量、格式不符、缺display、双缓冲轮换、row padding、z-order、STOP、清理报错仍继续以及重复close/显式重开。测试Screen头仅在test目录使用，不作为SDK或APK输入。

已有QNX SDK与匹配系统链接库时，可用仓库脚本单独编译目标进程：

```sh
python3 scripts/verify_hab_native.py --qnx --qnx-compiler /absolute/path/to/qcc
```

该命令不部署、不读取认证文件、不运行目标代码；缺SDK会明确失败，不使用Android头文件、手写`_start`或占位动态库冒充正式QNX产物。另有[CMake QNX目标](<../galaxy/common/src/main/cpp/hab/CMakeLists.txt>)，系统名为QNX时才加入独立receiver进程；Android静态库不包含QNX后端。OEM库只作为目标系统链接依赖，不复制进仓库或APK。

2026-10-29 22:43 CST：最终9组HAB检查（含QNX后端主机合同及入口编译）通过，ASan/UBSan无报告；Android API29/arm64七个原有C源静态库编译成功；`scripts/verify.sh --quick`通过35项工具回归、1322锁定文件/505登记差异/3删除。日志为[verify-20261029-224319.log](<../.work/logs/verify-20261029-224319.log>)，该quick未重跑Gradle单测、lint或APK。详细记录见[源码核验](<源码核验.md#49-hab现场通道后的源码修复>)。

本轮没有修改APK源集或执行Gradle；本机未找到qcc，因此**未完成正式QNX目标编译或修复后实机复测**。Android `111完整地图 + 独立音乐画布 → 图像合成/采集 → sender`的运行时装配仍待接入，不把本轮QNX通道修复称为完整CarPlay仪表功能。


晚间后续已按用户明确授权完成指定首验车的6+35帧人工画面、截图、人眼确认和正常退出核验；QNX候选在目标运行成功，但仍不是SDP正式构建。最新结论见[HAB实车验证](HAB实车验证-20261029.md)，本页“本轮未连接设备”指前一轮源码修复阶段。
