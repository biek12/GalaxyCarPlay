# G733 Kanzi音乐卡片原型

2026-11-11 多行研究更新。现有媒体入口与驻车 preload 探针共用四行布局和 `node_replacement.h` 还原层。面板锚定原标题 RenderTransformation 的 Y，局部高 140px，各行使用显式 SRT；原父容器高度与原节点属性保持原值。坐标提取器、八项控制器用例、ASan/UBSan 音乐检查及最新候选见[研究记录](../../../../../../docs/Cluster双模式与原厂多行歌词研究-20261111.md)。本轮多行实屏及还原尚未验收，下文首版参数按历史记录阅读。

自有源码，归CarPlay `galaxy`适配层。此目录不是Kanzi SDK，不包含OEM代码或KZB资源。构建只读取本地固定SHA的原厂ELF及QNX libc符号，产出自有SO和**私有HMI修改候选**，不直接修改原始材料或车机。

## 首版行为

保留原厂 `Media_Text` 容器、外层卡片、封面、媒体注册及页面状态。匹配容器和两个原始文字子节点后，在Kanzi UI线程创建标题、歌手、四行歌词、进度七个TextBlock；原节点保持强引用，失去焦点、数据过期或正常关闭时插回原节点。根场景更换时先恢复旧场景，再定位新场景。

未知进度/时长显示`--:--`，不把未知值显示为0。原始数据来自iPhone NowPlaying及已有原厂媒体端口；不查网络歌词，不传声音，不操作导航/ADAS。四行依次为上句、当前句、后两句；纯逐句输入只填当前行。尺寸324×224为第一版排版参数，尚未在原厂容器校准；不宣称是最终视觉。

## 固定ABI依据

地址最初来自SHA256 `385d8fc526936a32fa08cf8dfd51db731a58c8a3c86fd43709a6269bdf080e64`。2026-11-03实车原件为 `5ceff21b4ac5b651b1b629095aa6da0bcb84e0d7ea6b4a569b6a1913c0c1f94d`：仅25字节位于build-id/只读数据不同，完整.text、动态符号、动态表与重定位一致。两个精确摘要由 `abi_profile.json` 锁定，不推断其他版本兼容。

- `Application::updateOverride` 在 `0x2837f4` 调用vptr+0x50，实际`ClusterFunction`虚表含16字节头，故回调重定位位于表+0x60，原始目标 `0x27d2a0` 为RET。
- `Application::uninitialize` 在 `0x27d48c` 读取vptr+0x38，故关闭回调位于表+0x48，原始目标 `0x27d2a8` 为RET。该RET被ICF共享，不能按addend把所有同地址槽位一起替换。
- `TextBlock2D::create` (`0x20e578`) 通过x8返回16字节libstdc++ shared_ptr；`Node2D::findAbstractChildOverride` (`0x25d560`) 对控制块+8的32位use count加引用；引用释放使用原厂 `_M_release` (`0x15ce28`)。
- TextBlock概念基类的 `getThisObject` (`0x20efb0`) 直接RET，不需要this调整；原厂 `setText` (`0x2104c0`) 读取输入C++11 string的pointer/length，并经 `_M_replace`复制，不转移输入所有权。原调用者在 `0x20e680..0x20e6b4` 构造/释放32字节string。桥只为此固定调用重建窄布局。
- `Node2D::setAutoWidth` (`0x25c650`) 与TextBlock setter均显示PropertyObject位于Node+8。
- `PropertyObject::setProperty<Vector2>` (`0x1b19a8`) 从s0/s1接收两个浮点分量，为按值HFA；桥不能传Vector2指针。
- 动态导出不足的选定Kanzi符号由私有候选增加导出。现场LIEF段扩张候选在loader中段错误；现使用自有ELF重写器保留原厂函数/数据地址与两个LOAD段。动态表、符号/字符串/hash与重定位放到扩展的第二LOAD；原BSS显式填零，重叠调试节先完整迁出。原TEXT字节仅允许PHDR第二LOAD尺寸字段变化，完整.text与每项API另验一致。
- 自有SO沿已实车HAB的双LOAD链接参数；虚表两项变为ABS64后，相对重定位先排列为连续前缀并同步DT_RELACOUNT，不能保留旧计数。

这些是重建ABI证据，不是正式SDP/SDK构建，也不等于QNX加载或真实Kanzi渲染通过。

## GKM1合同

小端；固定72字节头，总帧≤8192字节。0:magic(4)，4:总长u32，8:version u16，10:flags u16（bit0 own-focus/有效，bit1 playing，bit2 position-known，bit3 duration-known）；12:session(16)，其中首u64是发布代次，余u64随机；28:revision u64；36:expires wall-time ms u64；44:position ms u64；52:duration ms u64；60:六个UTF-8长度u16，之后依次标题、歌手、上句、当前句、后两句，每项≤1024字节。

通过AutoService Root对固定自有NFS文件执行原子rename；实车shared/images拒绝Android UID0写入，因此改用已实测可写的 `/data/vendor/nfs/nfs_ota/galaxy-kanzi-field` ↔ QNX `/otaupdate/galaxy-kanzi-field`，不改OEM目录权限。关闭态清空文本；有焦点时每秒续租，租约7秒。QNX使用秒级wall clock，挂接后200ms轮询，未挂接时1000ms；实际恢复延迟还包含时钟粒度和调度，不宣称精确7秒。过期/无效帧不得污染当前完整快照。

原生端拒绝旧代次、旧revision的迟到数据/退出态。阶段日志 `/tmp/galaxy-carplay-kanzi-status.log` 仅保留最近16次变化，含时间/PID/UID，不写歌名、歌词或原始帧。

实车两端UTC相差约8小时。跨时钟输入须先观察同代递增revision及递增续租时间的两个快照，再建立源时钟偏移；单个遗留文件不能校准/挂接。校准后按源时钟继续检查7秒期限，旧代次不能改写校准；不修改Android/QNX时钟。该路径已编译，真实回调/恢复仍需受控场景试验。

## 构建与边界

统一入口见 `scripts/verify_kanzi_music.sh --rootfs <本地rootfs>`。普通APK构建不依赖此私有候选；显式 `-PgalaxyKanziMusicTrial=true` 才把自有SO和合同装入CarPlay assets并启动该数据发布者。APK绝不包含修改后的原厂HMI或OEM库。原型不是“复制SO即可生效”：指定车机必须另行部署与SO同批的HMI候选才会进入回调，现场动作本轮未执行。

宿主替身验证只检验本项目挂接/恢复/会话逻辑，不能代替本车ABI执行。仍待本车验证：QNX loader、原厂节点实际类型与布局、中文字体/昼夜/裁剪、原厂页面切换、退出恢复、封面以及CPU/内存/FD/延迟。


2026-11-03第一轮实际挂接试验后修正：`main:0x11868c`调用`createApplication:0x15be38`，构造函数在`0x15be88`经GOT `0xa4ce18`取到`_ZTV15ClusterFunction`（`0xa28dc0`），加16字节后作为vptr。先前选中的`ExampleApplication`基类虚表不会被实际实例使用。现只改实际ClusterFunction表+0x60（`0xa28e20`）和+0x48（`0xa28e08`）的两个已核验空槽，构建强制核对factory GOT重定位指向目标虚表。修正候选尚待下一轮挂接试验；第一次仅证实SO实际加载，卡片未挂接。
