# GALAXY-ANDROID-R0-20261112 状态

**BLOCKED_VALIDATION**（不是 `READY_FOR_REVIEW`）

| 项 | 值 |
| --- | --- |
| 分支 | `feat/galaxy-android-r0-20261112` |
| 起点 HEAD | `572ba0eb07113c8c1859efa9c34cb25a1676eff7` |
| 最终 commit | `b70482099b2dc2dfd418389ce3d8e5752a28cceb`（提交后工作树干净） |
| 暂存方式 | 显式白名单 `logs/commit-whitelist.txt` 139 项（65 新增 / 24 修改 / 50 删除），未用 `git add -A` |
| 单命令门槛 | `BUILD FAILED`（`scripts/verify_projection_retirement.sh` 第 3 步） |
| 交付物 | `DELIVERY.md`、`STATUS.md`、`VALIDATION.json`、`logs/`（20 份 JUnit XML、归档摘要、格式结果、完整验证日志） |
| 车端 / APK | 均未验证：`apkBuilt=false`、`vehicleValidated=false` |

## 判定理由

按 HANDOFF「编译/必测失败或未执行必须 BLOCKED_VALIDATION，不能 READY」如实判定：
`:service:testDebugUnitTest` 跑 48 例、失败 3 例（`ClusterHookLifecycleTest`），
脚本 `set -e` 终止，第 4/5/6 步（产物核对、证据复制、通过回显）未执行。
代码虽按用户 2026-11-12 18:24 指令提交，状态不改写为通过。

## 已通过（可复用，不必重做）

- 全下游编译 / 资源：`logs/verify_cluster_pages.log` → `BUILD SUCCESSFUL`，102 actionable tasks。
- 固定格式：ktlint 1.4.1 二次幂等 + 只读 check（22 个 Kotlin/KTS）、xmllint 20913
  parse/语义/二次幂等（7 个 XML）、4 个脚本 `py_compile` / `bash -n`，全部通过。
- Cluster JUnit：11 suites / 52 cases，0 失败 0 错误 0 跳过。
- service JUnit：9 suites / 48 cases，45 通过；新增 `RootProjectionArchiveTest` 4/4、
  `ServiceHomeBehaviorTest` 10/10、`PlatformExecutionTest` 4/4（`PosixChecksum` 与系统 `cksum` 对拍）全通过。
- 归档：50 个专用文件原字节移出源集 + 3 份混合职责快照，`manifest.json` 53 条，
  逐文件 SHA256 与起点 git blob 一致；不进 Gradle 源集。

## 未完成 / 残留（交接给下一 AI）

1. **3 个必测失败**：`ClusterHookLifecycleTest` 的
   `matchingLiveProcessReusesWithoutRestartOrStage`(:18)、
   `firstStartStagesVerifiesAndOnlyThenActivates`(:28) 抛
   `IllegalStateException: cluster_restore_unconfirmed`（`QnxClusterHook.kt:126` ← `:139`）；
   `fixedFirmwareMismatchCannotActivate`(:51) 为 `AssertionError`。
   两文件（测试与 `QnxClusterHook.kt`）本批零改动、与起点 blob 字节一致；
   台账 `文件台账-20261111.tsv:883` 标注该测试「待重构，未验收」，
   既有 `verify_android_refactor.sh` 的必测清单从不包含它。
   推断为测试 fake 的协议 token 与当前 `QnxClusterRemote` 不同步（详见 `DELIVERY.md §9`），
   未在独立基线 worktree 实跑复验。
2. **`verify_artifacts.py ready` 未执行**：merged Manifest / Kotlin class / 三方 SHA256 /
   共享闭包审计尚未由脚本逐项核对；`VALIDATION.json` 为交付阶段手工汇总，
   `artifactsCheckExecuted=false`、`scriptCompleted=false`。
3. JUnit XML / 归档摘要 / 格式结果副本由交付阶段手工复制到 `logs/`，内容真实但非脚本流程产出。
4. 需补跑完整单命令门槛至全绿，并让 `verify_artifacts.py ready` 生成正式
   `VALIDATION.json` 后，才可以把状态改为 `READY_FOR_REVIEW`。

证据、复现命令、建议修复方向与约束见 `DELIVERY.md §9`、`VALIDATION.json.residual`。

## 冻结

按用户 2026-11-12 18:24 指令：不再继续编码，如实记录残留问题后提交并冻结，交由新的 AI 完善。
未 push / merge / reset / clean / stash；未自动集成、未续派、不恢复已取消的 C2b；
不把离线编译与部分测试通过写成车端已恢复。
