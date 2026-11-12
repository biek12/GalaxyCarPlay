package com.galaxy.autocluster

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.autocluster.ui.ComponentGallery
import com.galaxy.autocluster.ui.ComponentPresets
import com.galaxy.autocluster.ui.ThemeLibraryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * C2a 标准用例 T4：真实 ThemeActivity 与外壳 View Binding。
 *
 * 只隔离系统服务绑定边界（声明 AutoService 组件不可绑定），既有 ThemeActions 仍真实运行并随
 * Activity 关闭；配置回调按生产 [ThemeActivity.onConfigurationChanged] 路径驱动，不接车、
 * 不假造实车数据、不把宿主结果当 8155/QNX 显示验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w571dp-h1028dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeShellBehaviorTest {
    /** 深度优先遍历视图树，用于确认组件库没有添加入口。 */
    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            } else {
                emptyList()
            }

    /** 宿主离线渲染截图；不是模拟器、车机截图或显示验收。 */
    private fun capture(
        root: View,
        name: String,
    ) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1800)
        val image = Bitmap.createBitmap(1000, 1800, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(image))
            val directory = File("build/outputs/refactor-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            image.recycle()
        }
    }

    /** 外壳 XML、三 tab 等宽与顺序、组件库三列真实预览、配置重建保留原页、返回 finish。 */
    @Test
    fun realShellNavigatesRebuildsOnConfigurationAndFinishes() {
        ClusterPageFixture.declareServiceUnbindable()
        val controller = Robolectric.buildActivity(ThemeActivity::class.java)
        controller.setup()
        try {
            val activity = controller.get()
            assertEquals(
                activity.getString(R.string.theme_title),
                activity.findViewById<TextView>(R.id.theme_title).text.toString(),
            )
            val tabIds =
                listOf(
                    R.id.theme_tab_library,
                    R.id.theme_tab_editor,
                    R.id.theme_tab_gallery,
                )
            val tabs = tabIds.map { activity.findViewById<Button>(it) }
            assertEquals(
                listOf(
                    activity.getString(R.string.theme_tab_library),
                    activity.getString(R.string.theme_tab_editor),
                    activity.getString(R.string.theme_tab_gallery),
                ),
                tabs.map { it.text.toString() },
            )
            val tabHeight = activity.resources.getDimensionPixelSize(R.dimen.theme_tab_height)
            assertTrue(tabs.all { it.layoutParams.height == tabHeight })
            // 等宽：三个 tab 都是 0dp + weight=1，宽度与权重完全一致。
            assertTrue(tabs.all { it.layoutParams.width == 0 })
            assertTrue(tabs.all { (it.layoutParams as LinearLayout.LayoutParams).weight == 1f })
            val footer = activity.findViewById<TextView>(R.id.theme_footer)
            assertTrue(footer.text.isNotEmpty())
            val content = activity.findViewById<FrameLayout>(R.id.theme_content)
            assertTrue(content.getChildAt(0) is ThemeLibraryPage)
            capture(activity.window.decorView, "theme-shell-library")

            // 组件库仍是另一个每行三个真实样式预览的页面，浏览态没有添加入口。
            activity.findViewById<Button>(R.id.theme_tab_gallery).performClick()
            val gallery = content.getChildAt(0) as ComponentGallery
            assertEquals((ComponentPresets.all.size + 2) / 3, gallery.adapter.count)
            val galleryRow = gallery.adapter.getView(0, null, gallery) as ViewGroup
            assertEquals(3, galleryRow.childCount)
            assertFalse(descendants(gallery).filterIsInstance<Button>().any { it.text.contains("添加") })
            capture(activity.window.decorView, "theme-shell-gallery")

            // 我的主题 → 组件库 → 配置变化：原 page 保留，重建后的 tab 仍可操作。
            activity.findViewById<Button>(R.id.theme_tab_library).performClick()
            assertTrue(content.getChildAt(0) is ThemeLibraryPage)
            activity.findViewById<Button>(R.id.theme_tab_gallery).performClick()
            activity.onConfigurationChanged(Configuration())
            val rebuiltContent = activity.findViewById<FrameLayout>(R.id.theme_content)
            assertTrue(rebuiltContent.getChildAt(0) is ComponentGallery)
            assertEquals(
                activity.getString(R.string.theme_title),
                activity.findViewById<TextView>(R.id.theme_title).text.toString(),
            )
            activity.findViewById<Button>(R.id.theme_tab_library).performClick()
            assertTrue(rebuiltContent.getChildAt(0) is ThemeLibraryPage)

            // 只观察外壳与连接边界：不得因导航/配置变化新增显示或安装命令。
            val started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).allStartedServices
            assertTrue(started.none { it.component?.className == ClusterRenderService::class.java.name })
            assertTrue(started.none { it.action == "theme-stop" })

            activity.findViewById<Button>(R.id.theme_back).performClick()
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
