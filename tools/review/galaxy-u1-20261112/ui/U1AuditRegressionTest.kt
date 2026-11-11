package com.galaxy.auto.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Lead审计反例：直接驱动提交内公共控件，不复制被测算法。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class U1AuditRegressionTest {
    @Test
    fun xmlRowMustKeepOriginalRoleLineHeight() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = GalaxySettingRow(owner, "标题", "说明")
        val originalRole = GalaxyTypography.text(owner, "标题", GalaxyTypography.Role.LABEL)
        assertEquals("XML设置行应保持原LABEL的28sp行高", originalRole.lineHeight, row.titleView.lineHeight)
    }

    @Test
    fun existingRowMustRefreshNightColors() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val owner = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = GalaxySettingRow(owner, "标题", "说明")
        val before = row.titleView.currentTextColor
        RuntimeEnvironment.setQualifiers("+night")
        val expected = owner.getColor(R.color.galaxy_ui_text)
        assertNotEquals("夹具必须真实切换资源色", before, expected)
        GalaxyStyle.refresh(row)
        assertEquals("已有设置行必须按现有刷新入口更新文字色", expected, row.titleView.currentTextColor)
    }

    @Test
    fun existingRadiusTwoCallerMustKeepTwoDp() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = GalaxyViews.button(owner, "分类") {}
        GalaxyStyle.button(button, radius = 2)
        val shape = (button.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertEquals("保留真实分类栏传入的2dp圆角", 2f * owner.resources.displayMetrics.density, shape.cornerRadius, 0.01f)
    }
}
