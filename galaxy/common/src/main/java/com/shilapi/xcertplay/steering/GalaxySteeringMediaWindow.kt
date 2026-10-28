package com.shilapi.xcertplay.steering

import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * 方控与原车媒体中心两条入口共享的媒体命令窗口（M05：跨源250ms）。
 *
 * 边界：只处理“同一次实体按键被方控HardKey与原车媒体中心各回送一次”的重复，同一入口的
 * 连按与不同动作原样放行；电话接听/挂断与Siri没有第二个回送入口，不进入此窗口。
 * 记录时机：窗口只在命令真的交给手机后才写入，被拒绝或未发出的命令不占窗口，
 * 避免把紧随其后的合法命令误判成重复。
 */
internal object GalaxySteeringMediaWindow {
    /** 方控HardKey（Autolink）入口来源。 */
    const val STEERING = "steering-hardkey"

    /** 原车媒体中心/窗口按键回送CarPlay的来源。 */
    const val VEHICLE = "oem-media-center"

    /** 进程内唯一窗口：方控出口与原车入口必须共享同一份记录才有跨源语义。 */
    internal val deduplicator = SteeringMediaCommandDeduplicator()

    /** 方控动作到CarPlay媒体索引；PLAY_PAUSE不在学习动作表内，播放/暂停各自显式映射。 */
    fun index(action: SteeringAction): Int? = when (action) {
        SteeringAction.PLAY -> CarPlayMediaButton.PLAY
        SteeringAction.PAUSE -> CarPlayMediaButton.PAUSE
        SteeringAction.PREVIOUS -> CarPlayMediaButton.PREVIOUS
        SteeringAction.NEXT -> CarPlayMediaButton.NEXT
        else -> null
    }

    /** 媒体索引反查方控动作；非四类媒体索引返回null，由调用方按原逻辑处理。 */
    fun action(index: Int): SteeringAction? = when (index) {
        CarPlayMediaButton.PLAY -> SteeringAction.PLAY
        CarPlayMediaButton.PAUSE -> SteeringAction.PAUSE
        CarPlayMediaButton.PREVIOUS -> SteeringAction.PREVIOUS
        CarPlayMediaButton.NEXT -> SteeringAction.NEXT
        else -> null
    }

    /**
     * 原车媒体中心入口：窗口内刚被方控发过的同一动作不再转发。
     * `send` 返回false时说明命令没有真正发出（无会话/队列拒绝），此时不占用窗口。
     */
    fun acceptVehicleMedia(index: Int, clock: () -> Long, send: () -> Boolean): Boolean {
        val action = action(index) ?: return send()
        val now = clock()
        if (!deduplicator.allowed(action, VEHICLE, now)) return false
        if (!send()) return false
        deduplicator.accept(action, VEHICLE, now)
        return true
    }
}