package com.galaxy.auto.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * XML 组件用例：真实 inflate/binding/resources/measure，不复制布局算法，也不启动模拟器。
 *
 * 覆盖 UI-01（XML 行构造与长值测量）、UI-02（XML 开关回填/点击/禁用）、
 * UI-03（模态框结构与选项 adapter 复用）、日夜与字体放大。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalaxyXmlComponentsTest {
    /** 真实宿主 Activity；用于给组件提供 theme、resources 与窗口。 */
    private fun owner(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /**
     * XML 构造路径：三段式构造即 inflater 使用的入口。
     *
     * 带属性名的 AttributeSet 需要 Robolectric 编译期资源包（本机离线缓存未备），
     * 因此 `galaxyTitle/galaxyDescription/galaxyLeadingIcon` 的解析由 AutoService 真实布局
     * （`include_service_features.xml`）在 ServiceHomeBehaviorTest 中验证。
     */
    private fun xmlRow(owner: Activity) = GalaxySettingRow(owner, null, 0)

    /** 在固定宽度下测量一次，返回行高。 */
    private fun measure(container: LinearLayout, width: Int): Int {
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        container.layout(0, 0, width, container.measuredHeight)
        return container.measuredHeight
    }

    @Test
    fun xmlConstructorAndCompatConstructorShareOneSkeleton() {
        val owner = owner()
        val container = LinearLayout(owner).apply { orientation = LinearLayout.VERTICAL }
        owner.setContentView(container)
        val xmlRow = xmlRow(owner)
        val compatRow = GalaxySettingRow(owner, "音乐卡片", "说明")
        container.addView(xmlRow)
        container.addView(compatRow)
        assertEquals("音乐卡片", compatRow.titleView.text.toString())
        assertEquals("说明", compatRow.descriptionView.text.toString())
        assertEquals(View.VISIBLE, compatRow.descriptionView.visibility)
        assertEquals(View.GONE, xmlRow.descriptionView.visibility)
        listOf(xmlRow, compatRow).forEach { row ->
            assertNotNull(row.findViewById<View>(R.id.setting_row_text))
            assertNotNull(row.findViewById<View>(R.id.setting_row_icon))
            assertNotNull(row.findViewById<View>(R.id.setting_row_accessory))
        }
    }

    @Test
    fun longValueGrowsRowWithoutOverlappingAccessory() {
        val owner = owner()
        val container = LinearLayout(owner).apply { orientation = LinearLayout.VERTICAL }
        owner.setContentView(container)
        val row = GalaxySettingRow(owner, "音乐卡片", "")
        container.addView(row)
        row.setValue("原厂")
        val shortHeight = measure(container, 200)
        row.setValue("车道级导航地图与音乐卡片长文本示例")
        val longHeight = measure(container, 200)
        assertTrue("长值必须增高而不是截断", longHeight > shortHeight)
        assertTrue(longHeight >= GalaxyViews.px(owner, R.dimen.galaxy_ui_row_min_height_detail))
        assertEquals("长值文本必须完整保留", "车道级导航地图与音乐卡片长文本示例", row.valueView.text.toString())
    }

    @Test
    fun longValueStaysInsideTextAreaWhenAccessoryIsUsed() {
        val owner = owner()
        val container = LinearLayout(owner).apply { orientation = LinearLayout.VERTICAL }
        owner.setContentView(container)
        val row = GalaxySettingRow(owner, "背景", "长值不得与操作槽重叠")
        container.addView(row)
        GalaxyViews.attachSwitch(row, "背景", false, leading = false) {}
        row.setValue("车道级导航地图与音乐卡片长文本示例")
        measure(container, 260)
        val text = row.findViewById<View>(R.id.setting_row_text)
        val accessory = row.findViewById<View>(R.id.setting_row_accessory)
        assertEquals(View.VISIBLE, accessory.visibility)
        assertTrue("文字区必须在操作槽左侧结束", text.right <= accessory.left)
        assertEquals(row.textInset, text.left)
    }

    @Test
    fun xmlSwitchBackfillDoesNotCallbackAndWholeRowIsTheOnlyEntry() {
        val owner = owner()
        val events = mutableListOf<Boolean>()
        val row = GalaxySettingRow(owner, "音乐卡片", "")
        val control = GalaxyViews.attachSwitch(row, "音乐卡片", false) { events.add(it) }
        owner.setContentView(row)
        row.setSwitchChecked(true)
        assertTrue("外部回填不能触发业务回调", events.isEmpty())
        assertTrue(control.isChecked)
        assertEquals("音乐卡片", control.contentDescription)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.importantForAccessibility)
        row.performClick()
        assertEquals(listOf(false), events)
        row.isEnabled = false
        row.performClick()
        assertEquals(1, events.size)
        assertFalse(control.isEnabled)
    }

    @Test
    fun dialogInflatesHeaderBodyFooterAndRecyclesChoiceRows() {
        val owner = owner()
        val clicked = mutableListOf<Int>()
        val dialog =
            GalaxyDialogs
                .builder(owner)
                .setTitle("选择背景")
                .setSingleChoiceItems(arrayOf("保留原厂", "使用图片"), 0, { i -> i != 1 }) { _, index ->
                    clicked.add(index)
                }.setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("应用", null)
                .create()
        dialog.show()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(dialog.findViewById<TextView>(R.id.dialog_title))
        assertNotNull(dialog.findViewById<View>(R.id.dialog_body_scroll))
        assertNotNull(dialog.findViewById<View>(R.id.dialog_footer))
        assertNotNull(dialog.findViewById<View>(R.id.dialog_close))
        assertNotNull(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE))
        val list = dialog.listView
        assertNotNull(list)
        val adapter = list!!.adapter
        val first = adapter.getView(1, null, list) as CheckedTextView
        assertFalse("禁用项必须不可选", first.isEnabled)
        val reused = adapter.getView(0, first, list)
        assertSame("convertView 必须复用同一行实例", first, reused)
        list.performItemClick(null, 1, 1)
        assertTrue("禁用项点击不产生回调", clicked.isEmpty())
        list.performItemClick(null, 0, 0)
        assertEquals(listOf(0), clicked)
        dialog.dismiss()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun selectionCancelDiscardsAndApplyCommitsOnceOnRealDialog() {
        val owner = owner()
        val applied = mutableListOf<Int>()
        val dialog =
            GalaxySelection.select(owner, "背景", listOf("原厂", "图片"), 0, "应用") {
                applied.add(it)
            }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.cancel()
        assertTrue("取消不得提交", applied.isEmpty())
        val confirmed =
            GalaxySelection.select(owner, "背景", listOf("原厂", "图片"), 0, "应用") {
                applied.add(it)
            }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        confirmed.listView.performItemClick(null, 1, 1)
        val positive = confirmed.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        positive.performClick()
        positive.performClick()
        assertEquals(listOf(1), applied)
    }

    @Test
    fun closingDialogKeepsFocusOnOpeningRow() {
        val owner = owner()
        val container = LinearLayout(owner).apply { orientation = LinearLayout.VERTICAL }
        owner.setContentView(container)
        val row =
            GalaxySettingRow(owner, "背景", "").apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
        container.addView(row)
        assertTrue(row.requestFocus())
        val dialog =
            GalaxyDialogs
                .builder(owner)
                .setTitle("背景")
                .setPositiveButton("应用", null)
                .create()
        dialog.show()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        dialog.dismiss()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertSame("关闭后焦点回到原控件", row, owner.window.decorView.findFocus())
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun nightThemeResolvesRowColorsFromResources() {
        val owner = owner()
        val row = GalaxySettingRow(owner, "背景", "说明")
        owner.setContentView(row)
        assertEquals(owner.getColor(R.color.galaxy_ui_text), row.titleView.currentTextColor)
        assertEquals(owner.getColor(R.color.galaxy_ui_muted), row.descriptionView.currentTextColor)
        assertTrue(owner.getColor(R.color.galaxy_ui_text) != 0xFFE5000000.toInt())
    }

    @Test
    fun enlargedFontScaleKeepsRowHeightGrowing() {
        RuntimeEnvironment.setFontScale(1.6f)
        val owner = owner()
        val container = LinearLayout(owner).apply { orientation = LinearLayout.VERTICAL }
        owner.setContentView(container)
        val row = GalaxySettingRow(owner, "音乐卡片", "大字号下说明仍完整显示")
        container.addView(row)
        row.setValue("长值")
        val height = measure(container, 240)
        assertTrue("字体放大后行高必须增长", height >= GalaxyViews.px(owner, R.dimen.galaxy_ui_row_min_height_detail))
    }

    @Test
    fun segmentsUseSharedButtonsAndPublishSelection() {
        val owner = owner()
        val selected = mutableListOf<Int>()
        val segment = GalaxySelection.segments(owner, listOf("首页", "音乐"), 0) { selected.add(it) }
        owner.setContentView(segment)
        assertEquals(2, segment.childCount)
        segment.getChildAt(1).performClick()
        assertEquals(listOf(1), selected)
    }

    @Test
    fun roleLineHeightMatchesBetweenXmlRowAndCodeRole() {
        val owner = owner()
        val row = GalaxySettingRow(owner, "标题", "说明")
        val codeRole = GalaxyTypography.text(owner, "标题", GalaxyTypography.Role.LABEL)
        assertEquals("XML 设置行与代码角色必须同一行高", codeRole.lineHeight, row.titleView.lineHeight)
        assertEquals(
            owner.resources.getDimensionPixelSize(R.dimen.galaxy_ui_line_height_label),
            row.titleView.lineHeight,
        )
    }

    @Test
    fun existingRowRefreshesNightColorsOnSameInstance() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val owner = owner()
        val row = GalaxySettingRow(owner, "标题", "说明")
        val title = row.titleView
        val dayColor = title.currentTextColor
        RuntimeEnvironment.setQualifiers("+night")
        val nightColor = owner.getColor(R.color.galaxy_ui_text)
        assertNotEquals("夹具必须真实切换资源色", dayColor, nightColor)
        GalaxyStyle.refresh(row)
        assertSame("刷新不得重建文字视图", title, row.titleView)
        assertEquals("已有设置行必须按原刷新入口更新文字色", nightColor, title.currentTextColor)
    }

    @Test
    fun buttonKeepsRequestedRadiusPerInstanceAndState() {
        val owner = owner()
        val density = owner.resources.displayMetrics.density
        val twoDp = GalaxyViews.button(owner, "分类") {}
        GalaxyStyle.button(twoDp, radius = 2)
        val fourDp = GalaxyViews.button(owner, "操作") {}
        val primary = GalaxyViews.button(owner, "应用", primary = true) {}

        fun corner(button: Button) =
            ((button.background as RippleDrawable).getDrawable(0) as GradientDrawable).cornerRadius

        assertEquals("真实 2dp 调用方必须保留半径", 2f * density, corner(twoDp), 0.01f)
        assertEquals(4f * density, corner(fourDp), 0.01f)
        assertEquals("主按钮同样可调", 4f * density, corner(primary), 0.01f)
        assertEquals("实例之间互不污染", 4f * density, corner(fourDp), 0.01f)

        primary.isEnabled = false
        assertEquals("禁用后仍是同一可调形状", 4f * density, corner(primary), 0.01f)
        assertEquals("其他实例仍不受影响", 2f * density, corner(twoDp), 0.01f)
    }

    @Test
    fun openDialogRefreshesNightColorsWithoutRebuilding() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val owner = owner()
        val draft = EditText(owner).apply { setText("草稿内容") }
        val dialog =
            GalaxyDialogs
                .builder(owner)
                .setTitle("选择背景")
                .setMessage("主题和配置仍会保留")
                .setView(draft)
                .setSingleChoiceItems(arrayOf("保留原厂", "使用图片"), 1) { _, _ -> }
                .setPositiveButton("应用", null)
                .create()
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()

        val title = dialog.findViewById<TextView>(R.id.dialog_title)
        val message = dialog.findViewById<TextView>(R.id.dialog_message)
        val panel = title?.parent?.parent as View
        val dayPanel = (panel.background as GradientDrawable).color?.defaultColor

        RuntimeEnvironment.setQualifiers("+night")
        GalaxyDialogs.refresh(owner)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame("面板实例不得重建", panel, title?.parent?.parent)
        assertNotEquals(
            "面板背景必须重解析夜间资源",
            dayPanel,
            (panel.background as GradientDrawable).color?.defaultColor,
        )
        assertEquals(owner.getColor(R.color.galaxy_ui_text), title?.currentTextColor)
        assertEquals(owner.getColor(R.color.galaxy_ui_muted), message?.currentTextColor)
        assertEquals("草稿内容", draft.text.toString())
        assertEquals("已选项保持", 1, dialog.listView.checkedItemPosition)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
