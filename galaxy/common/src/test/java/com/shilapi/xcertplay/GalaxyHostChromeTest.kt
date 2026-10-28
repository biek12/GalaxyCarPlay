package com.shilapi.xcertplay

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 沿旧Galaxy导航浮层回归迁移；浮层不能挤压画面或吞掉收起后的投屏触控。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class GalaxyHostChromeTest {
    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1440, 1800)
    }

    private fun tap(root: View, action: Int) {
        val event = MotionEvent.obtain(0, 100, action, 1200f, 1500f, 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun connectedOverlayKeepsVideoBoundsAndCollapsedTouchesReachVideo() {
        val activity = activity()
        var touches = 0
        val root = FrameLayout(activity)
        val video = View(activity).apply { setOnTouchListener { _, _ -> touches++; true } }
        val navigation = GalaxyHostChrome(activity).navigation({}, {})
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(navigation, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        layout(root)
        navigation.setConnected(true)
        assertFalse(navigation.expanded)
        tap(root, MotionEvent.ACTION_DOWN)
        tap(root, MotionEvent.ACTION_UP)
        assertEquals(2, touches)
        navigation.expand()
        tap(root, MotionEvent.ACTION_DOWN)
        tap(root, MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, touches)
        assertFalse(navigation.expanded)
        // 收起动画会请求重新布局，Robolectric 默认窗口(320宽)会把内容卡回小屏；
        // 这里按目标中控尺寸重新布局一次，验证浮层与画面同为兄弟层、不抢占画面空间。
        layout(root)
        assertEquals(1440, video.width)
        assertEquals(1800, video.height)
        activity.finish()
    }

    @Test fun disconnectedConnectionActionOpensProductConnectionPageOnly() {
        CarPlayBackgroundSession.clear()
        val activity = activity()
        val pages = mutableListOf<String>()
        var released = 0
        val navigation = GalaxyHostChrome(activity).navigation({ released++ }, pages::add)
        layout(navigation)
        fun clickables(view: View): List<View> =
            (if (view.isClickable && !view.contentDescription.isNullOrEmpty()) listOf(view) else emptyList()) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { clickables(view.getChildAt(it)) }
                else emptyList()
        val actions = clickables(navigation.getChildAt(1))
        assertEquals(5, actions.size)
        actions[1].performClick()
        assertEquals(listOf("settings-connection"), pages)
        assertTrue(released > 0)
        assertFalse(navigation.expanded)
        activity.finish()
    }

    @Test fun waitingCancelIsOneUserActionAndDoesNotConnectOrStopByItself() {
        val activity = activity()
        var cancelled = 0
        var returned = 0
        val panel = GalaxyHostChrome(activity).waiting(true, { returned++ }, { cancelled++ }, {})
        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
        val cancelLabel = activity.getString(com.shilapi.xcertplay.host.R.string.l7_cancel_connection)
        val cancel = views(panel).filterIsInstance<android.widget.Button>()
            .first { it.text.toString() == cancelLabel }
        cancel.performClick()
        assertEquals(1, cancelled)
        assertEquals(0, returned)
        assertFalse(cancel.isEnabled)
        activity.finish()
    }
}
