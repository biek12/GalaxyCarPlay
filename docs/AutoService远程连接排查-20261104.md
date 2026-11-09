# AutoService 远程一直连接中：Telnet 排查

2026-11-04，用户明确授权通过工程既有 Telnet 连接车机排查。本轮仅诊断，没有安装 APK、重启 AutoService、变更权限、改动原厂服务或车辆控制。地址从当前网络邻居发现，不保存实际地址；Android Linux、UID0、AutoService 包存在已回读。

## 已取得证据

- 20:23 左右，车机 AutoService PID 26126、UID 10142 的 `remote_connection` 持续出现：`SocketException → NoSuchAlgorithmException → KeyManagementException → KeyStoreException`。栈位于 `DefaultSSLSocketFactory`、`SSLContext.getDefault`、Conscrypt 的 `createDefaultX509KeyManager` 和 `KeyStore.getInstance`。请求尚未进入 HTTP 响应检查。
- 电脑请求公网 `/galaxy/bridge/health` 返回 HTTP 200 和协议版本1；仅证明此路径当时可用，不代验车机蜂窝出网。服务器 SSH 本轮公钥认证被拒绝，未读取服务端日志。
- [AdbIdentity.kt:17](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AdbIdentity.kt#L17) 在进程内先 `Security.removeProvider("BC")`，再构造新的 BouncyCastleProvider 并插入。构造发生在移除之后，其他线程存在查不到 BKS Provider 的窗口。自身 synchronized 只约束该方法，不约束远程 HTTPS 线程。
- [AutoService.kt:343](../galaxy/service/src/main/java/com/ecarx/autoservice/AutoService.kt#L343) 启动基础准备后调用 `updateRemote`，两条工作线程可并行。远程请求通过 [RemoteHttp.kt:26](../galaxy/service/src/main/java/com/ecarx/autoservice/remote/RemoteHttp.kt#L26) 使用系统默认 HTTPS。
- 在车机独立 `app_process` 中使用已装 AutoService APK 的 Provider 类进行诊断：正常系统 BC 1.61 和完整注册后的自带 BC 1.83 均能取得 BKS 和 TLS SocketFactory；人为模拟移除 BC 的窗口后，首次 TLS 初始化复现相同异常链，并得到完整底层错误 `KeyStoreException: BKS not found`、`NoSuchAlgorithmException: BKS KeyStore not available`。该操作只影响临时进程，没有改动运行中 AutoService 的 Provider。
- [RemoteBridge.kt:45](../galaxy/service/src/main/java/com/ecarx/autoservice/remote/RemoteBridge.kt#L45) 将异常统一上报 reconnecting；[ServiceActivity.kt:136](../galaxy/service/src/main/java/com/ecarx/autoservice/ServiceActivity.kt#L136) 在没有验证码时不区分该状态，持续显示“正在连接服务端…”。

## 结论及边界

当前直接失败点是车机 App 内 TLS 初始化，尚无证据表明是服务器认证、429 或验证码导致。源码竞争窗口与车机同固件复现高度吻合，首要根因是 ADB 初始化全局替换 BC 与 HTTPS 初始化并发。完整替换后的 Provider 本身能提供 BKS，不能把问题写成 BC 1.83 完全不支持 TLS。

重试持续失败的缓存持有位置尚未单独验证，不将具体缓存机制当成实测结论。原 App 的日志仅保留四层原因链，最底层 BKS 消息来自独立复现，不能混称原 App 已直接输出该消息。

补充尝试在独立进程内用 KADB 内存存储验证保留系统 Provider 的证书生成，进程被终止（返回 Killed），该项未通过；未查明终止原因，不能宣称 ADB 兼容性已验证。原 AutoService PID 回读仍为26126。临时 dex 已删除，探针及不含凭据的输出保存在本机 `.work/remote-tls-diagnosis/`。

## 修复方向（未实施）

优先取消进程级 BC 替换，改为 ADB 证书局部显式使用所需 Provider；先验证现有私钥读取、证书生成及自身 ADB TLS/Root，再验证远程注册与长轮询。KADB 2.1.1 本地字节码 `CertUtilsKt` 已有独立 Provider 实例候选，但仍需完整兼容性验证。界面应明确显示 TLS 初始化失败或重连状态。不能只增大网络超时或只改界面文案。

## 追加：2026-11-04 修复实施（离线与隔离模拟器）

已取消 [AdbIdentity.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AdbIdentity.kt#L14) 对全局 BC 的 remove/insert。第一次模拟器运行证明，仅删除替换仍不兼容：KADB 默认 RSA 解析产出不满足其 `RSAPrivateCrtKey` 要求。故在 [service 构建脚本](../galaxy/service/build.gradle.kts#L50) 对固定 KADB 2.1.1 `CertUtils.parsePrivateKeyPem/derivePublicKey` 的两个 KeyFactory 接点应用窄 ASM 修正，调用 [AdbCrypto.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/AdbCrypto.kt#L7) 的局部 BC 实例。上游版本及调用数均校验，不改 Gradle 缓存、不注册全局 Provider；KADB 其余行为保留。

远程 worker 现将 TLS 初始化、TLS 握手与普通重连区分为固定状态；界面无验证码时也显示真实失败类别，已配对时显示中文连接状态。见 [RemoteConnectionStatus.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/remote/RemoteConnectionStatus.kt#L8)、[RemoteBridge.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/remote/RemoteBridge.kt#L48)、[ServiceActivity.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/ServiceActivity.kt#L137)。不展示异常消息或认证材料。

隔离 Android 11 模拟器已实际通过：系统 BC/BKS 身份与顺序不变、100 次并发默认 TLS 初始化、已持久 RSA 私钥重读且字节不变、重建证书校验、真实双向 TLS 回环。测试只访问模拟器回环，不访问公网或车辆。测试代码见 [AdbIdentityProbe.kt](../galaxy/service/src/androidTest/java/com/ecarx/autoservice/platform/AdbIdentityProbe.kt#L24)。输出保存在本机 `.work/autorecord-emulator/identity-result.txt`。

复现命令：`bash scripts/verify_remote_record_fixes.sh`；真实 Android 隔离回归：`bash scripts/test_remote_record_fixes_emulator.sh`。后者固定检查 AVD 名称与 qemu 身份，禁用该 AVD 中自有服务的恢复组件，沿用工程签名，退出关闭其启动的模拟器。

**尚未实车验收**：AutoService 自身 ADB TLS/Root UID 回读、远程注册/验证码/长轮询仍须同签名更新后的指定 L6 验证。本轮没有连接车机、安装车机 APK 或部署服务器。模拟器证书回环不能代替上述验收。

最终23:46（北京时间）同一隔离脚本再次完成PASS，输出与绑定摘要在`.work/autorecord-emulator/identity-result.txt`及`remote-record-evidence.json`。TLS验证范围仍为系统初始化和回环，不扩写成车机远程注册成功。

最终离线聚焦回归3项（远程错误分类2项、局部RSA身份1项）通过，配套录制/存储58项通过；AutoService Debug、AutoRecord Debug/Field构建通过，沿用既有工程签名。验证入口及产物摘要已绑定到上述证据清单；没有推送或安装车机。

## 2026-11-05 后续服务部署与诊断链

用户补充SSH密钥后，自有galaxyd诊断接收/查询接口已部署；systemd active，公网合成事件的匿名拒绝、授权写读、重复ACK和内容冲突验证通过。此项覆盖上一轮“未部署服务器”的历史边界，但本轮仍未连接/更新车机，不代表车机远程注册恢复已验收。

四个自有App已接入错误与Java崩溃持久化、系统退出恢复事件，AutoService签名/UID校验后中继至自有服务；上传独立于远程Shell短授权窗口，遵守自动运行/诊断上传开关。APK摘要、异常链、来源与允许参数一起上报，缺失trace明确标记。原生上下文按本App/PID/时窗过滤，具体编译/注入验收与限制统一见[实施记录](AutoRecord/持续录制与错误上报实施-20261105.md)。
