# service / cluster 代码规范

2026-11-12。编写和审计 `galaxy/service`、`galaxy/cluster` 时与[项目规范](service-cluster项目规范-20261112.md)一起使用。本文规定代码怎么写。不满足本文的移动、重命名或拆类，不接受进入 Leader 分支。

## 1. 先对照再写

改一个类之前写出三行，放在该批说明里，不写进代码注释：

1. 现在的文件、类型、方法。
2. 目标包和类型。
3. 调用顺序里哪几步必须原样保留。

对不上项目规范的包表就停，不另起包名。不确定某个调用是否仍被活跃页面使用时停，把调用点写入回报，不删除。

## 2. 类怎么放

- 一个文件一个顶层类型，文件名与类型名相同。只服务该类的状态、请求或回调写成嵌套类型。
- 一个类型一个主要职责。装配类型只创建对象、连接回调和按原顺序释放，不写业务判断。
- 协议帧、QNX 命令、车辆读取、页面绑定不放进同一个类。
- 不新建 `Manager`、`Helper`、`Utils` 来收纳多个职责。已有类名能表达职责时沿用，只改包。
- 移动类型时同步改测试的包和 import。不留第二份同名实现。
- 仅被一处调用的逻辑，可以留在所有者类的私有方法里。拆成新类型的条件是：另一包需要调用，或同一方法里已经有独立的资源所有权。
- 不引入依赖注入框架、事件总线或新的架构库。跨类依赖用构造参数传进去。测试需要替换边界时，构造函数接收已经存在的函数或接口，生产代码把现有实现传进去。

`AutoService` 瘦身之后只保留：创建 worker、持有各 owner、前台通知、`onDestroy` 的释放顺序。Binder 方法体不留在这个类里。

活跃源集里以后单独拆文件的有：`QnxClusterHook.kt` 与 `QnxClusterRemote`、`NativeMediaSource.kt` 与 `MediaAccessListener`、`AdbEndpoint.kt`、`AdbTypes.kt`、`SceneData.kt` 与 `SceneSignal`/`SceneMedia`、`ClusterDisplayStatus.kt`、`ClusterDisplayActions.kt`、`ClusterFlowPages.kt`、`ClusterAppPresenter.kt`、`LyricsProvider.kt`。主题引擎归档暂不拆。两者都留在现在的模块，这一条只拆文件，不改归属。

## 3. 平台端口怎么写

业务代码使用具名方法，不传 operation 整数。

```kotlin
/** 向已认证的 Root 进程发送 Hook 控制。op11，定长 JSON。 */
internal fun hookControl(context: Context, payload: JSONObject, timeoutMillis: Int): JSONObject
```

规则：

- 数字、是否写 JSON、是否复用车辆连接只出现在 `RootOperations` 和 `RootServiceClient` 的一处实现里。
- `RootServiceMain` 用这张表分发。新增分支前先在表里增加一项；没有表项就回 `unknown_operation`。
- op4 与 op6 继续走 `RootProjectionArchive.rejectIfArchived`，不在业务包里复制拒绝逻辑。
- Hook 控制器依赖具名端口。生产构造传入 `RootServiceClient` 的方法引用。测试传入记录调用的函数。控制器内部不出现 `11` 或 `12`。
- 端口实现保持现有超时、16384 字节上限、ADB 开关和“stop 走关闭连接”的条件。这些条件写在端口上，不散落到每个调用方。

`QnxClusterHook.control` 若拆类型，必须按现有顺序调用：读租约、组装材料、打开 Telnet、交给 `QnxClusterRemote`。命令字符串、令牌、睡眠次数和路径不改。拆分会改变顺序或异常文本时，保持原方法，并在回报里说明。

## 4. 归档怎么写

- 用字节复制。不经过会改换行的编辑器保存。
- 归档树里的 `package` 和源码保持复制当时的原样。
- 活跃文件改包时只改 `package` 和 import。类体、算法、资源 id 和用户可见字符串不动。
- `SceneData`、`SceneMedia`、`SceneSignal` 进入 `com.galaxy.autocluster.preview`。卡片绘制类只改 import。
- 不把归档路径写进 `sourceSets`。

## 5. 注释、import 和格式

本批新写或整文件移动的自有类型、方法使用中文 KDoc，说明职责、线程和失败时谁释放资源。不给每一行加翻译。协议令牌保持原文。

类型先 import 再使用短名。不用星号 import。一个动作一条语句。Kotlin 使用仓库已有 ktlint 1.4.1 和 `format/galaxy/.editorconfig`。工具不在本机时，按该配置手写四空格、UTF-8、LF、文件末换行，并在回报里写明没有跑格式化工具。

不借移动把压缩的一行代码改写成行为不同的实现。只允许为满足“一条语句一行”和 import 规则而换行。

## 6. 禁止

- 在 `oem`、`vehicle`、`sentinel`、`adb` 的调用点写 `request(context, 数字, …)`。
- 让 `cluster` 依赖 `theme` 包或归档源码。
- 为了编译通过而改测试期望、删掉必测、或放宽 `verify_artifacts.py` 的失败条件。包移动时只改它指向的类路径和 import 字符串，检查仍然要求该类存在。
- 修改 AIDL、prefs 键、角色名、QNX 命令和 `hook-health`。
- 在缺 SDK 时把“未编译”写成通过。

## 7. 交回 Leader 前自检

1. 每个新文件的包能在项目规范的表里找到。
2. 业务调用点搜不到裸的 Root operation 整数。`RootOperations` 和帧实现除外。
3. 活跃源集搜不到已归档类型的 import。
4. 生产命令、令牌、超时和释放顺序与移动前一致。
5. 回报列出未跑的编译和测试，不写通过。
