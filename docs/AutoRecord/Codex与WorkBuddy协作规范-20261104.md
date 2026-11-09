# Codex 与 WorkBuddy 协作规范

生效：2026-11-04，用户指定双方协同，WorkBuddy 负责基础编码，Codex 负责调试、优化、审查和集成。WorkBuddy 优先使用界面标注限时免费的 Hy3；不可自行切换付费模型或购买额度。

## 分工与写入边界

| 责任方 | 责任与写入范围 |
| --- | --- |
| Codex（Lead） | 车辆远程通道、安装/签名、Camera2 与预览、车辆订阅、权限、UI、性能、AutoService/client/contracts/server、构建脚本、根规范、最终集成及验收 |
| WorkBuddy（编码） | 按任务书编写存储/编码基础实现和必要测试；**当前只写 `.workbuddy/tasks/autorecord-storage-20261104/`**，业务源码先暂存到其 `src/`、测试到 `test/`，不进入 Android sourceSets |

全工程已有大量未提交变更。禁止 reset/clean/stash/checkout 覆盖工作树；不提交、推送或改分支，不替换依赖/签名。不得擅自改上方边界；确有依赖修改需求，先写在 STATUS 的“接口请求”，Lead 在自身范围实施。暂存区是待审候选，不是第二份产品模块；通过后由 Lead 集成并归档补丁。

## 交接与构建

1. WorkBuddy 先读根 AGENTS、AutoRecord 台账、本规范和当前任务书，写明所读源码及拟用接口，然后立即执行当前任务。
2. 每批以一个完整职责为单位；状态依次 `IMPLEMENTING → READY_FOR_REVIEW → CHANGES_REQUESTED / ACCEPTED`。WorkBuddy 独占其 STATUS；Lead 独占 `docs/AutoRecord/协作审查-20261104.md`。
3. `READY_FOR_REVIEW` 后冻结该批文件；列出文件、接口、错误路径、线程/资源所有权、自测命令与真实结果。没有执行就写“未执行”，不能将计划或 mock 写成实测。
4. WorkBuddy 不运行全工程 Gradle/R8、Lint、模拟器、远程通道或安装；Lead 按批集中构建/执行相关测试，再按车辆授权安装。构建和测试入口写入仓库脚本；签名复用工程原件，缺失即停止。
5. Lead 检查差异/文件摘要、契约、失败路径和实际验证结果；未通过不得把 engineAvailable 改为 true 或在文档中标记录制完成。拒绝项写审查单，由 WorkBuddy 修复后重新交付。

冻结交付且用户要求继续测试后，Lead可接管该批的小范围调试修正：先归档冻结原件和摘要，再记录反例、修复与结果；接管期间WorkBuddy不得写该批，避免反复交接或重叠修改。新增基础功能仍另发WorkBuddy任务。接管不等于验收，通过后才集成，engineAvailable仍按实际引擎状态。

Lead候选验证入口：`bash scripts/verify_workbuddy_storage.sh`。只在STATUS为READY_FOR_REVIEW后临时加入候选源码/测试跑单测，拒绝assemble/package/install；不把暂存候选装车。由Lead确认该批不再修改后执行，代码审查通过再正式集成。

## 代码与产品规范

- 一个类一个主要职责，清楚命名，普通格式，不压缩堆叠代码；中文注释说明线程、所有权、为何如此及失败处理。
- 复用现有 `SegmentLayout`、`SegmentManifest`、`AtomicFiles`、`CodecCapabilities`、`RecordSettings`，不复制另一套目录/清单/编码选择逻辑；API 适配通过接口注入，禁止万能 Manager。
- 会话代次隔离迟到回调；stop/close/abort 幂等；所有队列、缓存、线程有界。耗时 I/O、codec drain 与 fsync 不上主线程。
- 每路独立 MP4，单调时钟统一 PTS；EOS→drain→muxer stop/release→fsync→原子改名→清单发布。未完成留 writing/partial，不能先删旧文件；空文件/坏文件不得标 complete。
- 目标卷缺失、满盘、权限、编码不支持必须明确失败，不回切另一存储介质，不从硬编暗降软编，不编造成功、不补零或复制一路冒充四路。
- 未获新增产品确认的录音/抓拍/导出/锁定/自动录制默认值等，不擅自启用。车辆数据只消费 AutoService；存储开发不接 Root/厂商 SDK，不改车型 ID 或车辆控制。
- 不读或转发 `.local/`、`.work/`、签名/认证材料、原始车机日志/图像；只读任务书指定源码与普通设计文档。用户已授权 WorkBuddy/Hy3 编写本任务代码，不扩大到其他第三方上传或服务。

## 当前技术事实

- 车机签名不一致问题已获用户本次授权修复：AutoRecord 数据已备份、恢复并校验，现与 AutoService 使用工程原签名。
- 正在调试的预览由 JPEG 单次抓拍改为 1280×800 PRIVATE 连续输出。TextureView 版已实测约 21–25fps；SurfaceView 版正在复测。**这仅是预览，不证明 MediaCodec 编码已通。**
- 首次 CAMERA 缺权经 AutoService 申请、回调及 App 本地复核后开流，现场已从 false 恢复 true；数据走 v4 按需订阅。转向灯数值显示与数据延迟由 Lead 修复。
- 本轮不把持续录像、历史同步回放、导出或长时热稳定性写成完成；这些仍需逐项实现及独立验收。旧 WorkBuddy memory 的 LEGACY、仅 JPEG、三 Tab 等表述已过时，以最新规范和实际源码为准。

## 首批交付

执行 [WorkBuddy 存储基础任务书](WorkBuddy存储基础任务书-20261104.md)。完成后只报告 READY_FOR_REVIEW，等待 Lead 审查；不要自行扩大到 UI/相机/整套录像引擎。
