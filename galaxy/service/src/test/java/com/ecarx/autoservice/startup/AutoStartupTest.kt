package com.ecarx.autoservice.startup

import android.app.job.JobScheduler
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AutoStartupTest {
    @Test fun repeatedBootSchedulesOneJobAndStartsWithoutActivity() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = AutoBootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
        assertEquals(1, jobs.size); assertTrue(jobs.single().isPersisted)
        assertEquals("automatic", shadowOf(context).nextStartedService.action)
    }
    @Test fun userStopCancelsJobsAndBlocksQueuedBoot() {
        val context = RuntimeEnvironment.getApplication()
        AutoStartup.setEnabled(context, true)
        AutoStartup.setEnabled(context, false)
        AutoBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(AutoStartup.enabled(context))
        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        assertNull(shadowOf(context).nextStartedService)
    }
}
