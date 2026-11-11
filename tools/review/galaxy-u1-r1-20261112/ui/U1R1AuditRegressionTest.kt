package com.galaxy.auto.ui

import android.app.Activity
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R1审计：只检查原有按钮工厂的实际View属性，不复制样式实现。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class U1R1AuditRegressionTest {
    @Test
    fun normalAndCompactButtonsMustKeepOriginalFontPadding() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = GalaxyViews.button(owner, "操作") {}
        assertFalse("原公共按钮includeFontPadding=false不能在资源迁移后丢失", button.includeFontPadding)
        val legacyButton = Button(owner)
        GalaxyStyle.button(legacyButton, compact = true)
        assertFalse("既有普通Button调用方同样保持无字体附加留白", legacyButton.includeFontPadding)
    }

    @Test
    fun textButtonMustKeepOriginalTwentySixSpLineHeight() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = GalaxyViews.textButton(owner, "返回") {}
        val reference = Button(owner)
        GalaxyTypography.apply(reference, 18, 26, false)
        assertEquals("textButton原18sp/26sp文字合同必须保留", reference.lineHeight, button.lineHeight)
        assertFalse("文字按钮原includeFontPadding=false必须保留", button.includeFontPadding)
    }
}
