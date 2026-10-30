# AutoService ADB / Root 实车闭环

2026-10-30，本轮在用户指定银河L6 2023 Max / FlymeAuto 2.3.1、Android 11/API30直接连接调试并安装AutoService，未更换签名，`pm install -r`保留数据。用户后续明确要求不再运行单元测试，直接修改App、安装并实车调试；本轮后续照此执行，不以离线测试为安装前置。

## 原因与处理

- 车机原先安装摘要为`6ef0f18e5e281062a98407994a9f96fd14b0f4f45ed85bc92a961fac867cb98d`，运行旧版诊断v1，停在`platform:prepare_failed ConnectException`，没有自有Root helper。本地较新的首次准备代码尚未安装，因此继续调试旧包不能验证当前实现。
- 已对照星枢`nexusx/src/main/java/com/ecarx/nexusx/adaptation/system/adb/root/RootAdbKeyAuthorization.kt:35`的公钥备份、登记、0640/system:shell、restorecon及回读，以及`AdbAuthorizationRecovery.kt:29`的helper认证、登记、adbd重启和端口重发现。星枢`docs/progress/root-adb-debug-log.md:15`起记录其历史实车链路；车机逆向`docs/research/live-root-debug-2026-09-17.md:7`记录Android TCP/23 UID0入口。参考目录保持原样。
- 本项目首次路径为[PlatformAccess.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/PlatformAccess.kt:99)：本App身份生成→本机Android root入口启动绑定UID/PID/starttime的自有helper→登记本App公钥→恢复自身权限和TLS→本AppKADB连接及Root协商→新连接UID回读。正常恢复优先使用自身TLS/ADB启动helper。
- 实车发现取消/重新准备可能使两个已派发的helper短暂竞争socket，旧实现抛出`Address already in use`。在[RootServiceMain.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:25)先取得监听权，再创建线程和车辆资源；未取得监听权的候选进程直接退出，现有helper继续服务。
- 一键授权脚本还存在独立误判：实际`dumpsys activity service`给`FOUNDATION_CHECK`行加缩进，原解析只接受列0，App已经成功仍会超时。已修正[prepare_autoservice_adb.py](../scripts/prepare_autoservice_adb.py:47)；增加`--apk`定向核对已安装AutoService和`--app-only`不从电脑预先赋权，使核验能覆盖App自身首次注入。

## 已实际通过

最终已装AutoService APK SHA-256：`85d2131aba02083829ccc66f34a4e088dd63e43d7343a96848a6d78eda1f5044`。

1. 当前App自身TLS/ADB UID0、认证RootService执行`id -u`返回0；公钥在白名单、0640/1000:2000、SELinux为`u:object_r:adb_keys_file:s0`。
2. 保留App数据和RSA身份，暂时移除本App公钥、撤销本App两项权限、关闭无线ADB/TLS，并终止App；重新启动后由App自行恢复授权及TLS并重新验证两个UID0。其它ADB客户端记录经车机内比较保持一致；未导出私钥或公钥原文。
3. 再次结束App进程后重新启动，新的App PID/owner及新helper完成自身ADB/Root恢复。此项为进程恢复，不是整车断电或真实开机验收。
4. 用户要求停止脚本后，直接经车机shell读取：`FOUNDATION_CHECK`为`verified`，`adbUid=0`、`rootUid=0`、`tlsPort=42829`、`identitySource=autoservice_private_adb`；当前App PID26082、UID10142，自有helper PID26159、UID0。已打开AutoService主页面。

本轮脱敏记录：[实车记录](../.work/logs/autoservice-foundation-field-20261030.json)，起始时间2026-10-30T19:24:38+08:00。端口和PID仅为当时值，不硬编码为产品配置。

定向核验入口（不安装、不从电脑预先赋权）：

```sh
python3 scripts/prepare_autoservice_adb.py --host '<当前Android root入口>' --apk galaxy/service/build/outputs/apk/debug/service-debug.apk --app-only --output .work/logs/autoservice-adb-current.json
```

用户要求后续直接测App，因此当前现场不再运行该脚本或单元测试。直接只读回读命令为`dumpsys activity service com.ecarx.autoservice/.AutoService --foundation-check`。

本轮没有重启整车，没有验收QNX、HAB图像、原车告警或性能；这些不由ADB/Root成功代验。
