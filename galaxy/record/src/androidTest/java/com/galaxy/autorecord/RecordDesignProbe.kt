package com.galaxy.autorecord

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.ui.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅独立测试 APK：四个有区别的合成视频+明确标注的测试车辆值；截图取真实系统合成结果。 */
internal class RecordDesignProbe(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private val at = 1792567110000L
    private val folder by lazy { File(context.cacheDir, "design-probe").apply { mkdirs() } }
    private val values = mapOf(
        "displaySpeed" to "0", "gear" to "P", "rpm" to "1301", "steeringAngle" to "89",
        "fuel" to "17", "soc" to "50", "fuelRange" to "161", "evRange" to "80", "odometer" to "64954",
        "insideTemperature" to "31", "outsideTemperature" to "19", "accelerator" to "12", "brake" to "0",
        "leftTurn" to "0", "rightTurn" to "1", "adasLateral" to "active", "cruiseState" to "active",
        "lowBeam" to "1", "highBeam" to "0", "autoHighBeamEnabled" to "on", "positionLights" to "1",
        "rearFog" to "0", "autoHold" to "active", "parkingBrake" to "0")

    fun run(report: Bundle) {
        check(BuildConfig.EMULATOR_CAMERA)
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        context.getSharedPreferences("record.ui", Context.MODE_PRIVATE).edit().putBoolean("record.ui.onboarded", true).commit()
        seed()
        val activity = runner.startActivitySync(Intent(context, RecordActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RecordActivity
        try {
            runner.runOnMainSync {
                val fixtureFeed = object : VehicleFeed {
                    override fun snapshot() = VehicleSnapshot(VehicleKey.entries.associateWith { key ->
                        values[key.key]?.let { VehicleValue(key, it, VehicleValueStatus.LIVE, SystemClock.elapsedRealtime()) }
                            ?: VehicleValue(key, "—", VehicleValueStatus.UNKNOWN)
                    }, connected = true, reason = "虚拟机测试数据")
                    override fun setInterval(intervalMs: Int) = Unit
                    override fun release() = Unit
                }
                RecordActivity::class.java.getDeclaredField("vehicleFeed").apply { isAccessible = true }.set(activity, fixtureFeed)
                val decor = activity.window.decorView as FrameLayout
                decor.addView(TextView(activity).apply {
                    text = "虚拟机测试 · 合成视频 / 测试车辆值"
                    textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(0xDD805000.toInt())
                    setPadding(10, 3, 10, 3)
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            Thread.sleep(1600)
            capture("01-live")
            page(activity, "02-library") { activity.openMainPage(MainPage.LIBRARY) }
            page(activity, "03-settings") { activity.openMainPage(MainPage.SETTINGS) }
            listOf(SubPage.STORAGE, SubPage.CODEC, SubPage.RECORDING, SubPage.VEHICLE,
                SubPage.PLAYBACK, SubPage.DIAGNOSE, SubPage.ABOUT).forEachIndexed { index, sub ->
                page(activity, "${index + 4}-${sub.name.lowercase()}") {
                    activity.backToSettings(); activity.openSubPage(sub)
                }
            }
            runner.runOnMainSync { activity.openMainPage(MainPage.LIBRARY); activity.refreshLibrary() }
            Thread.sleep(1200)
            check(activity.library.segments.size >= 6)
            page(activity, "11-playback-all") { activity.openPlayback(activity.library.segments.first().id) }
            click(activity) { it.contentDescription?.toString() == "播放" }
            Thread.sleep(1600)
            capture("12-playback-running")
            click(activity) { it.contentDescription?.toString() == "暂停" }
            click(activity) { it is TextView && it.text.toString() == "前" }
            Thread.sleep(900)
            capture("13-playback-front")
            click(activity) { it is TextView && it.text.toString() == "全部" }
            click(activity) { it is TextView && it.text.toString() == "选中事件" }
            capture("14-selected-event")
            click(activity) { it is TextView && it.text.toString() == "全部事件" }
            capture("15-all-events")
            report.putString("interaction", "PASS: play/pause, front/all camera, selected/all event controls")
            report.putString("design", "Rendered live/library/settings/7 settings pages/quad playback; actual compositor screenshots")
            report.putString("fixtures", "SYNTHETIC: four distinct generated AVC files, 60s, 5fps, explicit test telemetry/events")
            report.putString("screenshots", folder.absolutePath)
            // 留在实际回看页面，供主任务点按播放/镜头/事件并追加系统截图。
        } catch (error: Throwable) {
            runner.runOnMainSync { activity.finish() }
            throw error
        }
    }

    private fun click(activity: RecordActivity, match: (android.view.View) -> Boolean) {
        fun nodes(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        runner.runOnMainSync {
            val view = nodes(activity.window.decorView).firstOrNull { it.isShown && it.isEnabled && match(it) }
            checkNotNull(view) { "control_missing" }
            check(view.performClick()) { "control_not_clickable" }
        }
        runner.waitForIdleSync()
        Thread.sleep(300)
    }

    private fun page(activity: RecordActivity, name: String, action: () -> Unit) {
        runner.runOnMainSync(action)
        runner.waitForIdleSync()
        Thread.sleep(if (name == "02-library") 6500 else 900)
        capture(name)
    }

    private fun capture(name: String) {
        val shot = checkNotNull(runner.uiAutomation.takeScreenshot()) { "compositor_screenshot_unavailable" }
        try { File(folder, "$name.png").outputStream().use { check(shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { shot.recycle() }
    }

    private fun seed() {
        val base = checkNotNull(AndroidStorageVolumes(context).resolve(null)?.appDir)
        val sources = SegmentLayout.DIRECTIONS.mapIndexed { index, direction ->
            File(folder, "$direction.mp4").also { if (!it.isFile || it.length() == 0L) encode(it, index) }
        }
        repeat(6) { index ->
            val time = at - index * 60_000
            val segment = SegmentLayout.segmentDir(base, time, index).apply { mkdirs() }
            val channels = SegmentLayout.DIRECTIONS.mapIndexed { i, direction ->
                sources[i].copyTo(SegmentLayout.videoFile(segment, direction), true)
                ChannelRecord(direction, "$direction.mp4", "video/avc", "OMX.google.h264.encoder", "software",
                    320, 200, 5, 160_000, 1, 300, 0, 59_800_000, SegmentManifest.CHANNEL_COMPLETE)
            }
            val events = JSONArray()
            EventKind.entries.forEachIndexed { k, kind ->
                listOf(7L, 20L, 42L).forEach { start ->
                    events.put(JSONObject().put("type", kind.key).put("startMs", start * 1000 + k * 300)
                        .put("endMs", start * 1000 + k * 300 + if (k == 3 || k == 5) 0 else 6500))
                }
            }
            File(segment, SegmentLayout.FILE_EVENTS).writeText(JSONObject().put("ruleVersion", "synthetic-ui").put("events", events).toString())
            File(segment, SegmentLayout.FILE_VEHICLES).bufferedWriter().use { out ->
                repeat(241) { sample ->
                    val v = values.toMutableMap().apply { put("rpm", (1301 + sample % 25).toString()) }
                    out.appendLine(JSONObject().put("t", sample * 250).put("v", JSONObject(v)).toString())
                }
            }
            val manifest = SegmentManifest(segmentId = segment.name, startedAtWallMillis = time,
                startedAtMonotonicNanos = 0, durationMillis = 60_000, state = SegmentManifest.STATE_COMPLETE,
                storageVolumeUuid = null, channels = channels,
                vehicle = VehicleRecord(250, values.keys.toList(), 241, SegmentLayout.FILE_VEHICLES, "elapsedRealtimeNanos"),
                events = EventsRecord("synthetic-ui", events.length(), SegmentLayout.FILE_EVENTS), appVersionName = "UI-TEST")
            File(segment, SegmentLayout.FILE_MANIFEST).writeText(manifest.toJson().toString())
        }
    }

    private fun encode(file: File, direction: Int) {
        val codec = MediaCodec.createByCodecName("OMX.google.h264.encoder")
        val format = MediaFormat.createVideoFormat("video/avc", 320, 200).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000); setInteger(MediaFormat.KEY_FRAME_RATE, 5)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxing = false; var started = false; var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); started = true
            var sent = 0; var eos = false; var done = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!done && SystemClock.elapsedRealtime() < deadline) {
                if (!eos) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        if (sent == 300) { codec.queueInputBuffer(input, 0, 0, 60_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else {
                            val image = checkNotNull(codec.getInputImage(input))
                            image.planes.forEachIndexed { planeIndex, plane ->
                                val w = if (planeIndex == 0) 320 else 160; val h = if (planeIndex == 0) 200 else 100
                                val buffer = plane.buffer; val offset = buffer.position()
                                for (y in 0 until h) for (x in 0 until w) {
                                    val value = if (planeIndex == 0) 35 + (x / 32 + y / 25 + sent / 5) % 5 * 35
                                        else if (planeIndex == 1) 75 + direction * 28 else 180 - direction * 27
                                    buffer.put(offset + y * plane.rowStride + x * plane.pixelStride, value.toByte())
                                }
                            }
                            codec.queueInputBuffer(input, 0, 320 * 200 * 3 / 2, sent * 200_000L, 0); sent++
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(output)); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
            check(done) { "synthetic_video_timeout" }
        } finally {
            if (started) runCatching { codec.stop() }; codec.release()
            try { if (muxing) muxer.stop() } finally { muxer.release() }
        }
    }
}
