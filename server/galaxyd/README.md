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
