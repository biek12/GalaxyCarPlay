package com.ecarx.autoservice.ipc

import android.os.Bundle
import android.os.SystemClock
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.MapStreamSize
import com.galaxy.auto.contract.ProjectionPolicy

/**
 * 只组装只读快照，不启动业务、不登录、不做 Shell。
 *
 * sequence 只在这里增加。每个字段都在对应 put 时向 [Input] 取值，不提前缓存一整包。
 */
internal class ServiceSnapshotComposer(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var sequence = 0L

    /**
     * 快照所需的只读值。
     *
     * 实现只在 [build] 的对应 put 处被调用，不能提前把结果收成另一份状态。
     */
    class Input(
        val vehicleDiscovery: () -> String,
        val vehiclePropertyCount: () -> Int,
        val vehicleSampledCount: () -> Int,
        val vehicleValidCount: () -> Int,
        val sentinel: () -> Bundle?,
        val rootReady: () -> Boolean,
        val automatic: () -> Boolean,
        val vehicleWebEnabled: () -> Boolean,
        val features: () -> Bundle,
        val clusterElementsRequested: () -> Boolean,
        val clusterElementRevision: () -> Long,
        val clusterMusicRequested: () -> Boolean,
        val clusterLyricsRequested: () -> Boolean,
        val clusterElements: () -> Bundle?,
        val foundation: () -> String,
        val status: () -> String,
        val media: () -> Bundle,
        val navigation: () -> Bundle,
        val visualMedia: () -> Bundle?,
        val vehicleKeys: () -> Array<String>,
        val vehicleScanning: () -> Boolean,
        val inputEpoch: () -> String,
        val menuSequence: () -> Long,
        val menuReceivedAt: () -> Long,
        val remoteState: () -> String,
        val vehicleMode: () -> String,
        val model: () -> String,
        val artworkReady: () -> Boolean,
        val artworkVersion: () -> Long,
    )

    /**
     * 按原字段顺序生成新 Bundle，并把 sequence 加一。
     *
     * qnxReady 与 projectionRequested 恒为 false。不写入车辆值。
     */
    fun build(input: Input): Bundle =
        Bundle().apply {
            putString("vehicleDiscovery", input.vehicleDiscovery())
            putInt("vehiclePropertyCount", input.vehiclePropertyCount())
            putInt("vehicleSampledCount", input.vehicleSampledCount())
            putInt("vehicleValidCount", input.vehicleValidCount())
            putBundle("sentinel", input.sentinel())
            putInt("sentinelVersion", 1)
            putInt("version", AutoContract.VERSION)
            putLong("sequence", ++sequence)
            putLong("receivedAt", clock())
            putBoolean("rootReady", input.rootReady())
            putBoolean("qnxReady", false)
            putBoolean("automatic", input.automatic())
            putBoolean("vehicleWebEnabled", input.vehicleWebEnabled())
            putBundle("features", input.features())
            putBoolean("projectionRequested", false)
            putBoolean("clusterElementsRequested", input.clusterElementsRequested())
            putLong("clusterElementRevision", input.clusterElementRevision())
            putBoolean("clusterMusicRequested", input.clusterMusicRequested())
            putBoolean("clusterLyricsRequested", input.clusterLyricsRequested())
            input.clusterElements()?.let { putBundle("clusterElements", it) }
            putString("foundation", input.foundation())
            putString("status", input.status())
            val media = input.media()
            putBundle("media", Bundle(media))
            putBundle("navigation", Bundle(input.navigation()))
            val visual = input.visualMedia()
            if (visual?.getString("track") != null && visual.getString("track") == media.getString("track")) {
                putBundle(
                    "visualMedia",
                    Bundle(media).apply {
                        putStringArrayList("lyrics", visual.getStringArrayList("lyrics"))
                        if (visual.containsKey("lyricTimes")) {
                            putLongArray("lyricTimes", visual.getLongArray("lyricTimes"))
                        } else {
                            remove("lyricTimes")
                        }
                    },
                )
            }
            putStringArray("vehicleKeys", input.vehicleKeys())
            putBoolean("vehicleScanning", input.vehicleScanning())
            putInt("minVehicleFeedIntervalMs", AutoContract.MIN_FEED_INTERVAL_MS)
            putString("inputEpoch", input.inputEpoch())
            putLong("menuSequence", input.menuSequence())
            putLong("menuReceivedAt", input.menuReceivedAt())
            putBundle("projectionPolicy", ProjectionPolicy().bundle())
            putString("remoteState", input.remoteState())
            putBundle("mapStreamSize", MapStreamSize.DEFAULT.bundle())
            putString("vehicleMode", input.vehicleMode())
            putString("model", input.model())
            putBoolean("artworkReady", input.artworkReady())
            putLong("artworkVersion", input.artworkVersion())
            putString("artworkUri", "content://com.ecarx.autoservice.artwork/current")
        }
}
