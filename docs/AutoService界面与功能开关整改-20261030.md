# AutoService界面与功能开关整改

2026-10-30，用户要求将AutoService调整为FlymeAuto风格，并提供仪表投屏、ADB、Root等功能的一键关闭。本轮修改独立AutoService，不迁入CarPlay宿主依赖，未在车机安装或连接车辆。后续按用户要求，在本机隔离模拟器运行同一APK并补齐截图。

## 界面

首页使用项目已有Flyme设置页的浅渐变、细边框卡片、蓝色强调与短矩形滑块；日夜色阶参照 `galaxy/common/src/main/res/values/l7_settings_surface.xml:3` 及对应 `values-night` 文件。布局按竖屏组织，可滚动，整行可触控。首页只展示运行概览、仪表/ADB/Root状态、自动运行与四个功能开关，以及诊断入口；原始状态行、Shell和日志留在诊断页。

源码入口：`galaxy/service/src/main/java/com/ecarx/autoservice/ServiceActivity.kt:44`；静态色板、卡片与滑块在 `ui/ServiceUi.kt:12` 和 `src/main/res/values/service_theme.xml:1`，日夜由系统配置选择；诊断页同样使用ServiceUi。

## 开关语义

| 控件 | 开启 | 关闭 |
|---|---|---|
| 自动运行 | 按已保存单项选择运行，并调度开机/异常恢复 | 暂停全部后台工作，取消恢复任务，保留各单项选择 |
| 全部关闭 | 无开启动作 | 自动运行及四个功能同时持久化为关闭，取消恢复任务并停止当前工作；以后需自行选择要开启的单项 |
| 仪表投屏 | 按真实客户端需求输出，沿既有普通App直连优先流程 | 结束输出会话、释放自有窗口链资源，拒绝客户端重新打开物理输出；本地编辑器预览仍可使用 |
| 车辆数据 | 原有全量目录与只读采集范围保持 | 停止采样、失效迟到回调，并注销App和helper侧原厂订阅 |
| 本机ADB | 维护AutoService自身ADB身份和连接 | 取消自身在途ADB操作/后续恢复，不再通过自身流程登记公钥或启用TLS；不关闭整车adbd或删除其它客户端授权 |
| Root服务 | 维护自有UID0 helper；ADB开启时沿既有TLS/Root流程 | 结束自有helper和Root操作，不再自动获取或恢复Root，不执行全车unroot |

ADB与Root可独立选择：Root关闭、ADB开启时仅连接和回读ADB身份，不请求adb root或启动helper；ADB关闭、Root开启时复用已核验的本机Root入口，只准备并验证helper，不附带公钥登记和TLS准备。该分离模式已落入代码，尚未实车验收。车辆读取按用户选择使用Root或普通App身份；后者不伪造不可读数据。Root关闭仍允许仪表普通App直连已运行的接收端，但不自动执行需要Root的接收端准备。

关闭投屏仍沿既有STOP/释放与接收端有限监听机制，不为每次关闭再登录QNX杀进程，不修改原车启动项、HMI或原车窗口层级。

## 生命周期接入

- `settings/ServiceFeatures.kt:7` 定义四项独立持久键；旧安装没有键时全部默认开启，保留之前行为。设置先提交，再通知Service，工作线程也能检查当前选择。
- `AutoService.kt:306` 集中应用选择：ADB/Root变化使基础连接换代；车辆采集单独停止/重启；投屏开关作为客户端租约之外的总门控。单项功能开关不会反向修改用户保存的自动运行选择。
- `AutoService.kt:351` 对物理投屏统一门控；`projection/GalaxyHabComposition.kt:260` 在Root策略变化时只重建需要Root或正在等待接收端的输出，普通身份已经连通的会话保留。
- `platform/PlatformAccess.kt:34`、`:89`、`:141` 拆开ADB连接、Root准备及无ADB的既有本机入口；Shell、配对、ADB root及helper连接也检查功能选择。`platform/RootServiceClient.kt` 仅为停止旧helper保留认证清理入口。
- `platform/PlatformCoordinator.kt:129` 取消恢复及在途接收端准备并串行释放；新代次和旧回调隔离。Shell的Root请求也纳入可取消操作。
- `platform/RootServiceMain.kt:90` 新增自有操作8，关闭helper内车辆source；只用于停止自身只读订阅，不增加车辆控制。`vehicle/VehicleCollector.kt` 的停止路径调用它。
- Binder快照新增 `automatic`、`features`、`projectionRequested`，保留现有IPC版本和字段；已停止的基础身份诊断返回stopped，不能继续沿用旧verified状态。

以上为源码实现结论；“关闭后无残留、不会重新拉起”的目标需要按下方现场用例验证。

## 构建结果与产物

本轮只执行 `:service:assembleDebug`，未运行单元测试、Lint或Python验证循环。最终Gradle输出 `BUILD SUCCESSFUL in 20s`；日志为 `.work/logs/autoservice-ui-build-20261030.log`。

