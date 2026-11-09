# CarPlay原厂Kanzi音乐卡片原型

2026-11-03，Asia/Shanghai。用户授权先做音乐卡片扩展，并建议放在CarPlay的galaxy适配层。首轮完成离线实现与构建。用户随后开启车机并授权连接调试；本页末尾追加指定L6的现场结果，历史离线结果不覆盖为实车结论。

## 首版内容与归属

代码归`galaxy/common`，应用仍是CarPlay / `com.ecarx.carplay`，不新增App、不迁入AutoCluster。AutoService继续承担唯一Root执行入口；本功能不自建ADB/Root/QNX登录。

首版保留原厂音乐卡片外层、封面和`Media_Text`容器身份，只替换其中原始标题/歌手两个文字子节点。组件显示标题、歌手、四行歌词（上句/当前/后两句）与进度；没有时间信息时显示`--:--`。仅使用iPhone原始NowPlaying/歌词，不查询网络歌词，不传PCM，不修改导航/ADAS或原车启动项。

原厂外层页面状态和封面仍由原厂链处理；新文字子树由自有组件供数。数据过期、焦点移出、CarPlay会话关闭或HMI正常关闭时，代码把保留的原文字对象插回容器。根场景更换时先恢复旧场景，再定位新场景。首版324×224文字区及字体层级尚未在真实原厂容器校准，不能称为最终视觉。

## 首轮离线接入方式（现场修正见文末）

这版是**固定G733 HMI的原生场景桥候选**，不是“拷贝SO即可生效”的通用插件。没有可重建的原厂Studio工程，因此组件由原厂标准EmptyNode2D/TextBlock2D在运行时组成，不生成或改写KZB，不另建Screen窗口。

[构建脚本](../scripts/build_kanzi_music.py)只接受本车已核验的HMI SHA256 `385d8fc526936a32fa08cf8dfd51db731a58c8a3c86fd43709a6269bdf080e64`，在私有副本中：

1. 为自有SO所需的选定原厂Kanzi函数/属性增加动态导出。
2. 只替换ExampleApplication vtable的两个已证实空回调：表+`0x60`为onUpdate，表+`0x48`为onShutdown。
3. 增加自有`libgalaxy_kanzi_music.so`依赖。其他原厂逻辑、资源和引擎继续使用原件内容。
4. 生成后重新核对回调重定位、导出地址及每项导入API机器码；LIEF增加ELF空间产生的统一地址平移单独登记。

两个槽位由实际调用点确认：`Application::updateOverride`的`0x2837f4`读取vptr+0x50；`Application::uninitialize`的`0x27d48c`读取vptr+0x38。原厂多个空方法因ICF共用RET，不能根据相同addend把所有槽位一起改掉。完整ABI重建依据见[源码说明](../galaxy/common/src/main/cpp/kanzi_music/README.md)。这是重建ABI候选，不是官方SDP/SDK产物；QNX loader及真实Kanzi对象调用仍未运行验证。

## 数据和生命周期

- [原厂媒体端口](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyL6DimMediaPort.kt)在metadata/progress/focus事件中提供相同原始快照，不改变既有EAS和DIM发送。
- [帧合同](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyKanziMusicFrame.kt)为自有GKM1小端格式：最大8192字节、六项有界UTF-8文本、已知状态位、位置/时长、会话代次、revision和到期时间。关闭态清空原始文本；不保存听歌历史。
- [发布者](../galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyKanziMusicPublisher.kt)通过AutoService的既有ROOT接口，对固定自有文件原子rename。单个在途请求、超时处理、迟到回调ticket和退出态独立清理均已接入。有焦点时每秒续租；普通APK不启动试验发布者。
- 当前使用Android `/data/vendor/nfs/nfs_ota/galaxy-kanzi-field/galaxy-carplay-kanzi-music.bin` ↔ QNX `/otaupdate/galaxy-kanzi-field/galaxy-carplay-kanzi-music.bin`。首轮shared/images映射实车可读但不能由本App Root写入，修正依据见文末。
- [原生组件](../galaxy/common/src/main/cpp/kanzi_music/music_component.cpp)只在Kanzi主循环回调中处理节点。限定搜索深度32、节点预算4096；目标必须匹配`Media_Text`及`Media_Title/Media_Artist`。原容器留在场景中，外层状态机不被替换。
- 挂接后200ms、未挂接时1000ms轮询；帧租约7秒。秒级时钟和调度会增加实际过期检测时间，恢复不是精确7秒实时保证。旧会话/旧revision的迟到数据不能重新挂接或拆掉新卡片。
- 原生阶段日志位于 `/tmp/galaxy-carplay-kanzi-status.log`，最近16次变化，有时间/PID/UID，不包含歌名、歌词、密钥或任意Shell正文。Android日志只记录发布结果，不能据`written=true`认定已显示。

