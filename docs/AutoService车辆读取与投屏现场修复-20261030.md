# AutoService车辆读取与投屏现场修复

目标：银河L6 2023 Max / FlymeAuto 2.3.1 / Android 11 API30。2026-10-30现场直接编译、同签名`pm install -r`保留数据并回读App；遵从用户要求未运行单元测试、Lint或Python验证循环。不修改原车启动项/HMI，不发送车辆控制。

## 本轮已定位和修复

- [RootServiceMain.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/platform/RootServiceMain.kt:36)：复用星枢RootServiceMain的主Looper初始化。目标固件的HandlerThread在Looper.loop访问主Looper，缺少初始化导致车辆订阅后UID0 helper反复崩溃；真实栈为`NullPointerException → Looper.loop:206 → HandlerThread.run:67`。补齐后车辆读数持续返回。
- [EcarxVehicleSource.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/vehicle/EcarxVehicleSource.kt:95)：读取目标车`/system/priv-app/ECarXCarService/ECarXCarService.apk`中的纯ID映射，区分固件声明/已映射/实际读到；未知未映射项保留目录与REJECTED状态。原厂`CarSignalService.sendLatestValueToListener`遍历filter调用getProperty，未映射ID抛异常曾使整次注册失败。订阅按固件映射过滤，配套注销不变。
- 参考原厂`VehicleArea.GLOBAL=0x01000000/MASK=0x0f000000`、`CarSignalManager.getSIVehSpdIndcd()`的area1请求，以及`CarPropertyUtils.toCarPropertyValue()`保留HAL area0的包装行为，只对映射已存在的全局项接收area1→0，不混用车门/座椅区域。
- 原PA字段还用了另一版编号。现在同时枚举目标SDK的SigPropertyIdString和MgrPropertyIdString，并按相同符号名解析仪表语义属性，保留实际propertyId。PA按星枢已核验16字节LE `{availability,data,format,status}`解码，必须1/*/0/0；原始值仍保留。语义采样缓存以解析后的ID保持500ms补读。
- [AutoClient.kt](../galaxy/client/src/main/java/com/galaxy/auto/client/AutoClient.kt:55)：匿名Binder回调内`onSnapshot(snapshot)`原来再次调用自身，导致主线程循环排队而页面/仪表收不到快照。改为`this@AutoClient.onSnapshot(snapshot)`，三个App已同轮更新。
- AutoService主页面展示自身ADB核验UID/TLS、RootService状态、车型、有效/已读/声明数量，诊断增加运行日志查看。
- [ServiceTrace.kt](../galaxy/service/src/main/java/com/ecarx/autoservice/ServiceTrace.kt:9)：App/helper分别记录本地日志，每文件512KiB+一份轮转，30秒去重、128键有界。记录墙钟/elapsed/PID/UID、固定阶段、异常类型、源码栈/原因链，不记录异常message、任意Shell、认证或原始车辆载荷。helper另记录退出原因及未捕获异常，HAB输出失败保留栈。

## 实际回读

截至20:17，目录由3006扩为5912（包含固件Mgr声明），已轮读5912，3986项状态VALID；声明不等于每项实际可用，其余状态仍逐项保留。

现场语义快照：P挡、纯电、0km/h、电量78%、油量47%、燃油续航508km、纯电续航81km、胎压259/258/250/258kPa、外温约19.6°C；各语义项约122ms新鲜度，均在同一App ROOT.ECARX链读取。巡航目标、回收和功率仍UNAVAILABLE，不能以全量计数宣称这些组件已完成实车验收。

主页面已截图确认显示`ADB 已连接 UID0 TLS43787`、`RootService 已连接 UID0`及车辆有效计数。三个App（AutoService/AutoCluster/CarPlay）安装均返回Success；新版AutoCluster已启动。所有ADB证据来自AutoService自身，电脑远程ADB被目标车禁止，不再尝试。

当前网络前缀更换后，已从历史链路本地地址及MAC核对恢复Android调试入口；真实地址仅保存在`.local`，不写公开文档。没有依赖远程ADB。

## QNX当前定位

早期接收端未能启动，定位到password提示不满足原字面匹配、Telnet换行与命令返回码标记不符。后续已修复并用目标支持的`uname`及`pidin -p $$ user`核对QNX真实/有效UID0，完成接收端部署与HAB握手；用户照片确认新版画面显示。实际仍会在帧采样校验失败（QNX `-77`）后断流，用户照片还记录了原车“仪表显示异常，请联系经销商”提示。

自有认证材料按授权读取复用，不进入公开资料、日志或诊断产物。超时只记录字节数、固定prompt分类、随机标记邻接字符类别及异常栈；不记录响应原文。脱敏日志保存在[本机目录](../.work/logs/autoservice-field/)，其中`app.log`/`root.log`、主页面截图与状态回读可用于回溯。

用户离车后已继续完成[断流修复候选包](仪表传输断流修复包-20261030.md)：GLC2 v2、导入缓存同步、私有快照、逐帧采样校验、有限重传与两端诊断。编译/产物绑定已完成，新修复尚未安装或实车复测；原车告警及性能仍独立待验，不由车辆数据/ADB或此前画面成功代验。
