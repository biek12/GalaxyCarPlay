# AutoService 原生 Wi-Fi 连接帮助（独立改动）

2026-11-11。此功能只改 AutoService，不进入 AutoCluster 原型，也不属于公共 UI 组件。

`ServiceActivity` 已收到后台状态、自动运行启用且 ADB／Root 已启用但未就绪时显示连接帮助。通过 WifiManager 观察到 Wi-Fi 关闭才提示“Wi-Fi 已关闭”；其他情况只提示连接尚未就绪、可检查Wi-Fi，具体原因看诊断。不把TLS／密钥授权失败统一解释为网络关闭。

点击调用 service/settings/NativeWifiSettings 的 ACTION_WIFI_SETTINGS，直接打开系统 Wi-Fi 页面；没有可用入口或被拒绝时显示明确提示。页面恢复时重新读取状态，不强开Wi-Fi、不更改自动运行或基础功能选择。新增 ACCESS_WIFI_STATE 仅用于观察。

所用文案／按钮由auto-ui呈现，但启动系统设置的动作属于AutoService功能实现。CarPlay的参考客户端Wi-Fi入口位于 GalaxySettingsActivity.openCarClientWifiSettings；热点设置入口属于另一条流程，不能混用。

本机验证入口 `bash scripts/verify_shared_ui.sh [--mirror]`，仅编译／资源链接，无Lint、模拟器或车辆连接。此前离线依赖解析阻断已通过既定版本依赖准备与同版本AAPT2工具解决；本批已完成编译与资源链接，仍不得写成已安装或已验证车机原生页面跳转。本批不生成新签名。
