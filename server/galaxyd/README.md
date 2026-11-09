# 个人远程通道与歌词服务

Go标准库实现，独立于原星枢服务。部署在用户自有服务器，监听127.0.0.1:8781，经既有HTTPS站点的`/galaxy/`反代访问。部署脚本只更新自有galaxyd单元与此前缀，修改代理前备份配置并执行nginx检查。

```sh
bash scripts/deploy_bridge.sh
```

脚本使用本机已授权SSH身份；可用`GALAXY_SERVER_SSH`与`GALAXY_SERVER_SSH_KEY`指定入口。首次部署生成私有登记材料并保存到本机`.local/remote-enrollment`（0600）。它不进入APK、Git或诊断包。服务端状态目录0700、文件0600。

首次车机安装与配对：

```sh
env 'ORG_GRADLE_PROJECT_kotlin.incremental=false' python3 scripts/prepare_field_bundle.py
python3 scripts/bootstrap_remote_bridge.py --host CURRENT_CAR_HOST \
  --bundle "$(cat .work/vehicle-test/cluster-current.txt)" \
  --results .work/vehicle-results/remote-bootstrap \
  --enrollment-file .local/remote-enrollment --hours 12
```

电脑和车机首次需在同一可达网络，走既有授权的Android Root Telnet，不使用电脑远程ADB。脚本验证四APK身份、签名、传输及已装摘要；私有写入登记材料、开启12小时窗口、请求主题能量组件同步，并将一次验证码兑换为电脑私有会话。验证码和凭据不打印、不进报告。选择24小时可传`--hours 24`。

以后在窗口内直接使用公网：

```sh
python3 scripts/remote_car.py shell 'id -u'
python3 scripts/remote_car.py install /absolute/path/AutoService-debug.apk --package com.ecarx.autoservice
```

原有只读标定脚本也可复用公网传输，不需要电脑ADB连接：

```sh
python3 scripts/capture_cluster_fields.py --adb "$PWD/scripts/remote_field_transport.py" \
  --serial remote --label parked --duration 10 --interval 2 \
  --output .work/vehicle-results/remote-fields.json
```

此命令只读；不主动改变回收档位或任何车辆状态。可用`GALAXY_REMOTE_SESSION`指定其他私有会话文件。

也可在车机AutoService界面开启12/24小时，电脑执行`python3 scripts/remote_car.py pair`输入一次验证码。验证码5分钟有效、使用后失效；会话到期不会因重连自动延长。关闭远程使本地worker退出并请求服务器撤销。无角色或逐命令审批；既有ADB/Root全局关闭仍生效。默认关闭远程。

车机主动长轮询HTTPS，优先系统已连接的蜂窝网，否则使用默认网络；不更改网络设置。APK更新保留签名和数据，限定四个自有包。命令领取前落盘，超时/断网/自身更新后不自动重跑；待领取命令120秒过期。自身更新中断后通过只读已装摘要确认结果。临时APK有数量/大小限制，结果与命令记录随窗口到期清除。服务不记录请求正文、Authorization或命令输出。

匿名歌词接口：`GET /galaxy/lyrics/search?track_name=...&artist_name=...`。QQ、网易和LRCLIB并行查询，严格匹配歌曲版本与歌手，优先带时间戳歌词。成功结果保留30天：内存LRU最多512首且8MiB，满后逐条淘汰；服务器私有目录`/var/lib/galaxyplay-bridge/lyrics/`磁盘最多1024首且32MiB，跨服务重启复用，按最近使用时间淘汰。单条上限512KiB，文件0600；损坏/过期条目重新查询，查无结果和临时失败不缓存。缓存只存曲名、艺人和歌词结果，不存逐次请求历史、设备标识或播放进度。仅向歌词提供方发送曲名/艺人，接口保持现有Android解析格式。

验证：在本目录执行`go test -race ./...`，覆盖验证码/到期/重连、命令领取/重放、上传摘要与离线命令过期等。公网模拟设备往返已验证；不能代替车机物联网、Root身份或实车显示验收。

公网缓存验证（从工程根目录执行）：

```sh
python3 scripts/check_lyrics_cache.py --restart --output .work/vehicle-results/lyrics-cache-check.json
```

此命令查询同一首歌两次，核对HIT和内容摘要，再显式重启自有galaxyd验证磁盘复用；报告不含歌词正文。去掉`--restart`则只做HTTP读取、不重启服务。

## 自有App错误与崩溃恢复诊断（2026-11-05）

`POST /telemetry/events` 接收最多4条/256KiB事件，来源为四个自有App的AutoService汇总；独立于远程Shell会话时长。`GET /telemetry/events?device=<安装随机标识>&app=<包名>&kind=<事件类型>` 返回最近最多100条，可不连车查询。两者均要求私有部署的`X-Galaxy-Enrollment`；不能放在浏览器公开页面、APK或诊断报告中。无匿名读写，不通过开启遥控授权绕过鉴权。

事件含版本、App/进程、操作、脱敏参数、异常message/源码栈/cause/suppressed，恢复时补报系统退出原因和可得trace。不可得trace明确标记，不能据退出原因猜具体根因。UUID作为幂等键，冲突409；落盘0600、目录0700；每设备1024条/64MiB/30天滚动保留。只上传诊断，不上传录像、原始车辆数据、Shell正文或认证材料。上传失败保留客户端队列，服务端ACK后再清除。查询工具为仓库`scripts/read_diagnostics.py`，默认验证TLS并拒绝重定向。

测试：`go test -race ./...`覆盖鉴权、未授权拒绝、重复/冲突、重开状态持久、来源及体积校验。服务端接口通过不等于车机APK已更新或真实崩溃上报已验收。

