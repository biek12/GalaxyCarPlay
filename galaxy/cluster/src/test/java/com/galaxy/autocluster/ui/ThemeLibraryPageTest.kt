package com.galaxy.autocluster.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HeaderViewListAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.autocluster.R
import com.galaxy.autocluster.databinding.ItemThemeLibraryRowBinding
import com.galaxy.autocluster.theme.ThemeLibrary
import com.galaxy.autocluster.theme.ThemeLibraryEntry
import com.galaxy.autocluster.theme.ThemeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import com.galaxy.auto.ui.R as UiR

/** 一次真实缩略图请求；记录目标 View、元数据与 active 标志，不复制生产渲染结果。 */
private data class ThumbnailCall(
    val target: ImageView,
    val id: String,
    val revision: Long,
    val active: Boolean,
)

/**
 * 只隔离缩略图后端与页面回调边界：页面、XML、Adapter 全部为生产实现。
 *
 * 记录函数不写 `ImageView.tag`，因此也能验证 Adapter 未占用留给 ThemeActions 票据的 tag。
 */
private class ThemeLibraryRecorder {
    val thumbnails = mutableListOf<ThumbnailCall>()
    val edits = mutableListOf<String>()
    val applied = mutableListOf<ThemeMetadata>()
    val menus = mutableListOf<ThemeLibraryEntry>()
    var createCount = 0
        private set
    var importCount = 0
        private set

    /** 构造真实主题库页面，生产入口在这里被替身函数替换，其余回调逐一记录用户动作。 */
    fun page(context: Context): ThemeLibraryPage =
        ThemeLibraryPage(
            context,
            { target, metadata, active ->
                thumbnails += ThumbnailCall(target, metadata.id, metadata.revision, active)
            },
            { edits += it },
            { createCount++ },
            { importCount++ },
            { applied += it },
            { menus += it },
        )
}

