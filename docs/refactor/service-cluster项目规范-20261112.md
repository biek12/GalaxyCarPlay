# service / cluster 项目规范

2026-11-12。本文件是 AutoService（`galaxy/service`）和 AutoCluster（`galaxy/cluster`）重构的项目边界。编码会话先读完本文和[代码规范](service-cluster代码规范-20261112.md)，再改代码。[加强规划](包与架构加强规划-20261112.md)只排批次和目标包，不单独授权另做一套结构。[Android工程规范](Android工程规范.md)继续约束页面、资源和格式。三份冲突时，产品范围以本文为准，写法以代码规范为准，页面以 Android 工程规范为准。

## 1. 两个应用各做什么

| 应用 | 模块 | 拥有 | 不拥有 |
| --- | --- | --- | --- |
| AutoService | `galaxy/service` | 基础 ADB/Root 身份、Root 进程分发、车辆订阅、媒体与封面入口、原厂元素 Hook、远程与哨兵 | 主题编辑器、卡片绘制、CarPlay 协议会话 |
| AutoCluster | `galaxy/cluster` | 原厂元素的显示意图、普通页面、音乐卡片预览、`SceneData` | Root、QNX、Telnet、车辆采集、整屏投屏 |

跨应用只走现有 `galaxy/contracts` 与 `galaxy/client`。本轮不升契约版本，不改 `applicationId`，不改签名，不新建 Gradle 模块。

CarPlay 继续生产地图和媒体数据。关闭整屏投屏之后，MAP 在线不得接管仪表整屏。原厂音乐卡片、背景替换和 Hook 继续保留。

## 2. 依赖方向

```text
cluster 页面 / 预览
    → cluster 运行时
    → client → contracts
    → AutoService Binder

AutoService 装配
    → ipc / vehicle / media / oem / remote / sentinel
    → platform 具名端口
    → Root 进程分发
    → adb / qnx / hook
```

禁止：

- `cluster` 引用 `com.ecarx.autoservice` 的实现类。
- `platform` 引用页面、View、主题渲染器。
- `oem` 自己拼 Root 帧或写 operation 整数；它只调用 Hook 端口。
- 业务包为了省事引用归档目录里的源码。归档目录不进源集。

## 3. 包是职责边界

包名根保持 `com.ecarx.autoservice` 和 `com.galaxy.autocluster`。一个包只放一种职责。新类按这个表落位，不先放根包再待以后整理。

### AutoService

| 包 | 只放 |
| --- | --- |
| 根 | `AutoService` 生命周期装配、两个 Activity、`SerialWorker`、`ServiceTrace` |
| `ipc` | Binder 鉴权、排队、角色门闩、`ClientRegistry`、快照组装 |
| `vehicle` | 订阅、采集、目录、feed 扇出 |
| `media` | Kanzi、原车封面、本机媒体源、封面文件接收 |
| `oem` | 原厂元素意图、Hook 控制器、回执。不编码背景、不拼 KZB |
| `platform.foundation` | ADB/Root 身份恢复、权限修复、关闭屏障 |
| `platform.adb` | 本机 ADB 连接、密钥、TLS |
| `platform.root` | 套接字、认证、帧、operation 表、归档拒绝器 |
| `platform.qnx` | Telnet、登录材料、旧接收端清理 |
| `platform.hook` | 租约、远端进程、材料投放、POSIX 校验。不生成背景画面 |
| `remote` `sentinel` `startup` `settings` `ui` `diagnostics` `web` | 维持现有边界 |

`platform.foundation` 只恢复身份。身份就绪后，车辆、Hook、媒体各自使用自己的端口。foundation 不调用 Hook，也不准备投屏。

### AutoCluster

| 包 | 只放 |
| --- | --- |
| 根 | `ClusterActivity`、`ClusterRenderService` |
| `ui` 及其 `pages` `navigation` | 已有页面、Presenter、动作 |
| `runtime` | 显示方案存储、状态、外观、可视歌词会话 |
| `preview` | `SceneData`、`SceneMedia`、`SceneSignal`、音乐卡片预览、演示数据 |

`theme` 不再留在活跃源集。场景引擎、`ScenePreparation` 和对应测试进入 `archive/theme-engine/20261112/`。`BackgroundImagePicker` 留在原厂背景页一侧。

## 3.1 原厂背景：谁生成，谁运送

背景画面归 AutoCluster，连接归 AutoService。现有代码把两者都放在 Service 里，这个归属是错的，后续批次要改回来。

| 步骤 | 现在的类 | 归属 |
| --- | --- | --- |
| 选图、缩放到 1920×532、编成 RGB PNG | `ClusterBackgroundPng`、`ClusterBackgroundStore.import` | Cluster。这是画面内容 |
| 把 PNG 嵌进原厂 KZB 的固定区间 | `KzbBackgroundPatch` | Cluster。这是纯字节规则，不打开 QNX |
| 从 QNX 取出原厂 `background.kzb`、校验摘要、把结果部署到远端 | `QnxClusterHook` 里的 Telnet 与 `QnxClusterRemote` | Service。只有这里有特权连接 |