## AutoRecord 网页 API（2026-11-05）

`/web/v1` 与 `/bridge` 是独立的账号、车辆授权与作业域。生产网页在 `/galaxy/app/`，使用 Secure/HttpOnly/SameSite=Strict Cookie；修改请求要求匹配 Origin、X-CSRF-Token 和 Idempotency-Key。首次开通为受邀手机号+一次性激活码（24小时），确认TOTP后永久作废激活码；后续仅手机号+TOTP/恢复码。唯一验证器不可关闭，支持二次认证后更换。TOTP secret 与敏感幂等回执使用独立AES-GCM密钥加密，恢复码/会话令牌仅存摘要。

邀请必须经运行中的服务原子写入，避免CLI覆盖在线状态：

```sh
umask 077
GALAXY_ENROLLMENT_FILE=/var/lib/galaxyplay-bridge/enrollment /opt/galaxyplay-bridge/galaxyd web-invite PHONE > /private/invitation.txt
```

该命令仅向本机监听地址提交，激活码只输出一次；不得提交邀请码、web状态/key、设备凭据到Git或诊断包。已有验证器的账号不允许此命令重置。

后端已实现：登录/限流/重放防护、恢复码、会话撤销、敏感动作票据；车端确认绑定、owner/viewer授权；状态/真实能力、稳定复合游标索引、侧录、逐项删除作业、哨兵revision与意图/实际分离；有限期设备outbox、WebRTC信令会话、SSE；经Cookie鉴权的MP4 Range播放。云端删除只删服务端对象，车端删除必须收到设备逐段回执。直播/电源唤醒在能力未发布时返回503，不生成模拟成功。

设备接口独立位于 `/device/v1`，头为 `X-Galaxy-Device` 与 `Authorization: Bearer <device-secret>`；只接车端登记身份，浏览器不能调用。首次 `/register` 验证已登记AutoService身份；后续web设备域独立于诊断授权窗口。所有请求POST，媒体上传为PUT：

| 路径 | 请求与真实结果 |
| --- | --- |
| `/register` | 建立设备Web身份，不自动关联账号 |
| `/snapshot` | `{snapshot:{name,model,plateMasked,presence,capabilities,recording,storage,sentry,liveSample?}}`；45秒无更新显示离线 |
| `/binding-codes` | 产生5分钟独立绑定码；已有owner拒绝 |
| `/poll` | 车端待确认目标账号、未过期业务作业、活跃观看会话；不含Shell |
| `/binding-confirmations` | `{bindingId,accepted}`；必须车端明确确认，不能定时假成功 |
| `/index` | `{clips:Clip[]}`，替换车端索引，最大4096项；保留独立云索引 |
| `/telemetry` | `{clipId,telemetry:TelemetryResult}`，仅显式传输的真实侧录 |
| `/job-results` | `{jobId,state,items?,reason?,actual?}`；取消/过期/revision过时回执拒绝 |
| `/live-signals` | `{sessionId,type:answer/ice,data}` |
| `/live-status` | `{sessionId,channels:[{direction,status}]}`；仅实际首帧可标ok |
| `/media/{clipId}/{direction}` | 显式MP4上传，单路256MiB、全服务8GiB上限，不自动启动云上传 |

浏览器DTO以 `web/src/api/types.ts` 为准，路由清单见 `openapi.json`。`mediaUrl` 是经当前会话鉴权的地址，撤销后Range与在途读取均失效。设备必须尊重AutoService自动运行/全局关闭选择，并以jobId幂等执行；本轮服务器实现不代表Android端已发布这些新设备业务消息，更不代表WebRTC编码/原车深睡唤醒通过。

验证：`cd server/galaxyd && go test -race ./...`；`cd web && npm exec -- vue-tsc --noEmit && npm run build`。设备测试用隔离临时目录与合成夹具，不接车辆或真实账号，不生成真实观看流。

### 公共绑定与实时视频追加

AutoService/AutoRecord共用“车辆与账号”页；`POST /device/v1/binding-state`返回归属，`POST /device/v1/unbind`执行车端明确解绑。首次设备登记可用私有部署的Enrollment，车辆归属不依赖诊断窗口。`basicInfo`保存带来源的基础信息；绑定长期存在，只有临时码5分钟过期。

`POST /web/v1/vehicles/{id}/clips/{clip}/prepare`申请原片/侧录按需准备作业；允许owner/viewer读取，删除仍仅owner。设备按jobId回执。`PUT /web/v1/vehicles/{id}/live-sessions/{sid}/view`设置每方向flat模式；实时流只用WebRTC/H.264，SDP/ICE沿既有signals接口，没有JPEG帧接口。

自有TURN部署：`bash scripts/deploy_vehicle_turn.sh`；独立`galaxyplay-turn.service`，3478 TCP/UDP与49160–49200 UDP。`GALAXY_TURN_SECRET_FILE`指向服务器私有密钥，`GALAXY_TURN_URLS`配置自有TURN地址；观看会话返回短期`iceServers`。云安全组和客户端网络仍须允许这些端口，服务启动不等于公网可达。验证详情见[实现记录](../../docs/AutoRecord/车辆绑定与WebRTC联动-20261105.md)。


回放Range追加：设备`POST /media-ready`登记仅供本次观看的原片大小；`POST /range-poll`有界长轮询读取任务，`POST /range-result?id=...`提交最多512KiB二进制结果。浏览器仍走原Cookie鉴权的MP4 Range地址，服务器按读块回查车辆归属，不要求整文件上传。媒体源描述与请求队列只在内存，既有显式云上传能力保留；暂停在线/解绑/离开观看后回收只读FD和片段租约。原片路径不进入网络接口。