可复现构建命令（工程根目录）：

```sh
ANDROID_HOME="$PWD/.work/android-sdk" ANDROID_SDK_ROOT="$PWD/.work/android-sdk" GRADLE_USER_HOME="$PWD/.work/gradle-home" ./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false :service:assembleDebug
```

产物：[AutoService.apk](../.work/vehicle-test/autoservice-flyme-ui-20261030/AutoService.apk)；[包摘要](../.work/vehicle-test/autoservice-flyme-ui-20261030/manifest.json)。APK为14,196,637字节，SHA-256 `5f1e2f75e9b62d8e69d5050bae91ca24b7341221098828990a99ab2831d7f83c`。apksigner核验通过，证书摘要与22:48断流修复包一致；aapt2确认applicationId仍为 `com.ecarx.autoservice`。本轮沿用已有GLC2 v2原生产物，不宣称此前断流和原车告警已解决。

首轮只有编译、资源打包、签名与应用身份核对证据；后续模拟器运行结果见下节。仍没有车机触控或开关现场验收结果，也未重新运行完整Cluster preflight，不复用旧128项回归代验这些新增逻辑。

## 补充：同一APK的模拟器运行截图

使用新建隔离实例 `AutoService_UI_API30`，不修改原有星枢AVD或其数据。Android11/API30、1440×1920、240dpi，x86_64镜像声明支持arm64-v8a安装；没有原厂车辆服务。只对本机 `emulator-5582` 执行安装、页面启动、主题切换、点击、截图和自身状态回读，没有电脑远程ADB连接车辆。

安装后回读模拟器中base.apk的SHA-256，与本页产物 `5f1e2f75e9b62d8e69d5050bae91ca24b7341221098828990a99ab2831d7f83c` 一致。本次直接运行产品APK，未注入演示车辆值或伪造ADB/Root就绪。日间和夜间均保持车辆/通道实际未就绪状态。

| 日间首页 | 夜间首页 |
|---|---|
| ![日间运行](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/day.png) | ![夜间运行](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/night.png) |

其它截图：[全部关闭](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/all-off-night.png)、[重新打开后保持关闭](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/all-off-after-restart.png)、[诊断页](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/diagnostics-night.png)。

实际点击“全部关闭”后，服务回读 `status=stopped`、`paused=true`、`hab=inactive`、`automatic=false`，四项features均false；强制结束本机App并重新打开后相同关闭选择保留。[运行证据与截图摘要](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/runtime-evidence.json)、[关闭状态](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/off-state.txt)、[重开状态](../.work/vehicle-test/autoservice-flyme-ui-20261030/screenshots/restarted-state.txt)。当次crash缓冲为空。该结果只验页面启动、日夜、全部关闭和进程重开保留；不能代验有真实ADB/Root/HAB时的单项资源释放或原车显示恢复。

截图使用现有ADB的 `exec-out screencap -p` 原样保存PNG。复现截图的本机命令如下；前提是同一隔离模拟器已启动并安装APK，不用于车机：

```sh
ADB=/Users/anjon/Library/Android/sdk/platform-tools/adb
"$ADB" -s emulator-5582 shell am start -W -n com.ecarx.autoservice/.ServiceActivity
"$ADB" -s emulator-5582 shell cmd uimode night no
"$ADB" -s emulator-5582 exec-out screencap -p > /absolute/path/day.png
"$ADB" -s emulator-5582 shell cmd uimode night yes
"$ADB" -s emulator-5582 exec-out screencap -p > /absolute/path/night.png
```

页面启动/主题切换完成后再截图；点击坐标由本次UI层级dump确定，不在其它分辨率下照搬。本次完成后关闭自建模拟器释放主机资源，原有AVD不变。

## 下一次现场验收

1. 首页日夜、文本缩放、竖屏滚动及整行/滑块触控，检查运行状态不被开关的期望值冒充。
2. 投屏期间关闭投屏：自有窗口释放，原车显示恢复；CarPlay/Cluster继续请求或重新连接也不得绕过总开关。重新开启后按实际需求恢复；编辑器本地预览单独核对。
3. 关闭车辆数据：语义快照清除、轮读与两端订阅结束；Root/Shell独立工作。重新开启验证完整目录继续读取。
4. ADB开/Root开、ADB开/Root关、ADB关/Root开、两者全关四种组合，回读自身连接、UID和helper状态；关闭期间触发诊断prepare与Shell，不得绕过选择或把另一开关打开。
5. 在准备/重连期间快速关闭，核对旧完成和旧恢复任务不重新启动功能；关闭Root时普通身份已连通的HAB保持，UID0输出按策略释放/重建。
6. 自动运行关闭后重新打开页面、客户端重连及重启进程，不恢复后台工作；重新开启仅恢复保存为开启的功能。全部关闭后单项应保持关闭。真正开机/系统回收恢复另留现场证据。

这些用例本轮未执行。所有设备动作仍沿已有授权范围进行，不使用电脑远程ADB。