Service 收到的应当是 Cluster 已经生成的固定格式材料。Root 进程不解码用户图片，不决定缩放和 PNG 编码。`platform.hook` 与 `oem` 不得把上述三个生成类收进去当作连接代码。

原厂音乐卡片同样拆开。`KanziMediaFrame` 决定 GKM1 里有哪些字段，这是卡片内容，归 Cluster。`KanziMediaPublisher` 只负责把字节写进原厂通道，归 Service。`OemArtworkWriter` 写封面文件，仍归 Service。本批不搬 `KanziMediaFrame`。

本批还不把这些类搬进 `galaxy/cluster` 模块。Service 现在在 Hook 里直接调用 `KzbBackgroundPatch`，而原厂 KZB 只能经 QNX 读到；若让 `service` 依赖 `cluster` 模块，依赖方向就反了。搬家时 Cluster 先生成材料，再经现有 Binder 把结果交给 Service 投放。在那之前，这些类留在原包，不假装已经归到 Hook。

## 4. 平台注入只允许这些位置

Root 进程是唯一特权注入点。App 侧业务代码不得打开第二套 Root 套接字，也不得把 shell 命令散落到功能类里。

| 位置 | 允许的注入 | 不允许 |
| --- | --- | --- |
| `RootServiceMain` | 按 operation 表调用已有处理器 | 在分支里新写业务 |
| `platform.root` | 帧、认证、具名端口 | 解释 Hook 或车辆语义 |
| `platform.hook` | op11 控制、op12 回执、材料与远端进程 | 被页面直接调用 |
| `platform.foundation` | 恢复 ADB/Root 身份 | 发起 Hook 或投屏 |
| `oem.ClusterHookController` | 调用 Hook 端口 | 写 operation 数字或 QNX 命令 |
| `vehicle` | 调用车辆读/停端口 | 登录 QNX |
| `cluster` | 经 Binder 提交 ELEMENTS 意图 | 任何 Root/QNX 调用 |

operation 数字、是否带 JSON、是否复用车辆连接，只定义在 `RootOperations`。下表是现行合同，重构只改调用方式，不改帧：

| 数字 | 具名 | 帧 |
| --- | --- | --- |
| 0 | 健康检查 | 无 JSON，状态 `root_ready` |
| 1 | 车辆读取 | 复用连接，int 1 + 定长 JSON |
| 2 | 停止 helper | int 2，回复 int 0 |
| 3 | shell | 定长 JSON |
| 4、6 | 已归档投屏 | 拒绝并关闭 |
| 5 | 清理旧接收端 | 定长 JSON |
| 7 | ADB 公钥 | 定长 JSON，受 ADB 开关约束 |
| 8 | 停止车辆源 | 无 JSON |
| 9 | 哨兵控制 | 定长 JSON |
| 10 | 哨兵流 | 非 JSON 回复 |
| 11 | Hook 控制 | 定长 JSON，`stop` 走关闭连接 |
| 12 | Hook 回执 | 无 JSON |

## 5. 活跃源集与归档

活跃源集只编译仍有产品入口的代码。整屏投屏已在 `archive/legacy-fullscreen-projection/20261112/`。主题引擎另放 `archive/theme-engine/20261112/`，不并进旧归档的 manifest，也不加入 Gradle sourceSet。

归档文件保持原字节。活跃代码不 `import` 归档树。音乐卡片只依赖 `preview` 里的 `SceneData`。

## 6. 批次写范围

同一时间一个编码会话只写一个工作树。Leader 工作树 `feat/galaxy-android-r0-20261112` 只收审计后的结果。

| 批次 | 可写 | 不可写 |
| --- | --- | --- |
| 0 验证 | 已完成的测试假协议和脚本路径 | 生产 Hook |
| 1 平台端口 | `galaxy/service` 的 platform、oem、Root 调用点及其测试 | `galaxy/cluster`、归档、规划文档 |
| 2 服务装配 | `AutoService` 迁出的 ipc、vehicle、media、oem 装配 | 帧格式、Hook 命令 |
| 3 主题归档 | `galaxy/cluster` 的 theme、ScenePreparation、卡片 import、主题测试、新归档目录 | `galaxy/service`、旧整屏归档 |

批次 2 等批次 1 审计通过后再改 `AutoService`。两批都要动同一文件时，后一批基于前一批的提交，不平行改。

## 7. 完成不算完成的情况

缺 Android SDK、ktlint 或测试未执行时，状态是未验证。离线测试通过也不写成车端 Hook 或原厂画面已验收。未按本文落包、业务调用点仍散落 operation 整数、或 cluster 仍编译主题引擎，本批不能称为重构完成。
