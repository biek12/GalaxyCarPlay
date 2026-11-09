> 2026-11-05 后端联调更新：生产路径 `/galaxy/app/`，API `/galaxy/web/v1`，Go实现位于 `server/galaxyd/web*.go`，契约 `server/galaxyd/openapi.json`。下文早期演示描述不代表生产已具备车端直播/索引发布。
>
> 生产必须关闭mock，不再保存localStorage长期令牌；Cookie/CSRF/SSE已接入。仅受邀手机号+TOTP，首次需要部署者提供24小时一次性激活码；保留唯一验证器，支持再认证后更换和恢复码。账号首次确认、车辆绑定确认都需要用户真实操作；不自动绑定演示车辆。
>
> `npm exec -- vue-tsc --noEmit && npm run build` 完成类型/构建验证；真实MP4由公共播放器的mediaUrl接入，未上传原片/未接通直播显示不可用，生产不会绘制合成道路或车辆数据。车端设备业务上报和WebRTC编码仍需Android适配，详见Go README。

# AutoRecord 在线查看（Web 前端）

AutoRecord 四向记录仪的在线查看网页：手机 / 电脑自适应；实时四路画面与四路同步历史回放，交互与 App（P1/P2/P3）保持一致。**当前为 mock 优先交付**，接口为 [在线查看网页需求与 Agent 交接 §6](../docs/AutoRecord/在线查看网页需求与Agent交接-20261105.md) 拟定的 `/web/v1` 契约，后端由其他 Agent 依契约实现后无缝切换。

## 技术栈

Vite 5 · Vue 3.5 + TypeScript · Vue Router 4 · Pinia 2 · UnoCSS 0.66（presetWind3）· Ant Design Vue 4。依赖版本以 `package-lock.json` 锁定，不使用浮动 latest。

## 快速开始

```bash
cd web
npm install
npm run dev        # 默认 mock 模式（.env.development: VITE_USE_MOCK=true）
```

浏览器打开 http://localhost:5273。**免密码登录：手机号 + 两步验证（TOTP）**，演示手机号 `17760420247`。

- 首次登录（或场景面板「重置两步验证」后）会直接给出 2FA 绑定入口：用验证器 App 扫二维码，或手动输入页面展示的密钥，输入 6 位验证码即完成绑定并登录；恢复码只展示一次。
- 已绑定后直接输入验证码登录，也支持恢复码；错误 5 次将限流 60 秒。
- 绑定码演示：`BIND-DEMO-OK`（约 4 秒后车端确认）、`BIND-DEMO-EXPIRED`（过期）。
- 右下角齿轮是 **Mock 场景面板**：L6 休眠、右后断流、左侧被原厂占用、唤醒失败、平面标定可用、实时卡顿、批删部分失败、分页插入新片段、哨兵布防失败，全部即时生效。

## 页面清单（与交接文档 §2 对应）

| 路由 | 内容 | 已覆盖状态 |
|---|---|---|
| `/login` | 密码 + TOTP/恢复码两步登录 | 错误、限流（5 次锁 60s）、challenge 过期、恢复码重放 |
| `/security/setup` | TOTP 二维码 → 确认 → 恢复码一次展示 | 未确认不启用；刷新不再泄露恢复码 |
| `/vehicles` | 车辆列表、绑定（车端确认流程） | 空列表、pending/confirmed/rejected/expired、viewer 角色 |
| `/vehicles/:id` | 在线/最后在线、录制与存储、能力、唤醒 | 在线/休眠/唤醒中/离线；remoteWake 不支持时显示真实原因 |
| `/vehicles/:id/live` | 四宫格/单路、鱼眼/平面、REC/存储/健康徽标、数据条 | 准备、首帧、卡顿遮蔽、断流、占用、缺路、标定不可用 |
| `/vehicles/:id/history` | 时间范围（分钟精度）、重要/异常/未看、按日分组、游标分页、多选批删 | 已覆盖/未上传、锁定跳过、部分失败回执、索引未就绪 |
| `/vehicles/:id/clips/:clip` | 四路同步回放、同一时轴 seek/倍速、事件时间轴、力度数据条 | 跨段衔接、缺路占位、各路起始偏差对齐、缺失/过期样本、事件跳转 |
| `/vehicles/:id/sentry` | 意图开关 vs 实际状态回读 | 待布防/已布防/录像中/失败 |
| `/security` | 改密码、2FA、恢复码、会话管理 | 敏感操作前重新认证（actionTicket） |
| `/vehicles/:id/access` | 归属、成员、解绑 | owner/viewer 权限差异 |

## 代码组织（交接文档 §7）

```
src/api/            统一请求、错误映射、契约 DTO
  api/mock/         显式 mock（db 生成器 / server 路由 / canvas 画面渲染器）
  api/client.ts     mock 与真实 /web/v1 的无缝切换入口
src/stores/         auth / vehicles / playback / jobs / app（有界缓存，切车即清）
src/features/       live / history / playback / security / vehicles / sentry / mock
src/components/     布局、四路瓦片、数据条、事件时间轴
```

## 给后端 Agent 的接入口

1. `.env.production` 设 `VITE_USE_MOCK=false`、`VITE_API_BASE=/web/v1`。
2. `client.ts` 已按契约实现 fetch（Cookie 会话 + Idempotency-Key + `{code,message,requestId,retryAfterMs?}` 错误映射 + 401 统一跳登录），后端按 §6 OpenAPI 交付即可。
3. 实时流：`features/live` 目前用 mock canvas 流；接入 WebRTC 时替换 `CameraTile` 数据源与 `api.vehicles.startLive` 返回的信令，页面状态机（准备/首帧/卡顿/断流）不变。
4. 历史流：回放页通过统一时轴（`playheadMs`）驱动四路与数据条；接 MP4 点播时在 `stillT()` 处换成 `<video>` currentTime 映射，禁止四个独立进度条。
5. mock 覆盖的异常场景清单见 Mock 面板与交接文档 §7，可用于联调对照。

## 边界说明

- mock 全部为显式开发模式（`VITE_USE_MOCK`），生产构建不会偷偷启用；演示画面与数据均为生成内容，不代表实车能力。
- 未实现：WebRTC 真流、真实 TOTP 服务端校验、云上传。停车位置识别为未来模块，本期无页面。
