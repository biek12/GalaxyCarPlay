# galaxy Android 工程与团队交付规范

2026-11-11。适用 `galaxy/` 所有自有代码和新增模块。本文为强制工程约定；不是宣称 Android 只支持 XML。项目本轮选择 Views/XML，保留上游既有核心和已核验绘制能力。用户最新要求覆盖历史任务书的冲突条款。

## 1. 目标和完成标准

重构交付必须同时包括职责、布局、资源、复用、格式、注释、可编译性、有效回归和 Git 历史。只改注释/import、只移动旧整页工厂、只拆出名称为空泛的 Manager，不能称为完成。每批只标记实际完成范围，遗留进入总图。

用户随后明确：common中已有UI组件优先复用，ui只补必要封装。执行[组件复用映射](Common组件复用映射-20261111.md)：本轮XML/资源改造沿既有common组件实现和已迁出的Galaxy实现增量进行，不重做控件算法/交互/样式。纯UI需跨应用解耦时只迁移一次实现，原入口委托；宿主业务留common。不能让ui依赖common形成循环，也不能以“补封装”将现有同样不合规的静态整页工厂原样套壳。

先固定基线和需求，Lead 通读相关生产调用链，给出确定文件/方法迁移表、依赖与行为顺序、资源映射和测试规格；编码 Agent 按图施工。未知接口另行申请探索，不能用本轮重构授权开展新协议或车辆操作。

## 2. 源集、资源和依赖

| 位置 | 应承载的内容 | 禁止事项 |
| --- | --- | --- |
| `src/main/java/<既有包>/` | Kotlin/Java 按功能职责分包；包名兼容 | 借整理改 applicationId、签名或上游命名合同 |
| `res/layout/` | Activity/页面外壳、列表项、普通弹窗、组件静态树；必要时 include、ViewStub | Kotlin 用 LinearLayout/TextView/addView 拼同一静态树 |
| `res/values/strings.xml` 或功能命名文件 | 固定文案、contentDescription、hint、错误提示、完整格式句子 | 中文硬编码、拼接固定前后半句；给遥测/协议数据套本地化 |
| `res/values/` | dimens、styles/TextAppearance、attrs、arrays、integers 等有明确用途资源 | 每个数字都变公共常量；无调用资源；为凑目录生成空文件 |
| `res/values-night/`、`layout-w600dp/` 等 | 已有日夜色阶及实际宽度差异 | 车型 UI 分支、复制整份仅两处差异布局 |
| `res/drawable/`、`res/color/` | 现有矢量/位图、shape、ripple、状态 selector | Canvas 手绘普通按钮/图标/背景；文本符号冒充图标 |
| `res/mipmap-*`、`mipmap-anydpi-v26/` | 确有启动图标时的密度/自适应资源 | 凭空设计图标；把内容插图塞进 mipmap；为凑数复制 drawable |
| `AndroidManifest.xml` | 真实组件、权限、queries、资源标签、exported 边界 | 业务开关、运行状态或未经核验的权限 |
| `src/test/` | 生产代码标准用例、已有宿主夹具 | 字符串存在当功能测试、复制一份被测算法 |

依赖保持 `ui → Android 基础`；`client → contracts`；应用消费公共 UI，UI 不反向依赖服务、车辆、Binder、网络、权限或 Wi-Fi SDK。模块仅声明直接需要的依赖，禁止新建重复工具模块或借重构升级依赖。资源使用所属模块 R；重名用 `import …R as UiR`，不在表达式写全名。

启用 `buildFeatures { viewBinding = true }` 的模块使用生成 binding。inflate 时传正确 parent 和 attachToRoot，避免丢失 LayoutParams；Fragment/View 的 binding 在视图生命周期结束释放。重复数据项由 adapter inflate XML；允许按数据数量添加已 inflate 的项目，不允许循环中手造布局。

## 3. 布局、样式与 UI 行为

