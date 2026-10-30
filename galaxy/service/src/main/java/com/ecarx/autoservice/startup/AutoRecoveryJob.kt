package com.ecarx.autoservice.startup

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build

/** 系统调度的进程恢复机会；目标Android11可启动FGS，其它版本不假定后台启动豁免。 */
class AutoRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (AutoStartup.enabled(this) && Build.VERSION.SDK_INT <= 30) runCatching { AutoStartup.start(this, "recovery") }
        return false
    }
    override fun onStopJob(params: JobParameters) = AutoStartup.enabled(this)
}
