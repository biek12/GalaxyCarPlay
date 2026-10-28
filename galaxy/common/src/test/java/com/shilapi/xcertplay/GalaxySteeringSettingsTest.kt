package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.steering.GalaxySteeringSessions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 方控设置入口回归：合并后仍能看到学习/确认/保存/删除/开关，且实物键观察是同一页的下半部分。
 * GalaxySettingsActivity 只声明一个方控页字段（类型为 GalaxySteeringSettings），不再单独挂调试页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class GalaxySteeringSettingsTest {
    @Before fun seedConfirmedMapping() {
        // 已确认映射走与产品相同的存储在文件键；同时关闭监听，页面不发起Binder订阅。
        RuntimeEnvironment.getApplication().getSharedPreferences("galaxy_steering_learning", 0).edit()
            .putBoolean("enabled", false)
            .putString("configuration", """
                {"version":1,"clickWindowMs":400,"longPressMs":800,
                 "bindings":[{"keyCode":47,"gesture":"CLICK","action":"NEXT"}]}
            """.trimIndent())
            .commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit()
            .putBoolean("galaxy_steering_enabled", false).commit()
        // 目标表与配置在进程内单例里；重置以便本页从上面的存储重新读取。
        ReflectionHelpers.setField(GalaxySteeringSessions, "runtime", null)
    }

    @After fun clearPreferences() {
        ReflectionHelpers.setField(GalaxySteeringSessions, "runtime", null)
        RuntimeEnvironment.getApplication().getSharedPreferences("galaxy_steering_learning", 0).edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    }

    private fun texts(root: View): List<String> = buildList {
        if (root is TextView) root.text?.toString()?.takeIf { it.isNotEmpty() }?.let(::add)
        if (root is ViewGroup) (0 until root.childCount).forEach { addAll(texts(root.getChildAt(it))) }
    }

    private fun switches(root: View): List<Switch> = buildList {
        if (root is Switch) add(root)
        if (root is ViewGroup) (0 until root.childCount).forEach { addAll(switches(root.getChildAt(it))) }
    }

    @Test fun mergedEntryShowsLearningConfirmSaveRemoveSwitchAndObservation() {
        val activity = activity()
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val page = GalaxySteeringSettings(activity, parent, {})
        val values = texts(parent)
        fun has(text: String) = values.any { it.contains(text) }

        assertTrue("缺少学习分区标题", has("方控学习与映射"))
        assertTrue("缺少监听开关", has("监听方控"))
        assertTrue("缺少服务状态", has("服务状态"))
        assertTrue("缺少开始学习", has("开始学习"))
        assertTrue("缺少确认映射", has("确认映射"))
        assertTrue("缺少映射分区", has("已确认的映射"))
        assertTrue("已有映射未显示", has("按键 47 · 单击 → 下一曲"))
        assertTrue("缺少删除映射入口", has("点击后确认删除此映射"))
        assertTrue("缺少实物键观察分区", has("实物键观察与标注"))
        assertEquals("监听开关只应有一个", 1, switches(parent).size)
        // 关闭监听时开关显示为关，与既有产品键一致。
        assertFalse(switches(parent).single().isChecked)

        page.close()
        page.close() // 关闭幂等：重复close不抛异常
        activity.finish()
    }

    @Test fun settingsActivityKeepsExactlyOneSteeringEntry() {
        val fields = GalaxySettingsActivity::class.java.declaredFields
        assertTrue("调试方控页不应再被设置页单独持有",
            fields.none { it.type == L7SteeringDebugPage::class.java })
        assertEquals("方控入口应只有一个挂载字段",
            1, fields.count { it.type == GalaxySteeringSettings::class.java })
    }
}