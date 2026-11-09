package com.galaxy.autorecord.core

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.galaxy.autorecord.BuildConfig
import java.io.File

/** 本地有界阶段/崩溃证据；不写异常message、车辆值、路径参数、认证材料或图像。 */
object RecordDiagnostics {
    private var directory: File? = null

    @Synchronized fun initialize(app: Application) {
        directory = File(app.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { event("uncaught_${thread.name.take(48)}", error) }
            previous?.uncaughtException(thread, error)
        }
        event("process_start_${BuildConfig.VERSION_NAME}")
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            app.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(app.packageName, 0, 3).forEach { exit ->
                    event("previous_exit_reason=${exit.reason}_status=${exit.status}_at=${exit.timestamp}_pss=${exit.pss}_rss=${exit.rss}")
                }
        }
    }

    @Synchronized fun event(stage: String, error: Throwable? = null, parameters: Map<String,Any?> = emptyMap()) {
        if (error != null) com.galaxy.auto.client.diagnostics.DiagnosticEvents.error(stage,error,parameters)
        val root = directory ?: return
        val text = buildString {
            append(System.currentTimeMillis()); append(" pid="); append(Process.myPid())
            append(" stage="); append(stage.take(160)); append('\n')
            var cause = error
            repeat(5) {
                val current = cause ?: return@repeat
                append(current.javaClass.name); append('\n')
                current.stackTrace.take(32).forEach { frame -> append("  "); append(frame); append('\n') }
                cause = current.cause?.takeUnless { it === current }
            }
        }.take(24 * 1024)
        runCatching {
            val file = File(root, "record.log")
            if (file.length() + text.toByteArray().size > 64 * 1024) {
                File(root, "record.previous.log").delete()
                check(file.renameTo(File(root, "record.previous.log")))
            }
            file.appendText(text)
        }
    }
}

class RecordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordDiagnostics.initialize(this)
    }
}
