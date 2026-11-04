package com.galaxy.autorecord.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecordSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `默认值符合规格第7节`() {
        val s = RecordSettings()
        assertNull(s.storageVolumeUuid)
        assertEquals(60, s.segmentSeconds)
        assertEquals(RecordSettings.CODEC_H264, s.codecMime)
        assertEquals(RecordSettings.IMPL_HARDWARE, s.encoderImplementation)
        assertEquals(25, s.fps)
        assertEquals(RecordSettings.BITRATE_AUTO, s.bitrateMode)
        assertEquals(1, s.gopSeconds)
        assertEquals(false, s.audio)
        assertEquals(false, s.autoOnBoot)
        assertEquals(true, s.background)
        assertEquals(250, s.vehicleIntervalMs)
        assertEquals(5, s.brakeEnter)
        assertEquals(3, s.brakeExit)
        assertEquals(1f, s.playbackSpeed, 0f)
        assertEquals(RecordSettings.THEME_SYSTEM, s.themeMode)
    }

    @Test
    fun `自动配额取32GB与介质一半较小者`() {
        val auto = RecordSettings()
        val gb = 1024L * 1024 * 1024
        assertEquals(32 * gb, auto.effectiveQuotaBytes(128 * gb))
        assertEquals(20 * gb, auto.effectiveQuotaBytes(40 * gb))
        assertEquals(32 * gb, auto.effectiveQuotaBytes(0L))
        assertEquals(8 * gb, auto.copy(quotaBytes = 8 * gb).effectiveQuotaBytes(128 * gb))
    }

    @Test
    fun `事件规则版本随阈值变化`() {
        val base = RecordSettings()
        assertTrue(base.eventRulesVersion.contains("brake:5/3"))
        assertTrue(base.copy(brakeEnter = 8).eventRulesVersion != base.eventRulesVersion)
    }

    @Test
    fun `存储默认值可直接读回`() {
        val store = PrefsSettingsStore(context)
        store.save(RecordSettings())
        assertEquals(RecordSettings(), store.load())
    }
    @Test fun `省空间预设的15fps在重建和重启读取后不会被重置成25fps`() {
        val store=PrefsSettingsStore(context)
        val preset=RecordSettings(requestedHeight=720,fps=15)
        store.save(preset)
        assertEquals(preset,PrefsSettingsStore(context).load())
    }

    @Test
    fun `非法值回落默认而不是当正常`() {
        context.getSharedPreferences("record", Context.MODE_PRIVATE).edit()
            .putString(PrefsSettingsStore.KEY_CODEC, "video/av01")
            .putInt(PrefsSettingsStore.KEY_SEGMENT, 7)
            .putFloat(PrefsSettingsStore.KEY_PLAYBACK_SPEED, 3.5f)
            .commit()
        val loaded = PrefsSettingsStore(context).load()
        assertEquals(RecordSettings.CODEC_H264, loaded.codecMime)
        assertEquals(60, loaded.segmentSeconds)
        assertEquals(1f, loaded.playbackSpeed, 0f)
    }
}

class BitratePlanTest {
    @Test
    fun `1080p25 自动码率与四路合计`() {
        val one = BitratePlan.autoBps(1920, 1080, 25)
        assertTrue("单路码率应在 4Mbps 量级，实际 $one", one in 3_500_000..5_000_000)
        val total = BitratePlan.fourTrackTotal(one)
        assertTrue("四路合计应低于 20Mbps，实际 $total", total < 20_000_000)
    }

    @Test
    fun `档位倍数与对齐`() {
        val auto = BitratePlan.autoBps(1280, 720, 25)
        assertEquals(auto / 2, BitratePlan.forMode(RecordSettings.BITRATE_LOW, 1280, 720, 25))
        assertEquals(1072, CodecCapabilities.alignDown(1081, 16))
        assertEquals(1072, CodecCapabilities.alignDown(1080, 16))
        assertEquals(1072, CodecCapabilities.alignDown(1072, 16))
    }
}
