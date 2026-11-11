# Auto 公共 UI

`:auto-ui` 只包含原生 UI 组件，不依赖车辆、CarPlay 协议、Binder 或网络设置 SDK。实现从 CarPlay 的 `galaxy/common` 设置组件迁出；旧 `L7*` 入口委托这里，不维护第二套。

静态骨架在 `res/layout/galaxy_ui_*.xml`（模块已启用 View Binding）；字号/行高/颜色/圆角/尺寸在 `values`、`color`、`drawable`。Kotlin 只绑定状态、事件与确需动态的内容。

文字角色只有一份来源：`GalaxyUi.TextAppearance.*` 引用 `dimens` 里的字号与行高；XML 用 `style=` 直接引用，代码用 `GalaxyViews.text(role)` 应用同一资源，并在 View 层补 `setLineHeight`（`setTextAppearance` 不会应用段落 lineHeight）。

- `GalaxyTypography`：标题、标签、值、说明、反馈、系统字体缩放；XML 对应 `GalaxyUi.TextAppearance.*`。
- `GalaxyViews` / `GalaxyActionButton`：文字、按钮、图标按钮、分组、开关行；XML 对应 `galaxy_ui_{text,button,icon_button,group}.xml`。
- `GalaxySettingRow` / `GalaxySettingTextLayout` / `GalaxySettingsCard` / `GalaxySections`：长值自适应、操作槽、相邻行分隔线、分组与反馈。
- `GalaxyDialogs` / `GalaxyDialogPanel`：消息／列表／单选／内容弹窗，单一滚动区、关闭与取消、键盘焦点恢复。
- `GalaxySelection`：单选草稿与确认、分段切换。
- `GalaxySwitchStyle`：Flyme 开关；外部 `row.setSwitchChecked()` 不触发用户回调。
- `GalaxyStyle`：日夜绑定、按压与禁用、输入框、主题刷新。
- `GalaxyStyle.button(radius = …)`：半径是真实动态参数（分类栏传 2dp，其余 4dp），每次绑定取独立背景副本只改本实例内容层；主/次按钮共用资源形状与状态色。

```kotlin
implementation(project(":auto-ui"))

// 兼容入口：标题 + 说明 + 前置开关；整行点击转发，外部回填不回调。
val row = GalaxyViews.switchRow(context, "音乐卡片", "", checked) { enabled ->
    // 调用宿主业务，不在公共组件中持有 Service 或权限。
}

// 末位开关（AutoService 首页）：同一实现，槽位在行末。
val setting = GalaxySettingRow(context, title, description, iconRes)
GalaxyViews.attachSwitch(setting, title, checked, leading = false) { enabled -> save(enabled) }

GalaxyDialogs.builder(context).setTitle("恢复原厂？")
    .setMessage("主题和配置仍会保留")
    .setNegativeButton(android.R.string.cancel, null)
    .setPositiveButton("恢复") { _, _ -> restore() }.show()
```

```xml
<!-- 页面可直接声明设置行；标题/说明/图标来自属性，操作槽由代码插入。 -->
<com.galaxy.auto.ui.GalaxySettingRow
    android:id="@+id/service_projection"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:galaxyDescription="@string/service_feature_projection_description"
    app:galaxyLeadingIcon="@drawable/ic_service_projection"
    app:galaxyTitle="@string/service_feature_projection" />
```

宿主可注入弹窗密度、明确图标与业务按钮文案。选择弹窗取消不提交；开关回填与点击分开；`GalaxyStyle.refresh(view)` 会在同一实例上重解析颜色与背景（含已打开弹窗），不重建 Surface 或连接。原生 Wi-Fi 跳转位于 AutoService `settings/NativeWifiSettings`，不属于本模块。

验证：`GALAXY_REFACTOR_CACHE=<cache> bash scripts/verify_android_refactor.sh`（真实 Android 编译 + 本机 Robolectric），不启动模拟器或连接车辆。