- 普通静态属性在 XML/style：padding、margin、gravity、字号、字体、背景、最小触控尺寸、可见性初值、输入属性。Kotlin 负责当前状态、文案参数、选中状态、监听、数据列表和必要测量。隐藏状态不能只改透明度留下点击区。
- 不在 Activity 重复创建 label/card/rounded/button 工厂；公共组件以实际多处调用为依据迁移到 auto-ui。旧 L7 入口继续委托同一实现，不维护两套控件。已有 API 保留必要兼容重载，不强迫一次迁完所有调用者。
- 自定义 View 可用于仪表刻度/歌词绘制、视频 Surface、实际自适应测量。必须逐项登记“为何标准布局不足、静态骨架资源、动态范围、线程/资源所有者”。不能以“自定义 View”重新包住整页 addView。
- XML 可实例化的公共控件提供 `(Context, AttributeSet?, defStyleAttr)` 构造和必要兼容构造；自定义属性走 styleable/TypedArray 并 recycle；不得依赖 Kotlin 私有嵌套类型参与 XML inflation。
- 保留审定 Flyme 日夜颜色、字号角色、密度和触控尺度；sp 用于文字，dp 用于布局。常规触控至少 48dp，已有车机 64dp 等较大命中区域保留；不以视觉控件尺寸代替点击区域。用 start/end，覆盖长中文、字号放大、焦点、禁用和无障碍名称。
- 开关外部回填不触发业务；整行/开关只有一次有效用户事件；禁用不响应。选择弹窗取消不提交、确认只提交一次；关闭、返回和焦点恢复保持原合同。显示主题刷新不重建视频 Surface 或网络会话。
- 页面导航、binding/recreate/resume 不得隐式启用后台、投屏、Hook 或诊断扫描。草稿、提交意图和运行回执分别呈现，连接成功不冒充能力运行成功。

## 4. 文案、图形和常量

1. 所有面向用户的固定语义进入资源：标题、按钮、Toast、错误、状态、通知、无障碍提示。格式句子用 `%1$s`/`%1$d`，数量变体需要时用 plurals；时间/单位按明确语义格式化。设备原始输出和曲名等动态内容无需伪造资源。
2. 保留模块既有语言覆盖；本批新增资源提供默认值。已有中英页面补齐对应翻译，不将以前仅中文页面擅自扩为新语言产品要求。包名、MIME、协议 token、文件键应明确不可翻译。
3. 代码直接引用类型先 import：`Intent(context, VehicleAccountActivity::class.java)`。不能为了避开全名改成字符串/反射；只有外部组件协议本来要求字符串时才用组件配置。常量归实际所有者：服务 action 归服务命令合同、请求码归对应流程、prefs 键归存储类、URL 来自既有私有构建配置。无万能 Constants。
4. 硬编码颜色/尺寸和普通 shape 迁资源；真实 Canvas 坐标、矩阵和协议字节值属于算法/协议，应带单位与来源，不能机械塞到 dimens。密钥、凭据和真实部署地址不入库。
5. 图标优先复用现有素材，注明源与用途；纯资源跨模块需要迁到合理依赖层并保留旧别名。图标缺失先记录，不自画近似 OEM 素材冒充一致。

## 5. 职责和生命周期

| 层/职责 | 输入输出 | 约束 |
| --- | --- | --- |
| Activity/页面控制器 | 生命周期、binding、用户事件、呈现 | 不读文件/发网络/运行 shell；不拥有第二个后台连接协调器 |
| 功能状态转换器 | 现有快照+持久配置 → 明确页面状态 | 不启动/停止业务、不持有 View，不复制协议状态源 |
| 用户操作类 | 用户动作 → 既有设置 API/服务 Intent | 保留先保存再通知顺序、关闭选择与失败反馈 |
| Repository/既有 Store | 读写既有配置和数据 | 单一权威来源，键兼容；不再包无意义透传层 |
| Service/实际协调器 | 生命周期、租约、线程/输出所有权 | 停止/重连/过期回调可追踪；具体业务随对应批次拆分 |
| Platform/Port/Adapter | 已跑通厂商接口、资源申请/释放 | 不猜接口、不改变车型合同或引入无依据回退 |

使用最少的必要类；可通过纯状态类和控制器完成时，不为了分层增加 DI 框架、通用事件总线、泛型 Repository 或新 MVVM 依赖。已有 Jetpack 架构按真实项目条件使用；“Android 标准”不等于机械套所有架构库。

主线程只更新 UI/轻量决策，耗时文件/解码/I/O 在有界 worker。异步回调绑定页面/会话代次，停止移除 ticker，销毁后不触 View；错误保留原因但日志脱敏。新增线程须写启动、停止、队列和迟到结果语义。已有有界同步配置提交若是服务启动的顺序前提，先保留合同，后续专批优化，不在 UI 迁移中悄悄改为 apply。

