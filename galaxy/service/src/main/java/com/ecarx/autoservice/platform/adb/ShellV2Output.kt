package com.ecarx.autoservice.platform.adb

/**
 * shell v2 的标准输出、标准错误和退出码。退出码为空表示对端没有给出。
 */
internal data class ShellV2Output(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
)
