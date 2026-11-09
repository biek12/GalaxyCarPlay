package com.ecarx.autoservice.platform

/** 只准自有记录仪申请其相机和恢复/悬浮显示权限，不接收包名、任意 Shell 或其他 App 的授权请求。 */
internal object ClientPermissionPolicy {
    const val RECORD_PACKAGE = "com.galaxy.autorecord"
    const val CAMERA = "android.permission.CAMERA"
    const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    fun allowed(packages: Collection<String>, permissions: Collection<String>): Boolean =
        packages.size == 1 && packages.single() == RECORD_PACKAGE &&
            permissions.isNotEmpty() && permissions.size <= 8 && permissions.all { it == CAMERA || it == OVERLAY }
}
