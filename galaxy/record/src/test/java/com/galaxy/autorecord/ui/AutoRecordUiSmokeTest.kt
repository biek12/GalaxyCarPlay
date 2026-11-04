package com.galaxy.autorecord.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * 页面冒烟测试：把**每一个页面都真正构建一遍**。
 *
 * 为什么需要它：单元测试只覆盖 core 逻辑，而"lateinit 属性在 build 阶段就被读取"这类错误
 * 只有真正走一遍 build() 才会暴露（实车前就是这么崩的：renderData 早于 toggleChip 创建）。
 * 这个测试跑得比一次远程部署快几个数量级，所以任何改动后先跑它。
 */
@RunWith(RobolectricTestRunner::class)
class AutoRecordUiSmokeTest {
    @Before fun noExternalServiceInUiSmokeTest() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .declareComponentUnbindable(android.content.ComponentName("com.ecarx.autoservice", "com.ecarx.autoservice.AutoService"))
    }

    @Test
    fun `所有一级页与二级页都能构建且可切换`() {
        val controller = Robolectric.buildActivity(RecordActivity::class.java).setup()
        val activity = controller.get()

        MainPage.entries.forEach { page ->
            activity.openMainPage(page)
            assertNotNull("一级页 ${page.name} 未能构建", activity.currentPageView())
        }

        SubPage.entries.forEach { page ->
            activity.openSubPage(page)
            assertNotNull("二级页 ${page.name} 未能构建", activity.currentPageView())
        }
    }

    @Test
    fun `回放页在片段不存在时也必须可构建`() {
        val controller = Robolectric.buildActivity(RecordActivity::class.java).setup()
        val activity = controller.get()

        activity.openPlayback("20261103-221500-000")
        assertNotNull("缺片段的回放页不应崩溃", activity.currentPageView())
        activity.backFromPlayback()
        assertNotNull(activity.currentPageView())
    }

    @Test
    fun `页面切走与回到前台不会崩`() {
        val controller = Robolectric.buildActivity(RecordActivity::class.java).setup()
        val activity = controller.get()

        activity.openMainPage(MainPage.LIVE)
        controller.pause()   // 触发 onHidden + stopPreview
        controller.resume()  // 触发 onShown + startPreview（无权限时走申请分支）
        activity.openMainPage(MainPage.LIBRARY)
        activity.openSubPage(SubPage.DIAGNOSE)
        controller.pause()
        controller.resume()
        controller.destroy()
    }

    @Test fun `已有CAMERA权限时服务不可用不阻塞直连相机准备`() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.CAMERA)
        val controller=Robolectric.buildActivity(RecordActivity::class.java).setup()
        try {
            assertTrue(controller.get().cameraAccessReady)
            assertTrue(controller.get().cameraSource is com.galaxy.autorecord.core.SurfaceCameraSource)
            assertFalse(controller.get().vehicleFeed.snapshot().connected)
        } finally {controller.pause().stop().destroy()}
    }
}
