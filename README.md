# GalaxyPlay

新车机 CarPlay 接收端项目。应用显示名 **CarPlay**，包名 **com.ecarx.carplay**。首验为 **银河 L6 2023 年 Max / FlymeAuto 2.3.1**，L7 随后适配。

当前进入**Galaxy适配迁移实施阶段**：官方 DiPlay v0.2.21 固定提交为核心基线，旧GalaxyPlay `galaxy/` 目录已完整迁入并按L6首验与v0.2.21接口适配；唯一应用入口为 galaxy/mobile，产品适配集中在 galaxy/common。`scripts/verify.sh` 一条命令跑完整验证：离线源码契约 + `:shared` 1680项、`:common` 1617项单测 + `:mobile` lintDebug（0 error/72 warning）+ assembleDebug 产出APK，全部通过（2026-10-29 01:34）。方控学习已接入投屏会话运行时（七动作真正下发）。**尚未完成**：仪表HAB→QNX完整导航/音乐画面与L6 DIM显式绑定、L7 profile、实车验收。结果见[工程基础](docs/工程基础.md)。

## 项目文档

- [工程基础](docs/工程基础.md)：源码布局、固定基线、补丁、验证命令与本轮实际结果。
- [项目规划](docs/项目规划.md)：接入架构、上游跟随、开发阶段和验收门槛。
- [决策记录](docs/决策记录.md)：D01 固定核心已确认；D02 为 L6 2023 Max / FlymeAuto 2.3.1。
- [参考实现对标笔记](docs/参考实现对标.md)：记录别人实际的方法顺序、数据、绘制和提交逻辑，供用户明确要求开发后逐段对标。
- [ECARX 仪表投射对标](docs/ECARX仪表投射对标.md)：从原仓实拍反查完整地图流、解码、仪表窗口、DIM接通与音乐信息链。
- [源码核验](docs/源码核验.md)：来源、实现证据、适用边界及与用户已验证路线的版本差异。
- [协作规范](AGENTS.md)：后续 Agent 必须遵守的工作边界。
- [来源锁定](references/来源锁定.json)：本轮检出 SHA、本地快照与来源状态。

## 材料与当前边界

已拉取 [DiPlay](https://github.com/shihabal3amri/DiPlay)、[zmisgod/DiPlay](https://github.com/zmisgod/DiPlay)、[DiPlay-ECARX](https://github.com/jiopia/DiPlay-ECARX)，并制作用户指定本地 GalaxyPlay 的已跟踪源码快照。

“车机逆向”与“星枢”在原目录只读研究，不复制其密钥、缓存或私有日志。参考材料不等于新 App 源码，不自动打包或再分发。

用户已明确要求开始建立项目基础，本轮据此解除该范围的只研究限制。保留参考实现对标记录；不把基础落入当作所有功能已迁移。根Git按D10初始化：`main` 只收已验证状态，改动走 `feat/*`/`fix/*` 短分支，不推远端。不安装APK、不连接车辆、不替换签名或推送发版。

## 源码目录

- [galaxy/mobile](galaxy/mobile/build.gradle.kts)：唯一应用模块，入口、清单、资源和构建配置只放这一处。
- [galaxy/common](galaxy/common/src/main/java/com/ecarx/carplay/product/GalaxyProduct.kt)：产品配置及后续Flyme/车型/OEM适配，由[common](common/build.gradle.kts)编译。
- [shared](shared/build.gradle)：CarPlay协议、传输和音视频核心。

根mobile已剔除；`:mobile`仅为映射到galaxy/mobile的Gradle逻辑名，不存在第二套应用。

## 基础检查

```sh
scripts/verify.sh          # 全量：源码契约 + 单测 + lint + assembleDebug
scripts/verify.sh --quick  # 只跑离线源码检查（秒级）
```

Android构建沿用上游固定工具链，不沿用旧工程Docker约定；认证与签名仍外置。详见[工程基础](docs/工程基础.md)和[安全约定](SECURITY.md)。
