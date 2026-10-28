package com.shilapi.xcertplay.network

/** 地区集合由产品持有；通用建组计划消费冻结的具体信道列表，不写系统国家码。 */
class P2pChannelPolicy(channels: List<Int>) {
    val channels: List<Int> = java.util.Collections.unmodifiableList(ArrayList(channels))
    init {
        require(this.channels.isNotEmpty() && this.channels.distinct().size == this.channels.size)
        require(this.channels.all { it in WifiP2pChannels.channels })
    }
    fun allows(channel: Int) = channel in channels
    internal fun plan(station: Int?, remembered: P2pCreationRequest?, manual: Int): List<P2pCreationRequest> =
        P2pStartupRecovery.plan(station, remembered, manual, allowedChannels = channels)
}