## 构建和验证

单命令，只读本地rootfs，不连接、不执行目标、不安装、不跑Lint：

```sh
bash scripts/verify_kanzi_music.sh --rootfs /Users/anjon/Documents/ChatGPT/Nexusx_星枢/.local/g733-offline-environment-20261025/rootfs
```

流程包括：

- 宿主C解码器边界/UTF-8/到期/截断回归及5000组确定性随机输入，开启ASan/UBSan。
- 宿主替身执行实际自有组件逻辑，检查挂接、内容更新、旧revision、焦点退出、旧会话退出、场景切换、到期与正常关闭恢复，开启ASan/UBSan。**替身不包含原厂Kanzi，也不代验跨ABI执行。**
- QNX AArch64自有SO和固定HMI私有候选构建与ELF核对。
- GKM1及原始歌词/曲目身份/媒体会话四组JVM回归，随后`:mobile:assembleDebug`。
- 核对APK内自有SO摘要与构建报告一致，且APK不包含HMI候选或OEM系统库。

试验构建参数为`-PgalaxyKanziMusicTrial=true`：APK assets只包含自有SO与公开合同。常规源码构建不依赖本机OEM材料；没有试验合同的普通包不会启动本发布者。构建流程不改变applicationId、显示名或签名配置。实际替换仍需要与APK同批的HMI候选，仅更新APK不会使原厂组件自动变化。

产物与最终结果保存在本机忽略目录，摘要绑定报告为[trial.json](../.work/kanzi-music/trial.json)：

- CarPlay试验APK：`galaxy/mobile/build/outputs/apk/debug/mobile-debug.apk`。
- 自有QNX组件：`.work/kanzi-music/libgalaxy_kanzi_music.so`。
- 私有原厂HMI修改候选：`.work/kanzi-music/cluster-hmi-music-candidate`，不进入APK、源码提交或公共发布包。

## 本轮实际结果

时间：2026-11-03T14:16:26.211731+08:00（Asia/Shanghai）。上述统一命令完成：52项JVM回归/0失败/0跳过；宿主解码边界和5000组随机输入、组件挂接/恢复/过期/旧会话/场景切换测试均通过ASan/UBSan；QNX自有SO、私有HMI候选及CarPlay assembleDebug通过，不跑Lint。候选绑定22项原厂API及2个空回调。

| 产物 | SHA256 |
|---|---|
| CarPlay试验APK | `158bd8c18693e03f62b82644c4bad821327297d6af2a9e4b72fb0a525cba30af` |
| 自有QNX组件SO | `30ee27b5943501c0706d99b4fe379f099109d1246b92a712142fa8dbe3ac16e6` |
| 私有HMI候选 | `ac4d87cedf5d38fdad14a855cf0ba5de63ee504e9147bdb789e259c7b0540f1a` |

APK内自有SO与构建摘要一致；未包含HMI候选/OEM系统库。原厂材料摘要仍与锁定基线一致。签名配置未修改；未安装、未推送。

## 目标车尚未验收

仍需分别核验：QNX加载与依赖解析、回调触发、真实节点类型/字体/昼夜/裁剪、原厂音乐页切换和提示状态、原始歌词/进度/封面、退出后原节点恢复，以及CPU/内存/线程/FD/延迟。已有HAB显示或原厂媒体标题成功不代验此原型；本轮没有证明原车图传告警已修复。

部署前应先读回目标HMI摘要，保存同车原件与属性，再安排受控试验和恢复；本轮没有编写自动改原车启动项或自动覆盖HMI的App流程。当前交付为具体可审查的离线试验候选，非已装车完成声明。


## 追加：2026-11-03现场连接与加载调试

用户开启车辆、提供新验证码并授权连接后，经个人HTTPS桥调用AutoService自身本机ADB/Root；没有电脑ADB。Android API30/arm64，App自身ADB及Root回读UID0；QNX认证材料由车机内现有AutoService流程读取并登录，另行验证QNX UID0。验证码、凭据和会话token未写入源码/本记录。

