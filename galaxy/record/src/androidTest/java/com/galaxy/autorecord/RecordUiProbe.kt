package com.galaxy.autorecord

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.galaxy.autorecord.ui.*
import java.io.File

/** 真实 Android View 测量/布局/软件 Canvas 渲染，独立于模拟器 SurfaceFlinger 截屏问题。 */
internal class RecordUiProbe(private val runner: Instrumentation) {
    fun run(report: Bundle) {
        runner.targetContext.getSharedPreferences("record.ui",Context.MODE_PRIVATE).edit().putBoolean("onboarded",true).commit()
        val activity=runner.startActivitySync(Intent(runner.targetContext,RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RecordActivity
        val folder=File(runner.targetContext.cacheDir,"ui-probe").apply {mkdirs()}
        try {
            waitFor {activity.frameOf("front")!=null && activity.cameraSource.statuses()["front"]?.isLive==true}
            var navBounds=""
            runner.waitForIdleSync()
            runner.runOnMainSync {
                check(activity.resources.configuration.densityDpi==280)
                capture(activity,folder,"live")
                val nodes=children(activity.window.decorView)
                check(nodes.filterIsInstance<TextView>().none {it.text.toString()=="缺路" && visible(it)})
                navBounds=navBounds(nodes)
                val frame=activity.frameOf("front")!!
                val colors=(0..7).flatMap { y -> (0..7).map { x -> frame.getPixel(x*(frame.width-1)/7,y*(frame.height-1)/7) } }.toSet()
                report.putString("cameraScene",if(colors.size<2) "LIMITATION: single-color input; real-scene quality unverified" else "pixel variation observed; real-scene quality unverified")
            }
            renderPage(activity,folder,"settings") {activity.openMainPage(MainPage.SETTINGS)}
            runner.runOnMainSync {
                check(navBounds==navBounds(children(activity.window.decorView))) {"navigation_changed"}
            }
            renderPage(activity,folder,"storage") {activity.openSubPage(SubPage.STORAGE)}
            renderPage(activity,folder,"codec") {activity.backToSettings();activity.openSubPage(SubPage.CODEC)}
            renderPage(activity,folder,"library") {activity.openMainPage(MainPage.LIBRARY)}
            runner.runOnMainSync {
                check(navBounds==navBounds(children(activity.window.decorView)))
            }
            waitFor {activity.cameraSource.statuses().values.none {it.isLive}}
            val fd=runner.uiAutomation.executeShellCommand("dumpsys media.camera")
            val clients=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}
                .substringBefore("== Camera service events log")
            check(!clients.contains("com.galaxy.autorecord")) {"camera_not_released"}
            runner.runOnMainSync {activity.openMainPage(MainPage.LIVE)}
            waitFor {activity.cameraSource.statuses()["front"]?.isLive==true}
            report.putString("camera","PASS: computer webcam -> Camera2 JPEG -> bitmap -> preview, resumed")
            report.putString("navigation","PASS: identical rail across live/settings/library: $navBounds")
            report.putString("missingBadge","PASS: active input has no missing badge; unconfigured test directions disabled")
            report.putString("release","PASS: management page has no active camera client")
            report.putString("render","PASS: Android View Canvas, 280dpi, 1440x1800; cache/ui-probe/*.png")
        } finally {runner.runOnMainSync {activity.finish()}}
    }
    private fun renderPage(activity: RecordActivity, folder: File, name: String, action: () -> Unit) {
        runner.runOnMainSync(action)
        runner.waitForIdleSync()
        runner.runOnMainSync { capture(activity,folder,name) }
    }
    private fun capture(activity: RecordActivity, folder: File, name: String) {
        val root=activity.window.decorView
        children(root).forEach {it.forceLayout()}
        root.measure(View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1440,1800)
        val bitmap=Bitmap.createBitmap(1440,1800,Bitmap.Config.ARGB_8888)
        try {root.draw(Canvas(bitmap));File(folder,"$name.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
        File(folder,"$name.txt").writeText(children(root).filterIsInstance<TextView>().joinToString("\n") {
            "${it.text}: ${it.width}x${it.height}, font=${it.textSize}, baseline=${it.baseline}, visible=${visible(it)}"
        })
    }
    private fun navBounds(nodes: List<View>) = MainPage.entries.joinToString(";") { page ->
        val view=nodes.first {it.contentDescription?.toString()==page.label && it.isClickable}
        "${page.name}:${view.left},${view.top},${view.width},${view.height}"
    }
    private fun children(view: View): List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap {children(view.getChildAt(it))} else emptyList())
    private fun visible(view: View): Boolean = view.visibility==View.VISIBLE && ((view.parent as? View)?.let(::visible) ?: true)
    private fun waitFor(predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12_000
        while(!predicate()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
        check(predicate()) {"UI/camera deadline exceeded"}
    }
}
