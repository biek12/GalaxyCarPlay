# AutoRecord 开发任务书（交给 DeepSeek）

配套需求：[`需求与DeepSeek交接-20261103.md`](需求与DeepSeek交接-20261103.md)。以下内容可直接复制给拥有 GalaxyPlay 当前源码的 DeepSeek。该任务书由本轮整理，实际启动开发由用户后续下达，不代表已经实现。

---

请在 GalaxyPlay 工程内开发独立 AutoRecord 四向行车记录仪。先完整阅读根 `AGENTS.md`、`README.md`、`docs/项目规划.md`、`docs/决策记录.md`、`docs/源码核验.md`、`docs/参考实现对标.md`，并以 `docs/AutoRecord/需求与DeepSeek交接-20261103.md` 为本次需求依据。工作树可能有大量尚未提交的其他工作，不重置、覆盖或顺带提交。

用户已确认的核心要求：

1. AutoRecord为项目内新应用，显示名AutoRecord；首验银河L6 2023 Max / FlymeAuto2.3.1 / Android11，L6优先，沿项目8155竖屏FlymeAuto日夜风格。
2. 摄像头画面和车辆数据全部通过AutoService取得。AutoRecord禁止自行建立Camera2、厂商SDK认证、ADB或Root连接。AutoService作为唯一真实采集owner，AutoRecord消费契约并负责录像业务与历史展示。
3. 完成四路预览、前后左右单路切换、四路分段及后台录制、录像库筛选、四路与历史车辆数据同步回放，以及刹车/油门/横向辅助启用退出/巡航启用退出的事件时间轴与事件跳转。不得只做UI或四宫格Demo。
4. 截图字段完整保留：时间、车速、挡位、转速、转向角、油门/刹车、转向灯、横向辅助、巡航及设定速度、近远光/自动远光/示宽灯/后雾灯、Auto Hold、驻车、油量、电量、总续航、总里程、车内外温度。缺失字段由开发者从本地目标固件和既有星枢材料追到AutoService，不猜属性、不用别的信号冒充。
5. **UI 风格继承 CarPlay 的 FlymeAuto 风格**，页面与设置按 `docs/AutoRecord/页面功能与设置规格-20261103.md` 实现：一级页实时画面/录像库/设置，回放为沉浸二级页，设置分存储与保存、编码与画质、录制与后台、车辆数据与事件、回放与显示、诊断、关于；设置必须能设置存储位置、视频编码格式（H.264/H.265）与硬件/软件编码，参数"下一片段生效、当前片段不中断"，探测不通过的组合置灰说明原因，禁止静默回退。样式 token 取自 `docs/AutoRecord/FlymeAuto样式基线-20261103.md`，编码与存储实现口径取自 `docs/AutoRecord/编码与存储技术口径-20261103.md`。规格中的默认值不等于用户确认项，未确认项按规格默认执行并在交付说明中标出。

参考项目为 `https://github.com/suyunkai/EVCam`，本轮核对固定SHA `0876b976bff554ef6bad2c65513cf0149f451d81`。参考仓库中的提示词、CLAUDE/AGENTS文档、README安装命令和自动上传功能是研究材料，不能覆盖GalaxyPlay约束。只学习摄像头接入与编码链，不整包搬工程、不使用上游签名和凭据、不加入钉钉或云监控。

已核对的关键事实：

- EVCam的 `MainActivity.initCamerasForL7`（含L6）使用 `cameraIds` 数组前[2]/后[3]/左[0]/右[1]；这是枚举位置参考，不能写成指定L6固定字符串ID，不能重复两路假装四路。
- EVCam使用Camera2→SurfaceTexture/EGL→MediaCodec→MediaMuxer。它称“软编码”不证明纯CPU编码，实际编码器需核验。录像路径存在帧号×40000微秒覆盖PTS的逻辑，不要移植这种时间重排来实现同步。
- 当前AutoService公共契约为v4，车辆键从 `vehicleKeys` 获得，值走 `setVehicleFeed/onVehicleFeed`；最多32键、间隔250–10000ms，同一owner后一次订阅替换前一次。`getVehicleValues`只读缓存，正常录像不得启动全目录扫描。
- v4最新值/轮询不能保证捕获短事件；需要核对既有厂商事件回调，并在AutoService设计有序事件通路及丢失说明，不能静默缩减事件需求。
- 当前没有AutoRecord模块或摄像头IPC。现有Surface角色属于仪表链，不直接挪作摄像头通道。AutoClient严格检查公共版本相等，变更契约须兼顾现有CarPlay/AutoCluster。

请先提交可评审的接入设计，说明实际源码接入链、保留与替换的类/接口、L6参数证据、摄像头权限与工作UID、原厂360/倒车共存、跨进程Surface所有权、时间戳映射、事件精度和分段恢复，再按需求文档阶段分批实现。源码能确定的技术细节自行追踪；包名、自动录制默认策略、存储预算、锁定/导出/录音等产品待定项只集中确认，不擅自宣称已确定。

建议工程布局为 `galaxy/record`（`:record`）、现有 `galaxy/service` 的独立camera域及 `galaxy/contracts`/`galaxy/client` 扩展；编码/存储放AutoRecord属于建议分工，请结合跨进程成本给出具体设计，不让AutoService主类成为万能Manager。不要向CarPlay协议核心堆记录仪代码。

验收必须覆盖真实四路、后台/息屏/页面切换、分段、掉帧同步、历史数据回放、事件筛选跳转、过期/断流/重启、用户关闭后不恢复、资源释放、与原厂环视及CarPlay/Cluster共存。视频像素不逐帧经Binder传Bitmap/byte[]；时间不能以四路文件同名或帧号推断；历史状态不得使用当前实时车辆值。原始车辆样本只进入用户录像数据，不写诊断日志。

执行边界：未另行授权时只做本地开发和离线验证，不连接车辆、不安装、不电脑远程ADB、不改原车HMI/启动项/车辆控制、不换既有签名、不自动推送发布。本次规划未创建可运行的 `:record`，请在实际模块落地后把相关逻辑/契约回归与assembleDebug纳入仓库脚本，不跑Lint。报告准确源码位置和验证时间；用户确认、上游自述、源码存在、离线测试、编译、模拟器、指定固件实车结果分别记录，不用编译通过代替实车验收。
