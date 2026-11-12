package com.ecarx.autoservice.settings

import com.ecarx.autoservice.remote.RemoteSettings

/**
 * 页面使用的服务命令与请求码归属处。
 *
 * 这些值由 `AutoService.onStartCommand` 消费（`prepare`/`model`/`remote-authorize`/
 * `remote-disable`/`stop` 分支）与 `AutoService` 的 `hours` extra 读取点决定；
 * `FEATURES_CHANGED` 直接引用 [ServiceFeatures.ACTION_CHANGED]，不复制第二份协议字面量。
 *
 * 线程：常量，无状态。修改任一值必须同步本注释中的消费位置。
 */
internal object ServicePageCommands {
    /** 开启自动运行并恢复后台工作。 */
    const val START = "start"

    /** 停止后台工作。 */
    const val STOP = "stop"

    /** 功能开关变化；引用既有常量，避免两处字面量漂移。 */
    const val FEATURES_CHANGED = ServiceFeatures.ACTION_CHANGED

    /** 重新初始化已开启的能力。 */
    const val PREPARE = "prepare"

    /** 重新应用车型配置。 */
    const val MODEL = "model"

    /** 开启 12/24 小时远程授权窗口。 */
    const val REMOTE_AUTHORIZE = "remote-authorize"

    /** 关闭远程授权并撤销当前会话。 */
    const val REMOTE_DISABLE = "remote-disable"

    /** 远程授权小时数；取值由 [RemoteSettings.authorize] 限制为 12/24。 */
    const val EXTRA_HOURS = "hours"

    /** 车型名 extra（L6/L7）；诊断页当前直接写偏好后再发 [MODEL]。 */
    const val EXTRA_MODEL = "model"

    /** 诊断页认证覆盖的文件选择请求码。 */
    const val AUTH_DOCUMENT_REQUEST = 71

    /** 认证覆盖文件选择 MIME；与 `ACTION_OPEN_DOCUMENT` 搭配。 */
    const val AUTH_DOCUMENT_MIME = "*/*"
}
