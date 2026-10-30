package com.ecarx.autoservice.startup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** 用户授权的默认无人值守模式；主动停止先持久化关闭，系统重建不能反向打开。 */
internal object AutoStartup {
    fun enabled(context: Context) = context.getSharedPreferences("platform", 0).getBoolean("automatic", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("platform", 0).edit().putBoolean("automatic", enabled).commit())
        if (enabled) schedule(context) else cancel(context)
    }
    fun start(context: Context, action: String) {
        if (!enabled(context)) return
        schedule(context)
        val intent = Intent(context, com.ecarx.autoservice.AutoService::class.java).setAction(action)
        context.startForegroundService(intent)
    }
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (!enabled(context) || jobs.getPendingJob(8157) != null) return
        jobs.schedule(JobInfo.Builder(8157, ComponentName(context, AutoRecoveryJob::class.java))
            .setPersisted(true).setPeriodic(15 * 60000L).build())
    }
    fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(8157) }
}
