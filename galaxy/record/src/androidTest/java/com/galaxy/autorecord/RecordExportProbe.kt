package com.galaxy.autorecord

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import com.galaxy.autorecord.core.ChannelRecord
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import com.galaxy.autorecord.export.ExportTask
import java.io.File

/** 仅隔离模拟器：人工生成短片复用到四个测试方向，不声称真实四路采集验收。 */
internal object RecordExportProbe {
    fun run(context: Context, source: File, report: Bundle) {
        val root = File(context.cacheDir, "export-probe").apply { mkdirs() }
        try {
            val segment = File(root, "synthetic-segment").apply { mkdirs() }
            val channels = SegmentLayout.DIRECTIONS.map { direction ->
                source.copyTo(SegmentLayout.videoFile(segment, direction), overwrite = true)
                ChannelRecord(direction, "$direction.mp4", "video/avc", "synthetic-test", "unknown",
                    1280, 720, 20, 2_000_000, 1, 8, 0, 350_000, SegmentManifest.CHANNEL_COMPLETE)
            }
            val manifest = SegmentManifest(segmentId = segment.name, startedAtWallMillis = 1,
                startedAtMonotonicNanos = 0, durationMillis = 350, state = SegmentManifest.STATE_COMPLETE,
                storageVolumeUuid = null, channels = channels, vehicle = null, events = null, appVersionName = "test")
            File(segment, SegmentLayout.FILE_MANIFEST).writeText(manifest.toJson().toString())
            listOf(false to false, true to false, false to true).forEach { (grid, overlay) ->
                val output = File(root, "result-$grid-$overlay.mp4")
                val result = ExportTask(context, ExportTask.ExportRequest(
                    listOf(segment, segment), grid, "front", overlay, Uri.fromFile(output),
                )).execute()
                check(result.success) { result.message }
                val extractor = MediaExtractor()
                var count = 0
                try {
                    extractor.setDataSource(output.path)
                    check(extractor.trackCount == 1)
                    extractor.selectTrack(0)
                    var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        check(extractor.sampleTime > previous) { "non_monotonic_export" }
                        previous = extractor.sampleTime
                        count++
                        extractor.advance()
                    }
                    check(count >= 16) { "export_dropped_frames" }
                } finally {
                    extractor.release()
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.path)
                    val bitmap = checkNotNull(retriever.getFrameAtTime(400_000)) { "export_decode_failed" }
                    bitmap.recycle()
                } finally {
                    retriever.release()
                }
                check(result.exportJson != null)
                report.putString("export-$grid-$overlay", "PASS: synthetic two-segment MP4, $count samples, ordered PTS, decoded")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
