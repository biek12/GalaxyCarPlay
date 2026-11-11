package com.galaxy.auto.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.CompoundButton
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class GalaxyControlsTest {
    @Test fun externalSwitchUpdateDoesNotFireUserActionAndDisabledRowCannotToggle(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup().get()
        val events=mutableListOf<Boolean>()
        val row=GalaxyViews.switchRow(owner,"音乐卡片","",false){events.add(it)}
        owner.setContentView(row)
        row.setSwitchChecked(true);assertTrue(events.isEmpty())
        row.performClick();assertEquals(listOf(false),events)
        row.isEnabled=false;row.performClick();assertEquals(1,events.size)
    }
    @Test fun cancelSelectionDiscardsPreviewAndApplyCommitsOnce(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup().get()
        val applied=mutableListOf<Int>()
        val cancelled=GalaxySelection.select(owner,"背景",listOf("原厂","图片"),0,"应用"){applied.add(it)}
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        cancelled.listView.performItemClick(null,1,1);cancelled.cancel();assertTrue(applied.isEmpty())
        val selected=GalaxySelection.select(owner,"背景",listOf("原厂","图片"),0,"应用"){applied.add(it)}
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(selected.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        selected.listView.performItemClick(null,1,1)
        selected.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        selected.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(listOf(1),applied)
    }
}
