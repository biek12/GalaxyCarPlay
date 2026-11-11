# Auto 公共 UI

`:auto-ui` 只包含原生 UI 组件，不依赖车辆、CarPlay 协议、Binder 或网络设置 SDK。实现从 CarPlay 的 `galaxy/common` 设置组件迁出；旧 `L7*` 入口委托这里。

- `GalaxyTypography`：标题、标签、值、说明、反馈、系统字体缩放。
- `GalaxyViews` / `GalaxyActionButton`：文字、按钮、图标按钮、开关行。
- `GalaxySettingRow` / `GalaxySettingsCard` / `GalaxySections`：长值自适应列表、操作槽、分组、反馈。
- `GalaxyDialogs`：消息／列表／单选／内容弹窗，关闭与取消，键盘焦点恢复。
- `GalaxySelection`：单选草稿与确认、分段切换。
- `GalaxySwitchStyle`：Flyme开关；外部 `row.setSwitchChecked()` 不触发用户回调。
- `GalaxyStyle`：日夜绑定、按压与禁用、输入框、圆角、主题刷新。

```kotlin
implementation(project(":auto-ui"))

val row = GalaxyViews.switchRow(context, "音乐卡片", "", checked) { enabled ->
    // 调用宿主业务，不在公共组件中持有Service或权限。
}
GalaxyDialogs.builder(context).setTitle("恢复原厂？")
    .setMessage("主题和配置仍会保留")
    .setNegativeButton(android.R.string.cancel, null)
    .setPositiveButton("恢复") { _, _ -> restore() }.show()
```

宿主可注入弹窗密度、明确图标与业务按钮文案。选择弹窗取消不提交；开关回填与点击分开；样式刷新不重建Surface或连接。原生Wi-Fi跳转位于AutoService `settings/NativeWifiSettings`，不属于本模块。

验证：`bash scripts/verify_cluster_refactor.sh`，不启动模拟器或连接车辆。