## 6. 注释、命名与格式

所有自有类/接口/object、方法（含私有）、独立职责回调有有效中文文档注释；职责块说明前置条件、线程/所有者、取消和结果。注释解释契约与原因，不逐句翻译；重构后旧注释同步删除/更新。普通测试名体现场景和结果，不强行所有断言加注释。

显式 import、重名 alias，不用通配 import；一个动作一条语句。UTF-8/LF、空格缩进、末尾换行；Kotlin/KTS 四空格、XML 四空格，统一花括号/参数/换行。本批固定 Kotlin formatter 为既有 **ktlint 1.4.1**，jar SHA-256 `abff61ead7dabdf1264ae7dce61cb5325e602896f33f3ee2fbc359238adeac70`，配置见 `format/galaxy/.editorconfig`；格式检查不等于 Android Lint。XML 使用本机 `/usr/bin/xmllint` libxml **20913**、`XMLLINT_INDENT='    '` 格式化并做语义及二次不变检查；不格式化包含显著空白的文本资源以改变句义。以后其他语言批次先固定既有工具及版本，不临时各自选风格。

只格式化本批完整自有文件，不全仓扫第三方/生成文件。保留协议和资源字符串语义。交付记录命令、版本、配置、文件清单、二次检查；`git diff --check` 只是额外空白检查。

## 7. 团队流程与质量门槛

1. Lead：保存已有代码检查点；阅读已跑通材料和源码；列需求→旧方法→新文件→调用顺序→用例，给写白名单、固定基线和文档包。没有拆分表不派发。
2. Lead 创建任务分支和隔离 worktree。WorkBuddy 优先沿用 DeepSeek flash，先记录实际模型、HEAD/摘要、读完的规范/任务书/用例及逐项理解。按用户2026-11-12最新要求，模型不是停工或交审阻断，不因模型反复切换和讨论。不擅自切方案、探索未知接入或扩大写范围。
3. 编码 Agent 完成一个可编译单元，执行格式、真实 Android 编译和任务书标准用例；失败先修，依赖缺失写 `BLOCKED_VALIDATION`，不得标 `READY_FOR_REVIEW`。不得把 compile 交给 Lead 补做。
4. 每个可交付重构批次本地 Git 提交；只 stage 白名单。最终检查和报告绑定最终源码摘要、提交、实际日志和 JUnit XML；工作树应干净，证据在任务目录，不把缓存、凭据和 APK 入库。提交有问题用后续修复提交，不篡改已给 Lead 的历史。
5. 编码 Agent 自查需求覆盖、资源、依赖、无越界和遗留，达到门槛后冻结。Lead 发任务后结束本轮，等用户叫开始再复验/审计，不后台集成、不自动派下一批。
6. Lead 按最终提交复跑编译和必要用例，检查真实职责与旧行为；编译失败即退回，不能以几项字符串检查洗白。通过后在用户叫审计的本轮集成并提交集成结果；主分支仅收已验证状态，不推远端。

测试以真实生产入口为中心：XML inflation/资源处理、点击→实际配置/Intent、回填不回调、已知错误路径、生命周期。少量参数化覆盖常规状态，拒绝为数量堆边界。脚本测试必须执行目标脚本并核对真实产物。宿主 Robolectric 只证明本机行为，不能证明 8155/QNX/车辆显示/性能。无模拟器、无车辆连接/安装/HMI。

## 8. 官方依据与本项目选择

- [Android 布局资源](https://developer.android.com/develop/ui/views/layout/declaring-layout)：XML 描述界面骨架；本轮项目选定 XML。
- [View Binding](https://developer.android.com/topic/libraries/view-binding)：按布局生成类型安全引用，模块启用 viewBinding。
- [字符串资源](https://developer.android.com/guide/topics/resources/string-resource)、[提供替代资源](https://developer.android.com/guide/topics/resources/providing-resources)：字符串、占位符、数量与配置限定符。
- [应用架构建议](https://developer.android.com/topic/architecture)：分离 UI 与数据职责；本文按现有项目约束采用最少必要拆分。

官方平台支持多种 UI 技术；本文强制 XML 是用户本轮工程要求。资源、文件、用例和团队门槛以本项目任务书为准。
