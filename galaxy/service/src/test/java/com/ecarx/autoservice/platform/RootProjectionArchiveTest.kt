package com.ecarx.autoservice.platform

import android.content.Context
import com.ecarx.autoservice.settings.ServiceFeature
import com.ecarx.autoservice.settings.ServiceFeatures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * R0-02：Root 边界的旧投屏帧拒绝与旧开关恒不可用。
 *
 * 字节流用真实 [RootWire.read]/[RootWire.write] 构造与解析，只把「关闭连接」
 * 换成记录型边界替身；生产 [RootServiceMain] 分发循环与测试调用同一个
 * [RootProjectionArchive.rejectIfArchived] 方法，不另写 reject 模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class RootProjectionArchiveTest {
    /** 只记录连接关闭次数，不替换协议解析。 */
    private class RecordingConnection {
        var closed = 0
            private set

        fun close() {
            closed++
        }
    }

    /** op4 旧部署请求：按既有 RootWire 长度帧写入。 */
    private fun deployFrame(): ByteArray {
        val output = ByteArrayOutputStream()
        RootWire.write(DataOutputStream(output), JSONObject().put("ownerKey", "legacy-owner-key"))
        return output.toByteArray()
    }

    @Test
    fun archivedDeployFrameConsumesRequestRepliesArchiveStageAndClosesConnection() {
        val input = DataInputStream(ByteArrayInputStream(deployFrame()))
        val output = ByteArrayOutputStream()
        val connection = RecordingConnection()

        val handled =
            RootProjectionArchive.rejectIfArchived(
                4,
                input,
                DataOutputStream(output),
            ) { connection.close() }

        assertTrue("op4 必须由归档拒绝器处理", handled)
        assertEquals(1, connection.closed)
        // 消费帧后输入已到末尾，旧部署帧不再被原分发二次读取。
        assertEquals(-1, input.read())
        val reply = RootWire.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertEquals("legacy_projection_archived", reply.getString("stage"))
        assertEquals("legacy_projection_archived", reply.getString("error"))
    }

    @Test
    fun archivedBridgeFrameWritesMinusOneWithoutStartingProcess() {
        val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        val output = ByteArrayOutputStream()
        val connection = RecordingConnection()

        val handled =
            RootProjectionArchive.rejectIfArchived(
                6,
                input,
                DataOutputStream(output),
            ) { connection.close() }

        assertTrue("op6 必须由归档拒绝器处理", handled)
        assertEquals(1, connection.closed)
        // 旧协议只回一个 int -1，没有长度帧、没有 native 路径读取，也没有子进程。
        val bytes = output.toByteArray()
        assertEquals(4, bytes.size)
        assertEquals(-1, DataInputStream(ByteArrayInputStream(bytes)).readInt())
    }

    @Test
    fun liveOperationsRemainUnhandledForOriginalDispatch() {
        for (operation in listOf(0, 1, 2, 3, 5, 7, 8, 9, 10, 11, 12)) {
            val connection = RecordingConnection()
            val input = DataInputStream(ByteArrayInputStream(ByteArray(0)))
            val output = ByteArrayOutputStream()
            val handled =
                RootProjectionArchive.rejectIfArchived(
                    operation,
                    input,
                    DataOutputStream(output),
                ) { connection.close() }
            assertFalse("op$operation 应交回原分发", handled)
            assertEquals("op$operation 不得关闭连接", 0, connection.closed)
            assertEquals("op$operation 不得写响应", 0, output.size())
        }
    }

    @Test
    fun legacyProjectionFeatureNeverReopensWhileOtherFeaturesKeepWorking() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("platform", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // 旧安装默认值与显式 set(true) 都不能让 projection 对外可用。
        val initial = ServiceFeatures.read(context)
        assertFalse(initial.enabled(ServiceFeature.PROJECTION))
        assertFalse(initial.bundle().getBoolean("projectionEnabled"))
        ServiceFeatures.set(context, ServiceFeature.PROJECTION, true)
        assertTrue("兼容键仍可写，以保留历史记录", prefs.getBoolean("projectionEnabled", false))
        assertFalse("set(true) 不得重新打开旧能力", ServiceFeatures.read(context).enabled(ServiceFeature.PROJECTION))
        assertFalse("快照兼容键恒为 false", ServiceFeatures.read(context).bundle().getBoolean("projectionEnabled"))

        // vehicle/adb/root 三个现行开关照常读写。
        assertTrue(ServiceFeatures.read(context).enabled(ServiceFeature.VEHICLE))
        ServiceFeatures.set(context, ServiceFeature.VEHICLE, false)
        assertFalse(ServiceFeatures.read(context).enabled(ServiceFeature.VEHICLE))
        assertTrue(ServiceFeatures.read(context).enabled(ServiceFeature.ADB))
        assertTrue(ServiceFeatures.read(context).enabled(ServiceFeature.ROOT))
        ServiceFeatures.set(context, ServiceFeature.ROOT, false)
        assertFalse(ServiceFeatures.read(context).enabled(ServiceFeature.ROOT))
        assertTrue("关闭 Root 不影响 ADB", ServiceFeatures.read(context).enabled(ServiceFeature.ADB))
    }
}
