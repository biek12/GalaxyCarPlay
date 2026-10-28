package com.shilapi.xcertplay

import java.io.File

/** 产品专项日志清单在Galaxy维护；核心默认仍只有官方连接历史，不全局注册产品文件名。 */
internal object GalaxyDiagnosticFiles {
    val additionalNames = listOf(
        "steering-previous-2.log", "steering-previous.log", "steering.log",
        "voice-input-previous-2.log", "voice-input-previous.log", "voice-input.log",
        "debug-previous-2.log", "debug-previous.log", "debug.log",
    )
    val reportNames: List<String> get() = SessionLogFile.REPORT_NAMES + additionalNames
}

/** 不建立第二条队列；产品清理向同一核心队列注入清单，沿用原子代次失效与轮转锁。 */
internal fun AsyncDiagnosticLog.clearGalaxy(directory: File) = clear(directory, GalaxyDiagnosticFiles.additionalNames)