/**
 * C2a 标准用例 T1–T3：真实 XML 头部/两列卡片/Adapter 复用、四种正常状态与点击、
 * 日夜资源与不可读数量提示。组件库的三列预览由独立用例覆盖，本类不复制其模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w571dp-h1028dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeLibraryPageTest {
    /** 三个真实条目：奇数尾项必须留一张 INVISIBLE 卡片，而不是换成单行布局。 */
    private fun entries() =
        listOf(
            ThemeLibraryEntry(ThemeMetadata("t1", "主题一", 1), false),
            ThemeLibraryEntry(ThemeMetadata("t2", "主题二", 1), true),
            ThemeLibraryEntry(ThemeMetadata("t3", "主题三", 1), false),
        )

    /** ListView 有 header 时适配器被 HeaderViewListAdapter 包装；取回生产 Adapter 本身。 */
    private fun adapter(page: ThemeLibraryPage): ThemeLibraryAdapter =
        when (val current = page.adapter) {
            is ThemeLibraryAdapter -> current
            is HeaderViewListAdapter -> current.wrappedAdapter as ThemeLibraryAdapter
            else -> error("主题库适配器类型不符: ${current.javaClass.name}")
        }

    /** 真实测量与布局，让 header 与列表项进入视图树并可被 findViewById 找到。 */
    private fun measureLayout(
        view: View,
        width: Int = 1000,
        height: Int = 1800,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    /** 宿主离线渲染截图，不代表实车显示；仅用于人工核对文字/两列卡片/操作槽未裁切。 */
    private fun capture(
        root: View,
        name: String,
    ) {
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

    /** 由资源 configuration 构造 day/night 两个真实 Context，沿用 C1 已验证的包装方式。 */
    private fun themed(
        base: Context,
        nightMask: Int,
    ): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMask
        return base.createConfigurationContext(configuration)
    }

    /** T1：两列、行复用、532/1920 实测比例、独立当前主题预览、缩略图目标与点击对应当前项。 */
    @Test
    fun twoColumnsRebindRealViewsAndKeepRatioPreview() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val recorder = ThemeLibraryRecorder()
        val page = recorder.page(activity)
        activity.setContentView(page)
        val active = ThemeMetadata("active", "当前主题", 3)
        val data = entries()
        page.update(ThemeLibrary(data, emptyList()), active)
        measureLayout(page)
        try {
            assertEquals(active.name, page.findViewById<TextView>(R.id.theme_current_name).text.toString())
            val headerPreview = page.findViewById<ThemeRatioImageView>(R.id.theme_current_preview)
            assertTrue(headerPreview.measuredWidth > 0)

            val listAdapter = adapter(page)
            assertEquals(2, listAdapter.count)
            val row = listAdapter.getView(0, null, page) as LinearLayout
            assertEquals(2, row.childCount)
            val binding = row.tag as ItemThemeLibraryRowBinding
            assertEquals(
                "主题一",
                binding.themeCardLeft.themeCardName.text
                    .toString(),
            )
            assertEquals(
                "主题二",
                binding.themeCardRight.themeCardName.text
                    .toString(),
            )
            assertEquals(View.VISIBLE, binding.themeCardRight.root.visibility)
            assertNotSame(binding.themeCardLeft.themeCardPreview, headerPreview)
            // preview.tag 必须由 ThemeActions 的缩略图票据独占，Adapter 只能占用 row.root.tag。
            assertNull(binding.themeCardLeft.themeCardPreview.tag)

            val ratio = binding.themeCardLeft.themeCardPreview
            ratio.measure(
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            assertEquals(640 * 532 / 1920, ratio.measuredHeight)

            recorder.thumbnails.clear()
            val reused = listAdapter.getView(1, row, page)
            assertSame(row, reused)
            assertEquals(
                "主题三",
                binding.themeCardLeft.themeCardName.text
                    .toString(),
            )
            assertEquals(View.INVISIBLE, binding.themeCardRight.root.visibility)
            assertEquals(
                listOf("t3" to false),
                recorder.thumbnails.map { it.id to it.active },
            )
            assertEquals(1L, recorder.thumbnails.single().revision)

            recorder.thumbnails.clear()
            listAdapter.getView(0, reused, page)
            assertEquals(
                "主题一",
                binding.themeCardLeft.themeCardName.text
                    .toString(),
            )
            assertEquals(View.VISIBLE, binding.themeCardRight.root.visibility)
            assertEquals(
                listOf("t1" to false, "t2" to false),
                recorder.thumbnails.map { it.id to it.active },
            )

            binding.themeCardLeft.themeCardEdit.performClick()
            assertEquals("t1", recorder.edits.last())
            binding.themeCardLeft.themeCardUse.performClick()
            assertEquals(data[0].metadata, recorder.applied.last())
            binding.themeCardLeft.themeCardMore.performClick()
            assertEquals(data[0], recorder.menus.last())
            assertEquals(0, recorder.createCount)
            assertEquals(0, recorder.importCount)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** T2：四种正常状态顺序、已应用按钮、卡片/头部点击与 active 为空时不冒充旧图片。 */
    @Test
    fun realStatesAndClicksFollowActiveRevisionAndBuiltIn() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val recorder = ThemeLibraryRecorder()
        val page = recorder.page(activity)
        activity.setContentView(page)
        val active = ThemeMetadata("same", "当前主题", 1)
        val data =
            listOf(
                ThemeLibraryEntry(ThemeMetadata("same", "同代主题", 1), false),
                ThemeLibraryEntry(ThemeMetadata("same", "新代主题", 2), false),
                ThemeLibraryEntry(ThemeMetadata("builtin", "内置模板", 1), true),
                ThemeLibraryEntry(ThemeMetadata("custom", "自定义", 1), false),
            )
        page.update(ThemeLibrary(data, emptyList()), active)
        measureLayout(page)
        try {
            val listAdapter = adapter(page)
            val first = listAdapter.getView(0, null, page).tag as ItemThemeLibraryRowBinding
            val second = listAdapter.getView(1, null, page).tag as ItemThemeLibraryRowBinding
            assertEquals(
                activity.getString(R.string.theme_state_in_use),
                first.themeCardLeft.themeCardDetail.text
                    .toString(),
            )
            assertEquals(
                activity.getString(R.string.theme_card_applied),
                first.themeCardLeft.themeCardUse.text
                    .toString(),
            )
            assertEquals(
                activity.getString(R.string.theme_state_in_use_modified),
                first.themeCardRight.themeCardDetail.text
                    .toString(),
            )
            assertEquals(
                activity.getString(R.string.theme_card_apply),
                first.themeCardRight.themeCardUse.text
                    .toString(),
            )
            assertEquals(
                activity.getString(R.string.theme_state_builtin),
                second.themeCardLeft.themeCardDetail.text
                    .toString(),
            )
            assertEquals(
                activity.getString(R.string.theme_state_custom),
                second.themeCardRight.themeCardDetail.text
                    .toString(),
            )

            second.themeCardLeft.themeCardEdit.performClick()
            assertEquals("builtin", recorder.edits.last())
            second.themeCardLeft.themeCardUse.performClick()
            assertEquals(data[2].metadata, recorder.applied.last())
            second.themeCardLeft.themeCardMore.performClick()
            assertEquals(data[2], recorder.menus.last())

            page.findViewById<Button>(R.id.theme_edit_current).performClick()
            assertEquals("same", recorder.edits.last())
            page.findViewById<Button>(R.id.theme_create).performClick()
            page.findViewById<Button>(R.id.theme_import).performClick()
            assertEquals(1, recorder.createCount)
            assertEquals(1, recorder.importCount)

            val headerPreview = page.findViewById<ThemeRatioImageView>(R.id.theme_current_preview)
            // 模拟上一代已呈现的当前预览；update(active=null) 必须真正清空，不能留旧图冒充当前。
            headerPreview.setImageDrawable(ColorDrawable(Color.RED))
            val editsBefore = recorder.edits.size
            val activeRequests = recorder.thumbnails.count { it.active }
            page.update(ThemeLibrary(data, emptyList()), null)
            assertEquals(
                activity.getString(R.string.theme_current_empty),
                page.findViewById<TextView>(R.id.theme_current_name).text.toString(),
            )
            assertNull(headerPreview.drawable)
            assertEquals(activeRequests, recorder.thumbnails.count { it.active })
            page.findViewById<Button>(R.id.theme_edit_current).performClick()
            assertEquals(editsBefore, recorder.edits.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** T3：day/night 两个真实资源 Context、数量 Toast 只随 update 发出、宿主日夜截图。 */
    @Test
    fun dayAndNightResourcesAndUnreadableCountOnlyOnUpdate() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val day = themed(activity, Configuration.UI_MODE_NIGHT_NO)
        val night = themed(activity, Configuration.UI_MODE_NIGHT_YES)
        assertNotEquals(
            day.getColor(UiR.color.galaxy_ui_surface),
            night.getColor(UiR.color.galaxy_ui_surface),
        )
        val dayRecorder = ThemeLibraryRecorder()
        val nightRecorder = ThemeLibraryRecorder()
        val dayPage = dayRecorder.page(day)
        val nightPage = nightRecorder.page(night)
        val data = entries()
        try {
            ShadowToast.reset()
            dayPage.update(ThemeLibrary(data, listOf("bad-a", "bad-b", "bad-c")), ThemeMetadata("t1", "主题一", 1))
            assertEquals("3 个主题无法读取，文件已保留", ShadowToast.getTextOfLatestToast())
            ShadowToast.reset()
            val listAdapter = adapter(dayPage)
            repeat(3) { listAdapter.getView(0, null, dayPage) }
            assertNull(ShadowToast.getTextOfLatestToast())
            ShadowToast.reset()
            nightPage.update(ThemeLibrary(data, emptyList()), null)
            assertNull(ShadowToast.getTextOfLatestToast())

            // 单独截图页面时补上与真实外壳一致的公共日夜背景，否则夜间浅色文字会落在白色宿主底上不可读。
            val dayContainer =
                FrameLayout(day).apply {
                    setBackgroundColor(day.getColor(UiR.color.galaxy_ui_background))
                    addView(
                        dayPage,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            val nightContainer =
                FrameLayout(night).apply {
                    setBackgroundColor(night.getColor(UiR.color.galaxy_ui_background))
                    addView(
                        nightPage,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            activity.setContentView(dayContainer)
            measureLayout(dayContainer)
            capture(dayContainer, "theme-library-day")
            activity.setContentView(nightContainer)
            measureLayout(nightContainer)
            capture(nightContainer, "theme-library-night")

            val dayName = dayPage.findViewById<TextView>(R.id.theme_current_name)
            val nightName = nightPage.findViewById<TextView>(R.id.theme_current_name)
            assertEquals(day.getColor(UiR.color.galaxy_ui_muted), dayName.currentTextColor)
            assertEquals(night.getColor(UiR.color.galaxy_ui_muted), nightName.currentTextColor)
            assertNotEquals(dayName.currentTextColor, nightName.currentTextColor)
            assertTrue(dayName.measuredWidth > 0 && dayName.measuredHeight > 0)
            assertTrue(nightName.measuredWidth > 0 && nightName.measuredHeight > 0)
            assertTrue(dayPage.findViewById<Button>(R.id.theme_edit_current).measuredHeight > 0)
            assertTrue(nightPage.findViewById<Button>(R.id.theme_create).measuredHeight > 0)
            val dayRow = adapter(dayPage).getView(0, null, dayPage).tag as ItemThemeLibraryRowBinding
            assertTrue(dayRow.themeCardLeft.root.background != null)
            assertEquals(
                day.resources.getDimensionPixelSize(R.dimen.theme_card_menu_width),
                dayRow.themeCardLeft.themeCardMore.layoutParams.width,
            )
            assertEquals(
                day.getString(R.string.theme_card_more_description),
                dayRow.themeCardLeft.themeCardMore.contentDescription
                    .toString(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
