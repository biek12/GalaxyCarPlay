# WB-STORAGE-01：片段写入事务基础

负责人 WorkBuddy / Hy3；审查与集成 Codex。先读[协作规范](Codex与WorkBuddy协作规范-20261104.md)。现在开始基础编码，不仅交方案。

## 写入范围

仅 `.workbuddy/tasks/autorecord-storage-20261104/`。候选 Kotlin 放 `src/com/galaxy/autorecord/storage/`，测试放 `test/com/galaxy/autorecord/storage/`；进度更新同目录 `STATUS.md`。禁止修改实际模块、UI、相机、数据、服务、构建、根规范或其他文档；接口需求写 STATUS。

## 必读源码

`galaxy/record/src/main/java/com/galaxy/autorecord/core/` 下的 `SegmentLayout.kt`、`SegmentManifest.kt`、`AtomicFiles.kt`、`SegmentCatalog.kt`、`LocalLibrary.kt`、`StorageVolumes.kt`、`RecordingController.kt`、`CodecCapabilities.kt`；以及 `docs/AutoRecord/编码与存储技术口径-20261103.md`。同包依赖可顺引用只读。禁止扫描凭据/日志/截图目录。

## 要实现的结果

实现 `SegmentTransaction` 及其必要的小接口/数据类，服务后续真正的 MediaCodec/MediaMuxer 写入器；**本批不实现、也不假装接通录像引擎**。

1. 输入选定存储根目录、卷标识、墙钟/单调时钟锚点、段号、合法方向集合和应用版本；验证方向/时钟/路径，不复用既有同名段，不允许越过选定根目录。
2. 使用现有 SegmentLayout 建段目录并用现有 SegmentManifest/AtomicFiles 发布 writing 清单；为选定方向提供独立 `.mp4.writing` 目标。未选方向在清单中如实缺失。
3. 接收后续真实写入器的“已完成 EOS/drain/muxer stop/release”结果与**实际**帧数/PTS/编码器元数据。验证非空文件、有效计数及 PTS 后，以既有 AtomicFiles 完成 fsync/原子改名；有路失败则 partial/failed，不能整段 complete。不接受仅传一个成功 Boolean 就编造元数据。
4. 全部视频处理后才原子发布最终清单。文件/清单提交失败保留已有可用证据并返回具名错误；异常不吞掉，不删除旧片段，不做隐式重试或介质回切。index 可重建，本批不引入数据库或依赖。
5. finish/abort/close 幂等，明确单一写线程所有权；构造/调用不创建无界后台线程。通过可注入文件操作/时钟或必要的小端口模拟失败，真实默认实现复用现有工具，不复制工具实现。
6. 建立针对不安全路径/重复段、空视频、PTS非法、单路失败、清单失败、abort与重复finish的有意义测试；不只测试 getter，也不把假数据文件当作可播放 MP4 的证明。

不包含：自动覆盖/删用户录像、数据库重构、车辆历史 schema 变更、录音、导出、编码器选型规则修改、硬软降级、Root、Camera2、UI集成。下一批再安排真实 MediaMuxer writer 与录像生命周期，未经 Lead 下发不要扩写。

## 交付要求

STATUS 至少包括：状态、读过的源码和行号、方案/接口、候选文件表、完成/未完成项、错误与资源处理、测试是否真实运行、请求 Lead 修改的接口。暂存区不在 sourceSets；不要直接跑 Gradle，Lead 集成后统一验证。做好实现后置 `READY_FOR_REVIEW` 并停止写该批，等待审查单。
