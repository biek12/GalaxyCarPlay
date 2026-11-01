# AutoService自身ADB密钥白名单：上车第一优先级

2026-10-30用户进一步明确：ADB连接权限白名单/密钥是后续全部流程的基础，先复用星枢与车机逆向已跑通的流程，准备一键执行。这里授权的是AutoService自身生成的ADB RSA身份；电脑已授权的ADB、Android helper UID0与QNX远端UID0各自核对。

## 现场直接执行

先安装**本轮**三个同签名APK；安装可沿此前已授权电脑ADB或既有root通道进行，见[两小时执行卡](上车两小时执行卡-20261030.md)。本脚本不安装/卸载APK，安装摘要不同会停在installed_apk，避免给旧包背书。APK就位后，授权过程只需要当前车机Android root Telnet入口，不依赖电脑已有ADB连接，也不要求手输配对码/私钥。

```sh
cd /Users/anjon/Documents/GalaxyPlay
source .work/vehicle-test/field-ready.sh
field authorize --host '当前车机Android地址，链路本地IPv6需带%接口'
```

地址由现场已连接网络/邻居表落实，旧全局IPv6前缀不可复用，不能使用QNX的内部地址替代Android入口。默认TCP/23来自本车已验证记录，不扫端口、不尝试口令。此入口单独使用方式如下；包外输出目录会自动创建：

```sh
python3 scripts/prepare_autoservice_adb.py \
  --host '当前车机Android地址' \
  --bundle "$(cat .work/vehicle-test/cluster-current.txt)" \
  --output .work/logs/autoservice-adb-authorization.json
```

默认等App完成150秒，超过则明确失败阶段，最多另加 `--wait 300`；不能用等待超时当通过。现场预算：安装加授权/GPU合计优先占用前18分钟，授权卡住则先修它，暂停后续性能/编辑器项目。

## 一键流程实际做什么

1. 本机验包；连接显式Android入口，先执行uname与id确认Linux/UID0；若连错到QNX或非root入口，后续修改不执行。
2. 只读核对车机AutoService的APK SHA-256与本包一致。只为本App授予WRITE_SECURE_SETTINGS/READ_LOGS；开启adb_enabled、adb_wifi_enabled及本车已验证的persist.adb.tls_server.enable，并分别读回。
3. 启动AutoService自动运行并请求同一服务owner重新准备。App配置自己noBackup中的client.pem，导出自己client.pub；私钥不离开App目录，不复用电脑的身份作为替代证据。
4. App沿本机root入口建立自有helper；helper登记公钥到/data/misc/adb/adb_keys。保留其他客户端，原文件先备份在车机同目录；原子替换后回读，修复/验证0640、system:shell及restorecon。已有公钥也验证元数据；普通健康检查不重启adbd，真正认证恢复可显式重载已存在的key。
5. App重新发现真实TLS端口，在同一实际KADB连接验证CNXN/STLS/TLS/AUTH/shell_v2；按原有协商申请ADB Root，adbd重启后重新发现、重连并读UID0。再次用新会话读取id，并通过认证RootService另读id=0。
6. 脚本仅接受**本次触发之后**的FOUNDATION_CHECK，要求identitySource=autoservice_private_adb、adbUid=0、rootUid=0、有效tlsPort。另经root入口核对App公钥确在白名单、文件0640/1000:2000和实际SELinux标签；全通过才输出passed=true。

本次修正了两个提前成功路径：公钥已经存在不再绕过元数据核对；已有helper不再绕过App自身TLS/ADB重新验证。首次bootstrap与已有helper均恢复本车TLS属性，不只写Settings开关。相关实现：`galaxy/service/src/main/java/com/ecarx/autoservice/platform/AdbKeyAuthorization.kt:12`、`PlatformAccess.kt:57`、`PlatformAccess.kt:88`；App的`--foundation-check`只发布固定阶段/数值，不带密钥。

脚本会恢复AutoService自动运行；原来已启用的主题等持久工作状态按App正常逻辑恢复。只处理自有App和ADB设置，不改原车启动项/HMI，不改变车辆控制状态。adbd重启会使既有电脑ADB会话断开，应按新TLS端点重连，不使用固定5555。

## 失败时直接查这一段

| failed_stage | 意义/下一步 |
| --- | --- |
| android_root_entry | 当前Android地址/网络/接口scope或Linux UID0入口未通过；不继续赋权 |
| installed_apk | AutoService未安装或摘要不同；先安装本包，自有同包更新保留数据与签名 |
| permission_tls_prepare | 本App权限或三个ADB开关未成功读回；不是密钥已授权 |
| app_private_identity_authorization | App本次自身TLS/ADB/helper尚未闭环；在AutoService诊断看foundation_failed具体阶段，不能以电脑adb shell或Telnet UID0代验 |
| whitelist_readback | App公钥、0640/system:shell、SELinux标签任一未核实；不导出整个adb_keys或私钥到电脑 |

报告只保存上述阶段、APK摘要、布尔值、TLS端口、两个UID、时间及SELinux标签；不保存连接地址、密钥、公钥原文、证书、配对记录或任意Shell响应。QNX未由此脚本验收，qnx_tested固定false；后续投屏再验远端UID0与接收端。

## 已有成功流程的来源，及本轮边界

- **星枢实车记录**：`/Users/anjon/Documents/ChatGPT/Nexusx_星枢/docs/progress/root-adb-debug-log.md:15`记录2026-10-18公钥恢复/ADB UID0成功；`:16–19`记录开机清空无线ADB/TLS状态、root一次赋予WRITE_SECURE_SETTINGS、之后普通App恢复TLS、ADB uid2000→root uid0→helper的过程。该结果属于星枢历史，不代验AutoService本轮。
- **星枢源码**：`/Users/anjon/Documents/ChatGPT/Nexusx_星枢/nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/root/RootAdbKeyAuthorization.kt:35`为公钥备份、写入、0640/system:shell、restorecon、回读/失败还原；`:73`是完成安装后使adbd重载。`.../adb/AdbAuthorizationRecovery.kt:29`先认证已有helper，再登记公钥、等待并重新发现端口。本项目保留其他客户端且不在每次健康检查重启adbd。
- **车机逆向**：`/Users/anjon/Documents/ChatGPT/车机逆向/docs/research/live-root-debug-2026-09-17.md:7`明确TCP/23为Android UID0入口；`/Users/anjon/Documents/ChatGPT/车机逆向/apps/l6-nexus/tools/enable-wireless-adb-telnet.sh:1`包含root门槛、Settings及persist.adb.tls_server.enable恢复/读回，不把端口存在等于AUTH成功。

本轮在本机验证授权文件更新/其他key保留/元数据失败还原/幂等与强制重载、脚本新鲜度与身份门槛/Telnet回显处理，并按统一入口构建打包。目标车的密钥授权、真实TLS握手/ADB Root、真实开机恢复、QNX身份仍必须现场记录；未连接时不能写成已经授予AutoService权限。
