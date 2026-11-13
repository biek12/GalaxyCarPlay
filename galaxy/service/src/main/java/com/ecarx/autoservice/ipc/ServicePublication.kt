package com.ecarx.autoservice.ipc

import android.os.Bundle
import com.ecarx.autoservice.media.ServiceMediaSession
import com.ecarx.autoservice.oem.ClusterElementsSession
import com.ecarx.autoservice.remote.ServiceRemoteSession
import com.ecarx.autoservice.sentinel.SentinelCoordinator
import com.ecarx.autoservice.vehicle.ServiceVehicleSession

/**
 * 按固定顺序串起媒体源、哨兵、远程、快照、Hook、Kanzi 和已有快照通道。
 *
 * 不另存一份业务状态，也不把启停放进组装器。
 */
internal class ServicePublication(
    private val media: ServiceMediaSession,
    private val elements: ClusterElementsSession,
    private val remote: ServiceRemoteSession,
    private val vehicle: ServiceVehicleSession,
    private val clients: ClientRegistry,
    private val snapshots: ServiceSnapshots,
    private val composer: ServiceSnapshotComposer,
    private val sentinel: () -> SentinelCoordinator?,
    private val closed: () -> Boolean,
    private val paused: () -> Boolean,
    private val rootReady: () -> Boolean,
    private val featuresRoot: () -> Boolean,
    private val featuresVehicle: () -> Boolean,
    private val automatic: () -> Boolean,
    private val vehicleWebEnabled: () -> Boolean,
    private val features: () -> Bundle,
    private val foundation: () -> String,
    private val status: () -> String,
    private val vehicleMode: () -> String,
    private val onNative: () -> Unit = {},
    private val onSentinel: () -> Unit = {},
    private val onRemote: () -> Unit = {},
    private val onBuild: () -> Unit = {},
    private val onHook: () -> Unit = {},
    private val onKanzi: () -> Unit = {},
    private val onSnapshots: () -> Unit = {},
) {
    /**
     * 发布一轮快照。
     *
     * 顺序是 native、sentinel、remote、build、Hook、Kanzi、S2。构造期不调用。
     */
    fun publish() {
        onNative()
        media.ensureNative()
        onSentinel()
        sentinel()?.platform(!closed() && !paused() && featuresRoot() && featuresVehicle() && automatic())
        onRemote()
        remote.update()
        onBuild()
        val packet = composer.build(currentInput())
        onHook()
        val elementsWanted = elements.applyHook(packet, rootReady())
        onKanzi()
        media.updateKanzi(packet, elementsWanted && rootReady())
        onSnapshots()
        snapshots.publish(packet, clients.all().map { it.callback })
    }

    /**
     * 从各 owner 取本轮输入。
     *
     * getter 留到组装器对应字段才执行。元素需求再按暂停和 Root 开关过滤。
     */
    private fun currentInput(): ServiceSnapshotComposer.Input {
        val demand = { clients.elementDemand().takeIf { !paused() && featuresRoot() } }
        return ServiceSnapshotComposer.Input(
            vehicleDiscovery = { vehicle.discovery },
            vehiclePropertyCount = { vehicle.discoveredCount },
            vehicleSampledCount = { vehicle.sampledCount() },
            vehicleValidCount = { vehicle.validCount() },
            sentinel = { sentinel()?.snapshot() },
            rootReady = rootReady,
            automatic = automatic,
            vehicleWebEnabled = vehicleWebEnabled,
            features = features,
            clusterElementsRequested = { demand() != null },
            clusterElementRevision = { elements.revision },
            clusterMusicRequested = { demand()?.music == true },
            clusterLyricsRequested = { !paused() && demand()?.music == true },
            clusterElements = { demand()?.bundle() },
            foundation = foundation,
            status = status,
            media = { media.media() },
            navigation = { media.navigation() },
            visualMedia = { media.visualMedia() },
            vehicleKeys = { vehicle.keys() },
            vehicleScanning = { vehicle.scanning() },
            inputEpoch = { vehicle.inputEpoch },
            menuSequence = { vehicle.menuSequence },
            menuReceivedAt = { vehicle.menuReceivedAt },
            remoteState = { remote.state() },
            vehicleMode = vehicleMode,
            model = { vehicle.model() },
            artworkReady = { media.artworkReady() },
            artworkVersion = { media.artworkVersion() },
        )
    }
}
