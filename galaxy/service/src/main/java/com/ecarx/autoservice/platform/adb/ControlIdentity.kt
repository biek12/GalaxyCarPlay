package com.ecarx.autoservice.platform.adb

/**
 * 一次 ADB 身份读取得到的 UID。没有读到时保持为空，不在这里伪造 0。
 */
internal data class ControlIdentity(
    val uid: Int?,
)
