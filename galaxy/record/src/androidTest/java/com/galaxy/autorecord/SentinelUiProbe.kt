package com.galaxy.autorecord

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.galaxy.autorecord.core.PrefsSettingsStore
import com.galaxy.autorecord.runtime.*
import java.io.File

internal object SentinelUiProbe {
    fun run(test:Instrumentation,report:Bundle){
        val context=test.targetContext
        val store=PrefsSettingsStore(context)
        val previous=store.load()
        test.runOnMainSync{
            store.save(previous.copy(sentinelEnabled=true))
            RecordSessionHub.get(context).sentinelState=Bundle().apply{putString("state","waiting_lock")}
        }
        var activity:android.app.Activity?=null
        try{
            activity=test.startActivitySync(Intent(context,SentinelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen=checkNotNull(activity)
            test.runOnMainSync{
                val content=screen.findViewById<FrameLayout>(android.R.id.content)
                content.addView(TextView(screen).apply{
                    text="隔离模拟器 · 合成状态样本 · 未连接车辆";textSize=20f;setTextColor(0xffffffff.toInt());setBackgroundColor(0xffa65c00.toInt());gravity=Gravity.CENTER
                },FrameLayout.LayoutParams(-1,80,Gravity.TOP))
            }
            test.waitForIdleSync()
            Thread.sleep(300)
            val deadline=android.os.SystemClock.elapsedRealtime()+10_000
            var active=test.uiAutomation.rootInActiveWindow
            while((active==null||active.packageName?.toString()!=context.packageName)&&android.os.SystemClock.elapsedRealtime()<deadline){
                Thread.sleep(200);active=test.uiAutomation.rootInActiveWindow
            }
            val visible=checkNotNull(active){"sentinel_ui_window_missing"}
            check(visible.packageName?.toString()==context.packageName){"sentinel_ui_obscured_by_other_window"}
            check(visible.findAccessibilityNodeInfosByText("等待锁车").isNotEmpty()){"sentinel_status_not_visible"}
            val bitmap=checkNotNull(test.uiAutomation.takeScreenshot())
            val file=File(context.cacheDir,"sentinel-ui-synthetic.png")
            file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            report.putString("sentinelUi","PASS: actual SentinelActivity waiting-for-lock screen; explicitly labelled synthetic, no service/camera/vehicle action")
        }finally{test.runOnMainSync{activity?.finish();store.save(previous)}}
    }
}