### 本车版本和装载格式

- 实车原件 SHA256 为 `5ceff21b4ac5b651b1b629095aa6da0bcb84e0d7ea6b4a569b6a1913c0c1f94d`，与离线原件同为13980616字节。逐字节对照仅25字节不同，分别在`.note.gnu.build-id`与`.rodata`；完整`.text`、PHDR、动态表、动态符号及重定位一致。两份原件分别保存，按实车精确摘要另生成候选。
- 实车libc摘要仍为 `ce94da81ea340fbf94637953ba5fc9004b7596f3c2b6e624253cb7ea30d77c86`。
- 初始LIEF扩张候选的QNX `ldd` 在libc偏移`0x584c0`读取重定位表时段错误。原厂进程继续运行；该故障没有发生在原厂进程中。
- 已改用自有[ELF重写器](../tools/kanzi_music/patch_hmi.py)：保留两个LOAD段、原函数/属性地址、原PLT和所有原始符号索引；只扩展第二LOAD承载新动态表，并显式保留原BSS零值。重叠调试节完整迁出；不再整体平移原厂代码。GNU hash重新建立，回调转ABS64后相对重定位前缀及DT_RELACOUNT同步为17838。属性若在BSS中，比较装载初值零，不能把调试文件字节当作属性内容。
- 自有SO也改为既有已实车HAB采用的双LOAD链接参数。双LOAD候选已在QNX解析全部依赖，包括自有SO，无缺库或上述段错误。**ldd不执行Kanzi主循环，不能证明回调、ABI对象调用或组件显示已通过。**单独把SO交给ldd曾报Not enough memory，不作为完整候选加载结果。
- 重建器与链接参数另参照[QNX 7.0动态链接说明](https://qnx.com/developers/docs/7.0.0/com.qnx.doc.neutrino.sys_arch/topic/dll.html)；本项目具体结果以上述指定固件实测为准。

### 状态文件和原始手机数据

实测Android UID0写 `/data/vendor/nfs/shared/images` 返回Permission denied。原定映射本身存在，但可读不代表可写。已改为可写的自有OTA桥目录：

- Android：`/data/vendor/nfs/nfs_ota/galaxy-kanzi-field/galaxy-carplay-kanzi-music.bin`。
- QNX：`/otaupdate/galaxy-kanzi-field/galaxy-carplay-kanzi-music.bin`。
- 阶段日志：QNX `/tmp/galaxy-carplay-kanzi-status.log`。

未改OEM目录权限。原厂封面旧写入端也在现场返回copied=false，本轮不将HTTP封面读取成功当成QNX封面已显示。

用户回复已播放后，读取到原始手机播放态、phoneLyricPresent=true、oemLyricSource=iphone、OEM发送计数持续增加；网络查询enabled=false/projectionDemand=false。GKM1文件存在并续写，实取帧flags=15，标题/歌手/当前句非空，手机本次未提供完整多行内容，前后歌词行为空；不拼网络歌词补行。这里只证明数据接收与文件发布，未证明原厂卡片已消费。

现场QNX声明UTC时间比Android约快8小时，会使原7秒期限被误判。组件增加源时钟校准：先观察同代递增revision和续租时间的两个快照；单个遗留文件不能建立校准。校准后仍检查期限及旧代次门控，不修改两端系统时间。已编译/加载解析，真实回调中的校准、挂接与过期恢复尚未实测。

### 安装、备份与下一步边界

最终CarPlay通过`pm install -r`同包更新并回读安装摘要，保留签名与数据；本轮只做必要assembleDebug，不跑Lint或重复单元测试。APK内SO与已暂存自有SO摘要一致。最终摘要及采样结果见本机忽略目录`.work/kanzi-field/`和公开脱敏证据`tools/evidence/kanzi-music-field-20261103.json`。

原厂HMI原件与application.cfg已备份到自有OTA目录，HMI备份摘要与实车原件一致；原厂HMI文件、配置、启动项及监控清单未修改，原进程未停止。现场回读原HMI为UID0、security type ID36/default_root_t；启动源码`/apps/cluster/common/bin/startup:198–201`在原目录以`on -p 15 -X aps=System ./cluster-hmi &`启动，并登记APP_MONITOR。不能把任意杀进程后监控重启当成自有组件已生效。

下一步拟议为驻车条件下限时原厂HMI试验：备份/摘要匹配后临时激活候选，单独记录回调、节点挂接、画面、退出恢复，随后恢复原件。其涉及原厂HMI临时替换/重启，超出AGENTS.md既有“不改原车启动项/HMI”的设备边界，尚未执行；须单独获得用户对此具体步骤的授权。自有组件的候选依赖固定到自有OTA绝对路径，避免为试验改OEM启动环境或安装系统库。


现场恢复材料已落实为[限时试验/恢复脚本模板](../tools/kanzi_music/hmi-trial.sh)及[离线绑定器](../scripts/prepare_kanzi_music_field.py)。生成命令：

```sh
python3 scripts/prepare_kanzi_music_field.py --original .work/kanzi-field/cluster-hmi-original
```

脚本默认仅inspect材料；`activate-60s --authorized`才会执行原厂文件原子临时替换及HMI重启。独立60秒定时器在写原厂文件前启动，按代次回收并恢复原件，复用原厂APP_MONITOR；不改启动项/监控清单。脚本已生成、语法检查，**激活、定时回收及原厂可视恢复尚未执行**。断电/QNX重启会终止自有定时器，不能把计划的60秒恢复表述为已验证保证；这也是必须明确原厂文件替换授权及驻车条件的原因。

18:24只读截图显示当时D挡/66km/h，因此没有执行任何原厂HMI切换。用户随后确认已P挡；新确认不将旧截图改写为驻车截图。原厂画面可见音乐标题、歌手和进度，封面仍是默认图；该截图为原厂旧组件，不是自有组件显示成功。


最终现场绑定（2026-11-03，Asia/Shanghai）：

| 工件 | SHA256/结果 |
|---|---|
| 已安装CarPlay APK | `1983b348e15f9bfc5e1d40e59c8369c9e592bba81ee93b5cd494526492129091` |
| 已暂存自有SO（APK内一致） | `1ccdab06438ee7f13c49eea6b958871c4bdd68dd6fe32d575fbaad99442571a2` |
| 绝对自有依赖路径的私有HMI候选 | `efc0a89ff54984f5a59431e5a3ed2a3f95f8b92704ac95fdd0c213fe252cd226` |
| QNX原厂库搜索路径下依赖解析 | 全部解析，包括自有SO；没有额外修改原厂启动环境 |
| QNX脚本inspect | `TRIAL_MATERIALS_MATCH` |
| 两端紧邻UTC读回 | QNX比Android快28798秒，两次请求间隔约2秒，符合约8小时偏差 |
| 最终App媒体状态 | 播放中、原始手机歌词存在、来源iphone、网络查询关闭 |
| 原厂HMI | 原PID仍运行，原件CRC/大小仍匹配，未临时替换/重启 |

仅新增/修正自有源码与现场材料，没有提交、推送或修改签名。全仓`git diff --check`仍报告既有上游patch文件中的尾空格；本轮目标文件检查通过，不顺带重写他处patch。上述加载/文件/媒体结果均不能代替自有卡片实际显示和恢复验收。


用户随后明确回复“允许驻车限时试验”，本轮一次60秒原厂HMI临时替换/重启及恢复获授权；授权不扩大到启动项、监控清单或长期安装。下列现场结果另行记录，不以授权代验成功。


## 获授权的第一轮限时试验与恢复

首次原厂文件临时替换在写入前检查退出：目标位置不可写，未改文件或进程。改用QNX官方[进程管理器内存符号链接](https://qnx.com/developers/docs/7.0.0/com.qnx.doc.neutrino.lib_ref/topic/p/pathmgr_symlink.html)，经自有文件实际测试映射/撤销后，用自有`path-guard`只映射单个HMI路径，不重挂/apps、不写OEM文件、不改监控清单。此映射在QNX重启时消失，比原来的文件覆盖方案范围更窄。工具仅允许固定自测路径/HMI路径；不接受任意目标。

现场脚本另修正QNX缺少printf/rmdir，均在实际激活前发现；改用已验证可用的echo/rm。路径工具从OTA复制到自有`/var/data/galaxy-kanzi-field`后执行，避免NFS暂存文件的Text file busy。

19:03实际激活内存映射、重启HMI。新PID `226938962` 的库清单证实加载自有SO，画面仍是原厂卡片，没有组件阶段日志。计时器撤销映射后，脚本未匹配监控重启产生的`cluster-hmi &`参数格式，进程回收没有按时完成；修复匹配及恢复检查后进行了补充恢复。**不能写成整轮在60秒内完成恢复。**最终PID `229331026` 的库清单不含自有SO，原件CRC/大小仍为2593595783/13980616，恢复脚本返回`ORIGINAL_HMI_RESTORED`/0；原厂磁盘文件从未写入。自有试验锁已清理，原厂监控/启动项未改。该轮只证明候选启动与SO加载，未证明自有卡片显示、节点恢复或时钟校准。

恢复后沿反汇编查明：`main:0x11868c`→`createApplication:0x15be38`→`Application`基类构造→`0x15be88`读取GOT `0xa4ce18`，其RELATIVE addend为`0xa28dc0`，即`_ZTV15ClusterFunction`；加0x10为实际vptr。原先改的是未实例化的ExampleApplication基类虚表，因此回调不会进入自有组件。修正候选改实际ClusterFunction的表+0x60/0x48（`0xa28e20`/`0xa28e08`），两项原始目标仍为已证实空RET；新增factory-GOT来源断言，避免再次仅凭基类名选表。自有SO/CarPlay合同不变，不需要再次安装APK。新候选尚待下一轮授权及实测。

后续单命令准备材料：先 `python3 scripts/build_kanzi_path_guard.py --rootfs <本地rootfs>`，再执行上方字段绑定脚本。修正后的恢复脚本同时识别`./cluster-hmi`与`cluster-hmi`，并等待新的、确有PIE映像且不含自有SO的PID，不能仅凭旧PID暂时缺少库名认定恢复完成。


## 第二、三轮结果与用户结束现场调试

用户随后明确允许继续驻车迭代，每轮约60秒临时映射并恢复，不再逐轮询问。

- 第二轮使用实际ClusterFunction虚表。候选PID235483218的阶段日志出现`initialized → waiting_source_clock → source_clock_calibrated → mounted`，证明实际回调、时钟校准与节点挂接执行。已取回19:24画面，仍只见两行且有裁剪，不能据mounted宣称歌词布局完成。约60秒后日志为`HMI_MEMORY_ALIAS_REMOVED → ORIGINAL_HMI_RESTORED`，新原厂PID236015698，原件CRC/大小仍匹配，试验锁消失。原始截图保存在`.work/kanzi-field/round2/live.png`，不提交截图或听歌信息。
- 第三轮改为只选择effectively-visible的目标，采用真实ActualWidth；新面板不复制原容器几何，文字克隆清除变换/对齐/裁剪局部值。高度改140px，保存原高度的局部值存在性与值，退出时恢复局部值或移除自有覆盖，保留原绑定。补数值几何阶段日志，不写文本。自有SO/候选转存QNX自有`/var/data/galaxy-kanzi-field`后执行/加载，OTA只作为传输桥与GKM数据位置。
- 第三轮20:01启动，PID249147474稳定，但输入文件为约28分钟前的关闭态，Android媒体报告playing=false、phoneLyricPresent=false，原生停在waiting_source_clock，没有用旧文件挂接。新属性/几何调用未被真实活动数据触发，**140px排版与高度恢复尚未实测**。
- 第三轮20:02读回自动撤销及`ORIGINAL_HMI_RESTORED`，原厂PID249950290，原件CRC/大小2593595783/13980616。不会把保留在/tmp的上一PID日志当成当前进程仍挂接。
- HTTPS取结果曾偶发TLS EOF；客户端现在只对已知任务ID的GET轮询重试，POST命令不自动重发。第二轮该异常后先查实际状态，没有重复激活。

用户随后说明已不再连接车辆，准备安排其他任务；本轮停止现场操作，不继续远程连接、新轮次或安装。当前已安装APK仍为此前摘要1983b348...，其内SO为1ccdab...；第三轮几何源码/独立SO已构建和暂存，但没有重新打包安装进最终APK。不得把第三轮源码或独立SO与已装APK混作同批发布。

遗留：核验新几何显示与局部高度撤销；真实原厂封面仍未修复；手机本次只给当前句，前后行不能用网络替代；更新宿主Kanzi替身的新增可见性/属性ABI入口后再运行统一离线回归（本轮按现场要求未重跑）；后续定版再构建同批APK/工件。原厂文件/挂载/启动项/监控清单未修改，所有轮次仅用单文件内存映射，最后已恢复原厂。
